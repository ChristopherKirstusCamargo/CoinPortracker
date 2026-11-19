package com.coinportracker.data

import com.coinportracker.database.WalletEntity

/**
 * v5.0.1: a mesma carteira cadastrada duas vezes (ex.: endereço Bitcoin bech32 em maiúsculas e minúsculas, ou uma
 * restauração de backup que gravou a mesma carteira com outro id) aparecia duplicada em Carteiras.
 * Chave = tipo + endereço normalizado (EVM e bech32 sem diferença de maiúsculas; base58 é sensível e fica como está).
 * Fica a carteira mais antiga (menor id); as outras são arquivadas (reversível, nada é apagado).
 */
object WalletDedupe {
    fun normalize(address: String): String {
        val a = address.trim()
        val lower = a.lowercase()
        return if (lower.startsWith("0x") || lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("ltc1")) lower else a
    }

    fun key(w: WalletEntity): String? = w.address?.takeIf { it.isNotBlank() }?.let { (w.chain ?: "") + "|" + normalize(it) }

    /** Ids das carteiras ativas que repetem outra (mantém a de menor id). */
    fun duplicates(wallets: List<WalletEntity>): Set<Long> {
        val seen = HashSet<String>()
        val dup = HashSet<Long>()
        for (w in wallets.filter { !it.archived }.sortedBy { it.id }) {
            val k = key(w) ?: continue
            if (!seen.add(k)) dup += w.id
        }
        return dup
    }
}
