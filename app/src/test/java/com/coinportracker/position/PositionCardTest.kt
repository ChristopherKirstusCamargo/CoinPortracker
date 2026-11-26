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
        amount: String = "10,1",
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
        assertEquals(base.width, base.height)
        val corner = base.getPixel(8, 8)
        assertTrue(Color.red(corner) < 50 && Color.green(corner) < 50 && Color.blue(corner) < 50)

        assertNotEquals(hash(base), hash(renderer.render(model(amount = "11"))))
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
        val art = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val ac = android.graphics.Canvas(art)
        ac.drawColor(android.graphics.Color.rgb(18, 42, 78))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(210, 150, 70) }
        ac.drawCircle(240f, 40f, 70f, paint)
        val withArt = renderer.render(model(signature = "Christopher"), logo, art)
        File("build/position-card-art.png").outputStream().use { withArt.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val red = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val gif = File("build/position-card.gif")
        PositionGif.write(gif, listOf(red, blue), edge = 48, delayMs = 100)
        val bytes = gif.readBytes()
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertTrue(bytes.count { it == 0x2C.toByte() } >= 2)
        assertTrue(gif.length() < 500_000)
    }
}
