package be.maikel.xplorer.domain

import kotlin.math.abs

/** Réussites calculées à partir de tes vraies opérations Coinbase : rien à saisir, rien de truqué. */
object Achievements {

    enum class Tier(val label: String, val emoji: String) {
        AUCUN("À débloquer", "🔒"), BRONZE("Bronze", "🥉"), ARGENT("Argent", "🥈"), OR("Or", "🥇"), DIAMANT("Diamant", "💎")
    }

    data class Badge(
        val id: String,
        val title: String,
        val description: String,
        val tier: Tier,
        val value: Double,
        /** Seuil du prochain palier (null = tout est débloqué ou badge unique). */
        val next: Double?,
        val unit: String,
    ) {
        val unlocked get() = tier != Tier.AUCUN
        val progress get() = next?.let { (value / it).coerceIn(0.0, 1.0) } ?: 1.0
    }

    data class Summary(
        val badges: List<Badge>,
        val streakWeeks: Int,
        val level: Int,
        val levelName: String,
        val points: Int,
        val nextLevelAt: Int,
    )

    private val levelNames = listOf("Curieux", "Apprenti", "Explorateur", "Navigateur", "Capitaine", "Stratège", "Légende")

    fun week(ms: Long): Long = Math.floorDiv(ms / 86_400_000L + 3, 7L)

    /** Semaines d'affilée avec au moins un achat (la semaine en cours ne casse pas la série tant qu'elle n'est pas finie). */
    fun streak(buyTimes: List<Long>, now: Long): Int {
        val weeks = buyTimes.map { week(it) }.toSet()
        var w = week(now)
        if (w !in weeks) w -= 1
        var n = 0
        while (w in weeks) { n++; w-- }
        return n
    }

    private fun tiered(id: String, title: String, desc: String, value: Double, steps: List<Double>, unit: String): Badge {
        val reached = steps.count { value >= it }
        val tier = Tier.entries[reached.coerceAtMost(4)]
        return Badge(id, title, desc, tier, value, steps.getOrNull(reached), unit)
    }

    fun compute(
        trades: List<Trade>,
        declaredDone: Int,
        capital: Double,
        maxDrawdown: Double?,
        beatsBtc: Boolean?,
        weights: Map<String, Double>,
        targets: Map<String, Double>,
        smartBuys: Int,
        now: Long,
    ): Summary {
        val buys = trades.filter { it.buy && it.type != "reward" }
        val sells = trades.filter { !it.buy }
        val st = streak(buys.map { it.t }, now)
        val onTarget = targets.isNotEmpty() && targets.all { (s, t) -> abs((weights[s] ?: 0.0) - t) <= 0.05 }
        val held = maxDrawdown != null && maxDrawdown <= -0.2

        val badges = listOf(
            tiered("serie", "Série de la semaine", "Semaines d'affilée avec au moins un achat", st.toDouble(), listOf(2.0, 4.0, 12.0, 26.0), "semaines"),
            tiered("achats", "Collectionneur", "Nombre d'achats réalisés", buys.size.toDouble(), listOf(1.0, 10.0, 50.0, 150.0), "achats"),
            tiered("investi", "Bâtisseur", "Argent mis au travail", capital, listOf(500.0, 1000.0, 5000.0, 10000.0), "€"),
            tiered("malin", "Acheteur malin", "Achats faits quand le feu était vert (prix sous sa moyenne 200 j)", smartBuys.toDouble(), listOf(1.0, 5.0, 15.0, 40.0), "achats"),
            tiered("consigne", "Discipliné", "Fois où tu as suivi la consigne de la semaine", declaredDone.toDouble(), listOf(1.0, 4.0, 12.0, 26.0), "fois"),
            Badge("cible", "Pile dans la cible", "Toutes tes cryptos à moins de 5 points de ton objectif", if (onTarget) Tier.OR else Tier.AUCUN, if (onTarget) 1.0 else 0.0, null, ""),
            Badge("diamant", "Mains de diamant", "Tu as tenu pendant une baisse de 20 % ou plus", if (held) Tier.DIAMANT else Tier.AUCUN, if (held) 1.0 else 0.0, null, ""),
            Badge("btc", "Plus fort que le Bitcoin", "Ton portefeuille fait mieux que le même argent en Bitcoin", if (beatsBtc == true) Tier.OR else Tier.AUCUN, if (beatsBtc == true) 1.0 else 0.0, null, ""),
            Badge("prise", "Prise de bénéfice", "Une vente réalisée avec gain", if (sells.any { (it.realizedEur ?: 0.0) > 0 }) Tier.ARGENT else Tier.AUCUN, 0.0, null, ""),
        )
        val points = badges.sumOf { it.tier.ordinal * 100 } + st * 20 + declaredDone * 15
        val level = (points / 400).coerceAtMost(levelNames.size - 1)
        return Summary(badges, st, level + 1, levelNames[level], points, (level + 1) * 400)
    }
}
