package com.coinportracker.portfolio

import com.coinportracker.core.Fmt
import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import com.coinportracker.transactions.FeeTreatment
import com.coinportracker.transactions.FlowKind
import com.coinportracker.transactions.LedgerResult
import java.math.BigDecimal

/** Linha de auditoria: rótulo, valor formatado e, opcionalmente, a operação de origem. */
data class ExplainLine(val label: String, val value: String, val txId: Long? = null, val emphasis: Boolean = false)

data class Explanation(val title: String, val formula: String, val lines: List<ExplainLine>, val notes: List<String>)

/** Gera o detalhamento ("como este valor foi calculado") de cada indicador. */
class Explainer(private val ledger: LedgerResult, private val pnl: PnlSummary, private val symbolOf: (String) -> String) {
    private val c: Currency = pnl.currency
    private fun m(v: BigDecimal?) = Fmt.money(v, c)
    private val methodNote = "Método de custo: ${ledger.method.label}. Moeda do relatório: ${c.label}."

    fun equity() = Explanation(
        "Patrimônio atual", "Σ (quantidade × cotação atual) de cada ativo",
        pnl.assets.filter { it.quantity.signum() > 0 }.map {
            ExplainLine("${symbolOf(it.assetId)}: ${Fmt.qty(it.quantity)} × ${Fmt.price(it.price, c)}", m(it.marketValue))
        } + ExplainLine("Total", m(pnl.equity), emphasis = true),
        listOfNotNull(
            if (!pnl.equityComplete) "Atenção: há ativos sem cotação; eles não entram no total (patrimônio parcial)." else null,
            "Cotações vêm do provedor configurado; veja a fonte e o horário na tela de cada ativo."
        )
    )

    fun contributions(): Explanation {
        val lines = ledger.capitalFlows.sortedBy { it.timestamp }.map {
            ExplainLine("${Fmt.date(it.timestamp)} · ${if (it.kind == FlowKind.CONTRIBUTION) "Aporte" else "Retirada"} (${symbolOf(it.assetId)})",
                Fmt.signedMoney(it.signed, c), it.txId)
        }
        return Explanation(
            "Capital líquido aportado", "Aportes (compras, depósitos, taxas pagas em dinheiro) − retiradas (vendas e saques)",
            lines + listOf(
                ExplainLine("Total aportado", m(pnl.contributions)),
                ExplainLine("Total retirado", m(pnl.withdrawals)),
                ExplainLine("Aporte líquido", m(pnl.netContributions), emphasis = true)
            ),
            listOf(
                "Transferências internas não são aportes.",
                "Swaps não são aportes: trocam um ativo por outro já existente no portfólio.",
                "Rendimentos (staking, recompensas, airdrops) não são aportes; aparecem em Rendimentos.",
                methodNote
            )
        )
    }

    fun costBasis(assetId: String? = null): Explanation {
        val positions = ledger.positions.values.filter { (assetId == null || it.assetId == assetId) && it.quantity.signum() > 0 }
        val lines = positions.flatMap { pos ->
            val header = ExplainLine("${symbolOf(pos.assetId)} — ${Fmt.qty(pos.quantity)} un. · preço médio ${Fmt.price(pos.avgCost, c)}", m(pos.costBasis), emphasis = true)
            val lots = if (ledger.method == CostMethod.FIFO) pos.openLots.map {
                ExplainLine("  Lote de ${Fmt.date(it.acquiredAt)}: ${Fmt.qty(it.quantity)} × ${Fmt.price(it.unitCost, c)}", m(it.cost), it.txId)
            } else emptyList()
            listOf(header) + lots
        }
        return Explanation(
            "Custo de aquisição das posições atuais",
            if (ledger.method == CostMethod.FIFO) "Soma do custo dos lotes ainda abertos (FIFO)" else "Custo total das compras − custo médio × quantidades baixadas",
            lines + ExplainLine("Total", m(positions.fold(BigDecimal.ZERO) { a, p -> a.add(p.costBasis) }), emphasis = true),
            listOf("Taxas de compra são incorporadas ao custo.", "Preço médio = custo da posição ÷ quantidade.", methodNote)
        )
    }

