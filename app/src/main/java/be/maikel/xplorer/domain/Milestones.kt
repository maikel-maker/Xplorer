package be.maikel.xplorer.domain

import kotlin.math.ceil

/**
 * Paliers réalistes : posséder 1 ETH, atteindre 1 000 € de cryptos…
 * Tout est calculé avec tes vrais soldes Coinbase. L'estimation « à ce rythme » utilise
 * la part que l'arène donne à chaque crypto cette semaine, au prix d'aujourd'hui (sans deviner l'avenir).
 */
object Milestones {

    /** Échelles par crypto, en quantité. Pensées pour un budget d'environ 225 € par semaine. */
    val ladders: Map<String, List<Double>> = mapOf(
        "BTC" to listOf(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0),
        "ETH" to listOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0),
        "LINK" to listOf(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0),
        "GEOD" to listOf(100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0),
        "TAO" to listOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0),
        "KRL" to listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0),
        "SOL" to listOf(0.5, 1.0, 2.5, 5.0, 10.0, 25.0),
    )
    /** Échelle du patrimoine crypto total, en euros. */
    val wealth = listOf(250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0, 50000.0, 100000.0)
    /** Pour une crypto sans échelle connue : valeur détenue en euros. */
    private val generic = listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0)

    data class Track(
        val symbol: String?,          // null = patrimoine total
        val inEuros: Boolean,         // l'échelle est en euros (sinon en quantité)
        val current: Double,
        val steps: List<Double>,
        /** Combien de semaines pour le prochain palier au rythme actuel (null si inconnu). */
        val weeksToNext: Int?,
    ) {
        val reached get() = steps.count { current >= it }
        val next: Double? get() = steps.firstOrNull { current < it }
        val previous: Double get() = steps.lastOrNull { current >= it } ?: 0.0
        /** Progression vers le prochain palier, depuis le précédent (0 à 1). */
        val progress: Double get() {
            val n = next ?: return 1.0
            val p = previous
            return if (n <= p) 1.0 else ((current - p) / (n - p)).coerceIn(0.0, 1.0)
        }
        val done get() = next == null
    }

    data class Summary(val wealth: Track, val coins: List<Track>) {
        val reachedTotal get() = wealth.reached + coins.sumOf { it.reached }
        val stepsTotal get() = wealth.steps.size + coins.sumOf { it.steps.size }
    }

    /**
     * [qty] et [price] par crypto, [cryptoTotal] en euros, [weeklyBySymbol] = euros prévus cette semaine par crypto,
     * [weekly] = budget hebdomadaire total. [symbols] = cryptos à suivre (plan + positions).
     */
    fun compute(
        symbols: List<String>,
        qty: Map<String, Double>,
        price: Map<String, Double>,
        cryptoTotal: Double,
        weeklyBySymbol: Map<String, Double>,
        weekly: Double,
    ): Summary {
        val w = Track(null, true, cryptoTotal, wealth, weeks(cryptoTotal, wealth.firstOrNull { cryptoTotal < it }, weekly))
        val coins = symbols.distinct().map { s ->
            val q = qty[s] ?: 0.0
            val p = price[s] ?: Double.NaN
            val perWeek = weeklyBySymbol[s] ?: 0.0
            val ladder = ladders[s]
            if (ladder != null) {
                val next = ladder.firstOrNull { q < it }
                val unitsPerWeek = if (p > 0) perWeek / p else 0.0
                Track(s, false, q, ladder, weeks(q, next, unitsPerWeek))
            } else {
                val v = if (p > 0) q * p else 0.0
                Track(s, true, v, generic, weeks(v, generic.firstOrNull { v < it }, perWeek))
            }
        }.sortedWith(compareByDescending<Track> { it.progress }.thenBy { it.symbol })
        return Summary(w, coins)
    }

    /** Semaines pour passer de [cur] à [next] en ajoutant [perWeek] par semaine (sans variation de prix). */
    fun weeks(cur: Double, next: Double?, perWeek: Double): Int? {
        if (next == null || !(perWeek > 0)) return null
        val n = ceil((next - cur) / perWeek)
        return if (n.isNaN() || n > 520) null else n.toInt().coerceAtLeast(1)
    }

    /** Affichage d'un palier : « 1 ETH », « 0,05 BTC », « 1 000 € ». */
    fun label(t: Track, v: Double): String =
        if (t.inEuros) Money.eur(v, 0) else "${Money.qty(v)} ${t.symbol ?: ""}".trim()
}
