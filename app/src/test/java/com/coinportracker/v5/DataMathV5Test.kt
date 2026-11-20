package com.coinportracker.v5

import com.coinportracker.data.ActivityClassifier
import com.coinportracker.data.ActivityKind
import com.coinportracker.data.HubState
import com.coinportracker.data.StakeView
import com.coinportracker.data.WalletSummary
import com.coinportracker.database.ChainActivityEntity
import com.coinportracker.history.*
import com.coinportracker.onchain.*
import com.coinportracker.portfolio.CostSource
import com.coinportracker.portfolio.HoldingsPnlCalc
import com.coinportracker.portfolio.RealPnlCalc
import com.coinportracker.settings.AppSettings
import com.coinportracker.tax.TaxFromPortfolio
import com.coinportracker.tax.TaxRules
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneId

/** v5 — workstream A: P&L, câmbio histórico, rateio, staking, atividade e Contábil. */
class DataMathV5Test {
    private val day = 86_400_000L
    private val now = 1_000L * day

    // ---------- Resultado real: caminho do lucro pelo efeito preço ----------

    @Test fun inboundTransferNeverCreatesProfitOrLoss() {
        // R$ 7.924 pagos; metade das moedas chega no meio do período; preço parado → lucro constante, sem "lucro máximo" falso
        val series = listOf(0L to 3_000.0, 10L to 3_000.0, 20L to 7_869.48, 30L to 7_869.48)
        val gains = listOf(0.0, 0.0, 0.0, 0.0)
        val r = RealPnlCalc.compute(7_924.0, 7_869.48, 1.0, series, 0, 0.0, stakingYieldCur = 2.99, gainUsd = gains)!!
        assertEquals(7_869.48 - 7_924.0 - 2.99, r.result, 1e-9)
        assertEquals(0.0, r.peakProfit, 1e-9)
        assertEquals(0.0, r.givenBack, 1e-9)
        assertEquals(0.0, r.maxDrawdown!!, 1e-12)
        assertTrue("devolvido nunca negativo", r.givenBack >= 0)
    }

    @Test fun peakGivenBackAndDrawdownFollowThePrice() {
        val series = listOf(0L to 1_000.0, 1L to 1_100.0, 2L to 950.0)
        val gains = listOf(0.0, 100.0, -50.0)
        val r = RealPnlCalc.compute(1_000.0, 950.0, 1.0, series, 0, 0.0, gainUsd = gains)!!
        assertEquals(-50.0, r.result, 1e-9)
        assertEquals(100.0, r.peakProfit, 1e-9)
        assertEquals(100.0, r.givenBack, 1e-9) // lucro devolvido; a perda do principal (50) é outra coisa
        assertEquals(50.0, r.lossOnPrincipal, 1e-9)
        assertEquals((1_100.0 - 950.0) / 1_100.0, r.maxDrawdown!!, 1e-12)
        // linha do gráfico termina no valor atual e passa pelo topo
        assertEquals(listOf(1_000.0, 1_100.0, 950.0), RealPnlCalc.line(950.0, gains, 3))
    }

    @Test fun fxConvertsTheWholePath() {
        val r = RealPnlCalc.compute(5_800.0, 1_000.0, 5.8, listOf(0L to 1_000.0, 1L to 1_000.0), 0, 0.0, gainUsd = listOf(-100.0, 0.0))!!
        assertEquals(0.0, r.result, 1e-9)
        assertEquals(-580.0, r.worst!!.second, 1e-9)
    }

    @Test fun edgeValues() {
        assertNull(RealPnlCalc.compute(0.0, 100.0, 1.0, emptyList(), 0, 0.0))
        assertNull(RealPnlCalc.compute(100.0, Double.NaN, 1.0, emptyList(), 0, 0.0))
        assertNull(RealPnlCalc.compute(100.0, 100.0, 0.0, emptyList(), 0, 0.0))
        val big = RealPnlCalc.compute(1e12, 2e12, 1.0, listOf(0L to 1e12, 1L to 2e12), 0, 0.0, gainUsd = listOf(0.0, 1e12))!!
        assertEquals(1e12, big.result, 1.0); assertEquals(1e12, big.peakProfit, 1.0)
        val dust = RealPnlCalc.compute(1e-6, 2e-6, 1.0, listOf(0L to 1e-6, 1L to 2e-6), 0, 0.0, gainUsd = listOf(0.0, 1e-6))!!
        assertFalse(dust.resultPct.isNaN()); assertEquals(100.0, dust.resultPct, 1e-6)
        // série de ganho com tamanho diferente: cai para o principal reto (comportamento da v4.1)
        assertNotNull(RealPnlCalc.compute(100.0, 90.0, 1.0, listOf(0L to 100.0, 1L to 90.0), 0, 0.0, gainUsd = listOf(1.0)))
        // linha nunca negativa
        assertTrue(RealPnlCalc.line(10.0, listOf(0.0, 50.0), 2)!!.all { it >= 0 })
    }

