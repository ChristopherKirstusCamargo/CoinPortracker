package com.coinportracker.dev

import java.net.URI

/** Resultado da validação: ou a config aceita, ou a lista de erros (nunca ambos). */
data class DevValidation(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    fun requireOk(): DevConfigCheck = this
}
typealias DevConfigCheck = DevValidation

object DevValidator {
    private val EVM_ADDR = Regex("^0x[0-9a-fA-F]{40}$")
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val HEX64_0X = Regex("^0x[0-9a-fA-F]{64}$")
    private val SYMBOL = Regex("^[A-Za-z0-9]{1,12}$")
    private val PRV_PREFIX = Regex("^(xprv|yprv|zprv|tprv)", RegexOption.IGNORE_CASE)
    private val WORDS = Regex("^[a-z]+(?:\\s+[a-z]+){11,23}$")

    fun validate(c: DevConfig): DevValidation {
        val err = ArrayList<String>()
        err += limits(c)
        if (err.isNotEmpty()) return DevValidation(err)
        err += timeouts(c.net)
        val seenPrice = HashSet<String>()
        c.prices.forEachIndexed { i, p ->
            if (p.id.isBlank()) err += "Fonte de preço #${i + 1}: id vazio"
            if (!seenPrice.add(p.id)) err += "Fonte de preço duplicada: ${p.id}"
            err += url(p.url, "Fonte ${p.label.ifBlank { p.id }}")
            if (p.format !in setOf("kraken", "defillama", "coingecko", "paprika", "frankfurter"))
                err += "Fonte ${p.label.ifBlank { p.id }}: formato '${p.format}' não suportado"
            secret(p.url, "Fonte ${p.label.ifBlank { p.id }}")?.let { err += it }
        }
        c.chains.forEach { ch ->
            val seen = HashSet<String>()
            ch.endpoints.forEach { e ->
                err += url(e.url, "${ch.chain} (${e.label.ifBlank { e.kind }})")
                if (!seen.add(e.url.trim().trimEnd('/'))) err += "${ch.chain}: endpoint repetido"
                if (e.kind !in EndpointKind.entries.map { it.name }) err += "${ch.chain}: tipo '${e.kind}' inválido"
                secret(e.url, ch.chain)?.let { err += it }
            }
        }
        val chainIds = HashSet<Long>()
        val chainKeys = HashSet<String>()
        c.customChains.forEach { n ->
            if (n.name.isBlank()) err += "Rede personalizada sem nome"
            if (n.chainId <= 0) err += "${n.name.ifBlank { n.id }}: chain id deve ser positivo"
            if (!chainIds.add(n.chainId)) err += "Chain id ${n.chainId} duplicado"
            if (!chainKeys.add(n.id)) err += "Rede personalizada duplicada: ${n.id}"
            com.coinportracker.onchain.Network.entries.find { it.chainId == n.chainId }?.let {
                err += "Chain id ${n.chainId} já é de ${it.label}"
            }
            err += url(n.rpc, n.name.ifBlank { "rede" })
            if (n.explorerApi.isNotBlank()) err += url(n.explorerApi, "${n.name} (explorador)")
            if (n.nativeSymbol.isBlank() || !SYMBOL.matches(n.nativeSymbol)) err += "${n.name.ifBlank { n.id }}: símbolo nativo inválido"
            if (n.nativeDecimals !in 0..36) err += "${n.name.ifBlank { n.id }}: casas decimais devem estar entre 0 e 36"
            secret(n.rpc, n.name)?.let { err += it }
            secret(n.explorerApi, n.name)?.let { err += it }
        }
        val tokenKeys = HashSet<String>()
        c.customTokens.forEach { t ->
            if (t.chain.isBlank()) err += "Token ${t.symbol.ifBlank { t.contract }}: informe a rede"
            if (!EVM_ADDR.matches(t.contract.trim())) err += "Token ${t.symbol.ifBlank { t.contract }}: contrato EVM inválido"
            if (t.symbol.isBlank() || !SYMBOL.matches(t.symbol)) err += "Token na rede ${t.chain}: símbolo inválido"
            if (t.decimals !in 0..36) err += "${t.symbol.ifBlank { t.contract }}: casas decimais devem estar entre 0 e 36"
            val k = t.chain + ":" + t.contract.trim().lowercase()
            if (!tokenKeys.add(k)) err += "Token repetido em ${t.chain}: ${t.contract}"
            secret(t.contract, t.symbol)?.let { err += it }
            val known = t.chain in com.coinportracker.onchain.Network.entries.map { it.name } || t.chain in c.customChains.map { it.id }
            if (!known) err += "Token ${t.symbol}: rede '${t.chain}' desconhecida"
        }
        return DevValidation(err)
    }

    fun timeouts(n: DevNetSettings): List<String> {
        val err = ArrayList<String>()
        fun range(v: Int, name: String) { if (v !in 1..120) err += "$name deve estar entre 1 e 120 s" }
        range(n.connectTimeoutSec, "Timeout de conexão")
        range(n.readTimeoutSec, "Timeout de leitura")
        range(n.callTimeoutSec, "Timeout da chamada")
        if (n.callTimeoutSec < n.connectTimeoutSec) err += "Timeout da chamada deve ser ≥ o de conexão"
        return err
    }

