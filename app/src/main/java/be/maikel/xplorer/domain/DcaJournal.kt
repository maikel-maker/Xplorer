package be.maikel.xplorer.domain

/**
 * Le journal du DCA : semaine par semaine depuis le point de départ, combien tu as vraiment acheté
 * (achats réels lus sur Coinbase), et ce que les frais t'ont coûté.
 */
object DcaJournal {
    private const val DAY = 86_400_000L
    /** Une semaine compte comme « faite » dès qu'au moins 80 % du budget a été acheté. */
    const val DONE_SHARE = 0.8

    data class Week(val start: Long, val bought: Double, val fees: Double, val budget: Double, val bySymbol: Map<String, Double>) {
        val done get() = bought >= budget * DONE_SHARE
        val partial get() = !done && bought > 1
    }

    data class Summary(val weeks: List<Week>, val totalBought: Double, val totalFees: Double) {
        val doneWeeks get() = weeks.count { it.done }
        /** Frais en pourcentage des achats. */
        val feePct get() = if (totalBought > 0) totalFees / totalBought else 0.0
        /** Ce que des ordres limites (≈ 0,6 % au lieu de ≈ 1,2 %) auraient pu économiser, estimation. */
        val limitSaving get() = (totalFees - totalBought * 0.006).coerceAtLeast(0.0)
    }

    /** Lundi 00:00 UTC de la semaine de [ms] (le 1er janvier 1970 était un jeudi). */
    fun weekStart(ms: Long): Long = (Math.floorDiv(ms / DAY + 3, 7L) * 7 - 3) * DAY

    /**
     * [trades] : opérations Coinbase ; seuls les achats comptent. [budgetFor] : budget prévu pour la semaine qui commence à ce lundi.
     * Semaines du point de départ [from] jusqu'à [now], la plus récente en premier.
     */
    fun compute(trades: List<Trade>, from: Long, now: Long, budgetFor: (Long) -> Double): Summary {
        val buys = trades.filter { it.buy && it.t >= from && it.t <= now && it.totalEur > 0 }
        val byWeek = buys.groupBy { weekStart(it.t) }
        val weeks = ArrayList<Week>()
        var w = weekStart(from)
        val last = weekStart(now)
        while (w <= last) {
            val list = byWeek[w].orEmpty()
            weeks += Week(
                w, list.sumOf { it.totalEur }, list.sumOf { it.feeEur }, budgetFor(w),
                list.groupBy { it.symbol }.mapValues { e -> e.value.sumOf { it.totalEur } },
            )
            w += 7 * DAY
        }
        return Summary(weeks.reversed(), buys.sumOf { it.totalEur }, buys.sumOf { it.feeEur })
    }
}
