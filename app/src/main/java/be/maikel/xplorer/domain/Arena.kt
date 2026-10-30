package be.maikel.xplorer.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * L'Arène SnipeX : six signaux votent, crypto par crypto, Bulls (ça va monter) contre Bears (ça va baisser).
 *
 * Ce que l'arène décide : la RÉPARTITION du budget de la semaine entre tes cryptos.
 * Ce qu'elle ne décide jamais : garder de l'argent de côté. Le budget part en entier chaque semaine,
 * parce que les tests sur le passé montrent que l'argent qui attend coûte plus qu'il ne rapporte.
 *
 * Test hors app (Coin Metrics, BTC/ETH/LINK 2018-2026, réglé sur la 1re moitié, vérifié sur la 2e) :
 * l'écart avec une répartition fixe reste autour de 0 %. L'arène ne promet donc pas de battre le marché :
 * elle rend la décision lisible et évite de surcharger une crypto en pleine surchauffe.
 */
object Arena {
    const val VERSION = "arena-1"
    const val MIN_ORDER = 5.0
    /** Force du vote sur la répartition : score +1 → part × 1,5 ; score −1 → part × 0,5. */
    const val TILT = 0.5

    enum class Camp(val label: String) { BULLS("Bulls"), BEARS("Bears"), NEUTRE("Neutre") }

    data class Agent(val id: String, val name: String, val role: String)

    val TREND = Agent("trend", "Tendance de fond", "Moyennes 50 et 200 jours")
    val HEAT = Agent("heat", "Surchauffe", "RSI 14 jours")
    val DIP = Agent("dip", "Soldes", "Baisse depuis le plus haut de 3 mois")
    val VALUE = Agent("value", "Prix juste", "Prix par rapport à sa moyenne 200 jours")
    val MOMENTUM = Agent("momentum", "Élan", "Variation sur 30 jours")
    val STEADY = Agent("steady", "Régularité", "Le DCA paie sur la durée")
    val agents = listOf(TREND, HEAT, DIP, VALUE, MOMENTUM, STEADY)

    data class Vote(val agent: Agent, val score: Double, val quote: String, val abstain: Boolean = false) {
        val camp get() = when {
            abstain -> Camp.NEUTRE
            score > 0 -> Camp.BULLS
            score < 0 -> Camp.BEARS
            else -> Camp.NEUTRE
        }
    }

    data class Debate(val symbol: String, val votes: List<Vote>) {
        private val active get() = votes.filter { !it.abstain }
        /** Score de l'arène, de −1 (ours écrasants) à +1 (bœufs écrasants). */
        val score: Double get() = if (active.isEmpty()) 0.0 else active.sumOf { it.score } / active.size
        val bulls get() = votes.count { it.camp == Camp.BULLS }
        val bears get() = votes.count { it.camp == Camp.BEARS }
        /** Part des bœufs dans le rapport de force (0 à 1), pour la barre de l'arène. */
        val bullShare: Double get() = (score + 1) / 2
        val verdict: String get() = when {
            score > 0.4 -> "Les Bulls dominent"
            score > 0.15 -> "Avantage Bulls"
            score < -0.4 -> "Les Bears dominent"
            score < -0.15 -> "Avantage Bears"
            else -> "Égalité Bulls / Bears"
        }
        val multiplier: Double get() = (1 + TILT * score).coerceIn(1 - TILT, 1 + TILT)
    }

    /** Indicateurs précalculés, uniquement à partir du passé de chaque jour (aucune info du futur). */
    class Inputs(val closes: DoubleArray) {
        val rsi = Indicators.rsi(closes, 14)
        val sma50 = Indicators.sma(closes, 50)
        val sma200 = Indicators.sma(closes, 200)
    }

    private fun pct(x: Double) = (if (x >= 0) "+" else "−") + abs(x * 100).roundToInt() + " %"
    private fun ok(vararg v: Double) = v.all { !it.isNaN() && !it.isInfinite() }

