package com.coinportracker.ui.components

import com.coinportracker.ui.theme.LocalLowPower

import android.graphics.BitmapFactory
import android.graphics.Movie
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coinportracker.settings.BannerFit
import com.coinportracker.settings.BannerStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mídia decodificada do banner: GIF animado, imagem estática ou vídeo (caminho).
 * v6.0: no Android 9+ o GIF é um AnimatedImageDrawable ([anim]): os quadros são decodificados fora da thread principal e a
 * animação roda na RenderThread, no tamanho de exibição. Antes, todo GIF usava Movie: o arquivo inteiro na memória e cada
 * quadro decodificado na thread principal (até 30 vezes por segundo), o que travava a rolagem com GIFs grandes.
 * [movie] continua existindo no Android 8 e quando a velocidade do banner é diferente de 1× (o drawable não tem relógio próprio).
 */
@Stable
class BannerMedia(val movie: Movie?, val still: androidx.compose.ui.graphics.ImageBitmap?, val videoPath: String? = null,
                  val anim: android.graphics.drawable.Drawable? = null) {
    val width: Int get() = movie?.width() ?: anim?.intrinsicWidth?.takeIf { it > 0 } ?: still?.width ?: 0
    val height: Int get() = movie?.height() ?: anim?.intrinsicHeight?.takeIf { it > 0 } ?: still?.height ?: 0
    val duration: Int get() = movie?.duration()?.takeIf { it > 0 } ?: if (anim != null) 1 else 0
    val isVideo: Boolean get() = videoPath != null
}

/** v6.0 — decodificação dos GIFs do banner (sempre chamada fora da thread principal). */
object BannerGif {
    /** Lado maior máximo do GIF decodificado (nítido em telas 1440p, sem gastar memória com GIFs de 4K). */
    const val MAX_SIDE = 1440

    fun isGif(head: ByteArray) = head.size > 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()

    /** Fator de redução para caber em [MAX_SIDE] (1 = tamanho original). */
    fun downscale(w: Int, h: Int, max: Int = MAX_SIDE): Float { val m = maxOf(w, h); return if (m <= max || m <= 0) 1f else max.toFloat() / m }

