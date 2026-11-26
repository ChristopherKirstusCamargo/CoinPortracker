package com.coinportracker.ui.screens

import com.coinportracker.ui.theme.sharedKey
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.data.ChartRange
import com.coinportracker.domain.Currency
import com.coinportracker.onchain.PricePoint
import com.coinportracker.portfolio.CostSource
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.components.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm", Fmt.LOCALE)

@Composable
fun AssetDetailV2(vm: com.coinportracker.ui.UiModel, nav: NavHostController, key: String) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val a = h.agg.assets.firstOrNull { it.key == key }
    if (a == null) {
        Column { V2TopBar("Ativo", { nav.popBackStack() }); EmptyLine(if (h.ready) "Este ativo não está mais no portfólio." else "Carregando…") }
        return
    }
    val r = h.pnl.assets[key]
    var range by rememberSaveable { mutableStateOf(ChartRange.M1) }
    var pts by remember(key) { mutableStateOf<List<PricePoint>?>(null) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(a.priceKey, range) { pts = null; a.priceKey?.let { k -> vm.assetChart(range, k) { pts = it } } ?: run { pts = emptyList() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "top") { V2TopBar(a.name, { nav.popBackStack() }) }
        item(key = "head") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.sharedKey("icon:" + a.key)) { TokenIcon(a.iconUrl, a.symbol, 44.dp, a.networks.firstOrNull(), a.networks.isNotEmpty()) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(a.symbol, style = MaterialTheme.typography.titleMedium)
                        Text(if (a.networks.isEmpty()) "Manual" else a.networks.joinToString(" · ") { it.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(16.dp))
                val sp = scrub?.let { pts?.getOrNull(it) }
                Text(sp?.let { Instant.ofEpochMilli(it.t).atZone(ZoneId.systemDefault()).format(dayFmt) } ?: "Preço", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SlotText(h.price(sp?.usd ?: a.priceUsd), MaterialTheme.typography.displaySmall, replayKey = h.currency)
                Spacer(Modifier.height(6.dp))
                val first = pts?.firstOrNull()?.usd; val last = pts?.lastOrNull()?.usd
                val rp = if (first != null && last != null && first > 0) (last - first) / first * 100 else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChangePill(pctOf(a.change24h), upOf(a.change24h)); Text("  24h", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (rp != null) { Spacer(Modifier.width(12.dp)); ChangePill(pctOf(rp), upOf(rp), filled = false); Text("  ${range.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item(key = "chart") {
            val p = pts
            val up = p?.takeIf { it.size >= 2 }?.let { upOf(it.last().usd - it.first().usd) } ?: true
            Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 12.dp)) {
                when {
                    p == null -> ShimmerBlock(Modifier.fillMaxSize().padding(16.dp), 12.dp)
                    p.size < 2 -> EmptyLine("Sem histórico de preço para este token.")
                    else -> {
                        val xs = remember(p) { LongArray(p.size) { p[it].t } }; val ys = remember(p) { FloatArray(p.size) { p[it].usd.toFloat() } }
                        LineChart(xs, ys, changeColor(up ?: true), Modifier.fillMaxSize()) { scrub = it }
                    }
                }
            }
            RangeChips(ChartRange.entries, range, { it.label }, { range = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        item(key = "position") {
            CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                Text("Sua posição", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Saldo", h.money(a.valueUsd), Modifier.weight(1f))
                    Stat("Quantidade", if (h.hide) Fmt.MASK else Fmt.qty(a.amount.setScale(8, RoundingMode.DOWN).stripTrailingZeros()), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                if (r?.avgCost != null) {
                    Row {
                        Stat("Preço médio", h.price(r.avgCost), Modifier.weight(1f), sub = r.source.label)
                        val vsAvg = a.priceUsd?.let { p -> Decimals.pct(BigDecimal.valueOf(p).subtract(r.avgCost), r.avgCost) }
                        Stat("Preço atual × médio", Fmt.pct(vsAvg), Modifier.weight(1f), changeColor(upOf(vsAvg)))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Stat("Custo total", h.money(r.cost), Modifier.weight(1f))
                        Stat("Lucro não realizado", h.signed(r.pnl), Modifier.weight(1f), changeColor(upOf(r.pnl)), sub = Fmt.pct(r.pnlPct))
                    }
                } else {
                    Text("Preço médio indisponível para este ativo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r?.estimate?.let { e -> EstimateNote(h, e, r.source == CostSource.ESTIMATED) }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { nav.navigate("position/${android.net.Uri.encode(key)}") }, Modifier.fillMaxWidth()) { Text("Ver posição total") }
            }
        }
        item(key = "partsTitle") { SectionTitle("Onde está", Modifier.padding(horizontal = 20.dp)) }
        items(a.parts, key = { "${it.source.walletId}:${it.holding.network}:${it.holding.tokenKey}" }) { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.source.manual) TokenIcon(null, "M", 32.dp) else NetworkDot(p.holding.network, Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.source.walletName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (p.source.manual) "Manual" else p.holding.network.label) + if (p.holding.kind == com.coinportracker.onchain.HoldingKind.STAKED) " · em staking" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    FitText(h.money(p.valueUsd), style = MaterialTheme.typography.titleSmall)
                    Text(if (h.hide) Fmt.MASK else Fmt.qty(p.holding.amount.setScale(8, RoundingMode.DOWN).stripTrailingZeros()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!a.manual) item(key = "hide") {
            TextButton(onClick = { a.parts.filter { !it.source.manual }.forEach { p -> vm.hideToken("${p.holding.network.name}:${p.holding.tokenKey}", true) }; nav.popBackStack() },
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ocultar este token do portfólio")
            }
        }
    }

}


/** Detalhes da estimativa automática do preço médio (fonte, confiança e o que foi considerado). */
@Composable
private fun EstimateNote(h: com.coinportracker.data.HubState, e: com.coinportracker.history.CostEstimate, inUse: Boolean) {
    val x = com.coinportracker.ui.theme.LocalExtra.current
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(x.cardHigh).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (inUse) "Estimado pela blockchain" else "Estimativa da blockchain (não usada)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Tag("Confiança ${e.confidence.label.lowercase()}", when (e.confidence) {
                com.coinportracker.history.Confidence.HIGH -> x.gain; com.coinportracker.history.Confidence.MEDIUM -> x.warn; else -> x.loss })
        }
        Spacer(Modifier.height(4.dp))
        val lines = buildList {
            add("Preço médio estimado: ${h.price(e.avgUsd)}")
            add(if (e.inflows == 1) "1 entrada" else "${e.inflows} entradas")
            e.firstAt?.let { add("Desde ${Fmt.date(it)}") }
            if (e.incomeQty > 0) add("Recompensas ${Fmt.num(e.incomeQty, 6)} (${h.money(e.incomeUsd)})")
        }
        Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
