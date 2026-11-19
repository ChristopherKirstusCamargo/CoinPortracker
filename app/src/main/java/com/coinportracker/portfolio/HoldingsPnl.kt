package com.coinportracker.portfolio

import com.coinportracker.core.Decimals
import com.coinportracker.onchain.AssetGroup
import java.math.BigDecimal
import java.math.RoundingMode

/** De onde veio o preço médio de um ativo. */
enum class CostSource(val label: String) { USER("Informado por você"), LEDGER("Operações registradas"), ESTIMATED("Estimado pela blockchain"), NONE("Custo não definido"),
    /** v5: rateio do total pago («Preço do portfólio»); sempre estimado. */
    ALLOCATED("Rateio do total pago") }

data class AssetResult(
    val groupKey: String,
    val value: BigDecimal,
    val avgCost: BigDecimal?,
    val cost: BigDecimal?,
    val pnl: BigDecimal?,
    val pnlPct: BigDecimal?,
    val source: CostSource,
    /** Estimativa automática (quando existe), com confiança e origem. */
    val estimate: com.coinportracker.history.CostEstimate? = null,
)

data class BucketResult(val key: String, val value: BigDecimal, val cost: BigDecimal, val pnl: BigDecimal, val valueWithoutCost: BigDecimal)

data class HoldingsResult(
    val assets: Map<String, AssetResult>,
    val byWallet: Map<String, BucketResult>,
    val byNetwork: Map<String, BucketResult>,
    val value: BigDecimal,
    val openCost: BigDecimal,
    val unrealized: BigDecimal,
    val realized: BigDecimal,
    val income: BigDecimal,
    val total: BigDecimal,
    /** Resultado total ÷ (custo das posições abertas + custo das vendas realizadas). */
    val totalPct: BigDecimal?,
    val valueWithoutCost: BigDecimal,
    val missingCost: Int,
)

/**
 * Lucro/prejuízo do portfólio combinado (carteiras on-chain + manuais), em BigDecimal.
 * Não realizado por ativo = valor atual − quantidade × preço médio. O preço médio vem, nesta ordem:
 * do valor informado pelo usuário para o ativo; do livro de operações (custo médio ponderado ou FIFO, conforme Ajustes).
 * Ativos sem custo conhecido entram no patrimônio, mas não no P&L (contados em [HoldingsResult.missingCost]).
 * Realizado e rendimentos vêm do livro de operações. Todos os valores na mesma moeda (USD internamente).
 */
object HoldingsPnlCalc {
    private val MC = Decimals.MC

