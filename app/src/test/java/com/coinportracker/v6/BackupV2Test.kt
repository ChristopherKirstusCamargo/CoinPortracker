package com.coinportracker.v6

import com.coinportracker.backup.*
import com.coinportracker.database.*
import com.coinportracker.security.BackupCrypto
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class BackupV2Test {
    @get:Rule val tmp = TemporaryFolder()
    private val pw = "senha-forte-123".toCharArray()

    private fun seal(plain: ByteArray, iterations: Int = 10_000): ByteArray {
        val bo = ByteArrayOutputStream()
        StreamCipher.encrypt(bo, pw, iterations).use { it.write(plain) }
        return bo.toByteArray()
    }
    private fun open(b: ByteArray, p: CharArray = pw) = StreamCipher.decrypt(ByteArrayInputStream(b), p).use { it.readBytes() }

    @Test fun stream_roundTrip_sizes() {
        for (n in listOf(0, 1, 1 shl 20, (1 shl 20) + 1, 3 * (1 shl 20) - 7)) {
            val plain = Random(n).nextBytes(n)
            val sealed = seal(plain)
            assertTrue(StreamCipher.isV2(sealed))
            assertArrayEquals("n=$n", plain, open(sealed))
        }
    }

    @Test fun stream_wrongPassword_tamper_truncate_append_rejected() {
        val plain = Random(1).nextBytes(2 * (1 shl 20) + 100)
        val sealed = seal(plain)
        assertThrows(BackupCrypto.WrongPasswordOrCorrupted::class.java) { open(sealed, "outra-senha-000".toCharArray()) }
        val t = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { open(t) }
        // corte exatamente numa fronteira de bloco: o último bloco real não está marcado como final → recusado
        val cut = sealed.copyOf(sealed.size - (100 + 16 + 4))
        assertThrows(Exception::class.java) { open(cut) }
        val appended = sealed + seal(ByteArray(10)).copyOfRange(31, 31 + 30)
        assertThrows(Exception::class.java) { open(appended) }
    }

    private fun payload() = BackupPayload(createdAt = 1, appVersion = "t",
        assets = listOf(AssetEntity("solana", "SOL", "Solana")),
        wallets = listOf(WalletEntity(7, "Principal", "ONCHAIN", "SOLANA", "So1anaAddr111")),
        transactions = listOf(TransactionEntity(3, "BUY", 1000, "solana", "1.5", "500.12345678901234")),
        settings = mapOf("baseCurrency" to "BRL"))

    private fun build(media: Map<MediaEntry, File>, versionCode: Int = 17): ByteArray {
        val data = FullBackupData(payload(), chainActivity = listOf(ChainActivityEntity(7, "SOLANA", "sig1", 5, "IN", "SOL", "1", null, null, null, false, null, null, null)),
            roll = RollStateDto(true, true, true, "g1", listOf("g1")), media = media.keys.toList(), taxRules = "{}")
        val bo = ByteArrayOutputStream()
        FullBackup.write(bo, pw, data, media, "6.0.0", versionCode, iterations = 10_000)
        return bo.toByteArray()
    }

    @Test fun full_roundTrip_with_media_and_hashes() {
        val gif = tmp.newFile("g.gif").apply { writeBytes(Random(5).nextBytes(300_000)) }
        val e = MediaEntry(MediaKind.ROLL.name, "g1.gif", gif.length(), "GIF", "g1", 9)
        val bytes = build(mapOf(e to gif))
        val dir = File(tmp.root, "stage")
        val x = FullBackup.extract(ByteArrayInputStream(bytes), pw, dir, maxVersionCode = 17)
        assertEquals("500.12345678901234", x.data.base.transactions[0].unitPrice)
        assertEquals(1, x.data.chainActivity.size)
        assertArrayEquals(gif.readBytes(), x.file(x.data.media[0])!!.readBytes())
        assertEquals("{}", x.data.taxRules)
        x.discard(); assertFalse(dir.exists())
    }

    @Test fun full_newer_version_refused_and_staging_cleaned() {
        val bytes = build(emptyMap(), versionCode = 99)
        val dir = File(tmp.root, "stage2")
        assertThrows(BackupVersionException::class.java) { FullBackup.extract(ByteArrayInputStream(bytes), pw, dir, maxVersionCode = 17) }
        assertFalse(dir.exists())
    }

    @Test fun full_wrong_password_refused() {
        val bytes = build(emptyMap())
        assertThrows(BackupCrypto.WrongPasswordOrCorrupted::class.java) { FullBackup.extract(ByteArrayInputStream(bytes), "errada-errada".toCharArray(), File(tmp.root, "s3"), 17) }
    }

    @Test fun full_size_budget_enforced() {
        val big = tmp.newFile("b.img").apply { writeBytes(Random(2).nextBytes(200_000)) }
        val bytes = build(mapOf(MediaEntry(MediaKind.ROLL.name, "p1.img", big.length(), "PHOTO", "p1") to big))
        assertThrows(IllegalStateException::class.java) { FullBackup.extract(ByteArrayInputStream(bytes), pw, File(tmp.root, "s4"), 17, maxTotalBytes = 50_000) }
    }

    @Test fun names_validated() {
        assertTrue(FullBackup.validName("g_1-a.gif"))
        assertFalse(FullBackup.validName("../x")); assertFalse(FullBackup.validName("a/b")); assertFalse(FullBackup.validName(".hidden")); assertFalse(FullBackup.validName(""))
    }

    @Test fun merge_matches_wallets_case_insensitive_evm_and_manual_by_name() {
        val cur = listOf(WalletEntity(1, "Metamask", "ONCHAIN", "EVM", "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD"), WalletEntity(2, "Corretora X", "EXCHANGE"))
        val bak = listOf(WalletEntity(10, "mm", "ONCHAIN", "EVM", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"), WalletEntity(11, " corretora x ", "EXCHANGE"),
            WalletEntity(12, "Sol", "ONCHAIN", "SOLANA", "AbC"), WalletEntity(13, "Sol2", "ONCHAIN", "SOLANA", "abc"))
        assertEquals(mapOf(10L to 1L, 11L to 2L), BackupMerge.matchWallets(cur, bak))
    }

    @Test fun merge_absent_and_remap() {
        val cur = listOf(TransactionEntity(1, "BUY", 5, "a", "1", fingerprint = "f1"))
        val bak = listOf(TransactionEntity(9, "BUY", 5, "a", "1", fingerprint = "f1"), TransactionEntity(10, "SELL", 6, "a", "1"), TransactionEntity(11, "SELL", 6, "a", "1"))
        assertEquals(listOf(10L), BackupMerge.absent(cur, bak) { BackupMerge.txKey(it) }.map { it.id })
        val rows = listOf(ChainActivityEntity(10, "SOLANA", "h", 1, "IN", "SOL", "1", null, null, null, false, null, null, null),
            ChainActivityEntity(99, "SOLANA", "h2", 1, "IN", "SOL", "1", null, null, null, false, null, null, null))
        assertEquals(listOf(4L), BackupMerge.remapActivity(rows, mapOf(10L to 4L)).map { it.walletId })
        assertEquals("""[{"walletId":4,"x":1},{"walletId":5}]""", BackupMerge.remapCacheJson("""[{"walletId": 10,"x":1},{"walletId":5}]""", mapOf(10L to 4L)))
    }
}
