package com.coinportracker.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE archived = 0 ORDER BY symbol") fun observeAll(): Flow<List<AssetEntity>>
    @Query("SELECT * FROM assets") suspend fun all(): List<AssetEntity>
    @Query("SELECT * FROM assets WHERE id = :id") suspend fun get(id: String): AssetEntity?
    @Query("SELECT * FROM assets WHERE id = :id") fun observe(id: String): Flow<AssetEntity?>
    @Query("SELECT * FROM assets WHERE UPPER(symbol) = UPPER(:symbol) AND archived = 0") suspend fun bySymbol(symbol: String): List<AssetEntity>
    @Upsert suspend fun upsert(a: AssetEntity)
    @Upsert suspend fun upsertAll(a: List<AssetEntity>)
    @Query("DELETE FROM assets WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets WHERE archived = 0 ORDER BY name") fun observeAll(): Flow<List<WalletEntity>>
    @Query("SELECT * FROM wallets") suspend fun all(): List<WalletEntity>
    @Query("SELECT * FROM wallets WHERE id = :id") suspend fun get(id: Long): WalletEntity?
    @Insert suspend fun insert(w: WalletEntity): Long
    @Upsert suspend fun upsert(w: WalletEntity)
    @Update suspend fun update(w: WalletEntity)
    @Upsert suspend fun upsertAll(w: List<WalletEntity>)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories") suspend fun all(): List<CategoryEntity>
    @Insert suspend fun insert(c: CategoryEntity): Long
    @Upsert suspend fun upsertAll(c: List<CategoryEntity>)
    @Delete suspend fun delete(c: CategoryEntity)
    @Query("UPDATE assets SET categoryId = NULL WHERE categoryId = :id") suspend fun clearFromAssets(id: Long)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY timestamp ASC, id ASC") fun observeActive(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY timestamp ASC, id ASC") suspend fun active(): List<TransactionEntity>
    @Query("SELECT * FROM transactions") suspend fun all(): List<TransactionEntity>
    @Query("""SELECT * FROM transactions WHERE deletedAt IS NULL
        AND (:assetId IS NULL OR assetId = :assetId OR toAssetId = :assetId OR feeAssetId = :assetId)
        AND (:type IS NULL OR type = :type)
        AND (:walletId IS NULL OR fromWalletId = :walletId OR toWalletId = :walletId)
        AND (:query = '' OR notes LIKE '%' || :query || '%' OR txHash LIKE '%' || :query || '%' OR exchange LIKE '%' || :query || '%' OR assetId LIKE '%' || :query || '%')
        ORDER BY timestamp DESC, id DESC""")
    fun paged(assetId: String?, type: String?, walletId: Long?, query: String): PagingSource<Int, TransactionEntity>
    @Query("SELECT * FROM transactions WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeleted(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE id = :id") fun observe(id: Long): Flow<TransactionEntity?>
    @Query("SELECT fingerprint FROM transactions WHERE deletedAt IS NULL") suspend fun fingerprints(): List<String>
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY timestamp DESC, id DESC LIMIT :n") fun observeLatest(n: Int): Flow<List<TransactionEntity>>
    @Insert suspend fun insert(t: TransactionEntity): Long
    @Update suspend fun update(t: TransactionEntity)
    @Upsert suspend fun upsertAll(t: List<TransactionEntity>)
    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL") suspend fun countActive(): Int
}

@Dao
interface RevisionDao {
    @Query("SELECT * FROM transaction_revisions WHERE transactionId = :txId ORDER BY changedAt DESC, id DESC") fun observeFor(txId: Long): Flow<List<TransactionRevisionEntity>>
    @Query("SELECT * FROM transaction_revisions ORDER BY changedAt DESC LIMIT 500") fun observeRecent(): Flow<List<TransactionRevisionEntity>>
    @Query("SELECT * FROM transaction_revisions") suspend fun all(): List<TransactionRevisionEntity>
    @Insert suspend fun insert(r: TransactionRevisionEntity): Long
    @Upsert suspend fun upsertAll(r: List<TransactionRevisionEntity>)
}

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes") fun observeAll(): Flow<List<QuoteEntity>>
    @Query("SELECT * FROM quotes") suspend fun all(): List<QuoteEntity>
    @Upsert suspend fun upsertAll(q: List<QuoteEntity>)
    @Query("DELETE FROM quotes") suspend fun clearAll()
}

