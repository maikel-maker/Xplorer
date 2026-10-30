package be.maikel.xplorer.domain

import kotlin.math.min

/**
 * « Creux & pics avec garde-tendance » : l'algorithme retenu pour un marché mixte (qui monte et descend sans tendance nette).
 *
 * Chaque semaine : versement fixe investi (comme un DCA). Le reste du temps :
 * - Creux : le RSI 14 jours passe sous 30 → on investit la moitié de l'argent en attente.
 * - Pic : le RSI passe au-dessus de 75 → on vend 10 % de la position…
 * - …sauf si la tendance de fond est forte (moyenne 50 j au-dessus de la 200 j et prix au-dessus de la 50 j) :
 *   dans une vraie montée, on ne vend pas trop tôt.
 *
 * Choisi parmi 5 algorithmes testés sur l'historique BTC, ETH, LINK (3, 5 et 8 ans, frais 0,6 %) :
 * c'est le plus régulier, mais il perd contre le DCA dans les très grosses hausses (BTC 2018-2026 : −12 %).
 * Choisi sur ces mêmes données : le résultat futur sera probablement moins bon.
 */
object SwingAlgo {
    const val NAME = "Creux & pics (garde-tendance)"
    const val FEE = 0.006
    private const val RSI_LOW = 30.0
    private const val RSI_HIGH = 75.0
    private const val BUY_SHARE = 0.5
    private const val SELL_SHARE = 0.10

    data class Event(val t: Long, val buy: Boolean, val price: Double, val eur: Double, val reason: String)

    data class Result(
        val symbol: String,
        val events: List<Event>,
        val days: Int,
        val valueAlgo: Double,
        val valueDca: Double,
        val today: Event?,
    ) {
        val edge get() = if (valueDca > 0) valueAlgo / valueDca - 1 else Double.NaN
    }

    private fun strongTrend(c: DoubleArray, sma50: DoubleArray, sma200: DoubleArray, i: Int) =
        !sma50[i].isNaN() && !sma200[i].isNaN() && sma50[i] > sma200[i] && c[i] > sma50[i]

    /**
     * Simulation sur [daily] (anciennes → récentes) avec [weekly] € par semaine, après 200 jours de chauffe.
     * [noSell] : crypto qu'on ne vend jamais (règle GEOD).
     */
    fun simulate(symbol: String, daily: List<Candle>, weekly: Double = 100.0, noSell: Boolean = false): Result? {
        val n = daily.size
        if (n < 260) return null
        val c = DoubleArray(n) { daily[it].close }
        if (c.any { !(it > 0) }) return null
        val rsi = Indicators.rsi(c, 14)
        val s50 = Indicators.sma(c, 50)
        val s200 = Indicators.sma(c, 200)
        val start = 200
        var units = 0.0; var cash = 0.0; var dca = 0.0
        val ev = mutableListOf<Event>()
        for (i in start until n) {
            val p = c[i]
            if ((i - start) % 7 == 0) {
                cash += weekly; dca += weekly * (1 - FEE) / p
                units += cash.let { val b = min(weekly, it); cash -= b; b * (1 - FEE) / p }
            }
            val r = rsi[i]; val rp = rsi[i - 1]
            if (r.isNaN() || rp.isNaN()) continue
            if (r < RSI_LOW && rp >= RSI_LOW && cash > 1) {
                val b = BUY_SHARE * cash
                units += b * (1 - FEE) / p; cash -= b
                ev += Event(daily[i].t, true, p, b, "Creux : RSI ${r.toInt()}, la baisse est allée loin")
            } else if (!noSell && r > RSI_HIGH && rp <= RSI_HIGH && !strongTrend(c, s50, s200, i) && units > 0) {
                val q = SELL_SHARE * units
                units -= q; cash += q * p * (1 - FEE)
                ev += Event(daily[i].t, false, p, q * p, "Pic : RSI ${r.toInt()}, la hausse s'emballe sans vraie tendance")
            }
        }
        val last = c.last()
        val todayEv = ev.lastOrNull()?.takeIf { daily.last().t - it.t < 2 * 86_400_000L }
        return Result(symbol, ev, n - start, units * last + cash, dca * last, todayEv)
    }
}
