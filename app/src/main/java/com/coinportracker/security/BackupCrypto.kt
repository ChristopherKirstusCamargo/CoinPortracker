package com.coinportracker.security

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia autenticada de backups com primitivas padrão da plataforma (JCA):
 * chave derivada da senha via PBKDF2-HMAC-SHA256 e cifragem AES-256-GCM.
 *
 * Formato: "CPTBK" | versão(1) | iterações(4) | sal(16) | iv(12) | ciphertext+tag(16).
 * O cabeçalho inteiro é dado autenticado (AAD): qualquer alteração invalida o arquivo.
 * A senha nunca é armazenada. Sem a senha, o backup é irrecuperável (por design).
 */
object BackupCrypto {
    private val MAGIC = "CPTBK".toByteArray()
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 310_000

    class WrongPasswordOrCorrupted : Exception("Senha incorreta ou arquivo corrompido/adulterado.")

    fun isEncrypted(data: ByteArray) = data.size > MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.size >= 8) { "A senha do backup deve ter ao menos 8 caracteres." }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val header = ByteArrayOutputStream().apply {
            write(MAGIC); write(VERSION.toInt()); write(ByteBuffer.allocate(4).putInt(iterations).array()); write(salt); write(iv)
        }.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (!isEncrypted(data) || data.size < MAGIC.size + 1 + 4 + 16 + 12 + 16) throw WrongPasswordOrCorrupted()
        var p = MAGIC.size
        val version = data[p]; p += 1
        if (version != VERSION) throw IllegalArgumentException("Versão de backup não suportada: $version")
        val iterations = ByteBuffer.wrap(data, p, 4).int; p += 4
        if (iterations < 10_000 || iterations > 10_000_000) throw WrongPasswordOrCorrupted()
        val salt = data.copyOfRange(p, p + 16); p += 16
        val iv = data.copyOfRange(p, p + 12); p += 12
        val header = data.copyOfRange(0, p)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return try { c.doFinal(data, p, data.size - p) } catch (e: AEADBadTagException) { throw WrongPasswordOrCorrupted() }
    }

    internal fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
