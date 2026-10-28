package be.maikel.xplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.data.Settings
import be.maikel.xplorer.domain.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActions(
    val connect: (String, String) -> Unit,
    val disconnect: () -> Unit,
    val update: ((Settings) -> Settings) -> Unit,
    val setCapital: (Double?, String) -> Unit,
    val export: () -> Unit,
    val wipe: () -> Unit,
)

private fun num(s: String): Double? = s.replace(',', '.').replace("%", "").trim().toDoubleOrNull()
private fun pctTxt(v: Double) = Money.ratio(v).replace(" %", "")

fun LazyListScope.settingsScreen(s: UiState, st: Settings, act: SettingsActions) {
    item {
        SectionTitle("Coinbase en lecture seule")
        Glass {
            if (s.hasKey) {
                Text("Clé connectée", style = MaterialTheme.typography.titleMedium, color = Xp.Gain)
                Muted("Chiffrée par le coffre-fort Android de ce téléphone. Elle sert seulement à lire : aucun ordre, aucun retrait.")
                a(s)?.let { Muted(it) }
                var confirm by remember { mutableStateOf(false) }
                TextButton(onClick = { confirm = true }) { Text("Supprimer la clé et les données Coinbase", color = Xp.Loss) }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false }, containerColor = Xp.Night,
                    title = { Text("Supprimer la clé ?", color = Xp.Ice) },
                    text = { Muted("La clé et l'historique Coinbase sont effacés de ce téléphone immédiatement. Le journal des conseils est gardé.", small = false) },
                    confirmButton = { TextButton(onClick = { confirm = false; act.disconnect() }) { Text("Supprimer", color = Xp.Loss) } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler", color = Xp.Slate) } },
                )
            } else {
                Muted("Sur portal.cdp.coinbase.com › API Keys › Secret API Keys : crée une clé avec uniquement la permission View et l'algorithme ECDSA. Copie le nom et la clé privée ici. Ne la colle jamais dans un chat.", small = false)
                var name by remember { mutableStateOf("") }
                var pem by remember { mutableStateOf("") }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Nom de la clé (organizations/…/apiKeys/…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pem, { pem = it }, label = { Text("Clé privée (-----BEGIN EC PRIVATE KEY-----…)") }, minLines = 3, maxLines = 6,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                s.keyError?.let { Banner(it) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { act.connect(name, pem) }, enabled = !s.loading && name.isNotBlank() && pem.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Xp.Cyan, contentColor = Xp.Abyss)) { Text("Connecter en lecture seule") }
            }
        }
    }
    item {
        SectionTitle("Point de départ du suivi")
        Glass {
            val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            Muted("Le capital, les gains et la courbe sont comptés à partir de cette date : ce que valait ton portefeuille ce jour-là, plus tes virements depuis. L'historique d'avant reste visible à part.", small = false)
            Spacer(Modifier.height(8.dp))
            Text(st.startDate?.let { "Depuis le " + fmt.format(Date(it)) } ?: "Depuis l'ouverture du compte", style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
            var txt by remember(st) { mutableStateOf(st.startDate?.let { fmt.format(Date(it)) } ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(txt, { txt = it.filter { c -> c.isDigit() || c == '/' }.take(10) }, label = { Text("Date (JJ/MM/AAAA)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                TextButton(onClick = { try { fmt.parse(txt)?.time?.let { t -> act.update { it.copy(startDate = t) } } } catch (e: Exception) { } }) { Text("Appliquer", color = Xp.Cyan) }
            }
            val day = 86_400_000L
            val today = System.currentTimeMillis() / day * day
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("1 mois", false, { act.update { it.copy(startDate = today - 30 * day) } })
                Pill("3 mois", false, { act.update { it.copy(startDate = today - 91 * day) } })
                Pill("1 an", false, { act.update { it.copy(startDate = today - 365 * day) } })
                Pill("Tout", st.startDate == null, { act.update { it.copy(startDate = null) } })
            }
        }
    }
    item {
        SectionTitle("Budget et risque")
        Glass {
            var weekly by remember(st) { mutableStateOf(st.weeklyBudget.toInt().toString()) }
            var monthly by remember(st) { mutableStateOf(st.monthlyBudget.toInt().toString()) }
            var maxW by remember(st) { mutableStateOf(pctTxt(st.maxWeightDefault)) }
            var maxLoss by remember(st) { mutableStateOf(pctTxt(st.maxLossPct)) }
            var reserve by remember(st) { mutableStateOf(st.cashReserve.toInt().toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Budget / semaine (€)", weekly, { weekly = it }, Modifier.weight(1f))
                NumField("Budget / mois (€)", monthly, { monthly = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Exposition max / crypto (%)", maxW, { maxW = it }, Modifier.weight(1f))
                NumField("Perte max envisagée (%)", maxLoss, { maxLoss = it }, Modifier.weight(1f))
            }
            NumField("Réserve de liquidités (€)", reserve, { reserve = it }, Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Prudent", "Équilibré", "Dynamique").forEach { t -> Pill(t, st.tolerance == t, { act.update { it.copy(tolerance = t) } }) }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Long terme", st.longTerm, { act.update { it.copy(longTerm = true) } })
                Pill("Trading actif", !st.longTerm, { act.update { it.copy(longTerm = false) } })
            }
            TextButton(onClick = {
                act.update {
                    it.copy(
                        weeklyBudget = num(weekly) ?: it.weeklyBudget, monthlyBudget = num(monthly) ?: it.monthlyBudget,
                        maxWeightDefault = num(maxW)?.div(100)?.coerceIn(0.05, 1.0) ?: it.maxWeightDefault,
                        maxLossPct = num(maxLoss)?.div(100)?.coerceIn(0.01, 1.0) ?: it.maxLossPct,
                        cashReserve = num(reserve) ?: it.cashReserve,
                    )
                }
            }) { Text("Enregistrer", color = Xp.Cyan) }
        }
    }
    item {
        SectionTitle("Répartition cible")
        Glass {
            var edits by remember(st) { mutableStateOf(st.targets.mapValues { pctTxt(it.value) }) }
            var newSym by remember { mutableStateOf("") }
            edits.forEach { (sym, v) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    CoinBadge(sym, 32); Spacer(Modifier.width(10.dp))
                    Text(sym, style = MaterialTheme.typography.titleMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
                    NumField("%", v, { edits = edits + (sym to it) }, Modifier.width(96.dp))
                    TextButton(onClick = { edits = edits - sym }) { Text("✕", color = Xp.Slate) }
                }
            }
            val sum = edits.values.sumOf { num(it) ?: 0.0 }
            Banner("Total : ${"%.0f".format(sum)} %" + if (kotlin.math.abs(sum - 100) > 0.5) " — il faut arriver à 100 %" else "", error = kotlin.math.abs(sum - 100) > 0.5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newSym, { newSym = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, label = { Text("Ajouter (ex. SOL)") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (newSym.isNotBlank()) { edits = edits + (newSym to "0"); newSym = "" } }) { Text("Ajouter", color = Xp.Cyan) }
            }
            TextButton(onClick = { act.update { it.copy(targets = edits.mapValues { e -> (num(e.value) ?: 0.0) / 100 }) } }) { Text("Enregistrer la répartition", color = Xp.Cyan) }
            Muted("Les cryptos à 0 % sont suivies sans être achetées. Ajoute les autres à la liste de surveillance ci-dessous.")
            var watch by remember(st) { mutableStateOf(st.watchlist.joinToString(", ")) }
            OutlinedTextField(watch, { watch = it.uppercase() }, label = { Text("Liste de surveillance") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { act.update { it.copy(watchlist = watch.split(',', ' ').map { w -> w.trim() }.filter { w -> w.isNotEmpty() }.distinct()) } }) { Text("Enregistrer la liste", color = Xp.Cyan) }
        }
    }
    item {
        SectionTitle("Règle GEOD")
        Glass {
            var base by remember(st) { mutableStateOf(st.geodBase.toString()) }
            var mult by remember(st) { mutableStateOf(st.geodMultiple.toInt().toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Prix d'achat (€)", base, { base = it }, Modifier.weight(1f))
                NumField("Vendre à partir de x", mult, { mult = it }, Modifier.weight(1f))
            }
            Muted("Achat seulement. Aucune vente proposée sous ${Money.price(st.geodBase * st.geodMultiple)}.")
            TextButton(onClick = { act.update { it.copy(geodBase = num(base) ?: it.geodBase, geodMultiple = num(mult) ?: it.geodMultiple) } }) { Text("Enregistrer", color = Xp.Cyan) }
        }
    }
    item {
        SectionTitle("Notifications")
        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alertes intelligentes", style = MaterialTheme.typography.bodyLarge, color = Xp.Ice, modifier = Modifier.weight(1f))
                Switch(st.notifications, { v -> act.update { it.copy(notifications = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = Xp.Cyan))
            }
            var qs by remember(st) { mutableStateOf(st.quietStart.toString()) }
            var qe by remember(st) { mutableStateOf(st.quietEnd.toString()) }
            var mx by remember(st) { mutableStateOf(st.maxAlertsPerDay.toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Silence dès (h)", qs, { qs = it }, Modifier.weight(1f))
                NumField("jusqu'à (h)", qe, { qe = it }, Modifier.weight(1f))
                NumField("Max / jour", mx, { mx = it }, Modifier.weight(1f))
            }
            TextButton(onClick = {
                act.update { it.copy(quietStart = num(qs)?.toInt()?.coerceIn(0, 23) ?: it.quietStart, quietEnd = num(qe)?.toInt()?.coerceIn(0, 23) ?: it.quietEnd, maxAlertsPerDay = num(mx)?.toInt()?.coerceIn(1, 50) ?: it.maxAlertsPerDay) }
            }) { Text("Enregistrer", color = Xp.Cyan) }
            Muted("Xplorer’ vérifie environ toutes les heures quand le téléphone est connecté. Android peut espacer ces vérifications en économie d'énergie : ce n'est pas une surveillance permanente. Les doublons sont supprimés et les alertes restent dans l'historique.")
        }
    }
    item {
        SectionTitle("Corrections manuelles")
        Glass {
            val auto = s.analysis?.ledger?.capitalNet
            Muted("Capital apporté calculé : ${auto?.let { Money.eur(it, 0) } ?: "—"} (virements en euros moins retraits). Si c'est faux, indique le vrai montant.", small = false)
            var cap by remember(st) { mutableStateOf(st.capitalOverride?.toInt()?.toString() ?: "") }
            var note by remember { mutableStateOf("") }
            NumField("Capital réellement apporté (€)", cap, { cap = it }, Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Raison (journal d'audit)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { num(cap)?.let { act.setCapital(it, note.ifBlank { "correction manuelle" }) } }) { Text("Appliquer", color = Xp.Cyan) }
                if (st.capitalOverride != null) TextButton(onClick = { act.setCapital(null, "retour au calcul automatique") }) { Text("Revenir au calcul automatique", color = Xp.Slate) }
            }
            if (s.corrections.isNotEmpty()) {
                Text("Journal d'audit", style = MaterialTheme.typography.labelLarge, color = Xp.Ice, modifier = Modifier.padding(top = 8.dp))
                s.corrections.forEach { c ->
                    Muted("${SimpleDateFormat("d MMM yyyy HH:mm", Locale.FRANCE).format(Date(c.t))} · ${c.key} : ${c.previous} → ${c.value} (${c.note})")
                }
            }
        }
    }
    item {
        SectionTitle("Ce qu'Xplorer’ a lu chez Coinbase")
        Glass {
            val ops = s.analysis?.snap?.ops ?: emptyList()
            if (ops.isEmpty()) Muted("Rien de lu pour l'instant.")
            ops.groupBy { it.type to it.cur }.map { (k, v) -> Triple(k, v.size, v.sumOf { o -> if (o.eur.isNaN()) 0.0 else o.eur }) }
                .sortedByDescending { kotlin.math.abs(it.third) }.take(40).forEach { (k, n, eur) ->
                    KV("${k.first} · ${k.second} × $n", Money.signedEur(eur, 0), if (eur >= 0) Xp.Gain else Xp.Loss)
                }
            Muted("Montants signés en euros au moment de chaque opération, tels que donnés par Coinbase.")
        }
    }
    item {
        SectionTitle("Données personnelles")
        Glass {
            Muted("Tout reste sur ce téléphone : pas de serveur Xplorer’, pas de télémétrie, pas de sauvegarde cloud.", small = false)
            TextButton(onClick = act.export) { Text("Exporter mes données (JSON, sans la clé)", color = Xp.Cyan) }
            var confirm by remember { mutableStateOf(false) }
            TextButton(onClick = { confirm = true }) { Text("Tout supprimer", color = Xp.Loss) }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false }, containerColor = Xp.Night,
                title = { Text("Tout supprimer ?", color = Xp.Ice) },
                text = { Muted("Clé, historique, journal des conseils, alertes et réglages sont effacés. C'est définitif.", small = false) },
                confirmButton = { TextButton(onClick = { confirm = false; act.wipe() }) { Text("Tout supprimer", color = Xp.Loss) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler", color = Xp.Slate) } },
            )
        }
    }
    item {
        SectionTitle("À propos")
        Glass {
            KV("Version", be.maikel.xplorer.BuildConfig.VERSION_NAME)
            KV("Permissions Android", "Internet, notifications")
            Muted("Xplorer’ ne contient aucune fonction d'achat, de vente ou de retrait : ce n'est pas une option désactivée, le code n'existe pas. Aucun signal n'est une garantie de bénéfice. Ce n'est pas un conseil financier.")
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun a(s: UiState): String? = s.analysis?.snap?.let { snap ->
    val last = snap.lastSync?.let { SimpleDateFormat("d MMM HH:mm", Locale.FRANCE).format(Date(it)) } ?: "jamais"
    "Dernière synchronisation : $last · ${snap.ops.size} opérations · ${snap.syncedAccounts} comptes lus sur ${snap.totalAccounts}"
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onChange(it.filter { c -> c.isDigit() || c == ',' || c == '.' }) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.padding(vertical = 3.dp))
}
