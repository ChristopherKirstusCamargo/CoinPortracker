package com.coinportracker.portfolio

import com.coinportracker.onchain.Aggregator
import com.coinportracker.onchain.HoldingKind
import com.coinportracker.onchain.HoldingSource
import com.coinportracker.onchain.ManualPosition
import com.coinportracker.onchain.LivePrice
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.TokenHolding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class HoldingsPnlTest {
    private fun bd(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal?) {
        if (actual == null || bd(expected).compareTo(actual) != 0) throw AssertionError("esperado $expected, obtido $actual")
    }

    private fun eth(net: Network, amount: String) = TokenHolding(net, "native", "ETH", "Ether", 18, bd(amount), null, "coingecko:ethereum", HoldingKind.NATIVE, true, coingeckoId = "ethereum")
    private val prices = mapOf(
        "coingecko:ethereum" to LivePrice("coingecko:ethereum", 2000.0, 5.0, 1.0, "ETH", 0),
        "coingecko:solana" to LivePrice("coingecko:solana", 100.0, -2.0, 1.0, "SOL", 0),
    )

    private fun portfolio(): com.coinportracker.onchain.AggregatedPortfolio {
        val w1 = HoldingSource(1, "Principal"); val w2 = HoldingSource(2, "Ledger")
        val sol = TokenHolding(Network.SOLANA, "native", "SOL", "Solana", 9, bd("10"), null, "coingecko:solana", HoldingKind.NATIVE, true, coingeckoId = "solana")
        val staked = TokenHolding(Network.SOLANA, "stake:abc", "SOL", "SOL em stake", 9, bd("5"), null, "coingecko:solana", HoldingKind.STAKED, true, coingeckoId = "solana")
        return Aggregator.aggregate(
            listOf(w1 to eth(Network.ETHEREUM, "1"), w2 to eth(Network.BASE, "0.5"), w1 to sol, w1 to staked),
            listOf(ManualPosition("btc", "BTC", "Bitcoin", bd("0.1"), "bitcoin", 50000.0, null, walletId = 9, walletName = "Corretora")),
            prices, minUsd = 0.0,
        )
    }

    @Test fun unrealizedPerAssetWalletAndNetwork() {
        val p = portfolio()
        val r = HoldingsPnlCalc.compute(p.assets, userAvgCost = mapOf("cg:ethereum" to bd("1500")), ledgerAvgCost = mapOf("cg:solana" to bd("120"), "cg:bitcoin" to bd("40000")),
            realized = bd("250"), realizedCostBasis = bd("1000"), income = bd("12.5"))
        // ETH: 1.5 × 2000 = 3000; custo 1.5 × 1500 = 2250 → +750 (+33,33%)
        val eth = r.assets.getValue("cg:ethereum")
        eq("3000", eth.value); eq("2250", eth.cost); eq("750", eth.pnl); assertEquals(CostSource.USER, eth.source)
        eq("33.3333", eth.pnlPct!!.setScale(4, java.math.RoundingMode.HALF_EVEN))
        // SOL líquido: 10 × 100 = 1000; custo 1200 → −200 (livro de operações)
        val sol = r.assets.getValue("cg:solana"); eq("-200", sol.pnl); assertEquals(CostSource.LEDGER, sol.source)
        // SOL em stake herda o custo de SOL: 5 × (100 − 120) = −100
        eq("-100", r.assets.getValue("staked:solana").pnl)
        // BTC manual: 0.1 × 50000 = 5000; custo 4000 → +1000
        eq("1000", r.assets.getValue("cg:bitcoin").pnl)
        eq("1450", r.unrealized) // 750 − 200 − 100 + 1000
        eq("1712.5", r.total)    // + realizado 250 + rendimentos 12,5
        eq("8050", r.openCost)   // 2250 + 1200 + 600 + 4000
        // %: 1712,5 / (8050 + 1000 de custo já vendido)
        eq("18.9227", r.totalPct)
        // Por carteira: carteira 2 tem 0,5 ETH em Base → valor 1000, custo 750
        val w2 = r.byWallet.getValue("2"); eq("1000", w2.value); eq("250", w2.pnl)
        val w1 = r.byWallet.getValue("1"); eq("3500", w1.value); eq("200", w1.pnl) // ETH +500, SOL −200, stake −100
        // Por rede
        eq("250", r.byNetwork.getValue("BASE").pnl); eq("500", r.byNetwork.getValue("ETHEREUM").pnl); eq("-300", r.byNetwork.getValue("SOLANA").pnl)
        eq("1000", r.byNetwork.getValue("MANUAL").pnl)
        assertEquals(0, r.missingCost)
    }

    @Test fun assetsWithoutCostAreExcludedFromPnl() {
        val p = portfolio()
        val r = HoldingsPnlCalc.compute(p.assets, emptyMap(), mapOf("cg:ethereum" to bd("2500")), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        eq("-750", r.unrealized) // 1.5 × (2000 − 2500)
        assertNull(r.assets.getValue("cg:solana").pnl)
        assertEquals(3, r.missingCost) // SOL, SOL em stake, BTC
        eq("6500", r.valueWithoutCost) // 1000 + 500 + 5000
        eq("-20", r.totalPct) // −750 / 3750
        eq("6500", r.byWallet.values.fold(BigDecimal.ZERO) { a, b -> a.add(b.valueWithoutCost) })
    }

    @Test fun userCostOverridesLedger() {
        val p = portfolio()
        val r = HoldingsPnlCalc.compute(p.assets, mapOf("cg:solana" to bd("50")), mapOf("cg:solana" to bd("120")), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        eq("500", r.assets.getValue("cg:solana").pnl); assertEquals(CostSource.USER, r.assets.getValue("cg:solana").source)
        eq("250", r.assets.getValue("staked:solana").pnl)
    }

    @Test fun noInvestmentGivesNullPct() {
        val r = HoldingsPnlCalc.compute(emptyList(), emptyMap(), emptyMap(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        assertNull(r.totalPct); eq("0", r.total)
    }
}
