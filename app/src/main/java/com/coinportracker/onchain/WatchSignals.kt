package com.coinportracker.onchain

/**
 * Leitura das mensagens de tempo real. Não inventa saldo: só diz "esta conta ou este bloco
 * mudou, vale a pena ler de novo". O saldo continua vindo do scanner HTTP.
 */
object WatchSignals {
    fun toWebSocket(httpUrl: String): String? {
        val u = httpUrl.trim().substringBefore('?')
        return when {
            u.startsWith("wss://") || u.startsWith("ws://") -> u
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
            else -> null
        }
    }

    /** mempool.space e o fork litecoinspace.org. Outros Esplora (blockstream) não têm este canal. */
    fun esploraWebSocket(httpBase: String): String? {
        val host = httpBase.substringAfter("://").substringBefore('/').substringBefore(':')
        return when (host) {
            "mempool.space", "litecoinspace.org" -> "wss://$host/api/v1/ws"
            else -> null
        }
    }

    fun krakenWebSocket(restUrl: String, enabled: Boolean): String? {
        if (!enabled) return null
        val host = restUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        return if (host == "api.kraken.com" || host == "ws.kraken.com") "wss://ws.kraken.com/v2" else null
    }

    fun isSolanaLog(text: String): Boolean {
        if (text.length > 200_000) return false
        return "logsNotification" in text || "accountNotification" in text
    }

    fun mempoolAddresses(text: String, watched: Collection<String>): Set<String> {
        if (text.length > 800_000) return emptySet()
        if ("address-transactions" !in text && "multi-address-transactions" !in text) return emptySet()
        return watched.filter { it.length >= 20 && it in text }.toSet()
    }

    fun isEvmHead(text: String): Boolean {
        if (text.length > 100_000 || "eth_subscription" !in text) return false
        return "parentHash" in text || "\"number\"" in text
    }

    fun xrplAccounts(text: String, watched: Collection<String>): Set<String> {
        if (text.length > 500_000 || "transaction" !in text) return emptySet()
        if ("engine_result" !in text && "TransactionType" !in text) return emptySet()
        return watched.filter { it.length in 25..50 && it in text }.toSet()
    }

    /** Linha `data:` do stream SSE da Horizon (Stellar). Comentários e o hello vazio não contam. */
    fun stellarData(line: String): Boolean = line.startsWith("data:") && "{" in line

    fun krakenPrices(text: String): Map<String, Double> {
        if (text.length > 200_000 || "ticker" !in text) return emptyMap()
        val el = runCatching { chainJson.parseToJsonElement(text) }.getOrNull() ?: return emptyMap()
        return LiveQuotes.parseKrakenWs(el)
    }

    fun safeAddress(raw: String): String? {
        val a = raw.trim()
        if (a.length !in 20..120) return null
        if (a.any { it == '"' || it == '\\' || it.isWhitespace() || it == '{' || it == '}' }) return null
        return a
    }
}
