package com.coinportracker.data

import android.content.Context
import com.coinportracker.blockchain.BitcoinEsploraProvider
import com.coinportracker.blockchain.EvmRpcProvider
import com.coinportracker.blockchain.SolanaRpcProvider
import com.coinportracker.database.AppDatabase
import com.coinportracker.market.CoinGeckoProvider
import com.coinportracker.market.CoinPaprikaProvider
import com.coinportracker.market.FrankfurterFxProvider
import com.coinportracker.network.HttpClient
import com.coinportracker.settings.SettingsRepository
import com.coinportracker.tax.TaxRulesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth

/** Injeção de dependências manual (sem frameworks extras). */
class AppContainer(private val context: Context, val db: AppDatabase = AppDatabase.encrypted(context)) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val dev = com.coinportracker.dev.DevConfigRepository(context)
    @Volatile private var cgKey: String = ""
    val http = HttpClient(onRequest = { provider -> db.apiUsage().increment(provider.substringBefore(':'), YearMonth.now().toString()) })
    val probe = com.coinportracker.dev.EndpointProbe(http)
    val coingecko = CoinGeckoProvider(http) { cgKey }
    val paprika = CoinPaprikaProvider(http)
    val market = MarketRepository(db, settings, linkedMapOf(coingecko.id to coingecko, paprika.id to paprika), FrankfurterFxProvider(http))
    val transactions = TransactionRepository(db)
    val portfolio = PortfolioService(db, settings, scope)
    val walletSync = WalletSyncRepository(db, BitcoinEsploraProvider(http), SolanaRpcProvider(http), EvmRpcProvider(http), transactions)
    val backup by lazy { BackupRepository(db, settings, context, dev, tax) }
    private val rpc = com.coinportracker.onchain.JsonRpc(http)
    val tokenLists = com.coinportracker.onchain.CachedTokenLists(http, java.io.File(context.cacheDir, "tokenlists"), bundled = { name: String -> runCatching { context.assets.open(name) }.getOrNull() })
    private val evmScanner = com.coinportracker.onchain.EvmScanner(http, rpc, tokenLists)
    private val solScanner = com.coinportracker.onchain.SolanaScanner(http, rpc)
    private val btcScanner = com.coinportracker.onchain.BitcoinScanner(http)
    private val priceService = com.coinportracker.onchain.PriceService(http)
    val liveQuotes = com.coinportracker.onchain.LiveQuotes(http)
    val chain = ChainPortfolioRepository(db, evmScanner, solScanner, btcScanner, priceService, liveQuotes, alt = com.coinportracker.onchain.AltScanner(http))
    val history = HistoryRepository(db, evmScanner, solScanner, btcScanner, priceService, com.coinportracker.onchain.StakeRewardsReader(rpc),
        fxBackfill = { day -> market.refreshFxHistory(day) })
    val reports = ReportGenerator()
    val hub by lazy { PortfolioHub(this, scope) }

    init {
        scope.launch { settings.settings.collect { cgKey = it.coingeckoDemoKey } }
        runCatching {
            val n = dev.current().net
            http.applyTimeouts(n.connectTimeoutSec, n.readTimeoutSec, n.callTimeoutSec)
        }
        scope.launch { dev.config.collect { http.applyTimeouts(it.net.connectTimeoutSec, it.net.readTimeoutSec, it.net.callTimeoutSec) } }
    }

    /** Pós-atualização: snapshot diário e alertas locais. */
    val tax = TaxRulesRepository(context)

    /** Worker em segundo plano e atualização em primeiro plano podem terminar juntos: um alerta nunca dispara duas vezes. */
    private val alertLock = kotlinx.coroutines.sync.Mutex()

    suspend fun afterRefresh() = alertLock.withLock { afterRefreshLocked() }

    private suspend fun afterRefreshLocked() {
        portfolio.writeSnapshot()
        val st = settings.current()
        if (!st.alertsEnabled) return
        val alerts = db.alerts().all() // relido dentro do lock: já reflete o lastTriggeredAt de quem rodou antes
        if (alerts.isEmpty()) return
        val h = hub.state.value.takeIf { it.ready }
        val equity = h?.conv(h.agg.totalUsd)
        val day = h?.agg?.change24hPct?.let { java.math.BigDecimal.valueOf(it) }
        val top = h?.let { s -> s.agg.assets.firstOrNull()?.let { a -> if (s.agg.totalUsd > 0) java.math.BigDecimal.valueOf(a.valueUsd / s.agg.totalUsd * 100) else null } }
        val live = h?.agg?.assets?.flatMap { a ->
            val usd = a.priceUsd?.takeIf { !it.isNaN() && !it.isInfinite() } ?: return@flatMap emptyList()
            val ch = a.change24h?.let { java.math.BigDecimal.valueOf(it) }
            listOfNotNull(
                AlertEvaluator.Price(a.key, a.symbol, java.math.BigDecimal.valueOf(usd), ch, com.coinportracker.domain.Currency.USD, "portfólio"),
                h.fx?.let { fx -> AlertEvaluator.Price(a.key, a.symbol, java.math.BigDecimal.valueOf(usd).multiply(fx), ch, com.coinportracker.domain.Currency.BRL, "portfólio") },
            )
        }.orEmpty()
        val (hits, baselines) = AlertEvaluator.evaluate(alerts, db.quotes().all(), equity, top, st.baseCurrency, System.currentTimeMillis(), day, live)
        baselines.forEach { db.alerts().update(it) }
        for (hit in hits) {
            db.alerts().update(hit.alert.copy(lastTriggeredAt = System.currentTimeMillis(), lastValue = hit.newBaseline ?: hit.alert.lastValue))
            // Saldos ocultos ou app bloqueado: a notificação não mostra o patrimônio (preço de mercado é público e continua).
            val text = if ((st.hideValues || st.biometricLock) && AlertEvaluator.revealsPortfolio(hit.alert.kind)) AlertEvaluator.redactedMessage(hit.alert) else hit.message
            Notifier.notify(context, hit.alert.id.toInt(), "CoinPortracker · alerta", text)
        }
    }

    fun today(): LocalDate = LocalDate.now()
}
