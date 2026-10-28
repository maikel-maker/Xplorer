package be.maikel.xplorer.core

import be.maikel.xplorer.data.Snapshot
import be.maikel.xplorer.domain.AssetContext
import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.DecisionEngine
import be.maikel.xplorer.domain.History
import be.maikel.xplorer.domain.Indicators
import be.maikel.xplorer.domain.Ledger
import be.maikel.xplorer.domain.LedgerResult
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.Planner
import be.maikel.xplorer.domain.PortfolioHistory
import be.maikel.xplorer.domain.Radar
import be.maikel.xplorer.domain.Recommendation
import be.maikel.xplorer.domain.Rules
import be.maikel.xplorer.domain.Signal
import be.maikel.xplorer.domain.WeeklyPlan
import kotlin.math.max
import kotlin.math.sqrt

data class Scenario(val label: String, val valueAfter: Double, val loss: Double)

data class Health(
    val score: Int,
    val items: List<Pair<String, String>>,
    val tips: List<String>,
    val scenarios: List<Scenario>,
    val betas: Map<String, Double>,
)

/** Résultat complet d'une analyse, prêt pour l'affichage. */
data class Analysis(
    val snap: Snapshot,
    val ledger: LedgerResult,
    val history: PortfolioHistory?,
    val recs: Map<String, Recommendation>,
    val plan: WeeklyPlan,
    val signals: List<Signal>,
    val health: Health,
    val capital: Double,
    val capitalManual: Boolean,
    val gain: Double,
    val gainPct: Double,
    val day: Pair<Double, Double>,
    val issues: List<String>,
)

object Analyzer {

    fun run(s: Snapshot, h4: Map<String, List<Candle>>, budget: Double? = null, now: Long = System.currentTimeMillis()): Analysis {
        val st = s.settings
        val ledger = Ledger.compute(s.ops, s.balances, s.prices, s.usdEurNow)
        val dailyClose = s.daily.mapValues { (_, c) -> c.associate { History.dayOf(it.t) to it.close } }
        val history = try {
            History.build(s.ops, s.balances, dailyClose, s.usdEurDaily, s.usdEurNow, s.prices, now)
        } catch (e: Exception) { null }

        val capitalManual = st.capitalOverride != null
        val capital = st.capitalOverride ?: ledger.capitalNet
        val gain = ledger.totalEur - capital - ledger.externalNetEur
        val gainPct = if (capital > 0) gain / capital else Double.NaN

        val values = ledger.positions.associate { it.symbol to it.value }
        val weights = ledger.positions.associate { it.symbol to it.weight }
        val avg = ledger.positions.associate { it.symbol to it.avgCost }
        val universe = (st.targets.keys + ledger.positions.filter { it.value > 1 }.map { it.symbol } + st.watchlist).toSet()
        val rules = Rules(geodBase = st.geodBase, geodMultiple = st.geodMultiple)
        val recs = universe.associateWith { sym ->
            DecisionEngine.evaluate(
                sym, s.daily[sym] ?: emptyList(), h4[sym], s.prices[sym] ?: Double.NaN,
                AssetContext(sym, (values[sym] ?: 0.0) > 1.0, weights[sym] ?: 0.0, st.targets[sym] ?: 0.0, st.maxFor(sym), avg[sym] ?: Double.NaN),
                rules, now,
            )
        }
        val plan = Planner.plan(
            budget ?: st.weeklyBudget, recs, values, st.targets,
            st.targets.keys.associateWith { st.maxFor(it) }, ledger.cryptoEur,
            if (s.hasKey) ledger.cashEur else null,
        )
        val signals = universe.flatMap { sym ->
            Radar.scan(sym, s.daily[sym] ?: emptyList(), s.prices[sym] ?: Double.NaN, weights[sym] ?: 0.0, st.maxFor(sym), s.volume24Eur[sym])
        }
        val dayChange = ledger.positions.sumOf { p -> s.change24[p.symbol]?.let { c -> p.value - p.value / (1 + c) } ?: 0.0 }
        val dayPct = if (ledger.cryptoEur - dayChange > 0) dayChange / (ledger.cryptoEur - dayChange) else Double.NaN

        val issues = ledger.issues.toMutableList()
        if (s.hasKey && s.totalAccounts > s.syncedAccounts)
            issues += "${s.syncedAccounts} comptes Coinbase lus sur ${s.totalAccounts} (les comptes vides et inactifs sont ignorés)."
        history?.missingPrices?.takeIf { it.isNotEmpty() }?.let { issues += "Pas d'historique de prix pour ${it.joinToString()} : exclus de la courbe passée." }
        if (capitalManual) issues += "Capital apporté corrigé à la main : ${Money.eur(capital, 0)} (calcul automatique : ${Money.eur(ledger.capitalNet, 0)})."
        if (ledger.externalNetEur != 0.0) issues += "Transferts de cryptos depuis ou vers l'extérieur : ${Money.signedEur(ledger.externalNetEur, 0)} (valeur à la date du transfert), exclus des gains."

        return Analysis(s, ledger, history, recs, plan, signals, health(s, ledger, history), capital, capitalManual, gain, gainPct, dayChange to dayPct, issues)
    }

