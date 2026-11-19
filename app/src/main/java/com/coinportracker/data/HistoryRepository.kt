package com.coinportracker.data

import androidx.compose.runtime.Immutable
import com.coinportracker.core.Outcome
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.AssetEntity
import com.coinportracker.database.HistoryStateEntity
import com.coinportracker.database.MarketCacheEntity
import com.coinportracker.database.ValueSnapshotEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.LedgerTx
import com.coinportracker.history.*
import com.coinportracker.onchain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.BigInteger

/** Série do gráfico do portfólio (valor real no tempo) + P&L do período. */
@Immutable
data class PortfolioChart(val range: ChartRange, val points: List<PricePoint>, val result: HistoryResult?, val source: String)

/** Staking nativo de SOL detectado na blockchain, com recompensas reais. */
@Immutable
data class StakeView(
    val walletId: Long, val account: String, val principalSol: Double, val rewardsSol: Double, val rewardsUsd: Double?, val aprPct: Double?,
    val lastEpochSol: Double?, val activatedAt: Long?, val complete: Boolean,
    /** v5.0.1: recompensas por época já vistas por este aparelho (mais recente primeiro). */
    val epochs: List<EpochReward> = emptyList(),
)

/** Recompensa de staking de uma época (SOL creditado na conta de stake). */
@Immutable
@Serializable
data class EpochReward(val epoch: Long, val sol: Double, val usd: Double?, val t: Long)

@Serializable private data class StakeViewDto(val walletId: Long, val account: String, val principalSol: Double, val rewardsSol: Double, val rewardsUsd: Double?, val aprPct: Double?,
    val lastEpochSol: Double?, val activatedAt: Long?, val complete: Boolean, val epochs: List<EpochReward> = emptyList())
@Serializable private data class EpochLogDto(val m: Map<String, List<EpochReward>>)

/**
 * v6.0 — estado transparente da leitura do histórico, por carteira e rede: a Atividade mostra carregando, erro (com a
 * rede e o motivo), "sem histórico disponível" e vazio de verdade, em vez de uma lista vazia sem explicação.
 */
@Immutable
data class NetHistory(val walletId: Long, val network: String, val state: NetHistoryState, val message: String? = null)
enum class NetHistoryState { OK, ERROR, UNSUPPORTED }

@Immutable
data class HistoryStatus(
    val running: Boolean = false, val lastAt: Long? = null, val coverage: List<Coverage> = emptyList(), val movements: Int = 0, val error: String? = null,
    /** Movimentações gravadas na leitura em andamento. */
    val fetched: Int = 0,
    val nets: List<NetHistory> = emptyList(),
) {
    val failed: List<NetHistory> get() = nets.filter { it.state == NetHistoryState.ERROR }
    val unsupported: List<NetHistory> get() = nets.filter { it.state == NetHistoryState.UNSUPPORTED }
}

@Serializable private data class StakeDto(val walletId: Long, val account: String, val current: String, val rewards: String, val activatedAt: Long?, val complete: Boolean,
    val lastEpoch: Long?, val lastReward: String?, val lastPost: String?, val flows: List<FlowDto>, val epochMs: Long? = null)
@Serializable private data class HourlyDto(val m: Map<String, Map<Long, Double>>)
@Serializable private data class FlowDto(val sig: String, val t: Long?, val lamports: String)

/**
 * Histórico real do portfólio (v3): lê o histórico on-chain completo aos poucos, recompensas de staking, preços históricos
 * exatos, reconstrói o valor no tempo e estima o preço médio. Tudo local e com APIs públicas gratuitas.
 */
