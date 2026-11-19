package com.coinportracker.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.coinportracker.ui.theme.LocalExtra
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Immutable
data class Series(val name: String, val color: Color, val points: List<Pair<Long, Double?>>, val dashed: Boolean = false, val fill: Boolean = false)

/**
 * Gráfico de linhas próprio (Canvas): toque/arraste para ler valores, pinça para zoom horizontal,
 * legenda, lacunas (null) não interpoladas — dados ausentes não são inventados.
 */
@Composable
fun LineChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 200.dp,
    xLabel: (Long) -> String,
    yLabel: (Double) -> String,
    showLegend: Boolean = true,
    zeroLine: Boolean = false,
) {
    val all = remember(series) { series.flatMap { s -> s.points.mapNotNull { it.second } } }
    if (all.isEmpty() || series.all { it.points.size < 2 }) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("Dados insuficientes para o gráfico", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val n = series.maxOf { it.points.size }
    var zoom by remember(series) { mutableFloatStateOf(1f) }
    var pan by remember(series) { mutableFloatStateOf(1f) } // fração direita visível (1 = mais recente)
    var touchX by remember { mutableStateOf<Float?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val reveal by animateFloatAsState(1f, tween(500), label = "reveal")
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val wPx = constraints.maxWidth.toFloat()
            val visible = max(2, (n / zoom).toInt())
            val end = (((n - visible) * pan).toInt() + visible).coerceIn(visible, n)
            val start = end - visible
            val windowVals = series.flatMap { s -> s.points.subList(min(start, s.points.size), min(end, s.points.size)).mapNotNull { it.second } }.ifEmpty { all }
            var lo = windowVals.min(); var hi = windowVals.max()
            if (zeroLine) { lo = min(lo, 0.0); hi = max(hi, 0.0) }
            if (hi - lo < 1e-12) { hi += 1.0; lo -= 1.0 }
            val padY = (hi - lo) * 0.08; lo -= padY; hi += padY
            val idx = touchX?.let { tx -> (start + ((tx / wPx) * (visible - 1)).toInt()).coerceIn(start, end - 1) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(series) { detectTransformGestures { _, panOff, z, _ -> zoom = (zoom * z).coerceIn(1f, max(1f, n / 8f)); if (zoom > 1f) pan = (pan - panOff.x / size.width / zoom).coerceIn(0f, 1f) } }
                    .pointerInput(series) { detectTapGestures(onPress = { o -> touchX = o.x; tryAwaitRelease(); touchX = null }) }
                    .pointerInput(series) { detectDragGesturesAfterLongPress(onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { c, _ -> touchX = c.position.x } }
            ) {
                val h = size.height
                fun y(v: Double) = (h - ((v - lo) / (hi - lo) * h)).toFloat()
                fun x(i: Int) = ((i - start).toFloat() / (visible - 1).coerceAtLeast(1)) * size.width
                for (g in 0..3) { val gy = h * g / 3f; drawLine(grid.copy(alpha = 0.35f), Offset(0f, gy), Offset(size.width, gy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))) }
                if (zeroLine && lo < 0 && hi > 0) drawLine(labelColor.copy(alpha = 0.6f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.5f)
                for (s in series) {
                    val path = Path(); var started = false
                    val fillPath = Path(); var firstX = 0f; var lastX = 0f
                    val last = min(end, s.points.size)
                    val upto = start + ((last - start) * reveal).toInt()
                    for (i in start until upto) {
                        val v = s.points[i].second
                        if (v == null) { started = false; continue }
                        val px = x(i); val py = y(v)
                        if (!started) { path.moveTo(px, py); if (s.fill && fillPath.isEmpty) { fillPath.moveTo(px, h); firstX = px }; started = true } else path.lineTo(px, py)
                        if (s.fill) fillPath.lineTo(px, py)
                        lastX = px
                    }
                    if (s.fill && !fillPath.isEmpty) {
                        fillPath.lineTo(lastX, h); fillPath.lineTo(firstX, h); fillPath.close()
                        drawPath(fillPath, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0.0f))))
                    }
                    drawPath(path, s.color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
                }
                if (idx != null) {
                    val px = x(idx)
                    drawLine(labelColor.copy(alpha = 0.7f), Offset(px, 0f), Offset(px, h), 1f)
                    for (s in series) s.points.getOrNull(idx)?.second?.let { v -> drawCircle(Color.Black, 5.dp.toPx(), Offset(px, y(v))); drawCircle(s.color, 3.5.dp.toPx(), Offset(px, y(v))) }
                }
            }
            Text(yLabel(hi - padY), style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.align(Alignment.TopEnd))
            Text(yLabel(lo + padY), style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.align(Alignment.BottomEnd))
            if (idx != null) {
                val ts = series.firstNotNullOfOrNull { it.points.getOrNull(idx)?.first }
                Column(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f)).padding(8.dp)) {
                    ts?.let { Text(xLabel(it), style = MaterialTheme.typography.labelSmall, color = labelColor) }
                    series.forEach { s -> Text("${s.name}: ${s.points.getOrNull(idx)?.second?.let(yLabel) ?: "sem dado"}", style = MaterialTheme.typography.labelMedium, color = s.color) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val first = series.firstOrNull()?.points
            Text(first?.firstOrNull()?.first?.let(xLabel) ?: "", style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.weight(1f))
            if (zoom > 1f) Text("zoom ${"%.1f".format(zoom)}× · arraste com dois dedos", style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.weight(1f))
            Text(first?.lastOrNull()?.first?.let(xLabel) ?: "", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        if (showLegend && series.size > 1) Legend(series.map { it.name to it.color })
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    FlowRowLite(items.map { (n, c) -> @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, top = 4.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp))
            Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowLite(children: List<@Composable () -> Unit>) { FlowRow { children.forEach { it() } } }

@Immutable
data class DonutSlice(val label: String, val value: Double, val color: Color, val valueText: String)

/** Rosca com seleção por toque. */
@Composable
fun DonutChart(slices: List<DonutSlice>, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 150.dp, center: String = "") {
    val total = slices.sumOf { it.value }
    var selected by remember(slices) { mutableIntStateOf(-1) }
    val sweep by animateFloatAsState(1f, tween(700), label = "sweep")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().pointerInput(slices) {
                detectTapGestures { o ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val d = o - c
                    if (sqrt(d.x * d.x + d.y * d.y) < this.size.width / 2f) {
                        var ang = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())) + 90; if (ang < 0) ang += 360
                        var acc = 0.0
                        selected = slices.indexOfFirst { s -> acc += s.value / total * 360; ang <= acc }.let { if (it == selected) -1 else it }
                    }
                }
            }) {
                if (total <= 0) return@Canvas
                val stroke = this.size.minDimension * 0.14f
                var startA = -90f
                slices.forEachIndexed { i, s ->
                    val a = (s.value / total * 360f).toFloat() * sweep
                    val w = if (i == selected) stroke * 1.35f else stroke
                    drawArc(s.color, startA + 1f, max(a - 2f, 0.5f), false, topLeft = Offset(stroke, stroke), size = Size(this.size.width - stroke * 2, this.size.height - stroke * 2), style = Stroke(w, cap = StrokeCap.Butt))
                    startA += a
                }
            }
            val sel = slices.getOrNull(selected)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sel?.label ?: center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (sel != null) Text("%.1f%%".format(sel.value / total * 100), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            slices.take(8).forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).pointerInput(Unit) { detectTapGestures { selected = if (selected == i) -1 else i } }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(s.color)); Spacer(Modifier.width(8.dp))
                    Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("%.1f%%".format(s.value / total * 100), style = MaterialTheme.typography.labelMedium, color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (slices.size > 8) Text("+${slices.size - 8} outros", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Barras com sinal (resultado por ativo/período). Toque para ver o valor. */
@Composable
fun BarChart(bars: List<Triple<String, Double, String>>, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 160.dp) {
    if (bars.isEmpty()) { Text("Sem dados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val gain = LocalExtra.current.gain; val loss = LocalExtra.current.loss
    var sel by remember(bars) { mutableIntStateOf(-1) }
    val maxAbs = bars.maxOf { kotlin.math.abs(it.second) }.takeIf { it > 0 } ?: 1.0
    val hasNeg = bars.any { it.second < 0 }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).pointerInput(bars) { detectTapGestures { o -> sel = (o.x / (size.width / bars.size)).toInt().coerceIn(0, bars.size - 1) } }) {
            val bw = size.width / bars.size
            val base = if (hasNeg) size.height / 2 else size.height
            val scale = (if (hasNeg) size.height / 2 else size.height) * 0.95f
            bars.forEachIndexed { i, (_, v, _) ->
                val h = (v / maxAbs * scale).toFloat()
                val c = (if (v >= 0) gain else loss).copy(alpha = if (sel == -1 || sel == i) 0.9f else 0.35f)
                val top = if (v >= 0) base - h else base
                drawRoundRect(c, Offset(i * bw + bw * 0.18f, top), Size(bw * 0.64f, kotlin.math.abs(h).coerceAtLeast(1.5f)), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            }
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, base), Offset(size.width, base), 1f)
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { (l, _, _) -> Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        bars.getOrNull(sel)?.let { Text("${it.first}: ${it.third}", style = MaterialTheme.typography.labelLarge, color = if (it.second >= 0) gain else loss, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * Sparkline do Mercado (7 dias). Faixa vertical mínima de 3% do preço ([sparkRange]): stablecoins (±0,3%) ficam
 * retas em vez de virarem ruído. Caminho montado uma vez por dado e tamanho (drawWithCache), nunca por quadro.
 */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) { Box(modifier); return }
    // 168 pontos (7 d por hora) em ~50 dp viram uma faixa serrilhada; médias por blocos (até 48 pontos) mantêm a forma
    val pts = remember(values) { sparkDownsample(values, 48) }
    Box(modifier.drawWithCache {
        val values = pts
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (v in values) { if (v < lo) lo = v; if (v > hi) hi = v }
        val (l, r) = com.coinportracker.ui.components.sparkRange(lo, hi)
        val inset = size.height * 0.06f; val h = size.height - 2 * inset
        val p = Path()
        values.forEachIndexed { i, v -> val x = i / (values.size - 1f) * size.width; val y = (inset + h - (v - l) / r * h).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind { drawPath(p, color, style = stroke) }
    })
}

/** Médias por blocos para no máximo [max] pontos (mantém primeiro e último valor exatos). */
internal fun sparkDownsample(v: List<Double>, max: Int): List<Double> {
    if (v.size <= max || max < 3) return v
    val out = ArrayList<Double>(max)
    out += v.first()
    val inner = max - 2
    val step = (v.size - 2).toDouble() / inner
    for (b in 0 until inner) {
        val from = 1 + (b * step).toInt(); val to = (1 + ((b + 1) * step).toInt()).coerceAtMost(v.size - 1).coerceAtLeast(from + 1)
        var s = 0.0; for (i in from until to) s += v[i]; out += s / (to - from)
    }
    out += v.last()
    return out
}
