package be.maikel.xplorer.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    val targets: Map<String, Double> = mapOf("BTC" to 0.50, "ETH" to 0.25, "GEOD" to 0.15, "LINK" to 0.05, "TAO" to 0.05),
    val watchlist: List<String> = listOf("SOL", "AKT", "NEAR"),
    val maxWeightDefault: Double = 0.5,
    val maxWeight: Map<String, Double> = emptyMap(),
    val weeklyBudget: Double = 250.0,
    val monthlyBudget: Double = 1000.0,
    val tolerance: String = "Équilibré",
    val horizonYears: Int = 5,
    val cashReserve: Double = 0.0,
    val maxLossPct: Double = 0.4,
    val longTerm: Boolean = true,
    val geodBase: Double = 0.21,
    val geodMultiple: Double = 20.0,
    val notifications: Boolean = true,
    val quietStart: Int = 22,
    val quietEnd: Int = 8,
    val maxAlertsPerDay: Int = 6,
    /** Correction manuelle du capital apporté (null = calcul automatique). */
    val capitalOverride: Double? = null,
    /** Point de départ du suivi (ms UTC). Par défaut le 28 septembre 2026, début de la série actuelle. null = depuis l'ouverture du compte. */
    val startDate: Long? = 1790553600000L,
    /** DCA intelligent : réserve « creux » au début de la semaine en cours (€). */
    val dcaReserveStart: Double = 0.0,
    /** Depuis quand la réserve attend (ms UTC), null si vide. */
    val dcaReserveSince: Long? = null,
    /** Semaine (index) à laquelle la réserve a été arrêtée pour la dernière fois. */
    val dcaWeek: Long? = null,
    /** Variation de réserve prévue par le plan de la semaine en cours, appliquée au changement de semaine. */
    val dcaPendingDelta: Double = 0.0,
    val dcaSchema: Int = 0,
    /** Dernier mois (année × 12 + mois) pour lequel le bilan mensuel a été envoyé. */
    val recapMonth: Int? = null,
) {
    fun maxFor(sym: String) = maxWeight[sym] ?: maxWeightDefault
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("xp_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Settings = migrate(try {
        sp.getString("settings", null)?.let { json.decodeFromString<Settings>(it) } ?: Settings()
    } catch (e: Exception) { Settings() })

    /**
     * v2 (30/10/2026) : 225 € par semaine, répartis par l'arène, plus de réserve « creux ».
     * v6 (31/10/2026) : cibles BTC 50 · ETH 25 · GEOD 15 · LINK 5 · TAO 5.
     * v5 (31/10/2026) : budget corrigé à 250 € par semaine (1 000 € par mois de 4 semaines).
     * v4 (30/10/2026) : KRL retiré ; cibles en chiffres ronds choisies par Maïkel (BTC 50 · ETH 20 · GEOD 20 · LINK 5 · TAO 5).
     */
    private fun migrate(s: Settings): Settings {
        var m = s
        if (m.dcaSchema < 2) m = m.copy(
            monthlyBudget = 975.0, weeklyBudget = 225.0, dcaSchema = 2,
            dcaReserveStart = 0.0, dcaReserveSince = null, dcaPendingDelta = 0.0,
        )
        if (m.dcaSchema < 4) m = m.copy(
            targets = Settings().targets,
            watchlist = m.watchlist - "KRL",
            dcaSchema = 4,
        )
        if (m.dcaSchema < 5) m = m.copy(weeklyBudget = 250.0, monthlyBudget = 1000.0, dcaSchema = 5)
        if (m.dcaSchema < 6) m = m.copy(targets = Settings().targets, dcaSchema = 6)
        if (m != s) save(m)
        return m
    }

    fun save(s: Settings) { sp.edit().putString("settings", json.encodeToString(s)).apply() }
}