class HistoryRepository(
    private val db: AppDatabase,
    private val evm: EvmScanner,
    private val sol: SolanaScanner,
    private val btc: BitcoinScanner,
    private val prices: PriceService,
    private val stakeReader: StakeRewardsReader,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Baixa o câmbio diário USD/BRL desde o dia informado (v5: o passado é convertido pelo câmbio da época). */
    private val fxBackfill: suspend (fromEpochDay: Long) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _status = MutableStateFlow(HistoryStatus())
    val status: StateFlow<HistoryStatus> = _status.asStateFlow()
    private val _stakes = MutableStateFlow<List<StakeRewardInfoW>>(emptyList())
    private val _estimates = MutableStateFlow<Map<String, CostEstimate>>(emptyMap())
    val estimates: StateFlow<Map<String, CostEstimate>> = _estimates.asStateFlow()
    private val _stakeViews = MutableStateFlow<List<StakeView>>(emptyList())
    val stakeViews: StateFlow<List<StakeView>> = _stakeViews.asStateFlow()
    /** Muda quando há histórico novo (o gráfico se recalcula). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Assinaturas das transações das contas de stake (para marcar carteira ↔ stake como "Entre contas"). */
    val stakeSignatures: kotlinx.coroutines.flow.Flow<Set<String>> = _stakes.map { l -> l.flatMap { s -> s.info.flows.map { it.signature } }.toSet() }.distinctUntilChanged()

    data class StakeRewardInfoW(val walletId: Long, val info: StakeRewardInfo)

    private val hourly = HashMap<String, java.util.TreeMap<Long, Double>>()
    private val seriesCache = HashMap<String, Pair<Long, Map<String, List<PricePoint>>>>()
    private val goneOk = HashMap<String, Boolean>()
    /** Uma nova tentativa por transação sem detalhes a cada abertura do app (sem insistir em transações que nunca têm valores). */
    private val retried = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Lê histórico paginado de todas as carteiras on-chain e as recompensas de staking. */
    suspend fun sync(wallets: List<WalletEntity>) = lock.withLock {
        _status.update { it.copy(running = true, error = null, fetched = 0) }
        var err: String? = null
        val nets = ArrayList<NetHistory>()
        var fetched = 0
        for (w in wallets) {
            val kind = AddressKind.fromStored(w.chain) ?: continue
            val addr = w.address ?: continue
            for (n in kind.networks) {
                if (n.family == ChainFamily.ALT || n.family == ChainFamily.EVM && com.coinportracker.dev.DevSources.indexer(n) == null) {
                    // v3.2: só saldo nessas redes (sem indexador público gratuito); a Atividade diz isso em vez de ficar muda
                    nets += NetHistory(w.id, n.name, NetHistoryState.UNSUPPORTED); continue
                }
                val st = db.chain().historyState(w.id, n.name)?.let { runCatching { json.decodeFromString(HistState.serializer(), it.json) }.getOrNull() } ?: HistState()
                val r = try {
                    when (n.family) {
                        ChainFamily.EVM -> evm.history(n, addr, st)
                        ChainFamily.SOLANA -> sol.history(addr, st) { batch ->
                            // v6.0: grava em blocos de 20 enquanto lê (a lista enche aos poucos)
                            db.chain().upsertActivity(batch.map { with(ChainPortfolioRepository) { it.toEntity(w.id) } })
                            fetched += batch.size; _status.update { it.copy(fetched = fetched) }
                        }
                        ChainFamily.BITCOIN -> btc.history(addr, st, n)
                        ChainFamily.ALT -> continue
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Outcome.Err(e.message ?: "erro inesperado") }
                when (r) {
                    is Outcome.Ok -> {
                        if (r.value.activity.isNotEmpty()) db.chain().upsertActivity(r.value.activity.map { with(ChainPortfolioRepository) { it.toEntity(w.id) } })
                        db.chain().upsertHistoryState(HistoryStateEntity(w.id, n.name, json.encodeToString(HistState.serializer(), r.value.state), clock()))
                        if (n.family != ChainFamily.SOLANA) { fetched += r.value.activity.size; _status.update { it.copy(fetched = fetched) } }
                        nets += NetHistory(w.id, n.name, NetHistoryState.OK)
                    }
                    is Outcome.Err -> { err = err ?: "${n.label}: ${r.message}"; nets += NetHistory(w.id, n.name, NetHistoryState.ERROR, friendly(r.message)) }
                }
            }
        }
        // v6.0: transações de Solana gravadas sem detalhes (o nó não respondeu na hora) são relidas aos poucos
        runCatching { retryDetails(wallets) }
        // staking nativo de SOL
        val stakeHoldings = db.chain().holdings().filter { it.tokenKey.startsWith("stake:") && wallets.any { w -> w.id == it.walletId } }
        if (stakeHoldings.isNotEmpty()) {
            val lamports = stakeHoldings.associate { it.tokenKey.removePrefix("stake:") to (it.amount.toBigDecimalOrNull()?.movePointRight(9)?.toBigInteger() ?: BigInteger.ZERO) }
            when (val r = stakeReader.read(lamports)) {
                is Outcome.Ok -> {
                    val list = r.value.mapNotNull { info -> stakeHoldings.firstOrNull { it.tokenKey == "stake:${info.account}" }?.let { StakeRewardInfoW(it.walletId, info) } }
                    _stakes.value = list
                    db.marketCache().upsert(MarketCacheEntity(STAKE_KEY, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(StakeDto.serializer()), list.map { it.toDto() }), "Solana RPC", clock()))
                }
                is Outcome.Err -> err = err ?: "Staking: ${r.message}"
            }
        } else _stakes.value = emptyList()
        _status.update { it.copy(running = false, lastAt = clock(), error = err, nets = nets, coverage = runCatching { coverage() }.getOrDefault(it.coverage)) }
        _version.update { it + 1 }
    }

    /** Até 12 transações por leitura que ficaram sem valores (getTransaction falhou) são relidas; nada é apagado se falhar de novo. */
    private suspend fun retryDetails(wallets: List<WalletEntity>) {
        val ids = wallets.map { it.id }.toSet()
        val bare = db.chain().bareActivity("SOLANA", 40).filter { it.walletId in ids && retried.add("${it.walletId}:${it.hash}") }.take(12)
        for (row in bare) {
            val w = wallets.firstOrNull { it.id == row.walletId } ?: continue
            val addr = w.address ?: continue
            val fresh = sol.txActivity(row.hash, row.timestamp, row.failed, row.label, addr.trim())
            if (fresh.any { it.amount != null }) db.chain().replaceActivity(row, fresh.map { with(ChainPortfolioRepository) { it.toEntity(w.id) } })
        }
    }

    /** Mensagem curta e compreensível para o motivo técnico da falha. */
    internal fun friendly(m: String): String = HistoryText.friendly(m)

    /**
     * v5.0.1: na abertura, carrega a última visão de staking salva. Antes ela só existia depois do primeiro cálculo de
     * estimativas (segundos após abrir): até lá o Resultado contava as recompensas como lucro (+R$ 3,03 no Início) e,
     * depois, as excluía (R$ 0,00 na Análise). Agora todas as telas usam as mesmas recompensas desde o primeiro quadro.
     */
    suspend fun warmStart() {
        loadStakes()
        if (_stakeViews.value.isNotEmpty()) return
        val c = db.marketCache().get(STAKE_VIEWS_KEY) ?: return
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StakeViewDto.serializer()), c.json) }.getOrNull()?.let { l ->
            if (_stakeViews.value.isEmpty()) _stakeViews.value = l.map { StakeView(it.walletId, it.account, it.principalSol, it.rewardsSol, it.rewardsUsd, it.aprPct, it.lastEpochSol, it.activatedAt, it.complete, it.epochs) }
        }
    }

    private suspend fun epochLog(): MutableMap<String, MutableList<EpochReward>> {
        val c = db.marketCache().get(EPOCH_LOG_KEY) ?: return HashMap()
        return runCatching { json.decodeFromString(EpochLogDto.serializer(), c.json).m.mapValues { it.value.toMutableList() }.toMutableMap() }.getOrDefault(HashMap())
    }

    private suspend fun loadStakes() {
        if (_stakes.value.isNotEmpty()) return
        val c = db.marketCache().get(STAKE_KEY) ?: return
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StakeDto.serializer()), c.json) }.getOrNull()?.let { l -> _stakes.value = l.map { it.toInfo() } }
    }

    suspend fun coverage(): List<Coverage> = db.chain().historyStates().mapNotNull { e ->
        val st = runCatching { json.decodeFromString(HistState.serializer(), e.json) }.getOrNull() ?: return@mapNotNull null
        Coverage(e.walletId, e.network, st.knownFrom, st.complete)
    }

    /** Entradas do motor a partir do estado atual do portfólio. */
    suspend fun inputs(h: HubState, ledger: List<LedgerTx>, assets: Map<String, AssetEntity>): Inputs = withContext(Dispatchers.Default) {
        loadStakes()
        val now = clock()
        val positions = h.agg.assets.flatMap { g -> g.parts.map { p ->
            val net = if (p.source.manual) "MANUAL" else p.holding.network.name
            Position(PosId(p.source.walletId, net, p.holding.tokenKey), g.key, p.holding.priceKey, p.holding.symbol, p.holding.amount.toDouble(),
                if (p.source.manual) PosKind.MANUAL else HistoryInputs.kindOf(p.holding.kind.name, p.holding.symbol, p.holding.name), p.priceUsd)
        } }
        val wallets = h.wallets.filter { it.watch }.map { it.id }.toSet()
        val activity = db.chain().allActivity().filter { it.walletId in wallets }.map {
            ActivityRow(it.walletId, it.network, it.hash, it.timestamp, it.direction, it.amount?.toDoubleOrNull(), it.tokenKey, it.counterparty, it.fee?.toDoubleOrNull(), it.failed, it.priceKey, it.symbol)
        }
        // tokens que já saíram da carteira: só entram se o preço for confiável
        val heldKeys = positions.mapNotNull { it.priceKey }.toSet()
        val unknown = activity.mapNotNull { it.priceKey }.filter { it !in heldKeys && it !in goneOk && !it.startsWith("coingecko:") }.distinct()
        if (unknown.isNotEmpty()) (prices.current(unknown.take(150)) as? Outcome.Ok)?.value?.let { m -> unknown.take(150).forEach { k -> goneOk[k] = (m[k]?.confidence ?: 0.0) >= 0.9 } }
        val own = h.wallets.mapNotNull { it.address?.lowercase() }.toSet()
        val stakes = _stakes.value.filter { it.walletId in wallets }.map { s ->
            StakeInput(s.walletId, s.info.account, s.info.flows.mapNotNull { f -> f.t?.let { t -> Triple(f.signature, t, BigDecimal(f.lamports).movePointLeft(9).toDouble()) } },
                if (s.info.complete) BigDecimal(s.info.rewardsLamports).movePointLeft(9).toDouble() else 0.0, s.info.activatedAt)
        }
        val ledgerManual = ledger.filter { tx -> listOf(tx.fromWalletId, tx.toWalletId).none { it != null && it in wallets } }
        // redes sem histórico (indexador indisponível, ex.: BNB Chain ou bloqueio temporário): marcadas como parciais
        val cov = coverage().toMutableList()
        positions.filter { it.id.network != "MANUAL" && it.id.walletId in wallets }.map { it.id.walletId to it.id.network }.distinct()
            .filter { k -> cov.none { it.walletId == k.first && it.network == k.second } }.forEach { (w, n) -> cov += Coverage(w, n, null, false) }
        HistoryInputs.build(positions, activity, cov, stakes, own, ledgerManual,
            { id -> assets[id]?.coingeckoId?.let { "coingecko:$it" } }, { k, _ -> k.startsWith("coingecko:") || goneOk[k] == true }, now)
    }

    /** Valor real do portfólio no intervalo + P&L do período. */
    suspend fun chart(range: ChartRange, h: HubState, ledger: List<LedgerTx>, assets: Map<String, AssetEntity>): PortfolioChart = withContext(Dispatchers.Default) {
        val now = clock()
        val inp = inputs(h, ledger, assets)
        val firstMove = inp.movements.minOfOrNull { it.t }
        val start = when (range) {
            ChartRange.ALL -> (firstMove ?: (now - 30L * 86_400_000L)).coerceAtLeast(now - range.seconds * 1000)
            else -> now - range.seconds * 1000
        }
        val keys = inp.positions.mapNotNull { it.priceKey }.toSet()
        val series = series(keys, start, now, range)
        val current = inp.positions.mapNotNull { p -> p.priceKey?.let { k -> p.currentPrice?.let { k to it } } }.toMap()
        val usdSeries = series.mapValues { (_, v) -> v.map { ValuePoint(it.t, it.usd) } }
        // v5: em reais, cada ponto do passado vale pelo câmbio daquele dia (não pelo de hoje)
        val fxf = fxFactor(h, start, now)
        val book = PriceBook(if (fxf != null) FxRebase.rebase(usdSeries, fxf) else usdSeries, current, now)
        val points = when (range) { ChartRange.D1 -> 73; ChartRange.W1 -> 85; ChartRange.M1 -> 91; ChartRange.Y1 -> 122; ChartRange.ALL -> 122 }
        val res = HistoryEngine.reconstruct(inp.positions, inp.movements, inp.coverage, inp.accruals, book, start, now, points)
        val pts = res.points.map { PricePoint(it.t, it.usd) }
        if (pts.size >= 2 && pts.any { it.usd > 0 }) return@withContext PortfolioChart(range, pts, res, if (res.partial) "Reconstruído on-chain (parcial)" else "Reconstruído on-chain")
        // sem histórico: usa as fotos salvas do patrimônio
        val snaps = db.chain().snapshots(start).map { PricePoint(it.t, it.totalUsd) }
        PortfolioChart(range, snaps + PricePoint(now, h.agg.totalUsd), null, "Fotos salvas do patrimônio")
    }

    /** Moeda em que o P&L é lido: a do total pago (se informado), senão a de exibição. */
    private fun pnlCurrency(h: HubState) = if (h.settings.invested != null) h.settings.investedCurrency else h.currency

    private var fxAskedFrom = Long.MAX_VALUE
    private var fxAskedAt = 0L
    private suspend fun fxFactor(h: HubState, start: Long, now: Long): ((Long) -> Double)? {
        if (pnlCurrency(h) != com.coinportracker.domain.Currency.BRL) return null
        val fxNow = h.fx?.toDouble()?.takeIf { it > 0 } ?: return null
        val fromDay = Math.floorDiv(start, 86_400_000L) - 4
        var daily = db.fx().all().associate { it.epochDay to (it.usdBrl.toDoubleOrNull() ?: 0.0) }
        val covered = daily.keys.minOrNull()?.let { it <= fromDay + 4 } == true && daily.keys.count { it >= fromDay } >= 1
        if (!covered && (fromDay < fxAskedFrom || now - fxAskedAt > 10 * 60_000L)) {
            fxAskedFrom = minOf(fxAskedFrom, fromDay); fxAskedAt = now
            runCatching { fxBackfill(fromDay) }
            daily = db.fx().all().associate { it.epochDay to (it.usdBrl.toDoubleOrNull() ?: 0.0) }
        }
        return FxRebase.factor(daily, fxNow, start, now)
    }

    private suspend fun series(keys: Set<String>, start: Long, now: Long, range: ChartRange): Map<String, List<PricePoint>> {
        if (keys.isEmpty()) return emptyMap()
        val spanSec = (now - start) / 1000
        val nPts = 90
        val stepSec = (spanSec / nPts).coerceAtLeast(1800)
        val period = if (stepSec >= 86_400) "${stepSec / 86_400}d" else "${(stepSec / 3600).coerceAtLeast(1)}h".let { if (stepSec < 3600) "30m" else it }
        val ck = range.name + ":" + keys.sorted().hashCode() + ":" + start / 3_600_000
        synchronized(seriesCache) { seriesCache[ck] }?.takeIf { now - it.first < range.ttlMs }?.let { return it.second }
        val s = prices.chart(keys, start / 1000, (spanSec / stepSec).toInt() + 2, period)
        if (s.isNotEmpty()) synchronized(seriesCache) { seriesCache[ck] = now to s }
        return s
    }

    /** Recalcula o preço médio estimado com preços horários exatos de cada entrada. */
    suspend fun refreshEstimates(h: HubState, ledger: List<LedgerTx>, assets: Map<String, AssetEntity>) = withContext(Dispatchers.Default) {
        if (!h.ready) return@withContext
        val now = clock()
        val inp = inputs(h, ledger, assets)
        val posBy = inp.positions.associateBy { it.id }
        fun pk(m: Movement) = posBy[m.pos]?.priceKey ?: inp.positions.firstOrNull { it.id.network == m.pos.network && it.id.tokenKey == m.pos.tokenKey }?.priceKey
        // instantes que precisam de preço (arredondados à hora)
        val want = HashMap<String, MutableSet<Long>>()
        fun need(k: String?, t: Long) { if (k != null) want.getOrPut(k) { HashSet() } += t / 3_600_000L * 3600L }
        for (m in inp.movements) if (m.delta > 0 && m.kind != FlowKind.INTERNAL || m.kind == FlowKind.TRADE_OUT) need(pk(m), m.t)
        for (c in inp.coverage) c.knownFrom?.let { t -> inp.positions.filter { it.id.walletId == c.walletId && it.id.network == c.network }.forEach { need(it.priceKey, t) } }
        for (a in inp.accruals) need(posBy[a.pos]?.priceKey, a.start + (now - a.start) / 2)
        loadHourly()
        val missing = want.mapValues { (k, ts) -> ts.filter { hourly[k]?.containsKey(it) != true } }.filterValues { it.isNotEmpty() }
        if (missing.isNotEmpty()) {
            val got = prices.historical(missing.mapValues { (_, v) -> v.take(400) })
            for ((k, pts) in got) { val m = hourly.getOrPut(k) { java.util.TreeMap() }; for (p in pts) m[p.t / 3_600_000L * 3600L] = p.usd }
            saveHourly()
        }
        val current = inp.positions.mapNotNull { p -> p.priceKey?.let { k -> p.currentPrice?.let { k to it } } }.toMap()
        fun priceAt(k: String?, t: Long): Double? {
            if (k == null) return null
            if (now - t < 3_600_000L) current[k]?.let { return it }
            val m = hourly[k] ?: return current[k].takeIf { now - t < 86_400_000L }
            val h0 = t / 3_600_000L * 3600L
            return m[h0] ?: m.floorEntry(h0)?.takeIf { h0 - it.key <= 6 * 3600 }?.value ?: m.ceilingEntry(h0)?.takeIf { it.key - h0 <= 6 * 3600 }?.value
        }
        val est = CostEstimator.estimate(inp.positions, inp.movements, inp.accruals, inp.coverage, ::priceAt, { g -> Aggregator.costAlias(g) ?: g }, now)
        _estimates.value = est
        // visão de staking com recompensas em USD (preço médio do período de acúmulo)
        val log = epochLog()
        val views = _stakes.value.map { s ->
            val i = s.info
            val r = BigDecimal(i.rewardsLamports).movePointLeft(9).toDouble()
            val px = i.activatedAt?.let { a -> priceAt("coingecko:solana", a + (now - a) / 2) } ?: current["coingecko:solana"]
            // registro por época: cada sincronização acrescenta a última época lida (sem repetir)
            val list = log.getOrPut(i.account) { ArrayList() }
            val le = i.lastEpoch; val lr = i.lastEpochRewardLamports
            if (le != null && lr != null && lr.signum() > 0 && list.none { it.epoch == le }) {
                val sol = BigDecimal(lr).movePointLeft(9).toDouble()
                list += EpochReward(le, sol, current["coingecko:solana"]?.let { sol * it }, now)
            }
            list.sortByDescending { it.epoch }
            while (list.size > 400) list.removeAt(list.lastIndex)
            StakeView(s.walletId, i.account, BigDecimal(i.principalLamports).movePointLeft(9).toDouble(), r, px?.let { r * it }, i.aprPct,
                i.lastEpochRewardLamports?.let { BigDecimal(it).movePointLeft(9).toDouble() }, i.activatedAt, i.complete, list.toList())
        }
        _stakeViews.value = views
        runCatching {
            db.marketCache().upsert(MarketCacheEntity(EPOCH_LOG_KEY, json.encodeToString(EpochLogDto.serializer(), EpochLogDto(log.filterKeys { k -> views.any { it.account == k } })), "Solana RPC", now))
            db.marketCache().upsert(MarketCacheEntity(STAKE_VIEWS_KEY, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(StakeViewDto.serializer()),
                views.map { StakeViewDto(it.walletId, it.account, it.principalSol, it.rewardsSol, it.rewardsUsd, it.aprPct, it.lastEpochSol, it.activatedAt, it.complete, it.epochs) }), "Solana RPC", now))
        }
        _status.update { it.copy(coverage = inp.coverage, movements = inp.movements.size) }
    }

    private var hourlyLoaded = false
    private suspend fun loadHourly() {
        if (hourlyLoaded) return
        hourlyLoaded = true
        val c = db.marketCache().get(HOURLY_KEY) ?: return
        runCatching { json.decodeFromString(HourlyDto.serializer(), c.json) }.getOrNull()?.m?.forEach { (k, m) -> hourly.getOrPut(k) { java.util.TreeMap() }.putAll(m) }
    }
    private suspend fun saveHourly() {
        val m = hourly.mapValues { (_, v) -> v.toMap() }
        db.marketCache().upsert(MarketCacheEntity(HOURLY_KEY, json.encodeToString(HourlyDto.serializer(), HourlyDto(m)), "DefiLlama", clock()))
    }

    /** Foto do patrimônio total (no máximo a cada 15 min). */
    suspend fun snapshot(totalUsd: Double, partial: Boolean) {
        if (totalUsd <= 0 || totalUsd.isNaN()) return
        val now = clock()
        val last = db.chain().lastSnapshot()
        if (last != null && now - last.t < 15 * 60_000L) return
        db.chain().upsertSnapshot(ValueSnapshotEntity(now, totalUsd, partial))
        db.chain().compactSnapshots(now - 30L * 86_400_000L)
    }

    suspend fun clearWallet(id: Long) { db.chain().clearHistoryState(id); _stakes.update { l -> l.filter { it.walletId != id } }; _version.update { it + 1 } }

    private fun StakeRewardInfoW.toDto() = StakeDto(walletId, info.account, info.currentLamports.toString(), info.rewardsLamports.toString(), info.activatedAt, info.complete,
        info.lastEpoch, info.lastEpochRewardLamports?.toString(), info.lastEpochPostLamports?.toString(), info.flows.map { FlowDto(it.signature, it.t, it.lamports.toString()) }, info.epochMs)
    private fun StakeDto.toInfo() = StakeRewardInfoW(walletId, StakeRewardInfo(account, BigInteger(current), flows.map { StakeFlow(it.sig, it.t, BigInteger(it.lamports)) },
        BigInteger(rewards), activatedAt, complete, lastEpoch, lastReward?.let { BigInteger(it) }, lastPost?.let { BigInteger(it) }, epochMs ?: StakeRewardsReader.EPOCH_MS))

    companion object {
        const val STAKE_KEY = "stake:rewards:v3"
        const val STAKE_VIEWS_KEY = "stake:views:v1"
        const val EPOCH_LOG_KEY = "stake:epochs:v1"
        const val HOURLY_KEY = "prices:hourly:v3"
    }
}

/** v6.0 — textos da Atividade (puros, testados na JVM). */
object HistoryText {
    fun friendly(m: String): String {
        val l = m.lowercase()
        return when {
            "429" in l || "too many" in l || "rate" in l -> "a rede limitou as consultas agora; tente de novo em alguns minutos"
            "timeout" in l || "timed out" in l -> "a rede demorou para responder"
            "unable to resolve" in l || "unknownhost" in l || "failed to connect" in l || "sem conexão" in l || "network is unreachable" in l -> "sem conexão com a internet"
            "403" in l || "401" in l -> "o servidor recusou a consulta"
            "5" == l.take(1) || "500" in l || "502" in l || "503" in l || "504" in l -> "o servidor da rede está instável"
            else -> m.take(120)
        }
    }
}
