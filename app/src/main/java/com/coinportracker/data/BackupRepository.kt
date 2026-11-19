package com.coinportracker.data

import android.content.Context
import androidx.room.withTransaction
import com.coinportracker.BuildConfig
import com.coinportracker.backup.*
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.MarketCacheEntity
import com.coinportracker.media.BannerLibrary
import com.coinportracker.media.MediaHd
import com.coinportracker.media.VideoImport
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.settings.RollRules
import com.coinportracker.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** O que uma restauração vai fazer (mostrado antes de confirmar). */
data class RestorePreview(
    val createdAt: Long, val appVersion: String, val full: Boolean,
    val walletsNew: Int, val walletsKnown: Int, val activityRows: Int, val transactions: Int, val alerts: Int, val goals: Int,
    val mediaFiles: Int, val hasSettings: Boolean, val hasDev: Boolean, val hasTax: Boolean,
)

/** Resultado da restauração aplicada. */
data class RestoreResult(val walletsAdded: Int, val rowsAdded: Int, val mediaAdded: Int, val mediaDuplicates: Int, val mediaSkipped: Int, val bannerKept: Boolean)

@Serializable
private data class UndoSnapshot(val at: Long, val settings: Map<String, String>, val roll: RollStateDto?, val dev: String?, val tax: String?)

/**
 * Backup e restauração.
 * v6.0 — backup completo (formato 2, em fluxo cifrado): todas as tabelas, carteiras, histórico on-chain, staking, fotos e
 * GIFs da Rolagem (com as cópias HD e a escolhida), banner único, todos os ajustes (aparência, animações, alertas, metas,
 * baixo consumo, Preço do portfólio, Web Host), regras fiscais e configurações Dev. Ficam de fora, por segurança: a chave
 * de API do CoinGecko e os aparelhos pareados do Web Host.
 * Restauração NÃO destrutiva: nada que já está no app é apagado; só entra o que falta. Os ajustes do backup passam a valer,
 * e os ajustes anteriores ficam guardados (cifrados no banco) para "Desfazer ajustes da restauração".
 */
