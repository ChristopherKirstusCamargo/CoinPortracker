package com.coinportracker.market

import org.junit.Assert.*
import org.junit.Test

class MarketFilterTest {
    private fun c(id: String, name: String = id) = MarketCoin(id, id.take(4).uppercase(), name, rank = 0)

    @Test fun removesTokenizedRwa_keepsCryptoAndStables_renumbers() {
        val raw = listOf(c("bitcoin"), c("ethereum"), c("tether"), c("figure-heloc", "Figure Heloc"), c("tether-gold", "Tether Gold"),
            c("solana"), c("some-new-fund", "Acme US Treasury Fund"), c("usd-coin", "USDC"), c("x-from-api"))
        val top = MarketFilter.top(raw, excluded = setOf("x-from-api"))
        assertEquals(listOf("bitcoin", "ethereum", "tether", "solana", "usd-coin"), top.map { it.id })
        assertEquals((1..5).toList(), top.map { it.rank })
    }

    @Test fun capsAt100_andDedups() {
        val raw = (0 until 160).map { c("coin$it") } + c("coin1")
        val top = MarketFilter.top(raw, emptySet())
        assertEquals(100, top.size)
        assertEquals(100, top.map { it.id }.toSet().size)
    }

    @Test fun realSnapshot_noJunkInTop100() {
        // amostra real do CoinGecko (13/11/2026): FIGR_HELOC em 9º, ouro, fundos de Tesouro e CLO no top 100
        val junk = listOf("figure-heloc", "tether-gold", "pax-gold", "ondo-us-dollar-yield", "hashnote-usyc", "spiko-amundi-overnight-swap-fund-eur",
            "blackrock-usd-institutional-digital-liquidity-fund", "superstate-short-duration-us-government-securities-fund-ustb", "janus-henderson-anemoy-aaa-clo-fund")
        val raw = listOf("bitcoin", "ethereum", "tether", "binancecoin", "usd-coin", "ripple", "solana", "tron").map { c(it) } + junk.map { c(it) } + c("hyperliquid") + c("chainlink") + c("stellar") + c("ondo-finance")
        val top = MarketFilter.top(raw, emptySet())
        assertTrue(top.none { it.id in junk })
        // tokens de protocolos de RWA (Chainlink, Stellar, Ondo) são cripto e ficam
        assertTrue(top.map { it.id }.containsAll(listOf("chainlink", "stellar", "ondo-finance", "hyperliquid")))
        assertEquals("hyperliquid", top[8].id)
    }
}

class SparkRangeTest {
    @Test fun stablecoinGetsMinimumRange() {
        val usdt = listOf(0.9991, 1.0003, 0.9998, 1.0012, 0.9995)
        val r = SparkRange.of(usdt)!!
        assertTrue("faixa mínima de 3% do preço", r.span >= 0.0299)
        assertTrue(r.lo <= usdt.min() && r.hi >= usdt.max())
        // a variação real ocupa só uma fração da altura (linha quase reta, como deve ser)
        val used = (SparkRange.norm(usdt.max(), r) - SparkRange.norm(usdt.min(), r))
        assertTrue(used < 0.2)
    }

    @Test fun volatileCoinUsesFullRange() {
        val btc = listOf(60_000.0, 64_000.0, 62_000.0, 70_000.0)
        val r = SparkRange.of(btc)!!
        assertEquals(60_000.0, r.lo, 1e-9); assertEquals(70_000.0, r.hi, 1e-9)
    }

    @Test fun flatAndDegenerate() {
        assertNull(SparkRange.of(listOf(1.0)))
        val r = SparkRange.of(listOf(5.0, 5.0, 5.0))!!
        assertTrue(r.span > 0)
        assertEquals(0.5, SparkRange.norm(5.0, r), 1e-9)
        val z = SparkRange.of(listOf(0.0, 0.0))!!
        assertTrue(z.span > 0)
        assertNotNull(SparkRange.of(listOf(1.0, Double.NaN, 2.0)))
    }
}

class CoinGeckoParseTest {
    @Test fun malformedItemsSkipped() {
        val el = kotlinx.serialization.json.Json.parseToJsonElement("""[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","current_price":1},{"symbol":"x"},5,{"id":"","name":"y"}]""")
        val list = CoinGeckoProvider(com.coinportracker.network.HttpClient()).parseMarketsForTest(el)
        assertEquals(listOf("bitcoin"), list.map { it.id })
    }
}
