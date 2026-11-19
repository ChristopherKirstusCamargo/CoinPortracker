package com.coinportracker.backup

import com.coinportracker.database.AssetEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.security.BackupCrypto
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val payload = BackupPayload(
        createdAt = 1, appVersion = "test",
        assets = listOf(AssetEntity("solana", "SOL", "Solana")),
        transactions = listOf(TransactionEntity(1, "BUY", 1000, "solana", "1.5", "500.123456789012345678")),
        settings = mapOf("baseCurrency" to "BRL"),
    )

    @Test fun encrypted_roundTrip_preservesExactDecimals() {
        val bytes = BackupCodec.encode(payload, "senha-forte-123".toCharArray(), iterations = 10_000)
        assertTrue(BackupCodec.needsPassword(bytes))
        val back = BackupCodec.decode(bytes, "senha-forte-123".toCharArray())
        assertEquals(payload, back)
        assertEquals("500.123456789012345678", back.transactions[0].unitPrice)
    }

    @Test fun encrypted_wrongPassword_rejected() {
        val bytes = BackupCodec.encode(payload, "senha-forte-123".toCharArray(), iterations = 10_000)
        assertThrows(BackupCrypto.WrongPasswordOrCorrupted::class.java) { BackupCodec.decode(bytes, "outra-senha-000".toCharArray()) }
    }

    @Test fun encrypted_tampering_detected() {
        val bytes = BackupCodec.encode(payload, "senha-forte-123".toCharArray(), iterations = 10_000)
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        assertThrows(BackupCrypto.WrongPasswordOrCorrupted::class.java) { BackupCodec.decode(bytes, "senha-forte-123".toCharArray()) }
        val b2 = BackupCodec.encode(payload, "senha-forte-123".toCharArray(), iterations = 10_000)
        b2[7] = (b2[7].toInt() xor 1).toByte() // cabeçalho (AAD)
        assertThrows(BackupCrypto.WrongPasswordOrCorrupted::class.java) { BackupCodec.decode(b2, "senha-forte-123".toCharArray()) }
    }

    @Test fun plain_integrityChecked() {
        val bytes = BackupCodec.encode(payload, null)
        assertEquals(payload, BackupCodec.decode(bytes, null))
        val tampered = String(bytes).replace("500.123", "900.123").toByteArray()
        assertThrows(IllegalStateException::class.java) { BackupCodec.decode(tampered, null) }
    }

    @Test fun shortPassword_rejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(payload, "123".toCharArray()) }
    }

    @Test fun referentialIntegrity_validated() {
        val bad = payload.copy(assets = emptyList())
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(bad, null), null) }
    }
}
