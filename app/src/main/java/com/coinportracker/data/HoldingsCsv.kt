package com.coinportracker.data

import java.math.BigDecimal
import java.math.RoundingMode

/** Exporta a posição atual (uma linha por ativo × carteira × rede) com preço médio e lucro, em CSV (separador ';', decimal '.'). */
object HoldingsCsv {
    private fun esc(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun n(v: BigDecimal?, scale: Int = 2) = v?.setScale(scale, RoundingMode.HALF_EVEN)?.toPlainString() ?: ""

    fun build(h: HubState): String {
        val cur = h.currency.name
        val sb = StringBuilder()
        sb.append("ativo;nome;carteira;rede;quantidade;preco_usd;valor_usd;valor_$cur;preco_medio_$cur;custo_$cur;lucro_$cur;lucro_pct;variacao_24h_pct;origem_custo\n")
        for (a in h.agg.assets) {
            val r = h.pnl.assets[a.key]
            for (p in a.parts) {
                val price = p.priceUsd?.let { BigDecimal.valueOf(it) }
                val value = price?.multiply(p.holding.amount)
                val cost = r?.avgCost?.multiply(p.holding.amount)
                val pnl = if (value != null && cost != null) value.subtract(cost) else null
                sb.append(listOf(
                    esc(a.symbol), esc(a.name), esc(p.source.walletName), if (p.source.manual) "Manual" else p.holding.network.label,
                    p.holding.amount.stripTrailingZeros().toPlainString(), n(price, 8), n(value), n(h.conv(value)), n(h.conv(r?.avgCost), 8), n(h.conv(cost)), n(h.conv(pnl)),
                    n(r?.pnlPct), a.change24h?.let { n(BigDecimal.valueOf(it)) } ?: "", r?.source?.name ?: "NONE",
                ).joinToString(";")).append('\n')
            }
        }
        val t = h.pnl
        sb.append('\n').append("resumo;valor_$cur\n")
        listOf("patrimonio" to h.conv(h.agg.totalUsd), "custo_aberto" to h.conv(t.openCost), "lucro_nao_realizado" to h.conv(t.unrealized),
            "lucro_realizado" to h.conv(t.realized), "rendimentos" to h.conv(t.income), "lucro_total" to h.conv(t.total)).forEach { (k, v) -> sb.append("$k;${n(v)}\n") }
        sb.append("lucro_total_pct;${n(t.totalPct)}\n")
        return sb.toString()
    }
}
