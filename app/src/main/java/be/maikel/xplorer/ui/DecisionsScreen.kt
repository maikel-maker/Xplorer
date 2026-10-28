package be.maikel.xplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.domain.Action
import be.maikel.xplorer.domain.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dFmt = SimpleDateFormat("d MMM yyyy", Locale.FRANCE)

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.decisionsScreen(s: UiState, onBudget: (Double) -> Unit, onAsset: (String) -> Unit) {
    val a = s.analysis ?: run { item { Muted("Chargement…") }; return }
    val plan = a.plan
    item {
        SectionTitle("Que faire cette semaine ?")
        Glass {
            Muted("Ton budget")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(25.0, 50.0, 100.0, 250.0, 500.0, 1000.0).forEach { b -> Pill(Money.eur(b, 0), plan.budget == b, { onBudget(b) }) }
            }
            var free by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(free, { free = it.filter { c -> c.isDigit() } }, label = { Text("Montant libre") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                TextButton(onClick = { free.toDoubleOrNull()?.let(onBudget) }) { Text("Calculer", color = Xp.Cyan) }
            }
            Spacer(Modifier.height(12.dp))
            Text(plan.headline, style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
            plan.cashWarning?.let { Banner(it) }
            Spacer(Modifier.height(6.dp))
            plan.lines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    CoinBadge(l.symbol, 32); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.symbol, style = MaterialTheme.typography.titleMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
                            Text(Money.eur(l.eur, 0), style = MaterialTheme.typography.titleMedium, color = if (l.eur > 0) Xp.Gain else Xp.Slate)
                        }
                        Muted(l.note)
                    }
                }
            }
            if (plan.keepCash > 0 && plan.lines.any { it.eur > 0 }) Muted("${Money.eur(plan.keepCash, 0)} restent en liquidités : rien d'autre ne réunit assez d'arguments.")
            Spacer(Modifier.height(6.dp))
            Muted("Répartition indicative selon ta répartition cible, le score de chaque crypto et tes limites d'exposition. Rien n'est acheté automatiquement.")
        }
    }
    item {
        SectionTitle("Toutes les recommandations")
        Glass(padding = 8) {
            a.recs.values.sortedByDescending { it.score }.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoinBadge(r.symbol, 32); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.symbol, style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                        Muted(if (r.action == Action.INSUFFISANT) "Preuves insuffisantes" else "Score ${r.score}/100 · ${r.zone.lowercase()}")
                    }
                    TextButton(onClick = { onAsset(r.symbol) }) { ActionBadge(r.action) }
                }
            }
        }
    }
    item {
        val graded = s.journal.filter { it.verdict == "juste" || it.verdict == "raté" }
        val good = graded.count { it.verdict == "juste" }
        SectionTitle("Xplorer’ avait-il raison ?")
        Glass {
            if (graded.isEmpty()) {
                Text("Pas encore de note", style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                Muted("Chaque conseil est enregistré tel quel puis noté après 21 jours : un achat est juste si le prix a monté, une vente si le prix a baissé. « Attendre » et « Conserver » ne sont pas notés.")
            } else {
                Text("$good / ${graded.size}", style = MaterialTheme.typography.displayLarge, color = Xp.Gold)
                Muted("conseils justes, soit ${Money.pct(good.toDouble() / graded.size)}. Sous 50 %, Xplorer’ ne fait pas mieux qu'une pièce jetée en l'air.")
            }
            Spacer(Modifier.height(8.dp))
            s.journal.take(40).forEach { j ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${j.symbol} · ${Action.valueOf(j.action).label}", style = MaterialTheme.typography.labelLarge, color = Xp.Ice)
                        Muted("${dFmt.format(Date(j.t))} · ${Money.price(j.price)} · score ${j.score} · ${j.version}")
                    }
                    when (j.verdict) {
                        null -> Tag("noté dans ${((j.t + j.horizonDays * 86_400_000L - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)} j", Xp.Slate)
                        "juste" -> Tag("✓ juste ${Money.pct(j.outcomeMove ?: 0.0, true)}", Xp.Gain)
                        "raté" -> Tag("✗ raté ${Money.pct(j.outcomeMove ?: 0.0, true)}", Xp.Loss)
                        else -> Tag(j.verdict ?: "", Xp.Slate)
                    }
                }
            }
        }
    }
    if (s.intents.isNotEmpty()) item {
        SectionTitle("Achats déclarés")
        Glass {
            s.intents.forEach { i ->
                KV("${i.symbol} · ${Money.eur(i.eur, 0)}", if (i.matchedOpId != null) "retrouvé chez Coinbase" else "en attente", if (i.matchedOpId != null) Xp.Gain else Xp.Amber, dFmt.format(Date(i.t)))
            }
        }
    }
    item {
        SectionTitle("Historique des alertes")
        Glass {
            if (s.alerts.isEmpty()) Muted("Aucune alerte pour l'instant.")
            s.alerts.take(30).forEach { al ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Text(al.title, style = MaterialTheme.typography.labelLarge, color = Xp.Ice)
                    Muted("${SimpleDateFormat("d MMM HH:mm", Locale.FRANCE).format(Date(al.t))} · ${al.body}")
                }
            }
        }
    }
}
