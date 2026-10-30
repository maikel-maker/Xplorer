package be.maikel.xplorer.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * DCA intelligent : chaque semaine, le montant de base est multiplié selon l'écart du prix
 * à sa moyenne 200 jours (Mayer Multiple = prix / moyenne 200 j).
 *
 * - Prix sous sa tendance → on achète plus, en puisant dans la réserve « creux ».
 * - Prix très au-dessus de sa tendance → on achète moins, le reste va dans la réserve.
 * - Filet : une réserve qui attend depuis [MAX_RESERVE_DAYS] jours est investie quoi qu'il arrive.
 *
 * L'argent n'est retenu que dans les zones nettement chères (≥ 1,8), pas au moindre doute.
 * Aucune garantie de battre le marché : le test sur le passé ([backtest]) sert à le vérifier.
 */
object SmartDca {
    const val VERSION = "dca-mm-1"
    const val MAX_RESERVE_DAYS = 28
    const val MIN_ORDER = 5.0
    private const val DAY = 86_400_000L

    data class Band(val upTo: Double, val mult: Double, val label: String, val emoji: String)

    /** Zones : bornes supérieures exclusives du Mayer Multiple. */
    val bands = listOf(
        Band(0.8, 2.0, "Très bas : achat doublé", "🟢"),
        Band(1.0, 1.5, "Sous sa tendance : achat renforcé", "🟢"),
        Band(1.8, 1.0, "Prix normal : achat normal", "🟡"),
        Band(2.4, 0.5, "Cher : achat réduit de moitié", "🟠"),
        Band(Double.POSITIVE_INFINITY, 0.0, "Euphorie : on n'achète pas", "🔴"),
    )

    fun band(mm: Double?): Band? = if (mm == null || mm.isNaN()) null else bands.first { mm < it.upTo }
    fun multiplier(mm: Double?): Double = band(mm)?.mult ?: 1.0

    /** Mayer Multiple au dernier jour, en remplaçant la clôture du jour par le prix actuel. Null si moins de 200 jours. */
    fun mayer(daily: List<Candle>, price: Double, now: Long): Double? {
        if (daily.size < 200 || !(price > 0)) return null
        val closes = daily.map { it.close }.toMutableList()
        if (daily.last().t / DAY == now / DAY) closes[closes.size - 1] = price else closes += price
        val ma = closes.takeLast(200).average()
        return if (ma > 0) price / ma else null
    }

    /** Mayer Multiple à l'indice [i] d'une série de clôtures (pour le test sur le passé). */
    private fun mayerAt(closes: DoubleArray, i: Int): Double? {
        if (i < 199) return null
        var s = 0.0
        for (k in i - 199..i) s += closes[k]
        val ma = s / 200
        return if (ma > 0) closes[i] / ma else null
    }

    data class Line(
        val symbol: String,
        val mayer: Double?,
        val mult: Double,
        val base: Double,
        val eur: Double,
        val note: String,
    )

    data class Plan(
        val weekly: Double,
        val reserveStart: Double,
        val reserveDays: Int,
        val fromReserve: Double,
        val toReserve: Double,
        val forced: Boolean,
        val lines: List<Line>,
        val headline: String,
        val explanation: String,
    ) {
        val total get() = lines.sumOf { it.eur }
        val reserveEnd get() = max(0.0, reserveStart - fromReserve + toReserve)
        /** Variation de la réserve à appliquer en fin de semaine. */
        val reserveDelta get() = toReserve - fromReserve
    }

