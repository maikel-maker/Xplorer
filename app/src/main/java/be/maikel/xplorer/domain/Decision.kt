package be.maikel.xplorer.domain

import be.maikel.xplorer.domain.Indicators.clamp
import be.maikel.xplorer.domain.Indicators.last
import be.maikel.xplorer.domain.Indicators.lastAt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Action(val label: String) {
    ACHETER("Acheter"),
    RENFORCER("Renforcer"),
    CONSERVER("Conserver"),
    ATTENDRE("Attendre"),
    ALLEGER("Alléger"),
    VENDRE("Envisager une vente"),
    INSUFFISANT("Preuves insuffisantes");

    val isBuy get() = this == ACHETER || this == RENFORCER
    val isSell get() = this == ALLEGER || this == VENDRE
}

enum class Level3(val label: String) { FAIBLE("faible"), MOYEN("moyen"), ELEVE("élevé") }

/** Une famille d'indicateurs : un seul vote par famille, pour ne pas compter deux fois la même information. */
data class Component(val name: String, val weight: Double, val score: Double, val notes: List<String>)

data class Recommendation(
    val symbol: String,
    val at: Long,
    val price: Double,
    val action: Action,
    /** Qualité du signal, 0..100 (élevé = favorable à l'achat). Ce n'est pas une probabilité. */
    val score: Int,
    val components: List<Component>,
    val confidence: Level3,
    val risk: Level3,
    val dataQuality: Level3,
    val dataNotes: List<String>,
    val pros: List<String>,
    val cons: List<String>,
    val invalidation: String,
    val invalidationPrice: Double,
    val confirmation: String,
    val horizon: String,
    val risks: List<String>,
    val veto: String?,
    val timeframeConflict: String?,
    val strategyVersion: String = VERSION,
    val atrPct: Double,
    val percentile1y: Double,
    val zone: String,
) {
    companion object { const val VERSION = "xp-1.0" }
}

data class AssetContext(
    val symbol: String,
    val owned: Boolean,
    val weight: Double,
    val target: Double,
    val maxWeight: Double,
    val avgCost: Double,
)

data class Rules(
    val geodBase: Double = 0.21,
    val geodMultiple: Double = 20.0,
    /** Ne jamais acheter au-dessus de ce centile du prix sur un an (pas d'achat aux sommets). */
    val noBuyAbovePercentile: Double = 0.9,
    val extremeAtrPct: Double = 0.12,
)

object DecisionEngine {

    private fun fmt(p: Double) = Money.price(p)

