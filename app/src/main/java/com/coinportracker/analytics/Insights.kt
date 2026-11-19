package com.coinportracker.analytics

import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.portfolio.PnlSummary
import java.math.BigDecimal

enum class Severity(val label: String) { INFO("Informativo"), ATTENTION("Atenção"), HIGH("Risco elevado") }

data class Insight(val id: String, val severity: Severity, val title: String, val explanation: String, val evidence: List<Pair<String, String>>)

data class InsightInput(
    val pnl: PnlSummary,
    val pnlOtherCurrency: PnlSummary?,
    val bySymbol: List<Slice>,
    val byChain: List<Slice>,
    val byWallet: List<Slice>,
    val exchangeSharePct: BigDecimal?,
    val stableSharePct: BigDecimal?,
    val hhiNow: BigDecimal?,
    val hhiPast: BigDecimal?,
    val equityPast: BigDecimal?,
    val pastLabel: String,
    val maxDrawdown: Double?,
    val volatility: Double?,
    val missingPriceAssets: List<String>,
    val ledgerErrors: Int,
)

/**
 * Análise crítica local baseada em regras. Cada conclusão traz os dados que a justificam.
 * Limiares documentados; não são recomendações de investimento.
 */
object InsightEngine {
    val SINGLE_ASSET_HIGH = BigDecimal(50)
    val SINGLE_ASSET_ATTENTION = BigDecimal(35)
    val CHAIN_HIGH = BigDecimal(70)
    val EXCHANGE_HIGH = BigDecimal(50)
    val FEES_ATTENTION = BigDecimal(2)
    val UNREALIZED_SHARE = BigDecimal(60)
    val FX_DIFF_PP = BigDecimal(5)

