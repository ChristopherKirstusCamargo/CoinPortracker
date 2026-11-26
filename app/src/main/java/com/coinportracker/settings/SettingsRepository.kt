package com.coinportracker.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { NAVY("Escuro"), AMOLED("Preto"), LIGHT("Claro") }

enum class DashboardCard(val label: String) {
    EQUITY_CHART("Gráfico patrimonial"),
    PNL("Resultado (P&L)"),
    ALLOCATION("Distribuição"),
    TOP_ASSETS("Principais ativos"),
    ALERTS("Alertas e análises"),
    LATEST_TX("Últimas movimentações"),
    DATA_STATUS("Status dos dados"),
}

data class AppSettings(
    val baseCurrency: Currency = Currency.BRL,
    val costMethod: CostMethod = CostMethod.WEIGHTED_AVERAGE,
    val hideValues: Boolean = false,
    val theme: ThemeMode = ThemeMode.NAVY,
    /** Atualização de preços em primeiro plano (segundos): 0 = desligada, 30 ou 60. */
    val foregroundRefreshSec: Int = 60,
    /** Atualização em segundo plano (WorkManager), minutos: 0 = desligada; mínimo 15. */
    val backgroundRefreshMin: Int = 0,
    val marketProvider: String = "coingecko",
    val allowFallbackProvider: Boolean = true,
    val coingeckoDemoKey: String = "",
    val biometricLock: Boolean = false,
    val autoLockSeconds: Int = 60,
    val secureScreen: Boolean = true,
    val riskFreeAnnualPct: String = "10.0",
    val dashboardOrder: List<DashboardCard> = DashboardCard.entries,
    val dashboardHidden: Set<DashboardCard> = emptySet(),
    val defaultChartPeriod: String = "D30",
    val bannerUri: String = "",
    val alertsEnabled: Boolean = false,
    val staleAfterMin: Int = 15,
    val onboardingDone: Boolean = false,
    /** Oculta tokens com valor abaixo de US$ 1 (poeira). */
    val hideSmallBalances: Boolean = true,
    /** Tokens ocultados manualmente (chave do grupo). */
    val hiddenTokens: Set<String> = emptySet(),
    /** Banner/GIF atrás do saldo (discreto). */
    val banner: BannerStyle = BannerStyle(),
    /** Cotações rápidas e saldos on-chain em 12–30 s (ou por evento da rede). O número exibido não é interpolado. Desligado por padrão. */
    val liveBalance: Boolean = false,
    /** Usa o preço médio estimado pela blockchain quando você não informou um. */
    val useEstimatedCost: Boolean = true,
    /** Total pago pelo portfólio (texto decimal simples, ex.: "12500.00"); vazio = não informado. */
    val investedTotal: String = "",
    val investedCurrency: Currency = Currency.BRL,
    /** Data (epoch ms) a partir da qual o valor investido vale; 0 = desde sempre. */
    val investedSince: Long = 0L,
    /** Melhora a qualidade (upscale) de imagens e GIFs adicionados daqui em diante. */
    val upscaleMedia: Boolean = false,
    /** Meta de patrimônio (texto decimal); vazio = sem meta. */
    val goalTarget: String = "",
    val goalCurrency: Currency = Currency.BRL,
    /**
     * Modo de baixo consumo: desligado por padrão e só liga no interruptor.
     * Nunca ativa sozinho (nem com economia de bateria, nem com bateria baixa).
     * Quando ligado, desliga animações, vídeo/GIF e o upscale, e atualiza com menos frequência.
     */
    val lowPower: Boolean = false,
    /** v5.0.1: animações configuráveis (padrões = comportamento da v5.0). */
    val anim: AnimPrefs = AnimPrefs(),
    /** v5.1.0: Rolagem de banner (lista e ordem; os arquivos ficam na pasta privada — o backup completo da v6.0 os inclui). */
    val roll: BannerRoll = BannerRoll(),
    /** v6.0: nota da limpeza única dos vídeos antigos ocultos da Rolagem (vazia = nada a mostrar). */
    val videoCleanNote: String = "",
    /** v6.0: Web Host (painel no navegador, só leitura, na rede local). O servidor nunca liga sozinho. */
    val web: WebHostPrefs = WebHostPrefs(),
) {
    val invested: java.math.BigDecimal? get() = investedTotal.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val goal: java.math.BigDecimal? get() = goalTarget.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
}

