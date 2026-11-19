package com.coinportracker.media

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File

/** ROLL = v5.1.0 Rolagem de banner (até 1080p, vai para a biblioteca). */
enum class VideoDest { BANNER, SHARE, ROLL }

data class PendingVideo(val uri: Uri, val dest: VideoDest, val durationMs: Long, val select: Boolean = false)

enum class VideoJobState { IDLE, QUEUED, RUNNING, DONE, FAILED }

data class VideoJob(
    val state: VideoJobState = VideoJobState.IDLE,
    val progress: Float = 0f,
    val dest: VideoDest? = null,
    val path: String? = null,
    val error: String? = null,
)

/**
 * Escolha → recorte → transcode em segundo plano. Arquivos ficam no armazenamento
 * privado ([dir]); teto de tamanho em [VideoFormat].
 */
object VideoImport {
    private val _pending = MutableStateFlow<PendingVideo?>(null)
    val pending = _pending.asStateFlow()

    private val _sharePath = MutableStateFlow<String?>(null)
    val sharePath = _sharePath.asStateFlow()

    private val _events = MutableStateFlow<VideoJob>(VideoJob())
    val events = _events.asStateFlow()

    /** Vídeos da rolagem sendo preparados (contam no total exibido enquanto são preparados). */
    private val _rollPending = MutableStateFlow(0)
    val rollPending = _rollPending.asStateFlow()

    fun dir(ctx: Context) = File(ctx.filesDir, "video").apply { mkdirs() }
    fun bannerFile(ctx: Context) = File(dir(ctx), "banner.mp4")
    fun shareFile(ctx: Context) = File(dir(ctx), "share.mp4")
    fun inboxFile(ctx: Context) = File(ctx.cacheDir, "video-in.bin")

    suspend fun probeDuration(ctx: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { r.release() } }
    }

    suspend fun offer(ctx: Context, uri: Uri, dest: VideoDest, select: Boolean = false): PendingVideo? {
        val d = probeDuration(ctx, uri)
        if (d <= 0) return null
        return PendingVideo(uri, dest, d, select).also { _pending.value = it }
    }

    fun cancel() { _pending.value = null }

    fun confirm(ctx: Context, startMs: Long, endMs: Long) {
        val p = _pending.value ?: return
        _pending.value = null
        val window = VideoFormat.clampTrim(startMs, endMs, p.durationMs)
        val roll = p.dest == VideoDest.ROLL
        val tag = System.nanoTime().toString(36)
        val dst = when (p.dest) {
            VideoDest.BANNER -> bannerFile(ctx)
            VideoDest.SHARE -> shareFile(ctx)
            VideoDest.ROLL -> File(ctx.cacheDir, "roll-$tag.mp4")
        }
        // copia o conteúdo agora (a permissão da galeria pode expirar no worker).
        // v5.1.4: fora da thread principal, em blocos (sem carregar o vídeo na memória) e sem nunca derrubar o app
        val inbox = if (roll) File(ctx.cacheDir, "video-in-$tag.bin") else inboxFile(ctx)
        val app = ctx.applicationContext
        if (roll) _rollPending.value += 1
        _events.value = VideoJob(VideoJobState.QUEUED, dest = p.dest, path = dst.absolutePath)
        ioScope.launch {
            try {
                app.contentResolver.openInputStream(p.uri)!!.use { i -> inbox.outputStream().buffered(256 * 1024).use { o -> i.copyTo(o, 256 * 1024) } }
                if (!roll) enforceTotalCap(app, except = dst)
                enqueue(app, p, inbox, dst, window, roll)
            } catch (e: Throwable) {
                inbox.delete(); if (roll) rollDone()
                _events.value = VideoJob(VideoJobState.FAILED, dest = p.dest, error = "Não foi possível ler o vídeo.")
            }
        }
    }

    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun enqueue(ctx: Context, p: PendingVideo, inbox: File, dst: File, window: VideoFormat.TrimWindow, roll: Boolean) {
        val req = OneTimeWorkRequestBuilder<VideoTranscodeWorker>()
            .setInputData(workDataOf(
                "src" to inbox.absolutePath,
                "dst" to dst.absolutePath,
                "start" to window.startMs,
                "end" to window.endMs,
                "dest" to p.dest.name,
                "select" to p.select,
            ))
            .build()
        // rolagem: entra na fila (não cancela outro vídeo sendo preparado)
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, if (roll) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, req)
    }

    private fun workFlow(ctx: Context): kotlinx.coroutines.flow.Flow<List<androidx.work.WorkInfo>> =
        try { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(WORK) }
        catch (_: Exception) { kotlinx.coroutines.flow.flowOf(emptyList()) }

    fun status(ctx: Context) = combine(
        workFlow(ctx),
        events,
    ) { infos, ev ->
        val wi = infos.firstOrNull()
        when (wi?.state) {
            androidx.work.WorkInfo.State.RUNNING -> VideoJob(VideoJobState.RUNNING, wi.progress.getFloat("p", 0f), ev.dest, ev.path)
            androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> VideoJob(VideoJobState.QUEUED, 0f, ev.dest, ev.path)
            androidx.work.WorkInfo.State.SUCCEEDED -> ev.copy(state = VideoJobState.DONE, progress = 1f, path = wi.outputData.getString("path") ?: ev.path)
            androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> ev.copy(state = VideoJobState.FAILED, error = ev.error ?: "Não foi possível preparar o vídeo.")
            else -> ev
        }
    }

    internal fun rollDone() { _rollPending.value = (_rollPending.value - 1).coerceAtLeast(0) }

    internal fun onSuccess(dest: VideoDest, path: String) {
        if (dest == VideoDest.SHARE) _sharePath.value = path
        _events.value = VideoJob(VideoJobState.DONE, 1f, dest, path)
    }
    internal fun onFailure(dest: VideoDest?, msg: String) {
        _events.value = VideoJob(VideoJobState.FAILED, dest = dest, error = msg)
    }

    fun clearBanner(ctx: Context) { bannerFile(ctx).delete() }
    fun clearShare(ctx: Context) { shareFile(ctx).delete(); _sharePath.value = null }

    private fun enforceTotalCap(ctx: Context, except: File) {
        val files = dir(ctx).listFiles()?.filter { it.isFile && it.absolutePath != except.absolutePath }.orEmpty()
        val total = files.sumOf { it.length() }
        if (total > VideoFormat.MAX_TOTAL_BYTES) files.sortedBy { it.lastModified() }.forEach { it.delete() }
    }

    const val WORK = "video-transcode"
}

