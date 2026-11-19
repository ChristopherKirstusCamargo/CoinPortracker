package com.coinportracker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coinportracker.CoinPortrackerApp
import com.coinportracker.core.Outcome
import com.coinportracker.database.AssetEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.Currency
import com.coinportracker.market.AssetSearchResult
import com.coinportracker.market.MarketCoin
import com.coinportracker.settings.AppSettings
import com.coinportracker.backup.BackupCodec
import com.coinportracker.transactions.CsvCodec
import com.coinportracker.transactions.JsonCodec
import com.coinportracker.data.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import com.coinportracker.data.HubState
import com.coinportracker.data.ChartRange
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@androidx.compose.runtime.Immutable
data class ChartData(val range: ChartRange, val points: List<com.coinportracker.onchain.PricePoint>, val result: com.coinportracker.history.HistoryResult? = null, val source: String = "")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app), UiModel {
    val c = (app as CoinPortrackerApp).container
    val state = c.portfolio.state
    val refresh = c.market.status
    val wallets: StateFlow<List<WalletEntity>> = c.db.wallets().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val latestTx = c.db.transactions().observeLatest(6).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val watchlist = c.db.watchlist().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Portfólio combinado (on-chain + manual) já calculado fora da thread principal. */
    override val hub: StateFlow<HubState> = c.hub.state
    override val sync = c.chain.status
    override val activity = c.chain.activity(400).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override val stakeSignatures = c.history.stakeSignatures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    override val allActivity = c.chain.activity(100_000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override val allTx = c.db.transactions().observeLatest(400).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override val alerts = c.db.alerts().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var lastBalanceSync = 0L
    private var lastHistorySync = 0L

    private val foreground = MutableStateFlow(false)
    fun setForeground(v: Boolean) { foreground.value = v }

    init {
        // restos de uma leitura de backup interrompida (o app foi fechado no meio)
        viewModelScope.launch(Dispatchers.IO) { runCatching { File(getApplication<Application>().filesDir, "restore-staging").deleteRecursively() }; refreshUndo() }
        viewModelScope.launch(Dispatchers.Default) {
            com.coinportracker.core.catchingNonCancel { c.history.warmStart() }
            c.chain.migrateLegacyWallets()
            com.coinportracker.core.catchingNonCancel { c.chain.dedupeWallets() }
            fullSync(manual = false)
        }
        viewModelScope.launch {
            val plans = combine(foreground, c.settings.settings.map { Triple(it.foregroundRefreshSec, it.liveBalance, it.lowPower) }.distinctUntilChanged()) { fg, (sec, live, low) ->
                if (low && fg) RefreshPlan(5 * 60_000L, false) else RefreshScheduler.plan(fg, sec, live)
            }
            RefreshScheduler.run(plans) { p -> tick(p.live) }
        }
        // v5.1.0: o banner atual entra como 1º item da Rolagem (uma vez) e a biblioteca é conferida
        viewModelScope.launch { com.coinportracker.media.BannerLibrary.migrateAndTidy(getApplication<Application>(), c.settings) }
        // cópias em alta resolução do banner (opcional, em segundo plano)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // v5.1.3: a fonte é a mesma da Início (mídia escolhida na Rolagem, ou o banner único); trocar a escolha agenda a nova
            c.settings.settings.map { Triple(it.upscaleMedia, com.coinportracker.media.BannerLibrary.source(ctx, it), it.lowPower) }.distinctUntilChanged().collect { (on, src, low) ->
                withContext(Dispatchers.IO) {
                    if (!on) com.coinportracker.media.MediaHd.disableAll(ctx) // desligado: apaga as cópias HD e volta ao original
                    else if (!low && !com.coinportracker.media.VideoFormat.isVideoPath(src)) com.coinportracker.media.MediaHd.schedule(ctx, src)
                }
                // baixo consumo: não agenda novo upscale; não apaga a cópia HD já pronta
            }
        }
        viewModelScope.launch {
            com.coinportracker.media.VideoImport.events.collect { ev ->
                when (ev.state) {
                    com.coinportracker.media.VideoJobState.DONE -> if (ev.dest == com.coinportracker.media.VideoDest.BANNER && ev.path != null) {
                        c.settings.update { it.copy(bannerUri = ev.path + "?v=" + System.currentTimeMillis()) }
                    }
                    com.coinportracker.media.VideoJobState.FAILED -> ev.error?.let { toast(it) }
                    else -> {}
                }
            }
        }
    }

