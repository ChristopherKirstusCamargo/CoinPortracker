package com.coinportracker.portfolio

import com.coinportracker.core.Decimals
import com.coinportracker.core.isPositive
import com.coinportracker.core.sumDec
import com.coinportracker.domain.Currency
import com.coinportracker.transactions.FlowKind
import com.coinportracker.transactions.LedgerResult
import java.math.BigDecimal

data class AssetPnl(
    val assetId: String,
    val quantity: BigDecimal,
    val costBasis: BigDecimal,
    val avgCost: BigDecimal?,
    val price: BigDecimal?,
    val marketValue: BigDecimal?,
    val unrealized: BigDecimal?,
    val unrealizedPct: BigDecimal?,
    val realized: BigDecimal,
    val income: BigDecimal,
    val feesExpensed: BigDecimal,
    val sharePct: BigDecimal?,
    val costIncomplete: Boolean,
) {
    val missingPrice get() = quantity.isPositive() && price == null
    /** Resultado total do ativo = realizado + não realizado + rendimentos − taxas como despesa. */
    val totalResult: BigDecimal?
        get() = unrealized?.let { it.add(realized, Decimals.MC).add(income, Decimals.MC).subtract(feesExpensed, Decimals.MC) }
}

data class PnlSummary(
    val currency: Currency,
    val equity: BigDecimal,
    /** false quando algum ativo com saldo não tem cotação: o patrimônio é parcial. */
    val equityComplete: Boolean,
    val contributions: BigDecimal,
    val withdrawals: BigDecimal,
    val netContributions: BigDecimal,
    val openCostBasis: BigDecimal,
    val realizedProfit: BigDecimal,
    val realizedLoss: BigDecimal,
    val unrealizedProfit: BigDecimal,
    val unrealizedLoss: BigDecimal,
    val income: BigDecimal,
    val feesTotal: BigDecimal,
    val feesExpensed: BigDecimal,
    val assets: List<AssetPnl>,
) {
    val realizedNet: BigDecimal get() = realizedProfit.add(realizedLoss, Decimals.MC)
    val unrealizedNet: BigDecimal get() = unrealizedProfit.add(unrealizedLoss, Decimals.MC)
    val totalResult: BigDecimal
        get() = realizedNet.add(unrealizedNet, Decimals.MC).add(income, Decimals.MC).subtract(feesExpensed, Decimals.MC)
    /** Resultado total sobre o capital total aportado. */
    val totalReturnPct: BigDecimal? get() = Decimals.pct(totalResult, contributions)
    val feesOverContributionPct: BigDecimal? get() = Decimals.pct(feesTotal, contributions)
}

object PnlCalculator {
    /** [prices]: preço atual por ativo na moeda do relatório (null = sem cotação). */
    fun summarize(result: LedgerResult, prices: Map<String, BigDecimal?>): PnlSummary {
        val mc = Decimals.MC
        var complete = true
        val rows = result.positions.values.map { pos ->
            val price = prices[pos.assetId]
            val mv = if (pos.quantity.isPositive()) price?.let { pos.quantity.multiply(it, mc) } else BigDecimal.ZERO
            if (pos.quantity.isPositive() && price == null) complete = false
            val unr = mv?.subtract(pos.costBasis, mc)
            AssetPnl(
                assetId = pos.assetId,
                quantity = pos.quantity,
                costBasis = pos.costBasis,
                avgCost = pos.avgCost,
                price = price,
                marketValue = mv,
                unrealized = unr,
                unrealizedPct = unr?.let { Decimals.pct(it, pos.costBasis) },
                realized = pos.realized,
                income = pos.income,
                feesExpensed = pos.feesExpensed,
                sharePct = null,
                costIncomplete = pos.costIncomplete,
            )
        }
        val equity = rows.mapNotNull { it.marketValue }.sumDec()
        val withShare = rows.map { it.copy(sharePct = it.marketValue?.let { mv -> Decimals.pct(mv, equity) }) }
            .sortedByDescending { it.marketValue ?: BigDecimal.ZERO }
        val unrealizedVals = withShare.mapNotNull { it.unrealized }
        return PnlSummary(
            currency = result.currency,
            equity = equity,
            equityComplete = complete,
            contributions = result.totalContributions,
            withdrawals = result.totalWithdrawals,
            netContributions = result.netContributions,
            openCostBasis = result.openCostBasis,
            realizedProfit = result.realizedProfit,
            realizedLoss = result.realizedLoss,
            unrealizedProfit = unrealizedVals.filter { it.signum() > 0 }.sumDec(),
            unrealizedLoss = unrealizedVals.filter { it.signum() < 0 }.sumDec(),
            income = result.incomeTotal,
            feesTotal = result.feesTotal,
            feesExpensed = result.feesExpensed,
            assets = withShare,
        )
    }

    /** Evolução acumulada do capital aportado líquido (para gráficos). */
    fun contributionCurve(result: LedgerResult): List<Pair<Long, BigDecimal>> {
        var acc = BigDecimal.ZERO
        return result.capitalFlows.sortedBy { it.timestamp }.map {
            acc = if (it.kind == FlowKind.CONTRIBUTION) acc.add(it.amount, Decimals.MC) else acc.subtract(it.amount, Decimals.MC)
            it.timestamp to acc
        }
    }
}
