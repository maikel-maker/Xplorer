package be.maikel.xplorer.core

import be.maikel.xplorer.data.Snapshot
import be.maikel.xplorer.domain.Arena
import be.maikel.xplorer.domain.AssetContext
import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.DecisionEngine
import be.maikel.xplorer.domain.History
import be.maikel.xplorer.domain.Indicators
import be.maikel.xplorer.domain.Ledger
import be.maikel.xplorer.domain.LedgerResult
import be.maikel.xplorer.domain.DcaJournal
import be.maikel.xplorer.domain.Milestones
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.PortfolioHistory
import be.maikel.xplorer.domain.Radar
import be.maikel.xplorer.domain.Recommendation
import be.maikel.xplorer.domain.Rules
import be.maikel.xplorer.domain.Signal
import be.maikel.xplorer.domain.SmartDca
import be.maikel.xplorer.domain.SwingAlgo
import be.maikel.xplorer.domain.Achievements
import be.maikel.xplorer.domain.PlanLine
import be.maikel.xplorer.domain.Action
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
    val sinceStart: Boolean = false,
    val startValue: Double? = null,
    val lifetimeGain: Double = 0.0,
    /** DCA intelligent (moteur principal du plan de la semaine). */
    val dca: SmartDca.Plan? = null,
    val mayer: Map<String, Double?> = emptyMap(),
    val backtests: List<SmartDca.Backtest> = emptyList(),
    /** Semaine du plan et indication si le budget est celui des réglages (seul ce cas fait évoluer la réserve). */
    val dcaWeek: Long = 0,
    val dcaDefaultBudget: Boolean = true,
    val dcaCashWarning: String? = null,
    val achievements: Achievements.Summary? = null,
    /** Algorithme « marché mixte » simulé sur l'historique de chaque crypto. */
    val swing: Map<String, SwingAlgo.Result> = emptyMap(),
    /** SnipeX : débat des agents par crypto, répartition de la semaine et test sur le passé. */
    val arena: Map<String, Arena.Debate> = emptyMap(),
    val arenaPlan: Arena.Plan? = null,
    val arenaBacktest: Arena.Backtest? = null,
    /** Paliers réalistes (1 ETH, 1 000 € de cryptos…). */
    val milestones: Milestones.Summary? = null,
    /** Journal du DCA : achats réels semaine par semaine, frais. */
    val journal: DcaJournal.Summary? = null,
    /** Variation du Bitcoin sur 7 jours (pour l'alerte « ne panique pas »). */
    val btcWeek: Double? = null,
)

/** Nombre de lundis dans le mois de [ms] (4 ou 5). */
fun mondaysInMonth(ms: Long): Int {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms; set(java.util.Calendar.DAY_OF_MONTH, 1) }
    val days = c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    var n = 0
    for (d in 1..days) { c.set(java.util.Calendar.DAY_OF_MONTH, d); if (c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.MONDAY) n++ }
    return n
}

/** Budget de la semaine : le budget du mois réparti sur ses lundis, arrondi à l'euro. */
fun weeklyFromMonthly(monthly: Double, ms: Long): Double = kotlin.math.floor(monthly / mondaysInMonth(ms))

/** Semaines commençant le lundi (le 1er janvier 1970 était un jeudi). */
fun weekIndex(ms: Long): Long = Math.floorDiv(ms / 86_400_000L + 3, 7L)

/** Réserve « creux » valable pour la semaine [week], en appliquant le changement de semaine s'il n'est pas encore enregistré. */
fun reserveFor(st: be.maikel.xplorer.data.Settings, week: Long): Double = when (st.dcaWeek) {
    null -> 0.0
    week -> max(0.0, st.dcaReserveStart)
    else -> max(0.0, st.dcaReserveStart + st.dcaPendingDelta)
}

object Analyzer {

