package com.coinportracker.dev

import com.coinportracker.onchain.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DevSourcesTest {
    @Before fun reset() { DevSources.replace(DevDefaults.empty()) }

    @Test fun defaultsMatchBuiltInRpcs() {
        assertEquals(Network.ETHEREUM.rpcs, DevSources.rpcs(Network.ETHEREUM))
        assertEquals(Network.POLYGON.blockscout, DevSources.indexer(Network.POLYGON))
    }

    @Test fun overrideOrderAndDisable() {
        val eps = listOf(
            DevEndpoint("a", "https://first.example", true, "RPC"),
            DevEndpoint("b", "https://second.example", true, "RPC"),
            DevEndpoint("c", "https://off.example", false, "RPC"),
            DevEndpoint("d", "https://idx.example", true, "INDEXER"),
        )
        DevSources.replace(DevConfig(chains = listOf(ChainSources("ETHEREUM", eps))))
        assertEquals(listOf("https://first.example", "https://second.example"), DevSources.rpcs(Network.ETHEREUM))
        assertFalse(DevSources.rpcs(Network.ETHEREUM).contains("https://off.example"))
        assertEquals("https://idx.example", DevSources.indexer(Network.ETHEREUM))
        // outras redes seguem o padrão
        assertEquals(Network.SOLANA.rpcs, DevSources.rpcs(Network.SOLANA))
    }

    @Test fun allDisabledFallsBackToDefault() {
        val eps = listOf(DevEndpoint("a", "https://x.example", false, "RPC"))
        DevSources.replace(DevConfig(chains = listOf(ChainSources("BASE", eps))))
        assertEquals(Network.BASE.rpcs, DevSources.rpcs(Network.BASE))
    }

    @Test fun failoverSkipsFailures() = runBlocking {
        val urls = listOf("https://dead.example", "https://ok.example", "https://later.example")
        val tried = ArrayList<String>()
        val r = DevSources.failover(urls) { u ->
            tried += u
            if (u.contains("ok")) "yes" else null
        }
        assertEquals("yes", r)
        assertEquals(listOf("https://dead.example", "https://ok.example"), tried)
    }

    @Test fun priceEnableAndUrl() {
        DevSources.replace(DevConfig(prices = listOf(
            PriceSource("kraken", false, "https://api.kraken.com", "kraken", "Kraken"),
            PriceSource("defillama", true, "https://llama.custom.example", "defillama", "DefiLlama"),
        )))
        assertFalse(DevSources.priceEnabled("kraken"))
        assertTrue(DevSources.priceEnabled("defillama"))
        assertEquals("https://llama.custom.example", DevSources.priceUrl("defillama", DevDefaults.LLAMA))
    }
}
