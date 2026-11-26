package com.coinportracker.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import com.coinportracker.data.ChartRange
import com.coinportracker.data.HubState
import com.coinportracker.data.SyncStatus
import com.coinportracker.data.WalletSummary
import com.coinportracker.domain.Currency
import com.coinportracker.onchain.*
import com.coinportracker.portfolio.HoldingsPnlCalc
import com.coinportracker.settings.AppSettings
import com.coinportracker.settings.ThemeMode
import com.coinportracker.ui.screens.*
import com.coinportracker.ui.theme.CoinPortrackerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal
import kotlin.math.sin

/** Gera capturas de tela das telas principais com dados de exemplo (rodar com -Pshots=true). */
private fun android.graphics.Bitmap.asImageBitmapCompat(): androidx.compose.ui.graphics.ImageBitmap = this.asImageBitmap()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getProperty("shotsDir") ?: "previews")

    @Before fun gate() { assumeTrue(System.getProperty("shots") == "true"); out.mkdirs() }

    private class Fake(h: HubState, pts: List<PricePoint>, cd: ChartData? = null, r: ChartRange = ChartRange.M1, alerts0: List<com.coinportracker.database.AlertEntity> = emptyList()) : UiModel {
        override val hub = MutableStateFlow(h)
        override val refreshing = MutableStateFlow(false)
        override val sync = MutableStateFlow(SyncStatus(lastBalanceSync = System.currentTimeMillis()))
        override val chart = MutableStateFlow<ChartData?>(cd ?: ChartData(ChartRange.M1, pts, com.coinportracker.history.HistoryResult(
            pts.map { com.coinportracker.history.ValuePoint(it.t, it.usd) }, pts.first().t, pts.last().usd * 0.12, pts.last().usd * 0.14, pts.last().usd * 0.02, 41.3, false), "Blockchain + DefiLlama"))
        override val range = MutableStateFlow(r)
        override fun setRange(r: ChartRange) {}
        override fun fullSync(manual: Boolean) {}
        override fun updateSettings(f: (AppSettings) -> AppSettings) { hub.value = hub.value.copy(settings = f(hub.value.settings)) }
        override fun assetChart(range: ChartRange, key: String, onResult: (List<PricePoint>) -> Unit) { onResult(series(60, 2280.0, 3150.0, 7)) }
        override fun setCostBasis(groupKey: String, avg: BigDecimal?, cur: Currency) {}
        override fun ensureLedgerAsset(groupKey: String, symbol: String, name: String, iconChain: String?, onDone: (String) -> Unit) {}
        override fun hideToken(key: String, hide: Boolean) {}
        override fun setBanner(uri: Uri?) {}
        override val activity = MutableStateFlow(sampleActivity())
        override val allTx = MutableStateFlow(sampleTx())
        override fun addWatchWallet(address: String, name: String, kind: AddressKind?, onDone: (Boolean) -> Unit) {}
        override fun addWallet(name: String, type: String) {}
        override fun renameWallet(id: Long, name: String) {}
        override fun removeWallet(id: Long) {}
        override fun syncWallet(id: Long) {}
        override fun validateAddress(a: String): Pair<AddressKind?, String?> = AddressKind.detect(a) to null
        override fun toast(m: String?) {}
        override val alerts = MutableStateFlow(alerts0)
        override fun saveAlert(a: com.coinportracker.database.AlertEntity) {}
        override fun deleteAlert(a: com.coinportracker.database.AlertEntity) {}
    }

    companion object {
        private const val H = 3_600_000L
        fun sampleActivity(): List<com.coinportracker.database.ChainActivityEntity> {
            val now = System.currentTimeMillis()
            fun a(w: Long, n: Network, hash: String, ago: Long, dir: String, sym: String, amt: String?, pk: String?, cp: String?, label: String? = null) =
                com.coinportracker.database.ChainActivityEntity(w, n.name, hash, now - ago, dir, sym, amt, null, cp, null, false, label, null, pk)
            return listOf(
                a(3, Network.SOLANA, "s1", 2 * H, "IN", "SOL", "1.25", "coingecko:solana", "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"),
                a(1, Network.BASE, "b1", 5 * H, "OUT", "USDC", "350", "coingecko:usd-coin", "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad"),
                a(1, Network.ETHEREUM, "e1", 27 * H, "CONTRACT", "ETH", null, null, "0x7a250d5630b4cf539739df2c5dacb4c659f2488d", "Swap (Uniswap)"),
                a(2, Network.BITCOIN, "btc1", 30 * H, "IN", "BTC", "0.015", "coingecko:bitcoin", "bc1q9h7garjv4f3zqk7e8xw2l9c6s0e0wq8x3a2kq"),
                a(1, Network.ARBITRUM, "a1", 52 * H, "IN", "ARB", "420", "coingecko:arbitrum", "0x912ce59144191c1204e64559fe8253a0e49e6548"),
                a(3, Network.SOLANA, "s2", 75 * H, "SELF", "SOL", "20", "coingecko:solana", null),
                a(3, Network.SOLANA, "s3", 12 * H, "IN", "SOL", "0.08", "coingecko:solana", null, "Stake reward"),
            )
        }
        fun sampleTx(): List<com.coinportracker.database.TransactionEntity> {
            val now = System.currentTimeMillis()
            fun t(id: Long, type: String, ago: Long, asset: String, q: String, p: String?, w: Long? = null, ex: String? = null) =
                com.coinportracker.database.TransactionEntity(id, type, now - ago, asset, q, p, "BRL", fromWalletId = w, toWalletId = w, exchange = ex)
            return listOf(t(1, "BUY", 3 * H, "ethereum", "0.5", "13950", 1, "Binance"), t(2, "STAKING", 26 * H, "solana", "0.42", "1215"),
                t(3, "SELL", 80 * H, "bitcoin", "0.01", "498000", 2), t(4, "BUY", 200 * H, "solana", "60", "760", 3, "Mercado Bitcoin"))
        }
        fun series(n: Int, from: Double, to: Double, seed: Int): List<PricePoint> {
            val now = System.currentTimeMillis()
            return (0 until n).map { i ->
                val f = i / (n - 1.0)
                val noise = sin(i * 0.9 + seed) * 0.025 + sin(i * 0.31 + seed * 2) * 0.04
                PricePoint(now - (n - 1 - i) * 12 * 3_600_000L, (from + (to - from) * f) * (1 + noise))
            }
        }

        fun sample(theme: ThemeMode = ThemeMode.NAVY, priceScale: Double = 1.0): HubState {
            fun bd(s: String) = BigDecimal(s)
            val main = HoldingSource(1, "Principal"); val ledger = HoldingSource(2, "Ledger Nano"); val sol = HoldingSource(3, "Phantom")
            fun t(n: Network, key: String, sym: String, name: String, amt: String, pk: String, kind: HoldingKind = HoldingKind.TOKEN, cg: String? = null, verified: Boolean = true) =
                TokenHolding(n, key, sym, name, 18, bd(amt), null, pk, kind, verified, coingeckoId = cg)
            val onchain = listOf(
                main to t(Network.ETHEREUM, "native", "ETH", "Ether", "1.8421", "coingecko:ethereum", HoldingKind.NATIVE, "ethereum"),
                main to t(Network.BASE, "native", "ETH", "Ether", "0.6134", "coingecko:ethereum", HoldingKind.NATIVE, "ethereum"),
                main to t(Network.BASE, "0xusdc", "USDC", "USD Coin", "2450.18", "coingecko:usd-coin", cg = "usd-coin"),
                main to t(Network.ARBITRUM, "0xarb", "ARB", "Arbitrum", "1820", "coingecko:arbitrum", cg = "arbitrum"),
                ledger to t(Network.BITCOIN, "native", "BTC", "Bitcoin", "0.08732", "coingecko:bitcoin", HoldingKind.NATIVE, "bitcoin"),
                sol to t(Network.SOLANA, "native", "SOL", "Solana", "42.73", "coingecko:solana", HoldingKind.NATIVE, "solana"),
                sol to t(Network.SOLANA, "stake:abc", "SOL", "SOL em stake", "120.5", "coingecko:solana", HoldingKind.STAKED, "solana"),
                sol to t(Network.SOLANA, "jup", "JUP", "Jupiter", "3150", "coingecko:jupiter-exchange-solana", cg = "jupiter-exchange-solana"),
                main to t(Network.ETHEREUM, "0xsteth", "stETH", "Lido Staked Ether", "0.75", "coingecko:staked-ether", HoldingKind.LIQUID_STAKING, "staked-ether"),
                main to t(Network.POLYGON, "0xspam", "VISIT claim-usdt.xyz", "Claim reward", "50000", "polygon:0xspam", verified = false),
            )
            fun lp(k: String, usd: Double, ch: Double) = k to LivePrice(k, usd * priceScale, ch, 0.99, null, 0)
            val prices = mapOf(lp("coingecko:ethereum", 3184.2, 2.41), lp("coingecko:usd-coin", 1.0, 0.0), lp("coingecko:arbitrum", 0.78, -3.6),
                lp("coingecko:bitcoin", 91250.0, 1.12), lp("coingecko:solana", 214.6, 4.85), lp("coingecko:jupiter-exchange-solana", 1.12, -1.9), lp("coingecko:staked-ether", 3179.0, 2.38))
            val agg = Aggregator.aggregate(onchain, emptyList(), prices, 1.0)
            val pnl = HoldingsPnlCalc.compute(agg.assets, mapOf("cg:ethereum" to bd("2410"), "cg:bitcoin" to bd("58300")), mapOf("cg:solana" to bd("146.2"), "cg:arbitrum" to bd("1.05")),
                bd("1250.40"), bd("4800"), bd("86.20"))
            fun wv(k: String) = agg.byWallet.firstOrNull { it.key == k }?.valueUsd ?: 0.0
            val now = System.currentTimeMillis()
            val wallets = listOf(
                WalletSummary(1, "Principal", "0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d", AddressKind.EVM, wv("1"), pnl.byWallet["1"]?.pnl, listOf(Network.ETHEREUM, Network.BASE, Network.ARBITRUM), now, null, true),
                WalletSummary(3, "Phantom", "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", AddressKind.SOLANA, wv("3"), pnl.byWallet["3"]?.pnl, listOf(Network.SOLANA), now, null, true),
                WalletSummary(2, "Ledger Nano", "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", AddressKind.BITCOIN, wv("2"), pnl.byWallet["2"]?.pnl, listOf(Network.BITCOIN), now, null, true),
            )
            val est = mapOf(
                "cg:jupiter-exchange-solana" to com.coinportracker.history.CostEstimate("cg:jupiter-exchange-solana", 0.86, 3150.0, 2709.0, 0.0, 0.0, 0.0, 0.94, com.coinportracker.history.Confidence.HIGH, 4, 0.0, now - 200L * 24 * H),
                "cg:staked-ether" to com.coinportracker.history.CostEstimate("cg:staked-ether", 2890.0, 0.75, 2167.5, 0.0, 12.4, 0.0039, 0.71, com.coinportracker.history.Confidence.MEDIUM, 2, 0.2, now - 90L * 24 * H),
                "cg:usd-coin" to com.coinportracker.history.CostEstimate("cg:usd-coin", 1.0, 2450.18, 2450.18, 0.0, 0.0, 0.0, 1.0, com.coinportracker.history.Confidence.HIGH, 6, 0.0, now - 300L * 24 * H),
            )
            val pnl2 = HoldingsPnlCalc.compute(agg.assets, mapOf("cg:ethereum" to bd("2410"), "cg:bitcoin" to bd("58300")), mapOf("cg:solana" to bd("146.2"), "cg:arbitrum" to bd("1.05")),
                bd("1250.40"), bd("4800"), bd("86.20"), est)
            val stakes = listOf(com.coinportracker.data.StakeView(3, "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2", 117.83, 2.67, 2.67 * 214.6, 6.8, 0.0221, now - 400L * 24 * H, true))
            return HubState(true, AppSettings(theme = theme, baseCurrency = Currency.BRL), BigDecimal("5.78"), agg, pnl2, wallets, emptyMap(), emptyMap(), bd("86.20"), mapOf("ethereum" to "ETH", "solana" to "SOL", "bitcoin" to "BTC"),
                stakes = stakes, estimates = est)
        }
    }

    private fun shoot(name: String, theme: ThemeMode = ThemeMode.NAVY, tab: String? = null, scale: Double = 1.0, fontScale: Float = 1f, tweak: (HubState) -> HubState = { it },
                      range: ChartRange = ChartRange.M1, chart: ((HubState) -> ChartData)? = null,
                      alerts: List<com.coinportracker.database.AlertEntity> = emptyList(),
                      content: @androidx.compose.runtime.Composable (UiModel, androidx.navigation.NavHostController) -> Unit) {
        val h = tweak(sample(theme, scale))
        val pts = series(60, h.agg.totalUsd * 0.78, h.agg.totalUsd, 3)
        val fake = Fake(h, pts, chart?.invoke(h), range, alerts)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d0 = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d0.density, fontScale)) {
            CoinPortrackerTheme(theme) {
                val nav = rememberNavController()
                Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                    bottomBar = { if (tab != null) BottomBar(nav, tab) }) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) { content(fake, nav) }
                }
            }
            }
        }
        repeat(6) { rule.mainClock.advanceTimeBy(500); Thread.sleep(250) }
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        File(out, "$name.png").also { it.parentFile?.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot("01_inicio_saldo_gif", tab = "home") { vm, nav -> HomeScreen(vm, nav) }
    @Test fun homeLight() = shoot("02_inicio_tema_claro", ThemeMode.LIGHT, tab = "home") { vm, nav -> HomeScreen(vm, nav) }
    @Test fun holdings() = shoot("03_ativos_lista", tab = "assets") { vm, nav -> HoldingsScreen(vm, nav) }
    @Test fun asset() = shoot("04_ativo_detalhe_pnl") { vm, nav -> AssetDetailV2(vm, nav, "cg:ethereum") }
    @Test fun positionTotal() = shoot("80_posicao_total") { vm, nav -> PositionScreen(vm, nav, "cg:solana") }
    @Test fun analysis() = shoot("06_analise_pnl_alocacao", tab = "analysis") { vm, nav -> AnalysisScreen(vm, nav) }
    @Test fun activity() = shoot("07_atividade_onchain", tab = "activity") { vm, nav -> ActivityScreen(vm, nav) }
    @Test fun wallets() = shoot("09_carteiras") { vm, nav -> WalletsV2Screen(vm, nav) }
    @Test fun addWallet() = shoot("10_adicionar_carteira") { vm, nav -> AddWalletScreen(vm, nav, "0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d") }
    @Test fun banner() = shoot("05_ajustes_banner_gif") { vm, nav -> BannerSettingsScreen(vm, nav) }
    @Test fun homeClean() = shoot("11_inicio_gif_sem_textos", tab = "home", tweak = { it.copy(settings = it.settings.copy(banner = it.settings.banner.copy(hideOverlays = true))) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun settingsHub() = shoot("12_ajustes_blocos", tab = "settings") { vm, nav -> SettingsScreen(vm, nav) }
    @Test fun settingsCost() = shoot("13_ajustes_preco_medio") { vm, nav -> SettingsCategoryScreen(vm, nav, "portfolio") }
    @Test fun settingsRefresh() = shoot("14_ajustes_saldo_ao_vivo") { vm, nav -> SettingsCategoryScreen(vm, nav, "atualizacao") }
    @Test fun settingsAppearance() = shoot("15_ajustes_aparencia") { vm, nav -> SettingsCategoryScreen(vm, nav, "aparencia") }
    @Test fun pnl() = shoot("16_lucro_prejuizo") { vm, nav -> PnlScreen(vm, nav) }
    @Test fun share() = shoot("17_compartilhar_gif") { vm, nav -> ShareScreen(vm, nav) }
    @Test fun shareGradient() = shoot("18_compartilhar_degrade_barras") { vm, nav -> ShareScreen(vm, nav, com.coinportracker.share.ShareOptions(style = com.coinportracker.share.ShareStyle.BARS, bg = com.coinportracker.share.ShareBg.GRADIENT)) }
    @Test fun assetEstimated() = shoot("19_ativo_preco_estimado") { vm, nav -> AssetDetailV2(vm, nav, "cg:jupiter-exchange-solana") }
    @Test fun walletsLight() = shoot("20_carteiras_tema_claro", ThemeMode.LIGHT) { vm, nav -> WalletsV2Screen(vm, nav) }
    @Test fun pnlLight() = shoot("21_lucro_tema_claro", ThemeMode.LIGHT) { vm, nav -> PnlScreen(vm, nav) }

    // Números enormes (item 12): bilhões e trilhões, também com fonte do sistema em 130%
    @Test fun bigHome() = shoot("30_bilhoes_inicio", tab = "home", scale = 2.0e4) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun bigHomeTri() = shoot("31_trilhoes_inicio_fonte130", tab = "home", scale = 3.0e7, fontScale = 1.3f) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun bigHoldings() = shoot("32_trilhoes_ativos", tab = "assets", scale = 3.0e7) { vm, nav -> HoldingsScreen(vm, nav) }
    @Test fun bigPnl() = shoot("33_trilhoes_lucro", scale = 3.0e7) { vm, nav -> PnlScreen(vm, nav) }
    @Test fun bigWallets() = shoot("34_bilhoes_carteiras_fonte130", scale = 2.0e4, fontScale = 1.3f) { vm, nav -> WalletsV2Screen(vm, nav) }
    @Test fun bigAsset() = shoot("35_trilhoes_ativo", scale = 3.0e7) { vm, nav -> AssetDetailV2(vm, nav, "cg:ethereum") }
    @Test fun bigAnalysis() = shoot("36_trilhoes_analise", tab = "analysis", scale = 3.0e7) { vm, nav -> AnalysisScreen(vm, nav) }
    @Test fun homeFont130() = shoot("37_inicio_fonte130", tab = "home", fontScale = 1.3f) { vm, nav -> HomeScreen(vm, nav) }

    /** Cartão de compartilhar exportado em tamanho real (PNG 1080×1350), como sai no arquivo. */
    // ---------------- v3.2 ----------------
    /** Série sintética coerente com o motor v3.2: aporte em degrau (principal) + efeito de preço; termina no valor atual. */
    private fun pnlChart(h: HubState, r: ChartRange, depositAt: Double, marketPct: Double, wiggle: Double, seed: Int): ChartData {
        val now = System.currentTimeMillis(); val span = r.seconds * 1000; val n = 80
        val v1 = h.agg.totalUsd
        val rnd = java.util.Random(seed.toLong())
        val dep = v1 * 0.55
        // ganho de mercado acumulado (passeio suave) terminando em marketPct do valor
        var g = 0.0; val gains = DoubleArray(n)
        for (i in 0 until n) { g += (rnd.nextGaussian() * wiggle + marketPct / n) * v1 * 0.01 * 100 / 100; gains[i] = g }
        val adj = (v1 * marketPct / 100 - gains[n - 1]) / n
        for (i in 0 until n) gains[i] += adj * (i + 1)
        val base0 = v1 - dep - gains[n - 1]
        val principal = List(n) { i -> base0 + if (i >= (n * depositAt).toInt()) dep else 0.0 }
        val pts = List(n) { i -> com.coinportracker.history.ValuePoint(now - span + span * i / (n - 1), principal[i] + gains[i]) }
        val res = com.coinportracker.history.HistoryResult(pts, pts.first().t, dep, dep, 0.0, v1 * 0.0004, false, gains[n - 1], marketPct, gains.toList(), principal)
        return ChartData(r, pts.map { PricePoint(it.t, it.usd) }, res, "Reconstruído on-chain")
    }
    /** Caso real reportado: pagou o valor atual − R$ 18,83 → Resultado real +R$ 18,83. */
    private val paidLikeReportedCase: (HubState) -> HubState = { h -> h.copy(settings = h.settings.copy(investedTotal = "%.2f".format(java.util.Locale.US, h.agg.totalUsd * 5.78 - 18.83), investedCurrency = Currency.BRL)) }

    @Test fun v32HomeD1() = shoot("v32/40_inicio_pnl_1D", tab = "home", tweak = paidLikeReportedCase, range = ChartRange.D1, chart = { pnlChart(it, ChartRange.D1, 2.0, -0.6, 0.08, 1) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun v32HomeW1() = shoot("v32/41_inicio_pnl_1S", tab = "home", tweak = paidLikeReportedCase, range = ChartRange.W1, chart = { pnlChart(it, ChartRange.W1, 0.7, 1.8, 0.12, 2) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun v32HomeM1() = shoot("v32/42_inicio_pnl_1M", tab = "home", tweak = paidLikeReportedCase, range = ChartRange.M1, chart = { pnlChart(it, ChartRange.M1, 0.5, -3.2, 0.15, 3) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun v32HomeY1() = shoot("v32/43_inicio_pnl_1A", tab = "home", tweak = paidLikeReportedCase, range = ChartRange.Y1, chart = { pnlChart(it, ChartRange.Y1, 0.8, 6.5, 0.2, 4) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun v32HomeAll() = shoot("v32/44_inicio_pnl_tudo", tab = "home", tweak = paidLikeReportedCase, range = ChartRange.ALL, chart = { pnlChart(it, ChartRange.ALL, 0.6, 0.24, 0.2, 5) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun v32Pnl() = shoot("v32/45_lucro_prejuizo", tweak = paidLikeReportedCase, range = ChartRange.ALL, chart = { pnlChart(it, ChartRange.ALL, 0.6, 0.24, 0.2, 5) }) { vm, nav -> PnlScreen(vm, nav) }
    @Test fun v32PnlLoss() = shoot("v32/46_lucro_prejuizo_perda", tweak = { h -> h.copy(settings = h.settings.copy(investedTotal = "%.2f".format(java.util.Locale.US, h.agg.totalUsd * 5.78 * 1.2), investedCurrency = Currency.BRL)) },
        range = ChartRange.M1, chart = { pnlChart(it, ChartRange.M1, 0.5, -3.2, 0.15, 3) }) { vm, nav -> PnlScreen(vm, nav) }
    @Test fun v32PortfolioPrice() = shoot("v32/47_preco_do_portfolio", tweak = paidLikeReportedCase) { vm, nav -> SettingsCategoryScreen(vm, nav, "portfolio") }
    @Test fun v32AddStellar() = shoot("v32/48_adicionar_carteira_stellar") { vm, nav -> AddWalletScreen(vm, nav, "GDUKMGUGDZQK6YHYA5Z6AY2G4XDSZPSZ3SW5UN3ARVMO6QSRDWP5YLEX") }
    @Test fun v32AddTron() = shoot("v32/49_adicionar_carteira_tron") { vm, nav -> AddWalletScreen(vm, nav, "TNXoiAJ3dct8Fjg4M9fkLFh9S2v9TXc32G") }
    @Test fun v32AddAmbiguous() = shoot("v32/50_adicionar_carteira_sui_ou_aptos") { vm, nav -> AddWalletScreen(vm, nav, "0x0000000000000000000000000000000000000000000000000000000000000005") }
    @Test fun v32AddEvm() = shoot("v32/51_adicionar_carteira_evm_7_redes") { vm, nav -> AddWalletScreen(vm, nav, "0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d") }
    @Test fun v32Upscale() = shoot("v32/52_upscale_estados") { _, _ ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val imgs: Pair<androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.graphics.ImageBitmap> = androidx.compose.runtime.remember {
            val b = android.graphics.BitmapFactory.decodeStream(ctx.assets.open(com.coinportracker.ui.components.DEFAULT_BANNER.removePrefix("asset:")))
            val small = android.graphics.Bitmap.createScaledBitmap(b, b.width / 3, b.height / 3, true)
            small.asImageBitmapCompat() to b.asImageBitmapCompat()
        }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(16.dp)) {
            listOf(com.coinportracker.media.HdStatus(com.coinportracker.media.HdState.QUEUED), com.coinportracker.media.HdStatus(com.coinportracker.media.HdState.RUNNING, 0.45f, 7_000, 8_600, accel = "CPU (XNNPACK)"),
                com.coinportracker.media.HdStatus(com.coinportracker.media.HdState.PAUSED, reason = "Celular quente"), com.coinportracker.media.HdStatus(com.coinportracker.media.HdState.ORIGINAL),
                com.coinportracker.media.HdStatus(com.coinportracker.media.HdState.DONE, 1f, 14_200, method = "ML")).forEach { st ->
                com.coinportracker.ui.components.CpCard(Modifier.padding(bottom = 8.dp)) {
                    com.coinportracker.ui.components.HdStatusView(st) { com.coinportracker.ui.components.BeforeAfterView(imgs.first, imgs.second, Modifier.padding(top = 8.dp), 0.45f) }
                }
            }
        }
    }

    @Test fun shareCardFile() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = com.coinportracker.share.ShareCardRenderer(ctx)
        val d = com.coinportracker.share.ShareData.from(sample(), 5, null, "13/11/2026")
        val bmp = r.render(1080, 1350, d, com.coinportracker.share.ShareOptions(bg = com.coinportracker.share.ShareBg.GRADIENT), null)
        File(out, "22_cartao_compartilhado_1080x1350.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------- v3.3 ----------------
    private fun withSecondBtc(h: HubState): HubState {
        val extra = WalletSummary(4, "Carteira 2", "bc1q9h7garjv4f3zqk7e8xw2l9c6s0e0wq8x3a2kq", AddressKind.BITCOIN, 1_200.0, null, listOf(Network.BITCOIN), System.currentTimeMillis(), null, true)
        return h.copy(wallets = h.wallets + extra)
    }

    @Test fun v33Home() = shoot("v33/60_inicio_receber", tab = "home", tweak = paidLikeReportedCase, range = ChartRange.M1, chart = { pnlChart(it, ChartRange.M1, 0.5, -3.2, 0.15, 3) }) { vm, nav -> HomeScreen(vm, nav) }

    @Test fun v33ReceiveAssets() = shoot("v33/61_receber_ativos") { vm, nav -> ReceiveScreen(vm, nav, previewStep = ReceiveStep.Assets) }

    @Test fun v33ReceiveAddresses() = shoot("v33/62_receber_enderecos_btc", tweak = { withSecondBtc(it) }) { vm, nav ->
        val assets = com.coinportracker.receive.ReceiveAssets.from(vm.hub.value.wallets)
        val btc = assets.first { it.network == Network.BITCOIN }
        ReceiveScreen(vm, nav, previewStep = ReceiveStep.Addresses(btc))
    }

    @Test fun v33ReceiveQrBtc() = shoot("v33/63_receber_qr_bitcoin") { vm, nav ->
        val assets = com.coinportracker.receive.ReceiveAssets.from(vm.hub.value.wallets)
        val btc = assets.first { it.network == Network.BITCOIN }
        ReceiveScreen(vm, nav, previewStep = ReceiveStep.Qr(btc, btc.addresses[0]))
    }

    @Test fun v33ReceiveQrPolygon() = shoot("v33/64_receber_qr_ethereum") { vm, nav ->
        val assets = com.coinportracker.receive.ReceiveAssets.from(vm.hub.value.wallets)
        val eth = assets.first { it.network == Network.ETHEREUM }
        ReceiveScreen(vm, nav, previewStep = ReceiveStep.Qr(eth, eth.addresses[0]))
    }

    @Test fun v33TrimVideo() = shoot("v33/65_recortar_video") { _, nav ->
        VideoTrimScreen(nav, preview = com.coinportracker.media.VideoFormat.TrimWindow(10_000, 70_000, 180_000))
    }

    @Test fun v33BannerSettings() = shoot("v33/66_ajustes_banner") { vm, nav -> BannerSettingsScreen(vm, nav) }

    @Test fun v40DevHub() = shoot("v40/70_dev_inicio") { _, nav -> DevSettingsScreen(nav) }
    @Test fun v40DevEthereum() = shoot("v40/71_dev_ethereum") { _, nav -> DevSettingsScreen(nav, "chain:ETHEREUM") }
    @Test fun v40DevManual() = shoot("v40/72_dev_manual") { _, nav -> DevSettingsScreen(nav, "manual") }
    @Test fun v40DevDiag() = shoot("v40/73_dev_diagnostico") { _, nav -> DevSettingsScreen(nav, "diag") }
    @Test fun v40DevCustom() = shoot("v40/74_dev_redes_tokens") { _, nav -> DevSettingsScreen(nav, "custom") }

    // ---------------- v4.1 ----------------
    private val withGoal: (HubState) -> HubState = { h ->
        val base = paidLikeReportedCase(h)
        base.copy(settings = base.settings.copy(goalTarget = "500000", goalCurrency = Currency.BRL, alertsEnabled = true))
    }
    private fun sampleAlerts() = listOf(
        com.coinportracker.database.AlertEntity(1, "PRICE_ABOVE", "cg:solana", "solana", "SOL", "1500", "BRL", true, true, null, null, 0),
        com.coinportracker.database.AlertEntity(2, "PRICE_BELOW", "cg:bitcoin", "bitcoin", "BTC", "400000", "BRL", true, false, null, null, 0),
        com.coinportracker.database.AlertEntity(3, "PORTFOLIO_DAY", null, null, "Portfólio no dia", "3", "BRL", true, true, null, null, 0),
    )

    @Test fun v41Home() = shoot("80_inicio_acoes_meta", tab = "home", tweak = withGoal, range = ChartRange.ALL, chart = { pnlChart(it, ChartRange.ALL, 0.0, -0.36, 0.08, 5) }) { vm, nav -> HomeScreen(vm, nav) }
    @Test fun v41Analysis() = shoot("81_analise_pnl_compartilhar", tab = "analysis", tweak = withGoal, range = ChartRange.ALL, chart = { pnlChart(it, ChartRange.ALL, 0.0, -0.36, 0.08, 5) }) { vm, nav -> AnalysisScreen(vm, nav) }
    @Test fun v41Activity() = shoot("82_atividade_filtros", tab = "activity") { vm, nav -> ActivityScreen(vm, nav) }
    @Test fun v41Accounting() = shoot("83_contabil", tweak = withGoal) { vm, nav -> AccountingScreen(vm, nav) }
    @Test fun v41Alerts() = shoot("84_alertas", tweak = withGoal, alerts = sampleAlerts()) { vm, nav -> SettingsCategoryScreen(vm, nav, "alertas") }
    @Test fun v41Goals() = shoot("85_metas", tweak = withGoal) { vm, nav -> SettingsCategoryScreen(vm, nav, "metas") }
    @Test fun v41Security() = shoot("86_seguranca_privacidade") { vm, nav -> SettingsCategoryScreen(vm, nav, "seguranca") }
    @Test fun v41Refresh() = shoot("87_atualizacao_baixo_consumo") { vm, nav -> SettingsCategoryScreen(vm, nav, "atualizacao") }
    @Test fun v41Settings() = shoot("88_ajustes", tab = "settings", tweak = withGoal) { vm, nav -> SettingsScreen(vm, nav) }
    @Test fun v41About() = shoot("89_sobre") { vm, nav -> SettingsCategoryScreen(vm, nav, "sobre") }
    @Test fun v41Pnl() = shoot("90_lucro_prejuizo", tweak = withGoal, range = ChartRange.ALL, chart = { pnlChart(it, ChartRange.ALL, 0.0, -0.36, 0.08, 5) }) { vm, nav -> PnlScreen(vm, nav) }
    @Test fun v41Hidden() = shoot("91_ocultos_vazio", tweak = { it.copy(agg = it.agg.copy(hidden = emptyList()), settings = it.settings.copy(hiddenTokens = emptySet())) }) { vm, nav -> HoldingsScreen(vm, nav, previewView = HoldView.HIDDEN) }
    @Test fun v41DevTax() = shoot("92_dev_regras_fiscais") { _, nav -> DevSettingsScreen(nav, "tax") }
    @Test fun v41HomeLight() = shoot("93_inicio_tema_claro", ThemeMode.LIGHT, tab = "home", tweak = withGoal, range = ChartRange.ALL, chart = { pnlChart(it, ChartRange.ALL, 0.0, -0.36, 0.08, 5) }) { vm, nav -> HomeScreen(vm, nav) }


    // ---------------- v5.0 (revisão de layout: larguras 320/360/393/412 dp, fonte 1.0/1.3/2.0, números enormes, ocultar saldos) ----------------
    private val hidden: (HubState) -> HubState = { h -> withGoal(h).let { it.copy(settings = it.settings.copy(hideValues = true)) } }
    private fun pnlAll(h: HubState) = pnlChart(h, ChartRange.ALL, 0.0, -0.36, 0.08, 5)
    private fun marketCoins(): List<com.coinportracker.market.MarketCoin> {
        fun c(rank: Int, id: String, sym: String, price: String, ch: String, cap: String, spark: List<Double>) =
            com.coinportracker.market.MarketCoin(id = id, symbol = sym, name = sym, image = null, price = price, change24hPct = ch, marketCap = cap, rank = rank, sparkline = spark)
        val flat = List(168) { 1.0 + sin(it * 1.7) * 0.003 }
        val btc = List(168) { 91000.0 * (1 + sin(it * 0.08) * 0.04 + it * 0.0002) }
        return listOf(
            c(1, "bitcoin", "btc", "527412.35", "1.12", "10450000000000", btc),
            c(3, "tether", "usdt", "5.78", "0.01", "820000000000", flat.map { it * 5.78 }),
            c(7, "usd-coin", "usdc", "5.7795", "-0.02", "350000000000", flat.reversed().map { it * 5.78 }),
            c(88, "figure-heloc", "figr_heloc", "6.12", "-0.35", "1520000000", flat),
            c(100, "shiba-inu", "shib", "0.00012345", "-4.87", "73000000000", btc.map { it / 9e8 }),
        )
    }
    @androidx.compose.runtime.Composable
    private fun MarketPreview() {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            V2TopBar("Mercado", {})
            marketCoins().forEach { MarketRow(it, com.coinportracker.domain.Currency.BRL, it.rank == 1, {}, {}) }
        }
    }

    // antes/depois na largura do telefone dele (393 dp)
    @Test @Config(qualifiers = "w393dp-h1700dp-xxhdpi") fun v50Pnl393() = shoot("after/lucro_prejuizo_w393", tweak = withGoal, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> PnlScreen(vm, nav) }
    @Test @Config(qualifiers = "w393dp-h1700dp-xxhdpi") fun v50PnlNoCost393() = shoot("after/lucro_prejuizo_sem_custo_w393", tweak = { h -> withGoal(h).copy(pnl = com.coinportracker.portfolio.HoldingsPnlCalc.compute(h.agg.assets, emptyMap(), emptyMap(), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)) }, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> PnlScreen(vm, nav) }
    @Test @Config(qualifiers = "w393dp-h880dp-xxhdpi") fun v50Market393() = shoot("after/mercado_w393") { _, _ -> MarketPreview() }
    @Test @Config(qualifiers = "w320dp-h880dp-xxhdpi") fun v50Market320() = shoot("after/mercado_w320") { _, _ -> MarketPreview() }
    @Test @Config(qualifiers = "w412dp-h880dp-xxhdpi") fun v50Market412f13() = shoot("after/mercado_w412_fonte130", fontScale = 1.3f) { _, _ -> MarketPreview() }
    @Test @Config(qualifiers = "w360dp-h880dp-xxhdpi") fun v50Market360f20() = shoot("after/mercado_w360_fonte200", fontScale = 2.0f) { _, _ -> MarketPreview() }
    @Test @Config(qualifiers = "w393dp-h1700dp-xxhdpi") fun v50Home393() = shoot("after/inicio_w393", tab = "home", tweak = withGoal, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> HomeScreen(vm, nav) }
    @Test @Config(qualifiers = "w320dp-h1700dp-xxhdpi") fun v50Home320f20() = shoot("after/inicio_w320_fonte200", tab = "home", tweak = withGoal, fontScale = 2.0f, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> HomeScreen(vm, nav) }
    @Test @Config(qualifiers = "w412dp-h1700dp-xxhdpi") fun v50HomeTri412f13() = shoot("after/inicio_trilhoes_w412_fonte130", tab = "home", scale = 3.0e7, fontScale = 1.3f) { vm, nav -> HomeScreen(vm, nav) }
    @Test @Config(qualifiers = "w393dp-h1700dp-xxhdpi") fun v50HomeHidden() = shoot("after/inicio_oculto_w393", tab = "home", tweak = hidden, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> HomeScreen(vm, nav) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Analysis360() = shoot("after/analise_w360", tab = "analysis", tweak = withGoal, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> AnalysisScreen(vm, nav) }
    @Test @Config(qualifiers = "w320dp-h1700dp-xxhdpi") fun v50Analysis320f13() = shoot("after/analise_w320_fonte130", tab = "analysis", tweak = withGoal, fontScale = 1.3f, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> AnalysisScreen(vm, nav) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Pnl360f20() = shoot("after/lucro_prejuizo_w360_fonte200", tweak = withGoal, fontScale = 2.0f, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> PnlScreen(vm, nav) }
    @Test @Config(qualifiers = "w320dp-h1700dp-xxhdpi") fun v50PnlTri320() = shoot("after/lucro_prejuizo_trilhoes_w320", scale = 3.0e7, range = ChartRange.ALL, chart = ::pnlAll) { vm, nav -> PnlScreen(vm, nav) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Holdings360f13() = shoot("after/ativos_w360_fonte130", tab = "assets", fontScale = 1.3f) { vm, nav -> HoldingsScreen(vm, nav) }
    @Test @Config(qualifiers = "w412dp-h1700dp-xxhdpi") fun v50HoldingsHidden412() = shoot("after/ativos_oculto_w412", tab = "assets", tweak = hidden) { vm, nav -> HoldingsScreen(vm, nav) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Activity360() = shoot("after/atividade_w360", tab = "activity") { vm, nav -> ActivityScreen(vm, nav) }
    @Test @Config(qualifiers = "w393dp-h1700dp-xxhdpi") fun v50Accounting393() = shoot("after/contabil_w393", tweak = withGoal) { vm, nav -> AccountingScreen(vm, nav) }
    @Test @Config(qualifiers = "w320dp-h1700dp-xxhdpi") fun v50Settings320f20() = shoot("after/ajustes_w320_fonte200", tab = "settings", tweak = withGoal, fontScale = 2.0f) { vm, nav -> SettingsScreen(vm, nav) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Receive360() = shoot("after/receber_w360") { vm, nav -> ReceiveScreen(vm, nav, previewStep = ReceiveStep.Assets) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Goals360f13() = shoot("after/metas_w360_fonte130", tweak = withGoal, fontScale = 1.3f) { vm, nav -> SettingsCategoryScreen(vm, nav, "metas") }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun v50Alerts360() = shoot("after/alertas_w360", tweak = withGoal, alerts = sampleAlerts()) { vm, nav -> SettingsCategoryScreen(vm, nav, "alertas") }
    @Test @Config(qualifiers = "w320dp-h1700dp-xxhdpi") fun v50Wallets320f13() = shoot("after/carteiras_w320_fonte130", fontScale = 1.3f) { vm, nav -> WalletsV2Screen(vm, nav) }
    @Test @Config(qualifiers = "w393dp-h1700dp-xxhdpi") fun v50Asset393() = shoot("after/ativo_detalhe_w393") { vm, nav -> AssetDetailV2(vm, nav, "cg:ethereum") }

}