    fun compute(
        groups: List<AssetGroup>,
        userAvgCost: Map<String, BigDecimal>,
        ledgerAvgCost: Map<String, BigDecimal>,
        realized: BigDecimal,
        realizedCostBasis: BigDecimal,
        income: BigDecimal,
        estimated: Map<String, com.coinportracker.history.CostEstimate> = emptyMap(),
        investedUsd: BigDecimal? = null,
        yieldUsd: Map<String, BigDecimal> = emptyMap(),
    ): HoldingsResult {
        if (investedUsd != null && investedUsd.signum() > 0) return allocate(groups, investedUsd, estimated, yieldUsd)
        var realized = realized; var income = income
        val usedEst = HashSet<String>()
        val assets = LinkedHashMap<String, AssetResult>()
        val wallets = HashMap<String, Acc>()
        val nets = HashMap<String, Acc>()
        var value = BigDecimal.ZERO; var cost = BigDecimal.ZERO; var unreal = BigDecimal.ZERO; var noCost = BigDecimal.ZERO; var missing = 0
        for (g in groups) {
            val alias = com.coinportracker.onchain.Aggregator.costAlias(g.key)
            val est = estimated[alias ?: g.key] ?: estimated[g.key]
            val (avg, src) = (userAvgCost[g.key] ?: alias?.let { userAvgCost[it] })?.let { it to CostSource.USER }
                ?: (ledgerAvgCost[g.key] ?: alias?.let { ledgerAvgCost[it] })?.let { it to CostSource.LEDGER }
                ?: est?.takeIf { it.avgUsd > 0 }?.let { BigDecimal.valueOf(it.avgUsd) to CostSource.ESTIMATED } ?: (null to CostSource.NONE)
            if (src == CostSource.ESTIMATED && est != null && usedEst.add(est.costGroup)) {
                realized = realized.add(BigDecimal.valueOf(est.realizedUsd), MC); income = income.add(BigDecimal.valueOf(est.incomeUsd), MC)
            }
            var gValue = BigDecimal.ZERO; var gCost = BigDecimal.ZERO
            for (p in g.parts) {
                val price = p.priceUsd ?: continue
                val v = p.holding.amount.multiply(BigDecimal.valueOf(price), MC)
                val c = avg?.let { p.holding.amount.multiply(it, MC) }
                gValue = gValue.add(v, MC)
                if (c != null) gCost = gCost.add(c, MC)
                val wk = p.source.walletId.toString()
                val nk = if (p.source.manual) "MANUAL" else p.holding.network.name
                wallets.getOrPut(wk) { Acc() }.add(v, c)
                nets.getOrPut(nk) { Acc() }.add(v, c)
            }
            value = value.add(gValue, MC)
            if (avg != null) {
                val pnl = gValue.subtract(gCost, MC)
                cost = cost.add(gCost, MC); unreal = unreal.add(pnl, MC)
                assets[g.key] = AssetResult(g.key, gValue, avg, gCost, pnl, Decimals.pct(pnl, gCost), src, est)
            } else {
                missing++; noCost = noCost.add(gValue, MC)
                assets[g.key] = AssetResult(g.key, gValue, null, null, null, null, CostSource.NONE, est)
            }
        }
        val total = unreal.add(realized, MC).add(income, MC)
        val invested = cost.add(realizedCostBasis, MC)
        return HoldingsResult(assets, wallets.mapValues { it.value.result(it.key) }, nets.mapValues { it.value.result(it.key) }, value, cost, unreal,
            realized, income, total, if (invested.signum() > 0) total.divide(invested, MC).multiply(BigDecimal(100)).setScale(4, RoundingMode.HALF_EVEN) else null,
            noCost, missing)
    }

