package com.coinportracker.transactions

import com.coinportracker.core.Decimals
import com.coinportracker.core.isPositive
import com.coinportracker.core.isZero
import com.coinportracker.core.sumDec
import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.TxType
import java.math.BigDecimal

enum class IssueSeverity { INFO, WARNING, ERROR }

data class LedgerIssue(
    val txId: Long,
    val severity: IssueSeverity,
    val code: String,
    val message: String,
)

data class LotConsumption(val lotTxId: Long, val acquiredAt: Long, val quantity: BigDecimal, val cost: BigDecimal)

data class OpenLot(val txId: Long, val acquiredAt: Long, val quantity: BigDecimal, val cost: BigDecimal) {
    val unitCost: BigDecimal? get() = Decimals.div(cost, quantity)
}

data class RealizedEvent(
    val txId: Long,
    val timestamp: Long,
    val assetId: String,
    val type: TxType,
    val quantity: BigDecimal,
    val proceeds: BigDecimal,
    val costBasis: BigDecimal,
    val consumed: List<LotConsumption>,
) {
    val gain: BigDecimal get() = proceeds.subtract(costBasis, Decimals.MC)
}

data class IncomeEvent(val txId: Long, val timestamp: Long, val assetId: String, val type: TxType, val quantity: BigDecimal, val value: BigDecimal, val valueKnown: Boolean)

enum class FeeTreatment(val label: String) {
    CAPITALIZED("Incorporada ao custo de aquisição"),
    DEDUCTED("Deduzida do valor da venda"),
    EXPENSED("Despesa (reduz o resultado)")
}

data class FeeEvent(val txId: Long, val timestamp: Long, val assetId: String?, val amount: BigDecimal, val treatment: FeeTreatment, val paidInCrypto: Boolean)

enum class FlowKind { CONTRIBUTION, WITHDRAWAL }

/** Fluxo de capital externo. amount sempre positivo; [kind] define o sentido. */
data class CapitalFlow(val txId: Long, val timestamp: Long, val amount: BigDecimal, val kind: FlowKind, val assetId: String) {
    val signed: BigDecimal get() = if (kind == FlowKind.CONTRIBUTION) amount else amount.negate()
}

data class AvgCostPoint(val timestamp: Long, val txId: Long, val quantity: BigDecimal, val avgCost: BigDecimal?)

data class Position(
    val assetId: String,
    val quantity: BigDecimal,
    val costBasis: BigDecimal,
    val walletQuantities: Map<Long?, BigDecimal>,
    val openLots: List<OpenLot>,
    val realized: BigDecimal,
    val income: BigDecimal,
    val feesExpensed: BigDecimal,
    val costIncomplete: Boolean,
    val avgCostHistory: List<AvgCostPoint>,
) {
    val avgCost: BigDecimal? get() = if (quantity.isPositive()) Decimals.div(costBasis, quantity) else null
}

data class LedgerResult(
    val method: CostMethod,
    val currency: Currency,
    val positions: Map<String, Position>,
    val realizedEvents: List<RealizedEvent>,
    val incomeEvents: List<IncomeEvent>,
    val feeEvents: List<FeeEvent>,
    val capitalFlows: List<CapitalFlow>,
    val issues: List<LedgerIssue>,
    val processedCount: Int,
) {
    val totalContributions: BigDecimal get() = capitalFlows.filter { it.kind == FlowKind.CONTRIBUTION }.map { it.amount }.sumDec()
    val totalWithdrawals: BigDecimal get() = capitalFlows.filter { it.kind == FlowKind.WITHDRAWAL }.map { it.amount }.sumDec()
    val netContributions: BigDecimal get() = totalContributions.subtract(totalWithdrawals, Decimals.MC)
    val realizedProfit: BigDecimal get() = realizedEvents.map { it.gain }.filter { it.signum() > 0 }.sumDec()
    val realizedLoss: BigDecimal get() = realizedEvents.map { it.gain }.filter { it.signum() < 0 }.sumDec()
    val realizedNet: BigDecimal get() = realizedEvents.map { it.gain }.sumDec()
    val incomeTotal: BigDecimal get() = incomeEvents.map { it.value }.sumDec()
    val feesTotal: BigDecimal get() = feeEvents.map { it.amount }.sumDec()
    val feesExpensed: BigDecimal get() = feeEvents.filter { it.treatment == FeeTreatment.EXPENSED }.map { it.amount }.sumDec()
    val openCostBasis: BigDecimal get() = positions.values.map { it.costBasis }.sumDec()
    val hasErrors: Boolean get() = issues.any { it.severity == IssueSeverity.ERROR }
}