    /** Um ciclo automático: preços sempre; saldos on-chain a cada 5 min. */
    private suspend fun tick(live: Boolean) {
        if (System.currentTimeMillis() - lastBalanceSync > 5 * 60_000L && !_refreshing.value) { fullSync(false); return }
        c.chain.refreshPrices(fast = live)
    }

    override val activityStatus get() = c.history.status

    override fun refreshActivity(force: Boolean) {
        if (historyJob?.isActive == true) return
        val last = c.history.status.value.lastAt ?: 0L
        if (!force && System.currentTimeMillis() - last < 2 * 60_000L) return
        historyJob = viewModelScope.launch(Dispatchers.Default) {
            com.coinportracker.core.catchingNonCancel { c.history.sync(c.db.wallets().all().filter { !it.archived && c.chain.isWatch(it) }) }
            refreshEstimates()
        }
    }

    /** Puxar para atualizar: saldos de todas as redes + preços + câmbio + histórico (automático: histórico a cada 30 min). */
    override fun fullSync(manual: Boolean) { viewModelScope.launch {
        if (manual) _refreshing.value = true
        val now = System.currentTimeMillis()
        lastBalanceSync = now
        // v6.0: puxar para atualizar também relê o histórico (antes só a cada 30 min: a Atividade parecia não atualizar)
        val withHistory = manual || now - lastHistorySync > 30 * 60_000L
        try {
            val r = c.chain.syncAll(withHistory)
            if (withHistory && r is Outcome.Ok) lastHistorySync = now
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        finally { if (manual) _refreshing.value = false }
        if (manual) c.chain.status.value.error?.let { toast(it) }
        // histórico real + recompensas + preço médio estimado: em segundo plano, sem segurar a tela
        if (withHistory && historyJob?.isActive != true) historyJob = viewModelScope.launch(Dispatchers.Default) {
            com.coinportracker.core.catchingNonCancel { c.history.sync(c.db.wallets().all().filter { !it.archived && c.chain.isWatch(it) }) }
            refreshEstimates()
        } else if (c.history.estimates.value.isEmpty()) viewModelScope.launch(Dispatchers.Default) { refreshEstimates() }
        val h = hub.value
        if (h.ready) runCatching { c.history.snapshot(h.agg.totalUsd, h.agg.unpricedCount > 0) }
    } }

    private var historyJob: kotlinx.coroutines.Job? = null
    private suspend fun refreshEstimates() {
        val h = hub.first { it.ready }
        val ps = c.portfolio.state.value
        runCatching { c.history.refreshEstimates(h, emptyList(), ps.assets) }
    }


    // Gráfico do portfólio
    private val _range = MutableStateFlow(ChartRange.M1)
    override val range = _range.asStateFlow()
    override fun setRange(r: ChartRange) { _range.value = r }
    /** Valor REAL do portfólio no tempo (v3): reconstruído do histórico on-chain, não "saldo atual × preço antigo". */
    override val chart: StateFlow<ChartData?> = combine(_range,
        hub.filter { it.ready }.map { h -> listOf(h.agg.assets.map { it.key to it.amount.toDouble() }, h.currency, h.settings.investedCurrency, h.settings.invested != null, h.fx != null) }.distinctUntilChanged(),
        c.history.version) { r, _, _ -> r }
        .mapLatest { r ->
            val ps = c.portfolio.state.value
            runCatching { c.history.chart(r, hub.value, emptyList(), ps.assets) }.getOrNull()
                ?.let { ChartData(r, it.points, it.result, it.source) } ?: ChartData(r, emptyList())
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun assetChart(range: ChartRange, key: String, onResult: (List<com.coinportracker.onchain.PricePoint>) -> Unit) { viewModelScope.launch { onResult(c.chain.assetChart(range, key)) } }

    // Carteiras on-chain
    override fun addWatchWallet(address: String, name: String, kind: com.coinportracker.onchain.AddressKind?, onDone: (Boolean) -> Unit) { viewModelScope.launch {
        when (val r = c.chain.addWallet(address, name, kind)) {
            is Outcome.Ok -> { onDone(true); toast("Carteira adicionada. Buscando saldos em todas as redes…"); c.chain.syncAll(withHistory = true, onlyWallet = r.value) }
            is Outcome.Err -> { toast(r.message); onDone(false) }
        }
    } }
    override fun renameWallet(id: Long, name: String) { viewModelScope.launch { c.chain.renameWallet(id, name) } }
    override fun removeWallet(id: Long) { viewModelScope.launch { c.chain.removeWallet(id); c.history.clearWallet(id); toast("Carteira removida do app (nada muda na blockchain).") } }
    override fun syncWallet(id: Long) { viewModelScope.launch { c.chain.syncAll(withHistory = true, onlyWallet = id) } }
    override fun validateAddress(a: String) = c.chain.validate(a)

    // Preço médio / tokens ocultos
    override fun setCostBasis(groupKey: String, avg: java.math.BigDecimal?, cur: Currency) { viewModelScope.launch { c.chain.setCostBasis(groupKey, avg, cur); toast(if (avg == null) "Preço médio removido." else "Preço médio salvo. O lucro/prejuízo foi recalculado.") } }
    override fun hideToken(key: String, hide: Boolean) = updateSettings { it.copy(hiddenTokens = if (hide) it.hiddenTokens + key else it.hiddenTokens - key) }

    /** Garante um ativo no livro de operações para um ativo on-chain (para registrar compras/vendas e definir o custo). */
    override fun ensureLedgerAsset(groupKey: String, symbol: String, name: String, iconChain: String?, onDone: (String) -> Unit) { viewModelScope.launch {
        hub.value.ledgerAsset[groupKey]?.let { onDone(it); return@launch }
        val base = com.coinportracker.onchain.Aggregator.costAlias(groupKey) ?: groupKey
        val cg = base.takeIf { it.startsWith("cg:") }?.removePrefix("cg:")
        val id = cg ?: ("custom:" + UUID.randomUUID().toString().take(8))
        if (c.db.assets().get(id) == null) c.db.assets().upsert(AssetEntity(id, symbol.uppercase(), name, iconChain ?: "OTHER", coingeckoId = cg, isCustom = cg == null, createdAt = System.currentTimeMillis()))
        onDone(id)
    } }

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    override fun toast(m: String?) { _message.value = m }

    private val _refreshing = MutableStateFlow(false)
    override val refreshing = _refreshing.asStateFlow()

    /** Pull-to-refresh / automático: uma chamada em lote; falha mantém cache e informa. */
    fun refreshPrices(manual: Boolean) = viewModelScope.launch {
        if (_refreshing.value) return@launch
        if (manual) _refreshing.value = true
        val r = c.market.refreshQuotes(includeSupply = manual)
        if (r is Outcome.Ok) c.afterRefresh()
        if (manual) {
            if (r is Outcome.Ok) c.market.refreshAllHistory() // histórico incremental (cache)
            _refreshing.value = false
            if (r is Outcome.Err) toast(r.message)
        }
    }

    override fun updateSettings(f: (AppSettings) -> AppSettings) { viewModelScope.launch { c.settings.update(f) } }

    // Ativos
    private val _search = MutableStateFlow<List<AssetSearchResult>>(emptyList())
    val search = _search.asStateFlow()
    fun searchAssets(q: String) = viewModelScope.launch {
        if (q.length < 2) { _search.value = emptyList(); return@launch }
        when (val r = c.market.search(q)) { is Outcome.Ok -> _search.value = r.value; is Outcome.Err -> toast("Busca indisponível: ${r.message}") }
    }

    fun addAssetFromSearch(r: AssetSearchResult, stable: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        val id = r.providerId
        if (c.db.assets().get(id) == null) c.db.assets().upsert(AssetEntity(id, r.symbol, r.name, chainGuess(r.providerId), coingeckoId = id, isStablecoin = stable, createdAt = System.currentTimeMillis()))
        // tenta vincular ao CoinPaprika (fonte alternativa) pelo mesmo símbolo + nome
        (c.market.searchPaprika(r.symbol) as? Outcome.Ok)?.value?.firstOrNull { it.symbol.equals(r.symbol, true) && it.name.equals(r.name, true) }?.let { p ->
            c.db.assets().get(id)?.let { c.db.assets().upsert(it.copy(paprikaId = p.providerId)) }
        }
        refreshPrices(false); onDone(id)
    }

    fun addCustomAsset(symbol: String, name: String, chain: String, priceBrl: String?, priceUsd: String?, stable: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        val id = "custom:" + UUID.randomUUID().toString().take(8)
        c.db.assets().upsert(AssetEntity(id, symbol.uppercase().trim(), name.trim().ifEmpty { symbol }, chain, isCustom = true, isStablecoin = stable,
            manualPriceBrl = priceBrl, manualPriceUsd = priceUsd, manualPriceAt = if (priceBrl != null || priceUsd != null) System.currentTimeMillis() else null, createdAt = System.currentTimeMillis()))
        onDone(id)
    }

    fun saveAsset(a: AssetEntity) = viewModelScope.launch { c.db.assets().upsert(a) }

    private fun chainGuess(id: String) = when (id) { "bitcoin" -> "BITCOIN"; "solana" -> "SOLANA"; "ethereum" -> "ETHEREUM"; "binancecoin" -> "BSC"; "matic-network", "polygon-ecosystem-token" -> "POLYGON"; else -> "OTHER" }

    override fun addWallet(name: String, type: String) { viewModelScope.launch { c.db.wallets().insert(WalletEntity(name = name, type = type, createdAt = System.currentTimeMillis())) } }

    // Transações
    fun saveTx(t: TransactionEntity, reason: String?, onDone: () -> Unit) = viewModelScope.launch {
        try { c.transactions.save(t, reason); onDone() } catch (e: IllegalArgumentException) { toast(e.message) }
    }
    fun deleteTx(id: Long, reason: String?) = viewModelScope.launch { c.transactions.delete(id, reason); toast("Operação excluída (pode ser restaurada no histórico de alterações).") }
    fun restoreTx(id: Long) = viewModelScope.launch { c.transactions.restore(id) }

    // Importação
    private val _preview = MutableStateFlow<List<TransactionRepository.PreviewRow>?>(null)
    val preview = _preview.asStateFlow()
    private var previewSource = "IMPORT_CSV"
    fun loadImport(uri: Uri) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { com.coinportracker.core.BoundedRead.text(it, com.coinportracker.core.BoundedRead.IMPORT_CSV) } }
            val rows = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) { previewSource = "IMPORT_JSON"; JsonCodec.parse(text) } else { previewSource = "IMPORT_CSV"; CsvCodec.parse(text) }
            _preview.value = c.transactions.preview(rows)
        } catch (e: Exception) { toast("Falha ao ler arquivo: ${e.message}") }
    }
    fun editPreviewRow(index: Int, raw: Map<String, String>) = viewModelScope.launch {
        val cur = _preview.value ?: return@launch
        val row = CsvCodec.rowFromMap(cur[index].parsed.line, raw)
        val rows = cur.map { it.parsed }.toMutableList().also { it[index] = row }
        _preview.value = c.transactions.preview(rows)
    }
    fun confirmImport(selected: Set<Int>) = viewModelScope.launch {
        val p = _preview.value ?: return@launch
        val recs = p.filterIndexed { i, r -> i in selected && r.importable }.mapNotNull { it.parsed.record }
        val n = c.transactions.import(recs, previewSource)
        _preview.value = null; toast((if (n == 1) "1 operação importada" else "$n operações importadas") + ". Nenhuma operação existente foi alterada.")
    }
    fun cancelImport() { _preview.value = null }

    // Exportação / backup
    fun writeBytes(uri: Uri, bytes: suspend () -> ByteArray, ok: String) = viewModelScope.launch {
        try {
            val b = bytes()
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(b) } }
            toast(ok)
        } catch (e: Exception) { toast("Falha ao exportar: ${e.message}") }
    }

    // v6.0 — backup completo (fluxo cifrado, com mídias) e restauração sem apagar nada
    private val _backupFlow = MutableStateFlow<com.coinportracker.backup.BackupFlow>(com.coinportracker.backup.BackupFlow.Idle)
    val backupFlow: StateFlow<com.coinportracker.backup.BackupFlow> = _backupFlow.asStateFlow()
    private val _undoAt = MutableStateFlow<Long?>(null)
    val restoreUndoAt: StateFlow<Long?> = _undoAt.asStateFlow()
    private var pendingRestore: com.coinportracker.backup.ExtractedBackup? = null
    fun refreshUndo() = viewModelScope.launch { _undoAt.value = runCatching { c.backup.undoAvailableAt() }.getOrNull() }

    fun exportFullBackup(uri: Uri, password: CharArray) = viewModelScope.launch {
        if (_backupFlow.value is com.coinportracker.backup.BackupFlow.Busy) return@launch
        _backupFlow.value = com.coinportracker.backup.BackupFlow.Busy("Criando backup… (fotos e GIFs incluídos)")
        try {
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { c.backup.exportFull(it, password) } }
            _backupFlow.value = com.coinportracker.backup.BackupFlow.Idle; toast("Backup completo salvo.")
        } catch (e: Exception) { _backupFlow.value = com.coinportracker.backup.BackupFlow.Idle; toast("Falha ao criar o backup: ${e.message}") }
        finally { password.fill(' ') }
    }

    fun inspectBackup(uri: Uri, password: String?) = viewModelScope.launch {
        if (_backupFlow.value is com.coinportracker.backup.BackupFlow.Busy) return@launch
        cancelRestore()
        _backupFlow.value = com.coinportracker.backup.BackupFlow.Busy("Conferindo o backup…")
        try {
            val x = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { c.backup.read(it, password?.toCharArray()) } }
            pendingRestore = x
            _backupFlow.value = com.coinportracker.backup.BackupFlow.Review(c.backup.preview(x))
        } catch (e: Exception) { _backupFlow.value = com.coinportracker.backup.BackupFlow.Idle; toast("Restauração cancelada: ${e.message}") }
    }

    fun applyRestore() = viewModelScope.launch {
        val x = pendingRestore ?: return@launch
        pendingRestore = null
        _backupFlow.value = com.coinportracker.backup.BackupFlow.Busy("Restaurando… nada do que já está no app será apagado.")
        try {
            val r = c.backup.apply(x)
            _backupFlow.value = com.coinportracker.backup.BackupFlow.Done(com.coinportracker.backup.RestoreText.done(r))
            refreshUndo(); fullSync(manual = false)
        } catch (e: Exception) { _backupFlow.value = com.coinportracker.backup.BackupFlow.Idle; toast("Restauração interrompida: ${e.message}. Nenhum dado existente foi apagado.") }
    }

    fun cancelRestore() { pendingRestore?.discard(); pendingRestore = null; if (_backupFlow.value !is com.coinportracker.backup.BackupFlow.Busy) _backupFlow.value = com.coinportracker.backup.BackupFlow.Idle }
    fun dismissBackupFlow() { if (_backupFlow.value !is com.coinportracker.backup.BackupFlow.Busy) _backupFlow.value = com.coinportracker.backup.BackupFlow.Idle }

    fun undoRestoreSettings() = viewModelScope.launch {
        val ok = runCatching { c.backup.undoSettings() }.getOrDefault(false)
        refreshUndo(); toast(if (ok) "Ajustes de antes da restauração de volta. Os dados restaurados continuam." else "Nada para desfazer.")
    }

    // Banner (copiado para o armazenamento privado do app: sem permissões persistentes)
    override fun setBanner(uri: Uri?) { viewModelScope.launch {
        val ctx = getApplication<Application>()
        if (uri == null) {
            File(ctx.filesDir, "banner").delete()
            com.coinportracker.media.VideoImport.clearBanner(ctx)
            c.settings.update { it.copy(bannerUri = "") }
            return@launch
        }
        try {
            val f = File(ctx.filesDir, "banner")
            withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
            com.coinportracker.media.VideoImport.clearBanner(ctx)
            c.settings.update { it.copy(bannerUri = f.absolutePath + "?v=" + System.currentTimeMillis()) }
        } catch (e: Exception) { toast("Não foi possível usar a imagem: ${e.message}") }
    } }

    // Mercado
    private val _markets = MutableStateFlow<Pair<List<MarketCoin>, String?>?>(null)
    val markets = _markets.asStateFlow()
    fun loadMarkets(cur: Currency, force: Boolean, silent: Boolean = false) = viewModelScope.launch {
        if (force) _refreshing.value = true
        try {
            val (l, e) = c.market.markets(cur, force, maxAgeMs = if (silent) 55_000L else 5 * 60_000L); _markets.value = l to e?.let { "${it.source} · ${com.coinportracker.core.Fmt.ago(it.fetchedAt)}" }
            // lista de RWA tokenizados (1×/dia), depois de mostrar a tela; vale a partir da próxima atualização
            launch { com.coinportracker.core.catchingNonCancel { c.market.refreshTokenizedExclusions() } }
        }
        catch (e: Exception) { if (!silent) toast("Mercado indisponível: ${e.message}") }
        finally { if (force) _refreshing.value = false }
    }
    fun toggleWatch(coin: MarketCoin) = viewModelScope.launch {
        if (c.db.watchlist().all().any { it.coingeckoId == coin.id }) c.db.watchlist().delete(coin.id)
        else c.db.watchlist().upsert(com.coinportracker.database.WatchlistEntity(coin.id, coin.symbol, coin.name, addedAt = System.currentTimeMillis()))
    }

    override fun saveAlert(a: com.coinportracker.database.AlertEntity) {
        viewModelScope.launch {
            if (a.id == 0L) c.db.alerts().insert(a) else c.db.alerts().update(a)
            c.settings.update { it.copy(alertsEnabled = true) }
        }
    }
    override fun deleteAlert(a: com.coinportracker.database.AlertEntity) {
        viewModelScope.launch { c.db.alerts().delete(a) }
    }
}
