package com.coinportracker.ui.components

import androidx.compose.ui.unit.isSpecified

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import kotlinx.coroutines.launch
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.coinportracker.onchain.Network
import com.coinportracker.ui.theme.LocalExtra

/** Cartão plano, cantos de 20 dp e borda finíssima (Phantom/Trust). */
@Composable
fun CpCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val x = LocalExtra.current
    val shape = RoundedCornerShape(20.dp)
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressScale(src, 0.98f) else Modifier).clip(shape).background(x.card).border(BorderStroke(1.dp, x.cardBorder), shape)
            .then(if (onClick != null) Modifier.clickable(src, androidx.compose.material3.ripple(), onClick = onClick) else Modifier)
            .animateContentSize(com.coinportracker.ui.theme.Motion.spatial()).padding(padding),
        content = content,
    )
}

/** Escala sutil ao tocar (resposta tátil visual, sem custo de recomposição). */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val s = remember { Animatable(1f) }
    LaunchedEffect(isPressed) { s.animateTo(if (isPressed) pressed else 1f, com.coinportracker.ui.theme.Motion.press()) }
    return this.graphicsLayer { scaleX = s.value; scaleY = s.value }
}

private val initialsPalette = listOf(0xFF3A3A3A, 0xFF4A4A4A, 0xFF2F2F2F, 0xFF555555, 0xFF404040)

