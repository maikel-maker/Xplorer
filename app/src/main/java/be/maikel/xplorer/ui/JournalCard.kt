package be.maikel.xplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.DcaJournal
import be.maikel.xplorer.domain.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val dayFmt = SimpleDateFormat("d MMM", Locale.FRANCE).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Bandeau « ne panique pas » quand le Bitcoin chute fort sur 7 jours. */
@Composable
fun calmBanner(drop: Double) {
    val shape = RoundedCornerShape(20.dp)
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Feu.Orange.copy(alpha = 0.30f), Vivid.Violet.copy(alpha = 0.25f))))
            .border(1.dp, Feu.Orange, shape).padding(16.dp),
    ) {
        Text("🧘  Le marché baisse fort", style = MaterialTheme.typography.titleMedium, color = Feu.Orange, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Bitcoin ${Money.pct(drop, true)} en 7 jours. C'est là que ton DCA achète moins cher. Continue ton plan, ne vends pas sous le coup de la peur.",
            style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

/** Le journal du DCA : semaines faites, montants réels et frais. */
@Composable
fun journalCard(a: Analysis) {
    val j = a.journal ?: return
    if (j.weeks.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    SectionTitle("📒 Mon journal DCA")
    Glass(padding = 18) {
        // Série : semaines faites d'affilée, en partant de la dernière semaine terminée.
        val finished = j.weeks.drop(1)
        val streak = finished.takeWhile { it.done }.size + (if (j.weeks.first().done) 1 else 0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${j.doneWeeks} semaine(s) faite(s) sur ${j.weeks.size}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Muted("${Money.eur(j.totalBought, 0)} achetés depuis le départ")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("🔥 $streak", style = MaterialTheme.typography.headlineMedium, color = if (streak > 0) Vivid.Sun else Xp.Slate)
                Muted("d'affilée")
            }
        }
        Spacer(Modifier.height(12.dp))
        // Une pastille par semaine, la plus ancienne à gauche.
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            j.weeks.take(12).reversed().forEach { w ->
                val c = when { w.done -> Feu.Vert; w.partial -> Feu.Orange; else -> Feu.Rouge.copy(alpha = 0.6f) }
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(c))
            }
        }
        Spacer(Modifier.height(4.dp))
        Muted("🟩 faite · 🟧 partielle · 🟥 manquée (12 dernières semaines)")
        Spacer(Modifier.height(12.dp))

        val shown = if (all) j.weeks else j.weeks.take(4)
        shown.forEachIndexed { i, w -> weekRow(w, current = i == 0 && j.weeks.first() == w) }
        if (j.weeks.size > 4) TextButton(onClick = { all = !all }) { Text(if (all) "Moins ▲" else "Toutes les semaines ▼", color = Xp.Cyan) }

        // Frais.
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Vivid.Pink.copy(alpha = 0.18f), Vivid.Violet.copy(alpha = 0.18f))))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("💸", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Frais payés : ${Money.eur(j.totalFees, 2)} (${Money.pct(j.feePct)})", style = MaterialTheme.typography.titleSmall, color = Color.White)
                if (j.limitSaving >= 1) Muted("Avec des ordres limites sur Coinbase Advanced, tu aurais pu garder ≈ ${Money.eur(j.limitSaving, 0)}. C'est le gain le plus sûr qui existe.")
                else Muted("Bien joué : tes frais sont déjà bas. Continue avec les ordres limites.")
            }
        }
    }
}

@Composable
private fun weekRow(w: DcaJournal.Week, current: Boolean) {
    val c = when { w.done -> Feu.Vert; w.partial -> Feu.Orange; current -> Vivid.Sky; else -> Feu.Rouge }
    val status = when {
        w.done -> "✅ Faite"
        w.partial -> "🟠 Partielle"
        current -> "⏳ En cours"
        else -> "❌ Manquée"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Semaine du ${dayFmt.format(Date(w.start))}", style = MaterialTheme.typography.bodyMedium, color = Xp.Ice)
            if (w.bySymbol.isNotEmpty()) Muted(w.bySymbol.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${Money.eur(it.value, 0)}" })
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${Money.eur(w.bought, 0)} / ${Money.eur(w.budget, 0)}", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text(status, style = MaterialTheme.typography.labelMedium, color = c)
        }
    }
}