    /**
     * [weekly] : budget de la semaine. [reserve] : argent déjà mis de côté (réserve « creux »).
     * [reserveDays] : depuis combien de jours la réserve attend. [mayer] : Mayer Multiple par crypto.
     * [values] : valeur actuelle de chaque position. [maxWeight] : limite d'exposition par crypto.
     */
    fun plan(
        weekly: Double,
        reserve: Double,
        reserveDays: Int,
        mayer: Map<String, Double?>,
        targets: Map<String, Double>,
        values: Map<String, Double>,
        maxWeight: Map<String, Double>,
        cryptoTotal: Double,
    ): Plan {
        val syms = targets.keys.filter { (targets[it] ?: 0.0) > 0 }
        val after = cryptoTotal + weekly + max(0.0, reserve)
        val notes = mutableMapOf<String, String>()

        // 1. Répartition de base : on comble l'écart à la cible (les cryptos en retard d'abord).
        val need = syms.associateWith { s ->
            val cap = (maxWeight[s] ?: 1.0) * after - (values[s] ?: 0.0)
            val gap = (targets[s] ?: 0.0) * after - (values[s] ?: 0.0)
            if (cap <= 0) { notes[s] = "Au-dessus de ta limite d'exposition : pas d'achat."; 0.0 }
            else if (gap <= 0) { notes[s] = "Déjà au-dessus de sa cible : pause des achats."; 0.0 }
            else min(gap, cap)
        }
        val sumNeed = need.values.sum()
        val weights: Map<String, Double> = if (sumNeed > 0) need.mapValues { it.value / sumNeed }
        else {
            val tot = syms.sumOf { targets[it] ?: 0.0 }
            syms.associateWith { if (tot > 0) (targets[it] ?: 0.0) / tot else 0.0 }
        }
        val base = weights.mapValues { weekly * it.value }

        val forced = reserve > 0.5 && reserveDays >= MAX_RESERVE_DAYS
        val raw = mutableMapOf<String, Double>()
        var fromReserve = 0.0
        var toReserve = 0.0

        if (forced) {
            // Filet : la réserve attend depuis trop longtemps, on investit tout (budget + réserve).
            val buyable = syms.filter { multiplier(mayer[it]) > 0 && (need[it] ?: 0.0) > 0 }
            val pool = weekly + reserve
            if (buyable.isNotEmpty()) {
                val w = buyable.sumOf { weights[it] ?: 0.0 }
                buyable.forEach { raw[it] = pool * (if (w > 0) (weights[it] ?: 0.0) / w else 1.0 / buyable.size) }
            } else {
                // Tout est cher : la moins chère par rapport à sa tendance prend tout.
                val cheapest = syms.filter { (need[it] ?: 0.0) > 0 }.minByOrNull { mayer[it] ?: 1.0 } ?: syms.minByOrNull { mayer[it] ?: 1.0 }
                if (cheapest != null) raw[cheapest] = pool
            }
            fromReserve = reserve
            syms.forEach { if (raw[it] != null) notes[it] = "Réserve investie : elle attendait depuis $reserveDays jours." }
        } else {
            val want = base.mapValues { (s, b) -> b * multiplier(mayer[s]) }
            val totalWant = want.values.sum()
            if (totalWant > weekly) {
                // Zone basse : on puise dans la réserve, sans dépasser ce qu'elle contient.
                val extraWanted = totalWant - weekly
                fromReserve = min(extraWanted, max(0.0, reserve))
                val k = (weekly + fromReserve) / totalWant
                want.forEach { (s, v) -> raw[s] = v * k }
            } else {
                want.forEach { (s, v) -> raw[s] = v }
                toReserve = weekly - totalWant
            }
            for (s in syms) {
                if (notes.containsKey(s)) continue
                val b = band(mayer[s])
                notes[s] = if (b == null) "Moins de 200 jours d'historique : achat normal." else "${b.emoji} ${b.label} (prix ${Money.pct(mayer[s]!! - 1, true)} par rapport à sa moyenne 200 j)."
            }
        }

        // Pas de miettes : un ordre trop petit coûte trop en frais, il rejoint la plus grosse ligne.
        val small = raw.filter { it.value > 0 && it.value < MIN_ORDER }
        if (small.isNotEmpty()) {
            val freed = small.values.sum()
            small.keys.forEach { raw[it] = 0.0; notes[it] = (notes[it] ?: "") + " Montant trop petit, regroupé." }
            raw.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { raw[it.key] = it.value + freed }
                ?: run { toReserve += freed }
        }

        val lines = syms.map { s ->
            val e = round(raw[s] ?: 0.0)
            Line(s, mayer[s], if (forced) 1.0 else multiplier(mayer[s]), base[s] ?: 0.0, e, notes[s] ?: "")
        }.sortedByDescending { it.eur }
        // Les arrondis ne doivent pas créer ou faire disparaître d'argent.
        val spent = lines.sumOf { it.eur }
        val drift = spent - (weekly + fromReserve - toReserve)
        if (drift > 0) toReserve = max(0.0, toReserve - drift) else toReserve += -drift
        if (toReserve > 0 && fromReserve > 0) { val n = min(toReserve, fromReserve); toReserve -= n; fromReserve -= n }

        val headline = when {
            spent < 1 -> "Cette semaine : on n'achète rien, ${Money.eur(weekly, 0)} vont en réserve."
            forced -> "Cette semaine : investir ${Money.eur(spent, 0)}, dont la réserve qui attendait."
            fromReserve >= 1 -> "Prix bas : investir ${Money.eur(spent, 0)}, dont ${Money.eur(fromReserve, 0)} pris dans la réserve."
            toReserve >= 1 -> "Prix élevés : investir ${Money.eur(spent, 0)}, mettre ${Money.eur(toReserve, 0)} en réserve."
            else -> "Cette semaine : investir ${Money.eur(spent, 0)}."
        }
        val explanation = buildString {
            append("Base ${Money.eur(weekly, 0)} par semaine, répartie vers les cryptos en retard sur ta cible, puis ajustée selon le prix par rapport à sa moyenne 200 jours. ")
            append("L'argent n'est retenu que si c'est nettement cher, et jamais plus de $MAX_RESERVE_DAYS jours.")
        }
        return Plan(weekly, max(0.0, reserve), reserveDays, fromReserve, toReserve, forced, lines, headline, explanation)
    }

