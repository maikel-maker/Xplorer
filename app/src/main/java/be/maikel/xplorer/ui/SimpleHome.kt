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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Achievements
import be.maikel.xplorer.domain.SwingAlgo
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.SmartDca
import java.util.Calendar
import androidx.compose.runtime.Composable

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
                if (dca != null && dca.toReserve >= SmartDca.MIN_ORDER) {
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

    // 3. Réussites : série, niveau, médailles.
    a.achievements?.let { ach -> item { achievementsCard(ach) } }

    // 4. Un feu par crypto.
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

    // 5. L'algorithme « marché mixte » : achats et ventes simulés sur le graphique.
    if (a.swing.isNotEmpty()) item { swingCard(a) }

    // 6. Pour aller plus loin.
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


@Composable
private fun Progress(v: Double, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Xp.Night2)) {
        Box(Modifier.fillMaxWidth(v.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
    }
}

private fun tierColor(t: Achievements.Tier): Color = when (t) {
    Achievements.Tier.AUCUN -> Xp.Slate
    Achievements.Tier.BRONZE -> Color(0xFFD08A57)
    Achievements.Tier.ARGENT -> Color(0xFFC9D3E3)
    Achievements.Tier.OR -> Xp.Gold
    Achievements.Tier.DIAMANT -> Xp.Cyan
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun achievementsCard(ach: Achievements.Summary) {
    SectionTitle("🏆 Mes réussites")
    Glass(padding = 20) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Niveau ${ach.level} · ${ach.levelName}", style = MaterialTheme.typography.titleLarge, color = Xp.Gold)
                Muted("${ach.points} points · prochain niveau à ${ach.nextLevelAt}", small = false)
            }
            Text(if (ach.streakWeeks > 0) "🔥 ${ach.streakWeeks}" else "🔥 0", style = MaterialTheme.typography.headlineMedium, color = if (ach.streakWeeks > 0) Xp.Amber else Xp.Slate)
        }
        Spacer(Modifier.height(8.dp))
        Progress((ach.points % 400) / 400.0, Xp.Gold)
        Spacer(Modifier.height(6.dp))
        Text(
            when (ach.streakWeeks) {
                0 -> "Achète cette semaine pour lancer ta série 🔥"
                1 -> "1 semaine de série. Continue la semaine prochaine !"
                else -> "${ach.streakWeeks} semaines d'affilée. Ne casse pas la série !"
            },
            style = MaterialTheme.typography.bodyMedium, color = Xp.Ice,
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            ach.badges.forEach { b ->
                val c = tierColor(b.tier)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = if (b.unlocked) 0.14f else 0.05f))
                        .border(1.dp, c.copy(alpha = if (b.unlocked) 0.5f else 0.15f), RoundedCornerShape(16.dp)).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(b.tier.emoji, style = MaterialTheme.typography.headlineMedium)
                    Text(b.title, style = MaterialTheme.typography.labelMedium, color = if (b.unlocked) Xp.Ice else Xp.Slate, textAlign = TextAlign.Center, maxLines = 2)
                    if (b.next != null) {
                        Spacer(Modifier.height(4.dp))
                        Progress(b.progress, c)
                        Text("${b.value.toInt()}/${b.next.toInt()}${if (b.unit == "€") " €" else ""}", style = MaterialTheme.typography.labelSmall, color = Xp.Slate)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Muted("Les médailles se débloquent toutes seules avec tes vrais achats sur Coinbase.")
    }
}

@Composable
private fun swingCard(a: Analysis) {
    val syms = a.swing.keys.toList()
    var sel by rememberSaveable { mutableStateOf(syms.firstOrNull { it == "BTC" } ?: syms.first()) }
    val r = a.swing[sel] ?: a.swing.values.first()
    SectionTitle("📈 L'algo pour marché mixte")
    Glass {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            syms.forEach { s -> Pill(coinName(s), sel == s, { sel = s }) }
        }
        Spacer(Modifier.height(10.dp))
        val candles = a.snap.daily[r.symbol].orEmpty().takeLast(365)
        val from = candles.firstOrNull()?.t ?: 0L
        LineChart(
            listOf(Series(coinName(r.symbol), candles.map { it.t to it.close }, Xp.Cyan, fill = true)),
            height = 200,
            marks = r.events.filter { it.t >= from }.map { Mark(it.t, it.price, it.buy) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
            Tag("▲ achat de l'algo", Xp.Gain); Tag("▼ vente de l'algo", Xp.Loss)
        }
        Spacer(Modifier.height(10.dp))
        val e = r.edge
        val months = r.days / 30
        Text(
            if (e >= 0) "Sur $months mois, l'algo aurait fait ${Money.pct(e, true)} de mieux qu'acheter chaque semaine sans réfléchir."
            else "Sur $months mois, l'algo aurait fait ${Money.pct(-e)} de moins qu'acheter chaque semaine sans réfléchir.",
            style = MaterialTheme.typography.bodyLarge, color = if (e >= 0) Xp.Gain else Xp.Loss,
        )
        Spacer(Modifier.height(6.dp))
        val last = r.events.lastOrNull()
        Text(
            when {
                r.today != null -> "🔔 Signal récent : ${if (r.today.buy) "achat" else "vente de 10 %"}. ${r.today.reason}."
                last != null -> "Dernier signal : ${if (last.buy) "achat" else "vente"} le ${java.text.SimpleDateFormat("d MMM", java.util.Locale.FRANCE).format(java.util.Date(last.t))}. Pas de signal aujourd'hui."
                else -> "Aucun signal sur la période."
            },
            style = MaterialTheme.typography.bodyMedium, color = Xp.Ice,
        )
        if (r.symbol == "GEOD") Muted("Geodnet : l'algo n'affiche que des achats (ta règle : jamais de vente sous ×20).")
        Spacer(Modifier.height(6.dp))
        Muted("${SwingAlgo.NAME} : achète quand ça a beaucoup baissé, vend 10 % quand ça s'emballe sans vraie tendance. Simulation sur le passé avec ${Money.eur(a.snap.settings.weeklyBudget, 0)}/semaine, frais compris. Il perd quand le marché monte fort et longtemps.")
    }
}
