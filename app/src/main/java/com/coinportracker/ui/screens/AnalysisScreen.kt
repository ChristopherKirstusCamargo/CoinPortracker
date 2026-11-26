package com.coinportracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.data.HubState
import com.coinportracker.onchain.Network
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import java.math.BigDecimal

enum class AllocBy(val label: String) { ASSET("Ativo"), NETWORK("Rede"), WALLET("Carteira") }

private data class AllocItem(val key: String, val label: String, val value: Double, val share: Double, val pnl: BigDecimal?, val iconUrl: String? = null, val network: Network? = null)

/** Tons de cinza para a rosca (o app é preto e branco; verde/vermelho só para ganho/perda). */
private val greys = listOf(Color(0xFFF2F2F2), Color(0xFFBDBDBD), Color(0xFF8E8E8E), Color(0xFF6A6A6A), Color(0xFF4D4D4D), Color(0xFFDADADA), Color(0xFFA6A6A6), Color(0xFF7A7A7A), Color(0xFF5C5C5C), Color(0xFF3D3D3D))
private val greysLight = listOf(Color(0xFF111111), Color(0xFF3A3A3A), Color(0xFF5E5E5E), Color(0xFF828282), Color(0xFFA5A5A5), Color(0xFF262626), Color(0xFF4C4C4C), Color(0xFF707070), Color(0xFF959595), Color(0xFFB8B8B8))

