package com.coinportracker.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.coinportracker.media.BannerLibrary
import com.coinportracker.media.BannerLibrary.GRID_PX
import com.coinportracker.settings.BannerRoll
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.ui.theme.LocalLowPower
import com.coinportracker.ui.theme.LocalReducedMotion
import com.coinportracker.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val COLS = 3

fun kindLabel(k: RollKind, n: Int = 2) = when (k) { RollKind.GIF -> if (n == 1) "GIF" else "GIFs"; RollKind.PHOTO -> if (n == 1) "Foto" else "Fotos"; RollKind.VIDEO -> if (n == 1) "Vídeo" else "Vídeos" }

/** v5.1.3: onde está o banner e onde foi o último toque (coordenadas de tela). Sem estado observável: não recompõe nada. */
@Stable
class PickerAnchor {
    var banner: Rect? = null
    var lastDown: Offset? = null
}

private fun LayoutCoordinates.screenRect(): Rect = Rect(positionOnScreen(), size.toSize())

/** Registra o retângulo do banner e a posição de cada toque, sem consumir nada (os gestos continuam iguais). */
fun Modifier.trackPickerAnchor(a: PickerAnchor): Modifier = this
    .onGloballyPositioned { a.banner = it.screenRect() }
    .pointerInput(a) {
        awaitEachGesture {
            val d = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            a.lastDown = a.banner?.let { b -> b.topLeft + d.position }
        }
    }

/** Molas do seletor, seguindo a velocidade geral de Animações. */
private object PickerSprings {
    private val k get() = Motion.prefs.speed.coerceIn(0.25f, 4f).let { it * it }
    fun open() = spring<Float>(dampingRatio = 0.8f, stiffness = 260f * k)
    fun close() = spring<Float>(dampingRatio = 1f, stiffness = 380f * k)
    fun fly() = spring<Float>(dampingRatio = 0.86f, stiffness = 230f * k)
    fun cell() = spring<Float>(dampingRatio = 0.74f, stiffness = 300f * k)
    fun pop() = spring<Float>(dampingRatio = 0.42f, stiffness = 700f * k)
    fun indicator() = spring<Float>(dampingRatio = 0.76f, stiffness = 420f * k)
}

private class Flight(val bitmap: Bitmap, val from: Rect, val to: Rect)

/**
 * v5.1.1 — seletor do banner (toque no banner da Início). v5.1.3 — mais fluido:
 * - nasce do ponto do toque (escala em mola) com o escurecido e o desfoque subindo juntos, e fecha revertendo o mesmo caminho;
 * - tipos em abas com indicador deslizante; grade em cascata item a item, linha a linha;
 * - ao escolher, a miniatura "voa" da célula até o banner (como um elemento compartilhado) e a janela se desfaz por baixo.
 * Janela de borda a borda (5.1.2): o escurecido/desfoque cobre também a barra de status e a de navegação.
 * "Nenhuma" nas transições de tela, animações do sistema desligadas ou baixo consumo: cortes, sem voo.
 */