class BackupRepository(
    private val db: AppDatabase, private val settings: SettingsRepository,
    private val context: Context? = null,
    private val dev: com.coinportracker.dev.DevConfigRepository? = null,
    private val tax: com.coinportracker.tax.TaxRulesRepository? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun snapshot(): BackupPayload = BackupPayload(
        createdAt = System.currentTimeMillis(), appVersion = BuildConfig.VERSION_NAME,
        assets = db.assets().all(), wallets = db.wallets().all(), categories = db.categories().all(), transactions = db.transactions().all(),
        revisions = db.revisions().all(), priceHistory = db.priceHistory().all(), fxRates = db.fx().all(), watchlist = db.watchlist().all(),
        alerts = db.alerts().all(), staking = db.staking().all(), evmNetworks = db.evm().networks(), evmTokens = db.evm().allTokens(),
        snapshots = db.snapshots().all(), goals = db.goals().all(), settings = settings.exportMap(), costBasis = db.chain().costBasis(),
    )

    /** Backup v1 (só dados, em memória). Mantido para compatibilidade; o app grava o completo ([exportFull]). */
    suspend fun export(password: CharArray?): ByteArray = BackupCodec.encode(snapshot(), password)

    // ---------------- exportação completa ----------------

    suspend fun exportFull(out: OutputStream, password: CharArray) = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(context)
        val s = settings.current()
        val media = LinkedHashMap<MediaEntry, File>()
        for (it in s.roll.items) {
            if (it.kind == RollKind.VIDEO) continue
            val f = BannerLibrary.file(ctx, it)
            if (f.isFile) media[MediaEntry(MediaKind.ROLL.name, it.file, f.length(), it.kind.name, it.id, it.addedAt)] = f
        }
        File(ctx.filesDir, "hd").listFiles()?.filter { it.isFile && (it.name.endsWith(".hd") || it.name.endsWith(".hd.meta")) }?.forEach { f ->
            media[MediaEntry(MediaKind.HD.name, f.name, f.length(), hdOf = f.name.removeSuffix(".meta").removeSuffix(".hd"))] = f
        }
        val bannerPath = s.bannerUri.substringBefore("?v=")
        File(ctx.filesDir, "banner").takeIf { it.isFile && bannerPath == it.absolutePath }?.let { media[MediaEntry(MediaKind.BANNER.name, "banner", it.length())] = it }
        VideoImport.bannerFile(ctx).takeIf { it.isFile && bannerPath == it.absolutePath }?.let { media[MediaEntry(MediaKind.BANNER_VIDEO.name, "banner.mp4", it.length())] = it }
        val data = FullBackupData(
            base = snapshot(),
            chainHoldings = db.chain().holdings(), chainActivity = db.chain().allActivity(), historyStates = db.chain().historyStates(),
            valueSnapshots = db.chain().snapshots(0), cache = BackupMerge.CACHE_KEYS.mapNotNull { db.marketCache().get(it) },
            devConfig = dev?.exportJson(), taxRules = tax?.exportJson(),
            roll = RollStateDto(s.roll.enabled, s.roll.gifsOn, s.roll.photosOn, s.roll.selected, s.roll.items.map { it.id }),
            media = media.keys.toList(),
        )
        FullBackup.write(out, password, data, media, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    // ---------------- restauração ----------------

    private fun staging(): File = File(requireNotNull(context).filesDir, "restore-staging")

    /** Lê e confere o arquivo inteiro (senha, integridade, versão) sem aplicar nada. */
    suspend fun read(input: InputStream, password: CharArray?): ExtractedBackup = withContext(Dispatchers.IO) {
        val bin = input.buffered(1 shl 16)
        bin.mark(16)
        val head = ByteArray(8); val n = bin.read(head).coerceAtLeast(0); bin.reset()
        if (n >= 5 && StreamCipher.isV2(head)) {
            requireNotNull(password) { "Este backup é criptografado: informe a senha." }
            val dir = staging()
            val free = (dir.parentFile ?: dir).usableSpace
            FullBackup.extract(bin, password, dir, BuildConfig.VERSION_CODE, maxTotalBytes = (free - 64L * 1024 * 1024).coerceAtLeast(0))
        } else {
            val bytes = com.coinportracker.core.BoundedRead.bytes(bin, com.coinportracker.core.BoundedRead.BACKUP)
            ExtractedBackup(null, FullBackupData(BackupCodec.decode(bytes, password)), null, emptyMap())
        }
    }

    suspend fun preview(x: ExtractedBackup): RestorePreview {
        val known = BackupMerge.matchWallets(db.wallets().all(), x.data.base.wallets)
        val b = x.data.base
        return RestorePreview(x.manifest?.createdAt ?: b.createdAt, x.manifest?.appVersion ?: b.appVersion, x.manifest != null,
            b.wallets.size - known.size, known.size, x.data.chainActivity.size, b.transactions.size, b.alerts.size, b.goals.size,
            x.data.media.count { it.kind == MediaKind.ROLL.name || it.kind == MediaKind.BANNER.name || it.kind == MediaKind.BANNER_VIDEO.name },
            b.settings.isNotEmpty(), x.data.devConfig != null, x.data.taxRules != null)
    }

    /** Aplica sem apagar nada. Se o banco falhar no meio, nada do banco muda (transação única). */
    suspend fun apply(x: ExtractedBackup): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val d = x.data; val p = d.base
            saveUndo()
            var walletsAdded = 0; var rows = 0
            db.withTransaction {
                // carteiras: as que já existem são reconhecidas; as novas ganham ID novo (e tudo que aponta para elas é remapeado)
                val wmap = HashMap(BackupMerge.matchWallets(db.wallets().all(), p.wallets))
                for (w in p.wallets) if (w.id !in wmap) { wmap[w.id] = db.wallets().insert(w.copy(id = 0, lastSyncError = null)); walletsAdded++ }
                val catMap = HashMap<Long, Long>()
                val cats = db.categories().all().associateBy { BackupMerge.categoryKey(it) }
                for (c in p.categories) catMap[c.id] = cats[BackupMerge.categoryKey(c)]?.id ?: db.categories().insert(c.copy(id = 0))
                val newAssets = BackupMerge.absent(db.assets().all(), p.assets) { it.id }
                db.assets().upsertAll(newAssets); rows += newAssets.size
                val netMap = HashMap<Long, Long>()
                val nets = db.evm().networks().associateBy { BackupMerge.networkKey(it) }
                for (n in p.evmNetworks) netMap[n.id] = nets[BackupMerge.networkKey(n)]?.id ?: db.evm().insertNetwork(n.copy(id = 0))
                val tokens = BackupMerge.absent(db.evm().allTokens(), p.evmTokens.mapNotNull { t -> netMap[t.networkId]?.let { t.copy(id = 0, networkId = it) } }) { BackupMerge.tokenKey(it) }
                tokens.forEach { db.evm().insertToken(it) }; rows += tokens.size
                // staking (manual) e operações
                val stakeMap = HashMap<Long, Long>()
                val stakes = db.staking().all().associateBy { BackupMerge.stakingKey(it) }
                for (s in p.staking) stakeMap[s.id] = stakes[BackupMerge.stakingKey(s)]?.id ?: db.staking().insert(s.copy(id = 0, walletId = BackupMerge.remapWallet(s.walletId, wmap))).also { rows++ }
                val txKeys = db.transactions().all().mapTo(HashSet()) { BackupMerge.txKey(it) }
                val txMap = HashMap<Long, Long>()
                for (t in p.transactions) {
                    if (!txKeys.add(BackupMerge.txKey(t))) continue
                    txMap[t.id] = db.transactions().insert(t.copy(id = 0, fromWalletId = BackupMerge.remapWallet(t.fromWalletId, wmap), toWalletId = BackupMerge.remapWallet(t.toWalletId, wmap),
                        categoryId = t.categoryId?.let { catMap[it] }, stakingPositionId = t.stakingPositionId?.let { stakeMap[it] }))
                    rows++
                }
                for (r in p.revisions) txMap[r.transactionId]?.let { db.revisions().insert(r.copy(id = 0, transactionId = it)) }
                // tabelas com chave natural: só o que falta
                db.priceHistory().upsertAll(BackupMerge.absent(db.priceHistory().all(), p.priceHistory) { "${it.assetId}|${it.currency}|${it.epochDay}" }.also { rows += it.size })
                db.fx().upsertAll(BackupMerge.absent(db.fx().all(), p.fxRates) { it.epochDay }.also { rows += it.size })
                db.watchlist().upsertAll(BackupMerge.absent(db.watchlist().all(), p.watchlist) { it.coingeckoId }.also { rows += it.size })
                db.snapshots().upsertAll(BackupMerge.absent(db.snapshots().all(), p.snapshots) { it.epochDay }.also { rows += it.size })
                db.chain().upsertCostBasisAll(BackupMerge.absent(db.chain().costBasis(), p.costBasis) { it.groupKey }.also { rows += it.size })
                val alerts = db.alerts().all().mapTo(HashSet()) { BackupMerge.alertKey(it) }
                for (a in p.alerts) if (alerts.add(BackupMerge.alertKey(a))) { db.alerts().insert(a.copy(id = 0, lastTriggeredAt = null)); rows++ }
                val goals = db.goals().all().mapTo(HashSet()) { BackupMerge.goalKey(it) }
                for (g in p.goals) if (goals.add(BackupMerge.goalKey(g))) { db.goals().insert(g.copy(id = 0)); rows++ }
                // on-chain: saldos, histórico, estado da leitura e fotos do patrimônio
                db.chain().upsertHoldings(BackupMerge.absent(db.chain().holdings(), BackupMerge.remapHoldings(d.chainHoldings, wmap)) { "${it.walletId}|${it.network}|${it.tokenKey}" }.also { rows += it.size })
                val act = BackupMerge.absent(db.chain().allActivity(), BackupMerge.remapActivity(d.chainActivity, wmap)) { "${it.walletId}|${it.network}|${it.hash}" }
                act.chunked(500).forEach { db.chain().upsertActivity(it) }; rows += act.size
                val curStates = db.chain().historyStates()
                BackupMerge.absent(curStates, BackupMerge.remapStates(d.historyStates, wmap)) { "${it.walletId}|${it.network}" }.forEach { db.chain().upsertHistoryState(it); rows++ }
                val snaps = BackupMerge.absent(db.chain().snapshots(0), d.valueSnapshots) { it.t }
                snaps.forEach { db.chain().upsertSnapshot(it) }; rows += snaps.size
                for (c in d.cache) if (c.key in BackupMerge.CACHE_KEYS && db.marketCache().get(c.key) == null)
                    db.marketCache().upsert(c.copy(json = BackupMerge.remapCacheJson(c.json, wmap)))
            }
            // ajustes, regras fiscais e Dev (os anteriores ficaram guardados para desfazer)
            if (p.settings.isNotEmpty()) settings.importMap(p.settings)
            d.taxRules?.let { t -> runCatching { tax?.importJson(t) } }
            d.devConfig?.let { j -> runCatching { dev?.importJson(j) } }
            val media = applyMedia(x)
            RestoreResult(walletsAdded, rows, media.added, media.dup, media.skipped, media.bannerKept)
        } finally { x.discard() }
    }

    private data class MediaOutcome(val added: Int, val dup: Int, val skipped: Int, val bannerKept: Boolean)

    private suspend fun applyMedia(x: ExtractedBackup): MediaOutcome {
        val ctx = context ?: return MediaOutcome(0, 0, 0, false)
        val d = x.data
        if (d.media.isEmpty() && d.roll == null) return MediaOutcome(0, 0, 0, false)
        var added = 0; var dup = 0; var skipped = 0; var bannerKept = false
        val idMap = HashMap<String, String>()       // id do item no backup → id no aparelho
        val fileMap = HashMap<String, String>()     // arquivo no backup → arquivo no aparelho
        val existing = settings.current().roll.items
        val order = d.roll?.order.orEmpty()
        val rolls = d.media.filter { it.kind == MediaKind.ROLL.name }.sortedBy { e -> order.indexOf(e.rollId).let { if (it < 0) Int.MAX_VALUE else it } }
        for (e in rolls) {
            val src = x.file(e) ?: continue
            val kind = RollKind.entries.firstOrNull { it.name == e.rollKind }?.takeIf { it != RollKind.VIDEO } ?: continue
            // mesma mídia já está no app: reaproveita (compara tamanho e SHA-256)
            val same = existing.firstOrNull { it.kind == kind && BannerLibrary.file(ctx, it).let { f -> f.length() == src.length() && sha(f) == sha(src) } }
            if (same != null) { idMap[e.rollId ?: ""] = same.id; fileMap[e.name] = same.file; dup++; continue }
            if (RollRules.canAdd(settings.current().roll, kind) != null) { skipped++; continue }
            val id = BannerLibrary.newId(kind); val dst = File(BannerLibrary.dir(ctx), id + BannerLibrary.ext(kind))
            src.copyTo(dst, overwrite = false)
            val item = RollItem(id, kind, dst.name, dst.length(), e.addedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            if (BannerLibrary.addItem(settings, item)) { idMap[e.rollId ?: ""] = id; fileMap[e.name] = dst.name; added++ } else { dst.delete(); skipped++ }
        }
        // banner único: só entra se o app ainda usa o banner padrão (o seu banner atual nunca é trocado)
        val cur = settings.current()
        val bannerE = d.media.firstOrNull { it.kind == MediaKind.BANNER.name } ?: d.media.firstOrNull { it.kind == MediaKind.BANNER_VIDEO.name }
        if (bannerE != null) x.file(bannerE)?.let { src ->
            if (cur.bannerUri.isBlank()) {
                val dst = if (bannerE.kind == MediaKind.BANNER.name) File(ctx.filesDir, "banner") else VideoImport.bannerFile(ctx)
                src.copyTo(dst, overwrite = true); fileMap[bannerE.name] = dst.name; added++
                settings.update { it.copy(bannerUri = dst.absolutePath + "?v=" + System.currentTimeMillis()) }
            } else bannerKept = true
        }
        // cópias HD (depois dos originais, para ficarem mais novas que eles)
        val hdDir = File(ctx.filesDir, "hd").apply { mkdirs() }
        for (e in d.media.filter { it.kind == MediaKind.HD.name }) {
            val src = x.file(e) ?: continue
            val of = e.hdOf ?: continue
            val target = when {
                of.startsWith("asset_") -> of
                fileMap[of] != null -> fileMap[of]!!
                else -> null
            } ?: continue
            val dst = File(hdDir, target + e.name.removePrefix(of))
            if (!FullBackup.validName(dst.name) || dst.exists()) continue
            src.copyTo(dst)
        }
        MediaHd.bump()
        // estado da Rolagem: ligada/tipos e a mídia escolhida (pelo novo id)
        d.roll?.let { r ->
            settings.update { s -> s.copy(roll = s.roll.copy(enabled = s.roll.enabled || r.enabled && s.roll.items.isNotEmpty(), gifsOn = r.gifsOn, photosOn = r.photosOn,
                selected = idMap[r.selected] ?: s.roll.selected)) }
        }
        return MediaOutcome(added, dup, skipped, bannerKept)
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------- desfazer ajustes ----------------

    private suspend fun saveUndo() {
        val s = settings.current()
        val u = UndoSnapshot(System.currentTimeMillis(), settings.exportMap(), RollStateDto(s.roll.enabled, s.roll.gifsOn, s.roll.photosOn, s.roll.selected, emptyList()),
            dev?.exportJson(), runCatching { tax?.exportJson() }.getOrNull())
        db.marketCache().upsert(MarketCacheEntity(UNDO_KEY, json.encodeToString(UndoSnapshot.serializer(), u), "restore", u.at))
    }

    /** Quando a última restauração aconteceu (null = nada para desfazer). */
    suspend fun undoAvailableAt(): Long? = db.marketCache().get(UNDO_KEY)?.fetchedAt

    /** Volta aos ajustes de antes da restauração (as carteiras e dados que entraram continuam). */
    suspend fun undoSettings(): Boolean {
        val c = db.marketCache().get(UNDO_KEY) ?: return false
        val u = runCatching { json.decodeFromString(UndoSnapshot.serializer(), c.json) }.getOrNull() ?: return false
        settings.importMap(u.settings)
        u.roll?.let { r -> settings.update { s -> s.copy(roll = s.roll.copy(enabled = r.enabled, gifsOn = r.gifsOn, photosOn = r.photosOn, selected = r.selected.takeIf { id -> s.roll.items.any { it.id == id } } ?: s.roll.selected)) } }
        u.dev?.let { runCatching { dev?.importJson(it) } }
        u.tax?.let { runCatching { tax?.importJson(it) } }
        db.marketCache().delete(UNDO_KEY)
        return true
    }

    companion object { const val UNDO_KEY = "restore:undo:v1" }
}
