package com.coinportracker.ui.screens

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.coinportracker.media.PendingVideo
import com.coinportracker.media.VideoFormat
import com.coinportracker.media.VideoImport
import kotlin.math.roundToLong

/**
 * Recorte no app: prévia, linha do tempo com alças de início e fim, teto rígido de 60 s.
 * [preview] permite capturar a tela na JVM sem ExoPlayer (duração e janela fixas).
 */
@Composable
fun VideoTrimScreen(
    nav: androidx.navigation.NavHostController,
    preview: VideoFormat.TrimWindow? = null,
) {
    val pending = if (preview != null) null else VideoImport.pending.collectAsState().value
    val duration = preview?.durationMs ?: pending?.durationMs ?: 0L
    if (preview == null && pending == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    var win by remember { mutableStateOf(preview ?: VideoFormat.initialTrim(duration)) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        V2TopBar("Recortar vídeo", { VideoImport.cancel(); nav.popBackStack() }) {
            TextButton(onClick = {
                if (preview == null && pending != null) {
                    VideoImport.confirm(ctx, win.startMs, win.endMs)
                    nav.popBackStack()
                }
            }) { Text("Usar") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (preview != null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
            } else if (pending != null) {
                TrimPlayer(pending, win)
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 18.dp, 16.dp, 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${VideoFormat.fmtTime(win.startMs)} – ${VideoFormat.fmtTime(win.endMs)}", style = MaterialTheme.typography.titleSmall)
                Text(VideoFormat.fmtTime(win.lengthMs), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            TrimBar(win, duration) { start, end, movedEnd -> win = VideoFormat.clampTrim(start, end, duration, movedEnd = movedEnd) }
        }
    }
}

@Composable
private fun TrimPlayer(pending: PendingVideo, win: VideoFormat.TrimWindow) {
    val ctx = LocalContext.current
    val player = remember(pending.uri) {
        runCatching {
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(pending.uri))
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(win.startMs, win.endMs, player) {
        val p = player ?: return@LaunchedEffect
        p.setMediaItem(
            MediaItem.Builder().setUri(pending.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(win.startMs).setEndPositionMs(win.endMs).build())
                .build()
        )
        p.prepare(); p.playWhenReady = true
    }
    if (player == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
        return
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        TextureView(c).also { player.setVideoTextureView(it) }
    }, update = { player.setVideoTextureView(it) })
}

@Composable
fun TrimBar(win: VideoFormat.TrimWindow, durationMs: Long, onChange: (Long, Long, Boolean) -> Unit) {
    val dur = durationMs.coerceAtLeast(1L)
    var widthPx by remember { mutableIntStateOf(1) }
    val handle = 22.dp
    val handlePx = with(LocalDensity.current) { handle.toPx() }
    fun xOf(ms: Long) = (ms.toFloat() / dur) * widthPx
    fun msOf(x: Float) = ((x / widthPx) * dur).roundToLong().coerceIn(0, dur)
    Box(Modifier.fillMaxWidth().height(44.dp).onSizeChanged { widthPx = it.width }) {
        // trilho
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        val startX = xOf(win.startMs)
        val endX = xOf(win.endMs)
        // seleção
        Box(Modifier.align(Alignment.CenterStart).offset(x = with(LocalDensity.current) { startX.toDp() }).width(with(LocalDensity.current) { (endX - startX).toDp() })
            .height(6.dp).background(MaterialTheme.colorScheme.primary))
        Handle(Modifier.offset(x = with(LocalDensity.current) { (startX - handlePx / 2).toDp() }), left = true) { dx ->
            onChange(msOf(startX + dx), win.endMs, false)
        }
        Handle(Modifier.offset(x = with(LocalDensity.current) { (endX - handlePx / 2).toDp() }), left = false) { dx ->
            onChange(win.startMs, msOf(endX + dx), true)
        }
    }
}

@Composable
private fun Handle(modifier: Modifier, left: Boolean, onDrag: (Float) -> Unit) {
    Box(modifier.size(22.dp).pointerInput(left) {
        detectDragGestures { change, drag -> change.consume(); onDrag(drag.x) }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp, 22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}
