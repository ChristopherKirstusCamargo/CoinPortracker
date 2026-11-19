package com.coinportracker.media

import android.content.Context
import android.os.Process
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cópias em alta resolução das mídias do usuário (banner e fundos do cartão), geradas em segundo plano por
 * [EnhanceWorker] quando "Melhorar resolução" está ligado. A tela continua usando o original até a cópia ficar
 * pronta, e troca sozinha (via [version]).
 */
object MediaHd {
    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()

    private fun key(source: String): String {
        val s = source.substringBefore("?v=")
        return if (s.startsWith("asset:")) "asset_" + s.removePrefix("asset:").replace('/', '_') else File(s).name
    }

    fun hdFile(ctx: Context, source: String) = File(File(ctx.filesDir, "hd").apply { mkdirs() }, key(source) + ".hd")

    private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Resultado já calculado de [resolve] (sem I/O), para a composição não tocar no disco. */
    fun cachedResolve(source: String, enabled: Boolean, v: Any?): String? =
        if (!enabled || source.isBlank()) source else resolved["$v|$source"]

    /** [resolve] fora da thread principal, memorizado por versão do HD. */
    suspend fun resolveAsync(ctx: Context, source: String, enabled: Boolean, v: Any?): String =
        cachedResolve(source, enabled, v) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolve(ctx, source, enabled) }.also { resolved["$v|$source"] = it }

    /** Origem a exibir: a cópia HD se estiver pronta e for mais nova que o original; senão o próprio original. */
    fun resolve(ctx: Context, source: String, enabled: Boolean): String {
        if (!enabled || source.isBlank()) return source
        val hd = hdFile(ctx, source)
        if (!hd.exists() || hd.length() == 0L) return source
        val s = source.substringBefore("?v=")
        if (!s.startsWith("asset:") && File(s).lastModified() > hd.lastModified()) return source
        return hd.absolutePath + "?v=" + hd.lastModified()
    }

    fun readBytes(ctx: Context, source: String): ByteArray? = runCatching {
        if (source.startsWith("asset:")) ctx.assets.open(source.removePrefix("asset:")).use { it.readBytes() }
        else File(source.substringBefore("?v=")).takeIf { it.exists() }?.readBytes()
    }.getOrNull()

    fun workName(source: String) = "hd:" + key(source)

    fun schedule(ctx: Context, source: String) {
        if (source.isBlank() || VideoFormat.isVideoPath(source)) return
        if (resolve(ctx, source, true) != source) return   // já pronta
        val req = OneTimeWorkRequestBuilder<EnhanceWorker>()
            .setInputData(workDataOf("src" to source.substringBefore("?v=")))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 2, java.util.concurrent.TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(workName(source), ExistingWorkPolicy.REPLACE, req)
    }

    /** Resultado da última melhoria (para a tela mostrar "Pronto" e o antes/depois mesmo depois do trabalho sumir). */
    fun metaFile(ctx: Context, source: String) = File(hdFile(ctx, source).path + ".meta")

    /** Estado atual da melhoria de [source] (fila, progresso, pausa, pronto ou original). */
    fun status(ctx: Context, source: String, enabled: Boolean): kotlinx.coroutines.flow.Flow<HdStatus> {
        if (!enabled || source.isBlank() || VideoFormat.isVideoPath(source)) return kotlinx.coroutines.flow.flowOf(HdStatus(HdState.OFF))
        return kotlinx.coroutines.flow.combine(WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(workName(source)), version) { infos, _ ->
            val wi = infos.firstOrNull()
            val now = System.currentTimeMillis()
            val meta = runCatching { metaFile(ctx, source).readText() }.getOrNull()?.let { parseMeta(it) }
            val ready = resolve(ctx, source, true) != source
            when {
                wi?.state == androidx.work.WorkInfo.State.RUNNING -> {
                    val p = wi.progress.getFloat("p", 0f); val t0 = wi.progress.getLong("t0", now)
                    if (wi.progress.getString("state") == "PAUSED") HdStatus(HdState.PAUSED, reason = wi.progress.getString("reason"))
                    else HdStatus(HdState.RUNNING, p, now - t0, HdEta.eta(p, now - t0), accel = wi.progress.getString("accel")?.ifBlank { null }, startedAt = t0)
                }
                wi?.state == androidx.work.WorkInfo.State.ENQUEUED || wi?.state == androidx.work.WorkInfo.State.BLOCKED ->
                    if (wi.runAttemptCount > 0) HdStatus(HdState.PAUSED, reason = "Aguardando bateria/temperatura") else HdStatus(HdState.QUEUED)
                ready -> HdStatus(HdState.DONE, 1f, meta?.ms ?: 0, method = meta?.method, accel = meta?.accel)
                else -> HdStatus(HdState.ORIGINAL, method = meta?.method)
            }
        }
    }

    internal data class Meta(val method: String, val ms: Long, val accel: String?)
    internal fun parseMeta(s: String): Meta? = runCatching {
        val m = s.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        Meta(m["method"] ?: "NONE", m["ms"]?.toLong() ?: 0, m["accel"]?.takeIf { it.isNotBlank() })
    }.getOrNull()

    /** Apaga cópias HD (ao desligar a opção ou trocar a mídia). */
    fun clear(ctx: Context) { File(ctx.filesDir, "hd").listFiles()?.forEach { it.delete() }; resolved.clear(); _version.value++ }

    const val TAG = "hd"

    /** v5.1.3: "Melhorar resolução" desligado → cancela o que estiver na fila e apaga todas as cópias HD (originais intactos). */
    fun disableAll(ctx: Context) {
        runCatching { WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG) }
        if (File(ctx.filesDir, "hd").listFiles()?.isNotEmpty() == true) clear(ctx)
    }

    /** v5.1.3: mídia removida da Rolagem → some também a cópia HD dela. */
    fun forget(ctx: Context, source: String) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(source)) }
        hdFile(ctx, source).delete(); metaFile(ctx, source).delete(); resolved.clear(); _version.value++
    }

    internal fun bump() { _version.value++ }
}