/** Ícone do token (cache em disco do Coil) com fallback de iniciais e selo da rede no canto (Trust Wallet). */
@Composable
fun TokenIcon(url: String?, symbol: String, size: Dp = 40.dp, network: Network? = null, showBadge: Boolean = false) {
    Box(Modifier.size(size)) {
        val ctx = LocalContext.current
        val fallback: @Composable () -> Unit = {
            val c = Color(initialsPalette[(symbol.hashCode() and 0x7fffffff) % initialsPalette.size])
            Box(Modifier.fillMaxSize().clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                Text(symbol.take(if (symbol.length <= 3) symbol.length else 2).uppercase(), color = Color.White, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (url.isNullOrBlank()) fallback() else {
            val req = remember(url, size) { ImageRequest.Builder(ctx).data(url).size((size.value * 3).toInt()).crossfade(120).build() }
            SubcomposeAsyncImage(model = req, contentDescription = symbol, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape),
                loading = { Box(Modifier.fillMaxSize().clip(CircleShape).background(LocalExtra.current.cardHigh)) }, error = { fallback() })
        }
        if (showBadge && network != null) {
            val b = size * 0.42f
            Box(Modifier.size(b).align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.background).padding(1.5.dp)) {
                NetworkDot(network, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun NetworkDot(n: Network, modifier: Modifier = Modifier.size(16.dp)) {
    val ctx = LocalContext.current
    val req = remember(n) { ImageRequest.Builder(ctx).data(n.iconUrl).size(64).build() }
    SubcomposeAsyncImage(req, n.label, modifier.clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop,
        error = { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(Color(n.color))) },
        loading = { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(Color(n.color).copy(alpha = 0.5f))) })
}

/** Selo de variação: ▲ verde / ▼ vermelho (as únicas cores do app). */
@Composable
fun ChangePill(text: String, up: Boolean?, modifier: Modifier = Modifier, filled: Boolean = true) {
    val x = LocalExtra.current
    val c = when (up) { true -> x.gain; false -> x.loss; null -> MaterialTheme.colorScheme.onSurfaceVariant }
    val arrow = when (up) { true -> "▲ "; false -> "▼ "; null -> "" }
    FitText(arrow + text, color = c, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), minScale = 0.7f,
        modifier = modifier.then(if (filled) Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp) else Modifier))
}

@Composable
fun changeColor(up: Boolean?): Color = when (up) { true -> LocalExtra.current.gain; false -> LocalExtra.current.loss; null -> MaterialTheme.colorScheme.onSurfaceVariant }

/** Sparkline: caminho calculado uma vez por dado (drawWithCache), sem recomposição por quadro. */
@Composable
fun Spark(values: FloatArray?, up: Boolean?, modifier: Modifier = Modifier.size(64.dp, 28.dp)) {
    val color = changeColor(up)
    Box(modifier.drawWithCache {
        val v = values
        val path = Path()
        if (v != null && v.size >= 2) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (f in v) { if (f < lo) lo = f; if (f > hi) hi = f }
            val (l, r) = sparkRange(lo.toDouble(), hi.toDouble())
            val stepX = size.width / (v.size - 1)
            v.forEachIndexed { i, f ->
                val x = i * stepX; val y = (size.height - (f - l) / r * size.height * 0.9f - size.height * 0.05f).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind { if (v != null && v.size >= 2) drawPath(path, color, style = stroke) }
    })
}

/**
 * Faixa vertical das sparklines: no mínimo [minRel] do valor médio (padrão 3%, ±1,5%), centrada nos dados.
 * Sem isso, uma stablecoin oscilando ±0,3% vira "ruído" de ponta a ponta do gráfico.
 * Retorna (mínimo, amplitude).
 */
internal fun sparkRange(lo: Double, hi: Double, minRel: Double = 0.03): Pair<Double, Double> {
    // v5: mesma regra de com.coinportracker.market.SparkRange (uma só escala; nunca as duas).
    val r = com.coinportracker.market.SparkRange.of(listOf(lo, hi), minRel) ?: return (lo - 0.5) to 1.0
    return r.lo to (r.span.takeIf { it > 0 } ?: 1.0)
}

/** Botão de ação redondo (fila de ações estilo Phantom). */
@Composable
fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    Column(modifier.pressScale(src).clip(RoundedCornerShape(16.dp)).clickable(src, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (compact) 48.dp else 52.dp).clip(RoundedCornerShape(16.dp)).background(LocalExtra.current.cardHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (compact) 20.dp else 22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            FitText(label, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.7f)
        }
    }
}

/** Chips de intervalo (1D/1S/1M/1A/Tudo). */
@Composable
fun <T> RangeChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val padH = when { fs > 1.6f -> 5.dp; fs > 1.15f -> 8.dp; else -> 14.dp }
        options.forEach { o ->
            val sel = o == selected
            val bg by androidx.compose.animation.animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, com.coinportracker.ui.theme.Motion.fade(), label = "rc")
            val fg by androidx.compose.animation.animateColorAsState(if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, com.coinportracker.ui.theme.Motion.fade(), label = "rf")
            val src = remember { MutableInteractionSource() }
            Text(label(o), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, softWrap = false,
                modifier = Modifier.pressScale(src).clip(RoundedCornerShape(10.dp)).drawBehind { drawRect(bg) }
                    .semantics { this.selected = sel; this.role = androidx.compose.ui.semantics.Role.Tab }
                    .clickable(src, androidx.compose.material3.ripple()) { onSelect(o) }.padding(horizontal = padH, vertical = 7.dp))
        }
    }
}

