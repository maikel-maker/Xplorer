package be.maikel.xplorer.domain

/** Résultat d'un conseil, évalué une seule fois à l'échéance (jamais réécrit ensuite). */
data class Outcome(val price: Double, val move: Double, val verdict: String)

object Journal {
    const val HORIZON_DAYS = 21

    /** Faut-il enregistrer un nouveau conseil ? Oui si l'action change, ou une fois par semaine. */
    fun shouldRecord(lastAction: String?, lastAt: Long?, action: Action, now: Long): Boolean =
        lastAction == null || lastAt == null || lastAction != action.name || now - lastAt > 7 * 86_400_000L

    /** Évalue un conseil arrivé à échéance. ATTENDRE/CONSERVER ne sont pas notés (pas de pari). */
    fun grade(action: String, priceAt: Double, priceNow: Double): Outcome {
        val move = priceNow / priceAt - 1
        val verdict = when (Action.valueOf(action)) {
            Action.ACHETER, Action.RENFORCER -> if (move >= 0) "juste" else "raté"
            Action.ALLEGER, Action.VENDRE -> if (move <= 0) "juste" else "raté"
            else -> "non noté"
        }
        return Outcome(priceNow, move, verdict)
    }
}

/** Détections du radar d'opportunités. */
data class Signal(val symbol: String, val kind: String, val text: String, val positive: Boolean)

object Radar {
    fun scan(symbol: String, daily: List<Candle>, price: Double, weight: Double, maxWeight: Double, vol24Eur: Double?): List<Signal> {
        val out = mutableListOf<Signal>()
        if (vol24Eur != null && vol24Eur < 50_000) out += Signal(symbol, "Liquidité", "Volume 24 h faible (${Money.eur(vol24Eur, 0)}) : écarts de prix possibles.", false)
        if (weight > maxWeight) out += Signal(symbol, "Concentration", "Pèse ${Money.pct(weight)} du portefeuille, au-dessus de ta limite de ${Money.pct(maxWeight)}.", false)
        if (daily.size < 60 || !(price > 0)) return out
        val c = (daily.map { it.close }.dropLast(1) + price).toDoubleArray()
        val rsi = Indicators.rsi(c)
        val r = Indicators.last(rsi); val rp = Indicators.lastAt(rsi, 2)
        if (r < 32 && r > rp) out += Signal(symbol, "Survente avec confirmation", "RSI ${r.toInt()} qui remonte.", true)
        if (r > 72) out += Signal(symbol, "Surachat", "RSI ${r.toInt()} : risque de correction.", false)
        val ma50 = Indicators.sma(c, 50)
        val m = Indicators.last(ma50); val mp = Indicators.lastAt(ma50, 1)
        if (c.size > 2 && c[c.size - 2] < mp && price > m) out += Signal(symbol, "Reprise de moyenne", "Repasse au-dessus de la moyenne 50 jours (${Money.price(m)}).", true)
        val lv = Indicators.levels(daily.takeLast(365), price)
        lv.filter { it.support }.maxByOrNull { it.price }?.let { s ->
            val lowRecent = daily.takeLast(3).minOf { it.low }
            if (lowRecent <= s.price * 1.01 && price > s.price * 1.01) out += Signal(symbol, "Rebond sur support", "Rebond sur ${Money.price(s.price)} (${s.touches} contacts).", true)
        }
        val b = Indicators.bollinger(c)
        val bw = Indicators.bandWidth(b).filter { !it.isNaN() }
        if (bw.size > 60 && Indicators.percentileRank(bw.takeLast(180).toDoubleArray(), bw.last()) < 0.1)
            out += Signal(symbol, "Compression de volatilité", "Bandes de Bollinger très serrées : un mouvement fort se prépare souvent, sans direction connue.", true)
        val vols = daily.takeLast(21).map { it.volume }
        if (vols.size == 21) {
            val avg = vols.dropLast(1).average()
            if (avg > 0 && vols.last() > 2.5 * avg) out += Signal(symbol, "Anomalie de volume", "Volume ${"%.1f".format(vols.last() / avg)}× la moyenne de 20 jours.", daily.last().close >= daily.last().open)
        }
        val hi = daily.takeLast(21).dropLast(1).maxOf { it.high }
        if (price > hi && daily.last().volume > (vols.dropLast(1).average())) out += Signal(symbol, "Cassure avec volume", "Dépasse le plus haut de 20 jours (${Money.price(hi)}).", true)
        return out
    }
}
