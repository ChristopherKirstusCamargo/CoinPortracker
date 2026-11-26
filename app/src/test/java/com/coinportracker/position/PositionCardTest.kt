package com.coinportracker.position

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = android.app.Application::class)
class PositionCardTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val renderer by lazy { PositionCardRenderer(ctx) }

    private fun model(
        amount: String = "10 SOL",
        signature: String = "Ana",
        fiat: String? = null,
        status: String? = null,
    ) = PositionCardModel("SOL", "Solana", amount, fiat, signature, status)

    private fun pixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun hash(b: Bitmap) = pixels(b).contentHashCode()

    @Test fun portraitGlassCardChangesWithAmountNameAndFiat() {
        val base = renderer.render(model())
        assertEquals(PositionCardRenderer.WIDTH, base.width)
        assertEquals(PositionCardRenderer.HEIGHT, base.height)
        assertEquals(4f / 5f, base.width.toFloat() / base.height, 0.001f)
        val corner = base.getPixel(8, 8)
        assertTrue(Color.red(corner) < 40 && Color.green(corner) < 40 && Color.blue(corner) < 40)
        val center = base.getPixel(base.width / 2, base.height / 2)
        assertTrue(Color.red(center) > Color.red(corner))

        assertNotEquals(hash(base), hash(renderer.render(model(amount = "11 SOL"))))
        assertNotEquals(hash(base), hash(renderer.render(model(signature = "Bruno"))))
        assertNotEquals(hash(base), hash(renderer.render(model(fiat = "R$ 1.000,00"))))
        assertEquals(hash(renderer.render(model(fiat = null))), hash(renderer.render(model(fiat = "  "))))
        assertNotEquals(hash(base), hash(renderer.render(model(status = "Leitura parcial"))))
        assertEquals(hash(base), hash(renderer.render(model())))

        val stripped = renderer.render(model(signature = "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"))
        assertEquals(hash(renderer.render(model(signature = ""))), hash(stripped))
    }

    @Test fun logoIsDrawnAndSampleIsSaved() {
        val logo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val card = renderer.render(model(signature = "Christopher"), logo)
        val (x, y) = renderer.logoCenter(card.width, card.height)
        val px = card.getPixel(x.toInt(), y.toInt())
        assertTrue("logo vermelho no centro, veio ${Color.red(px)},${Color.green(px)},${Color.blue(px)}", Color.red(px) > 200 && Color.green(px) < 40)
        val out = File("build/position-card.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 5_000)
    }
}