enum class NumberAnim(val label: String) { ROLL("Rolar dígitos"), COUNT("Contagem suave"), FADE("Desfoque"), SLIDE("Deslizar"), NONE("Sem animação") }
enum class AnimCurve(val label: String) { SMOOTH("Suave"), SPRING("Mola"), LINEAR("Linear"), EXPRESSIVE("Expressivo") }
enum class ScreenAnim(val label: String) { SLIDE("Deslizar"), FADE("Fade"), SCALE("Escala"), NONE("Nenhuma") }

/** Preferências de animação. Os padrões reproduzem exatamente a v5.0. Velocidades: 1 = normal, 2 = duas vezes mais rápido. */
data class AnimPrefs(
    val number: NumberAnim = NumberAnim.ROLL,
    val numberMs: Int = 560,
    val curve: AnimCurve = AnimCurve.SMOOTH,
    val screen: ScreenAnim = ScreenAnim.SLIDE,
    val screenSpeed: Float = 1f,
    val chart: Boolean = true,
    val chartSpeed: Float = 1f,
    val listAppear: Boolean = true,
    val haptics: Boolean = true,
    val speed: Float = 1f,
) {
    /** Duração efetiva de uma animação de [ms] com o multiplicador global. */
    fun dur(ms: Int, local: Float = 1f): Int = (ms / (speed * local).coerceIn(0.25f, 4f)).toInt().coerceAtLeast(1)
    companion object {
        const val MIN_MS = 150; const val MAX_MS = 1500
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

enum class BannerFit(val label: String) { CROP("Recortar"), FILL("Preencher"), FIT("Ajustar") }

data class BannerStyle(
    val enabled: Boolean = true,
    val opacity: Float = 0.20f,
    val heightDp: Int = 236,
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val blurDp: Float = 0f,
    val cornerDp: Float = 0f,
    val fit: BannerFit = BannerFit.CROP,
    /** 0 = pausado; 0.5, 1, 1.5, 2. */
    val speed: Float = 1f,
    val fade: Boolean = true,
    /** Oculta nome do app, contagem de carteiras e seletor R$/US$ sobre o GIF. Desligado por padrão. */
    val hideOverlays: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val all = listOf("baseCurrency", "costMethod", "hideValues", "theme", "fgRefresh", "bgRefresh", "provider", "fallback", "cgKey",
            "bio", "autoLock", "secure", "rf", "dashOrder", "dashHidden", "chartPeriod", "banner", "bannerH", "alerts", "stale", "onboarding", "hideSmall", "hiddenTokens", "bnOn", "bnOpacity", "bnScale", "bnX", "bnY", "bnBlur", "bnCorner", "bnFit", "bnSpeed", "bnFade", "bnHideText", "live", "estCost", "invTotal", "invCur", "invSince", "upscale", "goalTarget", "goalCur", "lowPower", "anNum", "anMs", "anCurve", "anScreen", "anScreenSpd", "anChart", "anChartSpd", "anList", "anHaptic", "anSpeed", "bnRoll", "bnRollOn", "bnRollGif", "bnRollPhoto", "bnRollVideo", "bnRollMig", "bnRollSel", "bnVidClean", "whPort", "whSessH", "whApprove", "whBindIp", "whIdle", "whTs", "whMdns")
    }
    private fun key(n: String) = stringPreferencesKey(n)

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> fromMap(p.asMap().entries.associate { it.key.name to it.value.toString() }) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val cur = fromMap(prefs.asMap().entries.associate { it.key.name to it.value.toString() })
            toMap(f(cur)).forEach { (k, v) -> prefs[key(k)] = v }
        }
    }

    /** Exportação para backup (somente preferências não secretas: a chave de API não é incluída). */
    suspend fun exportMap(): Map<String, String> = toMap(current()) - NOT_BACKED_UP
    /** Restauração: nunca importa a chave de API nem o caminho do banner (arquivo local deste aparelho; um backup adulterado não aponta o app para outro arquivo). */
    suspend fun importMap(m: Map<String, String>) {
        context.dataStore.edit { prefs -> m.filterKeys { it in K.all && it !in NOT_BACKED_UP }.filterValues { it.length <= 4_096 }.forEach { (k, v) -> prefs[key(k)] = v } }
    }

    companion object {
        /** Nunca entram no backup: a chave de API, caminhos de arquivos deste aparelho e avisos já mostrados. */
        val NOT_BACKED_UP = setOf("cgKey", "banner", "bnRoll", "bnRollMig", "bnRollSel", "bnVidClean")
        fun toMap(s: AppSettings): Map<String, String> = mapOf(
            "baseCurrency" to s.baseCurrency.name, "costMethod" to s.costMethod.name, "hideValues" to s.hideValues.toString(),
            "theme" to s.theme.name, "fgRefresh" to s.foregroundRefreshSec.toString(), "bgRefresh" to s.backgroundRefreshMin.toString(),
            "provider" to s.marketProvider, "fallback" to s.allowFallbackProvider.toString(), "cgKey" to s.coingeckoDemoKey,
            "bio" to s.biometricLock.toString(), "autoLock" to s.autoLockSeconds.toString(), "secure" to s.secureScreen.toString(),
            "rf" to s.riskFreeAnnualPct, "dashOrder" to s.dashboardOrder.joinToString(",") { it.name },
            "dashHidden" to s.dashboardHidden.joinToString(",") { it.name }, "chartPeriod" to s.defaultChartPeriod,
            "banner" to s.bannerUri, "alerts" to s.alertsEnabled.toString(),
            "stale" to s.staleAfterMin.toString(), "onboarding" to s.onboardingDone.toString(),
            "hideSmall" to s.hideSmallBalances.toString(), "hiddenTokens" to s.hiddenTokens.joinToString("\u0001"),
            "bnOn" to s.banner.enabled.toString(), "bnOpacity" to s.banner.opacity.toString(), "bnScale" to s.banner.scale.toString(),
            "bnX" to s.banner.offsetX.toString(), "bnY" to s.banner.offsetY.toString(), "bnBlur" to s.banner.blurDp.toString(),
            "bnCorner" to s.banner.cornerDp.toString(), "bnFit" to s.banner.fit.name, "bnSpeed" to s.banner.speed.toString(), "bnFade" to s.banner.fade.toString(),
            "bannerH" to s.banner.heightDp.toString(), "bnHideText" to s.banner.hideOverlays.toString(),
            "live" to s.liveBalance.toString(), "estCost" to s.useEstimatedCost.toString(),
            "invTotal" to s.investedTotal, "invCur" to s.investedCurrency.name, "invSince" to s.investedSince.toString(), "upscale" to s.upscaleMedia.toString(),
            "goalTarget" to s.goalTarget, "goalCur" to s.goalCurrency.name, "lowPower" to s.lowPower.toString(),
            "anNum" to s.anim.number.name, "anMs" to s.anim.numberMs.toString(), "anCurve" to s.anim.curve.name, "anScreen" to s.anim.screen.name,
            "anScreenSpd" to s.anim.screenSpeed.toString(), "anChart" to s.anim.chart.toString(), "anChartSpd" to s.anim.chartSpeed.toString(),
            "anList" to s.anim.listAppear.toString(), "anHaptic" to s.anim.haptics.toString(), "anSpeed" to s.anim.speed.toString(),
            "bnRoll" to RollRules.encode(s.roll.items), "bnRollOn" to s.roll.enabled.toString(), "bnRollGif" to s.roll.gifsOn.toString(),
            "bnRollPhoto" to s.roll.photosOn.toString(), "bnRollVideo" to s.roll.videosOn.toString(), "bnRollMig" to s.roll.migrated.toString(), "bnRollSel" to s.roll.selected,
            "bnVidClean" to s.videoCleanNote,
            "whPort" to s.web.port.toString(), "whSessH" to s.web.sessionHours.toString(), "whApprove" to s.web.requireApproval.toString(),
            "whBindIp" to s.web.bindToIp.toString(), "whIdle" to s.web.autoStopMin.toString(), "whTs" to s.web.allowTailscale.toString(), "whMdns" to s.web.mdns.toString(),
        )

        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun <T> e(k: String, def: T, f: (String) -> T?): T = m[k]?.let { runCatching { f(it) }.getOrNull() } ?: def
            val order = e("dashOrder", d.dashboardOrder) { s -> s.split(',').mapNotNull { n -> DashboardCard.entries.firstOrNull { it.name == n } } }
                .let { o -> o + DashboardCard.entries.filter { it !in o } }
            return AppSettings(
                baseCurrency = e("baseCurrency", d.baseCurrency) { Currency.valueOf(it) },
                costMethod = e("costMethod", d.costMethod) { CostMethod.valueOf(it) },
                hideValues = e("hideValues", d.hideValues) { it.toBooleanStrict() },
                theme = e("theme", d.theme) { ThemeMode.valueOf(it) },
                foregroundRefreshSec = e("fgRefresh", d.foregroundRefreshSec) { it.toInt().takeIf { v -> v in listOf(0, 30, 60) } },
                backgroundRefreshMin = e("bgRefresh", d.backgroundRefreshMin) { it.toInt() },
                marketProvider = e("provider", d.marketProvider) { it },
                allowFallbackProvider = e("fallback", d.allowFallbackProvider) { it.toBooleanStrict() },
                coingeckoDemoKey = e("cgKey", d.coingeckoDemoKey) { it },
                biometricLock = e("bio", d.biometricLock) { it.toBooleanStrict() },
                autoLockSeconds = e("autoLock", d.autoLockSeconds) { it.toInt() },
                secureScreen = e("secure", d.secureScreen) { it.toBooleanStrict() },
                riskFreeAnnualPct = e("rf", d.riskFreeAnnualPct) { it },
                dashboardOrder = order,
                dashboardHidden = e("dashHidden", d.dashboardHidden) { s -> s.split(',').mapNotNull { n -> DashboardCard.entries.firstOrNull { it.name == n } }.toSet() },
                defaultChartPeriod = e("chartPeriod", d.defaultChartPeriod) { it },
                bannerUri = e("banner", d.bannerUri) { it },
                alertsEnabled = e("alerts", d.alertsEnabled) { it.toBooleanStrict() },
                staleAfterMin = e("stale", d.staleAfterMin) { it.toInt() },
                onboardingDone = e("onboarding", d.onboardingDone) { it.toBooleanStrict() },
                hideSmallBalances = e("hideSmall", d.hideSmallBalances) { it.toBooleanStrict() },
                hiddenTokens = e("hiddenTokens", d.hiddenTokens) { s -> s.split('\u0001').filter { it.isNotBlank() }.toSet() },
                banner = d.banner.let { b -> BannerStyle(
                    enabled = e("bnOn", b.enabled) { it.toBooleanStrict() },
                    opacity = e("bnOpacity", b.opacity) { it.toFloat().coerceIn(0.03f, 1f) },
                    heightDp = e("bannerH", b.heightDp) { it.toInt().coerceIn(120, 420) },
                    scale = e("bnScale", b.scale) { it.toFloat().coerceIn(0.5f, 3f) },
                    offsetX = e("bnX", b.offsetX) { it.toFloat().coerceIn(-1f, 1f) },
                    offsetY = e("bnY", b.offsetY) { it.toFloat().coerceIn(-1f, 1f) },
                    blurDp = e("bnBlur", b.blurDp) { it.toFloat().coerceIn(0f, 24f) },
                    cornerDp = e("bnCorner", b.cornerDp) { it.toFloat().coerceIn(0f, 40f) },
                    fit = e("bnFit", b.fit) { BannerFit.valueOf(it) },
                    speed = e("bnSpeed", b.speed) { it.toFloat().coerceIn(0f, 3f) },
                    fade = e("bnFade", b.fade) { it.toBooleanStrict() },
                    hideOverlays = e("bnHideText", b.hideOverlays) { it.toBooleanStrict() },
                ) },
                liveBalance = e("live", d.liveBalance) { it.toBooleanStrict() },
                useEstimatedCost = e("estCost", d.useEstimatedCost) { it.toBooleanStrict() },
                investedTotal = e("invTotal", d.investedTotal) { it.takeIf { v -> v.isEmpty() || v.toBigDecimalOrNull() != null } },
                investedCurrency = e("invCur", d.investedCurrency) { Currency.valueOf(it) },
                investedSince = e("invSince", d.investedSince) { it.toLong() },
                upscaleMedia = e("upscale", d.upscaleMedia) { it.toBooleanStrict() },
                goalTarget = e("goalTarget", d.goalTarget) { it.takeIf { v -> v.isEmpty() || v.toBigDecimalOrNull() != null } },
                goalCurrency = e("goalCur", d.goalCurrency) { Currency.valueOf(it) },
                lowPower = e("lowPower", d.lowPower) { it.toBooleanStrict() },
                anim = d.anim.let { a -> AnimPrefs(
                    number = e("anNum", a.number) { NumberAnim.valueOf(it) },
                    numberMs = e("anMs", a.numberMs) { it.toInt().coerceIn(AnimPrefs.MIN_MS, AnimPrefs.MAX_MS) },
                    curve = e("anCurve", a.curve) { AnimCurve.valueOf(it) },
                    screen = e("anScreen", a.screen) { ScreenAnim.valueOf(it) },
                    screenSpeed = e("anScreenSpd", a.screenSpeed) { it.toFloat().takeIf { v -> !v.isNaN() }?.coerceIn(0.5f, 2f) },
                    chart = e("anChart", a.chart) { it.toBooleanStrict() },
                    chartSpeed = e("anChartSpd", a.chartSpeed) { it.toFloat().takeIf { v -> !v.isNaN() }?.coerceIn(0.5f, 2f) },
                    listAppear = e("anList", a.listAppear) { it.toBooleanStrict() },
                    haptics = e("anHaptic", a.haptics) { it.toBooleanStrict() },
                    speed = e("anSpeed", a.speed) { it.toFloat().takeIf { v -> !v.isNaN() }?.coerceIn(0.5f, 2f) },
                ) },
                roll = d.roll.let { r -> BannerRoll(
                    enabled = e("bnRollOn", r.enabled) { it.toBooleanStrict() },
                    items = RollRules.decode(m["bnRoll"]),
                    gifsOn = e("bnRollGif", r.gifsOn) { it.toBooleanStrict() },
                    photosOn = e("bnRollPhoto", r.photosOn) { it.toBooleanStrict() },
                    videosOn = e("bnRollVideo", r.videosOn) { it.toBooleanStrict() },
                    migrated = e("bnRollMig", r.migrated) { it.toBooleanStrict() },
                    selected = m["bnRollSel"]?.take(64) ?: "",
                ) },
                videoCleanNote = m["bnVidClean"]?.take(64) ?: "",
                web = WebHostPrefs().let { w -> WebHostPrefs(
                    port = e("whPort", w.port) { it.toInt() }.takeIf { it in 1024..65535 } ?: w.port,
                    sessionHours = e("whSessH", w.sessionHours) { it.toInt() }.takeIf { it in WebHostPrefs.SESSION_CHOICES } ?: w.sessionHours,
                    requireApproval = e("whApprove", w.requireApproval) { it.toBooleanStrict() },
                    bindToIp = e("whBindIp", w.bindToIp) { it.toBooleanStrict() },
                    autoStopMin = e("whIdle", w.autoStopMin) { it.toInt() }.takeIf { it in WebHostPrefs.IDLE_CHOICES } ?: w.autoStopMin,
                    allowTailscale = e("whTs", w.allowTailscale) { it.toBooleanStrict() },
                    mdns = e("whMdns", w.mdns) { it.toBooleanStrict() },
                ) },
            )
        }
    }
}

/** v6.0: preferências do Web Host (entram no backup; aparelhos pareados e sessões não). */
data class WebHostPrefs(
    val port: Int = 8787,
    val sessionHours: Int = 24,
    val requireApproval: Boolean = true,
    val bindToIp: Boolean = true,
    /** Desliga sozinho após N minutos sem nenhum acesso (0 = nunca). */
    val autoStopMin: Int = 60,
    val allowTailscale: Boolean = false,
    val mdns: Boolean = true,
) {
    companion object {
        val SESSION_CHOICES = listOf(1, 8, 24, 168)
        val IDLE_CHOICES = listOf(0, 15, 60, 240)
    }
}
