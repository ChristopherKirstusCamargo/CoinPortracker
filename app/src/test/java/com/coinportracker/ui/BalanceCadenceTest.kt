package com.coinportracker.ui

import com.coinportracker.data.SyncFailure
import com.coinportracker.domain.Currency
import com.coinportracker.market.SparklineScale
import com.coinportracker.onchain.HoldingKind
import com.coinportracker.onchain.HoldingMerge
import com.coinportracker.onchain.LiveQuotes
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.TokenHolding
import com.coinportracker.onchain.WakeGate
import com.coinportracker.onchain.WatchSignals
import com.coinportracker.onchain.chainJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BalanceCadenceTest {
    @Test fun liveIntervalGrowsWithLoadAndLowPowerStaysSlow() {
        assertEquals(12_000L, BalanceCadence.balanceEveryMs(true, 30, 1, 1, false))
        assertEquals(30_000L, BalanceCadence.balanceEveryMs(true, 30, 8, 7, false))
        assertEquals(30_000L, BalanceCadence.balanceEveryMs(false, 30, 1, 1, false))
        assertEquals(60_000L, BalanceCadence.balanceEveryMs(false, 60, 4, 4, false))
        assertEquals(Long.MAX_VALUE, BalanceCadence.balanceEveryMs(false, 0, 2, 2, false))
        assertEquals(BalanceCadence.LOW_POWER_MS, BalanceCadence.balanceEveryMs(true, 30, 1, 1, true))
    }

    @Test fun failureDoesNotCountAsSuccessAndBackoffIsCapped() {
        assertTrue(BalanceCadence.balanceDue(0, 0, 0, 15_000, 1_000))
        assertFalse(BalanceCadence.balanceDue(10_000, 10_000, 0, 15_000, 20_000))
        assertTrue(BalanceCadence.balanceDue(10_000, 10_000, 0, 15_000, 25_000))
        // 1 falha dobra a espera (30 s) e ancora na tentativa, não num sucesso fictício
        assertFalse(BalanceCadence.balanceDue(10_000, 20_000, 1, 15_000, 40_000))
        assertTrue(BalanceCadence.balanceDue(10_000, 20_000, 1, 15_000, 50_000))
        assertFalse(BalanceCadence.balanceDue(0, 0, 0, Long.MAX_VALUE, 9_000))
    }

    @Test fun displayedBalanceIsTheRealTarget() {
        assertEquals(7602.60, LiveBalance.displayed(7602.60), 0.0)
        assertEquals(7602.60, if (true) LiveBalance.displayed(7602.60) else 0.0, 0.0)
    }
}

class SparklineScaleTest {
    @Test fun brlHighLowUsesFxNotRawUsdSparkline() {
        // o caso do XRP: preço em BRL ~5,46 e sparkline em USD ~0,87–0,99
        val spark = listOf(0.8708, 0.95, 0.9913)
        val fx = BigDecimal("5.50")
        val (max, min) = SparklineScale.extrema(spark, Currency.BRL, fx)!!
        assertEquals(0.9913 * 5.50, max.toDouble(), 1e-6)
        assertEquals(0.8708 * 5.50, min.toDouble(), 1e-6)
        assertNull(SparklineScale.extrema(spark, Currency.BRL, null))
        val usd = SparklineScale.extrema(spark, Currency.USD, null)!!
        assertEquals(0.9913, usd.first.toDouble(), 1e-9)
    }
}

class HoldingMergeTest {
    private fun h(key: String, kind: HoldingKind, amt: String) = TokenHolding(
        Network.SOLANA, key, "S", "n", 9, BigDecimal(amt), null, null, kind, true,
    )

    @Test fun partialTokenReadKeepsPreviousTokensAndRealNativeZero() {
        val prev = listOf(h("native", HoldingKind.NATIVE, "2"), h("mintA", HoldingKind.TOKEN, "5"), h("stake:abc", HoldingKind.STAKED, "9"))
        val incoming = listOf(h("native", HoldingKind.NATIVE, "3"))
        val merged = HoldingMerge.merge(prev, incoming, tokensComplete = false, stakesComplete = false)
        assertEquals(setOf("native", "mintA", "stake:abc"), merged.map { it.tokenKey }.toSet())
        assertEquals(BigDecimal("3"), merged.first { it.tokenKey == "native" }.amount)
    }

    @Test fun completeReadDropsSoldToken() {
        val prev = listOf(h("mintA", HoldingKind.TOKEN, "5"))
        val incoming = listOf(h("native", HoldingKind.NATIVE, "1"))
        val merged = HoldingMerge.merge(prev, incoming, tokensComplete = true, stakesComplete = true)
        assertEquals(listOf("native"), merged.map { it.tokenKey })
    }
}

