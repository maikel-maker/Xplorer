package be.maikel.xplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Arena
import be.maikel.xplorer.domain.Money

fun campColor(c: Arena.Camp): Color = when (c) {
    Arena.Camp.BULLS -> Vivid.Bulls
    Arena.Camp.BEARS -> Vivid.Bears
    Arena.Camp.NEUTRE -> Xp.Slate
}

/** Barre de rapport de force : Bulls (vert) à gauche, Bears (rouge) à droite. */
@Composable
fun TugBar(bullShare: Double, modifier: Modifier = Modifier) {
    val b = bullShare.toFloat().coerceIn(0.03f, 0.97f)
    Row(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Xp.Night2)) {
        Box(Modifier.weight(b).fillMaxHeight().background(Brush.horizontalGradient(listOf(Vivid.Bulls.copy(alpha = 0.55f), Vivid.Bulls))))
        Box(Modifier.width(3.dp).fillMaxHeight().background(Xp.Ice))
        Box(Modifier.weight(1f - b).fillMaxHeight().background(Brush.horizontalGradient(listOf(Vivid.Bears, Vivid.Bears.copy(alpha = 0.55f)))))
    }
}

/** Le débat d'une crypto : rapport de force et le vote de chaque signal. */
@Composable
fun BullsBearsPanel(d: Arena.Debate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bulls ${d.bulls}", style = MaterialTheme.typography.titleLarge, color = Vivid.Bulls, fontWeight = FontWeight.Bold)
        Text(d.verdict, style = MaterialTheme.typography.titleMedium, color = Xp.Ice,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp), textAlign = TextAlign.Center)
        Text("${d.bears} Bears", style = MaterialTheme.typography.titleLarge, color = Vivid.Bears, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    TugBar(d.bullShare)
    Spacer(Modifier.height(12.dp))
    d.votes.forEach { v -> signalRow(v) }
}

/** Bulls vs Bears sur l'accueil, crypto par crypto. */
@Composable
fun arenaCard(a: Analysis, onAsset: (String) -> Unit) {
    val held = a.ledger.positions.associate { it.symbol to it.value }
    val syms = a.snap.settings.targets.keys.filter { a.arena.containsKey(it) }.sortedByDescending { held[it] ?: 0.0 }
    if (syms.isEmpty()) return
    var sel by rememberSaveable { mutableStateOf(syms.first()) }
    val d = a.arena[sel] ?: return
    SectionTitle("📊 Bulls vs Bears")
    Glass(padding = 18) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            syms.forEach { s -> Pill(coinName(s), sel == s, { sel = s }) }
        }
        Spacer(Modifier.height(14.dp))
        BullsBearsPanel(d)
        val line = a.arenaPlan?.lines?.firstOrNull { it.symbol == sel }
        if (line != null) {
            Spacer(Modifier.height(4.dp))
            Muted(
                when {
                    line.eur <= 0 -> "Cette semaine : pas d'achat de ${coinName(sel)}. ${line.note}"
                    line.multiplier > 1.05 -> "Cette semaine : ${Money.eur(line.eur, 0)} sur ${coinName(sel)}, part renforcée."
                    line.multiplier < 0.95 -> "Cette semaine : ${Money.eur(line.eur, 0)} sur ${coinName(sel)}, part réduite."
                    else -> "Cette semaine : ${Money.eur(line.eur, 0)} sur ${coinName(sel)}, part normale."
                },
                small = false,
            )
        }
        TextButton(onClick = { onAsset(sel) }) { Text("Analyse complète de ${coinName(sel)} ›", color = Xp.Cyan) }
    }
}

@Composable
private fun signalRow(v: Arena.Vote) {
    val c = campColor(v.camp)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(c.copy(alpha = if (v.abstain) 0.04f else 0.10f))
            .border(1.dp, c.copy(alpha = if (v.abstain) 0.12f else 0.45f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.agent.name, style = MaterialTheme.typography.titleSmall, color = Xp.Ice, modifier = Modifier.weight(1f))
                Tag(if (v.abstain) "Pas de vote" else v.camp.label, c)
            }
            Muted(v.agent.role)
            Spacer(Modifier.height(2.dp))
            Text(v.quote, style = MaterialTheme.typography.bodyMedium, color = Xp.Ice)
        }
    }
}

/** Test honnête de l'arène sur le passé. */
@Composable
fun arenaBacktestCard(a: Analysis) {
    val b = a.arenaBacktest ?: return
    SectionTitle("🧪 Bulls vs Bears a-t-il raison ?")
    Glass {
        val e = b.edge
        Text(
            when {
                e.isNaN() -> "Pas assez d'historique pour juger."
                kotlin.math.abs(e) < 0.005 -> "Sur ${b.weeks} semaines, jeu égal avec une répartition fixe."
                else -> "Sur ${b.weeks} semaines : ${Money.pct(e, true)} par rapport à une répartition fixe."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (e.isNaN() || kotlin.math.abs(e) < 0.005) Xp.Ice else if (e > 0) Vivid.Bulls else Vivid.Bears,
        )
        Spacer(Modifier.height(6.dp))
        Muted("Même budget chaque semaine (${Money.eur(a.snap.settings.weeklyBudget, 0)}), cryptos testées : ${b.symbols.joinToString()}. À chaque date, les signaux ne voient que le passé.")
        Spacer(Modifier.height(4.dp))
        Muted("Sur 8 ans d'historique, l'écart reste proche de 0 % : ça t'aide à répartir sans surcharger une crypto en surchauffe, ça ne promet pas de battre le marché.")
    }
}
