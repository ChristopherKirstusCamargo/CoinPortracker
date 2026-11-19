package com.coinportracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gráfico de linha interativo (CoinStats/Phantom): área com degradê, entrada animada da esquerda para a direita,
 * arraste horizontal para inspecionar um ponto (vibração leve ao mudar de ponto). O caminho é montado uma vez por
 * conjunto de dados (drawWithCache); o arraste só invalida o desenho.
 */
@Composable
fun LineChart(xs: LongArray, ys: FloatArray, color: Color, modifier: Modifier, onScrub: (Int?) -> Unit) {
    val reduced = com.coinportracker.ui.theme.LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(ys) { if (reduced || !com.coinportracker.ui.theme.Motion.prefs.chart) { progress.snapTo(1f); return@LaunchedEffect }; progress.snapTo(0f); progress.animateTo(1f, tween(com.coinportracker.ui.theme.Motion.prefs.dur(700, com.coinportracker.ui.theme.Motion.prefs.chartSpeed), easing = FastOutSlowInEasing)) }
    var scrub by remember { mutableIntStateOf(-1) }
    val haptic = LocalHapticFeedback.current
    val guide = color.copy(alpha = 0.35f)
    val n = ys.size
    Box(modifier
        .pointerInput(ys) {
            if (n < 2) return@pointerInput
            fun idx(x: Float) = ((x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var started = false
                var total = Offset.Zero
                var pointer = down
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointer.id } ?: break
                    if (!ch.pressed) break
                    total += ch.positionChange()
                    if (!started && abs(total.x) > 6f && abs(total.x) > abs(total.y)) started = true
                    if (!started && abs(total.y) > 12f) break // rolagem vertical: devolve o gesto à lista
                    if (started) {
                        ch.consume()
                        val i = idx(ch.position.x)
                        if (i != scrub) { scrub = i; onScrub(i); if (com.coinportracker.ui.theme.Motion.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    pointer = ch
                }
                if (scrub != -1) { scrub = -1; onScrub(null) }
            }
        }
        .drawWithCache {
            val path = Path(); val area = Path()
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (f in ys) { if (f < lo) lo = f; if (f > hi) hi = f }
            val range = (hi - lo).takeIf { it > 0f } ?: 1f
            val top = size.height * 0.08f; val usable = size.height * 0.84f
            val padX = 10.dp.toPx()
            fun px(i: Int) = if (n <= 1) padX else padX + i * (size.width - 2 * padX) / (n - 1)
            fun py(i: Int) = top + usable - (ys[i] - lo) / range * usable
            for (i in 0 until n) { val x = px(i); val y = py(i); if (i == 0) { path.moveTo(x, y); area.moveTo(x, size.height); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) } }
            if (n > 0) { area.lineTo(px(n - 1), size.height); area.close() }
            val fill = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), top, size.height)
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            onDrawBehind {
                if (n < 2) return@onDrawBehind
                clipRect(right = size.width * progress.value) {
                    drawPath(area, fill)
                    drawPath(path, color, style = stroke)
                }
                val s = scrub
                if (s >= 0) {
                    val x = px(s); val y = py(s)
                    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = dash)
                    drawCircle(color.copy(alpha = 0.25f), 9.dp.toPx(), Offset(x, y))
                    drawCircle(color, 4.5.dp.toPx(), Offset(x, y))
                } else if (progress.value >= 1f) {
                    drawCircle(color.copy(alpha = 0.22f), 7.dp.toPx(), Offset(px(n - 1), py(n - 1)))
                    drawCircle(color, 3.5.dp.toPx(), Offset(px(n - 1), py(n - 1)))
                }
            }
        })
}

/** Rosca de alocação com entrada animada e destaque da fatia selecionada. */
@Composable
fun Donut(shares: FloatArray, colors: List<Color>, selected: Int, modifier: Modifier, track: Color) {
    val reduced = com.coinportracker.ui.theme.LocalReducedMotion.current
    val anim = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(shares.contentHashCode()) { if (reduced || !com.coinportracker.ui.theme.Motion.prefs.chart) { anim.snapTo(1f); return@LaunchedEffect }; anim.snapTo(0f); anim.animateTo(1f, tween(com.coinportracker.ui.theme.Motion.prefs.dur(800, com.coinportracker.ui.theme.Motion.prefs.chartSpeed), easing = FastOutSlowInEasing)) }
    Box(modifier.drawWithCache {
        val w = size.minDimension * 0.13f
        val inset = w / 2 + 6.dp.toPx()
        val arcSize = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
        val tl = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
        val gap = if (shares.size > 1) 2.2f else 0f
        onDrawBehind {
            drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(w))
            var start = -90f
            val total = 360f * anim.value
            for (i in shares.indices) {
                val sweep = shares[i] * total
                if (sweep > gap) {
                    val sel = i == selected
                    val c = colors[i % colors.size].let { if (selected >= 0 && !sel) it.copy(alpha = 0.35f) else it }
                    drawArc(c, start + gap / 2, sweep - gap, false, tl, arcSize, style = Stroke(if (sel) w * 1.28f else w, cap = StrokeCap.Butt))
                }
                start += sweep
            }
        }
    })
}