    /** Débat sur la dernière bougie de [daily], le dernier prix remplaçant la clôture du jour si fourni. */
    fun debate(symbol: String, daily: List<Candle>, price: Double = Double.NaN): Debate {
        val closes = daily.map { it.close }.toMutableList()
        if (price > 0 && closes.isNotEmpty()) closes[closes.size - 1] = price
        if (closes.isEmpty()) return Debate(symbol, agents.map { Vote(it, 0.0, "Pas encore de prix pour cette crypto.", abstain = true) })
        val arr = closes.toDoubleArray()
        return debateAt(symbol, Inputs(arr), arr.size - 1)
    }

    fun debateAt(symbol: String, x: Inputs, i: Int): Debate {
        val c = x.closes
        val p = c[i]
        val votes = ArrayList<Vote>()

        // Tendance de fond (moyennes 50 et 200 jours).
        val s50 = x.sma50[i]; val s200 = x.sma200[i]
        votes += if (!ok(s50, s200)) Vote(TREND, 0.0, "Moins de 200 jours d'historique.", abstain = true)
        else when {
            s50 > s200 && p > s50 -> Vote(TREND, 1.0, "La tendance de fond monte.")
            s50 < s200 && p < s50 -> Vote(TREND, -1.0, "La tendance de fond descend.")
            p > s200 -> Vote(TREND, 0.3, "Au-dessus de sa moyenne 200 jours, mais la tendance hésite.")
            else -> Vote(TREND, -0.3, "Sous sa moyenne 200 jours, pas encore de reprise.")
        }

        // Surchauffe (RSI 14 jours).
        val r = x.rsi[i]
        votes += if (!ok(r)) Vote(HEAT, 0.0, "Pas assez d'historique.", abstain = true)
        else when {
            r > 75 -> Vote(HEAT, -1.0, "RSI ${r.roundToInt()} : monté trop vite, risque de repli.")
            r > 65 -> Vote(HEAT, -0.6, "RSI ${r.roundToInt()} : ça chauffe.")
            r < 30 -> Vote(HEAT, 0.4, "RSI ${r.roundToInt()} : peur exagérée, zone d'achat.")
            else -> Vote(HEAT, -0.1, "RSI ${r.roundToInt()} : température normale.")
        }

        // Soldes (baisse depuis le plus haut de 90 jours).
        if (i < 89) votes += Vote(DIP, 0.0, "Moins de 3 mois d'historique.", abstain = true)
        else {
            var hi = 0.0
            for (k in i - 89..i) hi = max(hi, c[k])
            val dd = p / hi - 1
            votes += when {
                dd < -0.30 -> Vote(DIP, 1.0, "${pct(dd)} depuis son plus haut de 3 mois : grosses soldes.")
                dd < -0.20 -> Vote(DIP, 0.6, "${pct(dd)} depuis son plus haut : belles soldes.")
                dd < -0.10 -> Vote(DIP, 0.3, "${pct(dd)} depuis son plus haut : petite remise.")
                else -> Vote(DIP, -0.2, "Seulement ${pct(dd)} sous son plus haut : pas de soldes.")
            }
        }

        // Prix juste (prix / moyenne 200 jours).
        votes += if (!ok(s200) || s200 <= 0) Vote(VALUE, 0.0, "Moins de 200 jours d'historique.", abstain = true)
        else {
            val mm = p / s200
            val sc = ((1.2 - mm) / 0.6).coerceIn(-1.0, 1.0)
            val gap = pct(mm - 1)
            Vote(VALUE, sc, when {
                mm < 0.8 -> "$gap par rapport à son prix moyen : c'est bradé."
                mm < 1.0 -> "$gap par rapport à son prix moyen : sous sa valeur habituelle."
                mm < 1.5 -> "$gap par rapport à son prix moyen : prix correct."
                else -> "$gap par rapport à son prix moyen : c'est cher payé."
            })
        }

        // Élan (variation sur 30 jours).
        votes += if (i < 30 || !(c[i - 30] > 0)) Vote(MOMENTUM, 0.0, "Moins d'un mois d'historique.", abstain = true)
        else {
            val m = p / c[i - 30] - 1
            val sc = (m / 0.3).coerceIn(-1.0, 1.0)
            Vote(MOMENTUM, sc, when {
                m > 0.15 -> "${pct(m)} en un mois : fort élan."
                m > 0 -> "${pct(m)} en un mois : léger élan vers le haut."
                m > -0.15 -> "${pct(m)} en un mois : l'élan s'essouffle."
                else -> "${pct(m)} en un mois : forte baisse."
            })
        }

        // Régularité : toujours légèrement Bulls (le DCA continue).
        votes += Vote(STEADY, 0.3, "Acheter chaque semaine reste la base, quoi qu'il arrive.")
        return Debate(symbol, votes)
    }