    @Test fun sinceIgnoresOlderPoints() {
        val r = RealPnlCalc.compute(1_000.0, 1_000.0, 1.0, listOf(0L to 0.0, 5L to 1_500.0, 10L to 1_000.0), 6, 0.0, gainUsd = listOf(0.0, 500.0, 0.0))!!
        assertEquals(0.0, r.peakProfit, 1e-9)
    }

    // ---------- Câmbio histórico ----------

    @Test fun fxFactorUsesTheRateOfTheDay() {
        val d0 = now / day - 30
        val daily = (0..30).associate { (d0 + it) to (5.0 + 0.8 * it / 30.0) }
        val f = FxRebase.factor(daily, 5.8, (d0) * day, now)!!
        assertEquals(1.0, f(now), 1e-12)
        assertEquals(5.0 / 5.8, f(d0 * day), 1e-3)
        assertTrue(f(d0 * day + 15 * day) in (5.0 / 5.8)..1.0)
        // sem câmbio cobrindo o início: não converte (mantém a v4.1)
        assertNull(FxRebase.factor(daily, 5.8, (d0 - 40) * day, now))
        assertNull(FxRebase.factor(emptyMap(), 5.8, d0 * day, now))
        assertNull(FxRebase.factor(daily, 0.0, d0 * day, now))
    }

