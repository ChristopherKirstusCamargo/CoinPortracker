package com.coinportracker.share

import java.io.OutputStream

/**
 * Codificador GIF89a animado, sem dependências (para o cartão de compartilhar com fundo em GIF).
 * Paleta global fixa de 256 cores (cubo 6×6×6 + 40 tons de cinza) com pontilhado ordenado 4×4:
 * rápido e bom para o visual monocromático do app. Compressão LZW padrão (até 12 bits).
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, private val loop: Boolean = true) {
    private var started = false
    private val indices = ByteArray(width * height)

    fun addFrame(argb: IntArray, delayMs: Int) {
        require(argb.size == width * height)
        if (!started) header()
        quantize(argb)
        // Graphics Control Extension: sem transparência, descarte "não descartar"
        val cs = (delayMs / 10).coerceIn(2, 65535)
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04, (cs and 0xFF).toByte(), (cs shr 8).toByte(), 0, 0))
        // descritor da imagem (quadro inteiro, sem paleta local)
        out.write(0x2C); short(0); short(0); short(width); short(height); out.write(0)
        lzw(indices)
    }

    /**
     * Quadro com paleta local própria (corte pela mediana, 256 cores, sem pontilhado): usado para GIFs
     * ampliados, em que a paleta fixa perderia as cores originais.
     */
    fun addFrameLocal(argb: IntArray, delayMs: Int) {
        require(argb.size == width * height)
        if (!started) header()
        val pal = Palette.medianCut(argb, 256)
        val lut = Palette.lookup(pal)
        for (i in argb.indices) { val c = argb[i]; indices[i] = lut[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)] }
        val cs = (delayMs / 10).coerceIn(2, 65535)
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04, (cs and 0xFF).toByte(), (cs shr 8).toByte(), 0, 0))
        out.write(0x2C); short(0); short(0); short(width); short(height); out.write(0x87)
        val bytes = ByteArray(768); for (k in pal.indices) { bytes[k * 3] = (pal[k] shr 16).toByte(); bytes[k * 3 + 1] = (pal[k] shr 8).toByte(); bytes[k * 3 + 2] = pal[k].toByte() }
        out.write(bytes)
        lzw(indices)
    }

    fun finish() { if (!started) header(); out.write(0x3B); out.flush() }

    private fun header() {
        started = true
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        out.write(0xF7) // paleta global, 8 bits de cor, 256 entradas
        out.write(0); out.write(0)
        out.write(PALETTE)
        if (loop) out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun quantize(argb: IntArray) {
        var i = 0
        for (y in 0 until height) for (x in 0 until width) {
            indices[i] = indexOf(argb[i], x, y).toByte(); i++
        }
    }

    // LZW com tabela por "geração" (zera em O(1) a cada código de limpeza)
    private val table = IntArray(1 shl 20)
    private val gen = IntArray(1 shl 20)
    private var curGen = 1

    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        var codeSize = minCode + 1
        var next = eoi + 1
        val w = BitWriter(out)
        w.write(clear, codeSize)
        curGen++
        var prefix = data[0].toInt() and 0xFF
        for (k in 1 until data.size) {
            val c = data[k].toInt() and 0xFF
            val key = (prefix shl 8) or c
            if (gen[key] == curGen) { prefix = table[key]; continue }
            w.write(prefix, codeSize)
            if (next < 4096) {
                table[key] = next; gen[key] = curGen
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                next++
            } else {
                w.write(clear, codeSize)
                curGen++; codeSize = minCode + 1; next = eoi + 1
            }
            prefix = c
        }
        w.write(prefix, codeSize)
        w.write(eoi, codeSize)
        w.flush()
        out.write(0)
    }

    private class BitWriter(private val out: OutputStream) {
        private val block = ByteArray(255); private var n = 0
        private var acc = 0; private var bits = 0
        fun write(code: Int, size: Int) {
            acc = acc or (code shl bits); bits += size
            while (bits >= 8) { byte(acc and 0xFF); acc = acc ushr 8; bits -= 8 }
        }
        private fun byte(b: Int) { block[n++] = b.toByte(); if (n == 255) dump() }
        private fun dump() { if (n > 0) { out.write(n); out.write(block, 0, n); n = 0 } }
        fun flush() { if (bits > 0) { byte(acc and 0xFF); acc = 0; bits = 0 }; dump() }
    }

    companion object {
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val PALETTE: ByteArray = ByteArray(768).also { p ->
            var i = 0
            for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) { p[i++] = (r * 51).toByte(); p[i++] = (g * 51).toByte(); p[i++] = (b * 51).toByte() }
            for (k in 0 until 40) { val v = (k * 255 / 39).toByte(); p[i++] = v; p[i++] = v; p[i++] = v }
        }

        /** Índice na paleta fixa com pontilhado ordenado (x, y = posição do pixel). */
        fun indexOf(c: Int, x: Int, y: Int): Int {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val t = BAYER[(y and 3) * 4 + (x and 3)]
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx - mn < 14) {
                val l = (r * 299 + g * 587 + b * 114) / 1000
                val v = (l * 39 + (t - 7) * 4) / 255.0
                return 216 + v.toInt().coerceIn(0, 39).let { if (v - it >= 0.5 && it < 39) it + 1 else it }
            }
            val d = (t - 7.5f) * 3.2f
            fun q(v: Int) = ((v + d) / 51f + 0.5f).toInt().coerceIn(0, 5)
            return q(r) * 36 + q(g) * 6 + q(b)
        }
    }
}

