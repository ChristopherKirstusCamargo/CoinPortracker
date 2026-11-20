package com.coinportracker.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Valida o GIF com um decodificador LZW independente (o classpath de teste Android não tem javax.imageio). */
class GifEncoderTest {
    private class Decoded(val w: Int, val h: Int, val frames: List<ByteArray>, val delays: List<Int>, val loops: Boolean)

    private fun decode(b: ByteArray): Decoded {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        require(String(b, 0, 6) == "GIF89a"); p = 6
        val w = u16(); val h = u16(); val flags = u8(); u8(); u8()
        if (flags and 0x80 != 0) p += 3 * (1 shl ((flags and 7) + 1))
        val frames = ArrayList<ByteArray>(); val delays = ArrayList<Int>(); var loops = false
        while (true) {
            when (u8()) {
                0x3B -> return Decoded(w, h, frames, delays, loops)
                0x21 -> {
                    val label = u8()
                    var first = true
                    while (true) {
                        val n = u8(); if (n == 0) break
                        if (label == 0xF9 && first) delays += ((b[p + 1].toInt() and 0xFF) or ((b[p + 2].toInt() and 0xFF) shl 8)) * 10
                        if (label == 0xFF && first && String(b, p, 8) == "NETSCAPE") loops = true
                        first = false; p += n
                    }
                }
                0x2C -> {
                    u16(); u16(); val fw = u16(); val fh = u16(); val f = u8(); require(f and 0x80 == 0)
                    val min = u8()
                    val data = ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; data.write(b, p, n); p += n }
                    frames += lzwDecode(data.toByteArray(), min, fw * fh)
                }
                else -> error("bloco inválido em $p")
            }
        }
    }

    private fun lzwDecode(d: ByteArray, min: Int, n: Int): ByteArray {
        val clear = 1 shl min; val eoi = clear + 1
        val out = ByteArray(n); var o = 0
        val prefix = IntArray(4096); val suffix = ByteArray(4096); val len = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); len[i] = 1; prefix[i] = -1 }
        var size = min + 1; var next = eoi + 1; var old = -1
        var acc = 0; var bits = 0; var i = 0
        val tmp = ByteArray(4096)
        fun emit(code: Int): Byte { var c = code; var k = len[c]; val l = k; while (c >= 0 && k > 0) { tmp[--k] = suffix[c]; c = prefix[c] }; System.arraycopy(tmp, 0, out, o, l); o += l; return tmp[0] }
        while (true) {
            while (bits < size) { acc = acc or ((d[i++].toInt() and 0xFF) shl bits); bits += 8 }
            val code = acc and ((1 shl size) - 1); acc = acc ushr size; bits -= size
            if (code == clear) { size = min + 1; next = eoi + 1; old = -1; continue }
            if (code == eoi) break
            if (old == -1) { emit(code); old = code; continue }
            val firstByte: Byte
            if (code < next) { firstByte = emit(code) }
            else { // KwKwK
                var c = old; while (prefix[c] >= 0) c = prefix[c]
                firstByte = suffix[c]
                prefix[next] = old; suffix[next] = firstByte; len[next] = len[old] + 1
                emit(next); next++; old = code
                if (next == (1 shl size) && size < 12) size++
                continue
            }
            if (next < 4096) { prefix[next] = old; suffix[next] = firstByte; len[next] = len[old] + 1; next++ }
            if (next == (1 shl size) && size < 12) size++
            old = code
        }
        require(o == n) { "esperava $n pixels, veio $o" }
        return out
    }

    private fun expected(px: IntArray, w: Int) = ByteArray(px.size) { i -> GifEncoder.indexOf(px[i], i % w, i / w).toByte() }

    @Test fun producesValidAnimatedGif() {
        val w = 120; val h = 90
        val all = (0 until 5).map { f -> IntArray(w * h) { i -> val x = i % w; val y = i / w
            val g = ((x + f * 10) * 255 / w).coerceIn(0, 255); (0xFF shl 24) or (g shl 16) or (g shl 8) or ((y * 255 / h) and 0xFF) } }
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, w, h)
        all.forEach { enc.addFrame(it, 80) }
        enc.finish()
        val d = decode(bos.toByteArray())
        assertEquals(w, d.w); assertEquals(h, d.h); assertEquals(5, d.frames.size); assertEquals(true, d.loops)
        assertEquals(listOf(80, 80, 80, 80, 80), d.delays)
        all.forEachIndexed { k, px -> assertArrayEquals(expected(px, w), d.frames[k]) }
    }

    @Test fun noisyLargeFrameExercisesCodeTableResets() {
        val w = 400; val h = 300
        val rnd = java.util.Random(1)
        val px = IntArray(w * h) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val bos = ByteArrayOutputStream()
        GifEncoder(bos, w, h).apply { addFrame(px, 100); finish() }
        assertArrayEquals(expected(px, w), decode(bos.toByteArray()).frames[0])
    }

    @Test fun grayStaysClose() {
        val idx = GifEncoder.indexOf(0xFF808080.toInt(), 1, 1)
        val v = GifEncoder.PALETTE[idx * 3].toInt() and 0xFF
        assertEquals(128.0, v.toDouble(), 20.0)
    }
}
