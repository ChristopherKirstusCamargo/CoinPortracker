package com.coinportracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.ui.components.trackPickerAnchor
import com.coinportracker.R
import com.coinportracker.core.Fmt
import com.coinportracker.data.ChartRange
import com.coinportracker.data.HubState
import com.coinportracker.domain.Currency
import com.coinportracker.ui.ChartData
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.Refreshable
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.staggeredEntrance
import com.coinportracker.ui.theme.parallax
import com.coinportracker.ui.theme.reveal
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hourFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm", Fmt.LOCALE)

@Composable
fun HomeScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    // v5.1.0: com a Rolagem de banner ativa, o banner único não é decodificado
    val rollOn = h.settings.roll.active
    val media = rememberBannerMedia(if (rollOn) "" else com.coinportracker.ui.components.rememberHdSource(h.settings.bannerUri.ifBlank { DEFAULT_BANNER }, h.settings.upscaleMedia), h.settings.banner.speed)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.coinportracker.ui.ScrollToTopOnReselect("home", listState)

    var showGoal by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Refreshable(refreshing, { vm.fullSync(true) }) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "hero", contentType = "hero") {
                Box {
                    Hero(h, media, sync, vm, nav) { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f }
                    // indicador discreto enquanto a cópia HD do banner é gerada
                    // v5.1.3: mesma fonte da Início (com a Rolagem, a mídia escolhida)
                    val hdCtx = androidx.compose.ui.platform.LocalContext.current
                    val hdSrc = remember(h.settings.roll, h.settings.bannerUri) { com.coinportracker.media.BannerLibrary.source(hdCtx, h.settings) }
                    if (h.settings.roll.current?.kind != com.coinportracker.settings.RollKind.VIDEO || !rollOn) com.coinportracker.ui.components.HdBadge(com.coinportracker.ui.components.rememberHdStatus(hdSrc, h.settings.upscaleMedia),
                        Modifier.align(Alignment.BottomEnd).padding(12.dp))
                }
            }
            item(key = "actions", contentType = "actions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionButton(Icons.Outlined.South, "Receber", { nav.navigate("receive") }, Modifier.weight(1f), compact = true)
                    ActionButton(Icons.Outlined.ReceiptLong, "Contábil", { nav.navigate("contabil") }, Modifier.weight(1f), compact = true)
                    ActionButton(Icons.Outlined.ShowChart, "Mercado", { nav.navigate("market") }, Modifier.weight(1f), compact = true)
                    ActionButton(Icons.Outlined.AccountBalanceWallet, "Carteiras", { nav.navigate("wallets2") }, Modifier.weight(1f), compact = true)
                }
            }
            if (!h.ready) {
                item(key = "sk") { Column(Modifier.padding(16.dp)) { ShimmerBlock(Modifier.fillMaxWidth().height(180.dp), 20.dp); Spacer(Modifier.height(16.dp)); AssetSkeleton() } }
                return@LazyColumn
            }
            if (h.wallets.isEmpty() && h.agg.assets.isEmpty()) {
                item(key = "empty") { EmptyHome(nav) }
                return@LazyColumn
            }
            item(key = "chart", contentType = "chart") {
                Box(Modifier.staggeredEntrance(0)) { ChartCard(h, chart, range, vm::setRange) }
            }
            item(key = "pnl", contentType = "pnl") { Box(Modifier.staggeredEntrance(1)) { PnlCard(h) { nav.navigate("pnl") } } }
            val movers = h.agg.assets.filter { it.change24h != null && it.valueUsd >= 1.0 }
            if (movers.size >= 2) item(key = "movers", contentType = "movers") {
                val best = movers.maxBy { it.change24h!! }; val worst = movers.minBy { it.change24h!! }
                Row(Modifier.staggeredEntrance(2).padding(horizontal = 16.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoverCard("Maior alta 24h", best, h, Modifier.weight(1f)) { nav.navigate("asset2/${android.net.Uri.encode(best.key)}") }
                    MoverCard("Maior queda 24h", worst, h, Modifier.weight(1f)) { nav.navigate("asset2/${android.net.Uri.encode(worst.key)}") }
                }
            }
            item(key = "assetsTitle") {
                SectionTitle("Ativos", Modifier.padding(horizontal = 16.dp), "Ver todos (${h.agg.assets.size})") { com.coinportracker.ui.TabNav.switchTab(nav, "assets") }
            }
            items(h.agg.assets.take(8), key = { it.key }, contentType = { "asset" }) { a ->
                Box(Modifier.animateItem(fadeInSpec = com.coinportracker.ui.theme.Motion.fade(), placementSpec = com.coinportracker.ui.theme.Motion.itemPlacement, fadeOutSpec = com.coinportracker.ui.theme.Motion.fade(150)).reveal().padding(horizontal = 8.dp)) { AssetRow(h, a, h.pnl.assets[a.key], RowMetric.CHANGE_24H, { nav.navigate("asset2/${android.net.Uri.encode(a.key)}") }) }
            }
            if (h.agg.unpricedCount > 0 || h.agg.hidden.isNotEmpty()) item(key = "hiddenNote") {
                Text(if (h.agg.hidden.size == 1) "1 token oculto" else "${h.agg.hidden.size} tokens ocultos",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
    }
        if (h.settings.goal != null && h.ready) {
            GoalRing(h, { showGoal = true }, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp, 16.dp, 16.dp, 16.dp))
        }
        if (showGoal) GoalDialog(h, { showGoal = false }, { showGoal = false; nav.navigate("settings/metas") })
    }
}

