package com.coinportracker.share

import android.content.Context
import android.graphics.*
import androidx.core.content.res.ResourcesCompat
import com.coinportracker.R
import com.coinportracker.data.HubState
import kotlin.math.roundToInt

/** Uma posição no cartão (só percentual; valor em USD só se o usuário ligar "Mostrar valores"). */
data class ShareSlice(val label: String, val share: Double)

data class ShareData(
    val positions: List<ShareSlice>,
    val chains: List<ShareSlice>,
    val assetCount: Int,
    val chainCount: Int,
    val totalText: String?,
    val dateText: String,
) {
    companion object {
        /** Monta os dados a partir do portfólio. Agrupa o que passar do top N em "Outros". */
        fun from(h: HubState, topN: Int, totalText: String?, dateText: String): ShareData {
            val total = h.agg.totalUsd.takeIf { it > 0 } ?: 1.0
            // agrupa pelo símbolo (SOL em stake + SOL livre = SOL)
            val bySym = h.agg.assets.filter { it.valueUsd > 0 }.groupBy { it.symbol.uppercase() }
                .map { (_, l) -> l.maxBy { it.valueUsd }.symbol to l.sumOf { it.valueUsd } }.sortedByDescending { it.second }
            val sorted = bySym
            val top = sorted.take(topN).map { ShareSlice(it.first, it.second / total) }
            val rest = sorted.drop(topN).sumOf { it.second } / total
            val chains = h.agg.byNetwork.filter { it.share > 0 }.sortedByDescending { it.share }.map { ShareSlice(it.label, it.share) }
            return ShareData(if (rest > 0.0005) top + ShareSlice("Outros", rest) else top, chains, sorted.size, chains.size, totalText, dateText)
        }
    }
}

/** BANNER = mídia do banner do Início; GALLERY = qualquer foto ou GIF escolhido no aparelho. */
enum class ShareBg(val label: String) { BANNER("Banner"), GALLERY("Galeria"), SOLID("Sólido"), GRADIENT("Degradê") }
enum class ShareStyle(val label: String) { DONUT("Rosca"), BARS("Barras") }

data class ShareOptions(
    val style: ShareStyle = ShareStyle.DONUT,
    val topN: Int = 5,
    val showChains: Boolean = true,
    val showCount: Boolean = true,
    val showDate: Boolean = true,
    val showValues: Boolean = false,
    val bg: ShareBg = ShareBg.BANNER,
)

/**
 * Desenha o cartão de compartilhar (1080×1350 por padrão, proporção 4:5) com Canvas do Android:
 * monocromático, logo "C", alocação em rosca ou barras, top posições com %, redes. Sem valores
 * monetários a menos que o usuário ligue "Mostrar valores". Gerado fora da tela (funciona com
 * a proteção de captura ligada).
 */
