package com.coinportracker.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
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
import com.coinportracker.media.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exporta o cartão como MP4 curto: o vídeo de fundo (já recortado, ≤ 60 s) recortado em 4:5
 * com a interface do cartão sobreposta. Sem áudio.
 */
@UnstableApi
object ShareVideoExport {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    suspend fun mp4(
        ctx: Context,
        video: File,
        overlay: Bitmap,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.Main.immediate) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val dst = File(dir, "coinportracker-carteira.mp4")
        if (dst.exists()) dst.delete()
        val mediaItem = MediaItem.fromUri(Uri.fromFile(video))
        val overlayEffect = OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlay)))
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(
                Presentation.createForWidthAndHeight(WIDTH, HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                overlayEffect,
            )))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()
        val encoder = DefaultEncoderFactory.Builder(ctx.applicationContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VideoFormat.TARGET_BITRATE).build())
            .setEnableFallback(true)
            .build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(ctx.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, result: ExportResult) { if (cont.isActive) cont.resume(dst) }
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
                val st = transformer.getProgress(holder)
                if (st == Transformer.PROGRESS_STATE_AVAILABLE) onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                if (st != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    exec.execute { try { Thread.sleep(200) } catch (_: InterruptedException) { return@execute }; poll() }
                }
            }
            cont.invokeOnCancellation { transformer.cancel(); dst.delete(); exec.shutdownNow() }
            transformer.start(composition, dst.absolutePath)
            exec.execute { poll() }
        }
    }
}
