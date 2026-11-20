package com.coinportracker.tax

import org.junit.Assert.*
import org.junit.Test

class TaxRulesValidationTest {
    @Test fun defaultIsValid() { assertTrue(TaxRules.validate(TaxRules.default()).isEmpty()) }

    @Test fun rejectsIncoherentImports() {
        val d = TaxRules.default()
        assertFalse(TaxRules.validate(d.copy(brackets = listOf(TaxBracket("100", "15"), TaxBracket("50", "20")))).isEmpty())
        assertFalse(TaxRules.validate(d.copy(brackets = listOf(TaxBracket("100", "150")))).isEmpty())
        assertFalse(TaxRules.validate(d.copy(monthlyExemptionBrl = "abc")).isEmpty())
        assertFalse(TaxRules.validate(d.copy(effectiveFrom = "2026/01/01")).isEmpty())
        assertFalse(TaxRules.validate(d.copy(brackets = emptyList())).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { TaxRules.parseValidated(TaxRules.stringify(d.copy(monthlyExemptionBrl = "-1"))) }
        assertThrows(IllegalArgumentException::class.java) { TaxRules.parseValidated("x".repeat(300_000)) }
        assertEquals(d, TaxRules.parseValidated(TaxRules.stringify(d)))
    }

    @Test fun missingCodes_doNotCrash() {
        val r = TaxRules.default().copy(codes = listOf(AssetCodeRule("99", "Outros")))
        assertEquals("01", r.codeFor("BTC").code)
        assertEquals("03", r.codeFor("USDC").code)
        assertEquals("02", r.codeFor("SOL").code)
    }
}
