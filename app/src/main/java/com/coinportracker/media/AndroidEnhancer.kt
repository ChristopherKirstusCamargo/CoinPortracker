package com.coinportracker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object AndroidEnhancer {
    /** Prazo duro do modelo por mídia (v3.2: foto 20 s, GIF ~2 min); passou disso, o resultado vem do Lanczos. */
    val ML_DEADLINE_MS = MediaEnhancer.STILL_BUDGET_MS
    /** Último acelerador usado ("NNAPI" ou "CPU (XNNPACK)"), para diagnóstico na tela. */
    @Volatile var lastAccel: String? = null

    /**
     * Gera uma cópia ampliada de [bytes] (imagem ou GIF) em [dst]. Retorna o método usado, ou null se a mídia já
     * tem resolução suficiente ou não pôde ser processada.
     */
    fun enhance(ctx: Context, bytes: ByteArray, dst: File, targetW: Int, useMl: Boolean = true, onProgress: (Float) -> Unit = {}): EnhanceMethod? {
        val gif = GifDecoder.isGif(bytes)
        val engine = if (useMl && SrEngine.supported(ctx)) runCatching { SrEngine.open(ctx, if (gif) SrEngine.Model.ANIM else SrEngine.Model.PHOTO) }.getOrNull() else null
        try {
            lastAccel = engine?.accel
            val deadline = System.currentTimeMillis() + if (gif) MediaEnhancer.GIF_BUDGET_MS + 10_000 else MediaEnhancer.STILL_BUDGET_MS
            val sr: Sr4x? = engine?.let { e -> { px, w, h -> e.upscale4x(px, w, h, deadline) { d, t -> if (!gif) onProgress(0.1f + 0.85f * d / t) } } }
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            if (gif) {
                val out = MediaEnhancer.enhanceGif(bytes, targetW, sr, onProgress) ?: return null
                tmp.writeBytes(out.data); tmp.renameTo(dst); return out.method
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (Upscaler.targetSize(o.outWidth, o.outHeight, targetW) == null) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            onProgress(0.1f)
            val r = MediaEnhancer.enhanceStill(px, w, h, targetW, sr) ?: return null
            val out = Bitmap.createBitmap(r.data, r.width, r.height, Bitmap.Config.ARGB_8888)
            tmp.outputStream().use { out.compress(if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
            tmp.renameTo(dst)
            onProgress(1f)
            return r.method
        } finally { engine?.close() }
    }
}
