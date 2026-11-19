package com.coinportracker.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.coinportracker.core.Fmt
import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import com.coinportracker.portfolio.PnlCalculator
import com.coinportracker.portfolio.PnlSummary
import com.coinportracker.transactions.CsvCodec
import com.coinportracker.transactions.LedgerEngine
import com.coinportracker.transactions.LedgerResult
import com.coinportracker.transactions.TxRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

data class ReportFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val currency: Currency = Currency.BRL,
    val walletIds: Set<Long>? = null,
    val assetIds: Set<String>? = null,
    val method: CostMethod = CostMethod.WEIGHTED_AVERAGE,
)

enum class ReportKind(val label: String) {
    PORTFOLIO("Portfólio completo (posições, custo, P&L, alocação)"),
    TRANSACTIONS("Histórico de transações"),
    REALIZED("Lucros/prejuízos realizados"),
    INCOME_FEES("Rendimentos e taxas"),
    PERFORMANCE("Desempenho histórico (patrimônio diário)"),
}

@Serializable
data class JsonReport(
    val format: String = "coinportracker.report.v1",
    val generatedAt: Long, val currency: String, val method: String, val period: String, val filters: String,
    val sources: List<String>, val missing: List<String>,
    val summary: Map<String, String?>, val positions: List<Map<String, String?>>, val realized: List<Map<String, String?>>,
    val income: List<Map<String, String?>>, val fees: List<Map<String, String?>>, val transactions: List<TxRecord>,
    val dailyEquity: List<Map<String, String?>>,
)

/** Relatórios gerados 100% no aparelho a partir dos dados locais. */
class ReportGenerator {
    private fun m(v: BigDecimal?) = v?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString()

    data class Computed(val ledger: LedgerResult, val pnl: PnlSummary, val records: List<TxRecord>, val missing: List<String>, val periodLabel: String, val filterLabel: String)

    fun compute(s: PortfolioState, f: ReportFilter): Computed {
        val txs = s.ledgerTxs.filter { t ->
            (f.toMs == null || t.timestamp <= f.toMs) &&
                (f.assetIds == null || t.assetId in f.assetIds || t.toAssetId in f.assetIds) &&
                (f.walletIds == null || t.fromWalletId in f.walletIds || t.toWalletId in f.walletIds)
        }
        val fx = s.latestFx
        val ledger = LedgerEngine(f.method, f.currency, s.fxFallback).run(txs)
        val prices = PortfolioService.resolvePrices(s.assets.values, s.prices.values.mapNotNull { it.quote }, f.currency, fx)
        val pnl = PnlCalculator.summarize(ledger, prices.mapValues { it.value.price })
        val ids = txs.map { it.id }.toSet()
        val records = s.ledgerTxs.filter { it.id in ids && (f.fromMs == null || it.timestamp >= f.fromMs) }.map { t ->
            TxRecord(t.timestamp, t.type.name, s.symbol(t.assetId), t.quantity.toPlainString(), t.unitPrice?.toPlainString(), t.quoteCurrency.name,
                t.toAssetId?.let(s::symbol), t.toQuantity?.toPlainString(), t.feeFiat?.toPlainString(), t.feeAssetId?.let(s::symbol), t.feeQuantity?.toPlainString(),
                t.fromWalletId?.let { s.wallets[it]?.name }, t.toWalletId?.let { s.wallets[it]?.name }, fxUsdBrl = t.fxUsdBrl?.toPlainString(), declaredCost = t.declaredCost?.toPlainString())
        }
        val missing = pnl.assets.filter { it.missingPrice }.map { "Sem cotação: ${s.symbol(it.assetId)}" } +
            ledger.issues.filter { it.severity.name != "INFO" }.map { "Operação #${it.txId}: ${it.message}" }
        val period = "${f.fromMs?.let { Fmt.date(it) } ?: "início"} a ${f.toMs?.let { Fmt.date(it) } ?: "hoje"}"
        val filt = listOfNotNull(f.walletIds?.let { w -> "carteiras: " + w.joinToString { s.wallets[it]?.name ?: "?" } }, f.assetIds?.let { a -> "ativos: " + a.joinToString { s.symbol(it) } }).joinToString("; ").ifEmpty { "todas as carteiras e ativos" }
        return Computed(ledger, pnl, records, missing, period, filt)
    }

