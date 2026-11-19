package com.coinportracker.ui.components

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.coinportracker.settings.BannerFit
import com.coinportracker.settings.BannerStyle
import com.coinportracker.ui.theme.LocalLowPower
import android.os.Build

/**
 * Vídeo de fundo (ExoPlayer): mudo, em loop, pausa com a tela apagada ou fora da área visível,
 * e o player é liberado ao sair da composição. TextureView para opacidade, cantos e desfoque
 * funcionarem no Compose.
 */
@Composable
fun VideoBackdrop(path: String, style: BannerStyle, background: Color, modifier: Modifier) {
    if (!style.enabled) return
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var windowH by remember { mutableIntStateOf(0) }
    var onScreen by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) started = true
            if (e == Lifecycle.Event.ON_STOP) started = false
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val low = LocalLowPower.current
    val play = onScreen && started && style.speed > 0f && !low
    val player = remember(path) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path.substringBefore("?v=")))))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(play, style.speed, player) {
        player.setPlaybackSpeed(style.speed.coerceIn(0.25f, 3f))
        player.playWhenReady = play
        if (!play) player.pause()
    }
    val corner = style.cornerDp.dp
    val blurMod = if (style.blurDp > 0f && Build.VERSION.SDK_INT >= 31) Modifier.blur(style.blurDp.dp) else Modifier
    Box(modifier
        .then(if (style.cornerDp > 0f) Modifier.clip(RoundedCornerShape(corner)) else Modifier)
        .graphicsLayer { alpha = style.opacity.coerceIn(0f, 1f) }
        .then(blurMod)
    ) {
        AndroidView(
            modifier = Modifier.matchParentSize().onGloballyPositioned { coords ->
                val win = coords.findRootSize()
                if (win > 0) windowH = win
                val top = coords.positionInWindow().y
                val h = coords.size.height.toFloat()
                val limit = if (windowH > 0) windowH.toFloat() else Float.MAX_VALUE
                onScreen = h > 2f && top + h > 2f && top < limit
            },
            factory = { c ->
                TextureView(c).also { tv ->
                    player.setVideoTextureView(tv)
                    val apply: () -> Unit = {
                        val vs = player.videoSize
                        applyTransform(tv, vs, style)
                    }
                    tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
                    player.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) { apply() }
                    })
                }
            },
            update = { tv ->
                player.setVideoTextureView(tv)
                applyTransform(tv, player.videoSize, style)
            },
        )
    }
    if (style.fade) Box(modifier.drawWithCache {
        val b = Brush.verticalGradient(0f to background.copy(alpha = 0.15f), 0.55f to background.copy(alpha = 0.35f), 1f to background)
        val side = Brush.horizontalGradient(0f to background.copy(alpha = 0.45f), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to background.copy(alpha = 0.45f))
        onDrawBehind { drawRect(b); drawRect(side) }
    })
}

private fun androidx.compose.ui.layout.LayoutCoordinates.findRootSize(): Int {
    var c: androidx.compose.ui.layout.LayoutCoordinates? = this
    var last = this
    while (c != null) { last = c; c = c.parentLayoutCoordinates }
    return last.size.height
}

private fun applyTransform(tv: TextureView, vs: VideoSize, style: BannerStyle) {
    val cw = tv.width.toFloat(); val ch = tv.height.toFloat()
    val vw = vs.width.toFloat(); val vh = vs.height.toFloat()
    if (cw <= 0f || ch <= 0f || vw <= 0f || vh <= 0f) return
    val sx0 = cw / vw; val sy0 = ch / vh
    val (sx, sy) = when (style.fit) {
        BannerFit.CROP -> maxOf(sx0, sy0).let { it to it }
        BannerFit.FIT -> minOf(sx0, sy0).let { it to it }
        BannerFit.FILL -> sx0 to sy0
    }
    val fx = sx * style.scale; val fy = sy * style.scale
    val dx = (cw - vw * fx) / 2 + style.offsetX * cw / 2
    val dy = (ch - vh * fy) / 2 + style.offsetY * ch / 2
    val m = Matrix()
    m.setScale(fx, fy)
    m.postTranslate(dx, dy)
    tv.setTransform(m)
}


/** Player mudo em loop, sem estilo de banner — prévia do cartão de compartilhar. */
@Composable
fun LoopingVideo(path: String, modifier: Modifier, play: Boolean = true) {
    val playNow = play && !LocalLowPower.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) started = true
            if (e == Lifecycle.Event.ON_STOP) started = false
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val player = remember(path) {
        runCatching {
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path.substringBefore("?v=")))))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = false
                prepare()
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(playNow, started, player) { player?.playWhenReady = playNow && started; if (!(playNow && started)) player?.pause() }
    if (player == null) return
    AndroidView(modifier = modifier, factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } }, update = { player.setVideoTextureView(it) })
}
