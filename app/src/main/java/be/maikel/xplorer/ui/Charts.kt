package be.maikel.xplorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.Indicators
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.domain.Trade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Series(val name: String, val points: List<Pair<Long, Double>>, val color: Color, val fill: Boolean = false, val dashed: Boolean = false, val width: Float = 2f)

private val dayFmt = SimpleDateFormat("d MMM yy", Locale.FRANCE)
private val timeFmt = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE)

/** Courbes simples (valeur du portefeuille, BTC, capital) avec lecture au doigt. */
@Composable
fun LineChart(series: List<Series>, modifier: Modifier = Modifier, height: Int = 170) {
    val tm = rememberTextMeasurer()
    var touch by remember { mutableStateOf<Float?>(null) }
    val all = series.flatMap { it.points }.filter { !it.second.isNaN() }
    if (all.size < 2) {
        Box(modifier.fillMaxWidth().height(height.dp)) { Muted("Pas encore assez de données pour tracer la courbe.") }
        return
    }
    val t0 = all.minOf { it.first }; val t1 = all.maxOf { it.first }
    var v0 = all.minOf { it.second }; var v1 = all.maxOf { it.second }
    val pad = ((v1 - v0) * 0.08).takeIf { it > 0 } ?: (abs(v1) * 0.05 + 1)
    v0 -= pad; v1 += pad
    Canvas(
        modifier.fillMaxWidth().height(height.dp)
            .pointerInput(series) {
                detectDragGestures(onDragEnd = { touch = null }, onDragCancel = { touch = null }) { ch, _ -> touch = ch.position.x }
            }
            .pointerInput(series) { detectTapGestures(onPress = { touch = it.x; tryAwaitRelease(); touch = null }) },
    ) {
        val w = size.width; val h = size.height - 18.dp.toPx()
        fun x(t: Long) = if (t1 == t0) w / 2 else ((t - t0).toFloat() / (t1 - t0)) * w
        fun y(v: Double) = (h * (1 - (v - v0) / (v1 - v0))).toFloat()
        for (s in series) {
            val pts = s.points.filter { !it.second.isNaN() }
            if (pts.size < 2) continue
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
            if (s.fill) {
                val area = Path().apply { addPath(path); lineTo(x(pts.last().first), h); lineTo(x(pts.first().first), h); close() }
                drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), Color.Transparent), 0f, h))
            }
            drawPath(path, s.color, style = Stroke(width = s.width.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) else null))
        }
        val st = TextStyle(color = Xp.Slate, fontSize = 10.sp, fontFamily = Display)
        drawText(tm, dayFmt.format(Date(t0)), Offset(0f, h + 4.dp.toPx()), st)
        val endLabel = tm.measure(dayFmt.format(Date(t1)), st)
        drawText(endLabel, topLeft = Offset(w - endLabel.size.width, h + 4.dp.toPx()))
        touch?.let { tx ->
            val t = t0 + ((tx / w).coerceIn(0f, 1f) * (t1 - t0)).toLong()
            drawLine(Xp.Slate.copy(alpha = 0.6f), Offset(tx, 0f), Offset(tx, h), 1.dp.toPx())
            val lines = mutableListOf(dayFmt.format(Date(t)))
            for (s in series) {
                val p = s.points.minByOrNull { abs(it.first - t) } ?: continue
                if (!p.second.isNaN()) {
                    lines += "${s.name} ${Money.eur(p.second)}"
                    drawCircle(s.color, 4.dp.toPx(), Offset(x(p.first), y(p.second)))
                }
            }
            tooltip(tm, lines, tx, w)
        }
    }
}