    // ---------------------------------------------------------------- Plan de la semaine

    data class Line(val symbol: String, val eur: Double, val baseShare: Double, val share: Double, val multiplier: Double, val note: String)

    data class Plan(val weekly: Double, val lines: List<Line>, val headline: String) {
        val total get() = lines.sumOf { it.eur }
    }

    /**
     * Répartit [weekly] € en entier entre les cryptos de [targets] :
     * 1. part de base = rattraper l'écart à la cible (limites d'exposition respectées) ;
     * 2. chaque part est multipliée par le verdict de l'arène (× 0,5 à × 1,5) ;
     * 3. arrondi à l'euro, sans ordre de moins de [MIN_ORDER] €, total exactement égal au budget.
     */
    fun plan(
        weekly: Double,
        debates: Map<String, Debate>,
        targets: Map<String, Double>,
        values: Map<String, Double>,
        maxWeight: Map<String, Double>,
        cryptoTotal: Double,
    ): Plan {
        val syms = targets.keys.filter { (targets[it] ?: 0.0) > 0 }
        val budget = max(0.0, floor(weekly + 0.5))
        if (syms.isEmpty() || budget < 1) return Plan(weekly, emptyList(), "Aucune crypto dans ton plan : ajoute des cibles dans les réglages.")
        val after = cryptoTotal + budget
        val notes = HashMap<String, String>()
        val need = syms.associateWith { s ->
            val cap = (maxWeight[s] ?: 1.0) * after - (values[s] ?: 0.0)
            val gap = (targets[s] ?: 0.0) * after - (values[s] ?: 0.0)
            when {
                cap <= 0 -> { notes[s] = "Au-dessus de ta limite d'exposition : pas d'achat."; 0.0 }
                gap <= 0 -> { notes[s] = "Déjà au-dessus de sa cible : pause cette semaine."; 0.0 }
                else -> min(gap, cap)
            }
        }
        val sumNeed = need.values.sum()
        val base: Map<String, Double> = if (sumNeed > 0) need.mapValues { it.value / sumNeed } else {
            val t = syms.sumOf { targets[it] ?: 0.0 }
            syms.associateWith { (targets[it] ?: 0.0) / t }
        }
        val tilted = base.mapValues { (s, b) -> b * (debates[s]?.multiplier ?: 1.0) }
        var weights = normalize(tilted)

        // Pas de miettes : un ordre trop petit coûte trop en frais, sa part va aux autres.
        repeat(syms.size) {
            val small = weights.filter { it.value > 0 && it.value * budget < MIN_ORDER }.keys
            if (small.isEmpty() || small.size == weights.count { it.value > 0 }) return@repeat
            small.forEach { notes[it] = "Montant trop petit cette semaine, reporté sur les autres." }
            weights = normalize(weights.mapValues { if (it.key in small) 0.0 else it.value })
        }
        val euros = roundTo(budget, weights)

        val lines = syms.map { s ->
            val d = debates[s]
            val note = notes[s] ?: when {
                d == null -> "Pas de débat : part normale."
                d.multiplier > 1.05 -> "${d.verdict} : part renforcée."
                d.multiplier < 0.95 -> "${d.verdict} : part réduite."
                else -> "${d.verdict} : part normale."
            }
            Line(s, euros[s] ?: 0.0, base[s] ?: 0.0, weights[s] ?: 0.0, d?.multiplier ?: 1.0, note)
        }.sortedByDescending { it.eur }
        val top = lines.firstOrNull { it.eur > 0 }
        val headline = if (top == null) "Rien à acheter cette semaine." else
            "Cette semaine : ${Money.eur(budget, 0)} à investir, surtout en ${top.symbol} (${Money.eur(top.eur, 0)})."
        return Plan(budget, lines, headline)
    }

