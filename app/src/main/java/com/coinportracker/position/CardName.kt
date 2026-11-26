package com.coinportracker.position

/**
 * Nome do cartão, no tom de uma assinatura. Letras e um apelido curto.
 * Endereço de carteira, chave e texto de controle nunca passam.
 */
object CardName {
    const val MAX = 40
    private val hex = Regex("""(?i)0x[0-9a-f]{8,}""")
    private val base58 = Regex("""[1-9A-HJ-NP-Za-km-z]{32,}""")

    fun looksLikeAddress(s: String): Boolean = hex.containsMatchIn(s) || base58.containsMatchIn(s)

    fun sanitize(raw: String): String {
        val t = raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX)
        if (t.isEmpty() || looksLikeAddress(t)) return ""
        if (t.any { !(it.isLetterOrDigit() || it.isWhitespace() || it in ".'’-·&") }) return ""
        return t
    }
}
