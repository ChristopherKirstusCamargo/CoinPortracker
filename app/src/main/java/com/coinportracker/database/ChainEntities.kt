package com.coinportracker.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Saldo lido da blockchain (cache local; recalculado a cada sincronização, nunca digitado pelo usuário). */
@kotlinx.serialization.Serializable
@Entity(tableName = "chain_holdings", primaryKeys = ["walletId", "network", "tokenKey"], indices = [Index("walletId")])
data class ChainHoldingEntity(
    val walletId: Long,
    val network: String,
    val tokenKey: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val amount: String,
    val iconUrl: String?,
    val priceKey: String?,
    val kind: String,
    val verified: Boolean,
    val flaggedScam: Boolean,
    val priceUsdHint: Double?,
    val coingeckoId: String?,
    val updatedAt: Long,
)

@kotlinx.serialization.Serializable
@Entity(tableName = "chain_activity", primaryKeys = ["walletId", "network", "hash"], indices = [Index("timestamp")])
data class ChainActivityEntity(
    val walletId: Long,
    val network: String,
    val hash: String,
    val timestamp: Long?,
    val direction: String,
    val symbol: String,
    val amount: String?,
    val tokenKey: String?,
    val counterparty: String?,
    val fee: String?,
    val failed: Boolean,
    val label: String?,
    val iconUrl: String?,
    val priceKey: String?,
)

@Entity(tableName = "live_prices")
data class LivePriceEntity(
    @PrimaryKey val key: String,
    val usd: Double,
    val change24h: Double?,
    val confidence: Double?,
    val symbol: String?,
    val fetchedAt: Long,
)

/** Preço médio definido pelo usuário para um ativo detectado on-chain (quando a blockchain não informa custo). */
@kotlinx.serialization.Serializable
@Entity(tableName = "cost_basis")
data class CostBasisEntity(
    @PrimaryKey val groupKey: String,
    val avgCost: String,
    val currency: String,
    val note: String? = null,
    val updatedAt: Long = 0,
)

/** Estado da leitura paginada do histórico de uma carteira em uma rede (v3). */
@kotlinx.serialization.Serializable
@Entity(tableName = "history_state", primaryKeys = ["walletId", "network"])
data class HistoryStateEntity(
    val walletId: Long,
    val network: String,
    val json: String,
    val updatedAt: Long,
)

/** Foto do patrimônio total (USD) gravada a cada atualização (no máximo a cada 15 min; compactada para 1/dia após 30 dias). */
@kotlinx.serialization.Serializable
@Entity(tableName = "value_snapshots")
data class ValueSnapshotEntity(
    @PrimaryKey val t: Long,
    val totalUsd: Double,
    val partial: Boolean,
)

@Dao
interface ChainDao {
    @Query("SELECT * FROM history_state") suspend fun historyStates(): List<HistoryStateEntity>
    @Query("SELECT * FROM history_state WHERE walletId = :walletId AND network = :network") suspend fun historyState(walletId: Long, network: String): HistoryStateEntity?
    @Upsert suspend fun upsertHistoryState(s: HistoryStateEntity)
    @Query("DELETE FROM history_state WHERE walletId = :walletId") suspend fun clearHistoryState(walletId: Long)

    @Query("SELECT * FROM value_snapshots WHERE t >= :from ORDER BY t") suspend fun snapshots(from: Long): List<ValueSnapshotEntity>
    @Query("SELECT * FROM value_snapshots ORDER BY t DESC LIMIT 1") suspend fun lastSnapshot(): ValueSnapshotEntity?
    @Upsert suspend fun upsertSnapshot(s: ValueSnapshotEntity)
    @Query("DELETE FROM value_snapshots WHERE t < :before AND t NOT IN (SELECT MAX(t) FROM value_snapshots WHERE t < :before GROUP BY t / 86400000)") suspend fun compactSnapshots(before: Long)

    @Query("SELECT * FROM chain_activity") suspend fun allActivity(): List<ChainActivityEntity>

    @Query("SELECT * FROM cost_basis") fun observeCostBasis(): Flow<List<CostBasisEntity>>
    @Query("SELECT * FROM cost_basis") suspend fun costBasis(): List<CostBasisEntity>
    @Upsert suspend fun upsertCostBasis(c: CostBasisEntity)
    @Upsert suspend fun upsertCostBasisAll(c: List<CostBasisEntity>)
    @Query("DELETE FROM cost_basis WHERE groupKey = :key") suspend fun deleteCostBasis(key: String)

    @Query("SELECT * FROM chain_holdings") fun observeHoldings(): Flow<List<ChainHoldingEntity>>
    @Query("SELECT * FROM chain_holdings") suspend fun holdings(): List<ChainHoldingEntity>
    @Query("DELETE FROM chain_holdings WHERE walletId = :walletId AND network = :network") suspend fun clearHoldings(walletId: Long, network: String)
    @Query("DELETE FROM chain_holdings WHERE walletId = :walletId") suspend fun clearWallet(walletId: Long)
    @Upsert suspend fun upsertHoldings(h: List<ChainHoldingEntity>)

    @Transaction
    suspend fun replaceHoldings(walletId: Long, network: String, h: List<ChainHoldingEntity>) { clearHoldings(walletId, network); upsertHoldings(h) }

    @Query("SELECT * FROM chain_activity ORDER BY timestamp DESC LIMIT :limit") fun observeActivity(limit: Int): Flow<List<ChainActivityEntity>>
    @Query("SELECT * FROM chain_activity WHERE walletId = :walletId ORDER BY timestamp DESC LIMIT 200") fun observeWalletActivity(walletId: Long): Flow<List<ChainActivityEntity>>
    @Upsert suspend fun upsertActivity(a: List<ChainActivityEntity>)
    /** v6.0: transações gravadas sem valores (o nó não devolveu os detalhes na hora). */
    @Query("SELECT * FROM chain_activity WHERE network = :network AND direction = 'CONTRACT' AND amount IS NULL AND failed = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun bareActivity(network: String, limit: Int): List<ChainActivityEntity>
    @Query("DELETE FROM chain_activity WHERE walletId = :walletId AND network = :network AND hash = :hash") suspend fun deleteActivity(walletId: Long, network: String, hash: String)
    @androidx.room.Transaction suspend fun replaceActivity(old: ChainActivityEntity, fresh: List<ChainActivityEntity>) { deleteActivity(old.walletId, old.network, old.hash); upsertActivity(fresh) }
    @Query("SELECT COUNT(*) FROM chain_activity") suspend fun activityCount(): Int
    @Query("SELECT * FROM chain_activity ORDER BY timestamp DESC LIMIT :limit") suspend fun recentActivity(limit: Int): List<ChainActivityEntity>
    @Query("DELETE FROM chain_activity WHERE walletId = :walletId") suspend fun clearActivity(walletId: Long)

    @Query("SELECT * FROM live_prices") fun observePrices(): Flow<List<LivePriceEntity>>
    @Query("SELECT * FROM live_prices") suspend fun prices(): List<LivePriceEntity>
    @Upsert suspend fun upsertPrices(p: List<LivePriceEntity>)
}
