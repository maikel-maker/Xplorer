package be.maikel.xplorer.domain

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Une bougie. [t] = début de la période en millisecondes UTC. Prix en euros. */
data class Candle(
    val t: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

/**
 * Indicateurs techniques. Fonctions pures, testées dans IndicatorsTest contre des valeurs
 * calculées indépendamment (pandas). Les tableaux renvoyés ont la même taille que l'entrée ;
 * les valeurs non calculables valent NaN.
 */
object Indicators {

    fun sma(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (n <= 0 || v.size < n) return out
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    /** EMA initialisée par la SMA des n premières valeurs (convention TradingView). */
    fun ema(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (n <= 0 || v.size < n) return out
        val k = 2.0 / (n + 1)
        var prev = v.take(n).average()
        out[n - 1] = prev
        for (i in n until v.size) {
            prev = v[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Moyenne lissée de Wilder (RMA). */
    fun rma(v: DoubleArray, n: Int, from: Int = 0): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (v.size - from < n) return out
        var prev = (from until from + n).sumOf { v[it] } / n
        out[from + n - 1] = prev
        for (i in from + n until v.size) {
            prev = (prev * (n - 1) + v[i]) / n
            out[i] = prev
        }
        return out
    }

    /** RSI de Wilder. */
    fun rsi(close: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size <= n) return out
        val gain = DoubleArray(close.size)
        val loss = DoubleArray(close.size)
        for (i in 1 until close.size) {
            val d = close[i] - close[i - 1]
            gain[i] = max(d, 0.0); loss[i] = max(-d, 0.0)
        }
        val ag = rma(gain, n, 1)
        val al = rma(loss, n, 1)
        for (i in close.indices) {
            if (ag[i].isNaN()) continue
            out[i] = if (al[i] == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag[i] / al[i])
        }
        return out
    }

    data class Macd(val macd: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val ef = ema(close, fast)
        val es = ema(close, slow)
        val line = DoubleArray(close.size) { if (ef[it].isNaN() || es[it].isNaN()) Double.NaN else ef[it] - es[it] }
        val first = line.indexOfFirst { !it.isNaN() }
        val signal = DoubleArray(close.size) { Double.NaN }
        if (first >= 0) {
            val sub = line.copyOfRange(first, line.size)
            val s = ema(sub, sig)
            for (i in s.indices) signal[first + i] = s[i]
        }
        val hist = DoubleArray(close.size) { if (line[it].isNaN() || signal[it].isNaN()) Double.NaN else line[it] - signal[it] }
        return Macd(line, signal, hist)
    }

    data class Bands(val mid: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

    /** Bandes de Bollinger (écart-type de population, comme TradingView). */
    fun bollinger(close: DoubleArray, n: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(close, n)
        val up = DoubleArray(close.size) { Double.NaN }
        val lo = DoubleArray(close.size) { Double.NaN }
        for (i in n - 1 until close.size) {
            var s = 0.0
            for (j in i - n + 1..i) s += (close[j] - mid[i]) * (close[j] - mid[i])
            val sd = sqrt(s / n)
            up[i] = mid[i] + k * sd; lo[i] = mid[i] - k * sd
        }
        return Bands(mid, up, lo)
    }

    fun trueRange(c: List<Candle>): DoubleArray = DoubleArray(c.size) { i ->
        if (i == 0) c[0].high - c[0].low
        else maxOf(c[i].high - c[i].low, abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))
    }

    /** ATR de Wilder. */
    fun atr(c: List<Candle>, n: Int = 14): DoubleArray = rma(trueRange(c), n, 0)

    data class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    /** ADX / DMI de Wilder. */
    fun adx(c: List<Candle>, n: Int = 14): Adx {
        val size = c.size
        val pdm = DoubleArray(size); val mdm = DoubleArray(size)
        for (i in 1 until size) {
            val up = c[i].high - c[i - 1].high
            val down = c[i - 1].low - c[i].low
            pdm[i] = if (up > down && up > 0) up else 0.0
            mdm[i] = if (down > up && down > 0) down else 0.0
        }
        val tr = trueRange(c)
        val atr = rma(tr, n, 1)
        val sp = rma(pdm, n, 1)
        val sm = rma(mdm, n, 1)
        val pdi = DoubleArray(size) { if (atr[it].isNaN() || atr[it] == 0.0) Double.NaN else 100 * sp[it] / atr[it] }
        val mdi = DoubleArray(size) { if (atr[it].isNaN() || atr[it] == 0.0) Double.NaN else 100 * sm[it] / atr[it] }
        val dx = DoubleArray(size) {
            val s = pdi[it] + mdi[it]
            if (pdi[it].isNaN() || s == 0.0) Double.NaN else 100 * abs(pdi[it] - mdi[it]) / s
        }
        val firstDx = dx.indexOfFirst { !it.isNaN() }
        val adx = if (firstDx < 0) DoubleArray(size) { Double.NaN } else rma(dx, n, firstDx)
        return Adx(adx, pdi, mdi)
    }

    /** Stochastic RSI (%K lissé 3, %D 3). Valeurs 0..100. */
    fun stochRsi(close: DoubleArray, rsiN: Int = 14, stochN: Int = 14, k: Int = 3, d: Int = 3): Pair<DoubleArray, DoubleArray> {
        val r = rsi(close, rsiN)
        val raw = DoubleArray(close.size) { Double.NaN }
        for (i in close.indices) {
            if (i < stochN - 1) continue
            val w = (i - stochN + 1..i).map { r[it] }
            if (w.any { it.isNaN() }) continue
            val lo = w.min(); val hi = w.max()
            raw[i] = if (hi == lo) 50.0 else 100 * (r[i] - lo) / (hi - lo)
        }
        val kk = smaSkipNaN(raw, k)
        val dd = smaSkipNaN(kk, d)
        return kk to dd
    }

    private fun smaSkipNaN(v: DoubleArray, n: Int): DoubleArray {
        val first = v.indexOfFirst { !it.isNaN() }
        val out = DoubleArray(v.size) { Double.NaN }
        if (first < 0) return out
        val s = sma(v.copyOfRange(first, v.size), n)
        for (i in s.indices) out[first + i] = s[i]
        return out
    }

    /** On-Balance Volume. */
    fun obv(c: List<Candle>): DoubleArray {
        val out = DoubleArray(c.size)
        for (i in 1 until c.size) {
            out[i] = out[i - 1] + when {
                c[i].close > c[i - 1].close -> c[i].volume
                c[i].close < c[i - 1].close -> -c[i].volume
                else -> 0.0
            }
        }
        return out
    }

    /** Volatilité historique annualisée (365 jours) des rendements logarithmiques sur n périodes. */
    fun historicalVolatility(close: DoubleArray, n: Int = 30, periodsPerYear: Double = 365.0): Double {
        if (close.size < n + 1) return Double.NaN
        val r = (close.size - n until close.size).map { ln(close[it] / close[it - 1]) }
        val m = r.average()
        val v = r.sumOf { (it - m) * (it - m) } / (r.size - 1)
        return sqrt(v) * sqrt(periodsPerYear)
    }

    /** Rang centile de x dans v (part des valeurs ≤ x), 0..1. */
    fun percentileRank(v: DoubleArray, x: Double): Double =
        if (v.isEmpty()) Double.NaN else v.count { it <= x }.toDouble() / v.size

    data class Swing(val index: Int, val price: Double, val high: Boolean)

    /** Points pivots : plus haut (bas) strictement dominant sur [k] bougies de chaque côté. */
    fun swings(c: List<Candle>, k: Int = 3): List<Swing> {
        val out = mutableListOf<Swing>()
        for (i in k until c.size - k) {
            val h = c[i].high; val l = c[i].low
            if ((i - k..i + k).all { it == i || c[it].high < h }) out += Swing(i, h, true)
            if ((i - k..i + k).all { it == i || c[it].low > l }) out += Swing(i, l, false)
        }
        return out
    }

    data class Level(val price: Double, val touches: Int, val support: Boolean)

    /** Supports et résistances : regroupement des pivots proches (tolérance relative [tol]). */
    fun levels(c: List<Candle>, price: Double, tol: Double = 0.025, k: Int = 3): List<Level> {
        val sw = swings(c, k).map { it.price }.sorted()
        val clusters = mutableListOf<MutableList<Double>>()
        for (p in sw) {
            val last = clusters.lastOrNull()
            if (last != null && abs(p / last.average() - 1) <= tol) last += p else clusters += mutableListOf(p)
        }
        return clusters.filter { it.size >= 2 }
            .map { Level(it.average(), it.size, it.average() < price) }
    }

    data class Fib(val high: Double, val low: Double, val upMove: Boolean, val levels: List<Pair<Double, Double>>)

    val FIB = listOf(0.236, 0.382, 0.5, 0.618, 0.786)

    /** Retracements de Fibonacci sur la plus grande vague de la fenêtre. */
    fun fibonacci(c: List<Candle>): Fib? {
        if (c.size < 30) return null
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        var hiI = 0; var loI = 0
        c.forEachIndexed { i, k ->
            if (k.high > hi) { hi = k.high; hiI = i }
            if (k.low < lo) { lo = k.low; loI = i }
        }
        if (hi <= lo) return null
        val up = loI < hiI
        val lv = FIB.map { r -> r to if (up) hi - (hi - lo) * r else lo + (hi - lo) * r }
        return Fib(hi, lo, up, lv)
    }

    /** Largeur relative des bandes de Bollinger (compression de volatilité quand elle est basse). */
    fun bandWidth(b: Bands): DoubleArray = DoubleArray(b.mid.size) {
        if (b.mid[it].isNaN() || b.mid[it] == 0.0) Double.NaN else (b.upper[it] - b.lower[it]) / b.mid[it]
    }

    fun last(v: DoubleArray): Double = v.lastOrNull { !it.isNaN() } ?: Double.NaN
    fun lastAt(v: DoubleArray, back: Int): Double = if (v.size > back) v[v.size - 1 - back] else Double.NaN

    /** Agrège des bougies (ex. journalières → hebdomadaires) par clé de période. */
    fun aggregate(c: List<Candle>, key: (Long) -> Long): List<Candle> =
        c.groupBy { key(it.t) }.toSortedMap().map { (k, g) ->
            Candle(k, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close, g.sumOf { it.volume })
        }

    fun clamp(x: Double, lo: Double = 0.0, hi: Double = 100.0) = min(hi, max(lo, x))
}