    fun run(s: Snapshot, h4: Map<String, List<Candle>>, budget: Double? = null, now: Long = System.currentTimeMillis(), declaredWeeks: Int = 0): Analysis {
        val st = s.settings
        val ledger = Ledger.compute(s.ops, s.balances, s.prices, s.usdEurNow)
        val dailyClose = s.daily.mapValues { (_, c) -> c.associate { History.dayOf(it.t) to it.close } }
        val history = try {
            History.build(s.ops, s.balances, dailyClose, s.usdEurDaily, s.usdEurNow, s.prices, now, startMs = st.startDate)
        } catch (e: Exception) { null }

        val capitalManual = st.capitalOverride != null
        // Avec un point de départ : capital = valeur du portefeuille ce jour-là + virements (et transferts) depuis.
        val sinceStart = st.startDate != null && history?.startValue != null
        val autoCapital = if (sinceStart) history!!.points.last().capital else ledger.capitalNet
        val capital = st.capitalOverride ?: autoCapital
        val gain = ledger.totalEur - capital - (if (sinceStart || capitalManual) 0.0 else ledger.externalNetEur)
        val lifetimeGain = ledger.totalEur - ledger.capitalNet - ledger.externalNetEur
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
        // SnipeX : l'arène des agents répartit le budget de la semaine, en entier, entre les cryptos du plan.
        val week = weekIndex(now)
        // 1 000 € par mois, pas un euro de plus : le mois est divisé par son nombre de lundis (4 → 250 €, 5 → 200 €).
        val weekly = budget ?: weeklyFromMonthly(st.monthlyBudget, now)
        val mayer = universe.associateWith { sym -> SmartDca.mayer(s.daily[sym] ?: emptyList(), s.prices[sym] ?: Double.NaN, now) }
        val arena = universe.associateWith { sym -> Arena.debate(sym, s.daily[sym] ?: emptyList(), s.prices[sym] ?: Double.NaN) }
        val arenaPlan = Arena.plan(
            weekly, arena, st.targets, values,
            st.targets.keys.associateWith { st.maxFor(it) }, ledger.cryptoEur,
        )
        val cashWarning = if (s.hasKey && ledger.cashEur + 0.5 < arenaPlan.total)
            "Tu as ${Money.eur(ledger.cashEur, 0)} disponibles sur Coinbase : vire d'abord ${Money.eur(arenaPlan.total - ledger.cashEur, 0)}." else null
        val plan = WeeklyPlan(
            arenaPlan.weekly,
            arenaPlan.lines.map { l -> PlanLine(l.symbol, l.eur, l.note, if (l.eur > 0) Action.ACHETER else Action.ATTENDRE) },
            0.0, arenaPlan.headline, cashWarning,
        )
        val milestones = Milestones.compute(
            (st.targets.keys + ledger.positions.filter { it.value > 1 }.map { it.symbol }).filter { it != "USDC" && it != "EURC" && it != "EUR" },
            ledger.positions.associate { it.symbol to it.qty },
            s.prices, ledger.cryptoEur,
            arenaPlan.lines.associate { it.symbol to it.eur }, arenaPlan.total,
        )
        val journal = DcaJournal.compute(ledger.trades, st.startDate ?: (now - 84 * 86_400_000L), now) { w ->
            weeklyFromMonthly(st.monthlyBudget, w + 43_200_000L)
        }
        val btcWeek = s.daily["BTC"]?.takeIf { it.size > 8 }?.let { d ->
            val nowP = s.prices["BTC"] ?: d.last().close
            val ago = d[d.size - 8].close
            if (ago > 0 && nowP > 0) nowP / ago - 1 else null
        }
        val arenaBacktest = try { Arena.backtest(s.daily, st.targets, st.weeklyBudget) } catch (e: Exception) { null }
        // Achats « malins » : faits quand le prix était sous sa moyenne 200 jours ce jour-là.
        val smartBuys = ledger.trades.count { tr ->
            if (!tr.buy) return@count false
            val d = s.daily[tr.symbol] ?: return@count false
            val i = d.indexOfLast { it.t <= tr.t }
            i >= 199 && tr.unitEur > 0 && tr.unitEur < d.subList(i - 199, i + 1).sumOf { it.close } / 200
        }
        val beats = history?.points?.lastOrNull()?.let { p -> if (p.benchmark.isNaN()) null else p.value > p.benchmark }
        val achievements = Achievements.compute(
            ledger.trades.filter { st.startDate == null || it.t >= st.startDate }, declaredWeeks, capital,
            history?.maxDrawdown, beats, weights, st.targets, smartBuys, now,
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
        history?.takeIf { it.jumps > 0 }?.let { issues += "${it.jumps} jour(s) incohérent(s) dans l'historique reconstruit, exclus du calcul de performance." }
        if (capitalManual) issues += "Capital apporté corrigé à la main : ${Money.eur(capital, 0)} (calcul automatique : ${Money.eur(autoCapital, 0)})."
        if (ledger.externalNetEur != 0.0) issues += "Transferts de cryptos depuis ou vers l'extérieur : ${Money.signedEur(ledger.externalNetEur, 0)} (valeur à la date du transfert), exclus des gains."

        return Analysis(s, ledger, history, recs, plan, signals, health(s, ledger, history), capital, capitalManual, gain, gainPct, dayChange to dayPct, issues, sinceStart, history?.startValue, lifetimeGain,
            null, mayer, emptyList(), week, budget == null, cashWarning, achievements, emptyMap(),
            arena, arenaPlan, arenaBacktest, milestones, journal, btcWeek)
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
