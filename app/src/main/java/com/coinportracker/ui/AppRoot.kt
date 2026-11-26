package com.coinportracker.ui

import androidx.compose.foundation.selection.selectable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.coinportracker.ui.screens.*
import com.coinportracker.ui.theme.Motion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer

enum class Tab(val route: String, val label: String, val icon: ImageVector, val iconSel: ImageVector) {
    HOME("home", "Início", Icons.Outlined.Home, Icons.Filled.Home),
    ASSETS("assets", "Ativos", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    ANALYSIS("analysis", "Análise", Icons.Outlined.DonutLarge, Icons.Filled.DonutLarge),
    ACTIVITY("activity", "Atividade", Icons.Outlined.ReceiptLong, Icons.Filled.ReceiptLong),
    SETTINGS("settings", "Ajustes", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val msg by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it, withDismissAction = true); vm.toast(null) } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (Tab.entries.any { it.route == route }) BottomBar(nav, route) },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) { AppNav(nav, vm) }
    }
}

/**
 * Cápsula flutuante. O Scaffold reserva a altura dela (e o inset de gestos), então a lista
 * termina acima da pílula. O fundo é translúcido com borda fina — sem blur, para o rótulo continuar nítido.
 */
@Composable
internal fun BottomBar(nav: NavHostController, route: String?) {
    val x = com.coinportracker.ui.theme.LocalExtra.current
    val reduced = com.coinportracker.ui.theme.LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(32.dp)
    val dark = x.isDark
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(64.dp)
                .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.40f))
                .clip(shape)
                .background(if (dark) Color(0xE6121212) else x.card.copy(alpha = 0.94f))
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.09f else 0.45f), Color.Transparent), endY = size.height * 0.5f))
                }
                .border(1.dp, if (dark) Color.White.copy(alpha = 0.14f) else x.cardBorder, shape),
        ) {
            val tabs = Tab.entries
            val selected = tabs.indexOfFirst { it.route == route }.let { if (it < 0) 0 else it }
            BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                val tabW = maxWidth / tabs.size
                val spec = if (reduced) snap<androidx.compose.ui.unit.Dp>() else Motion.spatial<androidx.compose.ui.unit.Dp>()
                val offset by animateDpAsState(tabW * selected, spec, label = "pill")
                Box(
                    Modifier.offset(x = offset).width(tabW).fillMaxHeight()
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)),
                )
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { t ->
                        val sel = route == t.route
                        val color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        Column(
                            Modifier.weight(1f).fillMaxHeight().selectable(
                                selected = sel,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = ripple(bounded = false, radius = 28.dp),
                                role = androidx.compose.ui.semantics.Role.Tab,
                            ) {
                                if (Motion.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                TabNav.switchTab(nav, t.route)
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            val sc by animateFloatAsState(if (sel) 1.06f else 1f, Motion.press(), label = "tab")
                            Icon(if (sel) t.iconSel else t.icon, t.label, Modifier.size(22.dp).graphicsLayer { scaleX = sc; scaleY = sc }, tint = color)
                            Spacer(Modifier.height(2.dp))
                            com.coinportracker.ui.components.FitText(t.label, style = MaterialTheme.typography.labelSmall, color = color, minScale = 0.8f)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNav(nav: NavHostController, vm: MainViewModel) {
    val reduced = com.coinportracker.ui.theme.LocalReducedMotion.current
    fun isTab(r: String?) = Tab.entries.any { it.route == r }
    androidx.compose.animation.SharedTransitionLayout {
        androidx.compose.runtime.CompositionLocalProvider(com.coinportracker.ui.theme.LocalSharedScope provides this) {
            NavHost(nav, startDestination = Tab.HOME.route,
                enterTransition = { if (reduced) EnterTransition.None else if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) Motion.tabEnter() else Motion.pushEnter(1) },
                exitTransition = { if (reduced) ExitTransition.None else if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) Motion.tabExit() else Motion.pushExit(1) },
                popEnterTransition = { if (reduced) EnterTransition.None else Motion.pushEnter(-1) },
                popExitTransition = { if (reduced) ExitTransition.None else Motion.pushExit(-1) }) {
                composable(Tab.HOME.route) { Shared(this) { HomeScreen(vm, nav) } }
                composable(Tab.ASSETS.route) { Shared(this) { HoldingsScreen(vm, nav) } }
                composable(Tab.ANALYSIS.route) { AnalysisScreen(vm, nav) }
                composable(Tab.ACTIVITY.route) { ActivityScreen(vm, nav) }
                composable(Tab.SETTINGS.route) { SettingsScreen(vm, nav) }
                composable("asset2/{key}", listOf(navArgument("key") { type = NavType.StringType })) { Shared(this) { AssetDetailV2(vm, nav, it.arguments!!.getString("key")!!) } }
                composable("wallets2") { WalletsV2Screen(vm, nav) }
                composable("addWallet") { AddWalletScreen(vm, nav) }
                composable("banner") { BannerSettingsScreen(vm, nav) }
                composable("bannerRoll") { com.coinportracker.ui.screens.BannerRollScreen(vm, nav) }
                composable("market") { MarketScreen(vm, nav) }
                composable("coin/{id}", listOf(navArgument("id") { type = NavType.StringType })) { MarketCoinScreen(vm, nav, it.arguments!!.getString("id")!!) }
                composable("settings/{cat}", listOf(navArgument("cat") { type = NavType.StringType })) { SettingsCategoryScreen(vm, nav, it.arguments!!.getString("cat")!!) }
                composable("share") { ShareScreen(vm, nav) }
                composable("webhost") { com.coinportracker.ui.screens.WebHostScreen(vm, nav) }
                composable("receive") { ReceiveScreen(vm, nav) }
                composable("trimVideo") { VideoTrimScreen(nav) }
                composable("dev") { DevSettingsScreen(nav) }
                composable("dev/{section}", listOf(navArgument("section") { type = NavType.StringType })) { DevSettingsScreen(nav, it.arguments!!.getString("section")) }
                composable("pnl") { Shared(this) { PnlScreen(vm, nav) } }
                composable("contabil") { AccountingScreen(vm, nav) }
            }
        }
    }
}

@Composable
private fun Shared(scope: androidx.compose.animation.AnimatedVisibilityScope, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(com.coinportracker.ui.theme.LocalNavScope provides scope, content = content)

/** Pull-to-refresh com indicador próprio: linha de luz que cresce com o gesto e varre enquanto atualiza (sem spinner). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, state = state, indicator = { GlowIndicator(state, refreshing) }) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.GlowIndicator(state: PullToRefreshState, refreshing: Boolean) {
    // a varredura infinita só existe enquanto atualiza: antes ela pedia um quadro a cada 16 ms em todas as telas,
    // mesmo paradas (custo de bateria e de agendamento de quadros sem nada para mostrar)
    if (refreshing) { GlowSweep(); return }
    val c = MaterialTheme.colorScheme.primary
    Canvas(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp)) {
        val frac = state.distanceFraction.coerceIn(0f, 1f)
        if (frac == 0f) return@Canvas
        val w = size.width * frac
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = 0.4f + 0.6f * frac), Color.Transparent), size.width / 2 - w / 2, size.width / 2 + w / 2),
            Offset(size.width / 2 - w / 2, size.height / 2), Offset(size.width / 2 + w / 2, size.height / 2), size.height, StrokeCap.Round)
    }
}

@Composable
private fun BoxScope.GlowSweep() {
    val c = MaterialTheme.colorScheme.primary
    val v = MaterialTheme.colorScheme.secondary
    val t = rememberInfiniteTransition(label = "sweep")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp)) {
        val w = size.width * 0.35f
        val x = -w + (size.width + w) * phase
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c, v, Color.Transparent), x, x + w), Offset(x, size.height / 2), Offset(x + w, size.height / 2), size.height, StrokeCap.Round)
    }
}
