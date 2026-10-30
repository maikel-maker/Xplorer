package be.maikel.xplorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Action
import be.maikel.xplorer.domain.Milestones
import be.maikel.xplorer.domain.Money

/** Palette vive de SnipeX. Vert = Bulls, rouge = Bears. */
object Vivid {
    val Bulls = Color(0xFF2EE58A)
    val Bears = Color(0xFFFF4D6A)
    val Pink = Color(0xFFFF4FA3)
    val Violet = Color(0xFF8B5CFF)
    val Sky = Color(0xFF3FD8F2)
    val Sun = Color(0xFFFFC94D)
    val Coral = Color(0xFFFF7A59)
    val Rainbow = listOf(Sun, Coral, Pink, Violet, Sky)
}

/** Anneau de progression en dégradé. */
@Composable
fun ProgressRing(progress: Double, colors: List<Color>, size: Int, stroke: Int, content: @Composable () -> Unit) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.dp.toPx()
            val d = this.size.minDimension - sw
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(d, d)
            drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, tl, sz, style = Stroke(sw))
            val sweep = (360.0 * progress.coerceIn(0.0, 1.0)).toFloat()
            if (sweep > 0.5f) drawArc(Brush.sweepGradient(colors + colors.first()), -90f, sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Barre de progression fine en dégradé. */
@Composable
fun GradientBar(progress: Double, colors: List<Color>, modifier: Modifier = Modifier) {
    val p = progress.toFloat().coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.08f))) {
        if (p > 0.005f) Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(colors)))
    }
}

private fun eta(w: Int?): String = when {
    w == null -> "pas d'achat prévu cette semaine"
    w <= 1 -> "atteint avec l'achat de cette semaine 🎉"
    w < 9 -> "≈ $w semaines à ce rythme"
    else -> "≈ ${(w + 2) / 4} mois à ce rythme"
}

/** Le grand palier : le trésor crypto total. */
@Composable
fun wealthCard(m: Milestones.Summary) {
    val w = m.wealth
    val shape = RoundedCornerShape(26.dp)
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B))))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressRing(w.progress, listOf(Color.White, Vivid.Sun, Color.White), 92, 10) {
            Text("${(w.progress * 100).toInt()} %", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Mon trésor crypto", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
            Text(Milestones.label(w, w.current), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            val n = w.next
            Text(if (n == null) "Tous les paliers atteints 👑" else "Prochain palier : ${Milestones.label(w, n)}", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            if (n != null) Text(eta(w.weeksToNext), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            Text("${m.reachedTotal} paliers atteints sur ${m.stepsTotal}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Une carte par crypto, de la plus grosse à la plus petite. Touche une carte pour l'analyse complète. */
@Composable
fun cryptoCards(a: Analysis, onAsset: (String) -> Unit) {
    val m = a.milestones ?: return
    val total = a.ledger.cryptoEur
    val tracks = m.coins.associateBy { it.symbol }
    val syms = m.coins.mapNotNull { it.symbol }
        .sortedByDescending { s -> a.ledger.positions.firstOrNull { it.symbol == s }?.value ?: 0.0 }
    SectionTitle("💎 Mes cryptos")
    syms.forEach { sym ->
        val t = tracks[sym] ?: return@forEach
        val pos = a.ledger.positions.firstOrNull { it.symbol == sym }
        val c = coinColor(sym)
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape)
                .background(Brush.linearGradient(listOf(c.copy(alpha = 0.30f), Xp.Night, Xp.Night)))
                .border(1.dp, Brush.linearGradient(listOf(c, c.copy(alpha = 0.15f))), shape)
                .clickable { onAsset(sym) }
                .padding(16.dp),
        ) {
            // En-tête : nom et valeur.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(sym, 40); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(coinName(sym), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Muted("${Money.qty(pos?.qty ?: 0.0)} $sym")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Money.eur(pos?.value ?: 0.0, 2), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    val gain = pos?.let { p -> if (p.costBasis > 0 && p.trackedQty > 0 && p.price > 0) p.trackedQty * p.price / p.costBasis - 1 else null }
                    if (gain != null) Text(Money.pct(gain, true), style = MaterialTheme.typography.labelLarge, color = if (gain >= 0) Vivid.Bulls else Vivid.Bears)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Chiffres clés.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Fact("Part", if (total > 0 && pos != null) Money.pct(pos.value / total) else "0 %")
                Fact("Cible", Money.pct(a.snap.settings.targets[sym] ?: 0.0))
                Fact("Prix moyen", pos?.avgCost?.takeIf { it > 0 }?.let { Money.price(it) } ?: "—")
                Fact("Prix actuel", a.snap.prices[sym]?.let { Money.price(it) } ?: "—")
            }
            Spacer(Modifier.height(12.dp))
            // Palier.
            val n = t.next
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (n == null) "Échelle terminée 👑" else "🎯 Prochain palier : ${Milestones.label(t, n)}", style = MaterialTheme.typography.labelLarge, color = Xp.Ice, modifier = Modifier.weight(1f))
                Text("${(t.progress * 100).toInt()} %", style = MaterialTheme.typography.labelLarge, color = c)
            }
            Spacer(Modifier.height(6.dp))
            GradientBar(t.progress, listOf(c.copy(alpha = 0.7f), c))
            if (n != null) { Spacer(Modifier.height(4.dp)); Muted(eta(t.weeksToNext)) }
            Spacer(Modifier.height(12.dp))
            // Petite analyse technique.
            val rec = a.recs[sym]
            val d = a.arena[sym]
            val bull = d != null && d.score > 0.15
            val bear = d != null && d.score < -0.15
            val tone = when {
                rec != null && rec.action.isSell -> Vivid.Bears
                rec != null && rec.action.isBuy -> Vivid.Bulls
                bull -> Vivid.Bulls
                bear -> Vivid.Bears
                else -> Xp.Slate
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.12f))
                    .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Analyse technique", style = MaterialTheme.typography.labelMedium, color = Xp.Slate)
                    Text(
                        when {
                            rec == null || rec.action == Action.INSUFFISANT -> "Pas assez de données"
                            else -> "${rec.action.label} · score ${rec.score}/100"
                        },
                        style = MaterialTheme.typography.titleSmall, color = tone,
                    )
                }
                if (d != null) Text("Bulls ${d.bulls} · ${d.bears} Bears", style = MaterialTheme.typography.labelMedium, color = Xp.Ice)
            }
            Spacer(Modifier.height(6.dp))
            Text("Voir l'analyse complète ›", style = MaterialTheme.typography.labelLarge, color = c)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Xp.Slate)
        Text(value, style = MaterialTheme.typography.labelLarge, color = Xp.Ice)
    }
}
