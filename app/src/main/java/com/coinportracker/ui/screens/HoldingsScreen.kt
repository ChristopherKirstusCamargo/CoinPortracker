package com.coinportracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VisibilityOff
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
import com.coinportracker.onchain.AssetGroup
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.Visibility
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.Refreshable
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra

enum class HoldView(val label: String) { ASSETS("Ativos"), NETWORKS("Redes"), WALLETS("Carteiras"), HIDDEN("Ocultos") }
enum class HoldSort(val label: String) { VALUE("Valor"), PNL("Lucro"), PNL_PCT("Lucro %"), CHANGE("24h"), NAME("Nome") }

/** Filtro da lista: rede ("net:BASE") ou carteira ("w:12"). */
private fun AssetGroup.filteredBy(f: String?): AssetGroup? {
    if (f == null) return this
    val parts = when {
        f == "net:MANUAL" -> parts.filter { it.source.manual }
        f.startsWith("net:") -> parts.filter { !it.source.manual && it.holding.network.name == f.removePrefix("net:") }
        f.startsWith("w:") -> parts.filter { it.source.walletId.toString() == f.removePrefix("w:") }
        else -> parts
    }
    if (parts.isEmpty()) return null
    if (parts.size == this.parts.size) return this
    return copy(parts = parts, amount = parts.fold(java.math.BigDecimal.ZERO) { a, p -> a.add(p.holding.amount) }, valueUsd = parts.sumOf { it.valueUsd ?: 0.0 })
}

