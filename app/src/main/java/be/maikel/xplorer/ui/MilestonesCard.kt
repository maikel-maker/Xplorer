package be.maikel.xplorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.domain.Milestones

/** Palette vive de SnipeX. */
object Vivid {
    val Pink = Color(0xFFFF4FA3)
    val Violet = Color(0xFF8B5CFF)
    val Sky = Color(0xFF3FD8F2)
    val Lime = Color(0xFF7CF29A)
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
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, tl, sz, style = Stroke(sw))
            val sweep = (360.0 * progress.coerceIn(0.0, 1.0)).toFloat()
            if (sweep > 0.5f) drawArc(Brush.sweepGradient(colors + colors.first()), -90f, sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

private fun eta(w: Int?): String = when {
    w == null -> "pas encore dans le plan de la semaine"
    w <= 1 -> "dès cette semaine 🎉"
    w < 9 -> "≈ $w semaines à ce rythme"
    else -> "≈ ${(w + 2) / 4} mois à ce rythme"
}

@Composable
fun milestonesCard(m: Milestones.Summary) {
    SectionTitle("🎯 Mes paliers")

    // 1. Le grand palier : ton patrimoine crypto.
    val w = m.wealth
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B))))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(w.progress, listOf(Color.White, Vivid.Sun, Color.White), 96, 10) {
                Text("${(w.progress * 100).toInt()} %", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Mon trésor crypto", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                Text(Milestones.label(w, w.current), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                val n = w.next
                Text(
                    if (n == null) "Tous les paliers atteints 👑" else "Prochain palier : ${Milestones.label(w, n)}",
                    style = MaterialTheme.typography.bodyLarge, color = Color.White,
                )
                if (n != null) Text(eta(w.weeksToNext), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        Spacer(Modifier.height(14.dp))
        StepDots(w, Color.White)
    }

    // 2. Un palier par crypto, deux par ligne.
    Spacer(Modifier.height(12.dp))
    m.coins.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { t -> CoinMilestone(t, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    // 3. Le compteur de trophées.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(Vivid.Rainbow.map { it.copy(alpha = 0.18f) }))
            .border(1.dp, Brush.horizontalGradient(Vivid.Rainbow), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🏆", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${m.reachedTotal} paliers atteints sur ${m.stepsTotal}", style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
            Muted("Calculé avec tes vrais soldes Coinbase. Les estimations supposent des prix inchangés.")
        }
    }
}

@Composable
private fun CoinMilestone(t: Milestones.Track, modifier: Modifier) {
    val sym = t.symbol ?: return
    val c = coinColor(sym)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.32f), c.copy(alpha = 0.08f), Xp.Night)))
            .border(1.dp, Brush.verticalGradient(listOf(c, c.copy(alpha = 0.15f))), shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProgressRing(t.progress, listOf(c, Vivid.Pink, Vivid.Sun), 74, 8) { CoinBadge(sym, 40) }
        Spacer(Modifier.height(8.dp))
        val n = t.next
        Text(
            if (n == null) "Échelle terminée 👑" else Milestones.label(t, n),
            style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Text(coinName(sym), style = MaterialTheme.typography.labelMedium, color = c)
        Spacer(Modifier.height(4.dp))
        Text("J'ai ${Milestones.label(t, t.current)}", style = MaterialTheme.typography.bodySmall, color = Xp.Ice, textAlign = TextAlign.Center)
        if (n != null) Text(eta(t.weeksToNext), style = MaterialTheme.typography.labelSmall, color = Xp.Slate, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        StepDots(t, c)
    }
}

/** Une pastille par palier : pleine si atteint. */
@Composable
private fun StepDots(t: Milestones.Track, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        t.steps.forEachIndexed { i, _ ->
            val on = i < t.reached
            val isNext = i == t.reached
            Box(
                Modifier.size(if (isNext) 9.dp else 7.dp).clip(CircleShape)
                    .background(if (on) color else color.copy(alpha = if (isNext) 0.45f else 0.15f)),
            )
        }
    }
}