    private fun normalize(w: Map<String, Double>): Map<String, Double> {
        val t = w.values.filter { it > 0 }.sum()
        return if (t <= 0) w.mapValues { 0.0 } else w.mapValues { if (it.value > 0) it.value / t else 0.0 }
    }

    /** Arrondi à l'euro par plus grands restes : la somme vaut exactement [total]. */
    fun roundTo(total: Double, weights: Map<String, Double>): Map<String, Double> {
        val n = total.roundToInt()
        val raw = weights.mapValues { it.value * n }
        val out = raw.mapValues { floor(it.value) }.toMutableMap()
        var left = n - out.values.sum().roundToInt()
        val order = raw.entries.filter { it.value > 0 }.sortedByDescending { it.value - floor(it.value) }
        var k = 0
        while (left > 0 && order.isNotEmpty()) {
            val key = order[k % order.size].key
            out[key] = (out[key] ?: 0.0) + 1.0
            left--; k++
        }
        return out
    }

    // ---------------------------------------------------------------- Test sur le passé

    data class Backtest(val symbols: List<String>, val weeks: Int, val from: Long, val to: Long, val valueArena: Double, val valueFixed: Double, val invested: Double) {
        val edge get() = if (valueFixed > 0) valueArena / valueFixed - 1 else Double.NaN
    }

    /**
     * Même budget chaque semaine, même jour d'achat, mêmes frais : répartition de l'arène
     * contre répartition fixe selon les cibles. À chaque date, l'arène ne voit que le passé.
     */
    fun backtest(daily: Map<String, List<Candle>>, targets: Map<String, Double>, weekly: Double): Backtest? {
        val syms = targets.keys.filter { (targets[it] ?: 0.0) > 0 && (daily[it]?.size ?: 0) >= 200 + 26 * 7 }.sorted()
        if (syms.isEmpty()) return null
        val byDay = syms.associateWith { s -> daily[s]!!.filter { it.close > 0 }.associate { it.t / 86_400_000L to it.close } }
        val days = byDay.values.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
        if (days.size < 200 + 26 * 7) return null
        val inputs = syms.associateWith { s -> Inputs(DoubleArray(days.size) { byDay[s]!![days[it]]!! }) }
        val t = syms.sumOf { targets[it] ?: 0.0 }
        val fixedW = syms.associateWith { (targets[it] ?: 0.0) / t }
        val uA = HashMap<String, Double>(); val uF = HashMap<String, Double>()
        var weeks = 0
        var i = 199
        while (i < days.size) {
            val w = normalize(syms.associateWith { s -> fixedW[s]!! * debateAt(s, inputs[s]!!, i).multiplier })
            for (s in syms) {
                val p = inputs[s]!!.closes[i]
                uA[s] = (uA[s] ?: 0.0) + weekly * w[s]!! / p
                uF[s] = (uF[s] ?: 0.0) + weekly * fixedW[s]!! / p
            }
            weeks++; i += 7
        }
        val last = days.size - 1
        val vA = syms.sumOf { (uA[it] ?: 0.0) * inputs[it]!!.closes[last] }
        val vF = syms.sumOf { (uF[it] ?: 0.0) * inputs[it]!!.closes[last] }
        return Backtest(syms, weeks, days[199] * 86_400_000L, days[last] * 86_400_000L, vA, vF, weeks * weekly)
    }
}
