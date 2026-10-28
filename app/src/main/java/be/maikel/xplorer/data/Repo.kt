package be.maikel.xplorer.data

import android.content.Context
import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.Ledger
import be.maikel.xplorer.domain.Op
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tout ce que l'app sait, lu depuis la base locale (fonctionne hors connexion). */
data class Snapshot(
    val hasKey: Boolean,
    val ops: List<Op>,
    val balances: Map<String, Double>,
    val prices: Map<String, Double>,
    val change24: Map<String, Double>,
    val volume24Eur: Map<String, Double>,
    val daily: Map<String, List<Candle>>,
    val usdEurNow: Double,
    val usdEurDaily: Map<Long, Double>,
    val fills: List<FillEntity>,
    val settings: Settings,
    val lastSync: Long?,
    val pricesAt: Long?,
    val syncedAccounts: Int,
    val totalAccounts: Int,
)

data class SyncReport(val newOps: Int, val errors: List<String>)

class Repo(private val ctx: Context) {
    val dao = XpDb.get(ctx).dao()
    val secrets = SecretStore(ctx)
    val prefs = Prefs(ctx)
    private val api = CoinbaseApi()
    private val mutex = Mutex()

    @Volatile private var products: Map<String, CoinbaseApi.Product> = emptyMap()
    @Volatile private var productsAt = 0L
    @Volatile var eurPrices: Map<String, Double> = emptyMap(); private set
    @Volatile var h4: Map<String, List<Candle>> = emptyMap(); private set

