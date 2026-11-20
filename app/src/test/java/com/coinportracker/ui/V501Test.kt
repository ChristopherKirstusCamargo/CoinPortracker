package com.coinportracker.ui

import com.coinportracker.core.Plural
import com.coinportracker.data.WalletDedupe
import com.coinportracker.database.WalletEntity
import com.coinportracker.settings.AnimCurve
import com.coinportracker.settings.AnimPrefs
import com.coinportracker.settings.AppSettings
import com.coinportracker.settings.NumberAnim
import com.coinportracker.settings.ScreenAnim
import com.coinportracker.settings.SettingsRepository
import com.coinportracker.ui.anim.NumberMorph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V501Test {
    // --- Contagem suave: o molde mantém prefixo, sinal, separadores e casas ---
    @Test fun morphKeepsBrlFormat() {
        val t = NumberMorph.parse("R$ 7.927,03")!!
        assertEquals(7927.03, t.value, 1e-9)
        assertEquals("R$ 7.927,03", NumberMorph.format(t, 7927.03))
        assertEquals("R$ 12.345,60", NumberMorph.format(t, 12345.6))
        assertEquals("R$ 0,50", NumberMorph.format(t, 0.5))
    }
    @Test fun morphSignedValues() {
        val pos = NumberMorph.parse("+R$ 3,03")!!
        assertEquals("+R$ 1,50", NumberMorph.format(pos, 1.5))
        assertEquals("-R$ 2,00", NumberMorph.format(pos, -2.0))
        val neg = NumberMorph.parse("-R$ 3,03")!!
        assertEquals(-3.03, neg.value, 1e-9)
        assertEquals("-R$ 1,00", NumberMorph.format(neg, -1.0))
        assertEquals("R$ 0,00", NumberMorph.format(neg, 0.0001))
    }
    @Test fun morphUsdAndPercent() {
        val t = NumberMorph.parse("US$ 1.234.567,89")!!
        assertEquals("US$ 1.000,00", NumberMorph.format(t, 1000.0))
        val p = NumberMorph.parse("-3,59%")!!
        assertEquals("-3,27%", NumberMorph.format(p, -3.27))
    }
    @Test fun morphLerpEndsOnTarget() {
        val t = NumberMorph.parse("R$ 200,00")!!
        assertEquals("R$ 100,00", NumberMorph.lerpText(100.0, t, 0f))
        assertEquals("R$ 150,00", NumberMorph.lerpText(100.0, t, 0.5f))
        assertEquals("R$ 200,00", NumberMorph.lerpText(100.0, t, 1f))
    }
    @Test fun morphRejectsMasked() { assertNull(NumberMorph.parse("R$ •••")) }

    // --- Plural ---
    @Test fun plural() {
        assertEquals("1 ligada", Plural.of(1, "ligada", "ligadas"))
        assertEquals("4 ligadas", Plural.of(4, "ligada", "ligadas"))
        assertEquals("0 ligadas", Plural.of(0, "ligada", "ligadas"))
    }

    // --- Carteira duplicada ---
    private fun w(id: Long, addr: String, chain: String = "BITCOIN", archived: Boolean = false) =
        WalletEntity(id = id, name = "Carteira Bitcoin p6tk", type = "WATCH_ONLY", chain = chain, address = addr, archived = archived, createdAt = 0)
    @Test fun dedupeKeepsOldest() {
        val a = "bc1qexampleaddressxxxxxxxxxxxxxxxxxxp6tk"
        assertEquals(setOf(5L), WalletDedupe.duplicates(listOf(w(5, a.uppercase()), w(2, a), w(3, "bc1qother"))))
    }
    @Test fun dedupeIgnoresArchivedAndCaseSensitiveBase58() {
        assertTrue(WalletDedupe.duplicates(listOf(w(1, "bc1qaa"), w(2, "bc1qaa", archived = true))).isEmpty())
        assertTrue(WalletDedupe.duplicates(listOf(w(1, "So1aBc", "SOLANA"), w(2, "so1abc", "SOLANA"))).isEmpty())
    }

    // --- Animações: padrões = v5.0 e persistência tolerante ---
    @Test fun animDefaultsMatchV50() {
        val a = AnimPrefs()
        assertEquals(NumberAnim.ROLL, a.number); assertEquals(560, a.numberMs); assertEquals(AnimCurve.SMOOTH, a.curve)
        assertEquals(ScreenAnim.SLIDE, a.screen); assertEquals(1f, a.speed); assertEquals(560, a.dur(560)); assertEquals(280, a.dur(560, 2f))
        // configurações antigas (sem as chaves novas) carregam os padrões
        assertEquals(AnimPrefs(), SettingsRepository.fromMap(emptyMap()).anim)
        assertEquals(false, AppSettings().lowPower)
    }
    @Test fun animRoundTripAndClamp() {
        val s = AppSettings(anim = AnimPrefs(NumberAnim.COUNT, 900, AnimCurve.SPRING, ScreenAnim.SCALE, 1.5f, false, 0.5f, false, false, 2f))
        assertEquals(s.anim, SettingsRepository.fromMap(SettingsRepository.toMap(s)).anim)
        val bad = SettingsRepository.fromMap(mapOf("anMs" to "99999", "anSpeed" to "NaN", "anNum" to "XYZ"))
        assertEquals(AnimPrefs.MAX_MS, bad.anim.numberMs); assertEquals(1f, bad.anim.speed); assertEquals(NumberAnim.ROLL, bad.anim.number)
    }
}
