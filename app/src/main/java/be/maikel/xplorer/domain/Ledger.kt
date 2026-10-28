package be.maikel.xplorer.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Une opération Coinbase normalisée (une ligne de compte).
 * [amount] : quantité signée de [cur] (positive = entrée sur le compte).
 * [eur] : contre-valeur signée en euros au moment de l'opération, telle que donnée par Coinbase.
 * [fee] / [feeCur] : frais déclarés par Coinbase sur cette ligne, s'ils existent.
 */
data class Op(
    val id: String,
    val t: Long,
    val type: String,
    val cur: String,
    val amount: Double,
    val eur: Double,
    val fee: Double = 0.0,
    val feeCur: String = "",
    val unitPrice: Double = Double.NaN,
    val priceCur: String = "",
    val productId: String = "",
    val orderId: String = "",
    val title: String = "",
    val accountId: String = "",
)

/** Une exécution d'achat ou de vente, telle qu'affichée sur les graphiques. */
data class Trade(
    val t: Long,
    val symbol: String,
    val buy: Boolean,
    val qty: Double,
    val unitEur: Double,
    val totalEur: Double,
    val feeEur: Double,
    val type: String,
    val ref: String,
    val realizedEur: Double?,
)

data class Position(
    val symbol: String,
    val qty: Double,
    val price: Double,
    val value: Double,
    /** Quantité dont l'origine est connue dans l'historique. */
    val trackedQty: Double,
    /** Prix de revient total (euros, frais inclus) de la quantité suivie restante. */
    val costBasis: Double,
    val avgCost: Double,
    val realized: Double,
    val fees: Double,
    val rewardsQty: Double,
    val externalInQty: Double,
    /** Quantité détenue sans historique d'acquisition (prix de revient inconnu). */
    val unknownQty: Double,
    val unrealized: Double?,
    val weight: Double,
    val priceKnown: Boolean,
)

data class LedgerResult(
    val positions: List<Position>,
    val cashEur: Double,
    val cryptoEur: Double,
    val totalEur: Double,
    val capitalIn: Double,
    val capitalOut: Double,
    val externalNetEur: Double,
    val fees: Double,
    val realized: Double,
    val unrealized: Double,
    val gain: Double,
    val trades: List<Trade>,
    val issues: List<String>,
    val opCount: Int,
) {
    val capitalNet get() = capitalIn - capitalOut
}

object Ledger {
    val CASH = setOf("EUR", "EURC", "USD", "USDC", "USDT", "GBP")
    val EURLIKE = setOf("EUR", "EURC")

    val TRADE_TYPES = setOf("buy", "sell", "trade", "advanced_trade_fill", "retail_simple_dust", "exchange_trade")
    val REWARD_TYPES = setOf(
        "earn_payout", "staking_reward", "inflation_reward", "interest", "incentives_rewards_payout",
        "subscription_rebate", "coinbase_earn", "staking_reward_payout",
    )
    val CLAWBACK_TYPES = setOf("clawback", "incentives_shared_clawback")
    val EXTERNAL_TYPES = setOf("send", "receive", "request")
    val INTERNAL_TYPES = setOf(
        "transfer", "staking_transfer", "unstaking_transfer", "pro_deposit", "pro_withdrawal",
        "exchange_deposit", "exchange_withdrawal", "intx_deposit", "intx_withdrawal", "vault_withdrawal",
        "wrap_asset", "unwrap_asset", "derivatives_settlement", "unsupported_asset_recovery",
    )
    val FIAT_FLOW = setOf("fiat_deposit", "fiat_withdrawal")

    fun isCash(cur: String) = cur in CASH

    /** Valeur en euros d'une unité de [cur] (EUR = 1, stablecoins dollar = [usdEur]). */
    fun unitEur(cur: String, prices: Map<String, Double>, usdEur: Double): Double? = when (cur) {
        "EUR", "EURC" -> 1.0
        "USD", "USDC", "USDT" -> usdEur
        else -> prices[cur]
    }

    private class Book {
        var qty = 0.0
        var cost = 0.0
        var realized = 0.0
        var fees = 0.0
        var rewards = 0.0
        var extIn = 0.0
    }

