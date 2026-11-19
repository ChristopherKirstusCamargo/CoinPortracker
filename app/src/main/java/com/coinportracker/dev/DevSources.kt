package com.coinportracker.dev

import com.coinportracker.onchain.Network

/**
 * Resolve os endpoints efetivos (padrão da rede + overrides do usuário), na ordem, só os ligados.
 * Se a lista customizada ficar vazia ou toda desligada, volta ao padrão para a rede não ficar muda.
 */
object DevSources {
    @Volatile var current: DevConfig = DevDefaults.empty()
        private set

    fun replace(c: DevConfig) { current = c }

    fun rpcs(net: Network): List<String> {
        val ov = current.chains.firstOrNull { it.chain == net.name } ?: return net.rpcs
        return ov.endpoints.filter { it.enabled && it.kind == EndpointKind.RPC.name }.map { it.url.trim() }.filter { it.isNotEmpty() }.ifEmpty { net.rpcs }
    }

    fun indexers(net: Network): List<String> {
        val ov = current.chains.firstOrNull { it.chain == net.name }
        val custom = ov?.endpoints?.filter { it.enabled && it.kind == EndpointKind.INDEXER.name }?.map { it.url.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (ov == null) return listOfNotNull(net.blockscout)
        return custom.ifEmpty { listOfNotNull(net.blockscout) }
    }

    fun indexer(net: Network): String? = indexers(net).firstOrNull()

    fun price(id: String): PriceSource? = current.prices.firstOrNull { it.id == id }
    fun priceEnabled(id: String): Boolean = price(id)?.enabled ?: (id in setOf("kraken", "defillama", "coingecko", "frankfurter", "paprika"))
    fun priceUrl(id: String, fallback: String): String = price(id)?.url?.trim()?.ifBlank { fallback } ?: fallback
    fun pricesInOrder(): List<PriceSource> = current.prices.ifEmpty { DevDefaults.prices() }

    fun customChainsEnabled(): List<CustomEvmChain> = current.customChains.filter { it.enabled }
    fun customTokens(chain: String): List<CustomToken> = current.customTokens.filter { it.enabled && it.chain == chain }

    /**
     * Failover: tenta cada item na ordem até um ter sucesso. Usado pelos testes e pelo "Testar".
     * [attempt] devolve null em falha, o resultado em sucesso.
     */
    suspend fun <T> failover(urls: List<String>, attempt: suspend (String) -> T?): T? {
        for (u in urls) {
            val r = com.coinportracker.core.catchingNonCancel { attempt(u) }.getOrNull()
            if (r != null) return r
        }
        return null
    }
}
