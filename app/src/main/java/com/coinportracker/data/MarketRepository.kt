package com.coinportracker.data

import com.coinportracker.core.Outcome
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.AssetEntity
import com.coinportracker.database.FxRateEntity
import com.coinportracker.database.MarketCacheEntity
import com.coinportracker.database.PriceHistoryEntity
import com.coinportracker.database.QuoteEntity
import com.coinportracker.domain.Currency
import com.coinportracker.market.AssetSearchResult
import com.coinportracker.market.CoinGeckoProvider
import com.coinportracker.market.FxProvider
import com.coinportracker.market.GlobalData
import com.coinportracker.market.MarketCoin
import com.coinportracker.market.MarketDataProvider
import com.coinportracker.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset

data class RefreshStatus(
    val running: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val sourcesUsed: Set<String> = emptySet(),
)

/**
 * Orquestra provedores de mercado com cache local. Regras:
 * - Cada cotação guarda sua fonte e horário; nada é misturado sem identificação.
 * - Fallback para o provedor secundário somente se o ativo tiver ID compatível nele, e a fonte fica registrada.
 * - Falhas não apagam o cache: o app continua com o último valor e indica que está desatualizado.
 */
class MarketRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    val providers: Map<String, MarketDataProvider>,
    private val fx: FxProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(RefreshStatus())
    val status: StateFlow<RefreshStatus> = _status
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var lastSupplyAt = 0L
    private var lastFxAt = 0L

    suspend fun primary(): MarketDataProvider = providers[settings.current().marketProvider] ?: providers.values.first()
    private suspend fun secondary(): MarketDataProvider? = if (settings.current().allowFallbackProvider) providers.values.firstOrNull { it.id != primary().id } else null

    /** Atualiza cotações de todos os ativos vinculados + watchlist. Uma chamada em lote por provedor. */
    suspend fun refreshQuotes(includeSupply: Boolean = false): Outcome<Int> = mutex.withLock {
        val now = clock()
        _status.value = _status.value.copy(running = true, lastAttemptAt = now)
        val assets = db.assets().all().filter { !it.archived }
        val watch = db.watchlist().all()
        val p = primary()
        val currencies = listOf(Currency.BRL, Currency.USD)
        val byProviderId = HashMap<String, MutableList<String>>() // providerId -> assetIds
        for (a in assets) p.idFor(a.coingeckoId, a.paprikaId)?.let { byProviderId.getOrPut(it) { ArrayList() } += a.id }
        if (p.id == "coingecko") for (w in watch) byProviderId.getOrPut(w.coingeckoId) { ArrayList() } += "cg:${w.coingeckoId}"
        val used = HashSet<String>()
        var count = 0
        var error: String? = null
        if (byProviderId.isNotEmpty()) {
            when (val r = p.quotes(byProviderId.keys.toList(), currencies)) {
                is Outcome.Ok -> {
                    val rows = r.value.flatMap { q -> byProviderId[q.providerAssetId].orEmpty().map { aid -> q.toEntity(aid, p.displayName, now) } }
                    db.quotes().upsertAll(rows); count += rows.size; used += p.displayName
                }
                is Outcome.Err -> {
                    error = "${p.displayName}: ${r.message}"
                    val s = secondary()
                    if (s != null) {
                        val ids = HashMap<String, MutableList<String>>()
                        for (a in assets) s.idFor(a.coingeckoId, a.paprikaId)?.let { ids.getOrPut(it) { ArrayList() } += a.id }
                        if (ids.isNotEmpty()) when (val r2 = s.quotes(ids.keys.toList(), currencies)) {
                            is Outcome.Ok -> {
                                val rows = r2.value.flatMap { q -> ids[q.providerAssetId].orEmpty().map { aid -> q.toEntity(aid, "${s.displayName} (alternativo)", now) } }
                                db.quotes().upsertAll(rows); count += rows.size; used += "${s.displayName} (alternativo)"
                                error += " — usado ${s.displayName} como fonte alternativa para ${rows.size / 2} ativo(s)."
                            }
                            is Outcome.Err -> error += " | ${s.displayName}: ${r2.message}"
                        }
                    }
                }
            }
        }
        if ((includeSupply || now - lastSupplyAt > 30 * 60_000) && p is CoinGeckoProvider) {
            val cg = assets.mapNotNull { it.coingeckoId }.distinct()
            if (cg.isNotEmpty()) (p.supply(cg, Currency.USD) as? Outcome.Ok)?.value?.let { coins ->
                lastSupplyAt = now
                val existing = db.quotes().all().associateBy { it.assetId to it.currency }
                val upd = ArrayList<QuoteEntity>()
                for (c in coins) for (a in assets.filter { it.coingeckoId == c.id }) for (cur in currencies) {
                    existing[a.id to cur.name]?.let { q -> upd += q.copy(circulatingSupply = c.circulating, maxSupply = c.maxSupply, rank = c.rank) }
                }
                db.quotes().upsertAll(upd)
            }
        }
        if (now - lastFxAt > 60 * 60_000) refreshFxLatest().also { if (it is Outcome.Ok) lastFxAt = now }
        _status.value = RefreshStatus(false, if (error == null || count > 0) now else _status.value.lastSuccessAt, now, error, used)
        if (error != null && count == 0) Outcome.Err(error) else Outcome.Ok(count)
    }

    private fun com.coinportracker.market.MarketQuote.toEntity(assetId: String, source: String, now: Long) = QuoteEntity(
        assetId, currency.name, price.toPlainString(), change24hPct.text(), marketCap.text(), volume24h.text(), circulatingSupply.text(),
        totalSupply.text(), maxSupply.text(), rank, source, providerUpdatedAt, now)

    suspend fun refreshFxLatest(): Outcome<Unit> = when (val r = fx.latestUsdBrl()) {
        is Outcome.Ok -> { db.fx().upsertAll(listOf(FxRateEntity(r.value.first, r.value.second.toPlainString(), "Frankfurter (BCE)"))); Outcome.Ok(Unit) }
        is Outcome.Err -> r
    }

    /** Baixa câmbio diário desde a primeira operação (respeitando o que já está em cache). */
    suspend fun refreshFxHistory(fromEpochDay: Long): Outcome<Int> {
        val have = db.fx().all()
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        val start = if (have.isNotEmpty() && have.first().epochDay <= fromEpochDay) have.last().epochDay + 1 else fromEpochDay
        if (start > today) return Outcome.Ok(0)
        return when (val r = fx.seriesUsdBrl(start, today)) {
            is Outcome.Ok -> { db.fx().upsertAll(r.value.map { FxRateEntity(it.first, it.second.toPlainString(), "Frankfurter (BCE)") }); Outcome.Ok(r.value.size) }
            is Outcome.Err -> r
        }
    }

    /** Histórico diário: baixa somente o que falta (cache incremental). */
    suspend fun refreshHistory(asset: AssetEntity, days: Int = 365): Outcome<Int> {
        val p = primary()
        val pid = p.idFor(asset.coingeckoId, asset.paprikaId) ?: return Outcome.Err("${asset.symbol} não está vinculado ao ${p.displayName}.")
        var total = 0
        val currencies = if (p.id == "coinpaprika") listOf(Currency.USD) else listOf(Currency.BRL, Currency.USD)
        for (c in currencies) {
            val last = db.priceHistory().lastDay(asset.id, c.name)
            val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
            if (last != null && last >= today - 1) continue
            val need = if (last == null) days else (today - last + 2).toInt().coerceAtMost(days)
            when (val r = p.history(pid, c, need)) {
                is Outcome.Ok -> {
                    db.priceHistory().upsertAll(r.value.map { PriceHistoryEntity(asset.id, c.name, it.epochDay, it.price.toPlainString(), p.displayName) })
                    total += r.value.size
                }
                is Outcome.Err -> return r
            }
        }
        return Outcome.Ok(total)
    }

    suspend fun refreshAllHistory(): Outcome<Int> {
        val assets = db.assets().all().filter { !it.archived && (it.coingeckoId != null || it.paprikaId != null) }
        var n = 0
        val errs = ArrayList<String>()
        for (a in assets) when (val r = refreshHistory(a)) { is Outcome.Ok -> n += r.value; is Outcome.Err -> errs += "${a.symbol}: ${r.message}" }
        val first = db.transactions().active().minOfOrNull { it.timestamp }
        if (first != null) refreshFxHistory(java.time.Instant.ofEpochMilli(first).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())
        return if (errs.isNotEmpty() && n == 0) Outcome.Err(errs.joinToString("\n")) else Outcome.Ok(n)
    }

    suspend fun search(query: String): Outcome<List<AssetSearchResult>> = providers["coingecko"]!!.search(query)
    suspend fun searchPaprika(query: String): Outcome<List<AssetSearchResult>> = providers["coinpaprika"]!!.search(query)

    /** Ranking com cache local (mostra cache quando offline). */
    suspend fun markets(currency: Currency, force: Boolean, maxAgeMs: Long = 5 * 60_000L): Pair<List<MarketCoin>, MarketCacheEntity?> {
        val key = "markets:${currency.name}"
        val cached = db.marketCache().get(key)
        val excluded = tokenizedExclusions()
        if (!force && cached != null && clock() - cached.fetchedAt < maxAgeMs) return com.coinportracker.market.MarketFilter.top(decode(cached), excluded) to cached
        val p = providers["coingecko"]!!
        return when (val r = p.markets(currency, 1, com.coinportracker.market.MarketFilter.FETCH)) {
            is Outcome.Ok -> {
                val top = com.coinportracker.market.MarketFilter.top(r.value, excluded)
                val e = MarketCacheEntity(key, json.encodeToString(ListSerializer(MarketCoin.serializer()), top), p.displayName, clock())
                db.marketCache().upsert(e); top to e
            }
            is Outcome.Err -> { if (cached == null) throw IllegalStateException(r.message); com.coinportracker.market.MarketFilter.top(decode(cached), excluded) to cached.copy(source = cached.source + " (cache; falha: ${r.message})") }
        }
    }

    private fun decode(c: MarketCacheEntity) = runCatching { json.decodeFromString(ListSerializer(MarketCoin.serializer()), c.json) }.getOrDefault(emptyList())

    private val rwaKey = "markets:exclude:" + com.coinportracker.market.MarketFilter.CATEGORY

    /** Ids de RWA tokenizados em cache (vazio = só a lista embutida). */
    private suspend fun tokenizedExclusions(): Set<String> =
        db.marketCache().get(rwaKey)?.json?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** Atualiza a lista de exclusão no máximo 1×/dia (chamado depois de mostrar o ranking, sem atrasar a tela). */
    suspend fun refreshTokenizedExclusions(maxAgeMs: Long = 24 * 3_600_000L) {
        val cached = db.marketCache().get(rwaKey)
        if (cached != null && clock() - cached.fetchedAt < maxAgeMs) return
        val cg = providers["coingecko"] as? CoinGeckoProvider ?: return
        val r = cg.categoryIds(com.coinportracker.market.MarketFilter.CATEGORY)
        if (r is Outcome.Ok && r.value.isNotEmpty()) db.marketCache().upsert(MarketCacheEntity(rwaKey, r.value.sorted().joinToString(","), cg.displayName, clock()))
    }

    suspend fun global(currency: Currency): Pair<GlobalData?, MarketCacheEntity?> {
        val key = "global:${currency.name}"
        val cached = db.marketCache().get(key)
        if (cached != null && clock() - cached.fetchedAt < 10 * 60_000) return json.decodeFromString(GlobalData.serializer(), cached.json) to cached
        val p = providers["coingecko"]!!
        return when (val r = p.global(currency)) {
            is Outcome.Ok -> { val e = MarketCacheEntity(key, json.encodeToString(GlobalData.serializer(), r.value), p.displayName, clock()); db.marketCache().upsert(e); r.value to e }
            is Outcome.Err -> (cached?.let { json.decodeFromString(GlobalData.serializer(), it.json) }) to cached
        }
    }

    suspend fun coinHistory(coingeckoId: String, currency: Currency, days: Int) = providers["coingecko"]!!.history(coingeckoId, currency, days)
}
