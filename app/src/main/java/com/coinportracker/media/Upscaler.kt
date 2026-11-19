package com.coinportracker.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reamostragem Lanczos-3 separável (ampliação e redução, com núcleo alargado na redução para não serrilhar)
 * e nitidez adaptativa. É o caminho de reserva da ampliação: usado quando o modelo de super-resolução
 * ([SrTiler]/SrEngine) não pode rodar (pouca RAM, GIF longo demais) ou falha. Também reduz a saída 4× do modelo
 * para o tamanho final.
 */
object Upscaler {
    const val MAX_FACTOR = 3.0

    /** Tamanho de saída para caber em [targetW] px de largura, limitado a [maxFactor]× e [maxPixels]. Null = não vale ampliar. */
    fun targetSize(sw: Int, sh: Int, targetW: Int, maxPixels: Int = 2_400_000, maxFactor: Double = MAX_FACTOR): Pair<Int, Int>? {
        if (sw <= 0 || sh <= 0) return null
        var f = (targetW.toDouble() / sw).coerceAtMost(maxFactor)
        val px = sw.toDouble() * sh * f * f
        if (px > maxPixels) f = kotlin.math.sqrt(maxPixels / (sw.toDouble() * sh))
        if (f < 1.2) return null
        return (sw * f).roundToInt() to (sh * f).roundToInt()
    }

    /** Ampliação clássica (reserva): Lanczos-3 + máscara de nitidez. */
    fun upscale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int, sharpen: Float = 0.6f): IntArray {
        val out = resample(src, sw, sh, dw, dh)
        if (sharpen > 0f) unsharp(out, dw, dh, sharpen, threshold = 3)
        return out
    }

    /** Lanczos-3 em qualquer direção (ARGB, alfa pré-multiplicado durante o filtro). */
    fun resample(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val tmp = resizeH(src, sw, sh, dw)
        return resizeV(tmp, dw, sh, dh)
    }

    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= 3) return 0.0
        val px = PI * x
        return 3 * sin(px) * sin(px / 3) / (px * px)
    }

    private class Kernel(val taps: Int, val start: IntArray, val weights: Array<DoubleArray>)

    private fun kernel(srcLen: Int, dstLen: Int): Kernel {
        val scale = srcLen.toDouble() / dstLen
        val stretch = maxOf(1.0, scale)            // redução: alarga o núcleo (filtro passa-baixa)
        val support = 3.0 * stretch
        val taps = ceil(support * 2).toInt() + 1
        val starts = IntArray(dstLen); val ws = Array(dstLen) { DoubleArray(taps) }
        for (i in 0 until dstLen) {
            val center = (i + 0.5) * scale - 0.5
            val s = floor(center - support).toInt() + 1
            val w = ws[i]
            for (k in 0 until taps) w[k] = lanczos((center - (s + k)) / stretch)
            val sum = w.sum(); if (sum != 0.0) for (k in 0 until taps) w[k] /= sum
            starts[i] = s
        }
        return Kernel(taps, starts, ws)
    }

    private fun clamp8(v: Double) = if (v <= 0) 0 else if (v >= 255) 255 else (v + 0.5).toInt()

    private fun resizeH(src: IntArray, sw: Int, sh: Int, dw: Int): IntArray {
        if (sw == dw) return src.copyOf()
        val k = kernel(sw, dw); val out = IntArray(dw * sh)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                val s = k.start[x]; val wt = k.weights[x]
                for (t in 0 until k.taps) {
                    val q = wt[t]; if (q == 0.0) continue
                    val c = src[row + (s + t).coerceIn(0, sw - 1)]
                    val ca = (c ushr 24) / 255.0
                    a += ca * q; r += ((c shr 16) and 0xFF) * ca * q; g += ((c shr 8) and 0xFF) * ca * q; b += (c and 0xFF) * ca * q
                }
                out[y * dw + x] = pack(a, r, g, b)
            }
        }
        return out
    }

    private fun resizeV(src: IntArray, w: Int, sh: Int, dh: Int): IntArray {
        if (sh == dh) return src.copyOf()
        val k = kernel(sh, dh); val out = IntArray(w * dh)
        for (y in 0 until dh) {
            val s = k.start[y]; val wt = k.weights[y]
            for (x in 0 until w) {
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                for (t in 0 until k.taps) {
                    val q = wt[t]; if (q == 0.0) continue
                    val c = src[(s + t).coerceIn(0, sh - 1) * w + x]
                    val ca = (c ushr 24) / 255.0
                    a += ca * q; r += ((c shr 16) and 0xFF) * ca * q; g += ((c shr 8) and 0xFF) * ca * q; b += (c and 0xFF) * ca * q
                }
                out[y * w + x] = pack(a, r, g, b)
            }
        }
        return out
    }

    /** Recebe cores pré-multiplicadas e devolve ARGB normal. */
    private fun pack(a: Double, r: Double, g: Double, b: Double): Int {
        val aa = a.coerceIn(0.0, 1.0)
        if (aa < 1e-6) return 0
        return (clamp8(aa * 255) shl 24) or (clamp8(r / aa) shl 16) or (clamp8(g / aa) shl 8) or clamp8(b / aa)
    }

    /** Máscara de desfoque na luminância (desfoque 3×3), aplicada só onde a diferença passa do limiar. */
    fun unsharp(px: IntArray, w: Int, h: Int, amount: Float, threshold: Int) {
        if (w < 3 || h < 3) return
        val lum = IntArray(w * h) { i -> val c = px[i]; (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val blur = (lum[i - w - 1] + 2 * lum[i - w] + lum[i - w + 1] + 2 * lum[i - 1] + 4 * lum[i] + 2 * lum[i + 1] + lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]) shr 4
            val d = lum[i] - blur
            if (abs(d) <= threshold) continue
            val add = (d * amount).roundToInt()
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or ((((c shr 16) and 0xFF) + add).coerceIn(0, 255) shl 16) or ((((c shr 8) and 0xFF) + add).coerceIn(0, 255) shl 8) or (((c and 0xFF) + add).coerceIn(0, 255))
        }
    }
}