    fun compute(
        ops: List<Op>,
        balances: Map<String, Double>,
        prices: Map<String, Double>,
        usdEur: Double,
    ): LedgerResult {
        val sorted = ops.sortedWith(compareBy({ it.t }, { it.id }))
        val books = HashMap<String, Book>()
        val trades = mutableListOf<Trade>()
        val issues = mutableListOf<String>()
        var capIn = 0.0; var capOut = 0.0; var extNet = 0.0; var fees = 0.0
        var unknownTypes = 0

        fun feeEur(op: Op): Double {
            if (op.fee == 0.0) return 0.0
            val u = when {
                op.feeCur.isEmpty() || op.feeCur in EURLIKE -> 1.0
                op.feeCur in setOf("USD", "USDC", "USDT") -> usdEur
                else -> prices[op.feeCur] ?: 0.0
            }
            return abs(op.fee) * u
        }

        for (op in sorted) {
            when {
                op.type in FIAT_FLOW && op.cur in EURLIKE -> {
                    if (op.amount > 0) capIn += op.amount else capOut += -op.amount
                }
                op.type in FIAT_FLOW && op.cur in CASH -> {
                    val e = if (op.eur != 0.0) op.eur else op.amount * usdEur
                    if (e > 0) capIn += e else capOut += -e
                }
                isCash(op.cur) -> {
                    // Mouvements de cash liés aux achats/ventes : le côté crypto porte le calcul.
                    if (op.type !in TRADE_TYPES && op.type !in INTERNAL_TYPES && op.type !in REWARD_TYPES &&
                        op.type !in FIAT_FLOW && op.type !in EXTERNAL_TYPES) unknownTypes++
                    if (op.type in EXTERNAL_TYPES) extNet += if (op.eur != 0.0) op.eur else op.amount * (unitEur(op.cur, prices, usdEur) ?: 0.0)
                }
                else -> {
                    val b = books.getOrPut(op.cur) { Book() }
                    val q = op.amount
                    val f = feeEur(op)
                    val value = abs(op.eur)
                    when {
                        op.type in TRADE_TYPES -> {
                            fees += f; b.fees += f
                            if (q > 0) {
                                b.qty += q; b.cost += value + f
                                trades += Trade(op.t, op.cur, true, q, if (q != 0.0) value / q else Double.NaN, value, f, op.type, op.orderId.ifEmpty { op.id }, null)
                            } else {
                                val sold = -q
                                val avg = if (b.qty > 1e-12) b.cost / b.qty else Double.NaN
                                val covered = minOf(sold, max(b.qty, 0.0))
                                val proceeds = value - f
                                var pnl: Double? = null
                                if (covered > 0 && !avg.isNaN()) {
                                    val part = proceeds * covered / sold
                                    pnl = part - avg * covered
                                    b.realized += pnl
                                    b.cost -= avg * covered
                                }
                                b.qty -= sold
                                if (b.qty < 1e-12) { b.qty = max(b.qty, 0.0); if (b.qty == 0.0) b.cost = 0.0 }
                                trades += Trade(op.t, op.cur, false, sold, if (sold != 0.0) value / sold else Double.NaN, value, f, op.type, op.orderId.ifEmpty { op.id }, pnl)
                            }
                        }
                        op.type in REWARD_TYPES -> {
                            // Récompense : quantité gratuite (coût 0), c'est un gain.
                            if (q > 0) { b.qty += q; b.rewards += q } else reduce(b, -q)
                        }
                        op.type in CLAWBACK_TYPES -> reduce(b, abs(q))
                        op.type in EXTERNAL_TYPES -> {
                            // Transfert hors de Coinbase : ce n'est pas une performance.
                            extNet += op.eur
                            if (q > 0) { b.qty += q; b.cost += value; b.extIn += q } else reduce(b, -q)
                        }
                        op.type in INTERNAL_TYPES -> Unit
                        op.type in FIAT_FLOW -> Unit
                        else -> {
                            unknownTypes++
                            // Type inconnu : on suit la quantité au prix donné par Coinbase.
                            if (q > 0) { b.qty += q; b.cost += value } else reduce(b, -q)
                        }
                    }
                }
            }
        }
        if (unknownTypes > 0) issues += "$unknownTypes opération(s) d'un type non reconnu, comptée(s) au prix donné par Coinbase."

        // Positions à partir des soldes réels (source de vérité), complétées par l'historique.
        val symbols = (balances.keys.filter { !isCash(it) && (balances[it] ?: 0.0) > 0 } + books.keys.filter { (books[it]?.realized ?: 0.0) != 0.0 }).toSet()
        val raw = symbols.map { s ->
            val qty = balances[s] ?: 0.0
            val b = books[s] ?: Book()
            val p = prices[s]
            val tracked = max(b.qty, 0.0)
            val unknown = max(0.0, qty - tracked)
            val avg = if (tracked > 1e-12) b.cost / tracked else Double.NaN
            // Si la quantité suivie dépasse le solde (opérations manquantes), on ramène le coût au solde.
            val usedQty = minOf(qty, tracked)
            val basis = if (avg.isNaN()) 0.0 else avg * usedQty
            val value = if (p != null) qty * p else 0.0
            val unreal = if (p != null && usedQty > 0 && !avg.isNaN()) p * usedQty - basis else null
            if (unknown > qty * 0.005 && unknown * (p ?: 0.0) > 0.5) issues += "$s : ${fmtQty(unknown)} détenu(s) sans historique d'achat (prix de revient inconnu pour cette part)."
            if (tracked > qty * 1.005 && (tracked - qty) * (p ?: 0.0) > 0.5) issues += "$s : l'historique compte plus que ton solde (${fmtQty(tracked)} contre ${fmtQty(qty)}), une sortie manque."
            if (p == null && qty > 0) issues += "$s : prix introuvable, la valeur n'est pas comptée."
            Position(s, qty, p ?: Double.NaN, value, tracked, basis, avg, b.realized, b.fees, b.rewards, b.extIn, unknown, unreal, 0.0, p != null)
        }
        var cash = 0.0
        for ((cur, q) in balances) if (isCash(cur)) cash += q * (unitEur(cur, prices, usdEur) ?: 0.0)
        val crypto = raw.sumOf { it.value }
        val total = crypto + cash
        val positions = raw.map { it.copy(weight = if (crypto > 0) it.value / crypto else 0.0) }.sortedByDescending { it.value }
        val realized = books.values.sumOf { it.realized }
        val unrealized = positions.sumOf { it.unrealized ?: 0.0 }
        val gain = total - (capIn - capOut) - extNet
        if (sorted.isEmpty()) issues += "Aucune opération lue : le capital apporté et les gains ne peuvent pas être calculés."
        return LedgerResult(positions, cash, crypto, total, capIn, capOut, extNet, fees, realized, unrealized, gain,
            trades.sortedBy { it.t }, issues.distinct(), sorted.size)
    }