    /** AnimatedImageDrawable de um arquivo ou asset (API 28+); null se não for GIF animado. */
    fun animated(ctx: android.content.Context, source: String): android.graphics.drawable.Drawable? {
        if (Build.VERSION.SDK_INT < 28) return null
        val src = if (source.startsWith("asset:")) android.graphics.ImageDecoder.createSource(ctx.assets, source.removePrefix("asset:"))
            else File(source.substringBefore("?v=")).takeIf { it.isFile }?.let { android.graphics.ImageDecoder.createSource(it) } ?: return null
        return runCatching {
            android.graphics.ImageDecoder.decodeDrawable(src) { d, info, _ ->
                val f = downscale(info.size.width, info.size.height)
                if (f < 1f) d.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
            }.takeIf { it is android.graphics.drawable.AnimatedImageDrawable }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun movie(bytes: ByteArray): Movie? = Movie.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width() > 0 && it.duration() > 0 }
}

/** Origem do banner: arquivo escolhido pelo usuário ou GIF padrão embutido (assets/banner/default.gif). */
const val DEFAULT_BANNER = "asset:banner/default.gif"

/** [source] ou a cópia ampliada dela (quando "Melhorar resolução" está ligado e a cópia já ficou pronta). */
@Composable
fun rememberHdSource(source: String, enabled: Boolean): String {
    val ctx = LocalContext.current
    val v by com.coinportracker.media.MediaHd.version.collectAsState()
    // v5: a checagem do arquivo HD (exists/lastModified) roda em Dispatchers.IO, não na composição
    if (com.coinportracker.media.VideoFormat.isVideoPath(source)) return source
    val initial = com.coinportracker.media.MediaHd.cachedResolve(source, enabled, v) ?: source
    return produceState(initial, source, enabled, v) { value = com.coinportracker.media.MediaHd.resolveAsync(ctx, source, enabled, v) }.value
}

@Composable
fun rememberBannerMedia(source: String, speed: Float = 1f): BannerMedia? {
    val ctx = LocalContext.current
    // velocidade diferente de 1× precisa do relógio próprio (Movie); 1× ou pausado usa o drawable animado
    val needMovie = Build.VERSION.SDK_INT < 28 || (speed != 1f && speed != 0f)
    var media by remember(source) { mutableStateOf<BannerMedia?>(null) }
    LaunchedEffect(source, needMovie) {
        media = withContext(Dispatchers.IO) {
            runCatching {
                val path = source.substringBefore("?v=")
                if (!source.startsWith("asset:") && com.coinportracker.media.VideoFormat.isVideoPath(source)) {
                    return@runCatching if (File(path).exists()) BannerMedia(null, null, path) else null
                }
                if (source.isBlank()) return@runCatching null
                val head = ByteArray(8)
                val n = (if (source.startsWith("asset:")) ctx.assets.open(source.removePrefix("asset:")) else File(path).takeIf { it.isFile }?.inputStream())?.use { it.read(head) } ?: return@runCatching null
                if (n >= 6 && BannerGif.isGif(head) && !needMovie) BannerGif.animated(ctx, source)?.let { return@runCatching BannerMedia(null, null, anim = it) }
                val bytes = when {
                    source.startsWith("asset:") -> ctx.assets.open(source.removePrefix("asset:")).use { it.readBytes() }
                    else -> File(path).takeIf { it.exists() }?.readBytes()
                } ?: return@runCatching null
                val movie = if (BannerGif.isGif(bytes)) BannerGif.movie(bytes) else null
                if (movie != null) BannerMedia(movie, null) else {
                    // estática: reduz para no máximo ~1080 px de largura
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var sample = 1; while (o.outWidth / (sample * 2) >= 1080) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { BannerMedia(null, it.asImageBitmap()) }
                }
            }.getOrNull()
        }
    }
    return media
}

/**
 * GIF/imagem discreta atrás do saldo. Opacidade, escala, deslocamento, desfoque, cantos, encaixe e velocidade vêm de [BannerStyle].
 * O relógio do GIF avança no máximo 30 vezes por segundo e só invalida o desenho (sem recomposição); sai da composição = pausa.
 */
@Composable
fun BannerBackdrop(media: BannerMedia?, style: BannerStyle, background: Color, modifier: Modifier, animate: Boolean = true) {
    if (media == null || !style.enabled) return
    media.videoPath?.let { VideoBackdrop(it, style, background, modifier); return }
    val clock = remember(media) { mutableIntStateOf(0) }
    val speed = style.speed
    val animateGif = animate && media.duration > 0 && speed > 0f && !LocalLowPower.current
    val anim = media.anim.takeIf { media.movie == null }
    if (anim != null) {
        // drawable animado: a cada quadro só invalida o desenho (a decodificação não passa pela thread principal)
        val cb = remember(anim) { object : android.graphics.drawable.Drawable.Callback {
            override fun invalidateDrawable(who: android.graphics.drawable.Drawable) { clock.intValue++ }
            override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) {}
            override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) {}
        } }
        DisposableEffect(anim) {
            anim.callback = cb
            anim.setBounds(0, 0, media.width.coerceAtLeast(1), media.height.coerceAtLeast(1))
            onDispose { if (Build.VERSION.SDK_INT >= 28) (anim as? android.graphics.drawable.AnimatedImageDrawable)?.stop(); anim.callback = null }
        }
        LaunchedEffect(anim, animateGif) {
            if (Build.VERSION.SDK_INT >= 28) (anim as? android.graphics.drawable.AnimatedImageDrawable)?.let { if (animateGif) it.start() else it.stop() }
        }
    } else if (animateGif) {
        LaunchedEffect(media, speed) {
            var last = -1L; var t = clock.intValue.toDouble()
            while (isActive) {
                withFrameMillis { now ->
                    if (last >= 0) t += (now - last) * speed
                    last = now
                    val q = ((t % media.duration) / 33).toInt() * 33 // ≤ 30 quadros/s
                    if (q != clock.intValue) clock.intValue = q
                }
            }
        }
    }
    val corner = style.cornerDp.dp
    val blurMod = if (style.blurDp > 0f && Build.VERSION.SDK_INT >= 31) Modifier.blur(style.blurDp.dp) else Modifier
    Box(modifier
        .then(if (style.cornerDp > 0f) Modifier.clip(RoundedCornerShape(corner)) else Modifier)
        .graphicsLayer { alpha = style.opacity.coerceIn(0f, 1f) }
        .then(blurMod)
        .drawWithCache {
            val w = media.width.toFloat().coerceAtLeast(1f); val h = media.height.toFloat().coerceAtLeast(1f)
            val sx0 = size.width / w; val sy0 = size.height / h
            val (sx, sy) = when (style.fit) {
                BannerFit.CROP -> maxOf(sx0, sy0).let { it to it }
                BannerFit.FIT -> minOf(sx0, sy0).let { it to it }
                BannerFit.FILL -> sx0 to sy0
            }
            val fx = sx * style.scale; val fy = sy * style.scale
            val dx = (size.width - w * fx) / 2 + style.offsetX * size.width / 2
            val dy = (size.height - h * fy) / 2 + style.offsetY * size.height / 2
            onDrawBehind {
                clipRect {
                    withTransform({ translate(dx, dy); scale(fx, fy, Offset.Zero) }) {
                        val m = media.movie
                        if (m != null) {
                            m.setTime(clock.intValue)
                            drawIntoCanvas { m.draw(it.nativeCanvas, 0f, 0f) }
                        } else if (anim != null) {
                            clock.intValue   // lê o estado: redesenha quando o drawable pede
                            drawIntoCanvas { anim.draw(it.nativeCanvas) }
                        } else media.still?.let { drawImage(it) }
                    }
                }
            }
        })
    if (style.fade) BannerFade(background, modifier)
}