class ShareCardRenderer(ctx: Context) {
    private val semibold = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val bold = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val medium = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_medium) }.getOrNull() ?: Typeface.DEFAULT
    private val display = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_display_semibold) }.getOrNull() ?: bold
    private val logo: Bitmap? = runCatching { BitmapFactory.decodeResource(ctx.resources, R.drawable.splash_mark) }.getOrNull()

    fun render(w: Int, h: Int, d: ShareData, o: ShareOptions, bg: Bitmap?): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w, h, d, o, bg)
        return b
    }

    /** Só o degradê + interface, fundo transparente — para sobrepor um vídeo no export MP4. */
    fun foreground(w: Int, h: Int, d: ShareData, o: ShareOptions): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w, h, d, o, bg = null, paintBackground = false, dimOverVideo = true)
        return b
    }

    fun draw(c: Canvas, w: Int, h: Int, d: ShareData, o: ShareOptions, bg: Bitmap?) =
        draw(c, w, h, d, o, bg, paintBackground = true, dimOverVideo = false)

    fun draw(c: Canvas, w: Int, h: Int, d: ShareData, o: ShareOptions, bg: Bitmap?, paintBackground: Boolean, dimOverVideo: Boolean) {
        val s = w / 1080f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // fundo
        when {
            dimOverVideo -> {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x8C000000.toInt(), 0xB3000000.toInt(), 0xE6000000.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            }
            paintBackground && bg != null && (o.bg == ShareBg.BANNER || o.bg == ShareBg.GALLERY) -> {
                c.drawColor(Color.BLACK)
                val sc = maxOf(w / bg.width.toFloat(), h / bg.height.toFloat())
                val dw = bg.width * sc; val dh = bg.height * sc
                c.drawBitmap(bg, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x8C000000.toInt(), 0xB3000000.toInt(), 0xE6000000.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            }
            paintBackground && o.bg == ShareBg.GRADIENT -> {
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF2A2A2A.toInt(), 0xFF101010.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
                p.shader = RadialGradient(w * 0.85f, h * 0.1f, w * 0.7f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            }
            paintBackground -> c.drawColor(0xFF0B0B0B.toInt())
        }
        val pad = 84f * s
        val white = Color.WHITE; val mid = 0xFFB0B0B0.toInt(); val low = 0xFF6E6E6E.toInt()
        fun text(t: String, x: Float, y: Float, size: Float, tf: Typeface, color: Int, align: Paint.Align = Paint.Align.LEFT) {
            p.typeface = tf; p.textSize = size * s; p.color = color; p.textAlign = align; p.shader = null; p.style = Paint.Style.FILL
            c.drawText(t, x, y, p)
        }
        // topo: logo + nome + data
        val lr = 40f * s
        p.color = 0xFF000000.toInt(); c.drawCircle(pad + lr, pad + lr, lr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * s; p.color = 0x33FFFFFF; c.drawCircle(pad + lr, pad + lr, lr, p); p.style = Paint.Style.FILL
        logo?.let { c.drawBitmap(it, null, RectF(pad + lr * 0.25f, pad + lr * 0.25f, pad + lr * 1.75f, pad + lr * 1.75f), p) }
        text("CoinPortracker", pad + lr * 2 + 22 * s, pad + lr + 13 * s, 36f, semibold, white)
        if (o.showDate) text(d.dateText, w - pad, pad + lr + 12 * s, 28f, medium, mid, Paint.Align.RIGHT)
        // título
        var y = pad + lr * 2 + 110 * s
        text("Minha carteira", pad, y, 66f, display, white)
        y += 56 * s
        val sub = buildList {
            if (o.showCount) add(if (d.assetCount == 1) "1 ativo" else "${d.assetCount} ativos")
            if (o.showCount && d.chainCount > 0) add(if (d.chainCount == 1) "1 rede" else "${d.chainCount} redes")
            if (o.showValues && d.totalText != null) add(d.totalText)
        }
        if (sub.isNotEmpty()) text(sub.joinToString("  ·  "), pad, y, 32f, medium, mid)
        y += 70 * s
        val shades = intArrayOf(0xFFFFFFFF.toInt(), 0xFFD0D0D0.toInt(), 0xFFA6A6A6.toInt(), 0xFF808080.toInt(), 0xFF5E5E5E.toInt(), 0xFF454545.toInt(), 0xFF353535.toInt(), 0xFF2A2A2A.toInt(), 0xFF222222.toInt())
        val items = d.positions
        if (o.style == ShareStyle.DONUT) {
            val dia = 420f * s
            val cx = pad + dia / 2; val cy = y + dia / 2
            val sw = 64f * s
            val rect = RectF(cx - dia / 2 + sw / 2, cy - dia / 2 + sw / 2, cx + dia / 2 - sw / 2, cy + dia / 2 - sw / 2)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.BUTT
            p.color = 0x22FFFFFF; c.drawArc(rect, 0f, 360f, false, p)
            var start = -90f
            items.forEachIndexed { i, it ->
                val sweep = (it.share * 360).toFloat()
                if (sweep > 1.2f) { p.color = shades[i.coerceAtMost(shades.size - 1)]; c.drawArc(rect, start + 0.8f, sweep - 1.6f, false, p) }
                start += sweep
            }
            p.style = Paint.Style.FILL
            items.firstOrNull()?.let { top ->
                text(pct(top.share), cx, cy + 6 * s, 58f, display, white, Paint.Align.CENTER)
                text(top.label.take(10), cx, cy + 52 * s, 28f, medium, mid, Paint.Align.CENTER)
            }
            // legenda à direita
            val lx = pad + dia + 70 * s
            var ly = y + 40 * s
            val rowH = minOf(66f * s, (dia - 20 * s) / items.size.coerceAtLeast(1))
            items.forEachIndexed { i, it ->
                p.color = shades[i.coerceAtMost(shades.size - 1)]
                c.drawRoundRect(RectF(lx, ly - 22 * s, lx + 22 * s, ly), 6 * s, 6 * s, p)
                text(ellipsize(it.label, 9), lx + 40 * s, ly - 1 * s, 34f, semibold, white)
                text(pct(it.share), w - pad, ly - 1 * s, 34f, semibold, mid, Paint.Align.RIGHT)
                ly += rowH
            }
            y += dia + 70 * s
        } else {
            val barW = w - pad * 2
            items.forEachIndexed { i, it ->
                text(ellipsize(it.label, 14), pad, y, 34f, semibold, white)
                text(pct(it.share), w - pad, y, 34f, semibold, mid, Paint.Align.RIGHT)
                y += 22 * s
                p.color = 0x22FFFFFF; c.drawRoundRect(RectF(pad, y, pad + barW, y + 14 * s), 7 * s, 7 * s, p)
                p.color = shades[i.coerceAtMost(shades.size - 1)]
                c.drawRoundRect(RectF(pad, y, pad + (barW * it.share.toFloat()).coerceAtLeast(14 * s), y + 14 * s), 7 * s, 7 * s, p)
                y += 62 * s
            }
            y += 20 * s
        }
        // redes
        if (o.showChains && d.chains.isNotEmpty()) {
            text("Redes", pad, y, 28f, medium, low)
            y += 28 * s
            var x = pad
            for (ch in d.chains.take(5)) {
                val label = "${ch.label}  ${pct(ch.share)}"
                p.typeface = semibold; p.textSize = 28f * s
                val tw = p.measureText(label) + 44 * s
                if (x + tw > w - pad) break
                p.color = 0x1FFFFFFF; c.drawRoundRect(RectF(x, y, x + tw, y + 58 * s), 29 * s, 29 * s, p)
                text(label, x + 22 * s, y + 39 * s, 28f, semibold, white)
                x += tw + 14 * s
            }
        }
    }

    companion object {
        fun pct(v: Double): String {
            val x = v * 100
            return if (x >= 10 || x == 0.0) "${x.roundToInt()}%" else String.format(java.util.Locale("pt", "BR"), "%.1f%%", x)
        }
        private fun ellipsize(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1) + "…"
    }
}