    private fun inPeriod(ts: Long, f: ReportFilter) = (f.fromMs == null || ts >= f.fromMs) && (f.toMs == null || ts <= f.toMs)

    fun csv(kind: ReportKind, s: PortfolioState, f: ReportFilter): String {
        val c = compute(s, f)
        val header = "# CoinPortracker — ${kind.label}\n# Gerado em ${Fmt.dateTime(System.currentTimeMillis())} | Moeda ${f.currency} | Método ${f.method.label} | Período ${c.periodLabel} | Filtros: ${c.filterLabel}\n# Fontes de preço: ${s.sources.joinToString().ifEmpty { "nenhuma" }}\n" +
            c.missing.joinToString("") { "# AUSENTE: $it\n" }
        val body = when (kind) {
            ReportKind.TRANSACTIONS -> CsvCodec.export(c.records)
            ReportKind.PORTFOLIO -> CsvCodec.table(
                listOf("ativo", "quantidade", "preco_medio", "custo_aquisicao", "cotacao", "valor_mercado", "nao_realizado", "nao_realizado_pct", "realizado", "rendimentos", "participacao_pct", "fonte_cotacao"),
                c.pnl.assets.map { a -> listOf(s.symbol(a.assetId), a.quantity.toPlainString(), m(a.avgCost), m(a.costBasis), a.price?.toPlainString(), m(a.marketValue), m(a.unrealized), m(a.unrealizedPct), m(a.realized), m(a.income), m(a.sharePct), s.prices[a.assetId]?.source) }
            ) + CsvCodec.table(listOf("indicador", "valor"), summaryRows(c.pnl))
            ReportKind.REALIZED -> CsvCodec.table(listOf("data", "operacao_id", "tipo", "ativo", "quantidade", "receita", "custo", "resultado"),
                c.ledger.realizedEvents.filter { inPeriod(it.timestamp, f) }.map { listOf(Fmt.dateTime(it.timestamp), it.txId.toString(), it.type.label, s.symbol(it.assetId), it.quantity.toPlainString(), m(it.proceeds), m(it.costBasis), m(it.gain)) })
            ReportKind.INCOME_FEES -> CsvCodec.table(listOf("data", "operacao_id", "categoria", "ativo", "quantidade", "valor", "tratamento"),
                c.ledger.incomeEvents.filter { inPeriod(it.timestamp, f) }.map { listOf(Fmt.dateTime(it.timestamp), it.txId.toString(), "Rendimento: " + it.type.label, s.symbol(it.assetId), it.quantity.toPlainString(), m(it.value), if (it.valueKnown) "valor de mercado" else "sem preço (zero)") } +
                    c.ledger.feeEvents.filter { inPeriod(it.timestamp, f) }.map { listOf(Fmt.dateTime(it.timestamp), it.txId.toString(), "Taxa", it.assetId?.let(s::symbol), null, m(it.amount), it.treatment.label) })
            ReportKind.PERFORMANCE -> CsvCodec.table(listOf("data", "patrimonio", "aporte_liquido", "fluxo_dia", "ativos_sem_cotacao"),
                s.history.filter { f.fromMs == null || it.day.toEpochDay() * 86_400_000L >= f.fromMs }.map { listOf(it.day.toString(), m(it.equity), m(it.netContributions), m(it.flow), it.missingAssets.joinToString(" ") { a -> s.symbol(a) }) })
        }
        return header + body
    }