@androidx.media3.common.util.UnstableApi
class VideoTranscodeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val src = inputData.getString("src") ?: return Result.failure()
        val dstPath = inputData.getString("dst") ?: return Result.failure()
        val start = inputData.getLong("start", 0)
        val end = inputData.getLong("end", VideoFormat.MAX_DURATION_MS)
        val dest = inputData.getString("dest")?.let { runCatching { VideoDest.valueOf(it) }.getOrNull() } ?: VideoDest.BANNER
        val srcFile = File(src)
        val dst = File(dstPath)
        if (dest == VideoDest.ROLL) return try {
            val size = BannerLibrary.videoDisplaySize(srcFile.absolutePath)
            val target = size?.let { BannerLibrary.videoTarget(it.first, it.second) }
            val dur = runCatching { val r = android.media.MediaMetadataRetriever(); try { r.setDataSource(srcFile.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { r.release() } }.getOrDefault(0L)
            // v5.1.4: já tem até 1 min, o recorte é o vídeo inteiro e cabe no teto → usa o original como está (sem recodificar)
            val whole = dur in 1..(com.coinportracker.settings.RollRules.MAX_VIDEO_MS + 500) && start <= 250 && end >= dur - 250
            fun copyAsIs(): File { srcFile.copyTo(dst, overwrite = true); return dst }
            val out = if (whole && srcFile.length() <= com.coinportracker.settings.RollRules.MAX_VIDEO_BYTES) copyAsIs() else try {
                VideoTranscoder.transcodeWithinCap(applicationContext, Uri.fromFile(srcFile), dst, start, end,
                    presentation = target?.let { androidx.media3.effect.Presentation.createForWidthAndHeight(it.first, it.second, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT) },
                    rates = VideoFormat.ROLL_BITRATES, capBytes = com.coinportracker.settings.RollRules.MAX_VIDEO_BYTES,
                ) { p -> setProgressAsync(workDataOf("p" to p)) }
            } catch (t: Throwable) {
                // recodificação falhou: se o vídeo já tem até 1 min, guarda o original
                if (dur in 1..(com.coinportracker.settings.RollRules.MAX_VIDEO_MS + 500)) copyAsIs() else throw t
            }
            srcFile.delete()
            val msg = BannerLibrary.adoptVideo(applicationContext, out, inputData.getBoolean("select", false))
            VideoImport.rollDone()
            if (msg != null) VideoImport.onFailure(dest, msg) else VideoImport.onSuccess(dest, out.absolutePath)
            Result.success(workDataOf("dest" to dest.name))
        } catch (e: Throwable) {
            dst.delete(); srcFile.delete(); VideoImport.rollDone()
            VideoImport.onFailure(dest, e.message ?: "Não foi possível preparar o vídeo.")
            Result.failure()
        }
        return try {
            val out = VideoTranscoder.transcodeWithinCap(
                applicationContext,
                Uri.fromFile(srcFile),
                dst,
                start,
                end,
            ) { p -> setProgressAsync(workDataOf("p" to p)) }
            srcFile.delete()
            VideoImport.onSuccess(dest, out.absolutePath)
            Result.success(workDataOf("path" to out.absolutePath, "dest" to dest.name))
        } catch (e: Exception) {
            dst.delete()
            srcFile.delete()
            VideoImport.onFailure(dest, e.message ?: "Não foi possível preparar o vídeo.")
            Result.failure()
        }
    }
}