/** Abas segmentadas: o indicador é desenhado na mesma linha dos rótulos (largura = total / n), então nunca desalinha. */
@Composable
fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val x = LocalExtra.current
    val n = options.size.coerceAtLeast(1)
    val idx = options.indexOf(selected).coerceAtLeast(0)
    val pos by androidx.compose.animation.core.animateFloatAsState(idx.toFloat(), com.coinportracker.ui.theme.Motion.spatial(), label = "seg")
    val ind = x.card
    val border = x.cardBorder
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(x.cardHigh).padding(3.dp).drawBehind {
        val w = size.width / n
        val r = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx())
        drawRoundRect(ind, topLeft = Offset(pos * w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r)
        drawRoundRect(border, topLeft = Offset(pos * w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r, style = Stroke(1.dp.toPx()))
    }) {
        options.forEach { o ->
            val sel = o == selected
            val fg by androidx.compose.animation.animateColorAsState(if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, com.coinportracker.ui.theme.Motion.fade(), label = "sf")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable { onSelect(o) }.padding(horizontal = 4.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label(o), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Esqueleto com brilho: só na primeira carga (sem spinners). */
@Composable
fun ShimmerBlock(modifier: Modifier, radius: Dp = 10.dp) {
    val x = LocalExtra.current
    val t = rememberInfiniteTransition(label = "sh")
    val p by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(modifier.clip(RoundedCornerShape(radius)).drawWithCache {
        onDrawBehind {
            drawRect(x.shimmer)
            val w = size.width
            drawRect(Brush.linearGradient(listOf(Color.Transparent, x.shimmer, Color.Transparent), Offset(w * p - w * 0.4f, 0f), Offset(w * p, size.height)))
        }
    })
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    // fonte do sistema muito grande (≥ 150%): rótulo em cima, valor embaixo — nada de palavra partida ao meio
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) {
        Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FitText(value, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), valueColor)
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxValue = maxWidth * 0.62f
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            FitText(value, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), valueColor, Modifier.widthIn(max = maxValue), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/**
 * Texto de uma linha que reduz a fonte (até [minScale]) para caber na largura disponível antes de recorrer às
 * reticências. Usado para valores que podem chegar a bilhões/trilhões.
 */
@Composable
fun FitText(text: String, style: TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier, minScale: Float = 0.6f,
            textAlign: androidx.compose.ui.text.style.TextAlign? = null) {
    // v5: Layout simples (sem SubcomposeLayout nem segunda medição com outro tamanho de fonte). A largura natural vem da
    // intrínseca do próprio Text; se não couber, o texto é desenhado na camada com escala (mesma aparência de antes,
    // fonte menor proporcional) e só recorre às reticências abaixo de [minScale]. Bem mais barato nas listas.
    androidx.compose.ui.layout.Layout(content = {
        Text(text, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
    }, modifier = modifier) { ms, c ->
        val m = ms.first()
        val full = m.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val scale = if (c.hasBoundedWidth && full > c.maxWidth && full > 0) (c.maxWidth.toFloat() / full).coerceIn(minScale, 1f) else 1f
        val innerMaxW = if (c.hasBoundedWidth) (c.maxWidth / scale).toInt() else androidx.compose.ui.unit.Constraints.Infinity
        val p = m.measure(androidx.compose.ui.unit.Constraints(maxWidth = innerMaxW))
        val w = kotlin.math.ceil(p.width * scale).toInt().coerceIn(c.minWidth, c.maxWidth)
        val h = kotlin.math.ceil(p.height * scale).toInt().coerceIn(c.minHeight, c.maxHeight)
        layout(w, h) {
            if (scale == 1f) p.place(0, 0)
            else p.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) { Box(modifier.fillMaxWidth().height(1.dp).background(LocalExtra.current.divider)) }

/**
 * Saldo estilo caça-níquel (v3): cada dígito é um rolo 0–9 desenhado num único Canvas, numa célula
 * de largura fixa (dígitos tabulares) e recortado na própria célula — não há sobreposição nem
 * dígitos "vazando". Sem overshoot: curva desacelerada e mola criticamente amortecida.
 * O sentido acompanha o valor (subiu = rola para cima; caiu = rola para baixo).
 * Só a fase de desenho é invalidada a cada quadro (nenhuma recomposição) → fluido a 120/144 Hz.
 * [spinOnFirst] = giros completos na primeira exibição (negativo = aparece parado).
 */
@Composable
fun SlotText(text: String, style: TextStyle, color: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier,
             spinOnFirst: Int = 2, staggerMs: Int = 45, durationMs: Int = 950, replayKey: Any? = Unit) {
    if (com.coinportracker.ui.theme.LocalReducedMotion.current) { Text(text, style = style, color = color, modifier = modifier, maxLines = 1); return }
    val p = com.coinportracker.ui.theme.LocalAnim.current
    when (p.number) {
        com.coinportracker.settings.NumberAnim.ROLL -> RollText(text, style, color, modifier, spinOnFirst, staggerMs, durationMs, replayKey, p)
        else -> NumberText(text, style, color, modifier, p, replayKey)
    }
}

@Composable
private fun RollText(text: String, style: TextStyle, color: Color, modifier: Modifier,
             spinOnFirst: Int, staggerMs: Int, durationMs: Int, replayKey: Any?, prefs: com.coinportracker.settings.AnimPrefs) {
    val settleSpec = remember(prefs) { com.coinportracker.ui.theme.Motion.numberSpec<Float>(prefs) }
    val stagger = prefs.dur(staggerMs); val firstMs = prefs.dur(durationMs)
    val st = style.copy(fontFeatureSettings = "tnum, lnum", color = color)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 32)
    val glyphs = remember(st, measurer) { (0..9).map { measurer.measure(it.toString(), st) } }
    val cellW = remember(glyphs) { glyphs.maxOf { it.size.width } }
    val cellH = remember(glyphs) { glyphs.maxOf { it.size.height } }
    val reels = remember(replayKey) { HashMap<Int, Reel>() }
    var prevValue by remember(replayKey) { mutableStateOf<Double?>(null) }
    val value = remember(text) { parseNumber(text) }
    val n = text.length
    // layout: largura de cada caractere (dígitos = célula fixa)
    // símbolos ("R$", ".", ",", "-") medidos uma vez por estilo: o saldo ao vivo muda a cada 1 s e não remede nada
    val symbolCache = remember(st, measurer) { HashMap<Char, androidx.compose.ui.text.TextLayoutResult>() }
    val others = remember(text, symbolCache) { text.map { ch -> if (ch.isDigit()) null else symbolCache.getOrPut(ch) { measurer.measure(ch.toString(), st) } } }
    val totalW = remember(text, others, cellW) { var w = 0; text.forEachIndexed { i, ch -> w += if (ch.isDigit()) cellW else others[i]!!.size.width }; w }
    val scope = rememberCoroutineScope()
    // sincroniza rolos com o texto (chave = posição contada da direita: centavos ficam parados no lugar)
    val activeKeys = remember(text) { HashSet<Int>() }
    run {
        activeKeys.clear()
        var order = 0
        for (i in 0 until n) {
            val ch = text[i]; if (!ch.isDigit()) continue
            val k = n - i; activeKeys += k
            val d = ch - '0'
            val r = reels.getOrPut(k) { Reel(scope, d, if (spinOnFirst < 0 || prevValue != null) 0 else spinOnFirst, order * stagger, firstMs, fresh = prevValue != null, settle = settleSpec) }
            r.order = order++; r.settle = settleSpec
        }
    }
    LaunchedEffect(text, replayKey) {
        val up = value?.let { v -> prevValue?.let { v >= it } } ?: true
        var order = 0
        for (i in 0 until n) {
            val ch = text[i]; if (!ch.isDigit()) continue
            reels[n - i]?.go(ch - '0', up, order++)
        }
        prevValue = value ?: prevValue ?: 0.0
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.semantics { contentDescription = text }
        // encolhe proporcionalmente quando o número não cabe na largura (bilhões/trilhões, fonte grande)
        .layout { m, c ->
            val sc = if (c.hasBoundedWidth && totalW > c.maxWidth) c.maxWidth.toFloat() / totalW else 1f
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(totalW, cellH))
            layout((totalW * sc).toInt(), (cellH * sc).toInt()) {
                p.placeWithLayer(0, 0) { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
            }
        }
        .drawBehind {
            var x = 0f
            for (i in 0 until n) {
                val ch = text[i]
                if (!ch.isDigit()) {
                    val l = others[i]!!
                    drawText(l, topLeft = Offset(x, (cellH - l.size.height) / 2f)); x += l.size.width; continue
                }
                val r = reels[n - i]
                // parado = sempre um dígito inteiro (nunca meio glifo, mesmo se a animação foi interrompida)
                val pos = r?.drawPos() ?: (ch - '0').toFloat()
                val m = reelMod(pos)
                val lo = kotlin.math.floor(m).toInt() % 10
                val frac = m - kotlin.math.floor(m)
                val a = r?.appear?.value ?: 1f
                clipRect(x, 0f, x + cellW, cellH.toFloat()) {
                    val g0 = glyphs[lo]; val g1 = glyphs[(lo + 1) % 10]
                    val y0 = -frac * cellH
                    // leve esmaecimento conforme o dígito sai da célula (sem bordas duras)
                    drawText(g0, topLeft = Offset(x + (cellW - g0.size.width) / 2f, y0), alpha = a * (1f - frac * 0.75f))
                    if (frac > 0.001f) drawText(g1, topLeft = Offset(x + (cellW - g1.size.width) / 2f, y0 + cellH), alpha = a * (0.25f + frac * 0.75f))
                }
                x += cellW
            }
        })
}

/** Um rolo: posição contínua em "dígitos" (exibido = pos mod 10). */
private class Reel(private val scope: kotlinx.coroutines.CoroutineScope, digit: Int, private val spins: Int, private val delayMs: Int, private val durationMs: Int, fresh: Boolean,
                   var settle: androidx.compose.animation.core.FiniteAnimationSpec<Float> = tween(SETTLE_MS, easing = com.coinportracker.ui.theme.Motion.EmphasizedDecel)) {
    val pos = Animatable(if (spins > 0) 0f else digit.toFloat())
    val appear = Animatable(if (fresh) 0f else 1f)
    var order = 0
    private var target = digit
    private var first = true
    private var job: kotlinx.coroutines.Job? = null
    fun go(d: Int, up: Boolean, order: Int) {
        val wasFirst = first; first = false
        if (appear.value < 1f) scope.launch { appear.animateTo(1f, tween(260, easing = com.coinportracker.ui.theme.Motion.Standard)) }
        if (!wasFirst && d == target && (job?.isActive == true || pos.value == d.toFloat())) return
        target = d
        job?.cancel()
        job = scope.launch {
            if (wasFirst && spins > 0) {
                kotlinx.coroutines.delay(delayMs.toLong())
                pos.animateTo(d + 10f * spins, tween(durationMs + order * 25, easing = com.coinportracker.ui.theme.Motion.EmphasizedDecel))
                pos.snapTo(d.toFloat()); return@launch
            }
            val cur = pos.value
            val dest = cur + reelTravel(cur, d, up)
            // duração fixa e curta (< 1 s): com o saldo ao vivo atualizando a cada 1 s, o rolo sempre assenta
            // num dígito inteiro antes da próxima mudança (antes a mola levava >1 s e ficava meio-glifo na tela)
            pos.animateTo(dest, settle)
            pos.snapTo(d.toFloat())
        }
    }
    /** Posição para desenhar: arredondada quando não há animação ativa. */
    fun drawPos(): Float = if (job?.isActive == true || pos.isRunning) pos.value else target.toFloat()
    companion object { const val SETTLE_MS = 560 }
}

/** Posição mod 10 em [0, 10). */
internal fun reelMod(p: Float): Float = ((p % 10f) + 10f) % 10f

/** Deslocamento até o dígito [d] a partir da posição contínua [cur], no sentido do valor (subiu = para frente). */
internal fun reelTravel(cur: Float, d: Int, up: Boolean): Float {
    val fwd = reelMod(d - reelMod(cur))
    return if (up) fwd else -reelMod(10f - fwd)
}

/** "R$ 12.345,67" → 12345.67 (só para saber se subiu ou caiu). */
internal fun parseNumber(s: String): Double? {
    val neg = s.contains('-') || s.contains('−')
    val digits = s.filter { it.isDigit() || it == ',' }.replace(',', '.')
    val v = digits.toDoubleOrNull() ?: s.filter { it.isDigit() }.toDoubleOrNull() ?: return null
    return if (neg) -v else v
}