    companion object {
        const val DAY = 86_400_000L
        const val FX = "FX_USD"
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(ctx.applicationContext).also { inst = it } }
    }

    fun hasKey() = secrets.has()

    suspend fun connect(name: String, pem: String) {
        val key = CoinbaseAuth.parse(name, pem)
        api.accounts(key) // échoue si la clé est refusée : rien n'est enregistré
        secrets.save(key)
    }

    /** Supprime immédiatement la clé et toutes les données Coinbase du téléphone. */
    suspend fun disconnect() {
        secrets.clear()
        dao.wipeOps(); dao.wipeAccounts(); dao.wipeFills(); dao.clearBalances(); dao.wipeMeta()
    }

    private suspend fun ensureProducts() {
        if (System.currentTimeMillis() - productsAt < 6 * 3600_000L && products.isNotEmpty()) return
        try {
            products = api.products().associateBy { it.id }
            productsAt = System.currentTimeMillis()
        } catch (e: Exception) { /* on garde l'ancien */ }
    }

    /** Meilleure paire pour un symbole : EUR, sinon USDC, sinon USD. */
    fun productFor(sym: String): CoinbaseApi.Product? =
        listOf("EUR", "USDC", "USD").firstNotNullOfOrNull { q -> products["$sym-$q"]?.takeIf { it.online } }
            ?: listOf("EUR", "USDC", "USD").firstNotNullOfOrNull { q -> products["$sym-$q"] }

    fun usdEurNow(): Double = eurPrices["USDC"] ?: eurPrices["USD"] ?: products["USDC-EUR"]?.price ?: 0.92

    private fun quoteToEur(quote: String): Double = if (quote == "EUR" || quote == "EURC") 1.0 else usdEurNow()

    suspend fun sync(progress: (String) -> Unit = {}): SyncReport = mutex.withLock {
        val errors = mutableListOf<String>()
        var newOps = 0
        val s = prefs.load()
        progress("Prix du marché…")
        try { eurPrices = api.eurPrices(); dao.putMeta(MetaEntity("pricesAt", System.currentTimeMillis().toString())) } catch (e: Exception) { errors += "Prix : ${e.message}" }
        ensureProducts()

        val key = secrets.load()
        val held = mutableSetOf<String>()
        if (key != null) {
            try {
                progress("Comptes Coinbase…")
                val accounts = api.accounts(key)
                val bal = HashMap<String, Double>()
                accounts.forEach { bal[it.currency] = (bal[it.currency] ?: 0.0) + it.balance }
                dao.clearBalances()
                dao.putBalances(bal.filter { it.value != 0.0 }.map { BalanceEntity(it.key, it.value) })
                held += bal.filter { it.value > 0 && !Ledger.isCash(it.key) }.keys

                var fillBases = dao.fills().map { it.productId.substringBefore('-') }.toSet()
                try {
                    progress("Exécutions Advanced…")
                    val f = api.fills(key)
                    dao.putFills(f.map { FillEntity(it.id, it.t, it.productId, it.buy, it.price, it.size, it.commission, it.orderId) })
                    fillBases = fillBases + f.map { it.productId.substringBefore('-') }
                } catch (e: Exception) { errors += "Exécutions : ${e.message}" }

                // Comptes à lire : ceux qui ont un solde, ceux qui ont bougé, et les cryptos suivies.
                val wanted = s.targets.keys + s.watchlist + fillBases + setOf("EUR", "EURC", "USDC", "USD")
                val chosen = accounts.filter {
                    it.balance != 0.0 || it.currency in wanted || it.updatedAt - it.createdAt > DAY
                }.sortedByDescending { if (it.balance != 0.0) 1 else 0 }.take(80)
                dao.putAccounts(accounts.map { a -> AccountEntity(a.id, a.currency, a.balance, chosen.any { it.id == a.id }) })
                for ((i, a) in chosen.withIndex()) {
                    progress("Historique ${a.currency} (${i + 1}/${chosen.size})…")
                    try {
                        val known = dao.opIds(a.id).toHashSet()
                        val fresh = api.transactions(key, a.id, a.currency) { it in known }
                        val fixed = fresh.map { op ->
                            if (op.eur.isNaN()) op.copy(eur = op.amount * (Ledger.unitEur(op.cur, eurPrices, usdEurNow()) ?: 0.0)) else op
                        }
                        if (fixed.isNotEmpty()) dao.putOps(fixed.map { OpEntity.of(it) })
                        newOps += fixed.size
                    } catch (e: Exception) { errors += "${a.currency} : ${e.message}" }
                }
                dao.putMeta(MetaEntity("lastSync", System.currentTimeMillis().toString()))
            } catch (e: Exception) { errors += e.message ?: "Erreur Coinbase" }
        }

        // Historique journalier des prix (en euros).
        val syms = (held + s.targets.keys + s.watchlist + "BTC").filter { !Ledger.isCash(it) }.toSet()
        progress("Historique des prix…")
        try { syncDaily(FX) } catch (e: Exception) { errors += "Taux USD/EUR : ${e.message}" }
        for (sym in syms) {
            try { syncDaily(sym) } catch (e: Exception) { errors += "Prix $sym : ${e.message}" }
        }
        val fourH = HashMap<String, List<Candle>>()
        for (sym in (held + s.targets.keys).toSet()) {
            try { fourH[sym] = intraday(sym, CoinbaseApi.Gran.H4) } catch (e: Exception) { }
        }
        h4 = fourH
        SyncReport(newOps, errors)
    }

    private suspend fun syncDaily(sym: String) {
        val now = System.currentTimeMillis()
        val last = dao.lastDay(sym)
        val startDay = if (last != null) last - 2 else now / DAY - 1095
        val endSec = now / 1000
        val startSec = startDay * 86_400
        if (sym == FX) {
            val p = products["USDC-EUR"] ?: return
            val c = api.candles(p.id, CoinbaseApi.Gran.D1, startSec, endSec)
            dao.putDaily(c.map { DailyEntity(FX, it.t / DAY, it.open, it.high, it.low, it.close, it.volume) })
            return
        }
        val p = productFor(sym) ?: return
        val raw = api.candles(p.id, CoinbaseApi.Gran.D1, startSec, endSec)
        val fx = if (p.quote == "EUR") emptyMap() else dao.daily(FX).associate { it.day to it.c }
        val now0 = usdEurNow()
        dao.putDaily(raw.map {
            val d = it.t / DAY
            val k = if (p.quote == "EUR") 1.0 else fx[d] ?: now0
            DailyEntity(sym, d, it.open * k, it.high * k, it.low * k, it.close * k, it.volume)
        })
    }

    /** Bougies intrajournalières à la demande (non stockées), converties en euros. */
    suspend fun intraday(sym: String, g: CoinbaseApi.Gran, count: Int = 300): List<Candle> {
        ensureProducts()
        val p = productFor(sym) ?: return emptyList()
        val end = System.currentTimeMillis() / 1000
        val raw = api.candles(p.id, g, end - g.seconds * count, end)
        val k = quoteToEur(p.quote)
        return raw.map { it.copy(open = it.open * k, high = it.high * k, low = it.low * k, close = it.close * k) }
    }

    suspend fun dailyCandles(sym: String): List<Candle> =
        dao.daily(sym).map { Candle(it.day * DAY, it.o, it.h, it.l, it.c, it.v) }

    suspend fun snapshot(): Snapshot {
        val s = prefs.load()
        val balances = dao.balances().associate { it.cur to it.qty }
        val fills = dao.fills()
        var ops = dao.ops().map { it.toOp() }
        // Si Coinbase ne renvoie pas les exécutions Advanced dans l'historique, on les reconstitue.
        if (ops.none { it.type == "advanced_trade_fill" } && fills.isNotEmpty()) ops = ops + fillsToOps(fills)
        val syms = (balances.keys + s.targets.keys + s.watchlist + "BTC").filter { !Ledger.isCash(it) }.toSet()
        val daily = syms.associateWith { dailyCandles(it) }
        val prices = HashMap<String, Double>()
        val ch = HashMap<String, Double>(); val vol = HashMap<String, Double>()
        for (sym in syms) {
            val pr = productFor(sym)
            val p = eurPrices[sym] ?: pr?.let { it.price * quoteToEur(it.quote) } ?: daily[sym]?.lastOrNull()?.close
            if (p != null && p > 0) prices[sym] = p
            pr?.change24?.let { ch[sym] = it }
            pr?.let { vol[sym] = it.volume24 * (p ?: 0.0) }
        }
        val fx = dao.daily(FX).associate { it.day to it.c }
        val acc = dao.accounts()
        return Snapshot(
            secrets.has(), ops, balances, prices, ch, vol, daily, usdEurNow(), fx, fills, s,
            dao.meta("lastSync")?.toLongOrNull(), dao.meta("pricesAt")?.toLongOrNull(),
            acc.count { it.synced }, acc.size,
        )
    }

    private fun fillsToOps(fills: List<FillEntity>): List<Op> = fills.flatMap { f ->
        val base = f.productId.substringBefore('-'); val quote = f.productId.substringAfter('-')
        val k = quoteToEur(quote)
        val gross = f.price * f.size
        val sign = if (f.buy) 1.0 else -1.0
        listOf(
            Op("fill:${f.id}", f.t, "advanced_trade_fill", base, sign * f.size, sign * gross * k, f.commission, quote, f.price, quote, f.productId, f.orderId),
            Op("fillq:${f.id}", f.t, "advanced_trade_fill", quote, -sign * gross - f.commission, -sign * gross * k, 0.0, quote, f.price, quote, f.productId, f.orderId),
        )
    }
}
