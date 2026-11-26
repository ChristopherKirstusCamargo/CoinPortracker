package com.coinportracker.position

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.coinportracker.R

/**
 * Cartão quadrado (1080×1080). A imagem ou o GIF preenche o fundo;
 * o logo, a quantidade e a assinatura ficam num eixo só, com véu escuro para o texto aguentar qualquer fundo.
 */
data class PositionCardModel(
    val symbol: String,
    val name: String,
    /** Número já formatado, no máximo 1 casa. Ex.: "10,1" ou "< 0,1". */
    val amountNumber: String,
    val fiatText: String?,
    val signature: String,
    val status: String?,
)

class PositionCardRenderer(ctx: Context) {
    private val display = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_display_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val medium = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_medium) }.getOrNull() ?: Typeface.DEFAULT
    private val semibold = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val script = runCatching { ResourcesCompat.getFont(ctx, R.font.great_vibes) }.getOrNull()
        ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    fun render(m: PositionCardModel, logo: Bitmap? = null, art: Bitmap? = null, w: Int = WIDTH, h: Int = HEIGHT): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w, h, m.copy(signature = CardName.sanitize(m.signature)), logo, art)
        return b
    }

    fun draw(c: Canvas, w: Int, h: Int, m: PositionCardModel, logo: Bitmap?, art: Bitmap?) {
        val s = w / WIDTH.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        if (art != null) {
            cover(c, art, w, h, p)
        } else {
            c.drawColor(0xFF0A0A0A.toInt())
            p.shader = RadialGradient(w * 0.5f, h * 0.28f, w * 0.72f, 0x22FFFFFF, 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }
        // véu: o GIF continua visível no meio, e o texto senta no escuro
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xB3000000.toInt(), 0x66000000.toInt(), 0x55000000.toInt(), 0xE0000000.toInt()),
            floatArrayOf(0f, 0.28f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // poça escura atrás do número, sem caixa: o texto aguenta um GIF claro
        p.shader = RadialGradient(w * 0.5f, h * 0.48f, w * 0.42f, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        val (cx, cy) = logoCenter(w, h)
        val lr = 72f * s
        p.color = 0xCC000000.toInt()
        c.drawCircle(cx, cy, lr + 10f * s, p)
        p.color = 0xFF1A1A1A.toInt()
        c.drawCircle(cx, cy, lr, p)
        if (logo != null) {
            val save = c.save()
            c.clipPath(Path().apply { addCircle(cx, cy, lr - 2f * s, Path.Direction.CW) })
            c.drawBitmap(logo, null, RectF(cx - lr, cy - lr, cx + lr, cy + lr), p)
            c.restoreToCount(save)
        } else {
            val initials = m.symbol.filter { it.isLetter() }.take(2).uppercase().ifBlank { m.symbol.take(2).uppercase() }
            text(c, p, initials, cx, cy + 16f * s, 48f, semibold, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, s)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * s
        p.color = 0x66FFFFFF
        c.drawCircle(cx, cy, lr, p)
        p.style = Paint.Style.FILL
        p.letterSpacing = 0f

        var y = cy + lr + 56f * s
        val label = m.name.trim().ifBlank { m.symbol }.uppercase()
        p.letterSpacing = 0.18f
        text(c, p, ellipsize(p, label, medium, 22f * s, w - 200f * s), cx, y, 22f, medium, 0xFFB5B5B5.toInt(), Paint.Align.CENTER, s)
        p.letterSpacing = 0f

        y += 108f * s
        y = drawAmount(c, p, m.amountNumber, m.symbol, cx, y, w, s)

        val fiat = m.fiatText?.trim()?.takeIf { it.isNotEmpty() }
        if (fiat != null) {
            y += 48f * s
            val fs = fit(p, fiat, medium, 30f, 20f, w - 160f * s, s)
            text(c, p, fiat, cx, y, fs, medium, 0xFFC8C8C8.toInt(), Paint.Align.CENTER, s)
        }
        val status = m.status?.trim()?.takeIf { it.isNotEmpty() }
        if (status != null) {
            y += 40f * s
            text(c, p, status, cx, y, 22f, medium, 0xFFFFB547.toInt(), Paint.Align.CENTER, s)
        }

        val sig = CardName.sanitize(m.signature)
        if (sig.isNotEmpty()) {
            y += 92f * s
            val sigSize = fit(p, sig, script, 76f, 44f, w - 200f * s, s)
            text(c, p, sig, cx, y, sigSize, script, 0xFFF7F7F7.toInt(), Paint.Align.CENTER, s)
            y += 52f * s
        } else y += 36f * s
        text(c, p, "CoinPortracker", cx, y, 18f, medium, 0xFF9A9A9A.toInt(), Paint.Align.CENTER, s)
    }

    /** Número grande, símbolo menor na mesma linha de base. Devolve a linha de base usada. */
    private fun drawAmount(c: Canvas, p: Paint, number: String, symbol: String, cx: Float, baseline: Float, w: Int, s: Float): Float {
        val maxW = w - 140f * s
        var numSize = 124f
        val unitSize = 36f
        val gap = 16f * s
        while (numSize > 64f) {
            p.typeface = display
            p.textSize = numSize * s
            val nw = p.measureText(number)
            p.typeface = semibold
            p.textSize = unitSize * s
            val uw = p.measureText(symbol)
            if (nw + gap + uw <= maxW) break
            numSize -= 4f
        }
        p.typeface = display
        p.textSize = numSize * s
        val nw = p.measureText(number)
        p.typeface = semibold
        p.textSize = unitSize * s
        val uw = p.measureText(symbol)
        val start = cx - (nw + gap + uw) / 2f
        p.typeface = display
        p.textSize = numSize * s
        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = Paint.Align.LEFT
        p.style = Paint.Style.FILL
        c.drawText(number, start, baseline, p)
        p.typeface = semibold
        p.textSize = unitSize * s
        p.color = 0xFFE4E4E4.toInt()
        c.drawText(symbol, start + nw + gap, baseline, p)
        p.textAlign = Paint.Align.CENTER
        return baseline
    }

    fun logoCenter(w: Int, h: Int): Pair<Float, Float> {
        val s = w / WIDTH.toFloat()
        return w / 2f to 390f * s
    }

    private fun cover(c: Canvas, art: Bitmap, w: Int, h: Int, p: Paint) {
        val sc = maxOf(w / art.width.toFloat(), h / art.height.toFloat())
        val dw = art.width * sc
        val dh = art.height * sc
        c.drawBitmap(art, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
    }

    private fun text(c: Canvas, p: Paint, t: String, x: Float, y: Float, size: Float, tf: Typeface, color: Int, align: Paint.Align, s: Float) {
        p.typeface = tf
        p.textSize = size * s
        p.color = color
        p.textAlign = align
        p.shader = null
        p.style = Paint.Style.FILL
        c.drawText(t, x, y, p)
    }

    private fun fit(p: Paint, t: String, tf: Typeface, size: Float, min: Float, maxW: Float, s: Float): Float {
        p.typeface = tf
        var px = size * s
        val floor = min * s
        p.textSize = px
        while (px > floor && p.measureText(t) > maxW) {
            px -= 2f * s
            p.textSize = px
        }
        return px / s
    }

    private fun ellipsize(p: Paint, t: String, tf: Typeface, textSize: Float, maxW: Float): String {
        p.typeface = tf
        p.textSize = textSize
        if (p.measureText(t) <= maxW) return t
        var n = t.length
        while (n > 1 && p.measureText(t.take(n) + "…") > maxW) n--
        return t.take(n) + "…"
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1080
    }
}
