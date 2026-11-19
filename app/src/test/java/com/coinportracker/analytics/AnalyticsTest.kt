package com.coinportracker.analytics

import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.TxType
import com.coinportracker.transactions.LedgerEngine
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.sqrt

class AnalyticsTest {
    @Test fun hhi_knownValues() {
        assertEquals(0, BigDecimal(10000).compareTo(Concentration.hhi(listOf(BigDecimal(5)))!!.setScale(0)))
        // 4 ativos iguais: 4 × 25² = 2500
        assertEquals(0, BigDecimal(2500).compareTo(Concentration.hhi(List(4) { BigDecimal(10) })!!.setScale(0, java.math.RoundingMode.HALF_EVEN)))
        assertEquals("Moderada", Concentration.level(BigDecimal(2500)))
        assertEquals("Alta", Concentration.level(BigDecimal(2501)))
        assertNull(Concentration.hhi(emptyList()))
    }

    @Test fun maxDrawdown_known() {
        val m = Statistics.maxDrawdown(listOf(100.0, 120.0, 60.0, 90.0, 130.0, 65.0))
        assertEquals(-0.5, m.value!!, 1e-12)
    }

    @Test fun volatility_requiresMinObservations() {
        assertNull(Statistics.annualizedVolatility(List(10) { 0.01 }).value)
        val r = List(40) { if (it % 2 == 0) 0.01 else -0.01 }
        val v = Statistics.annualizedVolatility(r).value!!
        assertEquals(Statistics.stdev(r) * sqrt(365.0), v, 1e-12)
    }

    @Test fun correlation_perfect() {
        val a = List(40) { (it % 7 - 3) / 100.0 }
        assertEquals(1.0, Statistics.correlation(a, a.map { it * 2 }).value!!, 1e-12)
        assertEquals(-1.0, Statistics.correlation(a, a.map { -it }).value!!, 1e-12)
        assertEquals(2.0, Statistics.beta(a.map { it * 2 }, a).value!!, 1e-12)
    }

    @Test fun var_percentile() {
        val r = (1..100).map { -it / 1000.0 } // -0.001 .. -0.1
        val v = Statistics.historicalVaR(r, 0.95).value!!
        assertEquals(Statistics.percentile(r, 0.05) * -1, v, 1e-12)
    }

    @Test fun sharpe_sortino_insufficientData() {
        assertNull(Statistics.sharpe(listOf(0.1), 0.0).value)
        assertNull(Statistics.sortino(List(40) { 0.01 }, 0.0).value) // sem retornos negativos
    }

    private fun ms(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun history_twr_neutralizesFlows_xirrPositive() {
        val d0 = LocalDate.of(2025, 1, 1)
        val txs = listOf(
            LedgerTx(1, TxType.BUY, ms(d0), "A", BigDecimal(10), BigDecimal(100), Currency.BRL),
            LedgerTx(2, TxType.BUY, ms(d0.plusDays(5)), "A", BigDecimal(10), BigDecimal("105.10100501"), Currency.BRL),
        )
        val ledger = LedgerEngine(CostMethod.WEIGHTED_AVERAGE, Currency.BRL).run(txs)
        // preço sobe 1% ao dia
        val book = TreeMap<Long, BigDecimal>()
        var p = 100.0
        for (i in 0..60) { book[d0.plusDays(i.toLong()).toEpochDay()] = BigDecimal(p); p *= 1.01 }
        val pts = PortfolioHistory.build(txs, ledger.flows(), mapOf("A" to book), d0.plusDays(60), zone = ZoneOffset.UTC)
        assertEquals(61, pts.size)
        assertTrue(pts.all { it.equity != null })
        val twr = PortfolioHistory.twr(pts).value!!
        // TWR deve refletir apenas o preço: 100 → 100×1,01^60, apesar do aporte no meio (dia 5, a preço de mercado).
        val priceReturn = book.lastEntry().value.toDouble() / 100.0 - 1
        assertTrue("twr=$twr price=$priceReturn", abs(twr - priceReturn) < 0.01)
        val x = PortfolioHistory.xirr(ledger.capitalFlows, pts.last().equity!!, d0.plusDays(60), ZoneOffset.UTC).value!!
        assertTrue(x > 0.5) // retorno anualizado alto
    }

    private fun com.coinportracker.transactions.LedgerResult.flows() = capitalFlows

    @Test fun history_missingPrice_marksDayIncomplete() {
        val d0 = LocalDate.of(2025, 1, 1)
        val txs = listOf(LedgerTx(1, TxType.BUY, ms(d0), "A", BigDecimal.ONE, BigDecimal.TEN, Currency.BRL))
        val book = TreeMap<Long, BigDecimal>().apply { put(d0.toEpochDay(), BigDecimal.TEN) }
        val pts = PortfolioHistory.build(txs, emptyList(), mapOf("A" to book), d0.plusDays(10), zone = ZoneOffset.UTC)
        assertNotNull(pts[3].equity)   // carry-forward até 3 dias
        assertNull(pts[4].equity)      // depois disso: indisponível, não inventado
        assertTrue("A" in pts[4].missingAssets)
    }

    @Test fun monteCarlo_deterministicWithSeed_percentilesOrdered() {
        val cfg = Simulations.MonteCarloConfig(10000.0, 365, 1000, 0.0, 0.6, 0.0, Simulations.Distribution.NORMAL, seed = 7)
        val a = Simulations.monteCarlo(cfg); val b = Simulations.monteCarlo(cfg)
        assertEquals(a.p50, b.p50, 0.0)
        assertTrue(a.p5 < a.p25 && a.p25 < a.p50 && a.p50 < a.p75 && a.p75 < a.p95)
        // Drift 0 (de preço): mediana ≈ 10000 × e^(−σ²/2) ≈ 8353
        assertEquals(10000 * kotlin.math.exp(-0.18), a.p50, 600.0)
    }

    @Test fun simulations_buyImpact_partialSale_dca() {
        val i = Simulations.buyImpact(BigDecimal(4), BigDecimal(1910), BigDecimal(1), BigDecimal(390))
        assertEquals(0, BigDecimal(460).compareTo(i.newAvg!!.setScale(2)))
        val s = Simulations.partialSaleAvg(BigDecimal(4), BigDecimal(1910), BigDecimal(2), BigDecimal(550))!!
        assertEquals(0, BigDecimal(145).compareTo(s.gain.setScale(2)))
        val dca = Simulations.dcaProjection(BigDecimal(1000), BigDecimal(100), BigDecimal.ZERO, 12)
        assertEquals(0, BigDecimal(2200).compareTo(dca.last().value.setScale(2, java.math.RoundingMode.HALF_EVEN)))
        val sc = Simulations.scenario("Queda", mapOf("A" to BigDecimal(100), "S" to BigDecimal(100)), mapOf("S" to BigDecimal.ZERO), BigDecimal(-50))
        assertEquals(0, BigDecimal(150).compareTo(sc.value.setScale(2)))
        val pv = Simulations.portfolioVolatility(listOf(0.5, 0.5), listOf(0.2, 0.2)) { _, _ -> 1.0 }
        assertEquals(0.2, pv, 1e-12)
    }
}
