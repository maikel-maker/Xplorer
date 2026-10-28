package be.maikel.xplorer.domain

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Formats en français (Belgique) : 1 234,56 €, espaces insécables. */
object Money {
    private val locale = Locale("fr", "BE")

    private fun nf(dec: Int): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = dec; maximumFractionDigits = dec
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    fun eur(v: Double, dec: Int? = null): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val d = dec ?: if (abs(v) >= 100) 0 else 2
        return clean(nf(d).format(v)) + " €"
    }

    fun signedEur(v: Double, dec: Int? = null): String {
        if (v.isNaN()) return "—"
        return (if (v >= 0) "+" else "−") + eur(abs(v), dec)
    }

    fun priceDecimals(p: Double): Int = when {
        p.isNaN() -> 2
        p >= 1000 -> 0
        p >= 10 -> 2
        p >= 1 -> 3
        p >= 0.01 -> 4
        else -> 6
    }

    fun price(p: Double): String = eur(p, priceDecimals(p))

    fun pct(v: Double, sign: Boolean = false): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val s = clean(nf(1).format(v * 100))
        return (if (sign && v > 0) "+" else "") + s + " %"
    }

    fun ratio(r: Double): String = clean(NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(r * 100)) + " %"

    fun qty(q: Double): String {
        if (q.isNaN()) return "—"
        val d = when { q >= 1000 -> 2; q >= 1 -> 4; q >= 0.001 -> 6; else -> 8 }
        return clean(NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = d }.format(q))
    }
}
