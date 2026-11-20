package com.coinportracker.onchain

import com.coinportracker.network.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class OnchainTest {
    private val vitalik = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"

    @Test fun aggregate3EncodingMatchesReferenceVector() {
        val expected = "0x82ad56cb" + "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000040" +
            "0000000000000000000000000000000000000000000000000000000000000100" +
            "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000060" +
            "0000000000000000000000000000000000000000000000000000000000000024" +
            "70a08231000000000000000000000000d8da6bf26964af9d7eed9e03e53415d37aa96045" + "0".repeat(56) +
            "000000000000000000000000dac17f958d2ee523a2206206994597c13d831ec7" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000060" +
            "0000000000000000000000000000000000000000000000000000000000000024" +
            "70a08231000000000000000000000000d8da6bf26964af9d7eed9e03e53415d37aa96045" + "0".repeat(56)
        val got = Abi.encodeAggregate3(listOf("0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "0xdAC17F958D2ee523a2206206994597C13D831ec7"), Abi.balanceOfCall(vitalik))
        assertEquals(expected, got)
    }

    @Test fun aggregate3DecodingHandlesSuccessAndFailure() {
        val hex = "0x" + listOf(0x20, 2, 0x40, 0xc0, 1, 0x40, 32, 1000, 0, 0x40, 0).joinToString("") { Abi.word(it.toLong()) }
        val r = Abi.decodeAggregate3Uints(hex)
        assertEquals(listOf(BigInteger.valueOf(1000), null), r)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aggregate3DecodingRejectsGarbageOffsets() { Abi.decodeAggregate3Uints("0x" + "ff".repeat(64)) }

    @Test fun scalingIsExact() {
        assertEquals(BigDecimal("1.5"), scaled(BigInteger("1500000000000000000"), 18))
        assertEquals(BigDecimal("0.000001"), scaled(BigInteger.ONE, 6))
        assertEquals(BigDecimal("1200"), scaled(BigInteger("120000000000"), 8))
    }

    @Test fun addressKindDetection() {
        assertEquals(AddressKind.EVM, AddressKind.detect(vitalik))
        assertEquals(AddressKind.SOLANA, AddressKind.detect("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"))
        assertEquals(AddressKind.BITCOIN, AddressKind.detect("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        assertEquals(AddressKind.BITCOIN, AddressKind.detect("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertNull(AddressKind.detect("abandon abandon abandon"))
        assertEquals(7, AddressKind.EVM.networks.size)
    }

    @Test fun spamFilterRules() {
        fun tok(sym: String, name: String, amt: String = "100", scam: Boolean = false) =
            TokenHolding(Network.BASE, "0xabc", sym, name, 18, BigDecimal(amt), null, "base:0xabc", HoldingKind.TOKEN, true, scam)
        val price = LivePrice("base:0xabc", 2.0, 1.0, 0.99, sym(), 0)
        assertEquals(Visibility.SPAM, SpamFilter.classify(tok("CLAIM", "Visit mamefi.top to claim"), price, 1.0).first)
        assertEquals(Visibility.SPAM, SpamFilter.classify(tok("X", "X", scam = true), price, 1.0).first)
        assertEquals(Visibility.UNPRICED, SpamFilter.classify(tok("ABC", "Abc"), null, 1.0).first)
        assertEquals(Visibility.UNPRICED, SpamFilter.classify(tok("ABC", "Abc"), price.copy(confidence = 0.2), 1.0).first)
        assertEquals(Visibility.SMALL, SpamFilter.classify(tok("ABC", "Abc", "0.1"), price, 1.0).first)
        val (vis, v) = SpamFilter.classify(tok("ABC", "Abc"), price, 1.0)
        assertEquals(Visibility.VISIBLE, vis); assertEquals(200.0, v!!, 1e-9)
    }
    private fun sym() = "ABC"

    @Test fun aggregatorGroupsSameCoinAcrossChainsAndComputesChange() {
        val w = HoldingSource(1, "Principal")
        val eth = TokenHolding(Network.ETHEREUM, "native", "ETH", "Ethereum", 18, BigDecimal("1"), null, "coingecko:ethereum", HoldingKind.NATIVE, true, coingeckoId = "ethereum")
        val ethBase = eth.copy(network = Network.BASE)
        val usdcEth = TokenHolding(Network.ETHEREUM, "0xa0b8", "USDC", "USD Coin", 6, BigDecimal("500"), null, "ethereum:0xa0b8", HoldingKind.TOKEN, true)
        val usdcBase = usdcEth.copy(network = Network.BASE, tokenKey = "0x833", priceKey = "base:0x833")
        val spam = TokenHolding(Network.BASE, "0xdead", "FREE", "claim at scam.xyz", 18, BigDecimal("1000"), null, "base:0xdead", HoldingKind.TOKEN, false)
        val stake = TokenHolding(Network.SOLANA, "stake:abc", "SOL", "SOL em staking", 9, BigDecimal("10"), null, "coingecko:solana", HoldingKind.STAKED, true, coingeckoId = "solana")
        val prices = mapOf(
            "coingecko:ethereum" to LivePrice("coingecko:ethereum", 2000.0, 10.0, 0.99, "ETH", 0),
            "ethereum:0xa0b8" to LivePrice("ethereum:0xa0b8", 1.0, 0.0, 0.99, "USDC", 0),
            "base:0x833" to LivePrice("base:0x833", 1.0, 0.0, 0.99, "USDC", 0),
            "coingecko:solana" to LivePrice("coingecko:solana", 100.0, -50.0, 0.99, "SOL", 0),
        )
        val manual = listOf(ManualPosition("bitcoin", "BTC", "Bitcoin", BigDecimal("0.01"), "bitcoin", null, null))
        val p = Aggregator.aggregate(listOf(eth, ethBase, usdcEth, usdcBase, spam, stake).map { w to it }, manual,
            prices + ("coingecko:bitcoin" to LivePrice("coingecko:bitcoin", 50000.0, null, 1.0, "BTC", 0)), 1.0)
        assertEquals(4000.0 + 1000.0 + 1000.0 + 500.0, p.totalUsd, 1e-6)
        val ethGroup = p.assets.first { it.symbol == "ETH" }
        assertEquals(BigDecimal("2"), ethGroup.amount)
        assertEquals(2, ethGroup.networks.size)
        assertEquals(BigDecimal("1000"), p.assets.first { it.symbol == "USDC" }.amount)
        assertEquals(1, p.staking.size)
        assertTrue(p.hidden.any { it.holding.symbol == "FREE" })
        // ETH +10% (4000 ← 3636,36), SOL −50% (1000 ← 2000), USDC 0, BTC sem variação
        val prev = 4000 / 1.1 + 2000 + 1000 + 500
        assertEquals(p.totalUsd - prev, p.change24hUsd, 1e-6)
        assertTrue(p.change24hPct!! < 0)
        assertEquals("Manual", p.byWallet.first { it.key == "-1" }.label)
        assertTrue(p.byNetwork.first().valueUsd >= p.byNetwork.last().valueUsd)
    }

    @Test fun valueSeriesUsesHistoricalPricesAndEndsLive() {
        val s = mapOf(
            "a" to listOf(PricePoint(1000, 1.0), PricePoint(2000, 2.0), PricePoint(3000, 3.0)),
            "b" to listOf(PricePoint(1500, 10.0), PricePoint(2500, 20.0)),
        )
        val v = Aggregator.valueSeries(mapOf("a" to 2.0, "b" to 1.0, "c" to 5.0), s, mapOf("a" to 4.0, "b" to 30.0, "c" to 1.0), 4000)
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L), v.map { it.t })
        assertEquals(2 * 1.0 + 10.0 + 5.0, v[0].usd, 1e-9)
        assertEquals(2 * 2.0 + 10.0 + 5.0, v[1].usd, 1e-9)
        assertEquals(2 * 3.0 + 20.0 + 5.0, v[2].usd, 1e-9)
        assertEquals(2 * 4.0 + 30.0 + 5.0, v[3].usd, 1e-9)
    }

    @Test fun parsesBlockscoutTokenBalances() {
        val json = """[{"token":{"address_hash":"0x4200000000000000000000000000000000000006","decimals":"18","exchange_rate":"2016.59","icon_url":"https://x/weth.png","name":"Wrapped Ether","reputation":"ok","symbol":"WETH","type":"ERC-20"},"value":"34930323797124449"},
            {"token":{"address_hash":"0xNFT","decimals":null,"symbol":"NFT","type":"ERC-721"},"value":"1"},
            {"token":{"address_hash":"0xBAD","decimals":"18","symbol":"SCAM","name":"Scam","type":"ERC-20","reputation":"scam"},"value":"1000"}]"""
        val r = EvmScanner.parseBlockscoutTokens(Network.OPTIMISM, chainJson.parseToJsonElement(json))
        assertEquals(2, r.size)
        assertEquals(BigDecimal("0.034930323797124449"), r[0].amount)
        assertEquals("optimism:0x4200000000000000000000000000000000000006", r[0].priceKey)
        assertTrue(r[1].flaggedScam)
    }

    @Test fun parsesSolanaTokenAccountsStakeAndTxDeltas() {
        val acc = """{"value":[{"account":{"data":{"parsed":{"info":{"mint":"JUP","tokenAmount":{"amount":"2500000","decimals":6}}}}}},
            {"account":{"data":{"parsed":{"info":{"mint":"ZERO","tokenAmount":{"amount":"0","decimals":6}}}}}}]}"""
        val t = SolanaScanner.parseTokenAccounts(chainJson.parseToJsonElement(acc))
        assertEquals(1, t.size); assertEquals(BigDecimal("2.5"), t[0].second)
        val stake = """[{"pubkey":"S1","account":{"lamports":2000000000,"data":{"parsed":{"info":{"stake":{"delegation":{"voter":"V1"}}}}}}}]"""
        val s = SolanaScanner.parseStakeAccounts(chainJson.parseToJsonElement(stake))
        assertEquals("S1", s[0].first); assertEquals(BigInteger("2000000000"), s[0].second); assertEquals("V1", s[0].third)
        val tx = """{"meta":{"fee":5000,"preBalances":[1000000000,0],"postBalances":[499995000,500000000],
            "preTokenBalances":[{"owner":"ME","mint":"JUP","uiTokenAmount":{"uiAmountString":"1"}}],"postTokenBalances":[{"owner":"ME","mint":"JUP","uiTokenAmount":{"uiAmountString":"3.5"}}]},
            "transaction":{"message":{"accountKeys":[{"pubkey":"ME"},{"pubkey":"OTHER"}]}}}"""
        val d = SolanaScanner.parseTxDeltas(chainJson.parseToJsonElement(tx), "ME")
        assertEquals(BigDecimal("-0.5"), d.first { it.mint == null }.delta)
        assertEquals(BigDecimal("0.000005"), d.first { it.mint == null }.fee)
        assertEquals(BigDecimal("2.5"), d.first { it.mint == "JUP" }.delta)
    }

    @Test fun parsesJupiterAndPrices() {
        val j = SolanaScanner.parseJupiter(chainJson.parseToJsonElement("""[{"id":"JUP","symbol":"JUP","name":"Jupiter","icon":"i","decimals":6,"isVerified":true,"usdPrice":0.26,"tags":["verified"]}]"""))
        assertTrue(j[0].verified); assertEquals(0.26, j[0].usdPrice!!, 1e-9)
        val p = PriceService.parsePrices(chainJson.parseToJsonElement("""{"coins":{"coingecko:bitcoin":{"price":71308.2,"symbol":"BTC","confidence":0.99}}}"""),
            mapOf("coingecko:bitcoin" to -3.4), 7)
        assertEquals(71308.2, p[0].usd, 1e-9); assertEquals(-3.4, p[0].change24hPct!!, 1e-9)
        val c = PriceService.parseChart(chainJson.parseToJsonElement("""{"coins":{"coingecko:bitcoin":{"prices":[{"timestamp":20,"price":2},{"timestamp":10,"price":1}]}}}"""))
        assertEquals(listOf(10_000L, 20_000L), c["coingecko:bitcoin"]!!.map { it.t })
    }

    @Test fun tokenListCodecRoundTrip() {
        val l = listOf(TokenMeta("0xabc", "AB\"C", "Name", 6, "https://assets.coingecko.com/coins/images/1/thumb/x.png"), TokenMeta("0xdef", "D", "N", 18, null))
        val back = TokenListCodec.parseCompact(TokenListCodec.toCompact(l))
        assertEquals(l, back)
    }

    @Test fun bitcoinBalanceAndTxParsing() {
        val bal = BitcoinScanner.balanceSats(chainJson.parseToJsonElement("""{"chain_stats":{"funded_txo_sum":300,"spent_txo_sum":100},"mempool_stats":{"funded_txo_sum":50,"spent_txo_sum":0}}"""))
        assertEquals(BigInteger.valueOf(250), bal)
        val txs = BitcoinScanner.parseTxs(chainJson.parseToJsonElement("""[{"txid":"t1","fee":100,"status":{"confirmed":true,"block_time":10},"vin":[{"prevout":{"scriptpubkey_address":"me","value":1000}}],"vout":[{"scriptpubkey_address":"x","value":600},{"scriptpubkey_address":"me","value":300}]}]"""), "me")
        assertEquals(Direction.OUT, txs[0].direction); assertEquals(BigDecimal("0.000007"), txs[0].amount); assertFalse(txs[0].failed)
    }

    /** Teste real (opcional): ./gradlew testDebugUnitTest -Plive=true */
    @Test fun liveScanAcrossNetworks() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val http = HttpClient()
        val rpc = JsonRpc(http)
        val lists = TokenListSource { n -> TokenListCodec.parseTokenList(java.net.URL("https://tokens.coingecko.com/${n.tokenListSlug}/all.json").readText()) }
        val evm = EvmScanner(http, rpc, lists)
        val base = evm.scan(Network.BASE, vitalik, withHistory = false)
        assertTrue(base is com.coinportracker.core.Outcome.Ok)
        val holdings = (base as com.coinportracker.core.Outcome.Ok).value.holdings
        println("Base: ${holdings.size} saldos, fontes ${base.value.sources}")
        assertTrue(holdings.size > 5)
        val sol = SolanaScanner(http, rpc).scan("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", withHistory = true)
        assertTrue(sol is com.coinportracker.core.Outcome.Ok)
        println("Solana: ${(sol as com.coinportracker.core.Outcome.Ok).value.holdings.size} saldos, ${sol.value.activity.size} movimentos")
        val btc = BitcoinScanner(http).scan("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", true)
        assertTrue(btc is com.coinportracker.core.Outcome.Ok)
        val prices = PriceService(http).current(holdings.mapNotNull { it.priceKey }.take(80) + "coingecko:bitcoin")
        assertNotNull(prices.getOrNull()?.get("coingecko:bitcoin"))
        val ws = holdings.map { HoldingSource(1, "t") to it }
        val agg = Aggregator.aggregate(ws, emptyList(), prices.getOrNull()!!, 1.0)
        println("Base visível: US$ ${"%.2f".format(agg.totalUsd)} em ${agg.assets.size} ativos; ocultos ${agg.hidden.size}")
        agg.assets.take(10).forEach { println("  ${it.symbol} ${it.name} ${it.amount} = ${it.valueUsd} conf=${prices.getOrNull()!![it.priceKey]?.confidence}") }
        val fx = PriceService(http).usdBrl()
        println("USD/BRL: ${fx.getOrNull()}")
        assertNotNull(fx.getOrNull())
    }
}