@Dao
interface PriceHistoryDao {
    @Query("SELECT * FROM price_history WHERE currency = :currency") suspend fun allFor(currency: String): List<PriceHistoryEntity>
    @Query("SELECT * FROM price_history WHERE assetId = :assetId AND currency = :currency ORDER BY epochDay") suspend fun series(assetId: String, currency: String): List<PriceHistoryEntity>
    @Query("SELECT * FROM price_history WHERE currency = :currency") fun observeFor(currency: String): Flow<List<PriceHistoryEntity>>
    @Query("SELECT MAX(epochDay) FROM price_history WHERE assetId = :assetId AND currency = :currency") suspend fun lastDay(assetId: String, currency: String): Long?
    @Query("SELECT * FROM price_history") suspend fun all(): List<PriceHistoryEntity>
    @Upsert suspend fun upsertAll(p: List<PriceHistoryEntity>)
    @Query("DELETE FROM price_history") suspend fun clearAll()
}

@Dao
interface FxDao {
    @Query("SELECT * FROM fx_rates ORDER BY epochDay") suspend fun all(): List<FxRateEntity>
    @Query("SELECT * FROM fx_rates ORDER BY epochDay") fun observeAll(): Flow<List<FxRateEntity>>
    @Query("SELECT * FROM fx_rates WHERE epochDay <= :day ORDER BY epochDay DESC LIMIT 1") suspend fun onOrBefore(day: Long): FxRateEntity?
    @Upsert suspend fun upsertAll(f: List<FxRateEntity>)
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt") fun observeAll(): Flow<List<WatchlistEntity>>
    @Query("SELECT * FROM watchlist") suspend fun all(): List<WatchlistEntity>
    @Upsert suspend fun upsert(w: WatchlistEntity)
    @Upsert suspend fun upsertAll(w: List<WatchlistEntity>)
    @Query("DELETE FROM watchlist WHERE coingeckoId = :id") suspend fun delete(id: String)
}

@Dao
interface MarketCacheDao {
    @Query("SELECT * FROM market_cache WHERE `key` = :key") suspend fun get(key: String): MarketCacheEntity?
    @Query("SELECT * FROM market_cache WHERE `key` = :key") fun observe(key: String): Flow<MarketCacheEntity?>
    @Upsert suspend fun upsert(m: MarketCacheEntity)
    @Query("DELETE FROM market_cache") suspend fun clearAll()
    @Query("DELETE FROM market_cache WHERE `key` = :key") suspend fun delete(key: String)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC") fun observeAll(): Flow<List<AlertEntity>>
    @Query("SELECT * FROM alerts") suspend fun all(): List<AlertEntity>
    @Insert suspend fun insert(a: AlertEntity): Long
    @Update suspend fun update(a: AlertEntity)
    @Upsert suspend fun upsertAll(a: List<AlertEntity>)
    @Delete suspend fun delete(a: AlertEntity)
}

@Dao
interface StakingDao {
    @Query("SELECT * FROM staking_positions ORDER BY active DESC, startDate DESC") fun observeAll(): Flow<List<StakingPositionEntity>>
    @Query("SELECT * FROM staking_positions") suspend fun all(): List<StakingPositionEntity>
    @Query("SELECT * FROM staking_positions WHERE id = :id") suspend fun get(id: Long): StakingPositionEntity?
    @Insert suspend fun insert(s: StakingPositionEntity): Long
    @Update suspend fun update(s: StakingPositionEntity)
    @Upsert suspend fun upsertAll(s: List<StakingPositionEntity>)
    @Delete suspend fun delete(s: StakingPositionEntity)
}

