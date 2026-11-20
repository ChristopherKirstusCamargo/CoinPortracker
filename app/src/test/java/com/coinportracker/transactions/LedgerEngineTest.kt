package com.coinportracker.transactions

import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.TxType
import com.coinportracker.portfolio.PnlCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class LedgerEngineTest {
    private fun d(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal?, scale: Int = 8) =
        assertEquals("esperado $expected, obtido ${actual?.toPlainString()}", d(expected).setScale(scale, RoundingMode.HALF_EVEN), actual!!.setScale(scale, RoundingMode.HALF_EVEN))

    private var nextId = 1L
    private fun tx(type: TxType, asset: String, q: String, price: String? = null, day: Int = 1, cur: Currency = Currency.BRL,
                   fee: String? = null, from: Long? = 1, to: Long? = null, toAsset: String? = null, toQty: String? = null,
                   feeAsset: String? = null, feeQty: String? = null, fx: String? = null, cost: String? = null) =
        LedgerTx(nextId++, type, day * 86_400_000L, asset, d(q), price?.let(::d), cur, toAsset, toQty?.let(::d), fee?.let(::d), feeAsset, feeQty?.let(::d), from, to, fx?.let(::d), cost?.let(::d))

    /** Exemplo da especificação: 1 SOL a 500, 2 SOL a 400, 1 SOL a 600, R$ 10 de taxas. */
    private fun solExample() = listOf(
        tx(TxType.BUY, "SOL", "1", "500", day = 1),
        tx(TxType.BUY, "SOL", "2", "400", day = 2),
        tx(TxType.BUY, "SOL", "1", "600", day = 3, fee = "10"),
    )

    @Test fun solExample_weightedAverage_costAndAveragePrice() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(solExample())
        val p = r.positions.getValue("SOL")
        eq("4", p.quantity)
        eq("1910", p.costBasis)            // 500 + 800 + 600 + 10
        eq("477.5", p.avgCost)             // 1910 / 4
        eq("1910", r.totalContributions)
        eq("10", r.feesTotal)
        eq("0", r.feesExpensed)
    }

    @Test fun solExample_fifo_lotsPreserved() {
        val r = LedgerEngine(CostMethod.FIFO, Currency.BRL).run(solExample())
        val p = r.positions.getValue("SOL")
        eq("1910", p.costBasis)
        assertEquals(3, p.openLots.size)
        eq("500", p.openLots[0].cost); eq("800", p.openLots[1].cost); eq("610", p.openLots[2].cost)
    }

    @Test fun solExample_sellTwo_averageVsFifo() {
        val txs = solExample() + tx(TxType.SELL, "SOL", "2", "550", day = 4)
        val avg = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(txs)
        nextId = 1
        val fifo = LedgerEngine(CostMethod.FIFO, Currency.BRL).run(txs)
        // Média: custo baixado = 2 × 477,50 = 955 → realizado 1100 − 955 = 145
        eq("145", avg.realizedNet); eq("955", avg.positions.getValue("SOL").costBasis); eq("477.5", avg.positions.getValue("SOL").avgCost)
        // FIFO: baixa 1×500 + 1×400 = 900 → realizado 200; restam 1×400 + 1×610 = 1010
        eq("200", fifo.realizedNet); eq("1010", fifo.positions.getValue("SOL").costBasis); eq("505", fifo.positions.getValue("SOL").avgCost)
        // Com cotação de 450, o resultado TOTAL é igual nos dois métodos (90): só muda a divisão realizado/não realizado.
        val pa = PnlCalculator.summarize(avg, mapOf("SOL" to d("450")))
        val pf = PnlCalculator.summarize(fifo, mapOf("SOL" to d("450")))
        eq("-55", pa.unrealizedNet); eq("-110", pf.unrealizedNet)
        eq("90", pa.totalResult); eq("90", pf.totalResult)
        // Capital: aportes 1910, retiradas 1100 → líquido 810; patrimônio 900; 900 − 810 = 90
        eq("810", pa.netContributions); eq("900", pa.equity)
        eq(pa.equity.subtract(pa.netContributions).toPlainString(), pa.totalResult)
    }

    @Test fun sellFee_deductedFromProceeds() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "BTC", "1", "100000", day = 1),
            tx(TxType.SELL, "BTC", "0.5", "120000", day = 2, fee = "100"),
        ))
        // receita = 60000 − 100 = 59900; custo = 50000 → 9900
        eq("9900", r.realizedNet); eq("59900", r.totalWithdrawals); eq("100", r.feesTotal)
        eq("50000", r.positions.getValue("BTC").costBasis)
    }

    @Test fun internalTransfer_isNotContributionNorResult_feeInCryptoIsExpense() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "SOL", "10", "500", day = 1, from = 1),
            tx(TxType.INTERNAL_TRANSFER, "SOL", "9.99", day = 2, from = 1, to = 2, feeAsset = "SOL", feeQty = "0.01"),
        ))
        val p = r.positions.getValue("SOL")
        eq("9.99", p.quantity)
        eq("5000", r.totalContributions)            // sem novo aporte
        eq("0", r.realizedNet)                       // sem resultado
        eq("5", r.feesExpensed)                      // 0,01 × custo médio 500
        eq("4995", p.costBasis)
        eq("9.99", p.walletQuantities.getValue(2L)); assertNull(p.walletQuantities[1L])
    }

    @Test fun swap_withMarketValue_realizesAndIsNotContribution() {
        val r = LedgerEngine(CostMethod.FIFO, Currency.USD).run(listOf(
            tx(TxType.BUY, "ETH", "2", "1000", day = 1, cur = Currency.USD),
            tx(TxType.SWAP, "ETH", "1", "3000", day = 2, cur = Currency.USD, toAsset = "SOL", toQty = "20"),
        ))
        eq("2000", r.totalContributions)                     // swap não é aporte
        eq("2000", r.realizedNet)                            // 3000 − 1000
        eq("3000", r.positions.getValue("SOL").costBasis)    // custo do recebido = valor de mercado
        eq("150", r.positions.getValue("SOL").avgCost)
        eq("1000", r.positions.getValue("ETH").costBasis)
        val s = PnlCalculator.summarize(r, mapOf("ETH" to d("3000"), "SOL" to d("150")))
        eq("6000", s.equity); eq(s.equity.subtract(s.netContributions).toPlainString(), s.totalResult)
    }

    @Test fun swap_withoutValue_carriesCostOver() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "ETH", "2", "1000", day = 1),
            tx(TxType.SWAP, "ETH", "1", null, day = 2, toAsset = "USDC", toQty = "3000"),
        ))
        eq("0", r.realizedNet)
        eq("1000", r.positions.getValue("USDC").costBasis)
        assertTrue(r.issues.any { it.code == "SWAP_CARRYOVER" })
    }

    @Test fun staking_incomeAtFairValue_notContribution() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "SOL", "10", "500", day = 1),
            tx(TxType.STAKING, "SOL", "0.1", "600", day = 30),
        ))
        eq("60", r.incomeTotal); eq("5000", r.totalContributions)
        eq("5060", r.positions.getValue("SOL").costBasis)
        val s = PnlCalculator.summarize(r, mapOf("SOL" to d("600")))
        // patrimônio 6060; aporte 5000; resultado 1060 = não realizado 1000 + rendimento 60
        eq("1000", s.unrealizedNet); eq("1060", s.totalResult); eq("1060", s.equity.subtract(s.netContributions))
    }

    @Test fun currencyConversion_usdBuyReportedInBrl_usesTxFx() {
        val txs = listOf(
            tx(TxType.BUY, "BTC", "0.1", "50000", day = 1, cur = Currency.USD, fee = "5", fx = "5.00"),
            tx(TxType.SELL, "BTC", "0.05", "60000", day = 2, cur = Currency.USD, fx = "5.50"),
        )
        val brl = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(txs)
        nextId = 1
        val usd = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.USD).run(txs)
        // USD: custo 5005; venda 3000 − custo 2502,5 = 497,5
        eq("5005", usd.totalContributions); eq("497.5", usd.realizedNet)
        // BRL: custo 25025; venda 3000×5,5 = 16500 − 12512,5 = 3987,5 (inclui efeito cambial)
        eq("25025", brl.totalContributions); eq("3987.5", brl.realizedNet)
    }

    @Test fun missingFx_flaggedNotInvented() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(tx(TxType.BUY, "BTC", "1", "100", cur = Currency.USD)))
        assertTrue(r.issues.any { it.code == "MISSING_PRICE" })
        assertTrue(r.positions.getValue("BTC").costIncomplete)
        eq("0", r.totalContributions)
    }

    @Test fun fxFallback_usedWhenTxHasNoRate() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL) { d("5.2") }.run(listOf(tx(TxType.BUY, "BTC", "1", "100", cur = Currency.USD)))
        eq("520", r.totalContributions)
    }

    @Test fun overSell_flaggedAndOnlyAvailableDisposed() {
        val r = LedgerEngine(CostMethod.FIFO, Currency.BRL).run(listOf(
            tx(TxType.BUY, "ADA", "100", "2", day = 1),
            tx(TxType.SELL, "ADA", "150", "3", day = 2),
        ))
        assertTrue(r.issues.any { it.code == "INSUFFICIENT_BALANCE" })
        eq("0", r.positions.getValue("ADA").quantity)
        eq("100", r.realizedNet) // 100 × 3 − 200
    }

    @Test fun zeroBalance_avgCostNull_costZero() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "DOT", "3", "10", day = 1), tx(TxType.SELL, "DOT", "3", "7", day = 2)))
        val p = r.positions.getValue("DOT")
        eq("0", p.quantity); eq("0", p.costBasis); assertNull(p.avgCost); eq("-9", r.realizedNet)
        eq("-9", r.realizedLoss); eq("0", r.realizedProfit)
    }

    @Test fun decimalPrecision_noBinaryFloatingErrors() {
        // 0,1 + 0,2 em Double = 0,30000000000000004; aqui deve ser exato.
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "X", "0.1", "1", day = 1), tx(TxType.BUY, "X", "0.2", "1", day = 2)))
        assertEquals(0, d("0.3").compareTo(r.positions.getValue("X").quantity))
        assertEquals(0, d("0.3").compareTo(r.positions.getValue("X").costBasis))
    }

    @Test fun averageCost_thirds_roundsOnlyOnDisplay() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "X", "3", "33.33", day = 1, fee = "0.01"), // custo 100
            tx(TxType.SELL, "X", "1", "40", day = 2)))
        eq("66.66666667", r.positions.getValue("X").costBasis)
        eq("6.66666667", r.realizedNet)
        // soma exata: custo restante + custo baixado = 100
        val total = r.positions.getValue("X").costBasis.add(r.realizedEvents.first().costBasis)
        assertEquals("100.00", total.setScale(2, RoundingMode.HALF_EVEN).toPlainString())
    }

    @Test fun standaloneFiatFee_isContributionAndExpense() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "BTC", "1", "1000", day = 1), tx(TxType.TRADING_FEE, "BTC", "0", day = 2, fee = "15")))
        eq("1015", r.totalContributions); eq("15", r.feesExpensed)
        val s = PnlCalculator.summarize(r, mapOf("BTC" to d("1000")))
        eq("-15", s.totalResult); eq("-15", s.equity.subtract(s.netContributions))
    }

    @Test fun withdrawal_atCost_externalTransfer_atMarket() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.BUY, "BTC", "2", "1000", day = 1),
            tx(TxType.WITHDRAWAL, "BTC", "0.5", day = 2),
            tx(TxType.EXTERNAL_TRANSFER, "BTC", "0.5", "1500", day = 3)))
        eq("1250", r.totalWithdrawals) // 500 (custo) + 750 (mercado)
        eq("250", r.realizedNet)
        eq("1000", r.positions.getValue("BTC").costBasis)
    }

    @Test fun deposit_withDeclaredCost_andManualAdjustment() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(
            tx(TxType.DEPOSIT, "ETH", "1", day = 1, cost = "12000"),
            tx(TxType.MANUAL_ADJUSTMENT, "ETH", "-0.1", day = 2)))
        eq("12000", r.totalContributions); eq("1200", r.totalWithdrawals); eq("0.9", r.positions.getValue("ETH").quantity)
        assertTrue(r.issues.any { it.code == "MANUAL_ADJUSTMENT" })
    }

    @Test fun identity_holdsForMixedLedger() {
        val txs = listOf(
            tx(TxType.BUY, "BTC", "0.5", "200000", day = 1, fee = "50"),
            tx(TxType.BUY, "ETH", "3", "10000", day = 2),
            tx(TxType.SWAP, "ETH", "1", "11000", day = 3, toAsset = "SOL", toQty = "20", feeAsset = "ETH", feeQty = "0.01"),
            tx(TxType.STAKING, "SOL", "0.2", "560", day = 10),
            tx(TxType.SELL, "BTC", "0.1", "250000", day = 20, fee = "25"),
            tx(TxType.INTERNAL_TRANSFER, "SOL", "10", day = 21, from = 1, to = 2),
            tx(TxType.NETWORK_FEE, "SOL", "0.005", day = 22, from = 2),
            tx(TxType.AIRDROP, "JUP", "100", "4", day = 23),
        )
        for (m in CostMethod.entries) {
            nextId = 1
            val r = LedgerEngine(m, Currency.BRL).run(txs)
            val s = PnlCalculator.summarize(r, mapOf("BTC" to d("260000"), "ETH" to d("9000"), "SOL" to d("600"), "JUP" to d("3.5")))
            eq(s.equity.subtract(s.netContributions).toPlainString(), s.totalResult)
        }
    }

    @Test fun negativeQuantity_onNonAdjustment_isRejected() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(listOf(tx(TxType.BUY, "X", "-1", "1")))
        assertTrue(r.issues.any { it.code == "NEGATIVE_QTY" })
    }

    @Test fun avgCostHistory_recorded() {
        val r = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(solExample())
        val h = r.positions.getValue("SOL").avgCostHistory
        assertEquals(3, h.size); eq("500", h[0].avgCost); eq("433.33333333", h[1].avgCost); eq("477.5", h[2].avgCost)
    }
}
