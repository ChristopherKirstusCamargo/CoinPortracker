package com.coinportracker.position

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.coinportracker.R
import kotlin.math.roundToInt

/** O que o cartão desenha. Sem carteira, sem endereço, sem rede. */
data class PositionCardModel(
    val symbol: String,
    val name: String,
    val amountText: String,
    /** Nulo = não desenhar valor em moeda (padrão, por privacidade). */
    val fiatText: String?,
    val signature: String,
    /** Nulo quando a leitura está em dia. "Leitura parcial" ou "Dados desatualizados". */
    val status: String?,
)

/**
 * Cartão 4:5 (1080×1350), vidro escuro, para o lugar de uma imagem de perfil.
 * Logo, quantidade grande e o nome em fonte de assinatura (Great Vibes, OFL).
 */
class PositionCardRenderer(ctx: Context) {
    private val display = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_display_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val medium = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_medium) }.getOrNull() ?: Typeface.DEFAULT
    private val semibold = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val script = runCatching { ResourcesCompat.getFont(ctx, R.font.great_vibes) }.getOrNull()
        ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    fun render(m: PositionCardModel, logo: Bitmap? = null, w: Int = WIDTH, h: Int = HEIGHT): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w, h, m.copy(signature = CardName.sanitize(m.signature)), logo)
        return b
    }

    fun draw(c: Canvas, w: Int, h: Int, m: PositionCardModel, logo: Bitmap?) {
        val s = w / WIDTH.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        c.drawColor(0xFF070707.toInt())
        p.shader = RadialGradient(w * 0.5f, h * 0.22f, w * 0.85f, 0x18FFFFFF, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        val pad = 64f * s
        val top = 88f * s
        val glass = RectF(pad, top, w - pad, h - 80f * s)
        val r = 52f * s
        p.color = 0xFF141414.toInt()
        c.drawRoundRect(glass, r, r, p)
        p.shader = LinearGradient(glass.left, glass.top, glass.left, glass.top + glass.height() * 0.45f,
            intArrayOf(0xFF1C1C1C.toInt(), 0xFF121212.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(glass, r, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * s
        p.color = 0x1AFFFFFF
        c.drawRoundRect(glass, r, r, p)
        p.style = Paint.Style.FILL
        // brilho no topo do vidro
        val sheen = RectF(glass.left + 2f * s, glass.top + 2f * s, glass.right - 2f * s, glass.top + 90f * s)
        p.shader = LinearGradient(0f, sheen.top, 0f, sheen.bottom, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(sheen, r, r, p)
        p.shader = null

        val (cx, cy) = logoCenter(w, h)
        val lr = 92f * s
        p.color = 0xFF2A2A2A.toInt()
        c.drawCircle(cx, cy, lr, p)
        if (logo != null) {
            val save = c.save()
            val clip = Path().apply { addCircle(cx, cy, lr - 1.5f * s, Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(logo, null, RectF(cx - lr, cy - lr, cx + lr, cy + lr), p)
            c.restoreToCount(save)
        } else {
            val initials = m.symbol.filter { it.isLetter() }.take(2).uppercase().ifBlank { m.symbol.take(2).uppercase() }
            text(c, p, initials, cx, cy + 18f * s, 54f, semibold, Color.WHITE, Paint.Align.CENTER, s)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * s
        p.color = 0x33FFFFFF
        c.drawCircle(cx, cy, lr, p)
        p.style = Paint.Style.FILL

        var y = cy + lr + 78f * s
        val name = m.name.trim().ifBlank { m.symbol }
        text(c, p, ellipsize(p, name, medium, 28f * s, glass.width() - 120f * s), cx, y, 28f, medium, 0xFF9A9A9A.toInt(), Paint.Align.CENTER, s)
        y += 92f * s
        val amountSize = fit(p, m.amountText, display, 92f, 40f, glass.width() - 120f * s, s)
        text(c, p, m.amountText, cx, y, amountSize, display, Color.WHITE, Paint.Align.CENTER, s)

        val fiat = m.fiatText?.trim()?.takeIf { it.isNotEmpty() }
        if (fiat != null) {
            y += 64f * s
            val fs = fit(p, fiat, medium, 34f, 22f, glass.width() - 140f * s, s)
            text(c, p, fiat, cx, y, fs, medium, 0xFFB0B0B0.toInt(), Paint.Align.CENTER, s)
        }

        val status = m.status?.trim()?.takeIf { it.isNotEmpty() }
        if (status != null) {
            y += 52f * s
            text(c, p, status, cx, y, 26f, medium, 0xFFFFB547.toInt(), Paint.Align.CENTER, s)
        }

        val sig = CardName.sanitize(m.signature)
        if (sig.isNotEmpty()) {
            val lineY = glass.bottom - 210f * s
            p.color = 0x22FFFFFF
            c.drawRoundRect(RectF(glass.left + 150f * s, lineY, glass.right - 80f * s, lineY + 1.5f * s), 1f, 1f, p)
            val sigSize = fit(p, sig, script, 78f, 42f, glass.width() - 220f * s, s)
            text(c, p, sig, glass.right - 96f * s, lineY + 108f * s, sigSize, script, 0xFFF4F4F4.toInt(), Paint.Align.RIGHT, s)
        }

        text(c, p, "CoinPortracker", cx, glass.bottom - 48f * s, 22f, medium, 0xFF6A6A6A.toInt(), Paint.Align.CENTER, s)
    }

    /** Centro do logo, para os testes conferirem o desenho sem depender de OCR. */
    fun logoCenter(w: Int, h: Int): Pair<Float, Float> {
        val s = w / WIDTH.toFloat()
        val top = 88f * s
        return w / 2f to (top + 168f * s)
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

    /** [size] é o tamanho em px de referência (1080 de largura); devolve o tamanho já na escala de [s]. */
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
        const val HEIGHT = 1350
    }
}
