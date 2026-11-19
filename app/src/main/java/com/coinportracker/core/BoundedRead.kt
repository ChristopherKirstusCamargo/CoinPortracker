package com.coinportracker.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class TooLargeException(val limit: Long) : IOException("Arquivo grande demais (máx. ${Fmt0.mb(limit)})")

private object Fmt0 { fun mb(b: Long) = if (b >= 1 shl 20) "${b shr 20} MB" else "${b shr 10} KB" }

/**
 * Leitura com teto: para no limite em vez de carregar um arquivo gigante inteiro na memória
 * (arquivos escolhidos pelo usuário — backup, CSV, config Dev, regras fiscais, mídia).
 */
object BoundedRead {
    const val DEV_CONFIG = 512L * 1024
    const val TAX_RULES = 256L * 1024
    const val IMPORT_CSV = 32L shl 20
    const val BACKUP = 256L shl 20
    const val MEDIA = 40L shl 20

    fun bytes(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw TooLargeException(limit)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun text(input: InputStream, limit: Long): String = bytes(input, limit).toString(Charsets.UTF_8)
}
