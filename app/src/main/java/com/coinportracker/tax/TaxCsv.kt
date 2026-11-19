package com.coinportracker.tax

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV no padrão brasileiro (separador ';') para abrir no Excel/LibreOffice. */
object TaxCsv {
    private val dt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun build(report: TaxReport): String {
        val b = StringBuilder()
        val z = runCatching { ZoneId.of(report.zone) }.getOrDefault(ZoneId.systemDefault())
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        fun dec(v: BigDecimal?) = v?.setScale(8, RoundingMode.HALF_EVEN)?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: ""
        fun money(v: BigDecimal?) = v?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString()?.replace('.', ',') ?: ""

        b.appendLine("CoinPortracker — Informe de rendimentos")
        b.appendLine("Regras;${report.rules.label};versão ${report.rules.version};a partir de ${report.rules.effectiveFrom}")
        b.appendLine("Gerado em;${dt.format(Instant.ofEpochMilli(report.generatedAt).atZone(z))}")
        report.investedTotal?.let { b.appendLine("Total pago informado (rateio);${money(it)}") }
        b.appendLine(report.rules.disclaimer)
        b.appendLine()

        b.appendLine("BENS E DIREITOS (31/12)")
        b.appendLine(listOf("Ano","Grupo","Código","Ativo","Quantidade","Custo de aquisição (R$)","Abaixo do limite de declaração","Estimado","Discriminação sugerida").joinToString(";"))
        for (y in report.years) {
            for (h in report.holdingsByYear[y].orEmpty()) {
                b.appendLine(listOf(y.toString(), h.group, h.code, cell(h.symbol), dec(h.qty), money(h.costBrl), if (h.belowThreshold) "sim" else "não", if (h.estimated) "sim" else "não", cell(h.description)).joinToString(";"))
            }
        }
        b.appendLine()

        b.appendLine("LIVRO DE AQUISIÇÃO")
        b.appendLine(listOf("Data","Ativo","Quantidade","Custo (R$)","Origem","Estimado","Carteira","Hash").joinToString(";"))
        for (l in report.lots) {
            val origin = when (l.origin) { LotOrigin.PURCHASE -> "Compra / entrada"; LotOrigin.REWARD -> "Rendimento de staking" }
            b.appendLine(listOf(l.acquiredOn.toString(), cell(l.symbol), dec(l.qty), money(l.costBrl), origin, if (l.estimated) "sim" else "não", cell(l.walletName ?: ""), l.hash ?: "").joinToString(";"))
        }
        b.appendLine()

        b.appendLine("ALIENAÇÕES POR MÊS")
        b.appendLine(listOf("Mês","Total alienado (R$)","Custo (R$)","Ganho (R$)","Isento (limite ${money(report.rules.exemption())})","Ganho tributável (R$)","Imposto sugerido (R$)","Estimado","DARF").joinToString(";"))
        for (m in report.months) {
            b.appendLine(listOf(m.month.toString(), money(m.proceedsBrl), money(m.costBrl), money(m.gainBrl), if (m.exempt) "sim" else "não", money(m.taxableGain), money(m.suggestedTax), if (m.estimated) "sim" else "não", if (m.exempt) "" else report.rules.darfCode).joinToString(";"))
        }
        b.appendLine()
        b.appendLine("DETALHE DAS ALIENAÇÕES")
        b.appendLine(listOf("Mês","Ativo","Quantidade","Valor da alienação (R$)","Custo (R$)","Ganho (R$)","Estimado","Hash").joinToString(";"))
        for (m in report.months) for (d in m.disposals) {
            b.appendLine(listOf(d.month.toString(), cell(d.symbol), dec(d.qty), money(d.proceedsBrl), money(d.costBrl), money(d.gainBrl), if (d.estimated) "sim" else "não", d.hash ?: "").joinToString(";"))
        }
        b.appendLine()

        b.appendLine("RENDIMENTOS DE STAKING")
        b.appendLine(listOf("Data","Ativo","Quantidade","Valor (R$)","Estimado","Hash").joinToString(";"))
        for (r in report.stakingIncome) {
            b.appendLine(listOf(r.date.toString(), cell(r.symbol), dec(r.qty), money(r.valueBrl), if (r.estimated) "sim" else "não", r.hash ?: "").joinToString(";"))
        }
        b.appendLine()
        b.appendLine("NOTAS")
        report.notes.forEach { b.appendLine(cell(it)) }
        b.appendLine()
        b.appendLine("FONTES DAS REGRAS")
        report.rules.sources.forEach { b.appendLine(cell(it)) }
        return b.toString()
    }
}
