package be.maikel.xplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.maikel.xplorer.domain.Action
import be.maikel.xplorer.domain.Level3

val cardShape = RoundedCornerShape(22.dp)

/** Carte « verre dépoli » : dégradé bleu nuit et filet lumineux. */
@Composable
fun Glass(modifier: Modifier = Modifier, padding: Int = 18, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.fillMaxWidth().clip(cardShape)
        .background(Brush.linearGradient(listOf(Color(0xE6142242), Color(0xCC0B1426))))
        .border(1.dp, Xp.Edge, cardShape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding.dp), content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Xp.Ice, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = true) =
    Text(text, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = Xp.Slate, modifier = modifier)

@Composable
fun KV(label: String, value: String, valueColor: Color = Xp.Ice, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Xp.Slate, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = valueColor, textAlign = TextAlign.End)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Xp.Slate)
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Xp.Ice, sub: String? = null) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x7305080F)).border(1.dp, Xp.Edge, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Xp.Slate, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.85f))
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape)
            .background(if (selected) Xp.Ice else Color(0x7305080F))
            .border(1.dp, if (selected) Xp.Ice else Xp.Edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Xp.Abyss else Xp.Slate)
    }
}

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

fun actionColor(a: Action): Color = when (a) {
    Action.ACHETER, Action.RENFORCER -> Xp.Gain
    Action.CONSERVER -> Xp.Cyan
    Action.ATTENDRE -> Xp.Amber
    Action.ALLEGER -> Color(0xFFFF9452)
    Action.VENDRE -> Xp.Loss
    Action.INSUFFISANT -> Xp.Slate
}

@Composable
fun ActionBadge(a: Action, modifier: Modifier = Modifier) {
    val c = actionColor(a)
    Row(modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f)).padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(7.dp))
        Text(a.label, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

fun levelColor(l: Level3, invert: Boolean = false): Color = when (if (invert) Level3.entries[2 - l.ordinal] else l) {
    Level3.ELEVE -> Xp.Gain; Level3.MOYEN -> Xp.Amber; Level3.FAIBLE -> Xp.Loss
}

private val coinColors = mapOf(
    "BTC" to Color(0xFFF7931A), "ETH" to Color(0xFF8EA2FF), "GEOD" to Color(0xFF3FD8F2), "LINK" to Color(0xFF4C7DFF),
    "TAO" to Color(0xFFCFD8E8), "KRL" to Color(0xFF15B6EA), "SOL" to Color(0xFFB57BFF), "NEAR" to Color(0xFF9AE8C6), "AKT" to Color(0xFFFF6B5E),
)
fun coinColor(sym: String): Color = coinColors[sym] ?: Color(0xFF7F8EA9)

@Composable
fun CoinBadge(sym: String, size: Int = 40) {
    val c = coinColor(sym)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp))
            .background(Brush.radialGradient(listOf(c.copy(alpha = 0.32f), c.copy(alpha = 0.08f))))
            .border(1.dp, c.copy(alpha = 0.25f), RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(sym.take(4), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 10.sp), color = c)
    }
}

/** Barre de progression simple : part actuelle et repère de la cible en or. */
@Composable
fun WeightBar(value: Double, target: Double?, modifier: Modifier = Modifier, color: Color = Xp.Cyan) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x9905080F))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat()).height(6.dp).clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(listOf(Xp.CyanDeep, color))))
        if (target != null && target > 0) {
            Row(Modifier.fillMaxWidth()) {
                if (target < 1) Spacer(Modifier.weight(target.toFloat().coerceAtLeast(0.001f)))
                Box(Modifier.width(2.dp).height(6.dp).background(Xp.Gold))
                if (target < 1) Spacer(Modifier.weight((1 - target).toFloat().coerceAtLeast(0.001f)))
            }
        }
    }
}

@Composable
fun Banner(text: String, error: Boolean = true, onClick: (() -> Unit)? = null) {
    val c = if (error) Xp.Loss else Xp.Cyan
    var m = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
        .background(c.copy(alpha = 0.09f)).border(1.dp, c.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Text(text, m.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = if (error) Color(0xFFFFA9B5) else Xp.Ice)
}

@Composable
fun Bullet(text: String, color: Color = Xp.Slate) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Xp.Ice, modifier = Modifier.widthIn(max = 520.dp))
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(Xp.Edge))

val Spaced = Arrangement.spacedBy(8.dp)