    private fun reduce(b: Book, q: Double) {
        if (q <= 0) return
        val avg = if (b.qty > 1e-12) b.cost / b.qty else 0.0
        val covered = minOf(q, max(b.qty, 0.0))
        b.cost -= avg * covered
        b.qty -= q
        if (b.qty <= 1e-12) { b.qty = max(b.qty, 0.0); if (b.qty == 0.0) b.cost = 0.0 }
    }

    fun fmtQty(q: Double): String {
        val d = when { q >= 100 -> 2; q >= 1 -> 4; else -> 8 }
        return "%.${d}f".format(java.util.Locale.FRANCE, q).trimEnd('0').trimEnd(',')
    }
}

/** Historique de la valeur du portefeuille, reconstruit à partir des soldes réels et des opérations. */
data class HistoryPoint(val t: Long, val value: Double, val capital: Double, val benchmark: Double, val twr: Double, val flow: Double)

data class Performance(
    val label: String,
    val gainEur: Double,
    val pct: Double,
)

data class PortfolioHistory(
    val points: List<HistoryPoint>,
    val missingPrices: Set<String>,
    val maxDrawdown: Double,
    val currentDrawdown: Double,
    val volatility: Double,
    val periods: List<Performance>,
)

object History {
    const val DAY = 86_400_000L

    fun dayOf(t: Long) = t / DAY

