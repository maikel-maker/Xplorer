package be.maikel.xplorer.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    val targets: Map<String, Double> = mapOf("BTC" to 0.45, "ETH" to 0.20, "GEOD" to 0.20, "LINK" to 0.05, "TAO" to 0.05, "KRL" to 0.05),
    val watchlist: List<String> = listOf("SOL", "AKT", "NEAR"),
    val maxWeightDefault: Double = 0.5,
    val maxWeight: Map<String, Double> = emptyMap(),
    val weeklyBudget: Double = 125.0,
    val monthlyBudget: Double = 500.0,
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
) {
    fun maxFor(sym: String) = maxWeight[sym] ?: maxWeightDefault
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("xp_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Settings = try {
        sp.getString("settings", null)?.let { json.decodeFromString<Settings>(it) } ?: Settings()
    } catch (e: Exception) { Settings() }

    fun save(s: Settings) { sp.edit().putString("settings", json.encodeToString(s)).apply() }
}
