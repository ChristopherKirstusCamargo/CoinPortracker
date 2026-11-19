package com.coinportracker.data

import com.coinportracker.analytics.Concentration
import com.coinportracker.analytics.DayPoint
import com.coinportracker.analytics.PortfolioHistory
import com.coinportracker.analytics.PriceBook
import com.coinportracker.analytics.Slice
import com.coinportracker.core.Decimals
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.AssetEntity
import com.coinportracker.database.CategoryEntity
import com.coinportracker.database.FxRateEntity
import com.coinportracker.database.PriceHistoryEntity
import com.coinportracker.database.QuoteEntity
import com.coinportracker.database.SnapshotEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.Chain
import com.coinportracker.domain.Currency
import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.WalletType
import com.coinportracker.portfolio.Explainer
import com.coinportracker.portfolio.PnlCalculator
import com.coinportracker.portfolio.PnlSummary
import com.coinportracker.settings.AppSettings
import com.coinportracker.settings.SettingsRepository
import com.coinportracker.transactions.LedgerEngine
import com.coinportracker.transactions.LedgerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** Preço exibido para um ativo, sempre com fonte e horário. */
data class PriceInfo(
    val price: BigDecimal,
    val currency: Currency,
    val source: String,
    val fetchedAt: Long?,
    val change24hPct: BigDecimal?,
    val estimated: Boolean,
    val quote: QuoteEntity?,
)

data class LedgerInputs(
    val settings: AppSettings,
    val txs: List<TransactionEntity>,
    val assets: List<AssetEntity>,
    val wallets: List<WalletEntity>,
    val categories: List<CategoryEntity>,
    val fx: List<FxRateEntity>,
)

