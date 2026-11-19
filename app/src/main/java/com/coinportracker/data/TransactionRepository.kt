package com.coinportracker.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.coinportracker.core.Decimals
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.AssetEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.database.TransactionRevisionEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.TxType
import com.coinportracker.domain.ValidationStatus
import com.coinportracker.domain.WalletType
import com.coinportracker.transactions.DuplicateDetector
import com.coinportracker.transactions.ParsedRow
import com.coinportracker.transactions.TxRecord
import com.coinportracker.transactions.TxValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Livro de operações auditável: toda criação/edição/exclusão gera uma revisão com o estado
 * anterior e posterior (JSON). Exclusão é lógica (deletedAt) e pode ser desfeita.
 */
class TransactionRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.transactions()
    private val json = Json { encodeDefaults = true }

    fun paged(assetId: String?, type: TxType?, walletId: Long?, query: String): Flow<PagingData<TransactionEntity>> =
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) { dao.paged(assetId, type?.name, walletId, query.trim()) }.flow

    fun observe(id: Long) = dao.observe(id)
    fun revisions(id: Long) = db.revisions().observeFor(id)
    fun deleted() = dao.observeDeleted()

    private suspend fun maps(): Pair<Map<String, AssetEntity>, Map<Long, WalletEntity>> =
        db.assets().all().associateBy { it.id } to db.wallets().all().associateBy { it.id }

    /** Valida regras de negócio da entidade; retorna lista de erros (vazia = ok). */
    suspend fun validate(t: TransactionEntity): List<String> {
        val (assets, wallets) = maps()
        val rec = t.toRecord(assets, wallets)
        val (errs, _) = TxValidator.validate(rec)
        val extra = ArrayList(errs)
        if (t.assetId !in assets) extra += "Ativo não cadastrado."
        if (t.toAssetId != null && t.toAssetId !in assets) extra += "Ativo de destino não cadastrado."
        if (t.type == TxType.SWAP.name && t.toAssetId == t.assetId) extra += "Swap precisa de ativos diferentes."
        listOfNotNull(t.fromWalletId, t.toWalletId).forEach { if (it !in wallets) extra += "Carteira inexistente ($it)." }
        return extra
    }

    private fun status(t: TransactionEntity): String {
        val type = TxType.valueOf(t.type)
        val incomplete = (type.needsPrice && t.unitPrice.dec() == null) ||
            (type.isIncome && t.unitPrice.dec() == null && type != TxType.AIRDROP) ||
            (type == TxType.DEPOSIT && t.declaredCost.dec() == null && t.unitPrice.dec() == null)
        return when {
            incomplete -> ValidationStatus.INCOMPLETE.name
            !t.txHash.isNullOrBlank() -> ValidationStatus.VALID.name
            else -> if (t.status == ValidationStatus.VALID.name) t.status else ValidationStatus.UNVERIFIED.name
        }
    }

    suspend fun save(t: TransactionEntity, reason: String? = null): Long {
        val errs = validate(t)
        require(errs.isEmpty()) { errs.joinToString("\n") }
        val (assets, wallets) = maps()
        val now = clock()
        return db.withTransaction {
            if (t.id == 0L) {
                val e = t.copy(createdAt = now, updatedAt = now, status = status(t), fingerprint = t.fingerprintWith(assets, wallets))
                val id = dao.insert(e)
                db.revisions().insert(TransactionRevisionEntity(transactionId = id, changedAt = now, action = "CREATE", beforeJson = null, afterJson = json.encodeToString(e.copy(id = id)), reason = reason))
                id
            } else {
                val before = dao.get(t.id) ?: error("Operação ${t.id} não encontrada")
                val e = t.copy(createdAt = before.createdAt, updatedAt = now, status = status(t), fingerprint = t.fingerprintWith(assets, wallets), deletedAt = before.deletedAt)
                dao.update(e)
                db.revisions().insert(TransactionRevisionEntity(transactionId = t.id, changedAt = now, action = "EDIT", beforeJson = json.encodeToString(before), afterJson = json.encodeToString(e), reason = reason))
                t.id
            }
        }
    }

    suspend fun delete(id: Long, reason: String?) = db.withTransaction {
        val before = dao.get(id) ?: return@withTransaction
        val after = before.copy(deletedAt = clock(), updatedAt = clock())
        dao.update(after)
        db.revisions().insert(TransactionRevisionEntity(transactionId = id, changedAt = clock(), action = "DELETE", beforeJson = json.encodeToString(before), afterJson = json.encodeToString(after), reason = reason))
    }

    suspend fun restore(id: Long) = db.withTransaction {
        val before = dao.get(id) ?: return@withTransaction
        val after = before.copy(deletedAt = null, updatedAt = clock())
        dao.update(after)
        db.revisions().insert(TransactionRevisionEntity(transactionId = id, changedAt = clock(), action = "RESTORE", beforeJson = json.encodeToString(before), afterJson = json.encodeToString(after)))
    }

    suspend fun markVerified(id: Long, verified: Boolean) {
        val t = dao.get(id) ?: return
        save(t.copy(status = if (verified) ValidationStatus.VALID.name else ValidationStatus.UNVERIFIED.name), if (verified) "Marcada como validada" else "Validação removida")
    }

    // ---------- Importação ----------

    data class PreviewRow(val parsed: ParsedRow, val duplicateExisting: Boolean, val duplicateInFile: Boolean, val unknownAssets: List<String>, val unknownWallets: List<String>) {
        val importable get() = parsed.valid && !duplicateExisting && !duplicateInFile
    }

    suspend fun preview(rows: List<ParsedRow>): List<PreviewRow> {
        val (assets, wallets) = maps()
        val existing = dao.fingerprints().toSet()
        val symbols = assets.values.map { it.symbol.uppercase() }.toSet()
        val walletNames = wallets.values.map { it.name.lowercase() }.toSet()
        return DuplicateDetector.mark(rows, existing).map { m ->
            val r = m.row.record
            PreviewRow(m.row, m.duplicateOfExisting, m.duplicateInFile,
                listOfNotNull(r?.asset, r?.toAsset, r?.feeAsset).map { it.uppercase() }.filter { it.isNotBlank() && it !in symbols }.distinct(),
                listOfNotNull(r?.fromWallet, r?.toWallet).filter { it.lowercase() !in walletNames }.distinct())
        }
    }

    /**
     * Grava as linhas confirmadas. Ativos/carteiras desconhecidos são criados como personalizados
     * (o usuário vê isso na pré-visualização). Nunca sobrescreve operações existentes.
     */
    suspend fun import(records: List<TxRecord>, source: String): Int = db.withTransaction {
        val assetsBySymbol = HashMap<String, String>()
        db.assets().all().filter { !it.archived }.sortedBy { if (it.isCustom) 1 else 0 }.forEach { assetsBySymbol.putIfAbsent(it.symbol.uppercase(), it.id) }
        val walletsByName = HashMap<String, Long>()
        db.wallets().all().forEach { walletsByName[it.name.lowercase()] = it.id }
        val now = clock()
        fun asset(sym: String?): String? {
            if (sym.isNullOrBlank()) return null
            val s = sym.uppercase()
            return assetsBySymbol[s] ?: ("custom:" + UUID.randomUUID().toString().take(8)).also { id -> assetsBySymbol[s] = id }
        }
        val newAssets = HashMap<String, AssetEntity>()
        suspend fun wallet(name: String?): Long? {
            if (name.isNullOrBlank()) return null
            return walletsByName[name.lowercase()] ?: db.wallets().insert(WalletEntity(name = name, type = WalletType.MANUAL.name, createdAt = now)).also { walletsByName[name.lowercase()] = it }
        }
        var n = 0
        for (r in records) {
            val ids = listOf(r.asset, r.toAsset, r.feeAsset).map { s -> s to asset(s) }
            ids.forEach { (s, id) -> if (s != null && id != null && id.startsWith("custom:") && db.assets().get(id) == null && id !in newAssets) newAssets[id] = AssetEntity(id, s.uppercase(), s.uppercase(), isCustom = true, createdAt = now) }
            newAssets.values.forEach { db.assets().upsert(it) }
            val type = TxValidator.parseType(r.type) ?: continue
            val e = TransactionEntity(
                type = type.name, timestamp = r.timestamp, assetId = ids[0].second!!, quantity = normalizeDecimal(r.quantity) ?: continue,
                unitPrice = normalizeDecimal(r.unitPrice), quoteCurrency = r.currency.uppercase(), toAssetId = ids[1].second, toQuantity = normalizeDecimal(r.toQuantity),
                feeFiat = normalizeDecimal(r.fee), feeAssetId = ids[2].second, feeQuantity = normalizeDecimal(r.feeQuantity),
                fromWalletId = wallet(r.fromWallet), toWalletId = wallet(r.toWallet), txHash = r.txHash, exchange = r.exchange, notes = r.notes,
                fxUsdBrl = normalizeDecimal(r.fxUsdBrl), declaredCost = normalizeDecimal(r.declaredCost), source = source,
            )
            val assets = db.assets().all().associateBy { it.id }; val wallets = db.wallets().all().associateBy { it.id }
            val full = e.copy(createdAt = now, updatedAt = now, status = status(e), fingerprint = e.fingerprintWith(assets, wallets))
            val id = dao.insert(full)
            db.revisions().insert(TransactionRevisionEntity(transactionId = id, changedAt = now, action = "CREATE", beforeJson = null, afterJson = json.encodeToString(full.copy(id = id)), reason = "Importado ($source)"))
            n++
        }
        n
    }

    suspend fun exportRecords(filter: (TransactionEntity) -> Boolean = { true }): List<TxRecord> {
        val (assets, wallets) = maps()
        return dao.active().filter(filter).map { it.toRecord(assets, wallets) }
    }

    fun parseOrNull(s: String) = Decimals.parse(s)
}
