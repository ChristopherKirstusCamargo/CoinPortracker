package com.coinportracker.blockchain

import com.coinportracker.backup.BackupCodec
import com.coinportracker.backup.BackupPayload
import com.coinportracker.database.WalletEntity
import org.junit.Assert.*
import org.junit.Test

class SecretDetectionTest {
    private val V = AddressValidator
    private val seed = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    @Test fun detectsSeedVariants() {
        assertTrue(V.looksLikeSecret(seed))
        assertTrue(V.looksLikeSecret(seed.uppercase()))
        assertTrue(V.looksLikeSecret(seed.split(" ").mapIndexed { i, w -> "${i + 1}. $w" }.joinToString(" ")))
        assertTrue(V.looksLikeSecret(seed.replace(" ", ", ")))
        assertTrue(V.looksLikeSecret(seed.replace(" ", "\n")))
    }

    @Test fun detectsKeys() {
        assertTrue(V.looksLikeSecret("ab".repeat(32)))
        assertTrue(V.looksLikeSecret("0x" + "ab".repeat(32)))
        assertTrue(V.looksLikeSecret((0 until 64).joinToString(",", "[", "]") { "${(it * 7) % 256}" }))
        assertTrue(V.looksLikeSecret((0 until 64).joinToString(", ", "[", "]") { "${(it * 7) % 256}" }))
        assertTrue(V.looksLikeSecret("xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"))
        assertTrue(V.looksLikeSecret("suiprivkey1qzwant"))
        assertTrue(V.looksLikeSecret("SCZANGBA5YHTNYVVV4C3U252E2B6P6F5T3U6MM63WBSBZATAQI3EBTQ4"))
        assertTrue(V.looksLikeSecret("5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"))
    }

    @Test fun publicAddressesAreNotSecrets() {
        listOf("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", "0x742d35Cc6634C0532925a3b844Bc454e4438f44e", "DRpbCBMxVnDK7maPM5tGv6MvB3v1sRMC86PZ8okm21hy",
            "GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7", "addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x")
            .forEach { assertFalse(it, V.looksLikeSecret(it)) }
    }

    @Test fun backupWithSecret_rejected() {
        val p = BackupPayload(createdAt = 1, appVersion = "t", wallets = listOf(WalletEntity(name = "x", type = "WATCH_ONLY", chain = "EVM_MULTI", address = "ab".repeat(32))))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(p) }
        val p2 = BackupPayload(createdAt = 1, appVersion = "t", wallets = listOf(WalletEntity(name = "x", type = "WATCH_ONLY", notes = seed)))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(p2) }
        BackupCodec.validate(BackupPayload(createdAt = 1, appVersion = "t", wallets = listOf(WalletEntity(name = "x", type = "WATCH_ONLY", chain = "EVM_MULTI", address = "0x742d35Cc6634C0532925a3b844Bc454e4438f44e"))))
    }
}
