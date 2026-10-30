package be.maikel.xplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Money
import java.util.Calendar
import androidx.compose.runtime.Composable

private fun hello(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bonjour Maïkel"
    in 12..17 -> "Bon après-midi Maïkel"
    else -> "Bonsoir Maïkel"
}

/**
 * Accueil SnipeX : ton argent, la consigne de la semaine, l'arène des agents.
 * Aucun mot technique ici : tout le détail reste dans l'onglet « Détails ».
 */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.simpleHome(
    a: Analysis?,
    hasKey: Boolean,
    onAsset: (String) -> Unit,
    onDetails: () -> Unit,
    onSettings: () -> Unit,
    onDone: (List<Pair<String, Double>>) -> Unit,
) {
    item {
        Text(hello(), style = MaterialTheme.typography.titleLarge, color = Xp.Ice, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp))
    }
    if (a == null) { item { Muted("Je prépare tes chiffres…", small = false) }; return }
    val l = a.ledger

    // 1. Ton argent, en une phrase.
    item {
        Glass(padding = 22) {
            Muted("Tout ton argent sur Coinbase", small = false)
            Text(Money.eur(l.totalEur, 0), style = MaterialTheme.typography.displayLarge, color = Xp.Ice)
            if (hasKey) {
                val g = a.gain
                Spacer(Modifier.height(4.dp))
                Text(
                    if (g >= 0) "Tu as gagné ${Money.eur(g, 0)} depuis le début 🙂" else "Tu es à ${Money.eur(-g, 0)} sous ce que tu as mis. C'est normal en crypto, on vise le long terme.",
                    style = MaterialTheme.typography.bodyLarge, color = if (g >= 0) Xp.Gain else Xp.Ice,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Banner("Connecte ton compte Coinbase (lecture seule) pour voir ton vrai argent. Touche ici.", error = false) { onSettings() }
            }
        }
    }

    // 2. La consigne de la semaine.
    val buys = a.plan.lines.filter { it.eur > 0 }
    item {
        Spacer(Modifier.height(14.dp))
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.linearGradient(listOf(Color(0x66FFC94D), Color(0x40FF4FA3), Color(0x338B5CFF))))
                .border(1.dp, Brush.linearGradient(Vivid.Rainbow), shape)
                .padding(22.dp),
        ) {
            Text("📅  Cette semaine · ${Money.eur(a.plan.budget, 0)}", style = MaterialTheme.typography.titleMedium, color = Xp.Gold)
            Spacer(Modifier.height(10.dp))
            if (buys.isEmpty()) {
                Text("Rien à acheter.", style = MaterialTheme.typography.titleLarge, color = Xp.Ice)
                Spacer(Modifier.height(4.dp))
                Text(a.plan.headline, style = MaterialTheme.typography.bodyLarge, color = Xp.Ice)
            } else {
                Text("Achète :", style = MaterialTheme.typography.bodyLarge, color = Xp.Slate)
                buys.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoinBadge(b.symbol, 30); Spacer(Modifier.width(10.dp))
                        Text(coinName(b.symbol), style = MaterialTheme.typography.titleMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
                        Text(Money.eur(b.eur, 0), style = MaterialTheme.typography.titleLarge, color = Xp.Ice)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Les agents de l'arène ont choisi la répartition. Les ${Money.eur(a.plan.budget, 0)} partent en entier : on ne garde rien de côté.", style = MaterialTheme.typography.bodyMedium, color = Xp.Slate)
                a.plan.cashWarning?.let { Spacer(Modifier.height(8.dp)); Banner(it) }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onDone(buys.map { it.symbol to it.eur }) },
                    colors = ButtonDefaults.buttonColors(containerColor = Xp.Gold, contentColor = Color(0xFF231700)),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("C'est fait, j'ai acheté", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(6.dp))
                Muted("Tu achètes toi-même sur Coinbase. L'app ne touche jamais à ton argent.")
            }
        }
    }

    // 3. Paliers réalistes : 1 ETH, 1 000 € de cryptos…
    a.milestones?.let { m -> item { milestonesCard(m) } }

    // 4. L'arène : bœufs contre ours, crypto par crypto.
    if (a.arena.isNotEmpty()) item { arenaCard(a, onAsset) }

    // 5. L'arène a-t-elle raison ? Test honnête sur le passé.
    if (a.arenaBacktest != null) item { arenaBacktestCard(a) }

    // 6. Pour aller plus loin.
    item {
        Spacer(Modifier.height(14.dp))
        Glass(onClick = onDetails) {
            Text("🔍  Voir les détails", style = MaterialTheme.typography.titleMedium, color = Xp.Cyan)
            Muted("Graphiques, indicateurs, budget libre, historique des conseils.", small = false)
        }
        Spacer(Modifier.height(10.dp))
        Muted("Conseils seulement, jamais de garantie de gain.", Modifier.padding(horizontal = 8.dp), small = false)
    }
}
