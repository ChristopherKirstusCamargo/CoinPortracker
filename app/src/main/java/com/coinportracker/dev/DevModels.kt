package com.coinportracker.dev

import com.coinportracker.onchain.Network
import kotlinx.serialization.Serializable

enum class EndpointKind(val label: String) { RPC("RPC"), INDEXER("Indexador"), API("API") }

@Serializable
data class DevEndpoint(
    val id: String,
    val url: String,
    val enabled: Boolean = true,
    val kind: String = EndpointKind.RPC.name,
    val label: String = "",
)

@Serializable
data class ChainSources(
    val chain: String,
    val endpoints: List<DevEndpoint> = emptyList(),
)

@Serializable
data class PriceSource(
    val id: String,
    val enabled: Boolean = true,
    val url: String,
    val format: String,
    val label: String,
)

@Serializable
data class CustomEvmChain(
    val id: String,
    val name: String,
    val chainId: Long,
    val rpc: String,
    val explorerApi: String = "",
    val explorerTx: String = "",
    val explorerAddress: String = "",
    val nativeSymbol: String,
    val nativeDecimals: Int = 18,
    val nativeName: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class CustomToken(
    val id: String,
    val chain: String,
    val contract: String,
    val symbol: String,
    val decimals: Int,
    val name: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class DevNetSettings(
    val connectTimeoutSec: Int = 10,
    val readTimeoutSec: Int = 20,
    val callTimeoutSec: Int = 30,
)

@Serializable
data class DevConfig(
    val version: Int = 1,
    val chains: List<ChainSources> = emptyList(),
    val prices: List<PriceSource> = DevDefaults.prices(),
    val customChains: List<CustomEvmChain> = emptyList(),
    val customTokens: List<CustomToken> = emptyList(),
    val net: DevNetSettings = DevNetSettings(),
    val logRedactAddresses: Boolean = true,
)

object DevDefaults {
    const val LLAMA = "https://coins.llama.fi"
    const val KRAKEN = "https://api.kraken.com"
    const val COINGECKO = "https://api.coingecko.com/api/v3"
    const val PAPRIKA = "https://api.coinpaprika.com/v1"
    const val FRANKFURTER = "https://api.frankfurter.dev/v1"

    fun prices(): List<PriceSource> = listOf(
        PriceSource("kraken", true, KRAKEN, "kraken", "Kraken"),
        PriceSource("defillama", true, LLAMA, "defillama", "DefiLlama"),
        PriceSource("coingecko", true, COINGECKO, "coingecko", "CoinGecko"),
        PriceSource("paprika", true, PAPRIKA, "paprika", "CoinPaprika"),
        PriceSource("frankfurter", true, FRANKFURTER, "frankfurter", "Frankfurter"),
    )

    fun endpointsFor(n: Network): List<DevEndpoint> {
        val out = ArrayList<DevEndpoint>()
        n.rpcs.forEachIndexed { i, u -> out += DevEndpoint(id = "${n.name}-rpc-$i", url = u, kind = EndpointKind.RPC.name, label = hostOf(u)) }
        n.blockscout?.let { u -> out += DevEndpoint(id = "${n.name}-idx", url = u, kind = EndpointKind.INDEXER.name, label = hostOf(u)) }
        return out
    }

    fun chainSources(): List<ChainSources> = Network.entries.map { ChainSources(it.name, endpointsFor(it)) }

    fun empty(): DevConfig = DevConfig(chains = emptyList(), prices = prices())

    /** Config "completa" já preenchida com os padrões, para a tela editar. */
    fun materialize(c: DevConfig): DevConfig {
        val byName = c.chains.associateBy { it.chain }
        val chains = Network.entries.map { n -> byName[n.name] ?: ChainSources(n.name, endpointsFor(n)) }
        val prices = if (c.prices.isEmpty()) prices() else c.prices
        return c.copy(chains = chains, prices = prices)
    }

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')
}