@Dao
interface OnchainDao {
    @Query("SELECT * FROM onchain_balances") fun observeBalances(): Flow<List<OnchainBalanceEntity>>
    @Query("SELECT * FROM onchain_balances") suspend fun allBalances(): List<OnchainBalanceEntity>
    @Query("SELECT * FROM onchain_txs WHERE walletId = :walletId ORDER BY timestamp DESC") fun observeTxs(walletId: Long): Flow<List<OnchainTxEntity>>
    @Query("SELECT * FROM onchain_txs") suspend fun allTxs(): List<OnchainTxEntity>
    @Query("DELETE FROM onchain_balances WHERE walletId = :walletId") suspend fun clearBalances(walletId: Long)
    @Upsert suspend fun upsertBalances(b: List<OnchainBalanceEntity>)
    @Upsert suspend fun upsertTxs(t: List<OnchainTxEntity>)
    @Transaction
    suspend fun replaceBalances(walletId: Long, b: List<OnchainBalanceEntity>) { clearBalances(walletId); upsertBalances(b) }
}

@Dao
interface EvmDao {
    @Query("SELECT * FROM evm_networks ORDER BY builtIn DESC, name") fun observeNetworks(): Flow<List<EvmNetworkEntity>>
    @Query("SELECT * FROM evm_networks") suspend fun networks(): List<EvmNetworkEntity>
    @Query("SELECT * FROM evm_networks WHERE id = :id") suspend fun network(id: Long): EvmNetworkEntity?
    @Insert suspend fun insertNetwork(n: EvmNetworkEntity): Long
    @Upsert suspend fun upsertNetworks(n: List<EvmNetworkEntity>)
    @Delete suspend fun deleteNetwork(n: EvmNetworkEntity)
    @Query("SELECT * FROM evm_tokens") fun observeTokens(): Flow<List<EvmTokenEntity>>
    @Query("SELECT * FROM evm_tokens WHERE networkId = :networkId") suspend fun tokens(networkId: Long): List<EvmTokenEntity>
    @Query("SELECT * FROM evm_tokens") suspend fun allTokens(): List<EvmTokenEntity>
    @Insert suspend fun insertToken(t: EvmTokenEntity): Long
    @Upsert suspend fun upsertTokens(t: List<EvmTokenEntity>)
    @Delete suspend fun deleteToken(t: EvmTokenEntity)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshots ORDER BY epochDay") fun observeAll(): Flow<List<SnapshotEntity>>
    @Query("SELECT * FROM snapshots ORDER BY epochDay") suspend fun all(): List<SnapshotEntity>
    @Upsert suspend fun upsert(s: SnapshotEntity)
    @Upsert suspend fun upsertAll(s: List<SnapshotEntity>)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY createdAt DESC") fun observeAll(): Flow<List<GoalEntity>>
    @Query("SELECT * FROM goals") suspend fun all(): List<GoalEntity>
    @Insert suspend fun insert(g: GoalEntity): Long
    @Update suspend fun update(g: GoalEntity)
    @Upsert suspend fun upsertAll(g: List<GoalEntity>)
    @Delete suspend fun delete(g: GoalEntity)
}

@Dao
interface ApiUsageDao {
    @Query("SELECT * FROM api_usage WHERE month = :month") fun observeMonth(month: String): Flow<List<ApiUsageEntity>>
    @Query("SELECT count FROM api_usage WHERE provider = :provider AND month = :month") suspend fun count(provider: String, month: String): Int?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(e: ApiUsageEntity)
    @Query("UPDATE api_usage SET count = count + 1 WHERE provider = :provider AND month = :month") suspend fun incrementExisting(provider: String, month: String)
    @Transaction
    suspend fun increment(provider: String, month: String) { insertIgnore(ApiUsageEntity(provider, month, 0)); incrementExisting(provider, month) }
}
