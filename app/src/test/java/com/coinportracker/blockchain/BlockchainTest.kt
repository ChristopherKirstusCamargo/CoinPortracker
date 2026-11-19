package com.coinportracker.blockchain

import com.coinportracker.core.Outcome
import com.coinportracker.domain.Chain
import com.coinportracker.network.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger

class BlockchainTest {
    @Test fun validatesPublicAddresses() {
        assertTrue(AddressValidator.isValidBitcoin("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        assertTrue(AddressValidator.isValidBitcoin("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertFalse(AddressValidator.isValidBitcoin("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb")) // checksum
        assertTrue(AddressValidator.isValidSolana("vines1vzrYbzLMRdu58ou5XTby4qAqVRLmqo36NKPTg"))
        assertTrue(AddressValidator.isValidEvm("0x0000000000000000000000000000000000000000"))
        assertNull(AddressValidator.validate(Chain.BASE, "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"))
        assertNotNull(AddressValidator.validate(Chain.ETHEREUM, "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
    }

    @Test fun rejectsSecrets() {
        // 64 hex = formato de chave privada EVM; 12 palavras = seed phrase
        assertTrue(AddressValidator.looksLikeSecret("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"))
        assertTrue(AddressValidator.looksLikeSecret("abandon ability able about above absent absorb abstract absurd abuse access accident"))
        assertNotNull(AddressValidator.validate(Chain.ETHEREUM, "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"))
    }

    @Test fun evmScalingIsExact() {
        assertEquals("1.5", EvmRpcProvider(HttpClient()).scale(BigInteger("1500000000000000000"), 18).toPlainString())
        assertEquals("0.000001", EvmRpcProvider(HttpClient()).scale(BigInteger.ONE, 6).toPlainString())
    }

    /** Teste de rede real (opcional): ./gradlew testDebugUnitTest -Plive=true */
    @Test fun liveProviders() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val http = HttpClient()
        val btc = BitcoinEsploraProvider(http).sync("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh")
        assertTrue("btc $btc", btc is Outcome.Ok && btc.value.balances.isNotEmpty())
        val sol = SolanaRpcProvider(http).sync("vines1vzrYbzLMRdu58ou5XTby4qAqVRLmqo36NKPTg")
        assertTrue("sol $sol", sol is Outcome.Ok)
        val eth = EvmRpcProvider(http).sync("https://ethereum-rpc.publicnode.com", "ETH", "ethereum", "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
            listOf(EvmRpcProvider.Token("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6, "usd-coin")))
        assertTrue("eth $eth", eth is Outcome.Ok && eth.value.balances.any { it.symbol == "ETH" })
        println("LIVE OK: btc=${(btc as Outcome.Ok).value.balances} sol=${(sol as Outcome.Ok).value.balances.take(3)} eth=${(eth as Outcome.Ok).value.balances}")
    }
}