    /**
     * [daily] : bougies journalières (anciennes → récentes, la dernière peut être en cours).
     * [h4] : bougies 4 h facultatives, pour vérifier l'accord entre horizons.
     */
    fun evaluate(
        symbol: String,
        daily: List<Candle>,
        h4: List<Candle>?,
        price: Double,
        ctx: AssetContext,
        rules: Rules,
        now: Long,
    ): Recommendation {
        val dataNotes = mutableListOf<String>()
        val days = daily.size
        val lastAge = if (daily.isNotEmpty()) (now - daily.last().t) / 86_400_000.0 else 999.0
        val zeroVol = if (daily.isNotEmpty()) daily.takeLast(90).count { it.volume <= 0.0 }.toDouble() / daily.takeLast(90).size else 1.0
        var dq = when {
            days >= 365 -> Level3.ELEVE
            days >= 120 -> Level3.MOYEN
            else -> Level3.FAIBLE
        }
        if (days < 365) dataNotes += "Historique de $days jours seulement (1 an recommandé)."
        if (lastAge > 2.5) { dq = Level3.FAIBLE; dataNotes += "Dernière bougie vieille de ${lastAge.toInt()} jours." }
        if (zeroVol > 0.2) { dq = minOf(dq, Level3.MOYEN); dataNotes += "Beaucoup de jours sans échanges : marché peu liquide." }
        if (!(price > 0)) { dq = Level3.FAIBLE; dataNotes += "Prix actuel indisponible." }

        if (days < 120 || !(price > 0)) {
            return insufficient(symbol, now, price, dataNotes, dq)
        }

        val lastIsToday = daily.last().t / 86_400_000L == now / 86_400_000L
        val closeList = daily.map { it.close }.toMutableList()
        if (lastIsToday) closeList[closeList.size - 1] = price else closeList += price
        val close = closeList.toDoubleArray()
        val year = close.takeLast(365).toDoubleArray()
        val pct = Indicators.percentileRank(year, price)
        val ma20 = last(Indicators.sma(close, 20))
        val ma50 = last(Indicators.sma(close, 50))
        val ma200 = if (days >= 200) last(Indicators.sma(close, 200)) else Double.NaN
        val ma200Prev = if (days >= 220) lastAt(Indicators.sma(close, 200), 20) else Double.NaN
        val rsiArr = Indicators.rsi(close, 14)
        val rsi = last(rsiArr)
        val rsiPrev = lastAt(rsiArr, 3)
        val macd = Indicators.macd(close)
        val hist = last(macd.hist)
        val histPrev = lastAt(macd.hist, 1)
        val (sk, sd) = Indicators.stochRsi(close)
        val k = last(sk); val dd = last(sd)
        val atrArr = Indicators.atr(daily, 14)
        val atr = last(atrArr)
        val atrPct = atr / price
        val adx = Indicators.adx(daily, 14)
        val adxV = last(adx.adx); val pdi = last(adx.plusDi); val mdi = last(adx.minusDi)
        val boll = Indicators.bollinger(close)
        val bw = Indicators.bandWidth(boll)
        val bwRank = Indicators.percentileRank(bw.takeLast(180).filter { !it.isNaN() }.toDoubleArray(), last(bw))
        val lv = Indicators.levels(daily.takeLast(365), price)
        val support = lv.filter { it.price < price }.maxByOrNull { it.price }
        val resistance = lv.filter { it.price > price }.minByOrNull { it.price }
        val fib = Indicators.fibonacci(daily.takeLast(365))
        val nearFib = fib?.levels?.firstOrNull { abs(price / it.second - 1) < 0.025 }

        // 1. Valorisation : où est le prix dans son année ? (bas = favorable pour un accumulateur)
        val valNotes = mutableListOf<String>()
        var valS = 100 * (1 - pct)
        valNotes += "${(pct * 100).toInt()} % des jours de l'année étaient moins chers"
        if (!ma200.isNaN()) {
            val dist = price / ma200 - 1
            valS += clamp(-dist * 100, -15.0, 15.0)
            valNotes += "${Money.pct(dist, true)} par rapport à la moyenne 200 jours (${fmt(ma200)})"
        }
        if (support != null && price / support.price - 1 < 0.04) { valS += 8; valNotes += "Proche d'un support à ${fmt(support.price)} (${support.touches} contacts)" }
        if (nearFib != null && (nearFib.first == 0.5 || nearFib.first == 0.618) && fib!!.upMove) { valS += 6; valNotes += "Sur le retracement de Fibonacci ${Money.ratio(nearFib.first)} (${fmt(nearFib.second)})" }
        valS = clamp(valS)

        // 2. Momentum : la baisse s'essouffle-t-elle, la hausse se confirme-t-elle ?
        val momNotes = mutableListOf<String>()
        var momS = 50.0
        if (!rsi.isNaN()) {
            momNotes += "RSI ${rsi.toInt()}"
            momS += when {
                rsi < 30 && rsi > rsiPrev -> 22.0   // survente qui se retourne
                rsi < 30 -> 8.0                      // survente sans confirmation
                rsi < 45 && rsi > rsiPrev -> 12.0
                rsi > 75 -> -25.0
                rsi > 65 -> -12.0
                else -> 0.0
            }
        }
        if (!hist.isNaN() && !histPrev.isNaN()) {
            val crossUp = hist > 0 && histPrev <= 0
            val crossDown = hist < 0 && histPrev >= 0
            momS += when {
                crossUp -> 15.0
                crossDown -> -15.0
                hist > histPrev -> 7.0
                else -> -7.0
            }
            momNotes += when {
                crossUp -> "MACD : croisement haussier"
                crossDown -> "MACD : croisement baissier"
                hist > histPrev -> "MACD : l'élan s'améliore"
                else -> "MACD : l'élan se dégrade"
            }
        }
        if (!k.isNaN() && !dd.isNaN()) {
            if (k < 20 && k > dd) { momS += 8; momNotes += "Stochastic RSI remonte depuis la survente" }
            if (k > 80 && k < dd) { momS -= 8; momNotes += "Stochastic RSI retombe depuis le surachat" }
        }
        momS = clamp(momS)

        // 3. Tendance de fond : on évite d'attraper un couteau qui tombe.
        val trNotes = mutableListOf<String>()
        var trS = 50.0
        if (!ma200.isNaN()) {
            if (price > ma200) { trS += 15; trNotes += "Au-dessus de la moyenne 200 jours" } else { trS -= 15; trNotes += "Sous la moyenne 200 jours" }
            if (!ma200Prev.isNaN()) { if (ma200 > ma200Prev) { trS += 8; trNotes += "Moyenne 200 jours en hausse" } else { trS -= 8; trNotes += "Moyenne 200 jours en baisse" } }
            if (ma50 > ma200) { trS += 7; trNotes += "Moyenne 50 j au-dessus de la 200 j" } else { trS -= 7; trNotes += "Moyenne 50 j sous la 200 j" }
        } else {
            if (price > ma50) trS += 10 else trS -= 10
            trNotes += if (price > ma50) "Au-dessus de la moyenne 50 jours" else "Sous la moyenne 50 jours"
        }
        if (!adxV.isNaN()) {
            if (adxV > 25 && mdi > pdi) { trS -= 15; trNotes += "Tendance baissière forte (ADX ${adxV.toInt()})" }
            else if (adxV > 25 && pdi > mdi) { trS += 10; trNotes += "Tendance haussière forte (ADX ${adxV.toInt()})" }
            else trNotes += "Pas de tendance marquée (ADX ${adxV.toInt()})"
        }
        trS = clamp(trS)

        // 4. Risque : volatilité et chute en cours (score haut = risque bas).
        val rkNotes = mutableListOf<String>()
        val high = year.maxOrNull() ?: price
        val dd1y = price / high - 1
        var rkS = 100 - clamp(atrPct * 100 * 9, 0.0, 70.0)
        rkNotes += "Variation moyenne d'une journée : ${Money.pct(atrPct)}"
        if (dd1y < -0.5) { rkS -= 10; rkNotes += "${Money.pct(dd1y)} depuis le plus haut de l'année" }
        if (!bwRank.isNaN() && bwRank < 0.15) rkNotes += "Volatilité comprimée : un mouvement fort se prépare souvent"
        rkS = clamp(rkS)

        val comps = listOf(
            Component("Valorisation", 0.35, valS, valNotes),
            Component("Momentum", 0.25, momS, momNotes),
            Component("Tendance de fond", 0.25, trS, trNotes),
            Component("Risque", 0.15, rkS, rkNotes),
        )
        val score = comps.sumOf { it.weight * it.score }.toInt()

        // Accord entre familles et entre horizons → confiance (et non probabilité).
        val spread = comps.take(3).maxOf { it.score } - comps.take(3).minOf { it.score }
        var tfConflict: String? = null
        if (h4 != null && h4.size >= 60) {
            val c4 = DoubleArray(h4.size) { h4[it].close }
            val m4 = Indicators.macd(c4)
            val h4hist = last(m4.hist); val h4prev = lastAt(m4.hist, 1)
            val up4 = h4hist > h4prev
            val upD = momS >= 50
            if (up4 != upD) tfConflict = if (up4) "Le 4 h remonte alors que le journalier reste faible : signal à confirmer." else "Le journalier est favorable mais le 4 h faiblit : patience à court terme."
        }
        var conf = when {
            spread < 25 -> Level3.ELEVE
            spread < 45 -> Level3.MOYEN
            else -> Level3.FAIBLE
        }
        if (tfConflict != null) conf = minOf(conf, Level3.MOYEN)
        if (dq != Level3.ELEVE) conf = minOf(conf, Level3.MOYEN)
        if (dq == Level3.FAIBLE) conf = Level3.FAIBLE

        val risk = when {
            atrPct > 0.07 || dd1y < -0.6 -> Level3.ELEVE
            atrPct > 0.035 -> Level3.MOYEN
            else -> Level3.FAIBLE
        }

        // Action brute à partir du score et du contexte du portefeuille.
        val over = ctx.target > 0 && ctx.weight > ctx.target * 1.25
        var action = when {
            score >= 62 -> if (ctx.owned) Action.RENFORCER else Action.ACHETER
            score >= 40 -> if (ctx.owned) Action.CONSERVER else Action.ATTENDRE
            score >= 25 -> if (ctx.owned && (over || pct > 0.85)) Action.ALLEGER else if (ctx.owned) Action.CONSERVER else Action.ATTENDRE
            else -> if (ctx.owned && pct > 0.9) Action.VENDRE else if (ctx.owned) Action.ALLEGER else Action.ATTENDRE
        }
        if (ctx.target <= 0.0 && action.isBuy) action = if (ctx.owned) Action.CONSERVER else Action.ATTENDRE

        // Gardien du risque : droit de veto.
        var veto: String? = null
        if (action.isBuy) {
            when {
                pct >= rules.noBuyAbovePercentile -> veto = "Prix dans les 10 % les plus chers de l'année : pas d'achat au sommet."
                ctx.weight >= ctx.maxWeight -> veto = "${symbol} pèse déjà ${Money.pct(ctx.weight)} du portefeuille (limite ${Money.pct(ctx.maxWeight)})."
                atrPct > rules.extremeAtrPct -> veto = "Volatilité extrême (${Money.pct(atrPct)} par jour en moyenne)."
                dq == Level3.FAIBLE -> veto = "Données trop faibles pour justifier un achat."
            }
            if (veto != null) action = if (ctx.owned) Action.CONSERVER else Action.ATTENDRE
        }
        // Règle GEOD : achat seulement, vente uniquement à partir de x20 du prix d'achat.
        if (symbol == "GEOD" && action.isSell) {
            val exit = rules.geodBase * rules.geodMultiple
            if (price < exit) {
                veto = "Règle GEOD : pas de vente sous ${fmt(exit)} (x${rules.geodMultiple.toInt()} de ${fmt(rules.geodBase)})."
                action = Action.CONSERVER
            }
        }
        if (dq == Level3.FAIBLE && action.isSell) action = Action.CONSERVER

        val pros = mutableListOf<String>(); val cons = mutableListOf<String>()
        for (c in comps) {
            val txt = "${c.name} ${c.score.toInt()}/100 : ${c.notes.firstOrNull() ?: ""}"
            if (c.score >= 55) pros += txt else if (c.score <= 45) cons += txt
        }
        if (over) cons += "${symbol} est au-dessus de ta cible (${Money.pct(ctx.weight)} pour ${Money.pct(ctx.target)})."
        if (ctx.owned && ctx.target > 0 && ctx.weight < ctx.target * 0.8) pros += "${symbol} est sous ta cible (${Money.pct(ctx.weight)} pour ${Money.pct(ctx.target)})."
        if (resistance != null) cons += "Résistance à ${fmt(resistance.price)} (${Money.pct(resistance.price / price - 1, true)})."
        tfConflict?.let { cons += it }

        val invPrice: Double
        val invTxt: String
        if (action.isSell) {
            invPrice = max(resistance?.price ?: 0.0, price + 2 * atr)
            invTxt = "Scénario invalidé si la clôture journalière dépasse ${fmt(invPrice)}."
        } else {
            invPrice = max(0.0, min(support?.price?.times(0.98) ?: Double.MAX_VALUE, price - 2 * atr))
            invTxt = "Scénario invalidé si la clôture journalière passe sous ${fmt(invPrice)}."
        }
        val confirm = when {
            action.isBuy && momS < 55 -> "Attendre une clôture journalière au-dessus de la moyenne 20 jours (${fmt(ma20)}) ou un RSI qui repasse au-dessus de 40."
            action.isBuy -> "Confirmation déjà présente : élan positif au-dessus de la moyenne 20 jours."
            action.isSell -> "Confirmé par une clôture journalière sous la moyenne 20 jours (${fmt(ma20)})."
            else -> "Rien à confirmer : on ne fait rien tant que le score ne bouge pas."
        }
        val risks = mutableListOf<String>()
        risks += "Une baisse de 2 ATR (${Money.pct(2 * atrPct)}) est un mouvement ordinaire pour cette crypto."
        if (risk == Level3.ELEVE) risks += "Actif très volatil : prévoir de petits montants."
        if (dd1y < -0.4) risks += "Déjà ${Money.pct(dd1y)} depuis le plus haut : la tendance peut continuer."
        val zone = when {
            pct <= 0.35 -> "Prix bas sur un an"
            pct >= 0.9 -> "Prix au plus haut de l'année"
            pct >= 0.75 -> "Prix cher sur un an"
            else -> "Prix moyen sur un an"
        }
        return Recommendation(
            symbol, now, price, action, score, comps, conf, risk, dq, dataNotes, pros, cons,
            invTxt, invPrice, confirm, "2 à 6 semaines (signal journalier)", risks, veto, tfConflict,
            atrPct = atrPct, percentile1y = pct, zone = zone,
        )
    }

