package com.coinportracker.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Atualizar por cima de qualquer versão anterior (v1.0 → v4.1, versionCode 1–7; banco Room v1, v2 ou v3)
 * nunca perde dados nem trava: o banco antigo é recriado EXATAMENTE pelo esquema exportado da época
 * (app/schemas/…/N.json), preenchido, e aberto pelo AppDatabase atual — que aplica as AutoMigrations e
 * valida o esquema final (Room lança exceção se a migração não bater com as entidades atuais).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun schemaFile(v: Int): File = listOf("schemas", "app/schemas").map { File(it, "com.coinportracker.database.AppDatabase/$v.json") }.first { it.exists() }

    /** Cria o arquivo de banco no esquema da versão [v], como o app daquela versão deixou no aparelho. */
    private fun createAt(v: Int, name: String, fill: SQLiteDatabase.() -> Unit): File {
        val f = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        val db = Json.parseToJsonElement(schemaFile(v).readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(f, null).use { s ->
            for (e in db["entities"]!!.jsonArray) {
                val o = e.jsonObject; val t = o["tableName"]!!.jsonPrimitive.content
                s.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t))
                o["indices"]?.jsonArray?.forEach { i -> s.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t)) }
            }
            db["setupQueries"]!!.jsonArray.forEach { q -> s.execSQL(q.jsonPrimitive.content) }
            s.fill()
            s.version = v
        }
        return f
    }

    private fun open(name: String) = Room.databaseBuilder(ctx, AppDatabase::class.java, name).allowMainThreadQueries().build()

    private fun SQLiteDatabase.v1Data() {
        execSQL("INSERT INTO assets(id,symbol,name,chain,isStablecoin,isCustom,createdAt,archived,coingeckoId) VALUES('solana','SOL','Solana','SOLANA',0,0,1,0,'solana')")
        execSQL("INSERT INTO assets(id,symbol,name,chain,isStablecoin,isCustom,createdAt,archived,manualPriceBrl) VALUES('custom-x','XYZ','Meu token','OTHER',0,1,1,0,'12.3456789012345678')")
        execSQL("INSERT INTO wallets(id,name,type,chain,address,createdAt,archived) VALUES(1,'Phantom','WATCH_ONLY','SOLANA','7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU',1,0)")
        execSQL("INSERT INTO wallets(id,name,type,createdAt,archived) VALUES(2,'Corretora','EXCHANGE',1,0)")
        execSQL("INSERT INTO transactions(id,type,timestamp,assetId,quantity,unitPrice,quoteCurrency,status,fingerprint,source,createdAt,updatedAt,toWalletId) VALUES(10,'BUY',1700000000000,'solana','1.500000000000000001','500.123456789012345678','BRL','CONFIRMED','fp1','MANUAL',1,1,2)")
        execSQL("INSERT INTO transaction_revisions(transactionId,changedAt,action,afterJson) VALUES(10,2,'CREATE','{}')")
        execSQL("INSERT INTO alerts(id,kind,assetId,label,threshold,currency,enabled,repeat,createdAt) VALUES(1,'PRICE_ABOVE','solana','SOL > 1000','1000','BRL',1,0,1)")
        execSQL("INSERT INTO staking_positions(id,assetId,walletId,stakedQuantity,initialQuantity,startDate,active) VALUES(1,'solana',1,'10','10',1,1)")
        execSQL("INSERT INTO snapshots(epochDay,equityBrl,netContribBrl,complete,createdAt) VALUES(19000,'7924.00','7924.00',1,1)")
        execSQL("INSERT INTO goals(id,name,kind,targetValue,currency,createdAt) VALUES(1,'Meta','EQUITY','100000','BRL',1)")
        execSQL("INSERT INTO evm_networks(id,name,chainId,rpcUrl,nativeSymbol,builtIn) VALUES(1,'Ethereum',1,'https://ethereum-rpc.publicnode.com','ETH',1)")
    }

    private fun assertV1DataSurvived(db: AppDatabase) = runBlocking {
        assertEquals(setOf("solana", "custom-x"), db.assets().all().map { it.id }.toSet())
        assertEquals("12.3456789012345678", db.assets().all().first { it.id == "custom-x" }.manualPriceBrl)
        val w = db.wallets().all().associateBy { it.id }
        assertEquals("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", w[1]!!.address)
        assertEquals("Corretora", w[2]!!.name)
        val t = db.transactions().all().single()
        assertEquals("1.500000000000000001", t.quantity)
        assertEquals("500.123456789012345678", t.unitPrice)
        assertEquals(1, db.revisions().all().size)
        assertEquals("1000", db.alerts().all().single().threshold)
        assertEquals("10", db.staking().all().single().stakedQuantity)
        assertEquals("7924.00", db.snapshots().all().single().equityBrl)
        assertEquals("100000", db.goals().all().single().targetValue)
        assertEquals(1, db.evm().networks().size)
    }

    @Test fun upgradeFromV1_app1x_keepsEverything() {
        createAt(1, "from1.db") { v1Data() }
        val db = open("from1.db")
        try {
            assertV1DataSurvived(db)
            runBlocking {
                // tabelas novas (v2/v3) existem e funcionam
                db.chain().upsertCostBasisAll(listOf(CostBasisEntity("SOL", "123.45", "BRL", null, 1)))
                assertEquals("123.45", db.chain().costBasis().single().avgCost)
                assertTrue(db.chain().historyStates().isEmpty())
            }
        } finally { db.close() }
    }

    @Test fun upgradeFromV2_app2x_keepsChainData() {
        createAt(2, "from2.db") {
            v1Data()
            execSQL("INSERT INTO chain_holdings(walletId,network,tokenKey,symbol,name,decimals,amount,kind,verified,flaggedScam,updatedAt) VALUES(1,'SOLANA','native','SOL','Solana',9,'12.345678901',  'NATIVE',1,0,5)")
            execSQL("INSERT INTO cost_basis(groupKey,avgCost,currency,updatedAt) VALUES('SOL','650.5','BRL',5)")
            execSQL("INSERT INTO live_prices(`key`,usd,fetchedAt) VALUES('coingecko:solana',150.25,5)")
        }
        val db = open("from2.db")
        try {
            assertV1DataSurvived(db)
            runBlocking {
                assertEquals("650.5", db.chain().costBasis().single().avgCost)
                assertEquals("12.345678901", db.chain().holdings().single().amount)
            }
        } finally { db.close() }
    }

    @Test fun currentV3_app3x_to_4_1_opensWithoutMigration() {
        createAt(3, "from3.db") { v1Data() }
        val db = open("from3.db")
        try { assertV1DataSurvived(db) } finally { db.close() }
    }

    @Test fun exportedSchemaMatchesCurrentEntities() {
        // se alguém mudar uma entidade sem subir a versão, o identityHash muda e o app travaria ao abrir o banco de quem já usa
        val latest = (1..20).last { v -> runCatching { schemaFile(v) }.isSuccess }
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertEquals(latest, db.openHelper.readableDatabase.version)
            val hash = Json.parseToJsonElement(schemaFile(latest).readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
            db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { c -> assertTrue(c.moveToFirst()); assertEquals(hash, c.getString(0)) }
        } finally { db.close() }
    }
}
