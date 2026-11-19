package com.coinportracker.media

/** Quadros já compostos (ARGB, tela inteira) e atraso de cada um em ms. */
class GifFrames(val width: Int, val height: Int, val frames: List<IntArray>, val delaysMs: List<Int>)

/**
 * Decodificador GIF87a/89a sem dependências: tabelas global/local, transparência, entrelaçamento e
 * descarte 0–3. Usado para reprocessar GIFs quadro a quadro.
 */
object GifDecoder {
    fun isGif(b: ByteArray) = b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    /** v6.0: teto de pixels somados de todos os quadros (cada quadro fica inteiro na memória, 4 bytes por pixel). */
    class TooLarge : IllegalArgumentException("GIF grande demais para reprocessar com segurança")

    fun decode(data: ByteArray, maxFrames: Int = 300, maxTotalPixels: Long = Long.MAX_VALUE): GifFrames {
        var p = 6
        fun u8() = data[p++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        val w = u16(); val h = u16()
        require(w in 1..4096 && h in 1..4096) { "GIF com tamanho inválido" }
        val flags = u8(); val bgIndex = u8(); u8()
        val global = if (flags and 0x80 != 0) readTable(data, p, 2 shl (flags and 7)).also { p += 3 * (2 shl (flags and 7)) } else null
        val canvas = IntArray(w * h)
        val frames = ArrayList<IntArray>(); val delays = ArrayList<Int>()
        var delay = 100; var transparent = -1; var disposal = 0
        while (p < data.size && frames.size < maxFrames) {
            when (u8()) {
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        u8(); val f = u8(); delay = u16() * 10; val t = u8(); u8()
                        disposal = (f shr 2) and 7; transparent = if (f and 1 != 0) t else -1
                    } else { while (true) { val n = u8(); if (n == 0) break; p += n } }
                }
                0x2C -> {
                    val ix = u16(); val iy = u16(); val iw = u16(); val ih = u16(); val f = u8()
                    val local = if (f and 0x80 != 0) readTable(data, p, 2 shl (f and 7)).also { p += 3 * (2 shl (f and 7)) } else null
                    val interlaced = f and 0x40 != 0
                    val minCode = u8()
                    val sb = java.io.ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; sb.write(data, p, n); p += n }
                    val idx = lzw(sb.toByteArray(), minCode, iw * ih)
                    val pal = local ?: global ?: IntArray(256) { 0xFF000000.toInt() or (it * 0x010101) }
                    val saved = if (disposal == 3) canvas.copyOf() else null
                    val rows = if (interlaced) interlaceOrder(ih) else IntArray(ih) { it }
                    for (r in 0 until ih) {
                        val y = iy + rows[r]; if (y !in 0 until h) continue
                        for (c in 0 until iw) {
                            val x = ix + c; if (x !in 0 until w) continue
                            val ci = idx[r * iw + c].toInt() and 0xFF
                            if (ci == transparent || ci >= pal.size) continue
                            canvas[y * w + x] = pal[ci]
                        }
                    }
                    if ((frames.size + 2).toLong() * w * h > maxTotalPixels) throw TooLarge()
                    frames += canvas.copyOf(); delays += (if (delay < 20) 100 else delay)
                    when (disposal) {
                        2 -> for (r in 0 until ih) { val y = iy + r; if (y !in 0 until h) continue; for (c in 0 until iw) { val x = ix + c; if (x in 0 until w) canvas[y * w + x] = 0 } }
                        3 -> saved?.copyInto(canvas)
                    }
                    transparent = -1; disposal = 0; delay = 100
                }
                0x3B -> break
                else -> break
            }
        }
        require(frames.isNotEmpty()) { "GIF sem quadros" }
        return GifFrames(w, h, frames, delays)
    }

    private fun readTable(d: ByteArray, at: Int, n: Int) = IntArray(n) { i ->
        val o = at + i * 3
        if (o + 2 >= d.size) 0xFF000000.toInt() else 0xFF000000.toInt() or ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)
    }

    private fun interlaceOrder(h: Int): IntArray {
        val out = IntArray(h); var i = 0
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) { var y = start; while (y < h) { out[i++] = y; y += step } }
        return out
    }

    fun lzw(data: ByteArray, minCode: Int, n: Int): ByteArray {
        val out = ByteArray(n)
        val clear = 1 shl minCode; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = ByteArray(4096); val len = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); len[i] = 1; prefix[i] = -1 }
        var size = minCode + 1; var next = eoi + 1; var prev = -1
        var acc = 0; var bits = 0; var pos = 0; var o = 0
        val stack = ByteArray(4097)
        while (o < n) {
            while (bits < size) { if (pos >= data.size) return out; acc = acc or ((data[pos++].toInt() and 0xFF) shl bits); bits += 8 }
            val code = acc and ((1 shl size) - 1); acc = acc ushr size; bits -= size
            if (code == clear) { size = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) break
            var cur = code
            var first: Byte
            if (code >= next) { if (prev < 0) break; cur = prev }
            var sp = 0; var c = cur
            while (c >= 0 && sp < 4096) { stack[sp++] = suffix[c]; c = if (c < clear) -1 else prefix[c] }
            first = stack[sp - 1]
            while (sp > 0 && o < n) out[o++] = stack[--sp]
            if (code >= next && o < n) out[o++] = first
            if (prev >= 0 && next < 4096) { prefix[next] = prev; suffix[next] = first; len[next] = len[prev] + 1; next++; if (next == (1 shl size) && size < 12) size++ }
            prev = code
        }
        return out
    }
}
