package com.coinportracker.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recodifica um recorte de até 60 s para H.264 ~720p, sem áudio, bitrate modesto.
 * Roda fora da thread principal (WorkManager).
 */
@UnstableApi
object VideoTranscoder {
    suspend fun transcode(
        ctx: Context,
        src: Uri,
        dst: File,
        startMs: Long,
        endMs: Long,
        height: Int = VideoFormat.TARGET_HEIGHT,
        bitrate: Int = VideoFormat.TARGET_BITRATE,
        presentation: Presentation? = Presentation.createForHeight(height),
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.Main.immediate) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        val window = VideoFormat.clampTrim(startMs, endMs, (endMs - startMs).coerceAtLeast(endMs), VideoFormat.MAX_DURATION_MS)
        // o comprimento já vem limitado pelo recorte; usamos start/end crus coerentes
        val s = startMs.coerceAtLeast(0)
        val e = endMs.coerceAtLeast(s + VideoFormat.MIN_DURATION_MS)
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(s)
            .setEndPositionMs(e)
            .build()
        val mediaItem = MediaItem.Builder().setUri(src).setClippingConfiguration(clipping).build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOfNotNull(presentation)))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()
        val encoder = DefaultEncoderFactory.Builder(ctx.applicationContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(ctx.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(dst)
                    }
                    override fun onError(c: Composition, result: ExportResult, exception: ExportException) {
                        dst.delete()
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                })
                .build()
            val holder = ProgressHolder()
            val exec = Executors.newSingleThreadExecutor()
            fun poll() {
                if (!cont.isActive) return
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED && state != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                    exec.execute {
                        try { Thread.sleep(200) } catch (_: InterruptedException) { return@execute }
                        poll()
                    }
                }
            }
            cont.invokeOnCancellation {
                transformer.cancel()
                dst.delete()
                exec.shutdownNow()
            }
            transformer.start(composition, dst.absolutePath)
            exec.execute { poll() }
        }
    }.also { file ->
        if (!file.exists() || file.length() == 0L) error("Falha ao recodificar o vídeo")
    }

    /** Se o arquivo passar do teto, tenta de novo com bitrate menor. */
    suspend fun transcodeWithinCap(
        ctx: Context, src: Uri, dst: File, startMs: Long, endMs: Long,
        presentation: Presentation? = Presentation.createForHeight(VideoFormat.TARGET_HEIGHT),
        rates: IntArray = intArrayOf(VideoFormat.TARGET_BITRATE, VideoFormat.LOW_BITRATE, VideoFormat.MIN_BITRATE),
        capBytes: Long = VideoFormat.MAX_FILE_BYTES,
        onProgress: (Float) -> Unit,
    ): File {
        var rate = VideoFormat.TARGET_BITRATE
        var last: File? = null
        var attempt = 0
        for (b in rates) {
            rate = b
            last = transcode(ctx, src, dst, startMs, endMs, bitrate = rate, presentation = presentation) { p ->
                onProgress((attempt + p) / rates.size)
            }
            attempt++
            if (last.length() <= capBytes) return last
        }
        last?.delete()
        error("O vídeo ficou grande demais mesmo depois de compactar.")
    }
}