private fun DrawScope.tooltip(tm: androidx.compose.ui.text.TextMeasurer, lines: List<String>, x: Float, w: Float) {
    val st = TextStyle(color = Xp.Ice, fontSize = 11.sp, fontFamily = Display)
    val layouts = lines.map { tm.measure(it, st) }
    val bw = layouts.maxOf { it.size.width } + 20.dp.toPx()
    val bh = layouts.sumOf { it.size.height } + 14.dp.toPx()
    val left = if (x + bw + 12 > w) x - bw - 8 else x + 8
    drawRoundRect(Color(0xF20B1426), Offset(left, 4f), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
    var yy = 4f + 7.dp.toPx()
    for (l in layouts) { drawText(l, topLeft = Offset(left + 10.dp.toPx(), yy)); yy += l.size.height }
}

data class Overlays(val ma: Boolean = true, val bollinger: Boolean = false, val fib: Boolean = false, val levels: Boolean = true, val avg: Boolean = true)

/**
 * Graphique en chandeliers avec volumes, achats (triangle vert sous la bougie) et ventes
 * (triangle rouge au-dessus), ligne de prix de revient, indicateurs en surimpression.
 * Pincer pour zoomer, glisser pour se déplacer, toucher un marqueur pour le détail.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    intervalMs: Long,
    trades: List<Trade>,
    avgCost: Double?,
    overlays: Overlays,
    modifier: Modifier = Modifier,
    height: Int = 320,
    onTrades: (List<Trade>) -> Unit,
) {
    val tm = rememberTextMeasurer()
    var visible by remember(candles.size) { mutableIntStateOf(min(candles.size, 90).coerceAtLeast(10)) }
    var offset by remember(candles.size) { mutableFloatStateOf(0f) } // en bougies depuis la droite
    var cross by remember { mutableStateOf<Float?>(null) }
    if (candles.size < 2) {
        Box(modifier.fillMaxWidth().height(height.dp)) { Muted("Pas de bougies disponibles.") }
        return
    }
    val closes = DoubleArray(candles.size) { candles[it].close }
    val ma20 = remember(candles) { Indicators.sma(closes, 20) }
    val ma50 = remember(candles) { Indicators.sma(closes, 50) }
    val ma200 = remember(candles) { Indicators.sma(closes, 200) }
    val boll = remember(candles) { Indicators.bollinger(closes) }
    val fib = remember(candles) { Indicators.fibonacci(candles.takeLast(250)) }
    val levels = remember(candles) { Indicators.levels(candles.takeLast(250), candles.last().close) }

    // Rattache chaque opération à la bougie qui la contient.
    val byCandle = remember(candles, trades) {
        val m = HashMap<Int, MutableList<Trade>>()
        val first = candles.first().t; val lastEnd = candles.last().t + intervalMs
        for (tr in trades) {
            if (tr.t < first || tr.t >= lastEnd) continue
            var lo = 0; var hi = candles.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (candles[mid].t <= tr.t) lo = mid else hi = mid - 1 }
            m.getOrPut(lo) { mutableListOf() }.add(tr)
        }
        m
    }

    Canvas(
        modifier.fillMaxWidth().height(height.dp)
            .pointerInput(candles) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val n = (visible / zoom).roundToInt().coerceIn(12, candles.size)
                    visible = n
                    val per = size.width / n
                    offset = (offset + pan.x / per).coerceIn(0f, (candles.size - n).toFloat().coerceAtLeast(0f))
                    cross = null
                }
            }
            .pointerInput(candles, trades) {
                detectTapGestures(
                    onTap = { pos ->
                        val n = visible
                        val end = candles.size - offset.roundToInt()
                        val start = max(0, end - n)
                        val per = size.width.toFloat() / n
                        val idx = start + (pos.x / per).toInt()
                        val hits = (idx - 1..idx + 1).flatMap { byCandle[it] ?: emptyList() }
                        if (hits.isNotEmpty()) onTrades(hits) else cross = if (cross == null) pos.x else null
                    },
                )
            },
    ) {
        val n = visible
        val end = (candles.size - offset.roundToInt()).coerceIn(n.coerceAtMost(candles.size), candles.size)
        val start = max(0, end - n)
        val view = candles.subList(start, end)
        val w = size.width - 58.dp.toPx()
        val top = 8.dp.toPx()
        val volH = size.height * 0.16f
        val priceH = size.height - volH - 26.dp.toPx()
        var lo = view.minOf { it.low }; var hi = view.maxOf { it.high }
        val pad = (hi - lo) * 0.08 + hi * 0.0005
        lo -= pad; hi += pad
        val per = w / n
        fun x(i: Int) = (i - start) * per + per / 2
        fun y(v: Double) = (top + priceH * (1 - (v - lo) / (hi - lo))).toFloat()

        // grille et axe des prix
        val st = TextStyle(color = Xp.Slate, fontSize = 10.sp, fontFamily = Display)
        for (k in 0..4) {
            val v = lo + (hi - lo) * k / 4
            val yy = y(v)
            drawLine(Xp.Edge, Offset(0f, yy), Offset(w, yy), 1f)
            drawText(tm, Money.price(v), Offset(w + 6.dp.toPx(), yy - 7.dp.toPx()), st)
        }
        // volumes
        val vmax = view.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
        val volTop = top + priceH + 4.dp.toPx()
        view.forEachIndexed { j, c ->
            val i = start + j
            val vh = (c.volume / vmax * volH).toFloat()
            val col = if (c.close >= c.open) Xp.Gain else Xp.Loss
            drawRect(col.copy(alpha = 0.25f), Offset(x(i) - per * 0.35f, volTop + volH - vh), Size(per * 0.7f, vh))
        }
        // surimpressions
        fun line(arr: DoubleArray, color: Color, width: Float = 1.4f, dashed: Boolean = false) {
            val p = Path(); var started = false
            for (i in start until end) {
                val v = arr[i]; if (v.isNaN()) continue
                if (!started) { p.moveTo(x(i), y(v)); started = true } else p.lineTo(x(i), y(v))
            }
            if (started) drawPath(p, color, style = Stroke(width.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null))
        }
        if (overlays.bollinger) { line(boll.upper, Xp.Slate.copy(alpha = 0.6f), 1f); line(boll.lower, Xp.Slate.copy(alpha = 0.6f), 1f); line(boll.mid, Xp.Slate.copy(alpha = 0.4f), 1f, true) }
        if (overlays.ma) { line(ma20, Color(0xFF8EA2FF), 1.2f); line(ma50, Xp.Amber, 1.2f); line(ma200, Color(0xFFFF8BD1), 1.4f) }
        fun hline(v: Double, color: Color, label: String, dashed: Boolean = true) {
            if (v < lo || v > hi) return
            val yy = y(v)
            drawLine(color, Offset(0f, yy), Offset(w, yy), 1.2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(9f, 7f)) else null)
            val l = tm.measure(label, TextStyle(color = color, fontSize = 9.5.sp, fontFamily = Display))
            drawText(l, topLeft = Offset(4.dp.toPx(), yy - l.size.height - 1))
        }
        if (overlays.fib && fib != null) for ((r, v) in fib.levels) hline(v, Xp.Gold.copy(alpha = 0.85f), "Fibo ${Money.ratio(r)}")
        if (overlays.levels) for (l in levels) hline(l.price, if (l.support) Xp.Gain.copy(alpha = 0.7f) else Xp.Loss.copy(alpha = 0.7f), if (l.support) "Support" else "Résistance")
        if (overlays.avg && avgCost != null && !avgCost.isNaN()) hline(avgCost, Xp.Gold, "Ton prix de revient ${Money.price(avgCost)}", dashed = false)

        // bougies
        view.forEachIndexed { j, c ->
            val i = start + j
            val up = c.close >= c.open
            val col = if (up) Xp.Gain else Xp.Loss
            drawLine(col, Offset(x(i), y(c.high)), Offset(x(i), y(c.low)), max(1f, per * 0.12f))
            val yo = y(c.open); val yc = y(c.close)
            drawRect(col, Offset(x(i) - per * 0.36f, min(yo, yc)), Size(per * 0.72f, max(1.5f, abs(yo - yc))))
        }
        // marqueurs : plusieurs opérations sur une bougie sont empilées
        val tri = 6.dp.toPx()
        for ((i, list) in byCandle) {
            if (i < start || i >= end) continue
            val c = candles[i]
            list.filter { it.buy }.forEachIndexed { k, _ ->
                val cy = y(c.low) + 6.dp.toPx() + k * (tri * 1.6f)
                val p = Path().apply { moveTo(x(i), cy); lineTo(x(i) - tri, cy + tri * 1.4f); lineTo(x(i) + tri, cy + tri * 1.4f); close() }
                drawPath(p, Xp.Gain); drawPath(p, Xp.Abyss, style = Stroke(1.dp.toPx()))
            }
            list.filter { !it.buy }.forEachIndexed { k, _ ->
                val cy = y(c.high) - 6.dp.toPx() - k * (tri * 1.6f)
                val p = Path().apply { moveTo(x(i), cy); lineTo(x(i) - tri, cy - tri * 1.4f); lineTo(x(i) + tri, cy - tri * 1.4f); close() }
                drawPath(p, Xp.Loss); drawPath(p, Xp.Abyss, style = Stroke(1.dp.toPx()))
            }
        }
        // dates
        val fmt = if (intervalMs < 86_400_000L) timeFmt else dayFmt
        drawText(tm, fmt.format(Date(view.first().t)), Offset(0f, size.height - 14.dp.toPx()), st)
        val endL = tm.measure(fmt.format(Date(view.last().t)), st)
        drawText(endL, topLeft = Offset(w - endL.size.width, size.height - 14.dp.toPx()))
        // réticule
        cross?.let { cx ->
            val i = (start + (cx / per).toInt()).coerceIn(start, end - 1)
            val c = candles[i]
            drawLine(Xp.Slate, Offset(x(i), top), Offset(x(i), top + priceH), 1f)
            tooltip(tm, listOf(fmt.format(Date(c.t)), "Ouverture ${Money.price(c.open)}", "Haut ${Money.price(c.high)}", "Bas ${Money.price(c.low)}", "Clôture ${Money.price(c.close)}"), x(i), w)
        }
    }
}
