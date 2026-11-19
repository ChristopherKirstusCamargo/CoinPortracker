package com.coinportracker.media

import android.app.ActivityManager
import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Super-resolução 4× no aparelho: Real-ESRGAN compacto (SRVGGNetCompact, pesos oficiais convertidos para
 * TFLite fp16 por tools/sr_convert.py), em blocos 256×256 ([SrTiler]).
 * v3.2: bloco 256 (antes 128) e margem medida (24 px fotos / 16 px GIF, diferença máx. 0,22/255 contra a imagem
 * inteira) → ~3,4× menos pixels processados nas fotos e ~1,6× nos GIFs. NNAPI (acelerador do aparelho) quando
 * disponível e validado contra a CPU no primeiro bloco; senão CPU com XNNPACK.
 * - [Model.PHOTO] realesr-general-x4v3 (fotos; 2,4 MB)
 * - [Model.ANIM] realesr-animevideov3 (GIFs; mais leve e estável entre quadros; 1,2 MB)
 */
class SrEngine private constructor(private val interp: Interpreter, pad: Int, val accel: String, private val delegate: AutoCloseable? = null) : AutoCloseable {
    enum class Model(val asset: String) { PHOTO("sr/realesr-general-x4v3.tflite"), ANIM("sr/realesr-animevideov3.tflite") }

    private val tiler = SrTiler(TILE, pad, 4)
    private val inBuf = ByteBuffer.allocateDirect(4 * TILE * TILE * 3).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(4 * TILE * 4 * TILE * 4 * 3).order(ByteOrder.nativeOrder())

    fun tiles(w: Int, h: Int) = tiler.tiles(w, h)

    fun upscale4x(px: IntArray, w: Int, h: Int, deadlineMs: Long, onTile: (Int, Int) -> Unit = { _, _ -> }): IntArray = tiler.run(px, w, h, deadlineMs, onTile) { inp, out -> infer(interp, inp, out) }

    private fun infer(i: Interpreter, inp: FloatArray, out: FloatArray) {
        inBuf.rewind(); inBuf.asFloatBuffer().put(inp)
        outBuf.rewind()
        i.run(inBuf, outBuf)
        outBuf.rewind(); outBuf.asFloatBuffer().get(out)
    }

    override fun close() { interp.close(); runCatching { delegate?.close() } }

    companion object {
        const val TILE = 256
        /** Margens medidas com tools/sr_bench: fotos 24 px → dif. máx. 0,22/255; GIF 16 px → 0,16/255. */
        const val PAD = 24
        const val PAD_ANIM = 16

        /** Aparelhos com pouca memória ficam no Lanczos. */
        fun supported(ctx: Context): Boolean {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return !am.isLowRamDevice && mi.totalMem >= 3L * 1024 * 1024 * 1024 && Runtime.getRuntime().maxMemory() >= 192L * 1024 * 1024
        }

        private fun map(ctx: Context, model: Model): ByteBuffer {
            val fd = ctx.assets.openFd(model.asset)
            val buf = FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
            fd.close(); return buf
        }

        fun open(ctx: Context, model: Model, threads: Int = 2, tryAccel: Boolean = true): SrEngine {
            val pad = if (model == Model.PHOTO) PAD else PAD_ANIM
            if (tryAccel) runCatching { openNnapi(ctx, model, pad, threads) }.getOrNull()?.let { return it }
            val opts = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
            return SrEngine(Interpreter(map(ctx, model), opts), pad, "CPU (XNNPACK)")
        }

        /**
         * NNAPI (Android 10+): só é usado se (1) um travamento anterior não deixou a marca de falha, (2) o primeiro
         * bloco sai igual ao da CPU (dif. máx. ≤ 6/255) e (3) é mais rápido que a CPU. Caso contrário, CPU.
         */
        private fun openNnapi(ctx: Context, model: Model, pad: Int, threads: Int): SrEngine? {
            if (android.os.Build.VERSION.SDK_INT < 29) return null
            val marker = java.io.File(ctx.noBackupFilesDir, "sr_nnapi_fail")
            val okFile = java.io.File(ctx.noBackupFilesDir, "sr_nnapi_ok_" + model.name)
            if (marker.exists()) return null
            marker.writeText("testing")  // se o driver derrubar o processo, a marca fica e o NNAPI não é tentado de novo
            var nn: org.tensorflow.lite.nnapi.NnApiDelegate? = null
            try {
                nn = org.tensorflow.lite.nnapi.NnApiDelegate(org.tensorflow.lite.nnapi.NnApiDelegate.Options()
                    .setAllowFp16(true).setExecutionPreference(org.tensorflow.lite.nnapi.NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED))
                val accel = Interpreter(map(ctx, model), Interpreter.Options().addDelegate(nn).setNumThreads(threads))
                if (!okFile.exists()) {
                    val cpu = Interpreter(map(ctx, model), Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
                    val probe = SrEngine(cpu, pad, "probe")
                    val inp = FloatArray(TILE * TILE * 3) { ((it * 2654435761L) ushr 24 and 0xFF).toFloat() / 255f }
                    val a = FloatArray(TILE * 4 * TILE * 4 * 3); val b = FloatArray(a.size)
                    var t0 = System.nanoTime(); probe.infer(cpu, inp, a); val tCpu = System.nanoTime() - t0
                    probe.infer(accel, inp, b) // aquecimento
                    t0 = System.nanoTime(); probe.infer(accel, inp, b); val tAcc = System.nanoTime() - t0
                    cpu.close()
                    var maxd = 0f; for (i in a.indices) { val d = kotlin.math.abs(a[i].coerceIn(0f, 1f) - b[i].coerceIn(0f, 1f)); if (d > maxd) maxd = d }
                    if (maxd > 6f / 255f || tAcc >= tCpu) { accel.close(); nn.close(); marker.writeText("rejected maxd=$maxd cpu=$tCpu acc=$tAcc"); return null }
                    okFile.writeText("maxd=$maxd cpu=$tCpu acc=$tAcc")
                }
                marker.delete()
                return SrEngine(accel, pad, "NNAPI", nn)
            } catch (t: Throwable) {
                runCatching { nn?.close() }; marker.writeText("error ${t.javaClass.simpleName}"); return null
            }
        }
    }
}
