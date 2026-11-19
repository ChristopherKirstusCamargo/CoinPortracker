package com.coinportracker.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Gera uma senha aleatória de 256 bits para o SQLCipher e a guarda cifrada (AES-256-GCM)
 * por uma chave não exportável do Android Keystore. O arquivo cifrado fica no
 * armazenamento privado do app; a chave do Keystore nunca sai do hardware/TEE.
 */
class DatabaseKeyManager(private val context: Context) {
    private val alias = "coinportracker_db_wrap_v1"
    private val file get() = File(context.noBackupFilesDir, "db.key.enc")

    fun getOrCreatePassphrase(): ByteArray {
        val key = keystoreKey()
        if (file.exists()) {
            val data = file.readBytes()
            val iv = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return c.doFinal(ct)
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val out = c.iv + c.doFinal(pass)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        tmp.renameTo(file)
        return pass
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