    private fun summaryRows(p: PnlSummary) = listOf(
        listOf("patrimonio", m(p.equity)), listOf("patrimonio_completo", p.equityComplete.toString()), listOf("aportes", m(p.contributions)),
        listOf("retiradas", m(p.withdrawals)), listOf("aporte_liquido", m(p.netContributions)), listOf("custo_posicoes", m(p.openCostBasis)),
        listOf("lucro_realizado", m(p.realizedProfit)), listOf("prejuizo_realizado", m(p.realizedLoss)), listOf("lucro_nao_realizado", m(p.unrealizedProfit)),
        listOf("prejuizo_nao_realizado", m(p.unrealizedLoss)), listOf("rendimentos", m(p.income)), listOf("taxas_total", m(p.feesTotal)),
        listOf("taxas_despesa", m(p.feesExpensed)), listOf("resultado_total", m(p.totalResult)), listOf("rentabilidade_pct", m(p.totalReturnPct)),
    )

    fun json(s: PortfolioState, f: ReportFilter): String {
        val c = compute(s, f)
        val r = JsonReport(
            generatedAt = System.currentTimeMillis(), currency = f.currency.name, method = f.method.name, period = c.periodLabel, filters = c.filterLabel,
            sources = s.sources.toList(), missing = c.missing, summary = summaryRows(c.pnl).associate { it[0]!! to it[1] },
            positions = c.pnl.assets.map { a -> mapOf("asset" to s.symbol(a.assetId), "quantity" to a.quantity.toPlainString(), "avgCost" to m(a.avgCost), "costBasis" to m(a.costBasis), "price" to a.price?.toPlainString(), "marketValue" to m(a.marketValue), "unrealized" to m(a.unrealized), "realized" to m(a.realized), "income" to m(a.income), "sharePct" to m(a.sharePct), "priceSource" to s.prices[a.assetId]?.source) },
            realized = c.ledger.realizedEvents.filter { inPeriod(it.timestamp, f) }.map { mapOf("timestamp" to it.timestamp.toString(), "txId" to it.txId.toString(), "asset" to s.symbol(it.assetId), "quantity" to it.quantity.toPlainString(), "proceeds" to m(it.proceeds), "cost" to m(it.costBasis), "gain" to m(it.gain)) },
            income = c.ledger.incomeEvents.filter { inPeriod(it.timestamp, f) }.map { mapOf("timestamp" to it.timestamp.toString(), "asset" to s.symbol(it.assetId), "type" to it.type.name, "quantity" to it.quantity.toPlainString(), "value" to m(it.value)) },
            fees = c.ledger.feeEvents.filter { inPeriod(it.timestamp, f) }.map { mapOf("timestamp" to it.timestamp.toString(), "asset" to it.assetId?.let(s::symbol), "amount" to m(it.amount), "treatment" to it.treatment.name) },
            transactions = c.records,
            dailyEquity = s.history.map { mapOf("day" to it.day.toString(), "equity" to m(it.equity), "netContributions" to m(it.netContributions)) },
        )
        return Json { prettyPrint = true }.encodeToString(r)
    }

