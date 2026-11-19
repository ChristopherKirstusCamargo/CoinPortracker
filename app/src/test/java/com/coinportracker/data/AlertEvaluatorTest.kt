package com.coinportracker.data

import com.coinportracker.database.AlertEntity
import com.coinportracker.domain.Currency
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AlertEvaluatorTest {
    @Test fun portfolioDayFiresWhenAbsChangeExceedsThreshold() {
        val a = AlertEntity(id = 1, kind = AlertKind.PORTFOLIO_DAY.name, label = "dia", threshold = "5", createdAt = 1)
        val (hits, _) = AlertEvaluator.evaluate(listOf(a), emptyList(), BigDecimal("1000"), null, Currency.BRL, now = 10_000L, dayChangePct = BigDecimal("-7.5"))
        assertEquals(1, hits.size)
        val (none, _) = AlertEvaluator.evaluate(listOf(a), emptyList(), BigDecimal("1000"), null, Currency.BRL, now = 10_000L, dayChangePct = BigDecimal("2.0"))
        assertTrue(none.isEmpty())
    }

    @Test fun livePriceMatchesGroupKey() {
        val a = AlertEntity(id = 2, kind = AlertKind.PRICE_ABOVE.name, assetId = "cg:solana", label = "SOL", threshold = "100", currency = "BRL", createdAt = 1)
        val live = listOf(AlertEvaluator.Price("cg:solana", "SOL", BigDecimal("150"), null, Currency.BRL, "portfólio"))
        val (hits, _) = AlertEvaluator.evaluate(listOf(a), emptyList(), null, null, Currency.BRL, 1L, live = live)
        assertEquals(1, hits.size)
    }
}
