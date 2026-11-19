package com.coinportracker.backup

import com.coinportracker.database.*
import com.coinportracker.security.BackupCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupPayload(
    val schemaVersion: Int = 1,
    val createdAt: Long,
    val appVersion: String,
    val assets: List<AssetEntity> = emptyList(),
    val wallets: List<WalletEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val revisions: List<TransactionRevisionEntity> = emptyList(),
    val priceHistory: List<PriceHistoryEntity> = emptyList(),
    val fxRates: List<FxRateEntity> = emptyList(),
    val watchlist: List<WatchlistEntity> = emptyList(),
    val alerts: List<AlertEntity> = emptyList(),
    val staking: List<StakingPositionEntity> = emptyList(),
    val evmNetworks: List<EvmNetworkEntity> = emptyList(),
    val evmTokens: List<EvmTokenEntity> = emptyList(),
    val snapshots: List<SnapshotEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val costBasis: List<com.coinportracker.database.CostBasisEntity> = emptyList(),
)

@Serializable
data class PlainEnvelope(val format: String = FORMAT, val sha256: String, val payload: String) {
    companion object { const val FORMAT = "coinportracker.backup.v1" }
}

/**
 * Codifica/decodifica backups. Criptografado: AES-256-GCM (ver [BackupCrypto]).
 * Sem criptografia: JSON com SHA-256 do conteúdo para detectar corrupção (não protege contra adulteração intencional).
 */
object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(p: BackupPayload, password: CharArray?, iterations: Int = BackupCrypto.DEFAULT_ITERATIONS): ByteArray {
        val body = json.encodeToString(p).toByteArray(Charsets.UTF_8)
        return if (password != null) BackupCrypto.encrypt(body, password, iterations)
        else json.encodeToString(PlainEnvelope(sha256 = BackupCrypto.sha256Hex(body), payload = String(body, Charsets.UTF_8))).toByteArray(Charsets.UTF_8)
    }

    fun needsPassword(data: ByteArray) = BackupCrypto.isEncrypted(data)

    fun decode(data: ByteArray, password: CharArray?): BackupPayload {
        val body: ByteArray = if (BackupCrypto.isEncrypted(data)) {
            requireNotNull(password) { "Este backup é criptografado: informe a senha." }
            BackupCrypto.decrypt(data, password)
        } else {
            val env = runCatching { json.decodeFromString<PlainEnvelope>(String(data, Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException("Arquivo não é um backup do CoinPortracker.") }
            require(env.format == PlainEnvelope.FORMAT) { "Formato de backup desconhecido: ${env.format}" }
            val b = env.payload.toByteArray(Charsets.UTF_8)
            if (BackupCrypto.sha256Hex(b) != env.sha256) throw IllegalStateException("Falha de integridade: o conteúdo do backup não confere com o SHA-256 registrado.")
            b
        }
        val p = json.decodeFromString<BackupPayload>(String(body, Charsets.UTF_8))
        require(p.schemaVersion <= 1) { "Backup criado por versão mais nova do app (schema ${p.schemaVersion})." }
        validate(p)
        return p
    }

    /** Integridade referencial mínima antes de restaurar. */
    fun validate(p: BackupPayload) {
        val assetIds = p.assets.map { it.id }.toSet()
        val missing = p.transactions.filter { it.deletedAt == null }.flatMap { listOfNotNull(it.assetId, it.toAssetId, it.feeAssetId) }.filter { it !in assetIds }.toSet()
        require(missing.isEmpty()) { "Backup inconsistente: transações referenciam ativos inexistentes (${missing.take(5).joinToString()})." }
        val ids = p.transactions.map { it.id }
        require(ids.size == ids.toSet().size) { "Backup inconsistente: IDs de transação duplicados." }
        // Watch-only: um backup (editado ou adulterado) nunca coloca segredo no banco.
        val V = com.coinportracker.blockchain.AddressValidator
        val secretWallet = p.wallets.firstOrNull { w -> listOfNotNull(w.address, w.notes, w.institution).any { V.looksLikeSecret(it) } }
        require(secretWallet == null) { "Backup recusado: a carteira \"${secretWallet?.name?.take(40)}\" contém algo que parece chave privada ou frase-semente." }
        require(p.transactions.none { t -> t.notes?.let { V.looksLikeSecret(it) } == true }) { "Backup recusado: uma nota de operação parece conter chave privada ou frase-semente." }
    }
}