    private fun insufficient(symbol: String, now: Long, price: Double, notes: List<String>, dq: Level3) = Recommendation(
        symbol, now, price, Action.INSUFFISANT, 0, emptyList(), Level3.FAIBLE, Level3.ELEVE, dq, notes,
        emptyList(), listOf("Je ne dispose pas de preuves suffisantes pour recommander une opération."),
        "—", Double.NaN, "Attendre plus d'historique de prix.", "—", emptyList(), null, null,
        atrPct = Double.NaN, percentile1y = Double.NaN, zone = "Inconnu",
    )
}

/** Budget de la semaine : répartition indicative. */
data class PlanLine(val symbol: String, val eur: Double, val note: String, val action: Action)
data class WeeklyPlan(val budget: Double, val lines: List<PlanLine>, val keepCash: Double, val headline: String, val cashWarning: String?)

object Planner {
    const val MIN_ORDER = 5.0

    fun plan(
        budget: Double,
        recs: Map<String, Recommendation>,
        values: Map<String, Double>,
        targets: Map<String, Double>,
        maxWeight: Map<String, Double>,
        cryptoTotal: Double,
        cash: Double?,
    ): WeeklyPlan {
        val after = cryptoTotal + budget
        val raw = mutableMapOf<String, Double>()
        val notes = mutableMapOf<String, String>()
        for ((s, t) in targets) {
            val r = recs[s]
            if (r == null) { notes[s] = "Pas d'analyse disponible."; continue }
            if (!r.action.isBuy) { notes[s] = r.veto ?: "${r.action.label} : ${r.zone.lowercase()}."; continue }
            val gap = max(0.0, t * after - (values[s] ?: 0.0))
            if (gap <= 0) { notes[s] = "Déjà à sa cible."; continue }
            val cap = max(0.0, (maxWeight[s] ?: 1.0) * after - (values[s] ?: 0.0))
            raw[s] = min(gap * (r.score / 62.0), cap)
            notes[s] = "${r.action.label} : score ${r.score}/100, ${r.zone.lowercase()}."
        }
        val sum = raw.values.sum()
        val alloc = mutableMapOf<String, Double>()
        if (sum > 0 && budget > 0) {
            val k = min(1.0, budget / sum)
            raw.forEach { (s, v) -> alloc[s] = v * k }
            // pas de miettes : les petits ordres sont regroupés sur la plus grosse ligne
            val small = alloc.filter { it.value in 0.0..MIN_ORDER && it.value > 0 }
            val freed = small.values.sum()
            small.keys.forEach { alloc[it] = 0.0; notes[it] = "Montant trop petit pour justifier les frais." }
            alloc.maxByOrNull { it.value }?.let { if (it.value > 0) alloc[it.key] = it.value + freed }
        }
        val lines = targets.keys.map { s ->
            val eur = Math.round(alloc[s] ?: 0.0).toDouble()
            PlanLine(s, eur, notes[s] ?: "", recs[s]?.action ?: Action.INSUFFISANT)
        }.sortedByDescending { it.eur }
        val used = lines.sumOf { it.eur }
        val keep = max(0.0, budget - used)
        val headline = if (used < 1) "Ne rien acheter actuellement : conserver les liquidités." else "Répartition indicative de ${Money.eur(used, 0)} sur ${Money.eur(budget, 0)}."
        val warn = if (cash != null && cash + 0.5 < used) "Tu as ${Money.eur(cash, 0)} disponibles sur Coinbase : vire d'abord ${Money.eur(used - cash, 0)}." else null
        return WeeklyPlan(budget, lines, keep, headline, warn)
    }
}