class SyncFailureTest {
    @Test fun distinguishesOutageLimitAndPartial() {
        assertEquals(
            "O provedor limitou as consultas. Mostrando o último saldo salvo.",
            SyncFailure.label(listOf("Solana: HTTP 429"), 0, 2),
        )
        assertEquals(
            "Sem conexão com as redes agora. Mostrando o último saldo salvo.",
            SyncFailure.label(listOf("Bitcoin: timeout"), 0, 1),
        )
        assertEquals("Parte das redes não respondeu.", SyncFailure.label(listOf("Tron: erro"), 3, 1))
        assertNull(SyncFailure.label(emptyList(), 4, 0))
    }
}

class WatchSignalTest {
    @Test fun endpointsMatchProvidersWeActuallyUse() {
        assertEquals("wss://solana-rpc.publicnode.com", WatchSignals.toWebSocket("https://solana-rpc.publicnode.com"))
        assertEquals("wss://mempool.space/api/v1/ws", WatchSignals.esploraWebSocket("https://mempool.space/api"))
        assertEquals("wss://litecoinspace.org/api/v1/ws", WatchSignals.esploraWebSocket("https://litecoinspace.org/api"))
        assertNull(WatchSignals.esploraWebSocket("https://blockstream.info/api"))
        assertEquals("wss://ws.kraken.com/v2", WatchSignals.krakenWebSocket("https://api.kraken.com", true))
        assertNull(WatchSignals.krakenWebSocket("https://api.kraken.com", false))
        assertNull(WatchSignals.krakenWebSocket("https://example.invalid", true))
    }

    @Test fun parsersWakeOnlyOnRealEvents() {
        assertTrue(WatchSignals.isSolanaLog("""{"method":"logsNotification","params":{"subscription":1}}"""))
        assertFalse(WatchSignals.isSolanaLog("""{"result":1,"id":1}"""))
        val addr = "bc1qexampleaddress000000000000000000000"
        val payload = """{"multi-address-transactions":"{\"$addr\":{\"mempool\":[{\"txid\":\"abc\"}]}}"}"""
        assertEquals(setOf(addr), WatchSignals.mempoolAddresses(payload, setOf(addr, "bc1qotheraddress000000000000000000000000")))
        assertTrue(WatchSignals.mempoolAddresses("""{"pong":true}""", setOf(addr)).isEmpty())
        assertTrue(WatchSignals.isEvmHead("""{"method":"eth_subscription","params":{"result":{"parentHash":"0x1","number":"0x10"}}}"""))
        assertFalse(WatchSignals.isEvmHead("""{"id":1,"result":"0xabc"}"""))
        val xrp = "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"
        assertEquals(setOf(xrp), WatchSignals.xrplAccounts("""{"type":"transaction","engine_result":"tesSUCCESS","transaction":{"Account":"$xrp"}}""", setOf(xrp)))
        assertTrue(WatchSignals.stellarData("""data: {"id":"1"}"""))
        assertFalse(WatchSignals.stellarData(":hello"))
        assertNull(WatchSignals.safeAddress("has\"quote"))
    }

    @Test fun krakenWsMapsXrpToPortfolioKey() {
        val el = chainJson.parseToJsonElement("""{"channel":"ticker","type":"update","data":[{"symbol":"XRP/USD","last":0.95},{"symbol":"BTC/USD","last":97000}]}""")
        val p = LiveQuotes.parseKrakenWs(el)
        assertEquals(0.95, p["coingecko:ripple"]!!, 0.0)
        assertEquals(97000.0, p["coingecko:bitcoin"]!!, 0.0)
        assertEquals(97000.0, p["coingecko:wrapped-bitcoin"]!!, 0.0)
    }

    @Test fun krakenRestRecognizesPrefixedXrpKey() {
        val el = chainJson.parseToJsonElement("""{"result":{"XXRPZUSD":{"c":["0.94","1"]}}}""")
        assertEquals(0.94, LiveQuotes.parseKraken(el)["XRPUSD"]!!, 0.0)
    }

    @Test fun wakeGateSuppressesBursts() {
        var now = 1_000L
        val g = WakeGate { now }
        assertTrue(g.allow("a", 8_000))
        now = 5_000
        assertFalse(g.allow("a", 8_000))
        now = 9_000
        assertTrue(g.allow("a", 8_000))
        assertTrue(g.allow("b", 8_000))
    }
}