@Composable
private fun Hero(h: HubState, media: BannerMedia?, sync: com.coinportracker.data.SyncStatus, vm: com.coinportracker.ui.UiModel, nav: NavHostController, scrollPx: () -> Float) {
    val bg = MaterialTheme.colorScheme.background
    val style = h.settings.banner
    val clean = style.hideOverlays && style.enabled
    val roll = h.settings.roll
    // v5.1.1: um toque no banner abre o seletor (o toque duplo no saldo continua ocultando/mostrando)
    val canPick = roll.active && style.enabled
    var picker by remember { mutableStateOf(false) }
    val openPicker: () -> Unit = { if (canPick && !picker) picker = true }
    // v5.1.3: posição do banner e do último toque (na tela) para o seletor nascer do dedo e a mídia escolhida voar até o banner
    val anchor = remember { com.coinportracker.ui.components.PickerAnchor() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(canPick, roll.items) { if (canPick) { kotlinx.coroutines.delay(1200); com.coinportracker.media.BannerLibrary.prefetch(ctx, roll.carousel) } }
    Box(Modifier.fillMaxWidth().heightIn(min = style.heightDp.dp)
        .then(if (canPick) Modifier.trackPickerAnchor(anchor) else Modifier)
        .pointerInput(canPick) { if (canPick) detectTapGestures(onTap = { openPicker() }) }) {
        if (roll.active) {
            com.coinportracker.ui.components.RollBanner(roll.current, style, bg, Modifier.matchParentSize().parallax(scrollPx), upscale = h.settings.upscaleMedia)
        } else BannerBackdrop(media, style, bg, Modifier.matchParentSize().parallax(scrollPx))
        if (picker) com.coinportracker.ui.components.BannerPicker(roll, anchor,
            onPick = { item -> vm.updateSettings { it.copy(roll = it.roll.copy(selected = item.id)) } },
            onDismiss = { picker = false })
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp)) {
            // barra superior
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!clean) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF0B0B0B)), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.requiredSize(52.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("CoinPortracker", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(statusLine(h, sync), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    CurrencyToggle(h.currency) { c -> vm.updateSettings { it.copy(baseCurrency = c) } }
                } else Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(if (clean) 34.dp else 22.dp))
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val toggleHide = { if (com.coinportracker.ui.theme.Motion.haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.updateSettings { it.copy(hideValues = !it.hideValues) } }
            Column(Modifier
                .semantics { onClick(if (h.hide) "Mostrar saldo" else "Ocultar saldo") { toggleHide(); true } }
                .pointerInput(canPick) { detectTapGestures(onDoubleTap = { toggleHide() }, onTap = if (canPick) { _ -> openPicker() } else null) }) {
                if (!clean) {
                    Text("Patrimônio total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                if (!h.ready) ShimmerBlock(Modifier.size(220.dp, 48.dp))
                else LiveTotal(h)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chg = h.agg.change24hUsd
                ChangePill(pctOf(h.agg.change24hPct), upOf(h.agg.change24hPct))
                Spacer(Modifier.width(8.dp))
                FitText("${if (h.hide) Fmt.MASK else Fmt.signedMoney(h.conv(chg), h.currency)} em 24 h", style = MaterialTheme.typography.labelLarge, color = changeColor(upOf(chg)), modifier = Modifier.weight(1f, fill = false), minScale = 0.75f)
                if (h.settings.liveBalance) { Spacer(Modifier.width(8.dp)); LiveDot(sync.lastPriceSync) }
            }
            Spacer(Modifier.height(6.dp))
            val pnl = h.pnl
            val hasPnl = pnl.openCost.signum() != 0 || pnl.realized.signum() != 0 || pnl.income.signum() != 0
            val real = remember(h) { h.realPnl(null) }
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.navigate("pnl") }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (real != null) {
                    Text("Resultado real  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    FitText("${h.investedMoney(real.result, signed = true)} (${pctOf(real.resultPct)})", style = MaterialTheme.typography.labelLarge, color = changeColor(upOf(real.result)), modifier = Modifier.weight(1f, fill = false), minScale = 0.7f)
                } else if (hasPnl) {
                    Text("Lucro total  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    FitText("${h.signed(pnl.total)}${pnl.totalPct?.let { " (" + Fmt.pct(it) + ")" } ?: ""}", style = MaterialTheme.typography.labelLarge, color = changeColor(upOf(pnl.total)), modifier = Modifier.weight(1f, fill = false), minScale = 0.7f)
                } else Text("Ver lucro e prejuízo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/**
 * Saldo do topo. Isolado num escopo próprio: o tique de 1 s do saldo ao vivo recompõe só este número
 * (antes recompunha o topo inteiro: banner, barra, linhas de resultado).
 */
@Composable
private fun LiveTotal(h: HubState) {
    val total = com.coinportracker.ui.rememberLiveTotal(h.agg.totalUsd, h.settings.liveBalance && h.ready && !h.hide)
    val text = h.money(total)
    androidx.compose.animation.Crossfade(h.hide, animationSpec = androidx.compose.animation.core.tween(220), label = "hide") { hidden ->
        if (hidden) Text(text, style = MaterialTheme.typography.displayLarge, maxLines = 1)
        else SlotText(text, MaterialTheme.typography.displayLarge, replayKey = h.currency)
    }
}

/** Estado da cotação. Ponto parado: piscar o tempo todo sugeriria um dado que não chegou. */
@Composable
private fun LiveDot(lastPrice: Long?) {
    val x = LocalExtra.current
    val age = lastPrice?.let { System.currentTimeMillis() - it }
    val fresh = age != null && age < 25_000L
    val stale = age == null || age > 15 * 60_000L
    val color = when {
        fresh -> x.gain
        stale -> x.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        fresh -> "preços ao vivo"
        stale -> "preços antigos"
        else -> "preços em cache"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

internal fun statusLine(h: HubState, sync: com.coinportracker.data.SyncStatus, now: Long = System.currentTimeMillis()): String {
    if (sync.syncing) return "Atualizando saldos…"
    if (sync.pricing) return "Atualizando cotações…"
    val w = h.wallets.size
    if (w == 0) return "Nenhuma carteira"
    val base = if (w == 1) "1 carteira" else "$w carteiras"
    if (!sync.error.isNullOrBlank()) return sync.error
    val age = sync.lastBalanceSync?.let { "saldos " + Fmt.ago(it, now, precise = true) }
    return when {
        sync.partial && age != null -> "Parte das redes não respondeu · $age"
        sync.partial -> "Parte das redes não respondeu"
        age != null -> "$base · $age"
        else -> base
    }
}

@Composable
private fun CurrencyToggle(c: Currency, onChange: (Currency) -> Unit) {
    val x = LocalExtra.current
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(x.cardHigh).padding(2.dp)) {
        Currency.entries.forEach { o ->
            val sel = o == c
            Text(if (o == Currency.BRL) "R$" else "US$", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { if (!sel) onChange(o) }.padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Fmt.LOCALE)

/**
 * Evolução do portfólio (v3.2). Dois conceitos separados:
 *  - principal (linha tracejada) = dinheiro que saiu do bolso; em "Tudo", termina exatamente no valor pago informado;
 *  - resultado = valor acima/abaixo do principal (área verde/vermelha).
 * Nos períodos 1D/1S/1M/1A o destaque é só o DESEMPENHO DE MERCADO (efeito preço + rendimentos, % ponderado no tempo):
 * aportes e carteiras recém-sincronizadas nunca viram lucro ou prejuízo. "Tudo" bate com o Resultado real do topo.
 */
@Composable
fun ChartCard(h: HubState, chart: ChartData?, range: ChartRange, onRange: (ChartRange) -> Unit) {
    var scrub by remember { mutableStateOf<Int?>(null) }
    val x = LocalExtra.current
    CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), padding = PaddingValues(0.dp)) {
        val data = chart?.takeIf { it.range == range }
        val pts = remember(h, data) { h.chartPoints(data) }
        val res = data?.result
        val invUsd = h.investedUsd()
        // Com total pago informado, o principal é uma reta nesse total (sem degraus de transferência)
        // e o destaque é o Resultado real em qualquer período, para não contradizer a linha.
        val real = remember(h, data) { if (invUsd != null) h.realPnlFor(data) else null }
        val base: List<Double>? = remember(pts, invUsd, res) {
            if (pts == null || pts.isEmpty()) return@remember null
            if (invUsd != null) List(pts.size) { invUsd } else res?.principalSeries?.takeIf { it.size == pts.size && it.isNotEmpty() }
        }
        val first = pts?.firstOrNull()?.usd; val last = pts?.lastOrNull()?.usd
        val headline: String?; val headUp: Boolean?; val headPct: Double?
        if (real != null) { headline = h.investedMoney(real.result, signed = true); headUp = upOf(real.result); headPct = real.resultPct }
        else {
            val pnl = res?.pnlUsd ?: if (first != null && last != null) last - first else null
            headline = pnl?.let { if (h.hide) Fmt.MASK else Fmt.signedMoney(h.conv(it), h.currency) }; headUp = upOf(pnl)
            headPct = res?.pnlPct ?: if (first != null && first > 0 && pnl != null) pnl / first * 100 else null
        }
        val sp = scrub?.let { pts?.getOrNull(it) }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(16.dp, 12.dp, 16.dp, 0.dp), verticalAlignment = Alignment.Top) {
            if (sp != null) {
                val b = scrub?.let { base?.getOrNull(it) }
                Column(Modifier.weight(1f)) {
                    Text(Instant.ofEpochMilli(sp.t).atZone(ZoneId.systemDefault()).format(if (range == ChartRange.D1 || range == ChartRange.W1) hourFmt else dayFmt) +
                        (b?.let { " · investido " + h.money(it) } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    FitText(h.money(sp.usd), style = MaterialTheme.typography.titleLarge)
                }
                val ref = b ?: first
                if (ref != null && ref > 0) { val d = sp.usd - ref; ChangePill(pctOf(d / ref * 100), upOf(d)) }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(if (real != null) "Resultado" else "Mercado ${range.periodLabel()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    androidx.compose.animation.AnimatedContent(headline ?: "—", transitionSpec = { com.coinportracker.ui.theme.Motion.fadeThrough() }, label = "head") { t ->
                        FitText(t, style = MaterialTheme.typography.titleLarge, color = if (headline == null) MaterialTheme.colorScheme.onSurface else changeColor(headUp))
                    }
                }
                if (headPct != null) ChangePill(pctOf(headPct), headUp)
            }
        }
        val color = changeColor(headUp ?: true)
        Box(Modifier.fillMaxWidth().height(176.dp).padding(top = 6.dp)) {
            if (pts == null) ShimmerBlock(Modifier.fillMaxSize().padding(16.dp), 12.dp)
            else if (pts.size < 2) Text("Histórico em construção.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            else {
                val xs = remember(pts) { LongArray(pts.size) { pts[it].t } }
                val ys = remember(pts) { FloatArray(pts.size) { pts[it].usd.toFloat() } }
                val bs = remember(base) { base?.let { b -> FloatArray(b.size) { b[it].toFloat() } } }
                PnlChart(xs, ys, bs, color, x.gain, x.loss, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.fillMaxSize()) { scrub = it }
            }
        }
        if (pts != null && pts.size >= 2) {
            val parts = buildList {
                if (real != null) {
                    add("Investido " + h.investedMoney(real.invested))
                    add("Valor atual " + h.investedMoney(real.value))
                    if (real.income > 0.005) add("Rendimentos de staking " + h.investedMoney(real.income) + " fora do resultado")
                } else if (res != null) {
                    if (kotlin.math.abs(res.netFlowsUsd) >= 0.5) add((if (res.netFlowsUsd >= 0) "Aportes " else "Retiradas ") + h.money(kotlin.math.abs(res.netFlowsUsd)) + " fora do resultado")
                    if (res.incomeUsd >= 0.01) add("Rendimentos de staking " + h.money(res.incomeUsd) + " fora do resultado")
                }
                if (res?.partial == true) add(res.reliableFrom?.let { "Histórico desde " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dayFmt) } ?: "Histórico parcial em algumas redes")
                if (res == null) data?.source?.let { add(it) }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        RangeChips(ChartRange.entries, range, { it.label }, onRange, Modifier.padding(12.dp))
    }
}

internal fun ChartRange.periodLabel(): String = when (this) {
    ChartRange.D1 -> "em 24 h"; ChartRange.W1 -> "em 7 dias"; ChartRange.M1 -> "em 30 dias"; ChartRange.Y1 -> "em 1 ano"; else -> "desde o início"
}

@Composable
private fun PnlCard(h: HubState, onOpen: () -> Unit) {
    val p = h.pnl
    val real = remember(h) { h.realPnl(null) }
    CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), onClick = onOpen) {
        if (real != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Resultado real", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FitText(h.investedMoney(real.result, signed = true), style = MaterialTheme.typography.titleLarge, color = changeColor(upOf(real.result)), modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp)); ChangePill(pctOf(real.resultPct), upOf(real.result))
                    }
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            KeyValue("Investido (do seu bolso)", h.investedMoney(real.invested))
            KeyValue("Valor atual", h.investedMoney(real.value))
            if (real.lossOnPrincipal > 0.005) KeyValue("Perda sobre o aporte", h.investedMoney(-real.lossOnPrincipal, signed = true), changeColor(false))
            if (real.income > 0.005) KeyValue("Rendimentos de staking", h.investedMoney(real.income, signed = true), changeColor(true))
            if (real.breakEvenPct > 0) KeyValue("Alta para empatar", "+" + Fmt.num(real.breakEvenPct, 1) + "%")
            return@CpCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lucro e prejuízo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(h.signed(p.total), style = MaterialTheme.typography.titleLarge, color = changeColor(upOf(p.total)))
                    p.totalPct?.let { Spacer(Modifier.width(8.dp)); ChangePill(Fmt.pct(it), upOf(p.total)) }
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        KeyValue("Não realizado", h.signed(p.unrealized), changeColor(upOf(p.unrealized)))
        KeyValue("Realizado", h.signed(p.realized), changeColor(upOf(p.realized)))
        KeyValue("Rendimentos", h.signed(p.income), changeColor(upOf(p.income)))
        Spacer(Modifier.height(10.dp))
        Text("Informe o valor pago para ver o resultado real", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MoverCard(title: String, a: com.coinportracker.onchain.AssetGroup, h: HubState, modifier: Modifier, onClick: () -> Unit) {
    CpCard(modifier, onClick = onClick, padding = PaddingValues(14.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(a.iconUrl, a.symbol, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(a.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        ChangePill(pctOf(a.change24h), upOf(a.change24h))
    }
}

@Composable
private fun EmptyHome(nav: NavHostController) {
    CpCard(Modifier.padding(16.dp)) {
        Icon(Icons.Outlined.Link, null, Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text("Conecte suas carteiras pela blockchain", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("Endereço público EVM (0x…), Solana ou Bitcoin.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { nav.navigate("addWallet") }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Adicionar endereço") }
    }
}