/**
 * Gráfico de resultado (v3.2, estilo Uniswap/CoinStats): linha do valor + linha tracejada do principal (o que saiu do bolso).
 * Área entre as duas: verde quando o valor está acima do principal, vermelha abaixo. Sem [base], usa uma cor só ([color]).
 * - Eixo X proporcional ao tempo; lacunas grandes (> 3× o passo mediano) viram degrau, nunca diagonal.
 * - Troca de período: a curva antiga se transforma na nova (morph de 450 ms); sem animação com "Remover animações".
 */
@Composable
fun PnlChart(xs: LongArray, ys: FloatArray, base: FloatArray?, color: Color, gain: Color, loss: Color, baseColor: Color, modifier: Modifier, onScrub: (Int?) -> Unit) {
    val reduced = com.coinportracker.ui.theme.LocalReducedMotion.current
    val n = ys.size
    // normalização conjunta (valor + principal) em 0..1
    val norm = remember(xs, ys, base) { chartNorm(xs, ys, base) }
    var prev by remember { mutableStateOf<ChartNorm?>(null) }
    var cur by remember { mutableStateOf(norm) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(norm) {
        if (cur !== norm) { prev = if (morph.value < 1f) cur.lerpFrom(prev, morph.value) else cur; cur = norm }
        if (reduced || prev == null || !com.coinportracker.ui.theme.Motion.prefs.chart) { morph.snapTo(1f); return@LaunchedEffect }
        morph.snapTo(0f); morph.animateTo(1f, tween(com.coinportracker.ui.theme.Motion.prefs.dur(450, com.coinportracker.ui.theme.Motion.prefs.chartSpeed), easing = FastOutSlowInEasing))
    }
    var scrub by remember { mutableIntStateOf(-1) }
    val haptic = LocalHapticFeedback.current
    Box(modifier
        .pointerInput(xs) {
            if (n < 2) return@pointerInput
            val padX = 10.dp.toPx()
            fun idx(x: Float): Int { val f = ((x - padX) / (size.width - 2 * padX)).coerceIn(0f, 1f); return norm.nearest(f) }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var started = false; var total = Offset.Zero; var pointer = down
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointer.id } ?: break
                    if (!ch.pressed) break
                    total += ch.positionChange()
                    if (!started && abs(total.x) > 6f && abs(total.x) > abs(total.y)) started = true
                    if (!started && abs(total.y) > 12f) break
                    if (started) { ch.consume(); val i = idx(ch.position.x); if (i != scrub) { scrub = i; onScrub(i); if (com.coinportracker.ui.theme.Motion.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
                    pointer = ch
                }
                if (scrub != -1) { scrub = -1; onScrub(null) }
            }
        }
        .drawWithCache {
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            val baseStroke = Stroke(1.2.dp.toPx(), pathEffect = dash)
            val padX = 10.dp.toPx(); val top = size.height * 0.08f; val usable = size.height * 0.84f
            val fill = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), top, size.height)
            val gainFill = gain.copy(alpha = 0.20f); val lossFill = loss.copy(alpha = 0.20f); val baseLine = baseColor.copy(alpha = 0.7f)
            // caminhos alocados uma vez por tamanho; só são remontados quando a série muda ou durante o morph
            // (arrastar o dedo só move o marcador, sem remontar nada)
            val line = Path(); val bl = Path(); val band = Path(); val above = Path(); val below = Path(); val area = Path()
            var builtFor: ChartNorm? = null; var builtT = -1f
            onDrawBehind {
                val c = cur; if (c.fx.size < 2) return@onDrawBehind
                val t = morph.value; val p = prev
                fun X(i: Int) = padX + c.fx[i] * (size.width - 2 * padX)
                fun yv(i: Int): Float { val v = if (p != null && t < 1f) lerpF(p.sampleY(c.fx[i]), c.y[i], t) else c.y[i]; return top + usable * (1 - v) }
                fun yb(i: Int): Float { val b = c.b ?: return 0f; val v = if (p?.b != null && t < 1f) lerpF(p.sampleB(c.fx[i]), b[i], t) else b[i]; return top + usable * (1 - v) }
                val last = c.fx.size - 1
                if (builtFor !== c || builtT != t) {
                    line.rewind(); bl.rewind()
                    for (i in c.fx.indices) {
                        val x = X(i)
                        if (i == 0) { line.moveTo(x, yv(0)); if (c.b != null) bl.moveTo(x, yb(0)) }
                        else if (c.gap[i]) { line.lineTo(x, yv(i - 1)); line.lineTo(x, yv(i)); if (c.b != null) { bl.lineTo(x, yb(i - 1)); bl.lineTo(x, yb(i)) } }
                        else { line.lineTo(x, yv(i)); if (c.b != null) bl.lineTo(x, yb(i)) }
                    }
                    if (c.b == null) {
                        area.rewind(); area.addPath(line); area.lineTo(X(last), size.height); area.lineTo(X(0), size.height); area.close()
                    } else {
                        band.rewind(); band.addPath(line); for (i in last downTo 0) band.lineTo(X(i), yb(i)); band.close()
                        above.rewind(); above.addPath(bl); above.lineTo(X(last), 0f); above.lineTo(X(0), 0f); above.close()
                        below.rewind(); below.addPath(bl); below.lineTo(X(last), size.height); below.lineTo(X(0), size.height); below.close()
                    }
                    builtFor = c; builtT = t
                }
                if (c.b == null) {
                    drawPath(area, fill)
                    drawPath(line, color, style = stroke)
                } else {
                    // banda entre valor e principal
                    clipPath(above) { drawPath(band, gainFill); drawPath(line, gain, style = stroke) }
                    clipPath(below) { drawPath(band, lossFill); drawPath(line, loss, style = stroke) }
                    drawPath(bl, baseLine, style = baseStroke)
                }
                val s = scrub
                val dotI = if (s >= 0) s.coerceAtMost(last) else last
                val dotC = if (c.b == null) color else if (yv(dotI) <= yb(dotI)) gain else loss
                if (s >= 0) {
                    drawLine(baseColor.copy(alpha = 0.35f), Offset(X(dotI), 0f), Offset(X(dotI), size.height), 1.dp.toPx(), pathEffect = dash)
                    drawCircle(dotC.copy(alpha = 0.25f), 9.dp.toPx(), Offset(X(dotI), yv(dotI))); drawCircle(dotC, 4.5.dp.toPx(), Offset(X(dotI), yv(dotI)))
                } else if (t >= 1f) {
                    drawCircle(dotC.copy(alpha = 0.22f), 7.dp.toPx(), Offset(X(last), yv(last))); drawCircle(dotC, 3.5.dp.toPx(), Offset(X(last), yv(last)))
                }
            }
        })
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Série normalizada para desenho: x em 0..1 pelo tempo, y/b em 0..1 na mesma escala, marcação de lacunas. */
internal class ChartNorm(val fx: FloatArray, val y: FloatArray, val b: FloatArray?, val gap: BooleanArray) {
    private fun sample(arr: FloatArray, f: Float): Float {
        if (fx.isEmpty()) return 0.5f
        if (f <= fx[0]) return arr[0]; if (f >= fx[fx.size - 1]) return arr[arr.size - 1]
        var lo = 0; var hi = fx.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (fx[m] <= f) lo = m else hi = m }
        val span = fx[hi] - fx[lo]; return if (span <= 0f) arr[hi] else lerpF(arr[lo], arr[hi], (f - fx[lo]) / span)
    }
    fun sampleY(f: Float) = sample(y, f)
    fun sampleB(f: Float) = b?.let { sample(it, f) } ?: sampleY(f)
    fun nearest(f: Float): Int { var best = 0; var d = Float.MAX_VALUE; for (i in fx.indices) { val e = abs(fx[i] - f); if (e < d) { d = e; best = i } }; return best }
    /** Estado intermediário de um morph interrompido (para encadear trocas rápidas de período sem salto). */
    fun lerpFrom(from: ChartNorm?, t: Float): ChartNorm {
        if (from == null) return this
        return ChartNorm(fx, FloatArray(y.size) { lerpF(from.sampleY(fx[it]), y[it], t) }, b?.let { bb -> FloatArray(bb.size) { lerpF(from.sampleB(fx[it]), bb[it], t) } }, gap)
    }
}

internal fun chartNorm(xs: LongArray, ys: FloatArray, base: FloatArray?): ChartNorm {
    val n = ys.size
    if (n == 0) return ChartNorm(FloatArray(0), FloatArray(0), null, BooleanArray(0))
    val b = base?.takeIf { it.size == n }
    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
    for (v in ys) { if (v < lo) lo = v; if (v > hi) hi = v }
    if (b != null) for (v in b) { if (v < lo) lo = v; if (v > hi) hi = v }
    val range = (hi - lo).takeIf { it > 0f } ?: 1f
    val t0 = xs.first(); val span = (xs.last() - t0).takeIf { it > 0 }?.toFloat() ?: 1f
    val fx = FloatArray(n) { if (n == 1) 0f else ((xs[it] - t0) / span) }
    val steps = (1 until n).map { xs[it] - xs[it - 1] }.sorted()
    val med = steps.getOrNull(steps.size / 2) ?: 0L
    val gap = BooleanArray(n) { it > 0 && med > 0 && xs[it] - xs[it - 1] > 3 * med }
    return ChartNorm(fx, FloatArray(n) { (ys[it] - lo) / range }, b?.let { bb -> FloatArray(n) { (bb[it] - lo) / range } }, gap)
}