@Composable
fun HoldingsScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, previewView: HoldView = HoldView.ASSETS) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(previewView) }
    var sort by rememberSaveable { mutableStateOf(HoldSort.VALUE) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val filterLabel = remember(filter, h.wallets) {
        filter?.let { f -> if (f.startsWith("net:")) (Network.byName(f.removePrefix("net:"))?.label ?: "Manual") else h.wallets.firstOrNull { "w:${it.id}" == f }?.name ?: "Manual" }
    }
    val rows = remember(h, sort, filter) {
        val list = h.agg.assets.mapNotNull { it.filteredBy(filter) }
        when (sort) {
            HoldSort.VALUE -> list.sortedByDescending { it.valueUsd }
            HoldSort.PNL -> list.sortedByDescending { h.pnl.assets[it.key]?.pnl?.toDouble() ?: Double.NEGATIVE_INFINITY }
            HoldSort.PNL_PCT -> list.sortedByDescending { h.pnl.assets[it.key]?.pnlPct?.toDouble() ?: Double.NEGATIVE_INFINITY }
            HoldSort.CHANGE -> list.sortedByDescending { it.change24h ?: Double.NEGATIVE_INFINITY }
            HoldSort.NAME -> list.sortedBy { it.symbol.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Ativos", null)
        Segments(HoldView.entries, view, { it.label }, { view = it }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        Refreshable(refreshing, { vm.fullSync(true) }) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            com.coinportracker.ui.ScrollToTopOnReselect("assets", listState)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)) {
                when (view) {
                    HoldView.ASSETS -> {
                        item(key = "sort") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                                    items(HoldSort.entries, key = { it.name }) { s -> SortChip(s.label, s == sort) { sort = s } }
                                }
                            }
                            if (filterLabel != null) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(LocalExtra.current.cardHigh)
                                .clickable { filter = null }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Filtro: $filterLabel", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.Close, "Limpar filtro", Modifier.size(14.dp))
                            }
                            val total = rows.sumOf { it.valueUsd }
                            Text("${if (rows.size == 1) "1 ativo" else "${rows.size} ativos"} · ${h.money(total)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        }
                        if (!h.ready) item(key = "sk") { AssetSkeleton(8) }
                        else if (rows.isEmpty()) item(key = "empty") { EmptyLine("Nenhum ativo ainda. Adicione uma carteira pela blockchain ou registre uma operação.") }
                        items(rows, key = { it.key }, contentType = { "asset" }) { a ->
                            val metric = if (sort == HoldSort.PNL || sort == HoldSort.PNL_PCT) RowMetric.PNL else RowMetric.CHANGE_24H
                            Box(Modifier.animateItem(fadeInSpec = com.coinportracker.ui.theme.Motion.fade(), placementSpec = com.coinportracker.ui.theme.Motion.itemPlacement, fadeOutSpec = com.coinportracker.ui.theme.Motion.fade(150))) { AssetRow(h, a, h.pnl.assets[a.key], metric, { nav.navigate("asset2/${android.net.Uri.encode(a.key)}") }, spinFirst = false) }
                        }
                    }
                    HoldView.NETWORKS -> {
                        items(h.agg.byNetwork, key = { it.key }) { s ->
                            val n = Network.byName(s.key)
                            val b = h.pnl.byNetwork[s.key]
                            BucketRow(h, s.label, null, s.valueUsd, s.share, b?.pnl?.takeIf { b.cost.signum() > 0 }, icon = { if (n != null) NetworkDot(n, Modifier.size(36.dp)) else TokenIcon(null, "M", 36.dp) }) {
                                filter = "net:${s.key}"; view = HoldView.ASSETS
                            }
                        }
                        if (h.agg.byNetwork.isEmpty()) item { EmptyLine("Sem saldos em redes ainda.") }
                    }
                    HoldView.WALLETS -> {
                        items(h.wallets, key = { it.id }) { w ->
                            val share = if (h.agg.totalUsd > 0) w.valueUsd / h.agg.totalUsd else 0.0
                            BucketRow(h, w.name, if (w.watch) "${w.kind?.label?.substringBefore(" (") ?: ""} · ${walletAddressShort(w.address)}" else "Manual", w.valueUsd, share, w.pnlUsd?.takeIf { it.signum() != 0 },
                                icon = { WalletBadge(w.kind) }) { filter = "w:${w.id}"; view = HoldView.ASSETS }
                        }
                        item { TextButton(onClick = { nav.navigate("wallets2") }, Modifier.padding(8.dp)) { Text("Gerenciar carteiras") } }
                    }
                    HoldView.HIDDEN -> {
                        if (h.agg.hidden.isEmpty() && h.settings.hiddenTokens.isEmpty()) {
                            item { EmptyLine("Nenhum token oculto.") }
                        }
                        items(h.agg.hidden, key = { "${it.source.walletId}:${it.holding.network}:${it.holding.tokenKey}" }) { p ->
                            val key = "${p.holding.network.name}:${p.holding.tokenKey}"
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TokenIcon(p.holding.iconUrl, p.holding.symbol, 36.dp, p.holding.network, true)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.holding.symbol.take(24), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${Fmt.qty(p.holding.amount, h.hide)} · ${when (p.visibility) { Visibility.SPAM -> "suspeito"; Visibility.UNPRICED -> "sem preço"; Visibility.SMALL -> "valor pequeno"; else -> "" }}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                FitText(p.valueUsd?.let { h.money(it) } ?: "—", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 140.dp))
                            }
                        }
                        if (h.settings.hiddenTokens.isNotEmpty()) item {
                            TextButton(onClick = { vm.updateSettings { it.copy(hiddenTokens = emptySet()) } }, Modifier.padding(8.dp)) { Text("Reexibir " + com.coinportracker.core.Plural.of(h.settings.hiddenTokens.size, "token ocultado", "tokens ocultados")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val x = LocalExtra.current
    val bg by androidx.compose.animation.animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else x.cardHigh, com.coinportracker.ui.theme.Motion.fade(), label = "sc")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, com.coinportracker.ui.theme.Motion.fade(), label = "sf")
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Text(label, style = MaterialTheme.typography.labelLarge, color = fg,
        modifier = Modifier.pressScale(src).clip(RoundedCornerShape(10.dp)).background(bg).clickable(src, androidx.compose.material3.ripple(), onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp))
}

@Composable
fun WalletBadge(kind: com.coinportracker.onchain.AddressKind?) {
    val n = when (kind) { com.coinportracker.onchain.AddressKind.EVM -> Network.ETHEREUM; null -> null; else -> kind.networks.first() }
    if (n != null) NetworkDot(n, Modifier.size(36.dp)) else TokenIcon(null, "M", 36.dp)
}

/** Linha de agrupamento (rede/carteira): valor, participação e P&L, com barra de participação. */
@Composable
fun BucketRow(h: HubState, title: String, sub: String?, valueUsd: Double, share: Double, pnl: java.math.BigDecimal?, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val x = LocalExtra.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub ?: Fmt.pct(java.math.BigDecimal.valueOf(share * 100), false) + " do total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 160.dp)) {
                FitText(h.money(valueUsd), style = MaterialTheme.typography.titleSmall)
                if (pnl != null) FitText(h.signed(pnl), style = MaterialTheme.typography.labelMedium, color = changeColor(upOf(pnl)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(x.cardHigh)) {
            Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)))
        }
    }
}
