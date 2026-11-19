package com.coinportracker.core

import com.coinportracker.domain.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class FmtBigTest {
    private val c = Currency.BRL
    private fun s(x: String) = x.replace('\u00A0', ' ')

    @Test fun moneyBelowBillionIsFull() = assertEquals("R$ 999.999.999,99", s(Fmt.money(BigDecimal("999999999.99"), c)))
    @Test fun moneyBillions() = assertEquals("R$ 1,23 bi", s(Fmt.money(BigDecimal("1234567890.12"), c)))
    @Test fun moneyTrillions() = assertEquals("R$ 4,57 tri", s(Fmt.money(BigDecimal("4567000000000"), c)))
    @Test fun moneyQuadrillions() = assertEquals("R$ 7,89 qua", s(Fmt.money(BigDecimal("7890000000000000"), c)))
    @Test fun signedNegativeTrillions() = assertEquals("-R$ 2,00 tri", s(Fmt.signedMoney(BigDecimal("-2e12"), c)))
    @Test fun priceBillions() = assertEquals("R$ 3,10 bi", s(Fmt.price(BigDecimal("3.1e9"), c)))
    @Test fun compactNegativeAndHuge() {
        assertEquals("-R$ 1,5 bi", s(Fmt.compact(BigDecimal("-1.5e9"), c)))
        assertEquals("R$ 2,50 qua", s(Fmt.compact(BigDecimal("2.5e15"), c)))
    }
    @Test fun qtyHugeSupply() {
        assertEquals("420,69 tri", Fmt.qty(BigDecimal("420690000000000")))
        assertEquals("1.234.567,12", Fmt.qty(BigDecimal("1234567.123456")))
    }
    @Test fun pctHugeStaysShort() {
        assertTrue(Fmt.pct(BigDecimal("123456789.12")).length <= 13)
        assertEquals("+12,34%", Fmt.pct(BigDecimal("12.34")))
        assertEquals(">+99.999%", Fmt.pct(BigDecimal("3720000000")))
        assertEquals("<-99.999%", Fmt.pct(BigDecimal("-3720000000")))
    }
    @Test fun maxLengthBounded() {
        var v = BigDecimal.ONE
        repeat(25) { v = v.multiply(BigDecimal.TEN); assertTrue("$v → ${Fmt.money(v, c)}", Fmt.money(v, c).length <= 18) }
    }
}
