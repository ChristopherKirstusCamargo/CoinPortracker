@file:Suppress("DEPRECATION")

package com.coinportracker.position

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import com.coinportracker.share.GifEncoder
import java.io.File
import java.io.OutputStream

/**
 * O cartão animado. O quadro vem do [Movie] do sistema (API 26+, o mesmo caminho do cartão do portfólio)
 * e é regravado com o codificador GIF já do app, paleta por quadro, para o fundo colorido não virar cinza.
 * Teto: 2 s, 10 qps, 720 px. Um GIF enorme é desenhado já reduzido, nunca no tamanho original.
 */
object PositionGif {
    const val EDGE = 720
    const val STEP_MS = 100
    const val MAX_MS = 2_000
    const val MAX_FRAMES = 20
    private const val DRAW_EDGE = 960

    fun scaledFrame(movie: Movie, t: Int, maxEdge: Int = DRAW_EDGE): Bitmap {
        val mw = movie.width().coerceAtLeast(1)
        val mh = movie.height().coerceAtLeast(1)
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(mw, mh))
        val w = (mw * scale).toInt().coerceAtLeast(1)
        val h = (mh * scale).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(scale, scale)
        movie.setTime(t.coerceAtLeast(0))
        movie.draw(c, 0f, 0f)
        return b
    }

    fun frameTimes(durationMs: Int): List<Int> {
        val dur = durationMs.coerceIn(STEP_MS, MAX_MS)
        val n = (dur / STEP_MS).coerceIn(1, MAX_FRAMES)
        return (0 until n).map { it * STEP_MS }
    }

    fun encode(frames: List<Bitmap>, edge: Int = EDGE, delayMs: Int = STEP_MS, out: OutputStream) {
        val enc = GifEncoder(out, edge, edge)
        val px = IntArray(edge * edge)
        val card = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        for (frame in frames) {
            c.drawBitmap(frame, null, android.graphics.RectF(0f, 0f, edge.toFloat(), edge.toFloat()), null)
            card.getPixels(px, 0, edge, 0, 0, edge, edge)
            enc.addFrameLocal(px, delayMs)
        }
        enc.finish()
    }

    fun write(file: File, frames: List<Bitmap>, edge: Int = EDGE, delayMs: Int = STEP_MS) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { encode(frames, edge, delayMs, it) }
    }

    /** Compõe o cartão em cada quadro e grava um GIF quadrado. Um quadro por vez, para não segurar a animação inteira. */
    fun writeCard(file: File, renderer: PositionCardRenderer, model: PositionCardModel, logo: Bitmap?, movie: Movie) {
        val times = frameTimes(movie.duration())
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            val enc = GifEncoder(out, EDGE, EDGE)
            val px = IntArray(EDGE * EDGE)
            for (t in times) {
                val art = scaledFrame(movie, t)
                val card = renderer.render(model, logo, art, EDGE, EDGE)
                art.recycle()
                card.getPixels(px, 0, EDGE, 0, 0, EDGE, EDGE)
                card.recycle()
                enc.addFrameLocal(px, STEP_MS)
            }
            enc.finish()
        }
    }
}