/** Quantização por corte na mediana sobre um histograma de 15 bits. */
object Palette {
    fun medianCut(argb: IntArray, n: Int): IntArray {
        val hist = IntArray(32768)
        for (c in argb) hist[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)]++
        val colors = (0 until 32768).filter { hist[it] > 0 }.toIntArray()
        if (colors.size <= n) return IntArray(n) { i -> if (i < colors.size) expand(colors[i]) else 0 }
        val boxes = ArrayList<IntArray>().apply { add(colors) }
        while (boxes.size < n) {
            var bi = -1; var bestRange = 0; var bestCh = 0
            for ((i, b) in boxes.withIndex()) {
                if (b.size < 2) continue
                for (ch in 0..2) {
                    var lo = 31; var hi = 0
                    for (c in b) { val v = (c shr (10 - ch * 5)) and 31; if (v < lo) lo = v; if (v > hi) hi = v }
                    val r = (hi - lo) * kotlin.math.sqrt(b.sumOf { hist[it].toLong() }.toDouble()).toInt().coerceAtLeast(1)
                    if (r > bestRange) { bestRange = r; bi = i; bestCh = ch }
                }
            }
            if (bi < 0) break
            val b = boxes.removeAt(bi)
            val sh = 10 - bestCh * 5
            val sorted = b.sortedBy { (it shr sh) and 31 }
            val total = sorted.sumOf { hist[it].toLong() }
            var acc = 0L; var cut = 1
            for ((i, c) in sorted.withIndex()) { acc += hist[c]; if (acc * 2 >= total) { cut = (i + 1).coerceIn(1, sorted.size - 1); break } }
            boxes += sorted.subList(0, cut).toIntArray(); boxes += sorted.subList(cut, sorted.size).toIntArray()
        }
        return IntArray(n) { i ->
            val b = boxes.getOrNull(i) ?: return@IntArray 0
            var r = 0L; var g = 0L; var bl = 0L; var w = 0L
            for (c in b) { val h = hist[c].toLong(); r += ((c shr 10) and 31) * h; g += ((c shr 5) and 31) * h; bl += (c and 31) * h; w += h }
            if (w == 0L) 0 else (avg(r, w) shl 16) or (avg(g, w) shl 8) or avg(bl, w)
        }
    }

    private fun avg(v: Long, w: Long) = ((v * 255) / (w * 31)).toInt().coerceIn(0, 255)
    private fun e5(v: Int) = v * 255 / 31
    private fun expand(c: Int): Int = (e5((c shr 10) and 31) shl 16) or (e5((c shr 5) and 31) shl 8) or e5(c and 31)

    /** Tabela 15 bits → índice da cor mais próxima. */
    fun lookup(pal: IntArray): ByteArray {
        val out = ByteArray(32768)
        for (k in 0 until 32768) {
            val r = ((k shr 10) and 31) * 255 / 31; val g = ((k shr 5) and 31) * 255 / 31; val b = (k and 31) * 255 / 31
            var best = 0; var bd = Int.MAX_VALUE
            for (i in pal.indices) {
                val p = pal[i]; val dr = r - ((p shr 16) and 0xFF); val dg = g - ((p shr 8) and 0xFF); val db = b - (p and 0xFF)
                val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
                if (d < bd) { bd = d; best = i }
            }
            out[k] = best.toByte()
        }
        return out
    }
}