/** Esmaecer nas bordas (mesmo desenho do banner único). */
@Composable
fun BannerFade(background: Color, modifier: Modifier) {
    Box(modifier.drawWithCache {
        val b = Brush.verticalGradient(0f to background.copy(alpha = 0.15f), 0.55f to background.copy(alpha = 0.35f), 1f to background)
        val side = Brush.horizontalGradient(0f to background.copy(alpha = 0.45f), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to background.copy(alpha = 0.45f))
        onDrawBehind { drawRect(b); drawRect(side) }
    })
}

/** Estado da melhoria de resolução (v3.2), observado do WorkManager. */
@Composable
fun rememberHdStatus(source: String, enabled: Boolean): com.coinportracker.media.HdStatus {
    val ctx = LocalContext.current
    val flow = remember(source, enabled) { com.coinportracker.media.MediaHd.status(ctx, source, enabled) }
    val st by flow.collectAsState(com.coinportracker.media.HdStatus(if (enabled) com.coinportracker.media.HdState.QUEUED else com.coinportracker.media.HdState.OFF))
    // relógio de 1 s para o tempo decorrido andar mesmo entre atualizações do trabalho
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(st.state) { while (st.state == com.coinportracker.media.HdState.RUNNING) { kotlinx.coroutines.delay(1000); tick++ } }
    return remember(st, tick) {
        if (st.state == com.coinportracker.media.HdState.RUNNING && st.startedAt > 0) { val el = System.currentTimeMillis() - st.startedAt; st.copy(elapsedMs = el, etaMs = com.coinportracker.media.HdEta.eta(st.progress, el)) } else st
    }
}

internal fun hdLabel(s: com.coinportracker.media.HdStatus): String = when (s.state) {
    com.coinportracker.media.HdState.OFF -> "Desligado"
    com.coinportracker.media.HdState.QUEUED -> "Na fila"
    com.coinportracker.media.HdState.RUNNING -> "Melhorando ${(s.progress * 100).toInt()}%"
    com.coinportracker.media.HdState.PAUSED -> "Pausado" + (s.reason?.let { " · $it" } ?: "")
    com.coinportracker.media.HdState.DONE -> if (s.method == "LANCZOS") "Pronto (método rápido)" else "Pronto"
    com.coinportracker.media.HdState.ORIGINAL -> "Usando original"
}

internal fun secs(ms: Long): String = if (ms < 60_000) "${(ms + 500) / 1000} s" else "${ms / 60_000} min ${(ms % 60_000) / 1000} s"

/** Indicador discreto sobre o banner enquanto a cópia HD é gerada. */
@Composable
fun HdBadge(s: com.coinportracker.media.HdStatus, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(s.state == com.coinportracker.media.HdState.RUNNING || s.state == com.coinportracker.media.HdState.QUEUED, modifier,
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        androidx.compose.foundation.layout.Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (s.state == com.coinportracker.media.HdState.RUNNING && s.progress > 0f)
                androidx.compose.material3.CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = androidx.compose.ui.graphics.Color.White)
            else androidx.compose.material3.CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = androidx.compose.ui.graphics.Color.White)
            androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
            androidx.compose.material3.Text("HD", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

/** Cartão de status da melhoria (v3.2): estado, %, tempo decorrido/estimado e, quando pronto, antes × depois. */
@Composable
fun HdStatusCard(source: String, enabled: Boolean) {
    if (!enabled) return
    val s = rememberHdStatus(source, true)
    HdStatusView(s) { BeforeAfter(source, Modifier.padding(top = 10.dp)) }
}

/** Visual do status (sem WorkManager), usado pela tela e pelas prévias. */
@Composable
fun HdStatusView(s: com.coinportracker.media.HdStatus, compare: @Composable () -> Unit = {}) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 6.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Text(hdLabel(s), style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val info = when (s.state) {
                com.coinportracker.media.HdState.RUNNING -> secs(s.elapsedMs) + (s.etaMs?.let { " · falta ~" + secs(it) } ?: " · estimando…")
                com.coinportracker.media.HdState.DONE -> if (s.elapsedMs > 0) "em " + secs(s.elapsedMs) else ""
                else -> ""
            }
            androidx.compose.material3.Text(info, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        if (s.state == com.coinportracker.media.HdState.RUNNING || s.state == com.coinportracker.media.HdState.QUEUED) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            val p by androidx.compose.animation.core.animateFloatAsState(s.progress, label = "hdp")
            if (s.state == com.coinportracker.media.HdState.QUEUED) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidthCompat())
            else androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidthCompat())
        }
        val sub = when (s.state) {
            com.coinportracker.media.HdState.RUNNING -> "IA no aparelho" + (s.accel?.let { " · $it" } ?: "") + " · em segundo plano, prioridade baixa"
            com.coinportracker.media.HdState.PAUSED -> "Continua sozinho quando o celular esfriar ou a economia de bateria for desligada."
            com.coinportracker.media.HdState.ORIGINAL -> "A mídia já tem resolução suficiente para a tela (ou não pôde ser melhorada)."
            com.coinportracker.media.HdState.DONE -> if (s.method == "LANCZOS") "A IA passaria do limite de tempo; usei a ampliação rápida." else "Arraste para comparar antes × depois."
            else -> null
        }
        sub?.let { androidx.compose.material3.Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        if (s.state == com.coinportracker.media.HdState.DONE && s.method != "LANCZOS") compare()
    }
}

