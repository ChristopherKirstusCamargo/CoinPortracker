package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Teste real (opcional, -Plive=true): histórico paginado nas três famílias de rede, com dados públicos. */
class HistoryLiveTest {
    @Test fun pagedHistoryOnRealAddresses() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val http = HttpClient(); val rpc = JsonRpc(http)
        val lists = TokenListSource { emptyList() }
        val now = System.currentTimeMillis()
        fun check(name: String, r: Outcome<HistPage>): HistPage {
            assertTrue("$name: ${(r as? Outcome.Err)?.message}", r is Outcome.Ok)
            val p = (r as Outcome.Ok).value
            val withTime = p.activity.filter { it.timestamp != null }
            println("$name: ${p.activity.size} movimentos, completo=${p.state.complete}, cursores=${p.state.cursors.keys}, " +
                "de ${withTime.minOfOrNull { it.timestamp!! }?.let { java.time.Instant.ofEpochMilli(it) }} a ${withTime.maxOfOrNull { it.timestamp!! }?.let { java.time.Instant.ofEpochMilli(it) }}")
            p.activity.take(4).forEach { println("   ${it.direction} ${it.amount} ${it.symbol} ${it.tokenKey}") }
            assertTrue(p.activity.isNotEmpty())
            assertTrue(withTime.all { it.timestamp!! in 1_230_000_000_000L..now + 60_000 })
            return p
        }
        val evm = EvmScanner(http, rpc, lists)
        val b1 = check("Ethereum", evm.history(Network.ETHEREUM, "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", HistState()))
        // segunda chamada continua de onde parou (preenchimento para trás) sem repetir
        val b2 = (evm.history(Network.ETHEREUM, "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", b1.state) as Outcome.Ok).value
        val dup = b2.activity.map { it.hash + it.tokenKey + it.direction }.toSet().intersect(b1.activity.map { it.hash + it.tokenKey + it.direction }.toSet())
        println("Ethereum 2ª página: ${b2.activity.size} movimentos, repetidos=${dup.size}")
        check("Bitcoin", BitcoinScanner(http).history("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", HistState()))
        check("Solana", SolanaScanner(http, rpc).history("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", HistState()))
        Unit
    }
}
