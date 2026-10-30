package be.maikel.xplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.domain.Action
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.Recommendation

/** Les trois couleurs de décision : vert = achat, orange = attendre, rouge = vente possible. */
object Feu {
    val Vert = Color(0xFF2EE58A)
    val Orange = Color(0xFFFFA62B)
    val Rouge = Color(0xFFFF4D6A)
}

private data class Verdict(val color: Color, val emoji: String, val title: String, val line: String)

private fun verdict(r: Recommendation, sym: String): Verdict = when {
    r.action == Action.INSUFFISANT -> Verdict(Feu.Orange, "🟠", "Pas assez d'infos", "Trop peu d'historique pour juger. Continue ton achat normal de la semaine.")
    r.action.isBuy -> Verdict(Feu.Vert, "🟢", "Bon moment pour acheter", "Les signaux sont plutôt favorables. Ton achat de la semaine tombe bien.")
    r.action.isSell && sym == "GEOD" -> Verdict(Feu.Rouge, "🔴", "Ça chauffe", "Les signaux sont défavorables. Ta règle GEOD : on ne vend pas avant ×20, on garde.")
    r.action.isSell -> Verdict(Feu.Rouge, "🔴", "Prudence, vente possible", "Les signaux sont défavorables. Pas de panique : ton DCA continue, mais n'en rajoute pas.")
    else -> Verdict(Feu.Orange, "🟠", "On attend", "Rien de spécial. Fais simplement ton achat prévu de la semaine.")
}

/** Les familles d'indicateurs, dites simplement. */
private fun plain(name: String): String = when (name) {
    "Tendance de fond" -> "Sur plusieurs mois, le prix…"
    "Valorisation" -> "Par rapport à l'année, le prix est…"
    "Momentum" -> "Ces dernières semaines, ça…"
    "Risque" -> "Le marché est…"
    else -> name
}

private fun plainAnswer(name: String, score: Double): Pair<String, Color> {
    val good = score >= 55; val bad = score <= 45
    val c = if (good) Feu.Vert else if (bad) Feu.Rouge else Feu.Orange
    val t = when (name) {
        "Tendance de fond" -> if (good) "monte ✅" else if (bad) "descend ❌" else "hésite ➖"
        "Valorisation" -> if (good) "bon marché ✅" else if (bad) "plutôt cher ❌" else "dans la moyenne ➖"
        "Momentum" -> if (good) "repart à la hausse ✅" else if (bad) "s'essouffle ❌" else "se stabilise ➖"
        "Risque" -> if (good) "calme ✅" else if (bad) "très agité ❌" else "normalement agité ➖"
        else -> if (good) "favorable ✅" else if (bad) "défavorable ❌" else "neutre ➖"
    }
    return t to c
}

/** La recommandation en clair, pour monsieur tout le monde. Le détail technique reste accessible en dessous. */
@Composable
fun SimpleVerdictCard(r: Recommendation, sym: String, planEur: Double?, onIntent: () -> Unit) {
    val v = verdict(r, sym)
    val shape = RoundedCornerShape(26.dp)
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(v.color.copy(alpha = 0.35f), Color(0xFF2A1B5C).copy(alpha = 0.6f), Xp.Night)))
            .border(2.dp, Brush.linearGradient(listOf(v.color, Vivid.Violet, Vivid.Sky)), shape)
            .padding(20.dp),
    ) {
        Text("${v.emoji}  ${v.title}", style = MaterialTheme.typography.headlineMedium, color = v.color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(v.line, style = MaterialTheme.typography.bodyLarge, color = Color.White)

        // Le thermomètre : rouge (vendre) → orange (attendre) → vert (acheter).
        if (r.action != Action.INSUFFISANT) {
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
                val w = maxWidth
                Box(
                    Modifier.fillMaxWidth().height(12.dp).align(Alignment.Center).clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(listOf(Feu.Rouge, Feu.Orange, Feu.Vert))),
                )
                val x = (w - 22.dp) * (r.score / 100f).coerceIn(0f, 1f)
                Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(Color.White).border(3.dp, v.color, CircleShape))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Vendre", style = MaterialTheme.typography.labelMedium, color = Feu.Rouge, modifier = Modifier.weight(1f))
                Text("Attendre", style = MaterialTheme.typography.labelMedium, color = Feu.Orange, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Acheter", style = MaterialTheme.typography.labelMedium, color = Feu.Vert, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }

        // En clair : une ligne par famille d'indicateurs.
        if (r.components.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("En clair", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            r.components.forEach { c ->
                val (ans, col) = plainAnswer(c.name, c.score)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                        .background(col.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                    Spacer(Modifier.width(10.dp))
                    Text(plain(c.name), style = MaterialTheme.typography.bodyMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
                    Text(ans, style = MaterialTheme.typography.bodyMedium, color = col, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (planEur != null && planEur > 0) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Vivid.Sun.copy(alpha = 0.25f), Vivid.Pink.copy(alpha = 0.2f))))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📅", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Text("Cette semaine, ton plan : ${Money.eur(planEur, 0)}", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onIntent,
                colors = ButtonDefaults.buttonColors(containerColor = Feu.Vert, contentColor = Color(0xFF00210F)),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("J'ai acheté", style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(8.dp))
        Muted("Un conseil, jamais une garantie.")
    }
}
