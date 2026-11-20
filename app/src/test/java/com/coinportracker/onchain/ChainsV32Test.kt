package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

/** v3.2 PT4: detecção de endereço (endereços públicos reais), parsers e leitura ao vivo (-Plive=true). */
class ChainsV32Test {
    companion object {
        const val XLM = "GDUKMGUGDZQK6YHYA5Z6AY2G4XDSZPSZ3SW5UN3ARVMO6QSRDWP5YLEX"
        const val TRX = "TNXoiAJ3dct8Fjg4M9fkLFh9S2v9TXc32G"
        const val XRP = "rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh"
        const val ADA = "addr1qx2kd28nq8ac5prwg32hhvudlwggpgfp8utlyqxu6wqgz62f79qsdmm5dsknt9ecr5w468r9ey0fxwkdrwh08ly3tu9sy0f4qd"
        const val LTC = "M8T1B2Z97gVdvmfkQcAtYbEepune1tzGua"
        const val DOGE = "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L"
        const val TON = "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"
        const val ATOM = "cosmos1fl48vsnmsdzcv85q5d2q4z5ajdha8yu34mf0eh"
        const val NEAR = "aurora"
        const val SUI = "0x0000000000000000000000000000000000000000000000000000000000000005"
        const val APT = "0x0000000000000000000000000000000000000000000000000000000000000001"
        const val EVM = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    }

