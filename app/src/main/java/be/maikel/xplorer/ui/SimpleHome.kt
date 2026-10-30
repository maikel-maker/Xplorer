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
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.SmartDca
import java.util.Calendar

private fun feuColor(f: SmartDca.Feu): Color = when (f) {
    SmartDca.Feu.VERT -> Xp.Gain
    SmartDca.Feu.JAUNE -> Color(0xFFF2D35B)
    SmartDca.Feu.ORANGE -> Xp.Amber
}

private fun hello(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bonjour Maïkel"
    in 12..17 -> "Bon après-midi Maïkel"
    else -> "Bonsoir Maïkel"
}

/**
 * Accueil simplifié : ton argent, la consigne de la semaine, un feu par crypto.
 * Aucun mot technique ici : tout le détail reste dans l'onglet « Détails ».
 */
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
    val dca = a.dca
    item {
        Spacer(Modifier.height(14.dp))
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.linearGradient(listOf(Color(0x33E8BE6E), Color(0x14E8BE6E))))
                .border(1.dp, Xp.Gold.copy(alpha = 0.45f), shape)
                .padding(22.dp),
        ) {
            Text("📅  Cette semaine", style = MaterialTheme.typography.titleMedium, color = Xp.Gold)
            Spacer(Modifier.height(10.dp))
            if (buys.isEmpty()) {
                Text("Tu n'achètes rien.", style = MaterialTheme.typography.titleLarge, color = Xp.Ice)
                Spacer(Modifier.height(4.dp))
                Text("Les prix sont trop hauts. Garde ${Money.eur(a.plan.budget, 0)} de côté sur Coinbase : ils serviront quand ça baissera.", style = MaterialTheme.typography.bodyLarge, color = Xp.Ice)
            } else {
                Text("Achète :", style = MaterialTheme.typography.bodyLarge, color = Xp.Slate)
                buys.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoinBadge(b.symbol, 30); Spacer(Modifier.width(10.dp))
                        Text(coinName(b.symbol), style = MaterialTheme.typography.titleMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
                        Text(Money.eur(b.eur, 0), style = MaterialTheme.typography.titleLarge, color = Xp.Ice)
                    }
                }
                if (dca != null && dca.toReserve >= 1) {
                    Spacer(Modifier.height(6.dp))
                    Text("Et garde ${Money.eur(dca.toReserve, 0)} de côté : certains prix sont trop hauts.", style = MaterialTheme.typography.bodyMedium, color = Xp.Ice)
                }
                if (dca != null && dca.fromReserve >= 1) {
                    Spacer(Modifier.height(6.dp))
                    Text("Les prix sont bas : tu utilises ${Money.eur(dca.fromReserve, 0)} de l'argent mis de côté.", style = MaterialTheme.typography.bodyMedium, color = Xp.Ice)
                }
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

    // 3. Un feu par crypto.
    item {
        SectionTitle("Tes cryptos en un coup d'œil")
        val syms = (a.snap.settings.targets.keys + l.positions.filter { it.value > 1 }.map { it.symbol })
            .filter { it != "USDC" && it != "EURC" }.distinct()
        Glass(padding = 8) {
            syms.forEachIndexed { i, sym ->
                val f = SmartDca.feu(a.mayer[sym])
                val buy = a.plan.lines.firstOrNull { it.symbol == sym }?.eur ?: 0.0
                val pos = l.positions.firstOrNull { it.symbol == sym }
                val target = a.snap.settings.targets[sym] ?: 0.0
                val enough = target > 0 && pos != null && pos.weight >= target
                val sentence = when {
                    buy > 0 -> "Achète ${Money.eur(buy, 0)} cette semaine."
                    target <= 0 -> "Pas dans ton plan, on le garde à l'œil."
                    enough -> "Tu en as déjà assez, on attend."
                    f == SmartDca.Feu.ORANGE -> "Trop cher pour l'instant, on attend."
                    else -> "Rien à faire cette semaine."
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onAsset(sym) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(feuColor(f)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(coinName(sym), style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                        Text("${f.emoji} ${f.label}", style = MaterialTheme.typography.bodyMedium, color = feuColor(f))
                        Text(sentence, style = MaterialTheme.typography.bodyMedium, color = Xp.Slate)
                    }
                    pos?.let { Text(Money.eur(it.value, 0), style = MaterialTheme.typography.titleMedium, color = Xp.Ice) }
                }
                if (i < syms.size - 1) Divider()
            }
        }
    }

    // 4. Pour aller plus loin.
    item {
        Spacer(Modifier.height(14.dp))
        Glass(onClick = onDetails) {
            Text("🔍  Voir les détails", style = MaterialTheme.typography.titleMedium, color = Xp.Cyan)
            Muted("Graphiques, indicateurs, test sur le passé, historique des conseils.", small = false)
        }
        Spacer(Modifier.height(10.dp))
        Muted("Conseils seulement, jamais de garantie de gain.", Modifier.padding(horizontal = 8.dp), small = false)
    }
}