data class PortfolioState(
    val ready: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val currency: Currency = Currency.BRL,
    val assets: Map<String, AssetEntity> = emptyMap(),
    val wallets: Map<Long, WalletEntity> = emptyMap(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val txCount: Int = 0,
    val ledgerTxs: List<LedgerTx> = emptyList(),
    val ledger: LedgerResult? = null,
    val ledgerOther: LedgerResult? = null,
    val pnl: PnlSummary? = null,
    val pnlOther: PnlSummary? = null,
    val prices: Map<String, PriceInfo> = emptyMap(),
    val history: List<DayPoint> = emptyList(),
    val book: PriceBook = emptyMap(),
    val latestFx: FxRateEntity? = null,
    val lastQuoteAt: Long? = null,
    val sources: Set<String> = emptySet(),
    val stale: Boolean = false,
    val fxFallback: (Long) -> BigDecimal? = { null },
) {
    fun symbol(id: String) = assets[id]?.symbol ?: id.removePrefix("custom:")
    fun explainer() = if (ledger != null && pnl != null) Explainer(ledger, pnl, ::symbol) else null

    private fun group(key: (AssetEntity?, String) -> Pair<String, String>): List<Slice> {
        val p = pnl ?: return emptyList()
        val m = LinkedHashMap<String, Pair<String, BigDecimal>>()
        for (a in p.assets) {
            val mv = a.marketValue ?: continue
            if (mv.signum() <= 0) continue
            val (k, label) = key(assets[a.assetId], a.assetId)
            m[k] = label to ((m[k]?.second ?: BigDecimal.ZERO).add(mv, Decimals.MC))
        }
        return Concentration.slices(m)
    }

    val byAsset: List<Slice> get() = group { a, id -> id to (a?.symbol ?: id) }
    val byCategory: List<Slice> get() = group { a, _ -> (a?.categoryId?.toString() ?: "none") to (a?.categoryId?.let { categories[it]?.name } ?: "Sem categoria") }
    val byChain: List<Slice> get() = group { a, _ -> val c = a?.chain?.let { runCatching { Chain.valueOf(it) }.getOrNull() } ?: Chain.OTHER; c.name to c.label }

    /** Distribuição por carteira usando quantidades por carteira do livro × preço. */
    val byWallet: List<Slice>
        get() {
            val l = ledger ?: return emptyList()
            val m = LinkedHashMap<String, Pair<String, BigDecimal>>()
            for (pos in l.positions.values) {
                val price = prices[pos.assetId]?.price ?: continue
                for ((w, q) in pos.walletQuantities) {
                    if (q.signum() <= 0) continue
                    val k = w?.toString() ?: "none"
                    val label = w?.let { wallets[it]?.name } ?: "Sem carteira"
                    m[k] = label to ((m[k]?.second ?: BigDecimal.ZERO).add(q.multiply(price, Decimals.MC), Decimals.MC))
                }
            }
            return Concentration.slices(m)
        }

    val exchangeSharePct: BigDecimal?
        get() {
            val slices = byWallet
            if (slices.isEmpty()) return null
            return slices.filter { s -> s.key.toLongOrNull()?.let { wallets[it]?.type } == WalletType.EXCHANGE.name }.fold(BigDecimal.ZERO) { a, s -> a.add(s.pct) }
        }

    val stableSharePct: BigDecimal?
        get() {
            val p = pnl ?: return null
            if (p.equity.signum() <= 0) return null
            val st = p.assets.filter { assets[it.assetId]?.isStablecoin == true }.mapNotNull { it.marketValue }.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
            return Decimals.pct(st, p.equity)
        }
}

class PortfolioService(private val db: AppDatabase, private val settings: SettingsRepository, scope: CoroutineScope) {

    private val inputs = combine(
        settings.settings, db.transactions().observeActive(), db.assets().observeAll(), db.wallets().observeAll(),
        combine(db.categories().observeAll(), db.fx().observeAll()) { c, f -> c to f },
    ) { s, t, a, w, cf -> LedgerInputs(s, t, a, w, cf.first, cf.second) }.distinctUntilChanged()

    private data class Computed(val inputs: LedgerInputs, val ledgerTxs: List<LedgerTx>, val ledger: LedgerResult, val other: LedgerResult, val fx: (Long) -> BigDecimal?)

    private val ledgerFlow = inputs.map { inp ->
        val fxMap = TreeMap<Long, BigDecimal>().apply { inp.fx.forEach { put(it.epochDay, BigDecimal(it.usdBrl)) } }
        val zone = ZoneId.systemDefault()
        val fallback: (Long) -> BigDecimal? = { ts -> fxMap.floorEntry(PortfolioHistory.dayOf(ts, zone).toEpochDay())?.takeIf { e -> PortfolioHistory.dayOf(ts, zone).toEpochDay() - e.key <= 5 }?.value }
        val lt = inp.txs.map { it.toLedger() }
        val cur = inp.settings.baseCurrency
        Computed(inp, lt, LedgerEngine(inp.settings.costMethod, cur, fallback).run(lt), LedgerEngine(inp.settings.costMethod, cur.other(), fallback).run(lt), fallback)
    }.flowOn(Dispatchers.Default).conflate()

    private val bookFlow = combine(settings.settings.map { it.baseCurrency }.distinctUntilChanged(), db.priceHistory().observeFor("BRL"), db.priceHistory().observeFor("USD"), db.fx().observeAll()) { cur, brl, usd, fx ->
        buildBook(cur, brl, usd, fx)
    }.flowOn(Dispatchers.Default).conflate()

    val state: StateFlow<PortfolioState> = combine(ledgerFlow, db.quotes().observeAll(), bookFlow) { c, quotes, book ->
        val inp = c.inputs
        val cur = inp.settings.baseCurrency
        val assets = inp.assets.associateBy { it.id }
        val latestFx = inp.fx.lastOrNull()
        val prices = resolvePrices(assets.values, quotes, cur, latestFx)
        val otherPrices = resolvePrices(assets.values, quotes, cur.other(), latestFx)
        val pnl = PnlCalculator.summarize(c.ledger, prices.mapValues { it.value.price })
        val pnlOther = PnlCalculator.summarize(c.other, otherPrices.mapValues { it.value.price })
        val today = LocalDate.now()
        // Ponto de hoje usa as cotações atuais (fonte identificada); dias anteriores, o histórico diário.
        val liveBook: PriceBook = book.mapValues { TreeMap(it.value) }.toMutableMap().also { m ->
            for ((id, p) in prices) m.getOrPut(id) { TreeMap() }[today.toEpochDay()] = p.price
        }
        val history = PortfolioHistory.build(c.ledgerTxs, c.ledger.capitalFlows, liveBook, today)
        val lastQuote = quotes.filter { it.assetId in assets }.maxOfOrNull { it.fetchedAt }
        val stale = lastQuote == null || System.currentTimeMillis() - lastQuote > inp.settings.staleAfterMin * 60_000L
        PortfolioState(
            ready = true, settings = inp.settings, currency = cur, assets = assets, wallets = inp.wallets.associateBy { it.id },
            categories = inp.categories.associateBy { it.id }, txCount = inp.txs.size, ledgerTxs = c.ledgerTxs, ledger = c.ledger, ledgerOther = c.other,
            pnl = pnl, pnlOther = pnlOther, prices = prices, history = history, book = liveBook, latestFx = latestFx,
            lastQuoteAt = lastQuote, sources = prices.values.map { it.source }.toSet(), stale = stale, fxFallback = c.fx,
        )
    }.flowOn(Dispatchers.Default).conflate().stateIn(scope, SharingStarted.Eagerly, PortfolioState())

    companion object {
        fun resolvePrices(assets: Collection<AssetEntity>, quotes: List<QuoteEntity>, cur: Currency, fx: FxRateEntity?): Map<String, PriceInfo> {
            val q = quotes.associateBy { it.assetId to it.currency }
            val out = HashMap<String, PriceInfo>()
            val rate = fx?.usdBrl?.let { BigDecimal(it) }
            for (a in assets) {
                val direct = q[a.id to cur.name]
                if (direct != null) { out[a.id] = PriceInfo(BigDecimal(direct.price), cur, direct.source, direct.fetchedAt, direct.change24hPct.dec(), false, direct); continue }
                val manual = (if (cur == Currency.BRL) a.manualPriceBrl else a.manualPriceUsd).dec()
                if (manual != null) { out[a.id] = PriceInfo(manual, cur, "Preço manual", a.manualPriceAt, null, false, null); continue }
                val otherManual = (if (cur == Currency.BRL) a.manualPriceUsd else a.manualPriceBrl).dec()
                val otherQuote = q[a.id to cur.other().name]
                val base = otherQuote?.price?.dec() ?: otherManual
                if (base != null && rate != null && rate.signum() > 0) {
                    val conv = if (cur == Currency.BRL) base.multiply(rate, Decimals.MC) else base.divide(rate, Decimals.MC)
                    out[a.id] = PriceInfo(conv, cur, "${otherQuote?.source ?: "Preço manual"} convertido (câmbio ${fx.source})", otherQuote?.fetchedAt ?: a.manualPriceAt, otherQuote?.change24hPct.dec(), true, otherQuote)
                }
            }
            return out
        }

        /** Monta séries diárias na moeda; converte de USD por câmbio diário quando não há série direta (marcado na UI). */
        fun buildBook(cur: Currency, brl: List<PriceHistoryEntity>, usd: List<PriceHistoryEntity>, fx: List<FxRateEntity>): PriceBook {
            val direct = (if (cur == Currency.BRL) brl else usd).groupBy { it.assetId }
            val other = (if (cur == Currency.BRL) usd else brl).groupBy { it.assetId }
            val fxMap = TreeMap<Long, BigDecimal>().apply { fx.forEach { put(it.epochDay, BigDecimal(it.usdBrl)) } }
            val out = HashMap<String, TreeMap<Long, BigDecimal>>()
            for ((id, rows) in direct) out[id] = TreeMap<Long, BigDecimal>().apply { rows.forEach { put(it.epochDay, BigDecimal(it.price)) } }
            for ((id, rows) in other) {
                if (id in out) continue
                val t = TreeMap<Long, BigDecimal>()
                for (r in rows) {
                    val e = fxMap.floorEntry(r.epochDay) ?: continue
                    if (r.epochDay - e.key > 4) continue
                    val p = BigDecimal(r.price)
                    t[r.epochDay] = if (cur == Currency.BRL) p.multiply(e.value, Decimals.MC) else p.divide(e.value, Decimals.MC)
                }
                if (t.isNotEmpty()) out[id] = t
            }
            return out
        }
    }

    /** Snapshot diário real (patrimônio e aporte), usado para análises de evolução e concentração. */
    suspend fun writeSnapshot() {
        val s = state.value
        val p = s.pnl ?: return
        val o = s.pnlOther
        val brl = if (s.currency == Currency.BRL) p else o
        val usd = if (s.currency == Currency.USD) p else o
        db.snapshots().upsert(SnapshotEntity(
            LocalDate.now().toEpochDay(), brl?.equity.text(), usd?.equity.text(), brl?.netContributions.text() ?: "0", usd?.netContributions.text(),
            Concentration.hhi(s.byAsset.map { it.value }).text(), p.equityComplete, System.currentTimeMillis()))
    }
}
