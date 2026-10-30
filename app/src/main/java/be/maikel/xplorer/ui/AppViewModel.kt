package be.maikel.xplorer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.core.Analyzer
import be.maikel.xplorer.core.Pipeline
import be.maikel.xplorer.data.AlertEntity
import be.maikel.xplorer.data.CoinbaseApi
import be.maikel.xplorer.data.CorrectionEntity
import be.maikel.xplorer.data.IntentEntity
import be.maikel.xplorer.data.JournalEntity
import be.maikel.xplorer.data.Repo
import be.maikel.xplorer.data.Settings
import be.maikel.xplorer.data.XpDb
import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.Indicators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId

enum class TF(val label: String, val gran: CoinbaseApi.Gran?) {
    M1("1 min", CoinbaseApi.Gran.M1), M5("5 min", CoinbaseApi.Gran.M5), M15("15 min", CoinbaseApi.Gran.M15),
    M30("30 min", CoinbaseApi.Gran.M30), H1("1 h", CoinbaseApi.Gran.H1), H4("4 h", CoinbaseApi.Gran.H4),
    D1("1 j", null), W1("1 sem", null), MO("1 mois", null)
}

data class ChartData(val symbol: String, val tf: TF, val candles: List<Candle>, val loading: Boolean, val error: String?)

