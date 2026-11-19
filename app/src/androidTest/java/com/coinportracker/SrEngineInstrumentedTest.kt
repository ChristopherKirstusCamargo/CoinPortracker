package com.coinportracker

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coinportracker.media.SrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** O modelo empacotado roda no Android e reproduz a saída de referência do TFLite em Python (mesmo arquivo .tflite). */
@RunWith(AndroidJUnit4::class)
class SrEngineInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val test = InstrumentationRegistry.getInstrumentation().context
    private fun px(name: String): Triple<IntArray, Int, Int> {
        val b = test.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val a = IntArray(b.width * b.height); b.getPixels(a, 0, b.width, 0, 0, b.width, b.height)
        return Triple(a, b.width, b.height)
    }

    private fun check(model: SrEngine.Model, ref: String) {
        val (inp, w, h) = px("sr/in128.png")
        val (exp, ew, eh) = px(ref)
        val t0 = System.nanoTime()
        val out = SrEngine.open(app, model).use { it.upscale4x(inp, w, h, Long.MAX_VALUE) }
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(ew * eh, out.size)
        // margens recortadas e bordas replicadas mudam a periferia; compara o miolo de cada bloco
        var sum = 0.0; var n = 0; var mx = 0
        for (y in 64 until eh - 64) for (x in 64 until ew - 64) {
            val a = out[y * ew + x]; val b = exp[y * ew + x]
            for (s in intArrayOf(16, 8, 0)) { val d = abs(((a shr s) and 255) - ((b shr s) and 255)); sum += d; n++; if (d > mx) mx = d }
        }
        val mean = sum / n
        android.util.Log.i("SrTest", "$model ${"%.0f".format(ms)} ms mean=${"%.3f".format(mean)} max=$mx")
        assertTrue("diferença média $mean", mean < 1.5)
        assertTrue("diferença máxima $mx", mx <= 24)
    }

    @Test fun photoModelMatchesReference() = check(SrEngine.Model.PHOTO, "sr/out_realesr-general-x4v3.png")
    @Test fun gifModelMatchesReference() = check(SrEngine.Model.ANIM, "sr/out_realesr-animevideov3.png")
}
