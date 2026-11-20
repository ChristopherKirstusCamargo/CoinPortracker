package com.coinportracker.receive

import com.coinportracker.onchain.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentUriTest {
    @Test fun bitcoinBip21() = assertEquals("bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
        PaymentUri.payload(Network.BITCOIN, "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))

    @Test fun litecoin() = assertEquals("litecoin:ltc1qtest", PaymentUri.payload(Network.LITECOIN, "ltc1qtest"))

    @Test fun dogecoin() = assertEquals("dogecoin:DTest", PaymentUri.payload(Network.DOGECOIN, "DTest"))

    @Test fun solanaPay() = assertEquals("solana:7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
        PaymentUri.payload(Network.SOLANA, "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"))

    @Test fun eip681Ethereum() = assertEquals(
        "ethereum:0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d@1",
        PaymentUri.payload(Network.ETHEREUM, "0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d"))

    @Test fun eip681Polygon() = assertEquals(
        "ethereum:0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d@137",
        PaymentUri.payload(Network.POLYGON, "0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d"))

    @Test fun eip681Base() = assertEquals(
        "ethereum:0xabc@8453", PaymentUri.payload(Network.BASE, "0xabc"))

    @Test fun eip681Arbitrum() = assertEquals("ethereum:0xabc@42161", PaymentUri.payload(Network.ARBITRUM, "0xabc"))
    @Test fun eip681Optimism() = assertEquals("ethereum:0xabc@10", PaymentUri.payload(Network.OPTIMISM, "0xabc"))
    @Test fun eip681Bsc() = assertEquals("ethereum:0xabc@56", PaymentUri.payload(Network.BSC, "0xabc"))
    @Test fun eip681Avalanche() = assertEquals("ethereum:0xabc@43114", PaymentUri.payload(Network.AVALANCHE, "0xabc"))

    @Test fun stellarSep7() = assertEquals(
        "web+stellar:pay?destination=GDUKMGUGDZQK6YHYA5Z6AY2G4XDSZPSZ3SW5UN3ARVMO6QSRDWP5YLEX",
        PaymentUri.payload(Network.STELLAR, "GDUKMGUGDZQK6YHYA5Z6AY2G4XDSZPSZ3SW5UN3ARVMO6QSRDWP5YLEX"))

    @Test fun ton() = assertEquals("ton://transfer/EQDtest", PaymentUri.payload(Network.TON, "EQDtest"))

    @Test fun cardanoCip13() = assertEquals("web+cardano://addr/addr1qtest", PaymentUri.payload(Network.CARDANO, "addr1qtest"))

    @Test fun rawWhenNoStandardScheme() {
        // Tron, XRP, Cosmos, Near, Sui, Aptos: QR = endereço cru (carteiras leem assim)
        assertEquals("TNXoiAJ3dct8Fjg4M9fkLFh9S2v9TXc32G", PaymentUri.payload(Network.TRON, "TNXoiAJ3dct8Fjg4M9fkLFh9S2v9TXc32G"))
        assertEquals("rTest", PaymentUri.payload(Network.XRP, "rTest"))
        assertEquals("cosmos1abc", PaymentUri.payload(Network.COSMOS, "cosmos1abc"))
        assertEquals("alice.near", PaymentUri.payload(Network.NEAR, "alice.near"))
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000005",
            PaymentUri.payload(Network.SUI, "0x0000000000000000000000000000000000000000000000000000000000000005"))
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000005",
            PaymentUri.payload(Network.APTOS, "0x0000000000000000000000000000000000000000000000000000000000000005"))
    }

    @Test fun trimsAndEmpty() {
        assertEquals("bitcoin:bc1qabc", PaymentUri.payload(Network.BITCOIN, "  bc1qabc  "))
        assertEquals("", PaymentUri.payload(Network.BITCOIN, "  "))
    }

    @Test fun qrPayloadIsNotAlwaysRaw() {
        val raw = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
        val qr = PaymentUri.payload(Network.BITCOIN, raw)
        assertTrue(qr.startsWith("bitcoin:"))
        assertFalse(qr == raw)
        // o copiar (testado no Receber) usa só o endereço: o payload contém o endereço
        assertTrue(qr.endsWith(raw))
    }
}