data class UiState(
    val loading: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val notice: String? = null,
    val analysis: Analysis? = null,
    val journal: List<JournalEntity> = emptyList(),
    val alerts: List<AlertEntity> = emptyList(),
    val intents: List<IntentEntity> = emptyList(),
    val corrections: List<CorrectionEntity> = emptyList(),
    val hasKey: Boolean = false,
    val budget: Double? = null,
    val keyError: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    private val _state = MutableStateFlow(UiState(hasKey = repo.hasKey()))
    val state: StateFlow<UiState> = _state
    private val _chart = MutableStateFlow<ChartData?>(null)
    val chart: StateFlow<ChartData?> = _chart

    init {
        viewModelScope.launch { recompute(); refresh() }
    }

    val settings: Settings get() = repo.prefs.load()

    private suspend fun recompute() {
        val snap = repo.snapshot()
        val dao = repo.dao
        val j = dao.journal(); val al = dao.alerts(); val inn = dao.intents(); val co = dao.corrections()
        val doneWeeks = inn.map { Math.floorDiv(it.t / 86_400_000L + 3, 7L) }.toSet().size
        val a = withContext(Dispatchers.Default) { Analyzer.run(snap, repo.h4, _state.value.budget, declaredWeeks = doneWeeks) }
        _state.update { it.copy(analysis = a, journal = j, alerts = al, intents = inn, corrections = co, hasKey = repo.hasKey()) }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val report = repo.sync { p -> _state.update { s -> s.copy(progress = p) } }
                recompute()
                _state.value.analysis?.let { Pipeline.afterSync(getApplication(), repo, it, report.newOps, notify = false) }
                recompute()
                _state.update { it.copy(error = report.errors.firstOrNull()?.let { e -> if (report.errors.size > 1) "$e (+${report.errors.size - 1} autres)" else e }) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Erreur de synchronisation") }
            }
            _state.update { it.copy(loading = false, progress = "") }
        }
    }

    fun refreshIfStale() {
        val last = _state.value.analysis?.snap?.lastSync ?: 0L
        if (System.currentTimeMillis() - last > 10 * 60_000L) refresh()
    }

    fun connect(name: String, pem: String) {
        viewModelScope.launch {
            _state.update { it.copy(keyError = null, loading = true, progress = "Vérification de la clé…") }
            try {
                repo.connect(name, pem)
                _state.update { it.copy(hasKey = true, loading = false, notice = "Clé enregistrée et chiffrée sur ce téléphone.") }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(keyError = e.message ?: "Clé refusée", loading = false, progress = "") }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect(); _state.update { it.copy(hasKey = false) }; recompute() }
    }

    fun wipeEverything() {
        viewModelScope.launch {
            repo.disconnect()
            withContext(Dispatchers.IO) { XpDb.get(getApplication()).clearAllTables() }
            repo.prefs.save(Settings())
            _state.update { UiState(hasKey = false) }
            recompute()
        }
    }

    fun updateSettings(f: (Settings) -> Settings) {
        repo.prefs.save(f(repo.prefs.load()))
        viewModelScope.launch { recompute() }
    }

    fun setBudget(b: Double) {
        _state.update { it.copy(budget = b) }
        viewModelScope.launch { recompute() }
    }

    fun addIntent(sym: String, eur: Double) {
        viewModelScope.launch {
            repo.dao.addIntent(IntentEntity(t = System.currentTimeMillis(), symbol = sym, eur = eur))
            _state.update { it.copy(notice = "Noté : achat déclaré de $sym. Il sera rapproché de Coinbase à la prochaine synchronisation.") }
            recompute()
        }
    }

    /** « C'est fait » : déclare d'un coup tous les achats du plan de la semaine. */
    fun addIntents(lines: List<Pair<String, Double>>) {
        if (lines.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            lines.forEach { (sym, eur) -> repo.dao.addIntent(IntentEntity(t = now, symbol = sym, eur = eur)) }
            _state.update { it.copy(notice = "Bien noté ! On vérifiera tes achats sur Coinbase à la prochaine mise à jour.") }
            recompute()
        }
    }

    /** Correction manuelle du capital apporté, avec trace dans le journal d'audit. */
    fun setCapital(value: Double?, note: String) {
        viewModelScope.launch {
            val prev = repo.prefs.load().capitalOverride
            repo.dao.addCorrection(CorrectionEntity(t = System.currentTimeMillis(), key = "Capital apporté",
                value = value?.toString() ?: "automatique", previous = prev?.toString() ?: "automatique", note = note))
            repo.prefs.save(repo.prefs.load().copy(capitalOverride = value))
            recompute()
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun loadChart(sym: String, tf: TF) {
        _chart.value = ChartData(sym, tf, _chart.value?.takeIf { it.symbol == sym }?.candles ?: emptyList(), true, null)
        viewModelScope.launch {
            try {
                val c = when (tf) {
                    TF.D1 -> repo.dailyCandles(sym)
                    TF.W1 -> Indicators.aggregate(repo.dailyCandles(sym)) { weekKey(it) }
                    TF.MO -> Indicators.aggregate(repo.dailyCandles(sym)) { monthKey(it) }
                    else -> repo.intraday(sym, tf.gran!!)
                }
                _chart.value = ChartData(sym, tf, c, false, if (c.isEmpty()) "Pas de données de prix pour $sym sur cette unité de temps." else null)
            } catch (e: Exception) {
                _chart.value = ChartData(sym, tf, emptyList(), false, e.message ?: "Erreur")
            }
        }
    }

    private fun weekKey(t: Long): Long {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.of("UTC")).toLocalDate()
        return d.minusDays((d.dayOfWeek.value - 1).toLong()).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }

    private fun monthKey(t: Long): Long {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.of("UTC")).toLocalDate().withDayOfMonth(1)
        return d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }

    /** Export lisible de toutes les données locales (sans la clé Coinbase). */
    suspend fun exportJson(): String {
        val a = _state.value.analysis
        val dao = repo.dao
        val ops = dao.ops(); val journal = dao.journal(); val corrections = dao.corrections()
        val obj = buildJsonObject {
            put("app", "SnipeX")
            put("exportedAt", Instant.now().toString())
            put("note", "Export sans la clé Coinbase.")
            putJsonArray("positions") {
                a?.ledger?.positions?.forEach { p ->
                    add(buildJsonObject {
                        put("symbol", p.symbol); put("qty", p.qty); put("valueEur", p.value)
                        put("avgCostEur", if (p.avgCost.isNaN()) null else p.avgCost); put("realizedEur", p.realized)
                    })
                }
            }
            putJsonArray("operations") {
                ops.forEach { o -> add(buildJsonObject { put("id", o.id); put("t", o.t); put("type", o.type); put("cur", o.cur); put("amount", o.amount); put("eur", o.eur) }) }
            }
            putJsonArray("journal") {
                journal.forEach { j -> add(buildJsonObject { put("t", j.t); put("symbol", j.symbol); put("action", j.action); put("score", j.score); put("price", j.price); put("verdict", j.verdict) }) }
            }
            putJsonArray("corrections") {
                corrections.forEach { c -> add(buildJsonObject { put("t", c.t); put("key", c.key); put("value", c.value); put("previous", c.previous); put("note", c.note) }) }
            }
            put("settings", Json.encodeToJsonElement(Settings.serializer(), repo.prefs.load()))
        }
        return Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }
}
