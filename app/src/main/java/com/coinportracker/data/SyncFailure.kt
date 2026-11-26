package com.coinportracker.data

/** Texto curto para a Início: falha total, falha parcial, limite do provedor ou rede. */
object SyncFailure {
    fun label(messages: List<String>, ok: Int, fail: Int): String? {
        if (fail <= 0) return null
        val j = messages.joinToString(" ").lowercase()
        val why = when {
            "429" in j || "rate limit" in j || "too many" in j || "limite" in j -> "O provedor limitou as consultas."
            "timeout" in j || "timed out" in j || "conexão" in j || "connection" in j ||
                "unable to resolve" in j || "failed to connect" in j || "network" in j -> "Sem conexão com as redes agora."
            ok == 0 -> "As redes não responderam."
            else -> "Parte das redes não respondeu."
        }
        return if (ok == 0) "$why Mostrando o último saldo salvo." else why
    }
}