    /**
     * [daily] : clôtures journalières en euros par symbole, (jour -> prix).
     * Les quantités passées sont reconstruites en remontant le temps depuis les soldes actuels :
     * la fin de la courbe correspond toujours aux soldes réels.
     */
    fun build(
        ops: List<Op>,
        balances: Map<String, Double>,
        daily: Map<String, Map<Long, Double>>,
        usdEur: Map<Long, Double>,
        usdEurNow: Double,
        prices: Map<String, Double>,
        nowMs: Long,
        bench: String = "BTC",
    ): PortfolioHistory? {
        if (ops.isEmpty()) return null
        val sorted = ops.sortedBy { it.t }
        val firstDay = dayOf(sorted.first().t)
        val today = dayOf(nowMs)
        if (today - firstDay < 1) return null
        val missing = mutableSetOf<String>()

        // Quantité par devise à la fin de chaque jour : solde actuel moins ce qui est arrivé après.
        val curs = (balances.keys + sorted.map { it.cur }).toSet()
        val qty = HashMap<String, Double>()
        for (c in curs) qty[c] = balances[c] ?: 0.0
        val byDay = sorted.groupBy { dayOf(it.t) }
        val flowsByDay = HashMap<Long, Double>()
        for (op in sorted) {
            val isFlow = (op.type in Ledger.FIAT_FLOW && Ledger.isCash(op.cur)) || op.type in Ledger.EXTERNAL_TYPES
            if (isFlow) {
                val e = if (op.cur in Ledger.EURLIKE) op.amount else op.eur
                flowsByDay[dayOf(op.t)] = (flowsByDay[dayOf(op.t)] ?: 0.0) + e
            }
        }
        val days = (firstDay..today).toList()
        val qtyAtEnd = HashMap<Long, Map<String, Double>>()
        // on parcourt les jours à l'envers
        for (d in days.reversed()) {
            qtyAtEnd[d] = HashMap(qty)
            byDay[d]?.forEach { op -> qty[op.cur] = (qty[op.cur] ?: 0.0) - op.amount }
        }

        fun px(cur: String, d: Long): Double? {
            if (cur in Ledger.EURLIKE) return 1.0
            if (cur in setOf("USD", "USDC", "USDT")) return usdEur[d] ?: nearest(usdEur, d) ?: usdEurNow
            if (d == today) prices[cur]?.let { return it }
            val m = daily[cur] ?: return null
            return m[d] ?: nearest(m, d)
        }

        val points = mutableListOf<HistoryPoint>()
        var capital = 0.0
        var benchUnits = 0.0
        var twr = 1.0
        var prevV = 0.0
        for (d in days) {
            val q = qtyAtEnd[d] ?: emptyMap()
            var v = 0.0
            for ((c, n) in q) {
                if (n <= 1e-12) continue
                val p = px(c, d)
                if (p == null) { if (n > 0) missing += c; continue }
                v += n * p
            }
            val f = flowsByDay[d] ?: 0.0
            capital += f
            val bp = px(bench, d)
            if (bp != null && bp > 0) benchUnits = max(0.0, benchUnits + f / bp)
            if (prevV > 0.01) {
                val r = (v - f) / prevV
                if (r > 0 && r < 20) twr *= r
            }
            prevV = v
            points += HistoryPoint(d * DAY, v, capital, if (bp != null) benchUnits * bp else Double.NaN, twr, f)
        }
        val trimmed = points.dropWhile { it.value < 0.01 && it.capital < 0.01 }
        if (trimmed.size < 2) return null

        var peak = 0.0; var mdd = 0.0
        for (p in trimmed) { peak = max(peak, p.twr); if (peak > 0) mdd = minOf(mdd, p.twr / peak - 1) }
        val cur = trimmed.last().twr / (trimmed.maxOf { it.twr }) - 1
        val rets = trimmed.zipWithNext { a, b -> if (a.twr > 0) b.twr / a.twr - 1 else 0.0 }.takeLast(365)
        val vol = if (rets.size > 10) {
            val m = rets.average(); sqrt(rets.sumOf { (it - m) * (it - m) } / (rets.size - 1)) * sqrt(365.0)
        } else Double.NaN

        val periods = listOf("7 jours" to 7, "30 jours" to 30, "1 an" to 365, "Depuis le début" to Int.MAX_VALUE)
            .map { (label, n) -> period(trimmed, n, label) }
        return PortfolioHistory(trimmed, missing, mdd, cur, vol, periods)
    }

    /** Performance d'une période par la méthode de Dietz modifiée (les dépôts ne sont pas des gains). */
    fun period(p: List<HistoryPoint>, n: Int, label: String): Performance {
        val end = p.size - 1
        val s = if (n >= p.size) 0 else end - n
        val v0 = if (n >= p.size) 0.0 else p[s].value
        var flows = 0.0; var weighted = 0.0
        val from = if (n >= p.size) 0 else s + 1
        val span = (end - (from - 1)).coerceAtLeast(1)
        for (i in from..end) {
            flows += p[i].flow
            weighted += p[i].flow * (end - i).toDouble() / span
        }
        val gain = p[end].value - v0 - flows
        val denom = v0 + weighted
        return Performance(label, gain, if (abs(denom) > 1e-9) gain / denom else Double.NaN)
    }

    private fun nearest(m: Map<Long, Double>, d: Long): Double? {
        for (k in 1..7) { m[d - k]?.let { return it } }
        for (k in 1..3) { m[d + k]?.let { return it } }
        return null
    }
}