    fun evaluate(i: InsightInput): List<Insight> {
        val out = ArrayList<Insight>()
        val c = i.pnl.currency
        i.bySymbol.firstOrNull()?.let { top ->
            if (top.pct >= SINGLE_ASSET_ATTENTION) out += Insight(
                "concentration_asset", if (top.pct >= SINGLE_ASSET_HIGH) Severity.HIGH else Severity.ATTENTION,
                "Seu patrimônio possui concentração elevada em um único ativo.",
                "${top.label} representa ${Fmt.pct(top.pct, false)} do patrimônio. Uma queda de 30% nesse ativo, isoladamente, reduziria o patrimônio em ${Fmt.pct(top.pct.multiply(BigDecimal("0.3")), false)}.",
                listOf("Ativo" to top.label, "Participação" to Fmt.pct(top.pct, false), "Valor" to Fmt.money(top.value, c), "HHI" to (i.hhiNow?.let { Fmt.num(it.toDouble(), 0) } ?: "—"))
            )
        }
        i.byChain.firstOrNull()?.let { ch ->
            if (ch.pct >= CHAIN_HIGH && i.byChain.size >= 1 && i.pnl.equity.signum() > 0) out += Insight(
                "concentration_chain", Severity.ATTENTION,
                "A maior parte da sua exposição está associada a uma única rede.",
                "${Fmt.pct(ch.pct, false)} do patrimônio está em ativos da rede ${ch.label}. Falhas, congestionamento ou eventos regulatórios dessa rede afetariam a maior parte do portfólio.",
                i.byChain.take(4).map { it.label to Fmt.pct(it.pct, false) }
            )
        }
        if (i.hhiNow != null && i.hhiPast != null && i.equityPast != null && i.equityPast.signum() > 0) {
            val growth = Decimals.pct(i.pnl.equity.subtract(i.equityPast), i.equityPast)
            if (growth != null && growth.signum() > 0 && i.hhiNow > i.hhiPast.multiply(BigDecimal("1.1"))) out += Insight(
                "growth_concentration", Severity.ATTENTION,
                "O crescimento do patrimônio está sendo acompanhado por aumento da concentração.",
                "Desde ${i.pastLabel} o patrimônio variou ${Fmt.pct(growth)} enquanto o HHI subiu de ${Fmt.num(i.hhiPast.toDouble(), 0)} para ${Fmt.num(i.hhiNow.toDouble(), 0)}: a alta veio principalmente de poucos ativos.",
                listOf("Patrimônio antes" to Fmt.money(i.equityPast, c), "Patrimônio agora" to Fmt.money(i.pnl.equity, c), "HHI antes" to Fmt.num(i.hhiPast.toDouble(), 0), "HHI agora" to Fmt.num(i.hhiNow.toDouble(), 0))
            )
        }
        val pos = i.pnl.totalResult
        if (pos.signum() > 0 && i.pnl.unrealizedNet.signum() > 0) {
            val share = Decimals.pct(i.pnl.unrealizedNet, pos)
            if (share != null && share >= UNREALIZED_SHARE) out += Insight(
                "unrealized_share", Severity.INFO,
                "Uma parcela relevante do seu resultado positivo não foi realizada.",
                "${Fmt.pct(share.min(Decimals.HUNDRED), false)} do resultado total é não realizado: depende das cotações atuais e pode desaparecer com uma queda de mercado.",
                listOf("Resultado total" to Fmt.signedMoney(pos, c), "Não realizado" to Fmt.signedMoney(i.pnl.unrealizedNet, c), "Realizado" to Fmt.signedMoney(i.pnl.realizedNet, c))
            )
        }
        val other = i.pnlOtherCurrency
        val r1 = i.pnl.totalReturnPct
        val r2 = other?.totalReturnPct
        if (other != null && r1 != null && r2 != null && r1.subtract(r2).abs() >= FX_DIFF_PP) out += Insight(
            "fx_effect", Severity.INFO,
            "Seu resultado em ${c.code.uppercase()} foi influenciado pela variação cambial.",
            "A rentabilidade sobre o capital aportado é ${Fmt.pct(r1)} em ${c.code.uppercase()} e ${Fmt.pct(r2)} em ${other.currency.code.uppercase()}. A diferença de ${Fmt.pct(r1.subtract(r2).abs(), false)} p.p. vem do câmbio BRL/USD entre as datas dos aportes e hoje.",
            listOf("Rentab. ${c.code.uppercase()}" to Fmt.pct(r1), "Rentab. ${other.currency.code.uppercase()}" to Fmt.pct(r2))
        )
        i.pnl.feesOverContributionPct?.let { fp ->
            if (fp >= FEES_ATTENTION) out += Insight(
                "fees", Severity.ATTENTION,
                "As taxas acumuladas representam uma parcela significativa do capital investido.",
                "Você pagou ${Fmt.money(i.pnl.feesTotal, c)} em taxas, ${Fmt.pct(fp, false)} de tudo que aportou. Operações frequentes ou valores pequenos amplificam esse custo.",
                listOf("Taxas" to Fmt.money(i.pnl.feesTotal, c), "Capital aportado" to Fmt.money(i.pnl.contributions, c), "Proporção" to Fmt.pct(fp, false))
            )
        }
        i.exchangeSharePct?.let { ex ->
            if (ex >= EXCHANGE_HIGH) out += Insight(
                "exchange_dependency", Severity.ATTENTION,
                "Grande parte do patrimônio está custodiada em exchanges.",
                "${Fmt.pct(ex, false)} do patrimônio está em carteiras do tipo Exchange, sujeito ao risco de contraparte da corretora.",
                i.byWallet.take(4).map { it.label to Fmt.pct(it.pct, false) }
            )
        }
        i.stableSharePct?.let { st ->
            if (i.pnl.equity.signum() > 0) out += Insight(
                "stable_exposure", Severity.INFO,
                "Exposição a stablecoins: ${Fmt.pct(st, false)}.",
                if (st.signum() == 0) "Não há reserva em stablecoins: toda a carteira está exposta à volatilidade de criptoativos."
                else "Stablecoins reduzem a volatilidade, mas carregam risco do emissor e de perda de paridade.",
                listOf("Stablecoins" to Fmt.pct(st, false))
            )
        }
        i.maxDrawdown?.let { dd ->
            if (dd <= -0.4) out += Insight(
                "drawdown", Severity.HIGH, "O portfólio já sofreu forte queda no histórico registrado.",
                "O drawdown máximo do patrimônio foi ${Fmt.pctD(dd)}. Desempenho passado não garante resultados futuros, mas indica a magnitude de quedas já vividas.",
                listOf("Drawdown máximo" to Fmt.pctD(dd), "Volatilidade anual" to Fmt.pctD(i.volatility, false))
            )
        }
        if (i.missingPriceAssets.isNotEmpty()) out += Insight(
            "missing_prices", Severity.ATTENTION, "Há ativos sem cotação: patrimônio e análises estão parciais.",
            "Os ativos ${i.missingPriceAssets.joinToString()} não possuem cotação disponível. Cadastre um preço manual ou vincule-os a um identificador do provedor.",
            i.missingPriceAssets.map { it to "sem cotação" }
        )
        if (i.ledgerErrors > 0) out += Insight(
            "ledger_errors", Severity.HIGH, "O livro de operações tem inconsistências.",
            "${i.ledgerErrors} operação(ões) com erro (ex.: venda acima do saldo, compra sem preço). Os resultados afetados não são confiáveis até a correção.",
            listOf("Erros" to i.ledgerErrors.toString())
        )
        return out.sortedByDescending { it.severity.ordinal }
    }
}