    /**
     * v5: com o total pago informado (a única entrada manual), o custo de cada ativo é um rateio desse total — o mesmo
     * critério do informe Contábil: peso = custo estimado na chegada (quando existe) ou o valor de mercado.
     * Lucro do ativo = valor − custo rateado − rendimento de staking do ativo, de modo que a soma por ativo é exatamente
     * o Resultado real (valor − investido − rendimentos). Marcado como [CostSource.ALLOCATED] (estimado).
     */
    private fun allocate(groups: List<AssetGroup>, investedUsd: BigDecimal, estimated: Map<String, com.coinportracker.history.CostEstimate>,
                         yieldUsd: Map<String, BigDecimal>): HoldingsResult {
        data class G(val g: AssetGroup, val value: BigDecimal, val qty: BigDecimal, val weight: BigDecimal, val est: com.coinportracker.history.CostEstimate?, val yld: BigDecimal)
        val rows = groups.map { g ->
            var v = BigDecimal.ZERO; var q = BigDecimal.ZERO
            for (p in g.parts) { val px = p.priceUsd ?: continue; if (px.isNaN() || px.isInfinite()) continue; v = v.add(p.holding.amount.multiply(BigDecimal.valueOf(px), MC), MC); q = q.add(p.holding.amount, MC) }
            val alias = com.coinportracker.onchain.Aggregator.costAlias(g.key)
            val est = estimated[alias ?: g.key] ?: estimated[g.key]
            val yld = (yieldUsd[g.key] ?: BigDecimal.ZERO).max(BigDecimal.ZERO).min(v)
            val estCost = est?.takeIf { it.avgUsd > 0 && !it.avgUsd.isNaN() && !it.avgUsd.isInfinite() }?.let { BigDecimal.valueOf(it.avgUsd).multiply(q.subtract(yld.divideSafe(v, q), MC).max(BigDecimal.ZERO), MC) }
            G(g, v, q, (estCost ?: v.subtract(yld, MC)).max(BigDecimal.ZERO), est, yld)
        }
        val sumW = rows.fold(BigDecimal.ZERO) { a, r -> a.add(r.weight, MC) }
        val assets = LinkedHashMap<String, AssetResult>()
        val wallets = HashMap<String, Acc>(); val nets = HashMap<String, Acc>()
        var value = BigDecimal.ZERO; var noCost = BigDecimal.ZERO; var missing = 0; var unreal = BigDecimal.ZERO; var cost = BigDecimal.ZERO
        val weighted = rows.filter { it.weight.signum() > 0 }
        var acc = BigDecimal.ZERO
        for (r in rows) {
            value = value.add(r.value, MC)
            val c: BigDecimal? = if (sumW.signum() <= 0 || r.weight.signum() <= 0) null
                else if (r === weighted.last()) investedUsd.subtract(acc, MC) else investedUsd.multiply(r.weight, MC).divide(sumW, MC)
            if (c == null) {
                if (r.value.signum() > 0) { missing++; noCost = noCost.add(r.value, MC) }
                assets[r.g.key] = AssetResult(r.g.key, r.value, null, null, null, null, CostSource.NONE, r.est)
            } else {
                acc = acc.add(c, MC)
                val pnl = r.value.subtract(c, MC).subtract(r.yld, MC)
                cost = cost.add(c, MC); unreal = unreal.add(pnl, MC)
                val avg = if (r.qty.signum() > 0) c.divide(r.qty, MC) else null
                assets[r.g.key] = AssetResult(r.g.key, r.value, avg, c, pnl, Decimals.pct(pnl, c), CostSource.ALLOCATED, r.est)
            }
            for (p in r.g.parts) {
                val px = p.priceUsd ?: continue
                val v = p.holding.amount.multiply(BigDecimal.valueOf(px), MC)
                // carteiras e redes continuam sem custo (como na v4.1): o rateio é por ativo
                wallets.getOrPut(p.source.walletId.toString()) { Acc() }.add(v, null)
                nets.getOrPut(if (p.source.manual) "MANUAL" else p.holding.network.name) { Acc() }.add(v, null)
            }
        }
        return HoldingsResult(assets, wallets.mapValues { it.value.result(it.key) }, nets.mapValues { it.value.result(it.key) }, value, cost, unreal,
            BigDecimal.ZERO, BigDecimal.ZERO, unreal, if (cost.signum() > 0) unreal.divide(cost, MC).multiply(BigDecimal(100)).setScale(4, RoundingMode.HALF_EVEN) else null,
            noCost, missing)
    }

    /** Quantidade equivalente a [this] em valor, dado valor total [v] e quantidade [q] (0 se não há valor). */
    private fun BigDecimal.divideSafe(v: BigDecimal, q: BigDecimal): BigDecimal = if (v.signum() <= 0) BigDecimal.ZERO else this.multiply(q, MC).divide(v, MC)

    private class Acc {
        var value: BigDecimal = BigDecimal.ZERO; var cost: BigDecimal = BigDecimal.ZERO; var costedValue: BigDecimal = BigDecimal.ZERO; var noCost: BigDecimal = BigDecimal.ZERO
        fun add(v: BigDecimal, c: BigDecimal?) {
            value = value.add(v, MC)
            if (c != null) { cost = cost.add(c, MC); costedValue = costedValue.add(v, MC) } else noCost = noCost.add(v, MC)
        }
        fun result(k: String) = BucketResult(k, value, cost, costedValue.subtract(cost, MC), noCost)
    }
}