/**
 * Motor contábil do livro de operações.
 *
 * Convenções (documentadas em docs/ARCHITECTURE.md §P&L):
 * - Custo pooled por ativo (todas as carteiras); quantidades rastreadas por carteira.
 * - Taxas de compra são capitalizadas no custo; taxas de venda deduzidas das receitas;
 *   demais taxas são despesas. Taxas em cripto baixam unidades pelo custo (despesa).
 * - Transferência interna não altera custo, aporte ou resultado.
 * - Swap não é aporte: realiza o resultado do ativo entregue pelo valor de mercado informado;
 *   sem valor informado, o custo é transferido (carry-over) e a operação é marcada.
 * - Rendimentos (staking/recompensa/airdrop/distribuição) entram com custo = valor de mercado
 *   no recebimento e são contabilizados como rendimento, não como aporte.
 *
 * Identidade verificada em testes:
 *   patrimônio − aporte líquido = realizado + não realizado + rendimentos − taxas como despesa
 */
class LedgerEngine(
    private val method: CostMethod,
    private val reportCurrency: Currency,
    /** Câmbio BRL/USD de referência para a data (fallback quando a transação não traz câmbio). */
    private val fxFallback: (Long) -> BigDecimal? = { null },
) {
    private val mc = Decimals.MC

    private class Lot(val txId: Long, val acquiredAt: Long, var qty: BigDecimal, var cost: BigDecimal)

    private inner class Pool(val assetId: String) {
        val lots = ArrayDeque<Lot>()
        var qty: BigDecimal = BigDecimal.ZERO
        var cost: BigDecimal = BigDecimal.ZERO
        val walletQty = LinkedHashMap<Long?, BigDecimal>()
        var realized: BigDecimal = BigDecimal.ZERO
        var income: BigDecimal = BigDecimal.ZERO
        var feesExpensed: BigDecimal = BigDecimal.ZERO
        var costIncomplete = false
        val avgHistory = ArrayList<AvgCostPoint>()

        fun add(q: BigDecimal, c: BigDecimal, ts: Long, txId: Long, wallet: Long?) {
            if (!q.isPositive()) return
            qty = qty.add(q, mc)
            cost = cost.add(c, mc)
            if (method == CostMethod.FIFO) lots.addLast(Lot(txId, ts, q, c))
            walletQty[wallet] = (walletQty[wallet] ?: BigDecimal.ZERO).add(q, mc)
        }

        /** Remove até [q] unidades; retorna (qtdRemovida, custoRemovido, consumos). */
        fun remove(q: BigDecimal, wallet: Long?, txId: Long): Triple<BigDecimal, BigDecimal, List<LotConsumption>> {
            val take = q.min(qty)
            if (q > qty) {
                issues += LedgerIssue(txId, IssueSeverity.ERROR, "INSUFFICIENT_BALANCE",
                    "Saldo insuficiente de $assetId: operação pede ${q.toPlainString()}, disponível ${qty.toPlainString()}. Apenas o disponível foi baixado.")
            }
            val wq = walletQty[wallet] ?: BigDecimal.ZERO
            if (take.isPositive() && wq < take) {
                issues += LedgerIssue(txId, IssueSeverity.WARNING, "WALLET_BALANCE",
                    "A carteira de origem tinha ${wq.toPlainString()} $assetId antes desta operação, menos que ${take.toPlainString()}. Verifique a carteira informada.")
            }
            walletQty[wallet] = wq.subtract(take, mc)
            if (!take.isPositive()) return Triple(BigDecimal.ZERO, BigDecimal.ZERO, emptyList())
            val consumed = ArrayList<LotConsumption>()
            val removedCost: BigDecimal
            if (method == CostMethod.WEIGHTED_AVERAGE) {
                removedCost = if (take.compareTo(qty) == 0) cost else cost.multiply(take, mc).divide(qty, mc)
            } else {
                var remaining = take
                var acc = BigDecimal.ZERO
                while (remaining.isPositive() && lots.isNotEmpty()) {
                    val lot = lots.first()
                    if (lot.qty <= remaining) {
                        acc = acc.add(lot.cost, mc)
                        consumed += LotConsumption(lot.txId, lot.acquiredAt, lot.qty, lot.cost)
                        remaining = remaining.subtract(lot.qty, mc)
                        lots.removeFirst()
                    } else {
                        val part = lot.cost.multiply(remaining, mc).divide(lot.qty, mc)
                        acc = acc.add(part, mc)
                        consumed += LotConsumption(lot.txId, lot.acquiredAt, remaining, part)
                        lot.cost = lot.cost.subtract(part, mc)
                        lot.qty = lot.qty.subtract(remaining, mc)
                        remaining = BigDecimal.ZERO
                    }
                }
                removedCost = acc
            }
            qty = qty.subtract(take, mc)
            cost = if (qty.isZero()) BigDecimal.ZERO else cost.subtract(removedCost, mc)
            if (qty.isZero()) lots.clear()
            return Triple(take, removedCost, consumed)
        }

        fun moveWallet(q: BigDecimal, from: Long?, to: Long?, txId: Long) {
            val wq = walletQty[from] ?: BigDecimal.ZERO
            if (wq < q) {
                issues += LedgerIssue(txId, IssueSeverity.WARNING, "WALLET_BALANCE",
                    "Transferência de ${q.toPlainString()} $assetId maior que o saldo da carteira de origem (${wq.toPlainString()}).")
            }
            walletQty[from] = wq.subtract(q, mc)
            walletQty[to] = (walletQty[to] ?: BigDecimal.ZERO).add(q, mc)
        }

        fun snapshot(ts: Long, txId: Long) {
            avgHistory += AvgCostPoint(ts, txId, qty, if (qty.isPositive()) cost.divide(qty, mc) else null)
        }
    }

    private val issues = ArrayList<LedgerIssue>()

    fun run(input: List<LedgerTx>): LedgerResult {
        issues.clear()
        val pools = LinkedHashMap<String, Pool>()
        val realized = ArrayList<RealizedEvent>()
        val incomes = ArrayList<IncomeEvent>()
        val fees = ArrayList<FeeEvent>()
        val flows = ArrayList<CapitalFlow>()
        fun pool(id: String) = pools.getOrPut(id) { Pool(id) }

        val txs = input.sortedWith(compareBy<LedgerTx> { it.timestamp }.thenBy { it.id })
        for (tx in txs) {
            val touched = LinkedHashSet<String>()
            if (tx.quantity.signum() < 0 && tx.type != TxType.MANUAL_ADJUSTMENT) {
                issues += LedgerIssue(tx.id, IssueSeverity.ERROR, "NEGATIVE_QTY", "Quantidade negativa só é permitida em ajuste manual. Operação ignorada.")
                continue
            }
            val conv: (BigDecimal?) -> BigDecimal? = { a -> convert(a, tx) }
            val feeFiat = tx.feeFiat?.takeIf { it.isPositive() }
            val feeFiatR = feeFiat?.let { conv(it) }
            if (feeFiat != null && feeFiatR == null) {
                issues += LedgerIssue(tx.id, IssueSeverity.WARNING, "MISSING_FX", "Taxa em ${tx.quoteCurrency} sem câmbio para ${reportCurrency}: taxa não contabilizada neste relatório.")
            }
            val src = tx.fromWalletId ?: tx.toWalletId
            val dst = tx.toWalletId ?: tx.fromWalletId
            val p = pool(tx.assetId)
            touched += tx.assetId
            var fiatFeeHandled = false

            when (tx.type) {
                TxType.BUY -> {
                    val gross = tx.unitPrice?.let { tx.quantity.multiply(it, mc) }
                    val grossR = conv(gross)
                    if (grossR == null) {
                        p.costIncomplete = true
                        issues += LedgerIssue(tx.id, IssueSeverity.ERROR, "MISSING_PRICE",
                            if (gross == null) "Compra sem preço unitário: custo desconhecido (quantidade registrada com custo zero, resultado incompleto)."
                            else "Compra em ${tx.quoteCurrency} sem câmbio para $reportCurrency: custo desconhecido neste relatório.")
                        p.add(tx.quantity, BigDecimal.ZERO, tx.timestamp, tx.id, dst)
                    } else {
                        val cost = grossR.add(feeFiatR ?: BigDecimal.ZERO, mc)
                        p.add(tx.quantity, cost, tx.timestamp, tx.id, dst)
                        flows += CapitalFlow(tx.id, tx.timestamp, cost, FlowKind.CONTRIBUTION, tx.assetId)
                        if (feeFiatR != null) fees += FeeEvent(tx.id, tx.timestamp, tx.assetId, feeFiatR, FeeTreatment.CAPITALIZED, false)
                    }
                    fiatFeeHandled = true
                }
                TxType.SELL -> {
                    val (taken, removedCost, consumed) = p.remove(tx.quantity, src, tx.id)
                    val gross = tx.unitPrice?.let { tx.quantity.multiply(it, mc) }
                    val grossR = conv(gross)
                    if (grossR == null) {
                        issues += LedgerIssue(tx.id, IssueSeverity.ERROR, "MISSING_PRICE", "Venda sem preço ou câmbio: baixada pelo custo, resultado realizado desconhecido.")
                        if (removedCost.isPositive()) flows += CapitalFlow(tx.id, tx.timestamp, removedCost, FlowKind.WITHDRAWAL, tx.assetId)
                    } else if (taken.isPositive()) {
                        val ratio = if (taken.compareTo(tx.quantity) == 0) BigDecimal.ONE else taken.divide(tx.quantity, mc)
                        val feeR = (feeFiatR ?: BigDecimal.ZERO).multiply(ratio, mc)
                        val proceeds = grossR.multiply(ratio, mc).subtract(feeR, mc)
                        realized += RealizedEvent(tx.id, tx.timestamp, tx.assetId, tx.type, taken, proceeds, removedCost, consumed)
                        p.realized = p.realized.add(proceeds.subtract(removedCost, mc), mc)
                        flows += CapitalFlow(tx.id, tx.timestamp, proceeds, FlowKind.WITHDRAWAL, tx.assetId)
                        if (feeR.isPositive()) fees += FeeEvent(tx.id, tx.timestamp, tx.assetId, feeR, FeeTreatment.DEDUCTED, false)
                    }
                    fiatFeeHandled = true
                }
                TxType.SWAP -> {
                    val toId = tx.toAssetId
                    val toQty = tx.toQuantity
                    if (toId == null || toQty == null || !toQty.isPositive()) {
                        issues += LedgerIssue(tx.id, IssueSeverity.ERROR, "SWAP_INCOMPLETE", "Swap sem ativo ou quantidade de destino. Operação ignorada.")
                        continue
                    }
                    val (taken, removedCost, consumed) = p.remove(tx.quantity, src, tx.id)
                    val valueR = conv(tx.unitPrice?.let { tx.quantity.multiply(it, mc) })
                    val ratio = if (taken.compareTo(tx.quantity) == 0) BigDecimal.ONE else Decimals.div(taken, tx.quantity) ?: BigDecimal.ZERO
                    val receivedQty = toQty.multiply(ratio, mc)
                    val newCost: BigDecimal
                    if (valueR == null) {
                        newCost = removedCost
                        issues += LedgerIssue(tx.id, IssueSeverity.INFO, "SWAP_CARRYOVER",
                            "Swap sem valor de mercado: o custo de ${tx.assetId} foi transferido para $toId sem realizar resultado (estimativa conservadora).")
                    } else {
                        val v = valueR.multiply(ratio, mc)
                        realized += RealizedEvent(tx.id, tx.timestamp, tx.assetId, tx.type, taken, v, removedCost, consumed)
                        p.realized = p.realized.add(v.subtract(removedCost, mc), mc)
                        newCost = v
                    }
                    val total = newCost.add(feeFiatR ?: BigDecimal.ZERO, mc)
                    if (feeFiatR != null) {
                        fees += FeeEvent(tx.id, tx.timestamp, toId, feeFiatR, FeeTreatment.CAPITALIZED, false)
                        flows += CapitalFlow(tx.id, tx.timestamp, feeFiatR, FlowKind.CONTRIBUTION, toId)
                    }
                    pool(toId).add(receivedQty, total, tx.timestamp, tx.id, dst)
                    touched += toId
                    fiatFeeHandled = true
                }
                TxType.DEPOSIT -> {
                    val declared = tx.declaredCost ?: tx.unitPrice?.let { tx.quantity.multiply(it, mc) }
                    val costR = conv(declared)
                    if (costR == null) {
                        p.costIncomplete = true
                        issues += LedgerIssue(tx.id, IssueSeverity.WARNING, "MISSING_COST", "Depósito sem custo de aquisição declarado: entra com custo zero (resultado superestimado até corrigir).")
                        p.add(tx.quantity, BigDecimal.ZERO, tx.timestamp, tx.id, dst)
                    } else {
                        p.add(tx.quantity, costR, tx.timestamp, tx.id, dst)
                        if (costR.isPositive()) flows += CapitalFlow(tx.id, tx.timestamp, costR, FlowKind.CONTRIBUTION, tx.assetId)
                    }
                }
                TxType.WITHDRAWAL -> {
                    val (_, removedCost, _) = p.remove(tx.quantity, src, tx.id)
                    if (removedCost.isPositive()) flows += CapitalFlow(tx.id, tx.timestamp, removedCost, FlowKind.WITHDRAWAL, tx.assetId)
                }
                TxType.EXTERNAL_TRANSFER -> {
                    val (taken, removedCost, consumed) = p.remove(tx.quantity, src, tx.id)
                    val valueR = conv(tx.unitPrice?.let { tx.quantity.multiply(it, mc) })
                    if (valueR != null && taken.isPositive()) {
                        val ratio = if (taken.compareTo(tx.quantity) == 0) BigDecimal.ONE else taken.divide(tx.quantity, mc)
                        val v = valueR.multiply(ratio, mc)
                        realized += RealizedEvent(tx.id, tx.timestamp, tx.assetId, tx.type, taken, v, removedCost, consumed)
                        p.realized = p.realized.add(v.subtract(removedCost, mc), mc)
                        flows += CapitalFlow(tx.id, tx.timestamp, v, FlowKind.WITHDRAWAL, tx.assetId)
                    } else if (removedCost.isPositive()) {
                        flows += CapitalFlow(tx.id, tx.timestamp, removedCost, FlowKind.WITHDRAWAL, tx.assetId)
                    }
                }
                TxType.INTERNAL_TRANSFER -> {
                    if (tx.toWalletId == null || tx.fromWalletId == null || tx.toWalletId == tx.fromWalletId) {
                        issues += LedgerIssue(tx.id, IssueSeverity.WARNING, "TRANSFER_WALLETS", "Transferência interna sem carteiras de origem e destino distintas.")
                    }
                    p.moveWallet(tx.quantity, tx.fromWalletId, tx.toWalletId, tx.id)
                }
                TxType.REWARD, TxType.STAKING, TxType.AIRDROP, TxType.INCOME_DISTRIBUTION -> {
                    val valueR = conv(tx.unitPrice?.let { tx.quantity.multiply(it, mc) })
                    if (valueR == null && tx.type != TxType.AIRDROP) {
                        issues += LedgerIssue(tx.id, IssueSeverity.WARNING, "INCOME_NO_VALUE", "${tx.type.label} sem preço na data do recebimento: rendimento registrado com valor zero.")
                    }
                    val v = valueR ?: BigDecimal.ZERO
                    p.add(tx.quantity, v, tx.timestamp, tx.id, dst)
                    p.income = p.income.add(v, mc)
                    incomes += IncomeEvent(tx.id, tx.timestamp, tx.assetId, tx.type, tx.quantity, v, valueR != null)
                }
                TxType.NETWORK_FEE, TxType.TRADING_FEE -> {
                    if (tx.quantity.isPositive()) {
                        val (_, removedCost, _) = p.remove(tx.quantity, src, tx.id)
                        p.feesExpensed = p.feesExpensed.add(removedCost, mc)
                        fees += FeeEvent(tx.id, tx.timestamp, tx.assetId, removedCost, FeeTreatment.EXPENSED, true)
                    }
                }
                TxType.MANUAL_ADJUSTMENT -> {
                    issues += LedgerIssue(tx.id, IssueSeverity.INFO, "MANUAL_ADJUSTMENT", "Ajuste manual de ${tx.quantity.toPlainString()} ${tx.assetId}.")
                    if (tx.quantity.isPositive()) {
                        val c = conv(tx.declaredCost ?: tx.unitPrice?.let { tx.quantity.multiply(it, mc) }) ?: BigDecimal.ZERO
                        p.add(tx.quantity, c, tx.timestamp, tx.id, dst)
                        if (c.isPositive()) flows += CapitalFlow(tx.id, tx.timestamp, c, FlowKind.CONTRIBUTION, tx.assetId)
                    } else if (tx.quantity.signum() < 0) {
                        val (_, removedCost, _) = p.remove(tx.quantity.negate(), src, tx.id)
                        if (removedCost.isPositive()) flows += CapitalFlow(tx.id, tx.timestamp, removedCost, FlowKind.WITHDRAWAL, tx.assetId)
                    }
                }
            }

            // Taxa em dinheiro nas operações sem tratamento específico: desembolso + despesa.
            if (!fiatFeeHandled && feeFiatR != null) {
                fees += FeeEvent(tx.id, tx.timestamp, tx.assetId, feeFiatR, FeeTreatment.EXPENSED, false)
                flows += CapitalFlow(tx.id, tx.timestamp, feeFiatR, FlowKind.CONTRIBUTION, tx.assetId)
                p.feesExpensed = p.feesExpensed.add(feeFiatR, mc)
            }
            // Taxa paga em cripto: baixa unidades pelo custo, como despesa.
            val feeAsset = tx.feeAssetId
            val feeQty = tx.feeQuantity
            if (feeAsset != null && feeQty != null && feeQty.isPositive()) {
                val fp = pool(feeAsset)
                touched += feeAsset
                val (_, removedCost, _) = fp.remove(feeQty, src, tx.id)
                fp.feesExpensed = fp.feesExpensed.add(removedCost, mc)
                fees += FeeEvent(tx.id, tx.timestamp, feeAsset, removedCost, FeeTreatment.EXPENSED, true)
            }
            for (a in touched) pools[a]?.snapshot(tx.timestamp, tx.id)
        }

        val positions = pools.mapValues { (id, p) ->
            Position(
                assetId = id,
                quantity = p.qty,
                costBasis = p.cost,
                walletQuantities = p.walletQty.filterValues { it.signum() != 0 },
                openLots = if (method == CostMethod.FIFO) p.lots.map { OpenLot(it.txId, it.acquiredAt, it.qty, it.cost) }
                else if (p.qty.isPositive()) listOf(OpenLot(-1, 0, p.qty, p.cost)) else emptyList(),
                realized = p.realized,
                income = p.income,
                feesExpensed = p.feesExpensed,
                costIncomplete = p.costIncomplete,
                avgCostHistory = p.avgHistory.toList(),
            )
        }
        return LedgerResult(method, reportCurrency, positions, realized, incomes, fees, flows, issues.toList(), txs.size)
    }

    private fun convert(amount: BigDecimal?, tx: LedgerTx): BigDecimal? {
        if (amount == null) return null
        if (tx.quoteCurrency == reportCurrency) return amount
        val fx = tx.fxUsdBrl?.takeIf { it.isPositive() } ?: fxFallback(tx.timestamp)?.takeIf { it.isPositive() } ?: return null
        return if (tx.quoteCurrency == Currency.USD) amount.multiply(fx, mc) else amount.divide(fx, mc)
    }
}