    @Test fun constantUsdPriceWithRisingDollarIsAGainInReais() {
        val sol = PosId(1, "SOLANA", "native")
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 10.0, PosKind.NATIVE, 100.0))
        val pts = (0..30).map { ValuePoint(now - (30 - it) * day, 100.0) }
        val d0 = now / day - 31
        val f = FxRebase.factor((0..31).associate { (d0 + it) to (5.0 + 0.8 * it / 31.0) }, 5.8, now - 30 * day, now)!!
        val book = PriceBook(FxRebase.rebase(mapOf("coingecko:solana" to pts), f), mapOf("coingecko:solana" to 100.0), now)
        val r = HistoryEngine.reconstruct(pos, emptyList(), listOf(Coverage(1, "SOLANA", 0, true)), emptyList(), book, now - 30 * day, now, 31, trimLeadingZeros = false)
        // em reais: 10 SOL × 100 USD × (5,8 − ~5,03) — positivo, e o % ponderado no tempo acompanha o câmbio
        assertTrue(r.marketUsd > 0)
        assertEquals(1_000.0 - 1_000.0 * f(now - 30 * day), r.marketUsd, 1.0)
        assertEquals(r.marketUsd, r.pnlUsd!!, 1e-9) // pnl do período = efeito preço (sem rendimento)
    }

    // ---------- Rateio do total pago por ativo ----------

    private fun portfolio(): AggregatedPortfolio {
        val w1 = HoldingSource(1, "Principal")
        val sol = TokenHolding(Network.SOLANA, "native", "SOL", "Solana", 9, BigDecimal("10"), null, "coingecko:solana", HoldingKind.NATIVE, true, coingeckoId = "solana")
        val staked = TokenHolding(Network.SOLANA, "stake:abc", "SOL", "SOL em stake", 9, BigDecimal("5"), null, "coingecko:solana", HoldingKind.STAKED, true, coingeckoId = "solana")
        val usdc = TokenHolding(Network.SOLANA, "EPjF", "USDC", "USD Coin", 6, BigDecimal("300"), null, "solana:EPjF", HoldingKind.TOKEN, true)
        val prices = mapOf("coingecko:solana" to LivePrice("coingecko:solana", 100.0, 0.0, 1.0, "SOL", 0), "solana:EPjF" to LivePrice("solana:EPjF", 0.98, 0.0, 1.0, "USDC", 0))
        return Aggregator.aggregate(listOf(w1 to sol, w1 to staked, w1 to usdc), emptyList(), prices, minUsd = 0.0)
    }

    @Test fun investedTotalIsAllocatedPerAssetAndSumsToTheHeadline() {
        val p = portfolio()
        val invested = BigDecimal("1900")
        val yieldUsd = mapOf("staked:solana" to BigDecimal("3"))
        val r = HoldingsPnlCalc.compute(p.assets, emptyMap(), emptyMap(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, investedUsd = invested, yieldUsd = yieldUsd)
        assertEquals(0, r.missingCost)
        assertTrue(r.assets.values.all { it.source == CostSource.ALLOCATED && it.cost != null })
        assertEquals(0, invested.compareTo(r.openCost))
        // Σ por ativo = valor − investido − rendimento de staking = Resultado real
        val value = BigDecimal("1794") // 1000 + 500 + 294
        assertEquals(value.subtract(invested).subtract(BigDecimal("3")).toDouble(), r.total.toDouble(), 1e-9)
        assertNotNull(r.assets["staked:solana"]); assertNotNull(r.assets["cg:solana"])
    }

    @Test fun noAllocationWithoutPrincipalOrPrices() {
        val p = portfolio()
        val r0 = HoldingsPnlCalc.compute(p.assets, emptyMap(), emptyMap(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, investedUsd = BigDecimal.ZERO)
        assertTrue(r0.assets.values.none { it.source == CostSource.ALLOCATED })
        val unpriced = Aggregator.aggregate(listOf(HoldingSource(1, "W") to TokenHolding(Network.SOLANA, "X", "X", "X", 6, BigDecimal.ONE, null, "solana:X", HoldingKind.TOKEN, false)), emptyList(), emptyMap(), minUsd = 0.0)
        val r1 = HoldingsPnlCalc.compute(unpriced.assets, emptyMap(), emptyMap(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, investedUsd = BigDecimal("100"))
        assertTrue(r1.assets.values.all { it.cost == null })
    }

    // ---------- Staking ----------

    @Test fun aprUsesMeasuredEpochLength() {
        // dados reais do RPC (13/11/2026): época 1084, slots ~0,215 s
        val perf = chainJson.parseToJsonElement("""[{"numSlots":280,"samplePeriodSecs":60},{"numSlots":279,"samplePeriodSecs":60}]""")
        val ms = StakeRewardsReader.measuredEpochMs(432_000, perf)
        assertEquals(432_000 * 120.0 / 559 * 1000, ms.toDouble(), 1_000.0)
        val info = StakeRewardInfo("A", BigInteger("11009012446"), emptyList(), BigInteger.ZERO, null, true, 1084, BigInteger("1456897"), BigInteger("11009012446"), ms)
        assertEquals(4.5, info.aprPct!!, 0.2) // antes: ~2,2% (época fixa de 2,2 dias)
        assertEquals(StakeRewardsReader.EPOCH_MS, StakeRewardsReader.measuredEpochMs(null, perf))
        assertEquals(StakeRewardsReader.EPOCH_MS, StakeRewardsReader.measuredEpochMs(432_000, chainJson.parseToJsonElement("[]")))
    }

    // ---------- Histórico on-chain ----------

    @Test fun btcFeeIsNotCountedTwice() {
        val btc = PosId(1, "BITCOIN", "native")
        val inp = HistoryInputs.build(
            positions = listOf(Position(btc, "cg:bitcoin", "coingecko:bitcoin", "BTC", 0.5, PosKind.NATIVE, 70_000.0)),
            activity = listOf(ActivityRow(1, "BITCOIN", "tx", 10 * day, "OUT", 0.5, "native", null, 0.0001, false, "coingecko:bitcoin", "BTC")),
            coverage = listOf(Coverage(1, "BITCOIN", 0, true)), stakes = emptyList(), ownAddresses = emptySet(), ledger = emptyList(),
            ledgerPriceKey = { null }, gonePriceOk = { _, _ -> true }, now = now,
        )
        assertEquals(1.0, HistoryEngine.amountAt(inp.positions.first(), inp.movements, null, 5 * day, now), 1e-12)
    }

    @Test fun splTokenDeltaSumsAllAccountsOfTheOwner() {
        val tx = chainJson.parseToJsonElement("""{"meta":{"fee":5000,"preBalances":[1000000000],"postBalances":[999995000],
            "preTokenBalances":[{"owner":"OWN","mint":"M","uiTokenAmount":{"amount":"1000000","decimals":6,"uiAmountString":"1"}},{"owner":"OWN","mint":"M","uiTokenAmount":{"amount":"2000000","decimals":6,"uiAmountString":"2"}}],
            "postTokenBalances":[{"owner":"OWN","mint":"M","uiTokenAmount":{"amount":"0","decimals":6,"uiAmountString":"0"}},{"owner":"OWN","mint":"M","uiTokenAmount":{"amount":"2000000","decimals":6,"uiAmountString":"2"}}]},
            "transaction":{"message":{"accountKeys":[{"pubkey":"OWN"}]}}}""")
        val d = SolanaScanner.parseTxDeltas(tx, "OWN")
        assertEquals(0, BigDecimal("-1").compareTo(d.single { it.mint == "M" }.delta))
        assertNull(d.firstOrNull { it.mint == null }) // só a taxa: sem variação de SOL
    }

    // ---------- Atividade e Contábil ----------

    private fun row(w: Long, dir: String, hash: String, sym: String = "SOL", amount: String = "1", ts: Long = 10 * day) =
        ChainActivityEntity(w, "SOLANA", hash, ts, dir, sym, amount, if (sym == "SOL") "native" else sym, null, null, false, null, null, if (sym == "SOL") "coingecko:solana" else "solana:$sym")

    @Test fun ownWalletTransfersAndStakingAreBetweenAccounts() {
        val rows = listOf(row(1, "OUT", "t1"), row(2, "IN", "t1"), row(1, "OUT", "stk", amount = "5"), row(1, "IN", "ext"), row(1, "OUT", "sold"))
        val c = ActivityClassifier.classify(rows, setOf("stk"))
        fun kind(w: Long, h: String) = c.first { it.primary.walletId == w && it.primary.hash == h }.kind
        assertEquals(ActivityKind.SELF, kind(1, "t1")); assertEquals(ActivityKind.SELF, kind(2, "t1"))
        assertEquals(ActivityKind.SELF, kind(1, "stk"))
        assertEquals(ActivityKind.IN, kind(1, "ext")); assertEquals(ActivityKind.OUT, kind(1, "sold"))
        // sem as assinaturas do stake, continua como era (saída)
        assertEquals(ActivityKind.OUT, ActivityClassifier.classify(rows).first { it.primary.hash == "stk" }.kind)
    }

    @Test fun delegatingSolIsNotASaleAndRewardsAreIncome() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val h = HubState(ready = true, settings = AppSettings(investedTotal = "1000"), fx = BigDecimal("5.8"),
            wallets = listOf(WalletSummary(1, "Phantom", "OWN", null, 0.0, null, emptyList(), null, null, true)),
            stakes = listOf(StakeView(1, "ACC", 5.0, 0.01, 1.0, 4.5, null, now - 9 * day, true)))
        val rows = listOf(row(1, "IN", "buy", amount = "6", ts = now - 20 * day), row(1, "OUT", "stk", amount = "5", ts = now - 9 * day),
            row(9, "OUT", "removed", amount = "6", ts = now - 5 * day))
        val rep = TaxFromPortfolio.report(h, rows, setOf("stk"), TaxRules.default(), now, zone)
        assertTrue("delegar não é venda", rep.months.none { m -> m.disposals.isNotEmpty() })
        assertEquals(1, rep.stakingIncome.size)
        assertEquals(0, BigDecimal("0.01").compareTo(rep.stakingIncome.single().qty))
    }

    // ---------- Alertas ----------

    @Test fun priceAlertOnlyUsesLivePriceInTheSameCurrency() {
        val a = com.coinportracker.database.AlertEntity(id = 1, kind = "PRICE_ABOVE", assetId = "cg:solana", label = "SOL", threshold = "400", currency = "BRL", enabled = true)
        val usdOnly = listOf(com.coinportracker.data.AlertEvaluator.Price("cg:solana", "SOL", BigDecimal("80"), null, com.coinportracker.domain.Currency.USD))
        val both = usdOnly + com.coinportracker.data.AlertEvaluator.Price("cg:solana", "SOL", BigDecimal("464"), null, com.coinportracker.domain.Currency.BRL)
        assertTrue(com.coinportracker.data.AlertEvaluator.evaluate(listOf(a), emptyList(), null, null, com.coinportracker.domain.Currency.BRL, now, live = usdOnly).first.isEmpty())
        assertEquals(1, com.coinportracker.data.AlertEvaluator.evaluate(listOf(a), emptyList(), null, null, com.coinportracker.domain.Currency.BRL, now, live = both).first.size)
    }
}
