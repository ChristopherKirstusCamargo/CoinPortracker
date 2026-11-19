package com.coinportracker.dev

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConfigRoundTripTest {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun roundTripPreservesOverrides() {
        val c = DevConfig(
            chains = listOf(ChainSources("SOLANA", listOf(
                DevEndpoint("1", "https://solana-rpc.publicnode.com", true, "RPC", "publicnode"),
                DevEndpoint("2", "https://api.mainnet-beta.solana.com", false, "RPC", "oficial"),
            ))),
            prices = DevDefaults.prices().map { if (it.id == "paprika") it.copy(enabled = false) else it },
            customChains = listOf(CustomEvmChain("mega", "Mega Chain", 999001, "https://rpc.mega.example", nativeSymbol = "MEGA", nativeDecimals = 18)),
            customTokens = listOf(CustomToken("usdx", "ETHEREUM", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6, "USD Coin")),
            net = DevNetSettings(8, 15, 25),
            logRedactAddresses = false,
        )
        assertTrue(DevValidator.validate(c).ok)
        val raw = json.encodeToString(DevConfig.serializer(), c)
        val back = json.decodeFromString(DevConfig.serializer(), raw)
        assertEquals(c, back)
        assertTrue("SOLANA" in raw)
        assertTrue("999001" in raw)
        assertFalse(back.prices.first { it.id == "paprika" }.enabled)
        assertEquals(8, back.net.connectTimeoutSec)
    }

    @Test fun importRejectsInvalid() {
        val raw = json.encodeToString(DevConfig.serializer(), DevConfig(
            customChains = listOf(CustomEvmChain("x", "X", -1, "ftp://nope", nativeSymbol = "")),
        ))
        val parsed = json.decodeFromString(DevConfig.serializer(), raw)
        assertFalse(DevValidator.validate(parsed).ok)
    }

    @Test fun unknownKeysDoNotBreakImport() {
        val raw = """{"version":1,"prices":[],"extraFutureField":{"a":1}}"""
        val c = json.decodeFromString(DevConfig.serializer(), raw)
        assertEquals(1, c.version)
    }
}
