package com.coinportracker.portfolio

import org.junit.Assert.*
import org.junit.Test

class RealPnlTest {
    @Test fun lossInBrlWithBreakEven() {
        // pagou R$ 10.000; vale US$ 1.600 a R$ 5 = R$ 8.000 → perda real de R$ 2.000 (-20%); precisa subir 25%
        val r = RealPnlCalc.compute(10_000.0, 1_600.0, 5.0, emptyList(), 0, 0.0)!!
        assertEquals(8_000.0, r.value, 1e-9)
        assertEquals(-2_000.0, r.result, 1e-9)
        assertEquals(-20.0, r.resultPct, 1e-9)
        assertEquals(25.0, r.breakEvenPct, 1e-9)
    }

    @Test fun worstBestAndDrawdownRespectSince() {
        val s = listOf(1L to 3_000.0, 10L to 2_000.0, 20L to 1_000.0, 30L to 2_500.0, 40L to 1_500.0)
        val r = RealPnlCalc.compute(10_000.0, 1_500.0, 5.0, s, since = 10, incomeUsd = 100.0)!!
        assertEquals(20L, r.worst!!.first); assertEquals(-5_000.0, r.worst!!.second, 1e-9)
        assertEquals(30L, r.best!!.first); assertEquals(2_500.0, r.best!!.second, 1e-9)
        // topo 12.500 (t=30) → 7.500 (t=40): 40% ; antes 10.000 → 5.000: 50%
        assertEquals(0.5, r.maxDrawdown!!, 1e-9)
        assertEquals(500.0, r.income, 1e-9)
        assertEquals(r.result - 500.0, r.market, 1e-9)
    }

    @Test fun noInvestedMeansNoResult() {
        assertNull(RealPnlCalc.compute(0.0, 100.0, 5.0, emptyList(), 0, 0.0))
    }

    /** Caso real reportado (3.1): pagou R$ 7.924, vale R$ 7.942 → +R$ ~18, nunca −R$ 1.643. */
    @Test fun reportedCasePaid7924Value7942() {
        val fx = 5.78
        val valueUsd = 7_942.83 / fx
        // série com "saldo fantasma" no início (R$ 1.900 sem histórico) e depois o aporte grande
        val s = listOf(0L to 1_900.0 / fx, 10L to 1_900.0 / fx, 20L to 9_500.0 / fx, 30L to 7_942.83 / fx)
        val principal = listOf(1_900.0 / fx, 1_900.0 / fx, 9_573.87 / fx, 9_573.87 / fx)
        val r = RealPnlCalc.compute(7_924.0, valueUsd, fx, s, 0, 3.02 / fx, principal)!!
        assertEquals(18.83, r.result, 0.01)
        assertTrue(r.inProfit)
        assertEquals(0.0, r.lossOnPrincipal, 1e-9)
        assertTrue(r.peakProfit >= r.result)
        assertEquals(r.peakProfit - r.result, r.givenBack, 1e-9)
        // principal reescalado termina exatamente no valor pago
        assertEquals(7_924.0, RealPnlCalc.scaledPrincipal(7_924.0, principal, fx).last(), 1e-9)
    }

    @Test fun lossOnPrincipalAndGivenBackAreSeparate() {
        // pagou 1000; chegou a valer 1300 (lucro máx 300); hoje vale 900 → devolveu 300 do lucro e perdeu 100 do aporte
        val s = listOf(0L to 1_000.0, 1L to 1_300.0, 2L to 900.0)
        val r = RealPnlCalc.compute(1_000.0, 900.0, 1.0, s, 0, 0.0)!!
        assertEquals(300.0, r.peakProfit, 1e-9)
        assertEquals(300.0, r.givenBack, 1e-9)
        assertEquals(100.0, r.lossOnPrincipal, 1e-9)
    }

    @Test fun stakingYieldIsExcludedFromResult() {
        // pagou R$ 7.924; vale R$ 7.895,86, dos quais R$ 50 são rendimento de staking
        // P&L = 7.895,86 − 7.924 − 50 = −78,14; rendimento mostrado à parte
        val r = RealPnlCalc.compute(7_924.0, 7_895.86, 1.0, emptyList(), 0, 0.0, stakingYieldCur = 50.0)!!
        assertEquals(7_895.86, r.value, 1e-9)
        assertEquals(50.0, r.income, 1e-9)
        assertEquals(-78.14, r.result, 1e-9)
        assertEquals(-78.14, r.market, 1e-9)
        assertEquals(78.14, r.lossOnPrincipal, 1e-9)
        assertFalse(r.inProfit)
    }

    @Test fun stakedPrincipalStillCountsWhenYieldIsZero() {
        val r = RealPnlCalc.compute(7_924.0, 7_895.86, 1.0, emptyList(), 0, 0.0)!!
        assertEquals(-28.14, r.result, 1e-9)
        assertEquals(0.0, r.income, 1e-9)
    }

    @Test fun flatPrincipalHasNoSteps() {
        val line = RealPnlCalc.flatPrincipal(5, 7_924.0)
        assertEquals(listOf(7_924.0, 7_924.0, 7_924.0, 7_924.0, 7_924.0), line)
    }
}
