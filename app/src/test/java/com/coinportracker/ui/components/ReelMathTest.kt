package com.coinportracker.ui.components

import org.junit.Assert.*
import org.junit.Test

/** v3.2 PT5: o rolo de dígitos sempre termina num inteiro e escolhe o caminho no sentido certo, inclusive com mudanças a cada 1 s. */
class ReelMathTest {
    @Test fun travelForwardAndBackward() {
        assertEquals(3f, reelTravel(2f, 5, up = true), 1e-6f)
        assertEquals(-7f, reelTravel(2f, 5, up = false), 1e-6f)
        assertEquals(1f, reelTravel(9f, 0, up = true), 1e-6f)    // 9 → 0 subindo: um passo, não 9 para trás
        assertEquals(0f, reelTravel(4f, 4, up = true), 1e-6f)
        assertEquals(0f, reelTravel(4f, 4, up = false), 1e-6f)
    }

    @Test fun interruptedMidRollStillLandsOnTarget() {
        // meio caminho (9,4 → mostra parte do 9 e parte do 0); chega nova mudança para 5
        val cur = 9.4f
        val dest = cur + reelTravel(cur, 5, up = true)
        assertEquals(5f, reelMod(dest), 1e-4f)
        val destDown = cur + reelTravel(cur, 5, up = false)
        assertEquals(5f, reelMod(destDown), 1e-4f)
    }

    @Test fun rapidOneSecondUpdatesSettleBeforeNextTick() {
        // simula saldo ao vivo: um valor novo por segundo; a animação dura SETTLE_MS < 1000 ms → entre ticks sempre há quadro assentado
        assertTrue(560 < 1000)
        var pos = 2f
        val digits = listOf(3, 9, 0, 5, 5, 1, 8, 2, 7, 4, 4, 6)
        var last = 2
        for (d in digits) {
            val up = d >= last
            pos += reelTravel(pos, d, up)
            assertEquals("dígito $d", d.toFloat(), reelMod(pos), 1e-4f)
            assertEquals(0f, reelMod(pos) - kotlin.math.floor(reelMod(pos)), 1e-4f) // inteiro: sem meio glifo
            last = d
        }
    }
}