@Composable
fun BannerPicker(roll: BannerRoll, anchor: PickerAnchor, onPick: (RollItem) -> Unit, onDismiss: () -> Unit) {
    val reduced = LocalReducedMotion.current || LocalLowPower.current || Motion.prefs.screen == com.coinportracker.settings.ScreenAnim.NONE
    val low = LocalLowPower.current
    val kinds = remember(roll) { roll.pickableKinds }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val p = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    var flight by remember { mutableStateOf<Flight?>(null) }
    var picking by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    var card by remember { mutableStateOf(Rect.Zero) }

    // miniaturas já pedidas antes de abrir (Início) continuam no cache; aqui completa o resto dos primeiros de cada tipo
    LaunchedEffect(Unit) { BannerLibrary.prefetch(ctx, roll.carousel, perKind = 24) }
    LaunchedEffect(Unit) { if (reduced) p.snapTo(1f) else p.animateTo(1f, PickerSprings.open()) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (!reduced) p.animateTo(0f, PickerSprings.close())
            onDismiss()
        }
    }
    fun pick(item: RollItem, cell: Rect?) {
        if (closing) return
        closing = true
        onPick(item) // o banner troca por baixo enquanto a miniatura voa
        val bmp = BannerLibrary.cachedThumb(item, GRID_PX)
        val to = anchor.banner?.translate(-rootPos)
        val from = cell?.translate(-rootPos)
        scope.launch {
            if (!reduced && bmp != null && to != null && from != null) {
                flight = Flight(bmp, from, to)
                picking = true
                launch { p.animateTo(0f, tween(Motion.d(300), easing = Motion.EmphasizedAccel)) }
                fly.animateTo(1f, PickerSprings.fly())
            } else if (!reduced) { picking = true; p.animateTo(0f, PickerSprings.close()) }
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val density = LocalDensity.current
        val maxBlur = with(density) { 26.dp.toPx() }
        val blurOn = remember(window) {
            window != null && Build.VERSION.SDK_INT >= 31 && !low &&
                runCatching { window.windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        }
        SideEffect {
            // 5.1.2: a janela do seletor cobre a tela inteira, de borda a borda (barra de status, recorte da câmera e barra de navegação)
            window?.let { w ->
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                @Suppress("DEPRECATION") run { w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT }
                if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
                if (blurOn) w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
        // desfoque e escurecido sobem e descem juntos, presos ao mesmo progresso (só atualiza quando o raio inteiro muda)
        if (blurOn && window != null && Build.VERSION.SDK_INT >= 31) {
            LaunchedEffect(window) {
                var last = -1
                snapshotFlow { (p.value.coerceIn(0f, 1f) * maxBlur).roundToInt() }.collect { r ->
                    if (r != last) { last = r; window.attributes = window.attributes.apply { blurBehindRadius = r } }
                }
            }
        }
        val scrim = if (blurOn) 0.38f else 0.66f
        Box(Modifier.fillMaxSize()
            .onGloballyPositioned { rootPos = it.positionOnScreen() }
            .pointerInput(Unit) { detectTapGestures { close() } }
            .semantics { contentDescription = "Fechar seletor" },
        ) {
            // escurecido de borda a borda (fora de qualquer padding de insets)
            Box(Modifier.matchParentSize().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = scrim)))
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
                val w = (maxWidth * 0.96f).coerceAtMost(640.dp)
                val h = (maxHeight * 0.80f).coerceAtMost(760.dp)
                val shape = RoundedCornerShape(28.dp)
                Box(Modifier.size(w, h)
                    .onGloballyPositioned { card = it.screenRect() }
                    .graphicsLayer {
                        val v = p.value
                        if (picking) {
                            // escolha: a janela se desfaz no lugar (a miniatura é que voa)
                            val s = lerp(0.94f, 1f, v.coerceIn(0f, 1f))
                            scaleX = s; scaleY = s
                            transformOrigin = TransformOrigin.Center
                            alpha = v.coerceIn(0f, 1f)
                        } else {
                            // abre a partir do dedo e volta para ele ao fechar
                            val tap = anchor.lastDown
                            transformOrigin = if (tap != null && card.width > 0f)
                                TransformOrigin(((tap.x - card.left) / card.width).coerceIn(-0.5f, 1.5f), ((tap.y - card.top) / card.height).coerceIn(-0.5f, 1.5f))
                            else TransformOrigin.Center
                            val s = lerp(0.18f, 1f, v)
                            scaleX = s; scaleY = s
                            alpha = (v * 2.4f).coerceIn(0f, 1f)
                        }
                    }
                    .clip(shape)
                    .background(Color(0xFF0A0A0A))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                    .pointerInput(Unit) { detectTapGestures { } }, // toques dentro não fecham
                ) {
                    PickerBody(roll, kinds, reduced, low, progress = { p.value }, onPick = ::pick, onClose = ::close)
                }
            }
            // a miniatura escolhida voando até o banner
            flight?.let { f ->
                val img = remember(f) { f.bitmap.asImageBitmap() }
                val corner = with(density) { 14.dp.toPx() }
                Box(Modifier
                    .align(Alignment.TopStart)
                    .requiredSize(with(density) { f.to.width.coerceAtLeast(1f).toDp() }, with(density) { f.to.height.coerceAtLeast(1f).toDp() })
                    .graphicsLayer {
                        val t = fly.value
                        val l = lerp(f.from.left, f.to.left, t); val tp = lerp(f.from.top, f.to.top, t)
                        val ww = lerp(f.from.width, f.to.width, t); val hh = lerp(f.from.height, f.to.height, t)
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = l; translationY = tp
                        scaleX = ww / f.to.width.coerceAtLeast(1f); scaleY = hh / f.to.height.coerceAtLeast(1f)
                        // some suavemente ao pousar, revelando o banner (que já trocou por baixo)
                        alpha = 1f - ((t - 0.6f) / 0.4f).coerceIn(0f, 1f).let { it * it }
                        shape = RoundedCornerShape(lerp(corner, 0f, t.coerceIn(0f, 1f)) / scaleX.coerceAtLeast(0.01f))
                        clip = true
                    }) {
                    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun PickerBody(roll: BannerRoll, kinds: List<RollKind>, reduced: Boolean, low: Boolean, progress: () -> Float, onPick: (RollItem, Rect?) -> Unit, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(if (kinds.size == 1) kinds.first() else null) }
    val current = roll.current
    val cascade = !reduced && Motion.prefs.listAppear
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val canBack = kind != null && kinds.size > 1
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(canBack, enter = fadeIn(tween(Motion.d(180))) + scaleIn(spring(0.6f, 600f), 0.6f), exit = fadeOut(tween(Motion.d(120))) + scaleOut(tween(Motion.d(160)), targetScale = 0.6f)) {
                    IconButton(onClick = { kind = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                }
            }
            AnimatedContent(kind, Modifier.weight(1f), label = "pickerTitle", transitionSpec = {
                if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(tween(Motion.d(220), Motion.d(60), Motion.EmphasizedDecel)) + androidx.compose.animation.slideInVertically(tween(Motion.d(280), easing = Motion.Emphasized)) { it / 3 }) togetherWith fadeOut(tween(Motion.d(100)))
            }) { k ->
                Column {
                    Text(if (k == null) "Escolher banner" else kindLabel(k), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (k == null) "Qual tipo de mídia?" else roll.carousel.count { it.kind == k }.let { "$it ${kindLabel(k, it).lowercase()} · toque para usar" },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Fechar") }
        }
        // abas de tipo com indicador deslizante (só na grade e com mais de um tipo)
        AnimatedVisibility(kind != null && kinds.size > 1,
            enter = if (reduced) fadeIn(snap()) else fadeIn(tween(Motion.d(220), Motion.d(80))) + expandVertically(tween(Motion.d(300), easing = Motion.Emphasized)),
            exit = if (reduced) fadeOut(snap()) else fadeOut(tween(Motion.d(120))) + shrinkVertically(tween(Motion.d(240), easing = Motion.Emphasized))) {
            KindTabs(kinds, kind, reduced) { kind = it }
        }
        AnimatedContent(kind, Modifier.fillMaxSize(), label = "pickerStep", transitionSpec = {
            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
            else {
                val from = initialState?.let { kinds.indexOf(it) } ?: -1
                val to = targetState?.let { kinds.indexOf(it) } ?: -1
                val fwd = to > from
                (fadeIn(tween(Motion.d(260), Motion.d(70), Motion.EmphasizedDecel)) + slideInHorizontally(tween(Motion.d(380), easing = Motion.Emphasized)) { (if (fwd) it else -it) / 6 }) togetherWith
                    (fadeOut(tween(Motion.d(130), easing = Motion.EmphasizedAccel)) + slideOutHorizontally(tween(Motion.d(320), easing = Motion.Emphasized)) { (if (fwd) -it else it) / 8 })
            }
        }) { k ->
            if (k == null) KindQuestion(roll, kinds, cascade) { kind = it }
            else MediaGrid(roll.carousel.filter { it.kind == k }, current?.id, reduced, cascade, low, onPick)
        }
    }
}

/** Abas GIF / Foto / Vídeo, com a pílula branca deslizando em mola até a aba escolhida (desenho só, sem recompor). */
@Composable
private fun KindTabs(kinds: List<RollKind>, kind: RollKind?, reduced: Boolean, onKind: (RollKind) -> Unit) {
    val idx = kinds.indexOf(kind).coerceAtLeast(0)
    val pos by animateFloatAsState(idx.toFloat(), if (reduced) snap() else PickerSprings.indicator(), label = "tab")
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(40.dp).clip(shape).background(Color(0xFF161616)).border(1.dp, Color.White.copy(alpha = 0.08f), shape)) {
        val seg = maxWidth / kinds.size
        Box(Modifier.width(seg).fillMaxHeight().padding(3.dp)
            .graphicsLayer {
                translationX = pos * seg.toPx()
                // leve esticada enquanto desliza
                val moving = (1f - kotlin.math.abs(pos - pos.roundToInt()) * 2f).coerceIn(0f, 1f)
                scaleX = lerp(1.08f, 1f, moving)
            }
            .clip(shape).background(Color.White))
        Row(Modifier.fillMaxSize()) {
            kinds.forEachIndexed { i, k ->
                val on = i == idx
                val c by animateColorAsState(if (on) Color.Black else Color.White.copy(alpha = 0.72f), tween(Motion.d(200)), label = "tabText")
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape).clickable { onKind(k) }, contentAlignment = Alignment.Center) {
                    Text(kindLabel(k, 1), style = MaterialTheme.typography.labelLarge, color = c, maxLines = 1)
                }
            }
        }
    }
}

/** Passo 1: GIF / Foto / Vídeo (só os que têm mídia), cartões grandes que entram em cascata. */
@Composable
private fun KindQuestion(roll: BannerRoll, kinds: List<RollKind>, cascade: Boolean, onKind: (RollKind) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        kinds.forEachIndexed { i, k ->
            val items = remember(roll, k) { roll.carousel.filter { it.kind == k } }
            val a = remember { Animatable(if (cascade) 0f else 1f) }
            LaunchedEffect(Unit) { if (cascade) { delay(Motion.d(90 + i * 80).toLong()); a.animateTo(1f, PickerSprings.cell()) } }
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val press by animateFloatAsState(if (pressed) 0.96f else 1f, if (!cascade) snap() else Motion.press(), label = "press")
            val shape = RoundedCornerShape(22.dp)
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 96.dp, max = 150.dp).aspectRatio(2.6f, matchHeightConstraintsFirst = false)
                .graphicsLayer {
                    val v = a.value
                    alpha = v.coerceIn(0f, 1f)
                    val s = lerp(0.85f, 1f, v) * press
                    scaleX = s; scaleY = s
                    translationY = (1f - v) * 28.dp.toPx()
                }
                .clip(shape).background(Color(0xFF141414)).border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .clickable(src, null) { onKind(k) }) {
                Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.58f)) {
                    items.take(3).forEach { item -> Box(Modifier.weight(1f).fillMaxHeight()) { Thumb(item, Modifier.matchParentSize(), animateGif = false) } }
                }
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color(0xFF141414), 0.45f to Color(0xFF141414), 0.75f to Color(0xCC141414), 1f to Color(0x33141414))))
                Row(Modifier.align(Alignment.CenterStart).padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(when (k) { RollKind.GIF -> Icons.Outlined.Gif; RollKind.PHOTO -> Icons.Outlined.Image; RollKind.VIDEO -> Icons.Outlined.PlayArrow }, null, Modifier.size(22.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(kindLabel(k, 1), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("${items.size} ${kindLabel(k, items.size).lowercase()}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/**
 * Passo 2: grade preguiçosa (chaves estáveis, miniaturas em cache, decodificação fora da thread principal).
 * Entrada em cascata item a item, linha a linha; rolando, as linhas que saem por cima e as que chegam por baixo
 * somem/aparecem com fade e escala, calculados só na camada de desenho (sem recompor por quadro).
 */
@Composable
private fun MediaGrid(items: List<RollItem>, selected: String?, reduced: Boolean, cascade: Boolean, low: Boolean, onPick: (RollItem, Rect?) -> Unit) {
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (items.indexOfFirst { it.id == selected }.coerceAtLeast(0) / COLS * COLS).let { if (it < COLS * 3) 0 else it - COLS })
    val start = remember { android.os.SystemClock.uptimeMillis() }
    LazyVerticalGrid(GridCells.Fixed(COLS), Modifier.fillMaxSize(), state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, it -> it.kind }) { i, item ->
            GridCell(item, i, item.id == selected, state, start, reduced, cascade, low, onPick)
        }
    }
}

@Composable
private fun GridCell(item: RollItem, index: Int, selected: Boolean, grid: LazyGridState, start: Long, reduced: Boolean, cascade: Boolean, low: Boolean, onPick: (RollItem, Rect?) -> Unit) {
    val appear = remember { Animatable(if (cascade) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!cascade) return@LaunchedEffect
        // abertura: item a item, linha a linha (linha × 55 ms + coluna × 28 ms); depois, ao rolar, entra na hora
        val initial = android.os.SystemClock.uptimeMillis() - start < 380
        if (initial) {
            val row = (index / COLS - grid.firstVisibleItemIndex / COLS).coerceIn(0, 10)
            delay(Motion.d(110 + row * 55 + (index % COLS) * 28).toLong())
        }
        appear.animateTo(1f, PickerSprings.cell())
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (reduced) press.snapTo(1f)
        else if (pressed) press.animateTo(0.92f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh))
        else press.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
    }
    val pop by animateFloatAsState(if (selected) 1f else 0f, if (reduced) snap() else PickerSprings.pop(), label = "pop")
    val ring by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.08f), tween(Motion.d(220)), label = "ring")
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.aspectRatio(1f)
        .onGloballyPositioned { coords[0] = it }
        .graphicsLayer {
            val a = appear.value
            var edge = 1f
            if (!reduced) {
                val info = grid.layoutInfo
                val me = info.visibleItemsInfo.firstOrNull { it.index == index }
                if (me != null) {
                    val top = me.offset.y - info.viewportStartOffset
                    val bottom = top + me.size.height
                    val vh = info.viewportEndOffset - info.viewportStartOffset
                    val zone = me.size.height * 0.9f
                    val e = minOf((bottom / zone).coerceIn(0f, 1f), ((vh - top) / zone).coerceIn(0f, 1f))
                    edge = e * e * (3f - 2f * e) // smoothstep
                    // a linha que sai encolhe para o lado de fora (cima/baixo)
                    transformOrigin = TransformOrigin(0.5f, if (top < vh / 2) 1f else 0f)
                }
            }
            alpha = (a.coerceIn(0f, 1f) * lerp(0.1f, 1f, edge))
            val s = lerp(0.85f, 1f, a) * lerp(0.84f, 1f, edge) * press.value
            scaleX = s; scaleY = s
            translationY = (1f - a) * 18.dp.toPx()
        }
        .clip(shape).background(Color(0xFF151515))
        .border(lerp(1f, 2f, pop.coerceIn(0f, 1f)).dp, ring, shape)
        .clickable(src, null, onClickLabel = "Usar como banner") { onPick(item, coords[0]?.takeIf { it.isAttached }?.screenRect()) }) {
        Thumb(item, Modifier.matchParentSize(), animateGif = !low)
        if (item.kind == RollKind.VIDEO) Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp), tint = Color.White)
        }
        if (pop > 0.01f) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
            .graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f); rotationZ = (1f - pop) * -40f }
            .clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, "Em uso", Modifier.size(14.dp), tint = Color.Black)
        }
    }
}

