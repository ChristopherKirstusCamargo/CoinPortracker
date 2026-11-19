package com.coinportracker.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Valores decimais são persistidos como TEXT (BigDecimal.toPlainString) para preservar
 * a precisão exata. Nunca REAL/Double para valores financeiros.
 */

@Serializable
@Entity(tableName = "assets", indices = [Index("symbol"), Index("coingeckoId"), Index("categoryId")])
data class AssetEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val name: String,
    val chain: String = "OTHER",
    val categoryId: Long? = null,
    val isStablecoin: Boolean = false,
    val coingeckoId: String? = null,
    val paprikaId: String? = null,
    val contractAddress: String? = null,
    val decimals: Int? = null,
    val isCustom: Boolean = false,
    val manualPriceBrl: String? = null,
    val manualPriceUsd: String? = null,
    val manualPriceAt: Long? = null,
    val notes: String? = null,
    val strategy: String? = null,
    val createdAt: Long = 0,
    val archived: Boolean = false,
)

@Serializable
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val chain: String? = null,
    val address: String? = null,
    val evmNetworkId: Long? = null,
    val institution: String? = null,
    val notes: String? = null,
    val createdAt: Long = 0,
    val archived: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
)

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
)

@Serializable
@Entity(
    tableName = "transactions",
    indices = [Index("assetId"), Index("timestamp"), Index("fingerprint"), Index("deletedAt"), Index("fromWalletId"), Index("toWalletId")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val timestamp: Long,
    val assetId: String,
    val quantity: String,
    val unitPrice: String? = null,
    val quoteCurrency: String = "BRL",
    val toAssetId: String? = null,
    val toQuantity: String? = null,
    val feeFiat: String? = null,
    val feeAssetId: String? = null,
    val feeQuantity: String? = null,
    val fromWalletId: Long? = null,
    val toWalletId: Long? = null,
    val txHash: String? = null,
    val exchange: String? = null,
    val notes: String? = null,
    val categoryId: Long? = null,
    val status: String = "UNVERIFIED",
    val fxUsdBrl: String? = null,
    val declaredCost: String? = null,
    val fingerprint: String = "",
    val source: String = "MANUAL",
    val stakingPositionId: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "transaction_revisions", indices = [Index("transactionId")])
data class TransactionRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val changedAt: Long,
    val action: String,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String? = null,
)

@Serializable
@Entity(tableName = "quotes", primaryKeys = ["assetId", "currency"])
data class QuoteEntity(
    val assetId: String,
    val currency: String,
    val price: String,
    val change24hPct: String? = null,
    val marketCap: String? = null,
    val volume24h: String? = null,
    val circulatingSupply: String? = null,
    val totalSupply: String? = null,
    val maxSupply: String? = null,
    val rank: Int? = null,
    val source: String,
    val providerUpdatedAt: Long? = null,
    val fetchedAt: Long,
)

@Serializable
@Entity(tableName = "price_history", primaryKeys = ["assetId", "currency", "epochDay"])
data class PriceHistoryEntity(
    val assetId: String,
    val currency: String,
    val epochDay: Long,
    val price: String,
    val source: String,
)

@Serializable
@Entity(tableName = "fx_rates")
data class FxRateEntity(
    @PrimaryKey val epochDay: Long,
    val usdBrl: String,
    val source: String,
)

@Serializable
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val coingeckoId: String,
    val symbol: String,
    val name: String,
    val interestPrice: String? = null,
    val interestCurrency: String = "BRL",
    val note: String? = null,
    val addedAt: Long = 0,
)

@Serializable
@Entity(tableName = "market_cache")
data class MarketCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val source: String,
    val fetchedAt: Long,
)

@Serializable
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val assetId: String? = null,
    val coingeckoId: String? = null,
    val label: String,
    val threshold: String,
    val currency: String = "BRL",
    val enabled: Boolean = true,
    val repeat: Boolean = false,
    val lastTriggeredAt: Long? = null,
    val lastValue: String? = null,
    val createdAt: Long = 0,
)

@Serializable
@Entity(tableName = "staking_positions", indices = [Index("assetId")])
data class StakingPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: String,
    val walletId: Long? = null,
    val stakedQuantity: String,
    val initialQuantity: String,
    val startDate: Long,
    val reportedApyPct: String? = null,
    val lockDays: Int? = null,
    val exitDays: Int? = null,
    val provider: String? = null,
    val validator: String? = null,
    val notes: String? = null,
    val active: Boolean = true,
    val onchainAccount: String? = null,
)

@Serializable
@Entity(tableName = "onchain_balances", primaryKeys = ["walletId", "assetKey"])
data class OnchainBalanceEntity(
    val walletId: Long,
    val assetKey: String,
    val symbol: String,
    val assetId: String? = null,
    val quantity: String,
    val chain: String,
    val kind: String = "NATIVE",
    val fetchedAt: Long,
    val source: String,
)

@Serializable
@Entity(tableName = "onchain_txs", primaryKeys = ["walletId", "hash"])
data class OnchainTxEntity(
    val walletId: Long,
    val hash: String,
    val timestamp: Long?,
    val direction: String,
    val amount: String?,
    val symbol: String,
    val fee: String? = null,
    val status: String? = null,
    val fetchedAt: Long,
)

@Serializable
@Entity(tableName = "evm_networks")
data class EvmNetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val chainId: Long,
    val rpcUrl: String,
    val nativeSymbol: String,
    val nativeCoingeckoId: String? = null,
    val builtIn: Boolean = false,
)

@Serializable
@Entity(tableName = "evm_tokens")
data class EvmTokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val contract: String,
    val symbol: String,
    val decimals: Int,
    val coingeckoId: String? = null,
)

@Serializable
@Entity(tableName = "snapshots", primaryKeys = ["epochDay"])
data class SnapshotEntity(
    val epochDay: Long,
    val equityBrl: String?,
    val equityUsd: String?,
    val netContribBrl: String,
    val netContribUsd: String?,
    val hhi: String?,
    val complete: Boolean,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val assetId: String? = null,
    val targetValue: String? = null,
    val targetQuantity: String? = null,
    val currency: String = "BRL",
    val deadline: Long? = null,
    val createdAt: Long = 0,
    val reachedAt: Long? = null,
)

@Serializable
@Entity(tableName = "api_usage", primaryKeys = ["provider", "month"])
data class ApiUsageEntity(
    val provider: String,
    val month: String,
    val count: Int,
)
