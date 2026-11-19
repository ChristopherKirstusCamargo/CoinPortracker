package com.coinportracker.market

import com.coinportracker.core.Outcome
import com.coinportracker.domain.Currency
import kotlinx.serialization.Serializable
import java.math.BigDecimal

data class MarketQuote(
    val providerAssetId: String,
    val currency: Currency,
    val price: BigDecimal,
    val change24hPct: BigDecimal? = null,
    val marketCap: BigDecimal? = null,
    val volume24h: BigDecimal? = null,
    val circulatingSupply: BigDecimal? = null,
    val totalSupply: BigDecimal? = null,
    val maxSupply: BigDecimal? = null,
    val rank: Int? = null,
    val providerUpdatedAt: Long? = null,
)

data class PricePoint(val epochDay: Long, val price: BigDecimal)

@Serializable
data class AssetSearchResult(val providerId: String, val symbol: String, val name: String, val rank: Int? = null, val thumb: String? = null)

@Serializable
data class MarketCoin(
    val id: String, val symbol: String, val name: String, val image: String? = null,
    val price: String? = null, val change24hPct: String? = null, val marketCap: String? = null, val volume24h: String? = null,
    val rank: Int? = null, val circulating: String? = null, val maxSupply: String? = null, val sparkline: List<Double> = emptyList(),
)

@Serializable
data class GlobalData(
    val totalMarketCap: String?, val totalVolume: String?, val btcDominancePct: String?, val ethDominancePct: String?,
    val activeCryptocurrencies: Int?, val marketCapChange24hPct: String?, val currency: String,
)

/**
 * Contrato de provedor de mercado. A lógica financeira só depende desta interface,
 * então trocar de provedor não exige reescrever cálculos.
 */
interface MarketDataProvider {
    val id: String
    val displayName: String
    val attribution: String
    val limitsDescription: String
    /** Máximo de dias de histórico diário acessível gratuitamente. */
    val maxHistoryDays: Int
    /** Identificador deste provedor para um ativo (cada provedor tem seus próprios IDs). */
    fun idFor(coingeckoId: String?, paprikaId: String?): String?

    suspend fun quotes(ids: List<String>, currencies: List<Currency>): Outcome<List<MarketQuote>>
    suspend fun history(id: String, currency: Currency, days: Int): Outcome<List<PricePoint>>
    suspend fun search(query: String): Outcome<List<AssetSearchResult>>
    suspend fun markets(currency: Currency, page: Int, perPage: Int): Outcome<List<MarketCoin>>
    suspend fun global(currency: Currency): Outcome<GlobalData>
}

interface FxProvider {
    val id: String
    suspend fun latestUsdBrl(): Outcome<Pair<Long, BigDecimal>>
    suspend fun seriesUsdBrl(fromEpochDay: Long, toEpochDay: Long): Outcome<List<Pair<Long, BigDecimal>>>
}
