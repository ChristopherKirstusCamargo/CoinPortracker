package com.coinportracker.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copia texto marcando-o como sensível (Android 13+: o sistema não mostra a prévia do conteúdo
 * no aviso de "copiado" nem em teclados/sugestões). Usado para endereços de carteira.
 */
object SecureClipboard {
    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
    }
}