    data class Backtest(
        val symbol: String,
        val weeks: Int,
        val invested: Double,
        val unitsDca: Double,
        val unitsSmart: Double,
        val cashLeft: Double,
        val valueDca: Double,
        val valueSmart: Double,
    ) {
        /** Avance du DCA intelligent sur le DCA simple, à valeur finale égale de dépôts (réserve restante comptée). */
        val edge get() = if (valueDca > 0) valueSmart / valueDca - 1 else Double.NaN
        val unitsEdge get() = if (unitsDca > 0) unitsSmart / unitsDca - 1 else Double.NaN
    }

    /**
     * Test sur le passé, crypto par crypto : mêmes versements chaque semaine, DCA simple contre DCA intelligent.
     * Aucune information du futur : à chaque semaine, seule la moyenne 200 j connue ce jour-là est utilisée.
     * La réserve restante à la fin est comptée en euros dans la valeur finale (pas de triche).
     * Frais identiques pour les deux méthodes, donc ignorés.
     */
    fun backtest(symbol: String, daily: List<Candle>, weekly: Double = 100.0, maxReserveDays: Int = MAX_RESERVE_DAYS): Backtest? {
        val closes = DoubleArray(daily.size) { daily[it].close }
        if (closes.size < 200 + 7 * 26 || closes.any { !(it > 0) }) return null
        var unitsDca = 0.0
        var unitsSmart = 0.0
        var reserve = 0.0
        var reserveSince = -1
        var weeks = 0
        var i = 199
        while (i < closes.size) {
            val p = closes[i]
            unitsDca += weekly / p
            val m = multiplier(mayerAt(closes, i))
            val forced = reserve > 0.5 && reserveSince >= 0 && i - reserveSince >= maxReserveDays
            var spend: Double
            if (forced) {
                spend = weekly + reserve
                reserve = 0.0
            } else {
                spend = weekly * m
                if (spend > weekly) {
                    val extra = min(spend - weekly, reserve)
                    spend = weekly + extra
                    reserve -= extra
                } else {
                    reserve += weekly - spend
                }
            }
            unitsSmart += spend / p
            if (reserve <= 0.5) { reserve = max(0.0, reserve); reserveSince = -1 } else if (reserveSince < 0) reserveSince = i
            weeks++
            i += 7
        }
        val last = closes.last()
        val invested = weeks * weekly
        return Backtest(symbol, weeks, invested, unitsDca, unitsSmart, reserve, unitsDca * last, unitsSmart * last + reserve)
    }
}
