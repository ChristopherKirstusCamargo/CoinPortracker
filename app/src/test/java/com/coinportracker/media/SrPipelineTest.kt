package com.coinportracker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SrPipelineTest {
    private fun img(w: Int, h: Int) = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x xor y) and 0xFF) }

    /** Modelo falso: vizinho mais próximo 4×, aplicado ao bloco (tile²×3 → (4·tile)²×3). */
    private fun fakeNearest(tile: Int): (FloatArray, FloatArray) -> Unit = { inp, out ->
        val t4 = tile * 4
        for (y in 0 until t4) for (x in 0 until t4) for (c in 0 until 3) out[(y * t4 + x) * 3 + c] = inp[((y / 4) * tile + x / 4) * 3 + c]
    }

    @Test fun tilerStitchesWithoutSeams() {
        val w = 300; val h = 170; val px = img(w, h)
        val out = SrTiler(tile = 64, pad = 6).run(px, w, h, infer = fakeNearest(64))
        for (y in 0 until h * 4) for (x in 0 until w * 4) assertEquals("($x,$y)", px[(y / 4) * w + x / 4], out[y * w * 4 + x])
    }

    @Test fun tilerHandlesImageSmallerThanTile() {
        val px = img(20, 9)
        val out = SrTiler(tile = 64, pad = 6).run(px, 20, 9, infer = fakeNearest(64))
        assertEquals(80 * 36, out.size); assertEquals(px[8 * 20 + 19], out[35 * 80 + 79])
    }

    @Test(expected = SrTimeout::class) fun tilerRespectsDeadline() {
        SrTiler(tile = 32, pad = 4).run(img(200, 200), 200, 200, deadlineMs = 0) { _, _ -> }
    }

    @Test fun lanczosDownscaleKeepsFlatColorAndSize() {
        val flat = IntArray(400 * 200) { 0xFF336699.toInt() }
        val out = Upscaler.resample(flat, 400, 200, 150, 75)
        assertEquals(150 * 75, out.size); assertTrue(out.all { it == 0xFF336699.toInt() })
    }

    @Test fun lanczosDownscaleDoesNotAlias() {
        // xadrez de 1 px reduzido 4× deve virar cinza médio (sem padrão residual)
        val w = 256; val chk = IntArray(w * w) { i -> if (((i % w) + (i / w)) % 2 == 0) -1 else 0xFF000000.toInt() }
        val out = Upscaler.resample(chk, w, w, 64, 64)
        val g = out.map { (it shr 8) and 0xFF }
        assertTrue("faixa ${g.min()}..${g.max()}", g.all { abs(it - 128) <= 6 })
    }

    @Test fun stillUsesModelThenFallsBack() {
        val px = img(100, 60)
        val sr: Sr4x = { p, w, h -> IntArray(w * 4 * h * 4) { i -> p[((i / (w * 4)) / 4) * w + (i % (w * 4)) / 4] } }
        val a = MediaEnhancer.enhanceStill(px, 100, 60, 400, sr)!!
        // v3.2: no máximo 2× (400 pedido → 200)
        assertEquals(EnhanceMethod.ML, a.method); assertEquals(200, a.width); assertEquals(120, a.height)
        val b = MediaEnhancer.enhanceStill(px, 100, 60, 400) { _, _, _ -> error("modelo falhou") }!!
        assertEquals(EnhanceMethod.LANCZOS, b.method); assertEquals(200, b.width)   // reserva entrega o mesmo tamanho
        assertNull(MediaEnhancer.enhanceStill(img(1000, 500), 1000, 500, 1080, sr))   // já tem resolução
    }

    @Test fun gifBudgetAndFallback() {
        val w = 40; val h = 30
        val enc = java.io.ByteArrayOutputStream()
        val g = com.coinportracker.share.GifEncoder(enc, w, h)
        repeat(3) { k -> g.addFrameLocal(IntArray(w * h) { i -> (0xFF shl 24) or (((i + k * 7) % 255) shl 16) or 0x4080 }, 100) }
        g.finish()
        var calls = 0
        val sr: Sr4x = { p, ww, hh -> calls++; IntArray(ww * 4 * hh * 4) { i -> p[((i / (ww * 4)) / 4) * ww + (i % (ww * 4)) / 4] } }
        val r = MediaEnhancer.enhanceGif(enc.toByteArray(), 160, sr)!!
        assertEquals(EnhanceMethod.ML, r.method); assertEquals(3, calls); assertEquals(80, r.width)
        val d = GifDecoder.decode(r.data); assertEquals(3, d.frames.size); assertEquals(80, d.width)
        var n = 0
        val failing: Sr4x = { p, ww, hh -> if (++n == 2) error("falhou no 2º quadro") else sr(p, ww, hh) }
        val f = MediaEnhancer.enhanceGif(enc.toByteArray(), 160, failing)!!
        assertEquals(EnhanceMethod.LANCZOS, f.method); assertEquals(3, GifDecoder.decode(f.data).frames.size)
    }

    private fun gifOf(w: Int, h: Int, frames: List<Int>): ByteArray {
        val enc = java.io.ByteArrayOutputStream(); val g = com.coinportracker.share.GifEncoder(enc, w, h)
        frames.forEach { k -> g.addFrameLocal(IntArray(w * h) { i -> (0xFF shl 24) or (((i + k * 7) % 255) shl 16) or 0x4080 }, 100) }
        g.finish(); return enc.toByteArray()
    }
    private val nearest4: Sr4x = { p, ww, hh -> IntArray(ww * 4 * hh * 4) { i -> p[((i / (ww * 4)) / 4) * ww + (i % (ww * 4)) / 4] } }

    /** v3.2: quadros repetidos passam pelo modelo uma vez só. */
    @Test fun gifDedupRunsModelOncePerUniqueFrame() {
        var calls = 0
        val sr: Sr4x = { p, w, h -> calls++; nearest4(p, w, h) }
        val r = MediaEnhancer.enhanceGif(gifOf(40, 30, listOf(0, 1, 0, 1, 0, 2)), 80, sr)!!
        assertEquals(EnhanceMethod.ML, r.method); assertEquals(3, calls)
        assertEquals(6, GifDecoder.decode(r.data).frames.size)
    }

    /** v3.2: se o 1º quadro indica que o GIF passa do orçamento, vai direto para o Lanczos (sem gastar o resto). */
    @Test fun gifBudgetEstimateSwitchesToLanczosEarly() {
        var now = 0L; var calls = 0
        val slow: Sr4x = { p, w, h -> calls++; now += 50_000; nearest4(p, w, h) }
        val r = MediaEnhancer.enhanceGif(gifOf(40, 30, listOf(0, 1, 2, 3)), 80, slow, budgetMs = 110_000, clock = { now })!!
        assertEquals(EnhanceMethod.LANCZOS, r.method); assertEquals(1, calls)
    }

    /** v3.2: fator final < 2 → entrada reduzida para metade do tamanho final antes do modelo 4×. */
    @Test fun mlInputIsReducedWhenFactorBelowTwo() {
        val (ip, iw, ih) = MediaEnhancer.mlInput(img(800, 400), 800, 400, 1080, 540)
        assertEquals(540, iw); assertEquals(270, ih); assertEquals(540 * 270, ip.size)
        val (_, w2, _) = MediaEnhancer.mlInput(img(400, 200), 400, 200, 800, 400)
        assertEquals(400, w2) // fator 2: entrada original
    }

    @Test fun tilerCountsAndReportsProgress() {
        val t = SrTiler(256, 24, 4)
        assertEquals(6, t.tiles(540, 300)) // núcleo 208 → 3 × 2
        val seen = ArrayList<Int>()
        t.run(img(300, 100), 300, 100, onTile = { d, _ -> seen += d }) { _, out -> java.util.Arrays.fill(out, 0.5f) }
        assertEquals(listOf(1, 2), seen)
    }

    /** Tempos medidos no JVM (Lanczos/caminho rápido) — impressos para o relatório. */
    @Test fun printJvmTimings() {
        val px = img(540, 300)
        Upscaler.upscale(px, 540, 300, 1080, 600)
        var t0 = System.nanoTime(); repeat(3) { Upscaler.upscale(px, 540, 300, 1080, 600) }
        println("TIMING Lanczos foto 540x300→1080x600: ${(System.nanoTime() - t0) / 3 / 1_000_000} ms")
        val gif = gifOf(240, 135, (0 until 40).toList())
        t0 = System.nanoTime(); val r = MediaEnhancer.enhanceGif(gif, 480, null)!!
        println("TIMING Lanczos GIF 240x135x40 → ${r.width}x${r.height}: ${(System.nanoTime() - t0) / 1_000_000} ms")
        t0 = System.nanoTime(); MediaEnhancer.dedup(GifDecoder.decode(gif).frames)
        println("TIMING dedup 40 quadros: ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