/** Miniatura nítida (decodificada no tamanho da célula, cacheada) com GIF animado no Android 9+. */
@Composable
private fun Thumb(item: RollItem, modifier: Modifier, animateGif: Boolean) {
    val ctx = LocalContext.current
    val cached = remember(item.id) { BannerLibrary.cachedThumb(item, GRID_PX) }
    val bmp by produceState(cached, item.id) { if (value == null) value = BannerLibrary.thumb(ctx, item, GRID_PX) }
    // já em cache: aparece na hora (sem piscar); senão, fade curto quando chega
    val fade = remember(item.id) { Animatable(if (cached != null) 1f else 0f) }
    LaunchedEffect(bmp != null) { if (bmp != null && fade.value < 1f) fade.animateTo(1f, tween(Motion.d(200))) }
    Box(modifier) {
        bmp?.let { b -> val img = remember(b) { b.asImageBitmap() }; Image(img, null, Modifier.matchParentSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop) }
        if (animateGif && item.kind == RollKind.GIF && Build.VERSION.SDK_INT >= 28) AnimatedGif(item, Modifier.matchParentSize())
    }
}

@Composable
private fun AnimatedGif(item: RollItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val d by produceState<Drawable?>(null, item.id) { value = BannerLibrary.animatedGif(ctx, item, GRID_PX) }
    val drawable = d ?: return
    val tick = remember(drawable) { mutableIntStateOf(0) }
    // o Drawable guarda o callback só por referência fraca: fica lembrado aqui
    val cb = remember(drawable) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { tick.intValue++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
    }
    DisposableEffect(drawable) {
        drawable.callback = cb
        if (Build.VERSION.SDK_INT >= 28) (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
        onDispose { if (Build.VERSION.SDK_INT >= 28) (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop(); drawable.callback = null }
    }
    Canvas(modifier) {
        tick.intValue
        val iw = drawable.intrinsicWidth.coerceAtLeast(1).toFloat(); val ih = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
        val s = maxOf(size.width / iw, size.height / ih)
        drawable.setBounds(0, 0, iw.toInt(), ih.toInt())
        drawIntoCanvas { c ->
            c.save()
            c.translate((size.width - iw * s) / 2f, (size.height - ih * s) / 2f)
            c.scale(s, s)
            drawable.draw(c.nativeCanvas)
            c.restore()
        }
    }
}
