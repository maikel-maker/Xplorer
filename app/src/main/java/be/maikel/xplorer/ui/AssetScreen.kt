package be.maikel.xplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Action
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.Recommendation
import be.maikel.xplorer.domain.Trade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fullFmt = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)

private fun intervalOf(tf: TF): Long = when (tf) {
    TF.M1 -> 60_000L; TF.M5 -> 300_000L; TF.M15 -> 900_000L; TF.M30 -> 1_800_000L
    TF.H1 -> 3_600_000L; TF.H4 -> 14_400_000L; TF.D1 -> 86_400_000L; TF.W1 -> 7 * 86_400_000L; TF.MO -> 31 * 86_400_000L
}

@Composable
fun AssetScreen(a: Analysis?, sym: String, chart: ChartData?, onLoad: (TF) -> Unit, onBack: () -> Unit, onIntent: (Double) -> Unit) {
    var tf by remember { mutableStateOf(TF.D1) }
    var ov by remember { mutableStateOf(Overlays()) }
    var picked by remember { mutableStateOf<List<Trade>?>(null) }
    var full by remember { mutableStateOf(false) }
    var intent by remember { mutableStateOf(false) }
    LaunchedEffect(sym, tf) { onLoad(tf) }
    val pos = a?.ledger?.positions?.firstOrNull { it.symbol == sym }
    val trades = a?.ledger?.trades?.filter { it.symbol == sym } ?: emptyList()
    val rec = a?.recs?.get(sym)
    val price = a?.snap?.prices?.get(sym)

    @Composable
    fun chartBlock(height: Int) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TF.entries.forEach { t -> Pill(t.label, tf == t, { tf = t }) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Pill("Moyennes", ov.ma, { ov = ov.copy(ma = !ov.ma) })
            Pill("Bollinger", ov.bollinger, { ov = ov.copy(bollinger = !ov.bollinger) })
            Pill("Fibonacci", ov.fib, { ov = ov.copy(fib = !ov.fib) })
            Pill("Supports", ov.levels, { ov = ov.copy(levels = !ov.levels) })
            Pill("Prix de revient", ov.avg, { ov = ov.copy(avg = !ov.avg) })
        }
        Spacer(Modifier.height(8.dp))
        val c = chart?.takeIf { it.symbol == sym && it.tf == tf }
        when {
            c == null || (c.loading && c.candles.isEmpty()) -> Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) { Muted("Chargement des bougies…") }
            c.error != null && c.candles.isEmpty() -> Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) { Muted(c.error) }
            else -> CandleChart(c.candles, intervalOf(tf), trades, pos?.avgCost, ov, height = height) { picked = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            Tag("▲ achat", Xp.Gain); Tag("▼ vente", Xp.Loss)
            if (ov.ma) { Tag("MM20", androidx.compose.ui.graphics.Color(0xFF8EA2FF)); Tag("MM50", Xp.Amber); Tag("MM200", androidx.compose.ui.graphics.Color(0xFFFF8BD1)) }
        }
        Muted("Pince pour zoomer, glisse pour te déplacer, touche un triangle pour le détail de l'opération.")
    }

    LazyColumn(Modifier.fillMaxSize().background(Xp.Abyss), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Xp.Ice) }
                CoinBadge(sym, 36); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(sym, style = MaterialTheme.typography.headlineMedium, color = Xp.Ice)
                    val ch = a?.snap?.change24?.get(sym)
                    Text((price?.let { Money.price(it) } ?: "—") + (ch?.let { "  " + Money.pct(it, true) + " en 24 h" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = if ((ch ?: 0.0) >= 0) Xp.Gain else Xp.Loss)
                }
                IconButton(onClick = { onLoad(tf) }) { Icon(Icons.Default.Refresh, "Recharger", tint = Xp.Slate) }
            }
        }
        item {
            Glass(Modifier.padding(top = 12.dp), padding = 12) {
                chartBlock(300)
                TextButton(onClick = { full = true }) { Text("Plein écran", color = Xp.Cyan) }
            }
        }
        rec?.let { r -> item { RecommendationCard(r, a?.plan?.lines?.firstOrNull { it.symbol == sym }?.eur) { intent = true } } }
        a?.arena?.get(sym)?.let { d ->
            item {
                SectionTitle("📊 Bulls vs Bears")
                Glass(padding = 16) { BullsBearsPanel(d) }
            }
        }
        rec?.let { r -> item { WhyCard(r) } }
        pos?.let { p ->
            item {
                SectionTitle("Ta position")
                Glass {
                    KV("Quantité", Money.qty(p.qty))
                    KV("Valeur", Money.eur(p.value))
                    KV("Prix de revient moyen", if (p.avgCost.isNaN()) "inconnu" else Money.price(p.avgCost), sub = "frais inclus")
                    p.unrealized?.let { KV("Plus-value latente", Money.signedEur(it), if (it >= 0) Xp.Gain else Xp.Loss) }
                    KV("Plus-value réalisée", Money.signedEur(p.realized), if (p.realized >= 0) Xp.Gain else Xp.Loss)
                    KV("Frais payés", Money.eur(p.fees))
                    KV("Part des cryptos", Money.pct(p.weight), sub = a?.snap?.settings?.targets?.get(sym)?.let { "cible ${Money.pct(it)}" })
                    if (p.unknownQty > 0) Muted("${Money.qty(p.unknownQty)} sans historique : prix de revient inconnu pour cette part.")
                }
            }
        }
        if (trades.isNotEmpty()) item {
            SectionTitle("Tes opérations")
            Glass {
                trades.reversed().take(60).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tag(if (t.buy) "Achat" else "Vente", if (t.buy) Xp.Gain else Xp.Loss)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${Money.qty(t.qty)} à ${Money.price(t.unitEur)}", style = MaterialTheme.typography.bodyMedium, color = Xp.Ice)
                            Muted(SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date(t.t)))
                        }
                        Text(Money.eur(t.totalEur), style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    picked?.let { list ->
        AlertDialog(
            onDismissRequest = { picked = null },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Fermer", color = Xp.Cyan) } },
            containerColor = Xp.Night,
            title = { Text(if (list.size > 1) "${list.size} opérations sur cette bougie" else if (list[0].buy) "Achat de $sym" else "Vente de $sym", color = Xp.Ice) },
            text = {
                Column {
                    list.forEach { t ->
                        KV("Date", fullFmt.format(Date(t.t)))
                        KV("Type", (if (t.buy) "Achat" else "Vente") + " (" + t.type.replace('_', ' ') + ")")
                        KV("Quantité", Money.qty(t.qty))
                        KV("Prix unitaire", Money.price(t.unitEur))
                        KV("Valeur totale", Money.eur(t.totalEur))
                        KV("Frais", if (t.feeEur > 0) Money.eur(t.feeEur) else "non communiqués")
                        t.realizedEur?.let { KV("Plus-value réalisée", Money.signedEur(it), if (it >= 0) Xp.Gain else Xp.Loss) }
                        Muted("Référence Coinbase : ${t.ref}")
                        if (list.size > 1) Divider()
                    }
                }
            },
        )
    }
    if (full) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Xp.Abyss).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$sym · ${tf.label}", style = MaterialTheme.typography.titleLarge, color = Xp.Ice, modifier = Modifier.weight(1f))
                    IconButton(onClick = { full = false }) { Icon(Icons.Default.Close, "Fermer", tint = Xp.Ice) }
                }
                chartBlock(560)
            }
        }
    }
    if (intent) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { intent = false },
            containerColor = Xp.Night,
            title = { Text("J'ai acheté du $sym", color = Xp.Ice) },
            text = {
                Column {
                    Muted("Note ton achat. Ce n'est qu'une déclaration : il sera rapproché de l'exécution réelle à la prochaine synchronisation Coinbase.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(txt, { txt = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } }, label = { Text("Montant en euros") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { txt.replace(',', '.').toDoubleOrNull()?.let { onIntent(it) }; intent = false }) { Text("Enregistrer", color = Xp.Cyan) }
            },
            dismissButton = { TextButton(onClick = { intent = false }) { Text("Annuler", color = Xp.Slate) } },
        )
    }
}