@Composable
fun AnalysisScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    var by by rememberSaveable { mutableStateOf(AllocBy.ASSET) }
    var sel by remember(by) { mutableIntStateOf(-1) }
    val items = remember(h, by) { alloc(h, by) }
    val palette = if (LocalExtra.current.isDark) greys else greysLight

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.coinportracker.ui.ScrollToTopOnReselect("analysis", listState)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "top") { V2TopBar("Análise", if (nav.previousBackStackEntry != null && nav.currentDestination?.route != "analysis") ({ nav.popBackStack() }) else null) }
        item(key = "pnl") { PnlHero(h) { nav.navigate("pnl") } }
        item(key = "allocTitle") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Alocação", Modifier.weight(1f))
                IconButton(onClick = { nav.navigate("share") }) { Icon(Icons.Outlined.IosShare, "Compartilhar portfólio") }
            }
        }
        item(key = "alloc") {
            CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                Segments(AllocBy.entries, by, { it.label }, { by = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    val shares = remember(items) { FloatArray(items.size) { items[it].share.toFloat() } }
                    Donut(shares, palette, sel, Modifier.size(220.dp), LocalExtra.current.cardHigh)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val it = items.getOrNull(sel)
                        Text(it?.label ?: "Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                        FitText(h.compact(it?.value ?: h.agg.totalUsd), style = MaterialTheme.typography.titleLarge)
                        if (it != null) Text(Fmt.pct(BigDecimal.valueOf(it.share * 100), false), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                items.forEachIndexed { i, a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { sel = if (sel == i) -1 else i }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(palette[i % palette.size]); Spacer(Modifier.width(10.dp))
                        Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // valor do item (antes: o P&L do rateio, que aparecia "R$ 0,00" quando o resultado está no zero a zero)
                        Text(h.money(a.value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 10.dp))
                        Text(Fmt.pct(BigDecimal.valueOf(a.share * 100), false), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                if (items.isEmpty()) EmptyLine("Sem ativos com valor.")
            }
        }
        val ranked = h.agg.assets.mapNotNull { a -> h.pnl.assets[a.key]?.takeIf { it.pnlPct != null }?.let { a to it } }
        if (ranked.isNotEmpty()) {
            item(key = "perfTitle") { SectionTitle("Melhores e piores (desde a compra)", Modifier.padding(horizontal = 20.dp)) }
            item(key = "perf") {
                CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), padding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)) {
                    val sorted = ranked.sortedByDescending { it.second.pnlPct }
                    val show = if (sorted.size <= 6) sorted else sorted.take(3) + sorted.takeLast(3)
                    show.forEach { (a, r) -> AssetRow(h, a, r, RowMetric.PNL, { nav.navigate("asset2/${android.net.Uri.encode(a.key)}") }, spinFirst = false) }
                }
            }
        }
        if (h.agg.staking.isNotEmpty() || h.stakingIncomeUsd.signum() != 0 || h.stakes.isNotEmpty()) {
            item(key = "stakeTitle") { SectionTitle("Staking", Modifier.padding(horizontal = 20.dp)) }
            item(key = "stake") {
                CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                    val total = h.agg.staking.sumOf { it.valueUsd ?: 0.0 }
                    Row {
                        Stat("Em staking", h.money(total), Modifier.weight(1f), sub = if (h.agg.totalUsd > 0) Fmt.pct(BigDecimal.valueOf(total / h.agg.totalUsd * 100), false) + " do total" else null)
                        val onchain = h.stakes.sumOf { it.rewardsUsd ?: 0.0 }
                        val rew = BigDecimal.valueOf(onchain).add(h.stakingIncomeUsd)
                        Stat("Recompensas", h.signed(rew), Modifier.weight(1f), changeColor(upOf(rew)), sub = if (h.stakes.isNotEmpty() && !h.hide) "+" + Fmt.num(h.stakes.sumOf { it.rewardsSol }, 4) + " SOL" else null)
                    }
                    Spacer(Modifier.height(8.dp))
                    h.agg.staking.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TokenIcon(p.holding.iconUrl, p.holding.symbol, 32.dp, p.holding.network, true)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${p.holding.symbol} em staking", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.source.walletName} · ${p.holding.network.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FitText(h.money(p.valueUsd), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PnlHero(h: HubState, onOpen: () -> Unit) {
    val real = remember(h) { h.realPnl(null) }
    val p = h.pnl
    CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), onClick = onOpen) {
        if (real != null) {
            Text("Resultado", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (h.hide) Text(h.investedMoney(real.result, signed = true), style = MaterialTheme.typography.displaySmall, color = changeColor(upOf(real.result)), maxLines = 1)
            else SlotText(h.investedMoney(real.result, signed = true), MaterialTheme.typography.displaySmall, color = changeColor(upOf(real.result)), replayKey = h.settings.investedCurrency.name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangePill(pctOf(real.resultPct), upOf(real.result))
                Spacer(Modifier.width(8.dp))
                FitText("sobre ${h.investedMoney(real.invested)} investidos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false), minScale = 0.8f)
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Stat("Investido", h.investedMoney(real.invested), Modifier.weight(1f))
                Stat("Valor atual", h.investedMoney(real.value), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Stat(if (real.inProfit) "Lucro" else "Perda", h.investedMoney(if (real.inProfit) real.result else -real.lossOnPrincipal, signed = true), Modifier.weight(1f), changeColor(real.inProfit))
                Stat("Variação 24h", if (h.hide) Fmt.MASK else Fmt.signedMoney(h.conv(h.agg.change24hUsd), h.currency), Modifier.weight(1f), changeColor(upOf(h.agg.change24hUsd)), sub = pctOf(h.agg.change24hPct))
            }
            if (real.income > 0.005) {
                Spacer(Modifier.height(12.dp))
                KeyValue("Rendimentos de staking", h.investedMoney(real.income, signed = true), changeColor(true))
            }
            return@CpCard
        }
        Text("Lucro e prejuízo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (h.hide) Text(h.signed(p.total), style = MaterialTheme.typography.displaySmall, color = changeColor(upOf(p.total)), maxLines = 1)
        else SlotText(h.signed(p.total), MaterialTheme.typography.displaySmall, color = changeColor(upOf(p.total)), replayKey = h.currency)
        Spacer(Modifier.height(8.dp))
        Text("Informe o valor pago para ver o resultado real", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row {
            Stat("Patrimônio", h.money(h.agg.totalUsd), Modifier.weight(1f))
            Stat("Variação 24h", if (h.hide) Fmt.MASK else Fmt.signedMoney(h.conv(h.agg.change24hUsd), h.currency), Modifier.weight(1f), changeColor(upOf(h.agg.change24hUsd)), sub = pctOf(h.agg.change24hPct))
        }
    }
}

private fun alloc(h: HubState, by: AllocBy): List<AllocItem> {
    val total = h.agg.totalUsd.takeIf { it > 0 } ?: return emptyList()
    val raw = when (by) {
        AllocBy.ASSET -> h.agg.assets.map { AllocItem(it.key, if (it.key.startsWith("staked:")) "${it.symbol} (stake)" else it.symbol, it.valueUsd, it.valueUsd / total, h.pnl.assets[it.key]?.pnl, it.iconUrl) }
        AllocBy.NETWORK -> h.agg.byNetwork.map { s -> AllocItem(s.key, s.label, s.valueUsd, s.share, h.pnl.byNetwork[s.key]?.takeIf { it.cost.signum() > 0 }?.pnl, network = Network.byName(s.key)) }
        AllocBy.WALLET -> h.agg.byWallet.map { s -> AllocItem(s.key, s.label, s.valueUsd, s.share, h.pnl.byWallet[s.key]?.takeIf { it.cost.signum() > 0 }?.pnl) }
    }.filter { it.value > 0 }.sortedByDescending { it.value }
    if (raw.size <= 8) return raw
    val rest = raw.drop(7)
    return raw.take(7) + AllocItem("others", "Outros (${rest.size})", rest.sumOf { it.value }, rest.sumOf { it.share }, null)
}
