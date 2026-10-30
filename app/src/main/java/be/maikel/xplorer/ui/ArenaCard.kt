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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Arena
import be.maikel.xplorer.domain.Money

private val Bull = Color(0xFF3EE39A)
private val Bear = Color(0xFFFF5F76)

private fun campColor(c: Arena.Camp): Color = when (c) {
    Arena.Camp.BOEUF -> Bull
    Arena.Camp.OURS -> Bear
    Arena.Camp.NEUTRE -> Xp.Slate
}

/** Barre de rapport de force : bœufs à gauche, ours à droite. */
@Composable
fun TugBar(bullShare: Double, modifier: Modifier = Modifier) {
    val b = bullShare.toFloat().coerceIn(0.03f, 0.97f)
    Row(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Xp.Night2)) {
        Box(Modifier.weight(b).fillMaxHeight().background(Brush.horizontalGradient(listOf(Bull.copy(alpha = 0.55f), Bull))))
        Box(Modifier.width(3.dp).fillMaxHeight().background(Xp.Ice))
        Box(Modifier.weight(1f - b).fillMaxHeight().background(Brush.horizontalGradient(listOf(Bear, Bear.copy(alpha = 0.55f)))))
    }
}

/** L'arène : choix de la crypto, rapport de force, et la tirade de chaque agent. */
@Composable
fun arenaCard(a: Analysis, onAsset: (String) -> Unit) {
    val syms = a.snap.settings.targets.keys.filter { a.arena.containsKey(it) }
    if (syms.isEmpty()) return
    var sel by rememberSaveable { mutableStateOf(syms.firstOrNull { it == "BTC" } ?: syms.first()) }
    val d = a.arena[sel] ?: return
    SectionTitle("🏟️ L'arène : bœufs contre ours")
    Glass(padding = 18) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            syms.forEach { s -> Pill(coinName(s), sel == s, { sel = s }) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🐂 ${d.bulls}", style = MaterialTheme.typography.titleLarge, color = Bull)
            Text(d.verdict, style = MaterialTheme.typography.titleMedium, color = Xp.Ice,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("${d.bears} 🐻", style = MaterialTheme.typography.titleLarge, color = Bear)
        }
        Spacer(Modifier.height(8.dp))
        TugBar(d.bullShare)
        Spacer(Modifier.height(6.dp))
        val line = a.arenaPlan?.lines?.firstOrNull { it.symbol == sel }
        if (line != null) {
            val m = line.multiplier
            Muted(
                when {
                    line.eur <= 0 -> "Résultat : pas d'achat de ${coinName(sel)} cette semaine. ${line.note}"
                    m > 1.05 -> "Résultat : ${Money.eur(line.eur, 0)} sur ${coinName(sel)}, part renforcée (×${"%.2f".format(m).replace('.', ',')})."
                    m < 0.95 -> "Résultat : ${Money.eur(line.eur, 0)} sur ${coinName(sel)}, part réduite (×${"%.2f".format(m).replace('.', ',')})."
                    else -> "Résultat : ${Money.eur(line.eur, 0)} sur ${coinName(sel)}, part normale."
                },
                small = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        d.votes.forEach { v -> agentRow(v) }
        Spacer(Modifier.height(2.dp))
        androidx.compose.material3.TextButton(onClick = { onAsset(sel) }) { Text("Voir le graphique de ${coinName(sel)} ›", color = Xp.Cyan) }
    }
}

@Composable
private fun agentRow(v: Arena.Vote) {
    val c = campColor(v.camp)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(c.copy(alpha = if (v.abstain) 0.04f else 0.09f))
            .border(1.dp, c.copy(alpha = if (v.abstain) 0.12f else 0.35f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Xp.Night2), contentAlignment = Alignment.Center) {
            Text(v.agent.emoji, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.agent.name, style = MaterialTheme.typography.titleSmall, color = Xp.Ice, modifier = Modifier.weight(1f))
                Tag(if (v.abstain) "S'abstient" else v.camp.label, c)
            }
            Muted(v.agent.role)
            Spacer(Modifier.height(4.dp))
            Text("« ${v.quote} »", style = MaterialTheme.typography.bodyMedium, color = Xp.Ice)
        }
    }
}

/** Test honnête de l'arène sur le passé. */
@Composable
fun arenaBacktestCard(a: Analysis) {
    val b = a.arenaBacktest ?: return
    SectionTitle("🧪 L'arène a-t-elle raison ?")
    Glass {
        val e = b.edge
        Text(
            when {
                e.isNaN() -> "Pas assez d'historique pour juger."
                e >= 0.005 -> "Sur ${b.weeks} semaines, l'arène aurait fait ${Money.pct(e, true)} par rapport à une répartition fixe."
                e <= -0.005 -> "Sur ${b.weeks} semaines, l'arène aurait fait ${Money.pct(e, true)} par rapport à une répartition fixe."
                else -> "Sur ${b.weeks} semaines, l'arène fait jeu égal avec une répartition fixe."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (e.isNaN() || kotlin.math.abs(e) < 0.005) Xp.Ice else if (e > 0) Xp.Gain else Xp.Loss,
        )
        Spacer(Modifier.height(6.dp))
        Muted("Même budget chaque semaine (${Money.eur(a.snap.settings.weeklyBudget, 0)}), mêmes jours, cryptos testées : ${b.symbols.joinToString()}. À chaque date, les agents ne voient que le passé.")
        Spacer(Modifier.height(4.dp))
        Muted("Sur 8 ans d'historique, l'écart reste proche de 0 % : l'arène t'aide à répartir sans te ruiner en surchauffe, elle ne promet pas de battre le marché.")
    }
}
