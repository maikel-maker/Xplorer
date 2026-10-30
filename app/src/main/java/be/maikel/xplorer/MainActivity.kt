package be.maikel.xplorer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.maikel.xplorer.ui.AppViewModel
import be.maikel.xplorer.ui.AssetScreen
import be.maikel.xplorer.ui.Banner
import be.maikel.xplorer.ui.Muted
import be.maikel.xplorer.ui.SettingsActions
import be.maikel.xplorer.ui.Xp
import be.maikel.xplorer.ui.XplorerTheme
import be.maikel.xplorer.ui.decisionsScreen
import be.maikel.xplorer.ui.simpleHome
import be.maikel.xplorer.ui.overviewScreen
import be.maikel.xplorer.ui.healthScreen
import be.maikel.xplorer.ui.Pill
import be.maikel.xplorer.ui.marketsScreen
import be.maikel.xplorer.ui.portfolioScreen
import be.maikel.xplorer.ui.settingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { XplorerTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshIfStale()
    }
}

private data class Tab(val label: String, val icon: ImageVector)

// 3 onglets seulement. Les réglages s'ouvrent avec la roue dentée en haut à droite.
private val tabs = listOf(
    Tab("Accueil", Icons.Default.Home),
    Tab("Mes cryptos", Icons.Default.Star),
    Tab("Détails", Icons.Default.List),
)
private const val SETTINGS = 3
private val detailTabs = listOf("Ma semaine", "Résumé", "Marchés", "Santé")

@Composable
private fun App(vm: AppViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var asset by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val txt = vm.exportJson()
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray()) }
            snack.showSnackbar("Export enregistré.")
        }
    }
    LaunchedEffect(s.notice) { s.notice?.let { snack.showSnackbar(it); vm.clearNotice() } }
    BackHandler(enabled = asset != null || tab != 0) { if (asset != null) asset = null else tab = 0 }

    asset?.let { sym ->
        AssetScreen(s.analysis, sym, chart, onLoad = { tf -> vm.loadChart(sym, tf) }, onBack = { asset = null }, onIntent = { vm.addIntent(sym, it) })
        return
    }

    Scaffold(
        containerColor = Xp.Abyss,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = Xp.Night, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Xp.Cyan, selectedTextColor = Xp.Ice, indicatorColor = Xp.Night2, unselectedIconColor = Xp.Slate, unselectedTextColor = Xp.Slate),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().background(Xp.Abyss).padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (tab == 0) "Xplorer’" else if (tab == SETTINGS) "Réglages" else tabs[tab].label, style = MaterialTheme.typography.headlineMedium, color = Xp.Ice)
                    Muted(if (s.loading) s.progress.ifEmpty { "Synchronisation…" } else lastSyncText(s.analysis?.snap?.lastSync))
                }
                if (s.loading) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = Xp.Cyan, strokeWidth = 2.dp) }
                else IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualiser", tint = Xp.Ice) }
                IconButton(onClick = { tab = SETTINGS }) { Icon(Icons.Default.Settings, "Réglages", tint = if (tab == SETTINGS) Xp.Cyan else Xp.Ice) }
            }
            s.error?.let { Box(Modifier.padding(horizontal = 16.dp)) { Banner(it) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                when (tab) {
                    0 -> simpleHome(s.analysis, s.hasKey, { asset = it }, { tab = 2; detail = 0 }, { tab = SETTINGS }, { vm.addIntents(it) })
                    1 -> portfolioScreen(s.analysis) { asset = it }
                    2 -> {
                        item {
                            Row(Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detailTabs.forEachIndexed { i, t -> Pill(t, detail == i, { detail = i }) }
                            }
                        }
                        when (detail) {
                            0 -> decisionsScreen(s, { vm.setBudget(it) }, { asset = it })
                            1 -> overviewScreen(s.analysis, s.hasKey, { asset = it }) { t -> if (t == 4) tab = SETTINGS else { tab = 2; detail = 0 } }
                            2 -> marketsScreen(s.analysis) { asset = it }
                            else -> healthScreen(s.analysis)
                        }
                    }
                    else -> settingsScreen(s, vm.settings, SettingsActions(
                        connect = vm::connect, disconnect = vm::disconnect, update = vm::updateSettings,
                        setCapital = vm::setCapital, export = { exporter.launch("xplorer-export.json") }, wipe = vm::wipeEverything,
                    ))
                }
            }
        }
    }
}

private fun lastSyncText(t: Long?): String {
    if (t == null) return "Pas encore synchronisé"
    val min = (System.currentTimeMillis() - t) / 60_000
    return when {
        min < 1 -> "Synchronisé à l'instant"
        min < 60 -> "Synchronisé il y a $min min"
        min < 1440 -> "Synchronisé il y a ${min / 60} h"
        else -> "Synchronisé il y a ${min / 1440} j"
    }
}

