package com.coinportracker.dev

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DevValidatorTest {
    private fun ok(c: DevConfig = DevDefaults.empty()) = DevValidator.validate(c)

    @Test fun defaultsAreValid() {
        val v = ok(DevDefaults.materialize(DevDefaults.empty()))
        assertTrue(v.errors.joinToString(), v.ok)
    }

    @Test fun rejectsEmptyUrl() {
        val c = DevDefaults.empty().copy(chains = listOf(ChainSources("ETHEREUM", listOf(DevEndpoint("1", "", kind = "RPC")))))
        assertTrue(DevValidator.validate(c).errors.any { it.contains("URL vazia") })
    }

    @Test fun rejectsJavascriptUrl() {
        val c = DevDefaults.empty().copy(prices = listOf(PriceSource("x", true, "javascript:alert(1)", "defillama", "X")))
        assertTrue(DevValidator.validate(c).errors.any { "http ou https" in it })
    }

    @Test fun rejectsPrivateKeyHex() {
        val key = "0" + "ab".repeat(32) // 64 hex? "0" + 64 chars = 65. Use 64.
        val hex = "ab".repeat(32)
        assertEquals(64, hex.length)
        val msg = DevValidator.secret(hex, "RPC")
        assertTrue(msg, msg != null && msg.contains("chave privada"))
        assertTrue(DevValidator.secret("0x" + hex, "RPC") != null)
        assertTrue(DevValidator.secret("xprv123", "campo") != null)
    }

    @Test fun rejectsMnemonic() {
        val seed = (1..12).joinToString(" ") { "abandon" }
        assertTrue(DevValidator.secret(seed, "campo") != null)
        val v = DevValidator.validate(DevDefaults.empty().copy(customChains = listOf(
            CustomEvmChain("c", "Test", 99999, seed, nativeSymbol = "TST")
        )))
        assertFalse(v.ok)
        assertTrue(v.errors.any { it.contains("seed") || it.contains("observação") })
    }

    @Test fun acceptsHttpsRpc() {
        val v = DevValidator.url("https://ethereum-rpc.publicnode.com", "ETH")
        assertTrue(v.isEmpty())
    }

    @Test fun customChainValidation() {
        val bad = CustomEvmChain("x", "", 0, "not-a-url", nativeSymbol = "??", nativeDecimals = 99)
        val v = DevValidator.validate(DevDefaults.empty().copy(customChains = listOf(bad)))
        assertTrue(v.errors.any { it.contains("nome") })
        assertTrue(v.errors.any { it.contains("chain id") })
        assertTrue(v.errors.any { it.contains("URL") || it.contains("http") })
        assertTrue(v.errors.any { it.contains("símbolo") })
        assertTrue(v.errors.any { it.contains("decimais") })
    }

    @Test fun rejectsDuplicateAndBuiltInChainId() {
        val a = CustomEvmChain("a", "A", 1, "https://rpc.a.example", nativeSymbol = "AAA")
        val v = DevValidator.validate(DevDefaults.empty().copy(customChains = listOf(a)))
        assertTrue(v.errors.any { it.contains("já é de") })
        val b = CustomEvmChain("b", "B", 424242, "https://rpc.b.example", nativeSymbol = "BBB")
        val c = CustomEvmChain("c", "C", 424242, "https://rpc.c.example", nativeSymbol = "CCC")
        val v2 = DevValidator.validate(DevDefaults.empty().copy(customChains = listOf(b, c)))
        assertTrue(v2.errors.any { it.contains("duplicado") })
    }

    @Test fun customTokenMustBeEvmAddressOnKnownChain() {
        val tok = CustomToken("t", "ETHEREUM", "not-an-address", "USDX", 6)
        val v = DevValidator.validate(DevDefaults.empty().copy(customTokens = listOf(tok)))
        assertTrue(v.errors.any { it.contains("contrato") })
        val okTok = CustomToken("t", "ETHEREUM", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6)
        assertTrue(DevValidator.validate(DevDefaults.empty().copy(customTokens = listOf(okTok))).ok)
        val unknown = okTok.copy(chain = "NOPE")
        assertTrue(DevValidator.validate(DevDefaults.empty().copy(customTokens = listOf(unknown))).errors.any { it.contains("desconhecida") })
    }

    @Test fun timeoutsRange() {
        assertTrue(DevValidator.timeouts(DevNetSettings(0, 20, 30)).isNotEmpty())
        assertTrue(DevValidator.timeouts(DevNetSettings(10, 20, 5)).any { it.contains("chamada") })
        assertTrue(DevValidator.timeouts(DevNetSettings()).isEmpty())
    }

    @Test fun redactAddresses() {
        val s = DevValidator.redact("envio para 0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d e bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh")
        assertFalse(s.contains("0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d"))
        assertFalse(s.contains("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        assertTrue(s.contains("…"))
        // texto comum permanece
        assertTrue("envio para" in s)
    }
}
