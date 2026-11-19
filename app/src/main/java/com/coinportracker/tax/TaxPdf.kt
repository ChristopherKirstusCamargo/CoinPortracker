package com.coinportracker.tax

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** PDF simples (A4) do informe, gerado no aparelho com o PdfDocument do Android. */
object TaxPdf {
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    fun build(report: TaxReport): ByteArray {
        val doc = PdfDocument()
        val title = paint(13f, true)
        val body = paint(9.5f, false)
        val small = paint(8f, false)
        val bold = paint(9.5f, true)
        var pageN = 0
        var page: PdfDocument.Page? = null
        var c: android.graphics.Canvas? = null
        var y = 0f

        fun footer() {
            val canvas = c ?: return
            small.color = 0xFF666666.toInt()
            canvas.drawText("Página $pageN  ·  CoinPortracker  ·  ${report.rules.disclaimer.take(90)}…", M, H - 18f, small)
            small.color = 0xFF111111.toInt()
        }
        fun newPage() {
            page?.let { footer(); doc.finishPage(it) }
            pageN++
            val info = PdfDocument.PageInfo.Builder(W, H, pageN).create()
            page = doc.startPage(info)
            c = page!!.canvas
            y = M
        }
        fun need(dx: Float) { if (y + dx > H - 36f) newPage() }
        fun line(p: Paint, text: String, indent: Float = 0f) {
            need(14f)
            val max = W - M * 2 - indent
            val t = ellipsize(text, p, max)
            c!!.drawText(t, M + indent, y + p.textSize, p)
            y += p.textSize + 4f
        }
        fun gap(g: Float = 8f) { y += g }
        fun money(v: BigDecimal?) = v?.let { TaxEngine.brl(it) } ?: "—"
        fun dec(v: BigDecimal) = v.setScale(8, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString().replace('.', ',')

        newPage()
        val z = runCatching { ZoneId.of(report.zone) }.getOrDefault(ZoneId.systemDefault())
        line(title, "Informe de rendimentos")
        line(small, report.rules.label + "  ·  versão " + report.rules.version)
        line(small, "Regras vigentes em ${report.rules.effectiveFrom}  ·  gerado em ${dateFmt.format(Instant.ofEpochMilli(report.generatedAt).atZone(z))}")
        report.investedTotal?.let { line(small, "Total pago informado (rateio): ${money(it)}") }
        gap(6f)
        line(small, report.rules.disclaimer)
        gap(12f)

        line(bold, "Bens e Direitos — 31/12 (Grupo ${report.rules.group})")
        if (report.holdingsByYear.isEmpty() || report.holdingsByYear.values.all { it.isEmpty() }) line(body, "Nenhuma posição com custo na data.")
        for (year in report.years) {
            val rows = report.holdingsByYear[year].orEmpty()
            if (rows.isEmpty()) continue
            gap(4f); line(bold, "31/12/$year")
            for (h in rows) {
                line(body, "${h.symbol}  ·  grupo ${h.group} código ${h.code}  ·  ${h.codeLabel}")
                line(small, "Qtd ${dec(h.qty)}  ·  custo ${money(h.costBrl)}" + (if (h.belowThreshold) "  ·  abaixo de ${money(report.rules.threshold())} (declaração facultativa por espécie)" else "") + if (h.estimated) "  ·  estimado" else "")
                line(small, h.description)
            }
        }

        gap(12f); line(bold, "Livro de aquisição")
        if (report.lots.isEmpty()) line(body, "Nenhum lote montado a partir da blockchain.")
        for (l in report.lots) {
            val origin = if (l.origin == LotOrigin.REWARD) "rendimento" else "entrada"
            line(body, "${l.acquiredOn}  ${l.symbol}  ${dec(l.qty)}  ·  ${money(l.costBrl)}  ·  $origin" + if (l.estimated) "  ·  estimado" else "")
        }

        gap(12f); line(bold, "Alienações por mês  ·  isenção ${money(report.rules.exemption())}")
        if (report.months.isEmpty()) line(body, "Nenhuma alienação encontrada no histórico.")
        for (m in report.months) {
            val sit = if (m.exempt) "isento" else "tributável · DARF ${report.rules.darfCode} · imposto sugerido ${money(m.suggestedTax)}"
            line(body, "${m.month}  alienado ${money(m.proceedsBrl)}  ·  ganho ${money(m.gainBrl)}  ·  $sit" + if (m.estimated) "  ·  estimado" else "")
            for (d in m.disposals) line(small, "    ${d.symbol}  ${dec(d.qty)}  ·  ${money(d.proceedsBrl)} − custo ${money(d.costBrl)} = ${money(d.gainBrl)}", 8f)
        }

        gap(12f); line(bold, "Rendimentos de staking")
        if (report.stakingIncome.isEmpty()) line(body, "Nenhum rendimento de staking identificado.")
        for (r in report.stakingIncome) {
            line(body, "${r.date}  ${r.symbol}  ${dec(r.qty)}  ·  ${money(r.valueBrl)}" + if (r.estimated) "  ·  estimado" else "")
        }

        gap(12f); line(bold, "Notas")
        report.notes.forEach { line(small, "• $it") }
        gap(8f); line(bold, "Fontes das regras")
        report.rules.sources.forEach { line(small, "• $it") }

        footer()
        doc.finishPage(page!!)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun paint(size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun ellipsize(text: String, p: Paint, max: Float): String {
        if (p.measureText(text) <= max) return text
        var t = text
        while (t.length > 4 && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}
