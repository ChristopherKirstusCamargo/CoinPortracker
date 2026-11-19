package com.coinportracker.backup

import com.coinportracker.security.BackupCrypto
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v6.0 — criptografia em fluxo do backup completo (fotos e GIFs podem somar centenas de MB: nada é carregado inteiro na memória).
 *
 * Formato "CPTB2": MAGIC(5) | versão(1) | iterações(4) | sal(16) | prefixo do nonce(8) | blocos.
 * Cada bloco: tamanho(4) | AES-256-GCM(até 1 MiB de texto) com nonce = prefixo ‖ contador(4) e dado autenticado =
 * cabeçalho ‖ contador ‖ marca de último bloco. Trocar a ordem, remover, repetir ou cortar blocos (inclusive o final)
 * invalida o arquivo. A chave vem da senha por PBKDF2-HMAC-SHA256 (mesmo custo do backup v1). A senha nunca é guardada.
 */
object StreamCipher {
    val MAGIC = "CPTB2".toByteArray()
    private const val VERSION = 2
    const val CHUNK = 1 shl 20
    private const val HEADER = 5 + 1 + 4 + 16 + 8

    fun isV2(head: ByteArray) = head.size >= MAGIC.size && head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(out: OutputStream, password: CharArray, iterations: Int = BackupCrypto.DEFAULT_ITERATIONS, rnd: SecureRandom = SecureRandom()): OutputStream {
        require(password.size >= 8) { "A senha do backup deve ter ao menos 8 caracteres." }
        val salt = ByteArray(16).also(rnd::nextBytes)
        val prefix = ByteArray(8).also(rnd::nextBytes)
        val header = ByteArrayOutputStream().apply {
            write(MAGIC); write(VERSION); write(ByteBuffer.allocate(4).putInt(iterations).array()); write(salt); write(prefix)
        }.toByteArray()
        out.write(header)
        return Writer(out, BackupCrypto.derive(password, salt, iterations), header, prefix)
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val d = DataInputStream(input)
        val header = ByteArray(HEADER)
        try { d.readFully(header) } catch (e: EOFException) { throw BackupCrypto.WrongPasswordOrCorrupted() }
        if (!isV2(header)) throw IllegalArgumentException("Não é um backup completo do CoinPortracker.")
        if (header[5].toInt() != VERSION) throw IllegalArgumentException("Versão de backup não suportada: ${header[5]}")
        val iterations = ByteBuffer.wrap(header, 6, 4).int
        if (iterations < 10_000 || iterations > 10_000_000) throw BackupCrypto.WrongPasswordOrCorrupted()
        val salt = header.copyOfRange(10, 26)
        val prefix = header.copyOfRange(26, 34)
        return Reader(d, BackupCrypto.derive(password, salt, iterations), header, prefix)
    }

    private fun nonce(prefix: ByteArray, counter: Int) = prefix + ByteBuffer.allocate(4).putInt(counter).array()
    private fun aad(header: ByteArray, counter: Int, last: Boolean) = header + ByteBuffer.allocate(4).putInt(counter).array() + byteArrayOf(if (last) 1 else 0)

    private class Writer(private val out: OutputStream, private val key: SecretKeySpec, private val header: ByteArray, private val prefix: ByteArray) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var counter = 0
        private var closed = false
        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "fechado" }
            var o = off; var l = len
            while (l > 0) {
                // bloco cheio só é selado quando chega mais dado: o último bloco sempre leva a marca de fim
                if (n == CHUNK) emit(false)
                val k = minOf(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, k); n += k; o += k; l -= k
            }
        }
        private fun emit(last: Boolean) {
            if (counter == Int.MAX_VALUE) throw IllegalStateException("backup grande demais")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
            c.updateAAD(aad(header, counter, last))
            val ct = c.doFinal(buf, 0, n)
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array()); out.write(ct)
            counter++; n = 0
        }
        override fun flush() { out.flush() }
        override fun close() { if (closed) return; emit(true); closed = true; buf.fill(0); out.flush(); out.close() }
    }

    private class Reader(private val d: DataInputStream, private val key: SecretKeySpec, private val header: ByteArray, private val prefix: ByteArray) : InputStream() {
        private var cur = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var done = false
        private var nextLen: Int? = readLen() ?: throw BackupCrypto.WrongPasswordOrCorrupted()

        private fun readLen(): Int? = try { d.readInt() } catch (e: EOFException) { null }

        private fun fill(): Boolean {
            while (pos >= cur.size) {
                if (done) return false
                val len = nextLen ?: throw BackupCrypto.WrongPasswordOrCorrupted()
                if (len < 16 || len > CHUNK + 16) throw BackupCrypto.WrongPasswordOrCorrupted()
                val ct = ByteArray(len)
                try { d.readFully(ct) } catch (e: EOFException) { throw BackupCrypto.WrongPasswordOrCorrupted() }
                nextLen = readLen()
                val last = nextLen == null
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
                c.updateAAD(aad(header, counter, last))
                // sem marca de fim no bloco que termina o arquivo = arquivo cortado; marca de fim no meio = blocos colados
                cur = try { c.doFinal(ct) } catch (e: AEADBadTagException) { throw BackupCrypto.WrongPasswordOrCorrupted() }
                pos = 0; counter++
                if (last) done = true
            }
            return true
        }
        override fun read(): Int = if (!fill()) -1 else cur[pos++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val k = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, k); pos += k
            return k
        }
        override fun close() { d.close() }
    }
}