    /** PDF A4 paginado, com metodologia, fontes e informações ausentes. */
    fun pdf(s: PortfolioState, f: ReportFilter): ByteArray {
        val c = compute(s, f)
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 40f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(10, 20, 50) }
        val h2 = Paint().apply { textSize = 12.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(30, 60, 140) }
        val body = Paint().apply { textSize = 9f; color = Color.rgb(20, 20, 30); isAntiAlias = true }
        val small = Paint().apply { textSize = 7.5f; color = Color.rgb(90, 90, 100) }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
            page!!.canvas.drawText("CoinPortracker · relatório gerado no aparelho · página $pageNo", M, H - 20f, small)
        }
        fun line(text: String, p: Paint = body, dy: Float = p.textSize + 4f) {
            if (page == null || y + dy > H - 40) newPage()
            page!!.canvas.drawText(text.take(130), M, y + p.textSize, p); y += dy
        }
        fun cols(values: List<String>, widths: List<Float>, p: Paint = body) {
            if (page == null || y + 13 > H - 40) newPage()
            var x = M
            values.forEachIndexed { i, v -> page!!.canvas.drawText(v.take((widths[i] / 4.6f).toInt()), x, y + p.textSize, p); x += widths[i] }
            y += 13f
        }
        newPage()
        val cur = f.currency
        line("Relatório de portfólio", title, 26f)
        line("Gerado em ${Fmt.dateTime(System.currentTimeMillis())} · Moeda: ${cur.label} · Método de custo: ${f.method.label}", small)
        line("Período: ${c.periodLabel} · Filtros: ${c.filterLabel}", small)
        line("Fontes de preço: ${s.sources.joinToString().ifEmpty { "nenhuma (sem cotações)" }} · Última cotação: ${Fmt.dateTime(s.lastQuoteAt)}", small, 18f)
        line("Resumo", h2, 18f)
        val p = c.pnl
        listOf("Patrimônio" to Fmt.money(p.equity, cur) + if (!p.equityComplete) " (parcial)" else "", "Aporte líquido" to Fmt.money(p.netContributions, cur),
            "Aportes / Retiradas" to "${Fmt.money(p.contributions, cur)} / ${Fmt.money(p.withdrawals, cur)}", "Custo das posições" to Fmt.money(p.openCostBasis, cur),
            "Realizado (lucro / prejuízo)" to "${Fmt.signedMoney(p.realizedProfit, cur)} / ${Fmt.signedMoney(p.realizedLoss, cur)}",
            "Não realizado (lucro / prejuízo)" to "${Fmt.signedMoney(p.unrealizedProfit, cur)} / ${Fmt.signedMoney(p.unrealizedLoss, cur)}",
            "Rendimentos" to Fmt.money(p.income, cur), "Taxas (total / despesa)" to "${Fmt.money(p.feesTotal, cur)} / ${Fmt.money(p.feesExpensed, cur)}",
            "Resultado total" to Fmt.signedMoney(p.totalResult, cur), "Rentabilidade s/ aportes" to Fmt.pct(p.totalReturnPct)
        ).forEach { (k, v) -> cols(listOf(k, v), listOf(200f, 300f)) }
        y += 8
        line("Posições", h2, 18f)
        val w = listOf(55f, 75f, 70f, 75f, 70f, 80f, 45f)
        cols(listOf("Ativo", "Quantidade", "Preço médio", "Custo", "Cotação", "Não realiz.", "%"), w, small)
        p.assets.filter { it.quantity.signum() > 0 }.forEach { a ->
            cols(listOf(s.symbol(a.assetId), Fmt.qty(a.quantity), Fmt.price(a.avgCost, cur), Fmt.money(a.costBasis, cur), Fmt.price(a.price, cur), Fmt.signedMoney(a.unrealized, cur), Fmt.pct(a.sharePct, false)), w)
        }
        y += 8
        line("Resultados realizados", h2, 18f)
        val w2 = listOf(65f, 60f, 50f, 70f, 85f, 85f, 85f)
        cols(listOf("Data", "Tipo", "Ativo", "Qtd.", "Receita", "Custo", "Resultado"), w2, small)
        c.ledger.realizedEvents.filter { inPeriod(it.timestamp, f) }.forEach { e ->
            cols(listOf(Fmt.date(e.timestamp), e.type.label, s.symbol(e.assetId), Fmt.qty(e.quantity), Fmt.money(e.proceeds, cur), Fmt.money(e.costBasis, cur), Fmt.signedMoney(e.gain, cur)), w2)
        }
        y += 8
        line("Metodologia", h2, 18f)
        listOf(
            "Valores calculados com aritmética decimal exata (BigDecimal, 34 dígitos), arredondados a 2 casas apenas na apresentação.",
            "Taxas de compra integram o custo; taxas de venda reduzem a receita; demais taxas são despesas.",
            "Transferências internas não são aportes; swaps não são aportes e realizam o resultado pelo valor de mercado informado.",
            "Rendimentos entram pelo valor de mercado na data do recebimento. Conversões de moeda usam o câmbio registrado na operação.",
            "Resultado total = realizado + não realizado + rendimentos − taxas como despesa. Não é recomendação de investimento.",
        ).forEach { line("• $it", small) }
        if (c.missing.isNotEmpty()) {
            y += 8
            line("Informações ausentes ou inconsistentes", h2, 18f)
            c.missing.take(80).forEach { line("• $it", small) }
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