    @Test fun detectsEachNetworkUniquely() {
        assertEquals(listOf(AddressKind.STELLAR), AddressKind.candidates(XLM))
        assertEquals(listOf(AddressKind.TRON), AddressKind.candidates(TRX))
        assertEquals(listOf(AddressKind.XRP), AddressKind.candidates(XRP))
        assertEquals(listOf(AddressKind.CARDANO), AddressKind.candidates(ADA))
        assertEquals(listOf(AddressKind.LITECOIN), AddressKind.candidates(LTC))
        assertEquals(listOf(AddressKind.DOGECOIN), AddressKind.candidates(DOGE))
        assertEquals(listOf(AddressKind.TON), AddressKind.candidates(TON))
        assertEquals(listOf(AddressKind.COSMOS), AddressKind.candidates(ATOM))
        assertEquals(listOf(AddressKind.NEAR), AddressKind.candidates("$NEAR.near"))
        assertEquals(listOf(AddressKind.EVM), AddressKind.candidates(EVM))
        // EVM agora inclui Avalanche C-Chain (e Optimism, que já existia)
        assertTrue(Network.AVALANCHE in AddressKind.EVM.networks && Network.OPTIMISM in AddressKind.EVM.networks)
        // os antigos continuam iguais
        assertEquals(listOf(AddressKind.SOLANA), AddressKind.candidates("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"))
        assertEquals(listOf(AddressKind.BITCOIN), AddressKind.candidates("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        assertEquals(listOf(AddressKind.BITCOIN), AddressKind.candidates("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
    }

    @Test fun ambiguousFormatAsksForChain() {
        assertEquals(listOf(AddressKind.SUI, AddressKind.APTOS), AddressKind.candidates(SUI))
    }

    @Test fun checksumErrorsAreRejected() {
        assertTrue(AddressKind.candidates(TRX.dropLast(1) + "H").isEmpty())
        assertTrue(AddressKind.candidates(XLM.dropLast(1) + "A").isEmpty())
        assertTrue(AddressKind.candidates(XRP.dropLast(1) + "j").none { it == AddressKind.XRP })
        assertTrue(AddressKind.candidates(DOGE.dropLast(1) + "M").none { it == AddressKind.DOGECOIN })
        assertTrue(AddressKind.candidates(TON.dropLast(1) + "M").none { it == AddressKind.TON })
        assertTrue(AddressKind.candidates("abandon abandon abandon").isEmpty())
    }

    @Test fun storedNamesAreStableAndRoundTrip() {
        assertEquals(AddressKind.EVM, AddressKind.fromStored("EVM_MULTI"))
        assertEquals(AddressKind.SOLANA, AddressKind.fromStored("SOLANA"))
        assertEquals(AddressKind.BITCOIN, AddressKind.fromStored("BITCOIN"))
        AddressKind.entries.forEach { assertEquals(it, AddressKind.fromStored(AddressKind.storedName(it))) }
    }

    @Test fun parsesStellarTrustlines() {
        val el = chainJson.parseToJsonElement("""{"balances":[{"balance":"12.5000000","asset_type":"credit_alphanum4","asset_code":"USDC","asset_issuer":"GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"},
            {"balance":"3.0","asset_type":"credit_alphanum4","asset_code":"FOO","asset_issuer":"GXXX"},{"balance":"0.0000000","asset_type":"credit_alphanum4","asset_code":"ZERO","asset_issuer":"G1"},
            {"balance":"100.1234567","asset_type":"native"}]}""")
        val h = AltScanner.parseStellar(el)
        assertEquals(3, h.size)
        assertEquals("coingecko:usd-coin", h.first { it.symbol == "USDC" }.priceKey)
        assertNull(h.first { it.symbol == "FOO" }.priceKey)
        assertEquals(BigDecimal("100.1234567"), h.first { it.tokenKey == "native" }.amount)
    }

    @Test fun parsesTronOnlyKnownTrc20() {
        val el = chainJson.parseToJsonElement("""{"data":[{"balance":143960210,"trc20":[{"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t":"2500000"},{"TVoLHooTu9uthsczLDpkBhz5ES8ueoqX11":"99999000000"}]}]}""")
        val h = AltScanner.parseTron(el)
        assertEquals(listOf("TRX", "USDT"), h.map { it.symbol })
        assertEquals(BigDecimal("143.96021"), h[0].amount)
        assertEquals(BigDecimal("2.5"), h[1].amount)
    }

    @Test fun parsesXrpWithTrustlines() {
        val info = chainJson.parseToJsonElement("""{"result":{"account_data":{"Balance":"1661745264"}}}""")
        val lines = chainJson.parseToJsonElement("""{"result":{"lines":[{"account":"rMxCKbEDwqr76QuheSUMdEGf4B9xJ8m5De","currency":"524C555344000000000000000000000000000000","balance":"10.5"}]}}""")
        val h = AltScanner.parseXrp(info, lines)
        assertEquals(BigDecimal("1661.745264"), h[0].amount)
        assertEquals("RLUSD", h[1].symbol); assertEquals("coingecko:ripple-usd", h[1].priceKey)
    }

    @Test fun litecoinUsesEsploraParserWithItsOwnNetwork() {
        val txs = BitcoinScanner.parseTxs(chainJson.parseToJsonElement("""[{"txid":"t1","status":{"confirmed":true,"block_time":10},"vin":[],"vout":[{"scriptpubkey_address":"me","value":150000000}]}]"""), "me", Network.LITECOIN)
        assertEquals(Network.LITECOIN, txs[0].network); assertEquals("LTC", txs[0].symbol); assertEquals(BigDecimal("1.5"), txs[0].amount)
    }

    @Test fun polygonUsesPolAndWorkingRpcs() {
        assertEquals("POL", Network.POLYGON.nativeSymbol)
        assertEquals("coingecko:polygon-ecosystem-token", Network.POLYGON.nativePriceKey)
        assertFalse("polygon-rpc.com exige chave desde 2025", Network.POLYGON.rpcs.any { it.contains("polygon-rpc.com") })
    }

    /** Leitura real em cada rede nova (./gradlew testDebugUnitTest -Plive=true). */
    @Test fun liveBalancesOnNewNetworks() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val http = HttpClient()
        val alt = AltScanner(http)
        val cases = listOf(Network.STELLAR to XLM, Network.TRON to TRX, Network.XRP to XRP, Network.CARDANO to ADA, Network.DOGECOIN to DOGE,
            Network.TON to TON, Network.COSMOS to ATOM, Network.NEAR to NEAR, Network.SUI to SUI, Network.APTOS to APT)
        val fails = ArrayList<String>()
        for ((n, a) in cases) {
            val t0 = System.currentTimeMillis()
            when (val r = alt.scan(n, a)) {
                is Outcome.Ok -> println("LIVE ${n.name}: ${r.value.holdings.joinToString { it.symbol + " " + it.amount.toPlainString() }} via ${r.value.sources} (${System.currentTimeMillis() - t0} ms)")
                is Outcome.Err -> { println("LIVE ${n.name}: ERRO ${r.message}"); fails += n.name }
            }
        }
        val ltc = BitcoinScanner(http).scan(LTC, withHistory = true, net = Network.LITECOIN)
        println("LIVE LITECOIN: ${(ltc as? Outcome.Ok)?.value?.let { v -> v.holdings.joinToString { it.symbol + " " + it.amount } + " · ${v.activity.size} movimentos" } ?: ltc}")
        if (ltc !is Outcome.Ok) fails += "LITECOIN"
        val rpc = JsonRpc(http)
        val evm = EvmScanner(http, rpc, TokenListSource { emptyList() })
        for (n in listOf(Network.POLYGON, Network.AVALANCHE, Network.OPTIMISM)) {
            val r = evm.scan(n, EVM, withHistory = false)
            println("LIVE ${n.name}: ${(r as? Outcome.Ok)?.value?.let { v -> v.holdings.filter { it.tokenKey == "native" }.joinToString { it.symbol + " " + it.amount } + " · ${v.holdings.size} saldos · ${v.sources} · ${v.warnings}" } ?: r}")
            if (r !is Outcome.Ok) fails += n.name
            else assertTrue("sem duplicar POL nativo", r.value.holdings.none { it.tokenKey == EvmScanner.POL_NATIVE_TOKEN })
        }
        val prices = PriceService(http).current(Network.entries.map { it.nativePriceKey })
        println("LIVE preços nativos: " + Network.entries.joinToString { it.nativeSymbol + "=" + (prices.getOrNull()?.get(it.nativePriceKey)?.usd ?: "—") })
        assertTrue("falhas: $fails", fails.size <= 1) // tolera 1 provedor público fora do ar no momento
    }
}
