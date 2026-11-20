package com.coinportracker.receive

import com.coinportracker.data.WalletSummary
import com.coinportracker.onchain.AddressKind
import com.coinportracker.onchain.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveAssetsTest {
    private fun w(id: Long, name: String, addr: String?, kind: AddressKind?, nets: List<Network>, value: Double = 0.0, watch: Boolean = true) =
        WalletSummary(id, name, addr, kind, value, null, nets, null, null, watch)

    @Test fun emptyWhenNoWallets() = assertTrue(ReceiveAssets.from(emptyList()).isEmpty())

    @Test fun hidesXlmWhenNoStellarAddress() {
        val list = ReceiveAssets.from(listOf(
            w(1, "Phantom", "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", AddressKind.SOLANA, listOf(Network.SOLANA), 100.0),
            w(2, "Ledger", "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", AddressKind.BITCOIN, listOf(Network.BITCOIN), 50.0),
        ))
        assertEquals(setOf(Network.SOLANA, Network.BITCOIN), list.map { it.network }.toSet())
        assertFalse(list.any { it.network == Network.STELLAR })
        assertFalse(list.any { it.network == Network.TRON })
    }

    @Test fun stellarAppearsOnceAdded() {
        val before = ReceiveAssets.from(listOf(
            w(1, "Phantom", "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", AddressKind.SOLANA, listOf(Network.SOLANA), 10.0),
        ))
        assertFalse(before.any { it.network == Network.STELLAR })
        val after = ReceiveAssets.from(listOf(
            w(1, "Phantom", "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", AddressKind.SOLANA, listOf(Network.SOLANA), 10.0),
            w(2, "Stellar", "GDUKMGUGDZQK6YHYA5Z6AY2G4XDSZPSZ3SW5UN3ARVMO6QSRDWP5YLEX", AddressKind.STELLAR, listOf(Network.STELLAR), 1.0),
        ))
        assertTrue(after.any { it.network == Network.STELLAR })
        assertEquals(1, after.first { it.network == Network.STELLAR }.addresses.size)
    }

    @Test fun evmListsOnlyAssociatedNetworks() {
        val list = ReceiveAssets.from(listOf(
            w(1, "Principal", "0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d", AddressKind.EVM,
                listOf(Network.ETHEREUM, Network.BASE, Network.ARBITRUM), 200.0),
        ))
        assertEquals(listOf(Network.ETHEREUM, Network.BASE, Network.ARBITRUM), list.map { it.network })
        assertFalse(list.any { it.network == Network.POLYGON }) // sem Polygon nessa carteira
        list.forEach { a ->
            assertEquals(1, a.addresses.size)
            assertEquals("0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d", a.addresses[0].address)
        }
    }

    @Test fun evmFallsBackToKindNetworksWhenEmpty() {
        val list = ReceiveAssets.from(listOf(
            w(1, "Principal", "0xabc", AddressKind.EVM, emptyList(), 1.0),
        ))
        assertEquals(AddressKind.EVM.networks.toSet(), list.map { it.network }.toSet())
        assertTrue(list.any { it.network == Network.POLYGON })
    }

    @Test fun multipleBitcoinAddressesGoToStep2() {
        val list = ReceiveAssets.from(listOf(
            w(2, "Ledger", "bc1qaaa", AddressKind.BITCOIN, listOf(Network.BITCOIN), 80.0),
            w(4, "Carteira 2", "bc1qbbb", AddressKind.BITCOIN, listOf(Network.BITCOIN), 20.0),
        ))
        assertEquals(1, list.size)
        assertEquals(Network.BITCOIN, list[0].network)
        assertEquals(2, list[0].addresses.size)
        assertEquals(listOf("Ledger", "Carteira 2"), list[0].addresses.map { it.walletName })
    }

    @Test fun skipsManualWithoutAddressAndNonWatch() {
        val list = ReceiveAssets.from(listOf(
            w(1, "Manual", null, null, emptyList(), 9.0, watch = false),
            w(2, "Arquivada", "bc1qaaa", AddressKind.BITCOIN, listOf(Network.BITCOIN), 1.0, watch = false),
            w(3, "Phantom", "SolAddr", AddressKind.SOLANA, listOf(Network.SOLANA), 5.0),
        ))
        assertEquals(listOf(Network.SOLANA), list.map { it.network })
    }

    @Test fun sortsByWalletValueThenEnum() {
        val list = ReceiveAssets.from(listOf(
            w(1, "BTC", "bc1q", AddressKind.BITCOIN, listOf(Network.BITCOIN), 10.0),
            w(2, "SOL", "sol", AddressKind.SOLANA, listOf(Network.SOLANA), 90.0),
        ))
        assertEquals(listOf(Network.SOLANA, Network.BITCOIN), list.map { it.network })
    }
}

class ReceiveSafetyTest {
    private fun w(id: Long, addr: String, kind: AddressKind?, nets: List<Network>) =
        WalletSummary(id, "w$id", addr, kind, 1.0, null, nets, null, null, true)

    @Test fun neverOffersWrongNetworkForRecognizedAddress() {
        // dado inconsistente (ex.: banco antigo): endereço Bitcoin associado à rede Solana
        val list = ReceiveAssets.from(listOf(w(1, "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", AddressKind.BITCOIN, listOf(Network.SOLANA, Network.BITCOIN))))
        assertEquals(listOf(Network.BITCOIN), list.map { it.network })
    }

    @Test fun tonPayloadIsUrlSafe() {
        val p = PaymentUri.payload(Network.TON, "EQD+abc/def")
        assertEquals("ton://transfer/EQD-abc_def", p)
    }
}