@Composable
fun RecommendationCard(r: Recommendation, planEur: Double?, onIntent: () -> Unit) {
    SectionTitle("Recommandation")
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBadge(r.action)
            Spacer(Modifier.weight(1f))
            if (r.action != Action.INSUFFISANT) Text("${r.score}/100", style = MaterialTheme.typography.headlineMedium, color = actionColor(r.action))
        }
        Spacer(Modifier.height(10.dp))
        if (r.action == Action.INSUFFISANT) {
            Text("Je ne dispose pas de preuves suffisantes pour recommander une opération.", style = MaterialTheme.typography.bodyLarge, color = Xp.Ice)
            r.dataNotes.forEach { Bullet(it, Xp.Amber) }
            return@Glass
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Qualité du signal", "${r.score}/100", Modifier.weight(1f))
            Stat("Confiance", r.confidence.label, Modifier.weight(1f), levelColor(r.confidence))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Risque", r.risk.label, Modifier.weight(1f), levelColor(r.risk, invert = true))
            Stat("Données", r.dataQuality.label, Modifier.weight(1f), levelColor(r.dataQuality))
        }
        Spacer(Modifier.height(6.dp))
        Muted("Le score mesure la qualité du signal, pas une probabilité de gain : 80/100 ne veut pas dire 80 % de chances.")
        r.veto?.let { Spacer(Modifier.height(8.dp)); Banner("Veto du gardien du risque : $it") }
        if (planEur != null && planEur > 0 && r.action.isBuy) {
            Spacer(Modifier.height(8.dp))
            KV("Montant indicatif cette semaine", Money.eur(planEur, 0), Xp.Gain, "frais estimés ≈ ${Money.eur(planEur * 0.012)} au marché, ${Money.eur(planEur * 0.006)} en ordre limite")
        }
        Spacer(Modifier.height(6.dp))
        if (r.pros.isNotEmpty()) { Text("Pour", style = MaterialTheme.typography.labelLarge, color = Xp.Gain); r.pros.forEach { Bullet(it, Xp.Gain) } }
        if (r.cons.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text("Contre", style = MaterialTheme.typography.labelLarge, color = Xp.Loss); r.cons.forEach { Bullet(it, Xp.Loss) } }
        Spacer(Modifier.height(8.dp))
        KV("Invalidation", "", sub = null); Muted(r.invalidation)
        KV("Confirmation", "", sub = null); Muted(r.confirmation)
        KV("Horizon", r.horizon)
        r.risks.forEach { Bullet(it, Xp.Amber) }
        Spacer(Modifier.height(6.dp))
        Muted("Analyse du ${fullFmt.format(Date(r.at))}, stratégie ${r.strategyVersion}. Frais : barème de base Coinbase Advanced estimé, à vérifier dans ton compte.")
        if (r.action.isBuy) TextButton(onClick = onIntent) { Text("J'ai acheté", color = Xp.Cyan) }
    }
}

@Composable
fun WhyCard(r: Recommendation) {
    if (r.components.isEmpty()) return
    SectionTitle("Pourquoi ?")
    Glass {
        r.components.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.labelLarge, color = Xp.Ice, modifier = Modifier.weight(1f))
                Text("${c.score.toInt()}/100 · poids ${(c.weight * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = Xp.Slate)
            }
            WeightBar(c.score / 100, null, Modifier.padding(vertical = 6.dp), if (c.score >= 55) Xp.Gain else if (c.score <= 45) Xp.Loss else Xp.Cyan)
            c.notes.forEach { Muted("• $it") }
            Spacer(Modifier.height(10.dp))
        }
        r.timeframeConflict?.let { Banner("Conflit entre horizons : $it", error = false) }
        if (r.dataNotes.isNotEmpty()) { Text("Données manquantes ou fragiles", style = MaterialTheme.typography.labelLarge, color = Xp.Amber); r.dataNotes.forEach { Muted("• $it") } }
        Spacer(Modifier.height(6.dp))
        Muted("Chaque famille d'indicateurs ne vote qu'une fois, pour ne pas compter deux fois la même information. Le score change quand ces familles changent : c'est ce qui explique un changement d'avis.")
    }
}