private fun Modifier.fillMaxWidthCompat() = this.fillMaxWidth()

/** Antes × depois com divisor arrastável (original à esquerda, HD à direita). */
@Composable
fun BeforeAfter(source: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val v by com.coinportracker.media.MediaHd.version.collectAsState()
    val pair by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.graphics.ImageBitmap>?>(null, source, v) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val orig = com.coinportracker.media.MediaHd.readBytes(ctx, source) ?: return@runCatching null
                val hd = com.coinportracker.media.MediaHd.hdFile(ctx, source).takeIf { it.exists() }?.readBytes() ?: return@runCatching null
                fun dec(b: ByteArray, maxW: Int): android.graphics.Bitmap? {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    var ss = 1; while (o.outWidth / (ss * 2) >= maxW) ss *= 2
                    return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
                }
                val a = dec(orig, 1080) ?: return@runCatching null; val b = dec(hd, 1440) ?: return@runCatching null
                a.asImageBitmap() to b.asImageBitmap()
            }.getOrNull()
        }
    }
    val p = pair ?: return
    BeforeAfterView(p.first, p.second, modifier)
}

@Composable
fun BeforeAfterView(before: androidx.compose.ui.graphics.ImageBitmap, after: androidx.compose.ui.graphics.ImageBitmap, modifier: Modifier = Modifier, initialSplit: Float = 0.5f) {
    val p = before to after
    var split by remember { mutableStateOf(initialSplit) }
    val shape = RoundedCornerShape(14.dp)
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(150.dp).clip(shape)
        .pointerInputCompat { x, w -> split = (x / w).coerceIn(0.02f, 0.98f) }) {
        val (a, b) = p
        // recorte central da área (zoom 2× para a diferença aparecer)
        fun dst() = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
        fun srcFor(img: androidx.compose.ui.graphics.ImageBitmap): Pair<androidx.compose.ui.unit.IntOffset, androidx.compose.ui.unit.IntSize> {
            val ar = size.width / size.height
            var sw = img.width / 2; var sh = (sw / ar).toInt()
            if (sh > img.height) { sh = img.height; sw = (sh * ar).toInt() }
            return androidx.compose.ui.unit.IntOffset((img.width - sw) / 2, (img.height - sh) / 2) to androidx.compose.ui.unit.IntSize(sw, sh)
        }
        val (bo, bs) = srcFor(b); drawImage(b, bo, bs, dstSize = dst(), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
        clipRect(right = size.width * split) { val (ao, asz) = srcFor(a); drawImage(a, ao, asz, dstSize = dst(), filterQuality = androidx.compose.ui.graphics.FilterQuality.Low) }
        drawLine(Color.White, Offset(size.width * split, 0f), Offset(size.width * split, size.height), 2.dp.toPx())
        drawCircle(Color.White, 9.dp.toPx(), Offset(size.width * split, size.height / 2))
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        androidx.compose.material3.Text("Antes", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        androidx.compose.material3.Text("Depois (HD)", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Modifier.pointerInputCompat(onX: (Float, Float) -> Unit) = this.then(Modifier.pointerInput(Unit) {
    detectDragGesturesCompat { x -> onX(x, size.width.toFloat()) }
})

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGesturesCompat(onX: (Float) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(); onX(down.position.x)
        while (true) { val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break; if (!c.pressed) break; c.consume(); onX(c.position.x) }
    }
}
