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
import be.maikel.xplorer.domain.SmartDca
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
            Muted("Budget de la semaine (1 000 €/mois ≈ 231 €/semaine)")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(50.0, 100.0, 231.0, 500.0, 1000.0).forEach { b -> Pill(Money.eur(b, 0), plan.budget == b, { onBudget(b) }) }
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
            val dca = a.dca
            plan.lines.forEach { l ->
                val line = dca?.lines?.firstOrNull { it.symbol == l.symbol }
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    CoinBadge(l.symbol, 32); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.symbol, style = MaterialTheme.typography.titleMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
                            if (line != null && l.eur > 0 && line.mult != 1.0) Tag("×" + (if (line.mult % 1.0 == 0.0) line.mult.toInt().toString() else line.mult.toString().replace('.', ',')), if (line.mult > 1) Xp.Gain else Xp.Amber)
                            Spacer(Modifier.width(8.dp))
                            Text(Money.eur(l.eur, 0), style = MaterialTheme.typography.titleMedium, color = if (l.eur > 0) Xp.Gain else Xp.Slate)
                        }
                        Muted(l.note)
                    }
                }
            }
            if (dca != null) {
                Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
                KV("Réserve « creux »", Money.eur(dca.reserveStart, 0),
                    Xp.Gold, if (dca.reserveStart > 0.5) "en attente depuis ${dca.reserveDays} j · investie d'office après ${SmartDca.MAX_RESERVE_DAYS} j" else "vide : rien n'attend")
                if (dca.fromReserve >= 1) KV("Pris dans la réserve", Money.eur(dca.fromReserve, 0), Xp.Gain)
                if (dca.toReserve >= 1) KV("Mis en réserve cette semaine", Money.eur(dca.toReserve, 0), Xp.Amber, "à garder en EUR ou USDC sur Coinbase")
                Spacer(Modifier.height(6.dp))
                Muted(dca.explanation)
                Spacer(Modifier.height(6.dp))
                SmartDca.bands.forEach { b ->
                    val lo = SmartDca.bands.getOrNull(SmartDca.bands.indexOf(b) - 1)?.upTo
                    val range = when {
                        lo == null -> "prix sous ${(b.upTo * 100).toInt()} % de sa moyenne 200 j"
                        b.upTo.isInfinite() -> "au-dessus de ${(lo * 100).toInt()} %"
                        else -> "de ${(lo * 100).toInt()} à ${(b.upTo * 100).toInt()} %"
                    }
                    Muted("${b.emoji} ${b.label} · $range")
                }
            }
            Spacer(Modifier.height(6.dp))
            Muted("Rien n'est acheté automatiquement : tu passes les ordres toi-même sur Coinbase.")
        }
    }
    if (a.backtests.isNotEmpty()) item {
        SectionTitle("Test sur le passé : intelligent contre DCA simple")
        Glass {
            Muted("Mêmes versements chaque semaine. On compare la valeur finale (réserve restante comptée en euros). Seule la moyenne 200 j connue à chaque date est utilisée.")
            Spacer(Modifier.height(8.dp))
            a.backtests.forEach { b ->
                val e = b.edge
                KV("${b.symbol} · ${b.weeks} semaines", if (e.isNaN()) "—" else Money.pct(e, true),
                    if (e >= 0) Xp.Gain else Xp.Loss,
                    "crypto accumulée ${Money.pct(b.unitsEdge, true)} · ${Money.eur(b.invested, 0)} versés · réserve finale ${Money.eur(b.cashLeft, 0)}")
            }
            Spacer(Modifier.height(6.dp))
            val wins = a.backtests.count { it.edge > 0 }
            Text("Gagnant sur $wins crypto(s) sur ${a.backtests.size}", style = MaterialTheme.typography.titleMedium, color = if (wins * 2 >= a.backtests.size) Xp.Gain else Xp.Amber)
            Muted("Le passé ne garantit rien. Si le DCA simple fait mieux sur la durée, c'est lui qu'il faut garder.")
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