    /**
     * Só HTTPS para host público. Recusa http (o app proíbe texto claro), file:, content:, javascript:,
     * credenciais na URL, localhost, IPs internos e URLs gigantes (anti-SSRF; o DNS/rede também confere).
     */
    fun url(raw: String, ctx: String): List<String> {
        val u = raw.trim()
        if (u.isEmpty()) return listOf("$ctx: URL vazia")
        if (u.length > com.coinportracker.network.UrlGuard.MAX_URL_LEN) return listOf("$ctx: URL longa demais")
        if (u.any { it.isWhitespace() || it.code < 0x20 }) return listOf("$ctx: URL inválida")
        val uri = runCatching { URI(u) }.getOrNull() ?: return listOf("$ctx: URL inválida")
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http") return listOf("$ctx: use https (o app bloqueia conexões sem criptografia)")
        if (scheme != "https") return listOf("$ctx: use http ou https")
        val host = uri.host
        if (host.isNullOrBlank()) return listOf("$ctx: URL sem host")
        if (uri.rawUserInfo != null) return listOf("$ctx: não coloque usuário/senha na URL")
        if (com.coinportracker.network.UrlGuard.isBlockedHostName(host)) return listOf("$ctx: endereço local ou interno não é permitido")
        return emptyList()
    }

    const val MAX_IMPORT_BYTES = 512 * 1024
    private const val MAX_TEXT = 128

    /** Limites de quantidade e tamanho (config importada não pode travar a tela nem o app). */
    fun limits(c: DevConfig): List<String> {
        val err = ArrayList<String>()
        if (c.chains.size > 64) err += "Redes demais na configuração"
        c.chains.forEach { if (it.endpoints.size > 16) err += "${it.chain.take(32)}: no máximo 16 endpoints" }
        if (c.prices.size > 16) err += "Fontes de preço demais (máx. 16)"
        if (c.customChains.size > 32) err += "Redes personalizadas demais (máx. 32)"
        if (c.customTokens.size > 500) err += "Tokens personalizados demais (máx. 500)"
        val texts = c.chains.flatMap { ch -> listOf(ch.chain) + ch.endpoints.flatMap { listOf(it.id, it.label, it.kind) } } +
            c.prices.flatMap { listOf(it.id, it.label, it.format) } +
            c.customChains.flatMap { listOf(it.id, it.name, it.nativeSymbol, it.nativeName) } +
            c.customTokens.flatMap { listOf(it.id, it.chain, it.symbol, it.name) }
        if (texts.any { it.length > MAX_TEXT }) err += "Texto longo demais em algum campo (máx. $MAX_TEXT caracteres)"
        val urls = c.customChains.flatMap { listOf(it.explorerTx, it.explorerAddress) }.filter { it.isNotBlank() }
        urls.forEach { e -> err += url(e, "Link do explorador") }
        return err.distinct()
    }

    /** Recusa seed, chave privada e material que pareça chave — o app é só observação. */
    fun secret(raw: String, ctx: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (PRV_PREFIX.containsMatchIn(s)) return "$ctx: chave privada estendida não é aceita"
        if (HEX64.matches(s) || HEX64_0X.matches(s)) return "$ctx: isso parece uma chave privada; o app não aceita"
        if (WORDS.matches(s.lowercase())) return "$ctx: seed phrase não é aceita; o app é só observação"
        val lower = s.lowercase()
        if ("private key" in lower || "seed phrase" in lower || "mnemonic" in lower) return "$ctx: material secreto não é aceito"
        return null
    }

    // v5: regexes pré-compiladas (antes 7 compilações por linha de log a cada leitura da tela Logs)
    private val R_EVM = Regex("0x[0-9a-fA-F]{40}")
    private val R_H64 = Regex("0x[0-9a-fA-F]{64}")
    private val R_BC1 = Regex("\\bbc1[0-9a-z]{20,}\\b")
    private val R_LTC = Regex("\\blt[c1][0-9a-zA-Z]{20,}\\b")
    private val R_TRX = Regex("\\b(?:T)[1-9A-HJ-NP-Za-km-z]{30,34}\\b")
    private val R_XLM = Regex("\\b(?:G)[A-Z2-7]{55}\\b")
    private val R_B58 = Regex("\\b[1-9A-HJ-NP-Za-km-z]{32,44}\\b")

    fun redact(text: String): String {
        var s = text
        s = R_EVM.replace(s) { it.value.take(6) + "…" + it.value.takeLast(4) }
        s = R_H64.replace(s) { it.value.take(6) + "…" + it.value.takeLast(4) }
        s = R_BC1.replace(s) { it.value.take(6) + "…" + it.value.takeLast(4) }
        s = R_LTC.replace(s) { it.value.take(6) + "…" + it.value.takeLast(4) }
        s = R_TRX.replace(s) { it.value.take(4) + "…" + it.value.takeLast(4) }
        s = R_XLM.replace(s) { it.value.take(4) + "…" + it.value.takeLast(4) }
        s = R_B58.replace(s) { m -> if (m.value.length >= 32) m.value.take(4) + "…" + m.value.takeLast(4) else m.value }
        return s
    }

}
