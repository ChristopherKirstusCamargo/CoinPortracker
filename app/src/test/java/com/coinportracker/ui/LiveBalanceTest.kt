package com.coinportracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LiveBalanceTest {
    @Test fun pathStaysBetweenRealValuesAndEndsOnTarget() {
        val rnd = Random(7)
        repeat(500) {
            val from = 1_000 + rnd.nextDouble() * 200_000
            val to = from * (1 + (rnd.nextDouble() - 0.5) * 0.02)
            val p = LiveBalance.path(from, to)
            assertEquals(LiveBalance.STEPS, p.size)
            assertEquals(to, p.last(), 1e-9)
            val lo = minOf(from, to); val hi = maxOf(from, to)
            var prev = from
            for (v in p) {
                assertTrue("fora do intervalo real", v >= lo - 1e-9 && v <= hi + 1e-9)
                // monotônico: nunca volta (sem "tremidas" inventadas)
                if (to >= from) assertTrue(v >= prev - 1e-9) else assertTrue(v <= prev + 1e-9)
                prev = v
            }
        }
    }

    @Test fun stepIsBoundedByRealChange() {
        // variação real de R$ 3 em 14 s: nenhum passo pode passar de R$ 3 e o primeiro é ~R$ 0,21
        val p = LiveBalance.path(50_000.0, 50_003.0)
        val steps = (listOf(50_000.0) + p).zipWithNext { a, b -> b - a }
        assertTrue(steps.all { it in 0.0..3.0 })
        assertEquals(3.0 / LiveBalance.STEPS, steps.first(), 1e-9)
    }

    @Test fun noChangeMeansNoMovement() {
        assertTrue(LiveBalance.path(1234.56, 1234.56).all { it == 1234.56 })
    }

    @Test fun screenShowsTheRealTotalNotAStep() {
        assertEquals(50_003.0, LiveBalance.displayed(50_003.0), 0.0)
        val steps = LiveBalance.path(50_000.0, 50_003.0)
        assertTrue(steps.first() != LiveBalance.displayed(50_003.0))
    }
}

class SlotParseTest {
    @Test fun parsesBrazilianMoney() {
        assertEquals(12345.67, com.coinportracker.ui.components.parseNumber("R$ 12.345,67")!!, 1e-9)
        assertEquals(-3.5, com.coinportracker.ui.components.parseNumber("−R$ 3,50")!!, 1e-9)
        assertEquals(null, com.coinportracker.ui.components.parseNumber("•••••"))
    }
}