enum class HdState { OFF, QUEUED, RUNNING, PAUSED, DONE, ORIGINAL }

data class HdStatus(val state: HdState, val progress: Float = 0f, val elapsedMs: Long = 0, val etaMs: Long? = null,
                    val method: String? = null, val accel: String? = null, val reason: String? = null, val startedAt: Long = 0)

object HdEta {
    /** Tempo restante estimado pela taxa até agora (só depois de 5% para não oscilar). */
    fun eta(p: Float, elapsedMs: Long): Long? = if (p < 0.05f || elapsedMs <= 0) null else ((elapsedMs / p) * (1f - p)).toLong().coerceAtLeast(0)
}

class EnhanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    /** Pausa: economia de bateria ligada ou aparelho quente (THERMAL_STATUS_SEVERE+). Bateria baixa é restrição do WorkManager. */
    private fun pauseReason(): String? {
        val pm = applicationContext.getSystemService(android.os.PowerManager::class.java) ?: return null
        if (pm.isPowerSaveMode) return "Economia de bateria ligada"
        if (android.os.Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) return "Celular quente"
        return null
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val src = inputData.getString("src") ?: return@withContext Result.failure()
        pauseReason()?.let { setProgress(workDataOf("state" to "PAUSED", "reason" to it)); return@withContext Result.retry() }
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val bytes = MediaHd.readBytes(applicationContext, src) ?: return@withContext Result.failure()
        val dst = MediaHd.hdFile(applicationContext, src)
        // v3.2: só até a largura da tela (o 2× máximo fica no MediaEnhancer)
        val targetW = applicationContext.resources.displayMetrics.widthPixels.coerceIn(720, 1440)
        val t0 = System.currentTimeMillis()
        setProgress(workDataOf("state" to "RUNNING", "p" to 0f, "t0" to t0))
        var lastSent = 0L
        val progress: (Float) -> Unit = { p ->
            val now = System.currentTimeMillis()
            if (now - lastSent > 300 || p >= 1f) { lastSent = now; setProgressAsync(workDataOf("state" to "RUNNING", "p" to p, "t0" to t0, "accel" to (AndroidEnhancer.lastAccel ?: ""))) }
        }
        val method = runCatching { AndroidEnhancer.enhance(applicationContext, bytes, dst, targetW, onProgress = progress) }
            .getOrElse { runCatching { AndroidEnhancer.enhance(applicationContext, bytes, dst, targetW, useMl = false, onProgress = progress) }.getOrNull() }
        val ms = System.currentTimeMillis() - t0
        runCatching { MediaHd.metaFile(applicationContext, src).writeText("method=${method?.name ?: "NONE"};ms=$ms;accel=${AndroidEnhancer.lastAccel ?: ""}") }
        MediaHd.bump()
        Result.success(workDataOf("method" to (method?.name ?: "NONE"), "ms" to ms))
    }
}