    fun realized(assetId: String? = null): Explanation {
        val ev = ledger.realizedEvents.filter { assetId == null || it.assetId == assetId }
        val lines = ev.map {
            ExplainLine("${Fmt.date(it.timestamp)} · ${it.type.label} ${Fmt.qty(it.quantity)} ${symbolOf(it.assetId)}: receita ${m(it.proceeds)} − custo ${m(it.costBasis)}",
                Fmt.signedMoney(it.gain, c), it.txId)
        }
        val profit = ev.filter { it.gain.signum() > 0 }.fold(BigDecimal.ZERO) { a, e -> a.add(e.gain) }
        val loss = ev.filter { it.gain.signum() < 0 }.fold(BigDecimal.ZERO) { a, e -> a.add(e.gain) }
        return Explanation(
            "Resultado realizado", "Σ (receita líquida da venda/swap − custo das unidades baixadas)",
            lines + listOf(ExplainLine("Lucro realizado", Fmt.signedMoney(profit, c)), ExplainLine("Prejuízo realizado", Fmt.signedMoney(loss, c)),
                ExplainLine("Líquido", Fmt.signedMoney(profit.add(loss), c), emphasis = true)),
            listOf("Taxas de venda são deduzidas da receita.", "Em swaps, a receita é o valor de mercado informado do ativo entregue.", methodNote)
        )
    }

    fun unrealized(assetId: String? = null): Explanation {
        val rows = pnl.assets.filter { (assetId == null || it.assetId == assetId) && it.quantity.signum() > 0 }
        return Explanation(
            "Resultado não realizado", "Σ (valor de mercado − custo de aquisição) das posições abertas",
            rows.map { ExplainLine("${symbolOf(it.assetId)}: ${m(it.marketValue)} − ${m(it.costBasis)}", Fmt.signedMoney(it.unrealized, c)) } +
                listOf(ExplainLine("Lucro não realizado", Fmt.signedMoney(pnl.unrealizedProfit, c)),
                    ExplainLine("Prejuízo não realizado", Fmt.signedMoney(pnl.unrealizedLoss, c)),
                    ExplainLine("Líquido", Fmt.signedMoney(pnl.unrealizedNet, c), emphasis = true)),
            listOf("Valor não realizado muda com a cotação e não representa dinheiro recebido.", methodNote)
        )
    }

    fun fees() = Explanation(
        "Taxas acumuladas", "Σ taxas, classificadas pelo tratamento contábil",
        ledger.feeEvents.sortedBy { it.timestamp }.map {
            ExplainLine("${Fmt.date(it.timestamp)} · ${it.treatment.label}${if (it.paidInCrypto) " (em cripto, pelo custo)" else ""}", m(it.amount), it.txId)
        } + listOf(
            ExplainLine("Incorporadas ao custo", m(ledger.feeEvents.filter { it.treatment == FeeTreatment.CAPITALIZED }.fold(BigDecimal.ZERO) { a, f -> a.add(f.amount) })),
            ExplainLine("Deduzidas de vendas", m(ledger.feeEvents.filter { it.treatment == FeeTreatment.DEDUCTED }.fold(BigDecimal.ZERO) { a, f -> a.add(f.amount) })),
            ExplainLine("Despesas", m(pnl.feesExpensed)),
            ExplainLine("Total", m(pnl.feesTotal), emphasis = true)
        ),
        listOf("Percentual sobre o capital aportado: ${Fmt.pct(pnl.feesOverContributionPct, false)}.")
    )

    fun income() = Explanation(
        "Rendimentos recebidos", "Σ (quantidade recebida × cotação na data do recebimento)",
        ledger.incomeEvents.sortedBy { it.timestamp }.map {
            ExplainLine("${Fmt.date(it.timestamp)} · ${it.type.label} ${Fmt.qty(it.quantity)} ${symbolOf(it.assetId)}${if (!it.valueKnown) " (sem preço)" else ""}", m(it.value), it.txId)
        } + ExplainLine("Total", m(pnl.income), emphasis = true),
        listOf("Rendimentos são valores efetivamente registrados, não APY estimado.", "Recompensas sem preço informado contam como zero.")
    )

    fun total() = Explanation(
        "Resultado total", "Realizado + não realizado + rendimentos − taxas como despesa",
        listOf(
            ExplainLine("Realizado", Fmt.signedMoney(pnl.realizedNet, c)),
            ExplainLine("Não realizado", Fmt.signedMoney(pnl.unrealizedNet, c)),
            ExplainLine("Rendimentos", Fmt.signedMoney(pnl.income, c)),
            ExplainLine("Taxas como despesa", Fmt.signedMoney(pnl.feesExpensed.negate(), c)),
            ExplainLine("Resultado total", Fmt.signedMoney(pnl.totalResult, c), emphasis = true),
            ExplainLine("Conferência: patrimônio − aporte líquido", Fmt.signedMoney(pnl.equity.subtract(pnl.netContributions), c)),
            ExplainLine("Variação sobre o capital aportado", Fmt.pct(pnl.totalReturnPct)),
        ),
        listOfNotNull(
            "A conferência só coincide quando todos os ativos têm cotação e todas as operações têm preço.",
            if (!pnl.equityComplete) "Há ativos sem cotação: a conferência não fecha." else null,
            methodNote
        )
    )
}