    private fun health(s: Snapshot, l: LedgerResult, h: PortfolioHistory?): Health {
        val items = mutableListOf<Pair<String, String>>()
        val tips = mutableListOf<String>()
        var score = 100.0
        val top = l.positions.maxByOrNull { it.weight }
        val hhi = l.positions.sumOf { it.weight * it.weight }
        if (top != null) {
            items += "Plus grosse position" to "${top.symbol} ${Money.pct(top.weight)}"
            if (top.weight > 0.6) { score -= 20; tips += "${top.symbol} dépasse 60 % de tes cryptos : une chute de ce seul actif pèse lourd." }
        }
        items += "Diversification" to (when { hhi < 0.25 -> "bonne"; hhi < 0.45 -> "moyenne"; else -> "faible" } + " (${l.positions.size} actifs)")
        if (hhi >= 0.45) score -= 10

        // Sensibilité au Bitcoin : bêta sur 90 jours de rendements journaliers.
        val btc = s.daily["BTC"]?.takeLast(91)?.map { it.close }
        val betas = HashMap<String, Double>()
        if (btc != null && btc.size > 30) {
            val rb = btc.zipWithNext { a, b -> b / a - 1 }
            for (p in l.positions) {
                val c = s.daily[p.symbol]?.takeLast(91)?.map { it.close } ?: continue
                if (c.size != btc.size) continue
                val ra = c.zipWithNext { a, b -> b / a - 1 }
                val mb = rb.average(); val ma = ra.average()
                val cov = ra.indices.sumOf { (ra[it] - ma) * (rb[it] - mb) }
                val vb = rb.sumOf { (it - mb) * (it - mb) }
                if (vb > 0) betas[p.symbol] = cov / vb
            }
            val beta = l.positions.sumOf { (betas[it.symbol] ?: 1.0) * it.weight }
            items += "Sensibilité au Bitcoin" to "bêta ${"%.2f".format(beta)} (estimé sur 90 jours)"
        }
        if (h != null) {
            items += "Pire baisse historique" to Money.pct(h.maxDrawdown)
            if (!h.volatility.isNaN()) items += "Volatilité annuelle" to Money.pct(h.volatility)
            if (h.maxDrawdown < -0.5) { score -= 15; tips += "Ton portefeuille a déjà perdu plus de la moitié à un moment : garde des montants que tu peux laisser des années." }
        }
        val cashShare = if (l.totalEur > 0) l.cashEur / l.totalEur else 0.0
        items += "Liquidités" to "${Money.eur(l.cashEur, 0)} (${Money.pct(cashShare)})"
        val unknown = l.positions.count { it.unknownQty > 0 && it.unknownQty * (if (it.price.isNaN()) 0.0 else it.price) > 1 }
        items += "Qualité des données" to if (l.issues.isEmpty()) "complète" else "${l.issues.size} point(s) à vérifier"
        if (unknown > 0) { score -= 10; tips += "Prix de revient inconnu pour $unknown actif(s) : ajoute-le dans Réglages › Corrections." }
        if (l.positions.any { s.settings.maxFor(it.symbol) < it.weight }) { score -= 10; tips += "Une position dépasse ta limite d'exposition." }

        val crypto = l.cryptoEur
        val scenarios = mutableListOf<Scenario>()
        for (d in listOf(0.1, 0.2, 0.4, 0.6)) scenarios += Scenario("Toutes tes cryptos −${(d * 100).toInt()} %", l.totalEur - crypto * d, -crypto * d)
        if (betas.isNotEmpty()) for (d in listOf(0.2, 0.4)) {
            val loss = l.positions.sumOf { it.value * max(0.0, betas[it.symbol] ?: 1.0) * d }
            scenarios += Scenario("Bitcoin −${(d * 100).toInt()} % (selon les bêtas)", l.totalEur - loss, -loss)
        }
        return Health(score.coerceIn(0.0, 100.0).toInt(), items, tips, scenarios, betas)
    }

    /** Volatilité annuelle d'une série journalière (pour l'affichage d'une crypto). */
    fun annualVol(c: List<Candle>): Double = Indicators.historicalVolatility(c.map { it.close }.toDoubleArray(), 30).let { if (it.isNaN()) it else it * sqrt(1.0) }
}
