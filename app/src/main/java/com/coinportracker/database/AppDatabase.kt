package com.coinportracker.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coinportracker.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        AssetEntity::class, WalletEntity::class, CategoryEntity::class, TransactionEntity::class, TransactionRevisionEntity::class,
        QuoteEntity::class, PriceHistoryEntity::class, FxRateEntity::class, WatchlistEntity::class, MarketCacheEntity::class,
        AlertEntity::class, StakingPositionEntity::class, OnchainBalanceEntity::class, OnchainTxEntity::class,
        EvmNetworkEntity::class, EvmTokenEntity::class, SnapshotEntity::class, GoalEntity::class, ApiUsageEntity::class,
        ChainHoldingEntity::class, ChainActivityEntity::class, LivePriceEntity::class, CostBasisEntity::class,
        HistoryStateEntity::class, ValueSnapshotEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assets(): AssetDao
    abstract fun wallets(): WalletDao
    abstract fun categories(): CategoryDao
    abstract fun transactions(): TransactionDao
    abstract fun revisions(): RevisionDao
    abstract fun quotes(): QuoteDao
    abstract fun priceHistory(): PriceHistoryDao
    abstract fun fx(): FxDao
    abstract fun watchlist(): WatchlistDao
    abstract fun marketCache(): MarketCacheDao
    abstract fun alerts(): AlertDao
    abstract fun staking(): StakingDao
    abstract fun onchain(): OnchainDao
    abstract fun evm(): EvmDao
    abstract fun snapshots(): SnapshotDao
    abstract fun goals(): GoalDao
    abstract fun apiUsage(): ApiUsageDao
    abstract fun chain(): ChainDao

    companion object {
        const val NAME = "coinportracker.db"

        /** Banco criptografado com SQLCipher; a senha do banco é protegida pelo Android Keystore. */
        fun encrypted(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val pass = DatabaseKeyManager(context).getOrCreatePassphrase()
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(pass))
                .addCallback(SeedCallback)
                .build()
        }

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().addCallback(SeedCallback).build()
    }

    /** Dados de referência (não financeiros): categorias e redes EVM conhecidas. */
    object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            val cats = listOf("Bitcoin" to 0xFFF7931AL, "Layer 1" to 0xFF4C7DFFL, "Layer 2" to 0xFF6E5BFFL, "Stablecoins" to 0xFF2BB39BL,
                "DeFi" to 0xFF9C6BFFL, "Infraestrutura" to 0xFF5AA9E6L, "Inteligência artificial" to 0xFF7F8CFFL, "DePIN" to 0xFF3FA7A0L,
                "Memecoins" to 0xFFE0A84FL, "Outros" to 0xFF7A8499L)
            cats.forEach { (n, c) -> db.execSQL("INSERT INTO categories(name, color) VALUES(?, ?)", arrayOf<Any>(n, c)) }
            val nets = listOf(
                arrayOf<Any>("Ethereum", 1L, "https://ethereum-rpc.publicnode.com", "ETH", "ethereum"),
                arrayOf<Any>("Arbitrum One", 42161L, "https://arb1.arbitrum.io/rpc", "ETH", "ethereum"),
                arrayOf<Any>("Base", 8453L, "https://mainnet.base.org", "ETH", "ethereum"),
                arrayOf<Any>("OP Mainnet", 10L, "https://mainnet.optimism.io", "ETH", "ethereum"),
                arrayOf<Any>("Polygon PoS", 137L, "https://polygon-bor-rpc.publicnode.com", "POL", "polygon-ecosystem-token"),
                arrayOf<Any>("BNB Smart Chain", 56L, "https://bsc-dataseed.bnbchain.org", "BNB", "binancecoin"),
            )
            nets.forEach { db.execSQL("INSERT INTO evm_networks(name, chainId, rpcUrl, nativeSymbol, nativeCoingeckoId, builtIn) VALUES(?, ?, ?, ?, ?, 1)", it) }
        }
    }
}
