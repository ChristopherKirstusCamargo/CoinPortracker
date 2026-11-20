package com.coinportracker.tax

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class TaxEngineTest {
    private val zone = ZoneOffset.UTC
    private val rules = TaxRules.default()

    private fun ts(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay().toInstant(zone).toEpochMilli()

    @Test fun purchaseLotsAreScaledToInvestedTotal() {
        val ev = listOf(
            TaxLotEvent(ts(2025, 1, 10), "sol", "SOL", BigDecimal("10"), "IN", unitBrl = BigDecimal("100"), estimatedPrice = false),
            TaxLotEvent(ts(2025, 6, 1), "btc", "BTC", BigDecimal("0.1"), "IN", unitBrl = BigDecimal("300000"), estimatedPrice = false),
        )
        val r = TaxEngine.build(ev, emptyList(), BigDecimal("7924.00"), rules, now = ts(2026, 6, 1), zone = zone)
        assertTrue(r.investedAllocated)
        val sum = r.lots.filter { it.origin == LotOrigin.PURCHASE }.fold(BigDecimal.ZERO) { a, l -> a.add(l.costBrl) }
        assertEquals(0, sum.compareTo(BigDecimal("7924.00")))
        r.lots.forEach { assertTrue(it.estimated) }
    }

    @Test fun yearEndUsesCostNotMarketAndRespectsCutoff() {
        val ev = listOf(
            TaxLotEvent(ts(2025, 3, 1), "sol", "SOL", BigDecimal("10"), "IN", unitBrl = BigDecimal("200"), estimatedPrice = false),
            TaxLotEvent(ts(2026, 2, 1), "sol", "SOL", BigDecimal("5"), "IN", unitBrl = BigDecimal("200"), estimatedPrice = false),
        )
        val r = TaxEngine.build(ev, listOf(TaxPosition("sol", "SOL", BigDecimal("15"), BigDecimal("500"))), null, rules, now = ts(2026, 11, 13), zone = zone)
        val y25 = r.holdingsByYear[2025]!!.single()
        assertEquals(0, y25.qty.compareTo(BigDecimal("10")))
        assertEquals(0, y25.costBrl.compareTo(BigDecimal("2000")))
        assertEquals("08", y25.group)
        assertEquals("02", y25.code)
        val y26 = r.holdingsByYear[2026]!!.single()
        assertEquals(0, y26.qty.compareTo(BigDecimal("15")))
    }

    @Test fun monthlyExemptionOnTotalAlienations() {
        val ev = listOf(
            TaxLotEvent(ts(2026, 4, 1), "sol", "SOL", BigDecimal("10"), "IN", unitBrl = BigDecimal("1000"), estimatedPrice = false),
            TaxLotEvent(ts(2026, 4, 15), "sol", "SOL", BigDecimal("2"), "OUT", unitBrl = BigDecimal("2000"), estimatedPrice = false),
        )
        val r = TaxEngine.build(ev, emptyList(), null, rules, now = ts(2026, 11, 1), zone = zone)
        val apr = r.months.single()
        assertEquals(0, apr.proceedsBrl.compareTo(BigDecimal("4000")))
        assertTrue(apr.exempt)
        assertEquals(0, apr.suggestedTax.compareTo(BigDecimal.ZERO))
        // venda grande acima do limite
        val ev2 = ev + TaxLotEvent(ts(2026, 5, 2), "sol", "SOL", BigDecimal("8"), "OUT", unitBrl = BigDecimal("5000"), estimatedPrice = false)
        val r2 = TaxEngine.build(ev2, emptyList(), null, rules, now = ts(2026, 11, 1), zone = zone)
        val may = r2.months.single { it.month.monthValue == 5 }
        assertFalse(may.exempt)
        assertTrue(may.suggestedTax.signum() > 0)
    }

    @Test fun stakingRewardIsIncomeAndNotPurchase() {
        val ev = listOf(
            TaxLotEvent(ts(2026, 1, 1), "sol", "SOL", BigDecimal("10"), "IN", unitBrl = BigDecimal("800"), estimatedPrice = false),
            TaxLotEvent(ts(2026, 3, 1), "sol", "SOL", BigDecimal("0.5"), "REWARD", unitBrl = BigDecimal("800"), estimatedPrice = false),
        )
        val r = TaxEngine.build(ev, emptyList(), BigDecimal("8000"), rules, now = ts(2026, 11, 1), zone = zone)
        assertEquals(1, r.stakingIncome.size)
        assertEquals(0, r.stakingIncome.single().qty.compareTo(BigDecimal("0.5")))
        val purchases = r.lots.filter { it.origin == LotOrigin.PURCHASE }
        assertEquals(0, purchases.fold(BigDecimal.ZERO) { a, l -> a.add(l.costBrl) }.compareTo(BigDecimal("8000")))
        val rewards = r.lots.filter { it.origin == LotOrigin.REWARD }
        assertEquals(1, rewards.size)
    }

    @Test fun currentYearHoldingsReconcileToPositionsWhenHistoryIsShort() {
        val ev = listOf(
            TaxLotEvent(ts(2026, 10, 1), "btc", "BTC", BigDecimal("0.015"), "IN", unitBrl = BigDecimal("300000"), estimatedPrice = true),
        )
        val pos = listOf(TaxPosition("btc", "BTC", BigDecimal("0.08732"), BigDecimal("500000")))
        val r = TaxEngine.build(ev, pos, BigDecimal("7924.00"), rules, now = ts(2026, 11, 13), zone = zone)
        val y26 = r.holdingsByYear[2026]!!.single { it.symbol == "BTC" }
        assertEquals(0, y26.qty.compareTo(BigDecimal("0.08732")))
        assertTrue(y26.estimated)
        assertTrue(r.notes.any { it.contains("histórico on-chain não explica") })
        // ano anterior não recebe a posição atual
        assertTrue(r.holdingsByYear[2025].isNullOrEmpty())
        val purchases = r.lots.filter { it.origin == LotOrigin.PURCHASE }
        val sum = purchases.fold(BigDecimal.ZERO) { a, l -> a.add(l.costBrl) }
        assertEquals(0, sum.compareTo(BigDecimal("7924.00")))
    }

    @Test fun codesBtcStableAlt() {
        assertEquals("01", rules.codeFor("BTC").code)
        assertEquals("03", rules.codeFor("USDC").code)
        assertEquals("03", rules.codeFor("USDT").code)
        assertEquals("02", rules.codeFor("SOL").code)
        assertEquals("10", rules.codeFor("NFT").code)
    }

    @Test fun taxBrackets() {
        val tax = rules.taxOnGain(BigDecimal("1000"))
        assertEquals(0, tax.compareTo(BigDecimal("150.00")))
    }

    @Test fun csvContainsSections() {
        val ev = listOf(TaxLotEvent(ts(2026, 1, 1), "sol", "SOL", BigDecimal("1"), "IN", unitBrl = BigDecimal("100"), estimatedPrice = true))
        val r = TaxEngine.build(ev, emptyList(), BigDecimal("100"), rules, now = ts(2026, 11, 1), zone = zone)
        val csv = TaxCsv.build(r)
        assertTrue(csv.contains("BENS E DIREITOS"))
        assertTrue(csv.contains("LIVRO DE AQUISIÇÃO"))
        assertTrue(csv.contains("ALIENAÇÕES POR MÊS"))
        assertTrue(csv.contains("RENDIMENTOS DE STAKING"))
        assertTrue(csv.contains(rules.disclaimer.take(20)))
    }

    @Test fun rulesRoundTrip() {
        val raw = TaxRules.stringify(rules)
        val back = TaxRules.parse(raw)
        assertEquals(rules.version, back.version)
        assertEquals(rules.monthlyExemptionBrl, back.monthlyExemptionBrl)
        assertEquals(5, back.codes.size)
    }
}
