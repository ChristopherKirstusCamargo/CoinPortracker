package com.coinportracker.data

import com.coinportracker.blockchain.AddressValidator
import com.coinportracker.core.Outcome
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.ChainActivityEntity
import com.coinportracker.database.ChainHoldingEntity
import com.coinportracker.database.LivePriceEntity
import com.coinportracker.database.MarketCacheEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.Chain
import com.coinportracker.domain.WalletType
import com.coinportracker.onchain.AddressKind
import com.coinportracker.onchain.Aggregator
import com.coinportracker.onchain.BitcoinScanner
import com.coinportracker.onchain.ChainActivity
import com.coinportracker.onchain.ChainFamily
import com.coinportracker.onchain.EvmScanner
import com.coinportracker.onchain.HoldingKind
import com.coinportracker.onchain.LivePrice
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.PricePoint
import com.coinportracker.onchain.PriceService
import com.coinportracker.onchain.ScanResult
import com.coinportracker.onchain.SolanaScanner
import com.coinportracker.onchain.TokenHolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.math.BigDecimal

data class SyncStatus(
    val syncing: Boolean = false,
    val pricing: Boolean = false,
    val lastBalanceSync: Long? = null,
    val lastPriceSync: Long? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/** Intervalos do gráfico do portfólio (estilo CoinStats). */
enum class ChartRange(val label: String, val seconds: Long, val span: Int, val period: String, val ttlMs: Long) {
    D1("1D", 86_400, 48, "30m", 5 * 60_000L),
    W1("1S", 7 * 86_400, 56, "3h", 30 * 60_000L),
    M1("1M", 30 * 86_400, 60, "12h", 3 * 3_600_000L),
    Y1("1A", 365 * 86_400, 73, "5d", 12 * 3_600_000L),
    ALL("Tudo", 5 * 365 * 86_400L, 61, "30d", 24 * 3_600_000L),
}

/**
 * Portfólio automático pela blockchain: o usuário só informa o endereço público; o app descobre e
 * atualiza sozinho os saldos de todas as redes suportadas, preços, staking e histórico.
 */
class ChainPortfolioRepository(
    private val db: AppDatabase,
    private val evm: EvmScanner,
    private val sol: SolanaScanner,
    private val btc: BitcoinScanner,
    private val prices: PriceService,
    private val live: com.coinportracker.onchain.LiveQuotes? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val alt: com.coinportracker.onchain.AltScanner? = null,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val syncLock = Mutex()
    private val priceLock = Mutex()
    private val chartCache = HashMap<String, Pair<Long, Map<String, List<PricePoint>>>>()

    val holdings = db.chain().observeHoldings()
    val livePrices = db.chain().observePrices()
    fun activity(limit: Int = 300) = db.chain().observeActivity(limit)
    fun walletActivity(id: Long) = db.chain().observeWalletActivity(id)
    val fxFlow = db.marketCache().observe(FX_KEY)

    /** Define (ou remove, com null) o preço médio de um ativo detectado on-chain. */
    suspend fun setCostBasis(groupKey: String, avgCost: BigDecimal?, currency: com.coinportracker.domain.Currency) {
        if (avgCost == null || avgCost.signum() < 0) db.chain().deleteCostBasis(groupKey)
        else db.chain().upsertCostBasis(com.coinportracker.database.CostBasisEntity(groupKey, avgCost.stripTrailingZeros().toPlainString(), currency.name, null, clock()))
    }

    fun isWatch(w: WalletEntity) = !w.address.isNullOrBlank() && AddressKind.fromStored(w.chain) != null

    /** Converte carteiras de observação da v1 (uma rede EVM por carteira) para o modo multirrede da v2. */
    suspend fun migrateLegacyWallets() {
        for (w in db.wallets().all()) {
            if (w.address.isNullOrBlank() || AddressKind.fromStored(w.chain) != null) continue
            val ch = runCatching { Chain.valueOf(w.chain ?: "") }.getOrNull() ?: continue
            val kind = when { ch.isEvm -> AddressKind.EVM; ch == Chain.SOLANA -> AddressKind.SOLANA; ch == Chain.BITCOIN -> AddressKind.BITCOIN; else -> null } ?: continue
            db.wallets().update(w.copy(chain = AddressKind.storedName(kind), type = WalletType.WATCH_ONLY.name))
        }
    }

    fun validate(address: String): Pair<AddressKind?, String?> {
        val a = address.trim()
        if (a.contains(' ') && a.split(Regex("\\s+")).size >= 12) return null to "Isso parece uma frase-semente. Nunca compartilhe sua frase secreta: informe só o endereço público."
        val cands = AddressKind.candidates(a)
        val kind = cands.firstOrNull()
        if (kind == null) {
            if (AddressValidator.looksLikeSecret(a)) return null to "Isto parece uma chave privada ou frase-semente. NUNCA digite segredos. O CoinPortracker só aceita endereços públicos."
            return null to "Endereço não reconhecido. Redes aceitas: EVM (0x…), Solana, Bitcoin, Litecoin, Dogecoin, Stellar, Tron, XRP, Cardano, TON, Cosmos, Near, Sui e Aptos."
        }
        // 0x + 64 hex (Sui/Aptos) tem o mesmo formato de uma chave privada EVM: a tela exige confirmação explícita
        if (cands.all { it == AddressKind.SUI || it == AddressKind.APTOS }) return kind to null
        val chain = when (kind) { AddressKind.EVM -> Chain.ETHEREUM; AddressKind.SOLANA -> Chain.SOLANA; AddressKind.BITCOIN -> Chain.BITCOIN; else -> null }
        if (chain != null) AddressValidator.validate(chain, a)?.let { return null to it }
        else if (AddressValidator.looksLikeSecret(a)) return null to "Isto parece uma chave privada. O CoinPortracker só aceita endereços públicos."
        return kind to null
    }

    suspend fun addWallet(address: String, name: String, chosen: AddressKind? = null): Outcome<Long> {
        val (detected, err) = validate(address)
        if (detected == null) return Outcome.Err(err ?: "Endereço inválido")
        val kind = chosen?.takeIf { it in AddressKind.candidates(address) } ?: detected
        val a = address.trim()
        if (db.wallets().all().any { !it.archived && it.address != null && WalletDedupe.normalize(it.address) == WalletDedupe.normalize(a) }) return Outcome.Err("Essa carteira já foi adicionada.")
        val id = db.wallets().insert(WalletEntity(name = name.trim().ifEmpty { defaultName(kind, a) }, type = WalletType.WATCH_ONLY.name,
            chain = AddressKind.storedName(kind), address = a, createdAt = clock()))
        return Outcome.Ok(id)
    }

    /** Arquiva carteiras repetidas (mesmo endereço), mantendo a mais antiga. Reversível: os dados não são apagados. */
    suspend fun dedupeWallets() {
        val all = db.wallets().all()
        val dup = WalletDedupe.duplicates(all)
        for (id in dup) { val w = all.first { it.id == id }; db.wallets().update(w.copy(archived = true)); db.chain().clearWallet(id); db.chain().clearActivity(id) }
    }

    private fun defaultName(k: AddressKind, a: String) = "Carteira " + (if (k == AddressKind.EVM) "EVM" else k.label) + " ${a.takeLast(4)}"

    suspend fun renameWallet(id: Long, name: String) { db.wallets().get(id)?.let { db.wallets().update(it.copy(name = name.trim().ifEmpty { it.name })) } }

    suspend fun removeWallet(id: Long) {
        val w = db.wallets().get(id) ?: return
        db.wallets().update(w.copy(archived = true))
        db.chain().clearWallet(id); db.chain().clearActivity(id)
    }

    /** Sincroniza saldos (e histórico) de todas as carteiras; falhas parciais preservam o último saldo conhecido. */
    suspend fun syncAll(withHistory: Boolean, onlyWallet: Long? = null): Outcome<Int> = syncLock.withLock {
        val wallets = db.wallets().all().filter { !it.archived && isWatch(it) && (onlyWallet == null || it.id == onlyWallet) }
        if (wallets.isEmpty()) { refreshPrices(); return@withLock Outcome.Ok(0) }
        _status.update { it.copy(syncing = true, error = null) }
        val warnings = ArrayList<String>()
        val okN = java.util.concurrent.atomic.AtomicInteger(); val failN = java.util.concurrent.atomic.AtomicInteger()
        val sem = Semaphore(4)
        coroutineScope {
            wallets.flatMap { w -> AddressKind.fromStored(w.chain)!!.networks.map { n -> w to n } }.map { (w, n) ->
                async {
                    sem.withPermit {
                        val r = scanOne(n, w.address!!, false) // v3: histórico vem do HistoryRepository (paginado)
                        when (r) {
                            is Outcome.Ok -> {
                            val extra = if (n.family == com.coinportracker.onchain.ChainFamily.EVM) evm.customTokens(n, w.address!!) else emptyList()
                            persist(w.id, n, r.value.copy(holdings = r.value.holdings + extra), withHistory)
                            synchronized(warnings) { warnings += r.value.warnings }; okN.incrementAndGet()
                        }
                            is Outcome.Err -> { synchronized(warnings) { warnings += r.message }; failN.incrementAndGet() }
                        }
                        Triple(w, n, r)
                    }
                }
            }.awaitAll().groupBy { it.first.id }.forEach { (id, results) ->
                val w = db.wallets().get(id) ?: return@forEach
                val allFailed = results.all { it.third is Outcome.Err }
                db.wallets().update(w.copy(lastSyncAt = if (allFailed) w.lastSyncAt else clock(), lastSyncError = if (allFailed) (results.first().third as Outcome.Err).message else null))
            }
        }
        _status.update { it.copy(syncing = false, lastBalanceSync = clock(), warnings = warnings.distinct().take(12), error = if (okN.get() == 0 && failN.get() > 0) "Sem conexão com as redes agora. Mostrando o último saldo salvo." else null) }
        refreshPrices()
        Outcome.Ok(okN.get())
    }

    private suspend fun scanOne(n: Network, address: String, withHistory: Boolean): Outcome<ScanResult> = when (n.family) {
        ChainFamily.EVM -> evm.scan(n, address, withHistory)
        ChainFamily.SOLANA -> sol.scan(address, withHistory)
        ChainFamily.BITCOIN -> btc.scan(address, withHistory, n)
        ChainFamily.ALT -> alt?.scan(n, address) ?: Outcome.Err("${n.label}: leitor indisponível")
    }

    private suspend fun persist(walletId: Long, n: Network, r: ScanResult, withHistory: Boolean) {
        val now = clock()
        db.chain().replaceHoldings(walletId, n.name, r.holdings.map { it.toEntity(walletId, now) })
        if (withHistory && r.activity.isNotEmpty()) db.chain().upsertActivity(r.activity.map { it.toEntity(walletId) })
    }

    /** Atualiza só os preços (leve: 2–4 requisições). Usado no ciclo automático de 30/60 s. */
    private var lastLlama: Map<String, com.coinportracker.onchain.LivePrice> = emptyMap()
    private var lastLlamaAt = 0L
    private var llamaAsked: Set<String> = emptySet()

    /**
     * Preços: DefiLlama (todos os tokens) + último negócio da Kraken para os ativos principais.
     * [fast] = só a cotação ao vivo, com o DefiLlama no máximo a cada minuto.
     */
    suspend fun refreshPrices(extraKeys: Collection<String> = emptyList(), fast: Boolean = false): Outcome<Int> = priceLock.withLock {
        _status.update { it.copy(pricing = !fast) }
        try {
            val keys = (db.chain().holdings().mapNotNull { it.priceKey } + extraKeys + Network.entries.map { it.nativePriceKey }).toSet()
            var r: Outcome<Map<String, com.coinportracker.onchain.LivePrice>> = Outcome.Ok(lastLlama)
            if (com.coinportracker.dev.DevSources.priceEnabled("defillama") && (!fast || lastLlama.isEmpty() || clock() - lastLlamaAt > 60_000L || !llamaAsked.containsAll(keys))) {
                r = prices.current(keys)
                if (r is Outcome.Ok && r.value.isNotEmpty()) { lastLlama = lastLlama + r.value; lastLlamaAt = clock(); llamaAsked = keys }
            }
            val base = lastLlama.ifEmpty { db.chain().observePrices().first().associate { it.key to it.toLive() } }
            val q = if (com.coinportracker.dev.DevSources.priceEnabled("kraken")) (live?.fetch(keys) as? Outcome.Ok)?.value.orEmpty() else emptyMap()
            val now = clock()
            val merged = base.filterKeys { it in keys }.mapValues { (k, v) -> q[k]?.let { com.coinportracker.onchain.LiveQuotes.merge(v, it, now) } ?: v }
            if (merged.isNotEmpty()) db.chain().upsertPrices(merged.values.map { LivePriceEntity(it.key, it.usd, it.change24hPct, it.confidence, it.symbol, it.fetchedAt) })
            val fx = db.marketCache().get(FX_KEY)
            if (fx == null || clock() - fx.fetchedAt > 10 * 60_000L) (prices.usdBrl() as? Outcome.Ok)?.let { db.marketCache().upsert(MarketCacheEntity(FX_KEY, it.value.toPlainString(), "CoinGecko/Frankfurter", clock())) }
            val ok = r is Outcome.Ok || q.isNotEmpty()
            _status.update { it.copy(pricing = false, lastPriceSync = if (ok) clock() else it.lastPriceSync) }
            if (ok) Outcome.Ok(merged.size) else r.map { it.size }
        } finally { _status.update { it.copy(pricing = false) } }
    }

    /** Série do valor do portfólio no intervalo, em USD (cache por intervalo). */
    suspend fun chart(range: ChartRange, weights: Map<String, Double>, current: Map<String, Double>): List<PricePoint> = withContext(Dispatchers.Default) {
        if (weights.isEmpty()) return@withContext emptyList()
        val keys = weights.keys.sorted()
        val cacheKey = range.name + keys.hashCode()
        val now = clock()
        val cached = synchronized(chartCache) { chartCache[cacheKey] }?.takeIf { now - it.first < range.ttlMs }?.second
        val series = cached ?: prices.chart(keys, now / 1000 - range.seconds, range.span, range.period).also { s ->
            if (s.isNotEmpty()) synchronized(chartCache) { chartCache[cacheKey] = now to s }
        }
        Aggregator.valueSeries(weights, series, current, now)
    }

    /** Série de preço de um único ativo (tela de detalhes). */
    suspend fun assetChart(range: ChartRange, priceKey: String): List<PricePoint> = withContext(Dispatchers.Default) {
        val cacheKey = "a:" + range.name + priceKey
        val now = clock()
        synchronized(chartCache) { chartCache[cacheKey] }?.takeIf { now - it.first < range.ttlMs }?.second?.get(priceKey)?.let { return@withContext it }
        val s = prices.chart(listOf(priceKey), now / 1000 - range.seconds, range.span, range.period)
        if (s.isNotEmpty()) synchronized(chartCache) { chartCache[cacheKey] = now to s }
        s[priceKey].orEmpty()
    }

    companion object {
        const val FX_KEY = "fx:usdbrl"

        fun TokenHolding.toEntity(walletId: Long, now: Long) = ChainHoldingEntity(walletId, network.name, tokenKey, symbol, name, decimals, amount.toPlainString(),
            iconUrl, priceKey, kind.name, verified, flaggedScam, priceUsdHint, coingeckoId, now)

        fun ChainHoldingEntity.toHolding(): TokenHolding? {
            val n = Network.byName(network) ?: return null
            return TokenHolding(n, tokenKey, symbol, name, decimals, amount.toBigDecimalOrNull() ?: BigDecimal.ZERO, iconUrl, priceKey,
                runCatching { HoldingKind.valueOf(kind) }.getOrDefault(HoldingKind.TOKEN), verified, flaggedScam, priceUsdHint, coingeckoId)
        }

        fun ChainActivity.toEntity(walletId: Long) = ChainActivityEntity(walletId, network.name, hash, timestamp, direction.name, symbol,
            amount?.toPlainString(), tokenKey, counterparty, fee?.toPlainString(), failed, label, iconUrl, priceKey)

        fun LivePriceEntity.toLive() = LivePrice(key, usd, change24h, confidence, symbol, fetchedAt)
    }
}
