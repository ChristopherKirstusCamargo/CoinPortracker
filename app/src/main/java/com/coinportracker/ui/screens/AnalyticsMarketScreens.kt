package com.coinportracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.data.ChartRange
import com.coinportracker.market.MarketCoin
import com.coinportracker.onchain.PricePoint
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.Refreshable
import com.coinportracker.ui.charts.Sparkline
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val marketDay = DateTimeFormatter.ofPattern("dd/MM/yyyy", Fmt.LOCALE)

@Composable
fun MarketScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.state.collectAsStateWithLifecycle()
    val m by vm.markets.collectAsStateWithLifecycle()
    val watch by vm.watchlist.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var onlyWatch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.currency) {
        vm.loadMarkets(s.currency, false)
        while (true) { delay(60_000); vm.loadMarkets(s.currency, false, silent = true) }
    }
    val watchIds = remember(watch) { watch.map { it.coingeckoId }.toSet() }
    val hub by vm.hub.collectAsStateWithLifecycle()
    val live = remember(hub.agg) { liveQuotes(hub) }
    val list = remember(m, onlyWatch, watchIds) { m?.first.orEmpty().filter { !onlyWatch || it.id in watchIds } }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Mercado", { nav.popBackStack() })
        Row(Modifier.padding(horizontal = 16.dp)) { ChipSelector(listOf(false, true), onlyWatch, { if (it) "Favoritos (${watch.size})" else "Top 100" }, { onlyWatch = it }) }
        Refreshable(refreshing, { vm.loadMarkets(s.currency, true) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                if (m == null) item { SkeletonList(8) }
                items(list, key = { it.id }, contentType = { "coin" }) { coin ->
                    val lq = live[coin.id]
                    MarketRow(coin, s.currency, coin.id in watchIds, { vm.toggleWatch(coin) }, onClick = { nav.navigate("coin/${android.net.Uri.encode(coin.id)}") }, livePrice = lq?.let { hub.price(it.first) }, liveChange = lq?.second?.let { java.math.BigDecimal.valueOf(it) })
                }
                m?.second?.let { item(key = "src") { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp, 12.dp)) } }
            }
        }
    }
}

/**
 * Linha do Top 100. Colunas com largura própria (nada disputa espaço com nada): rank | ícone | símbolo e cap (encolhem antes
 * de cortar) | sparkline | preço e variação | favorito. Em telas estreitas a sparkline encolhe (< 360 dp) e some (< 340 dp) antes de cortar texto.
 */
@Composable
internal fun MarketRow(coin: MarketCoin, cur: com.coinportracker.domain.Currency, fav: Boolean, onFav: () -> Unit, onClick: () -> Unit,
                       livePrice: String? = null, liveChange: java.math.BigDecimal? = null) {
    // ativos que você tem usam a mesma cotação ao vivo (preço e variação 24 h) do Início e de Ativos
    val ch = liveChange ?: coin.change24hPct?.toBigDecimalOrNull()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 360.dp
        val showSpark = maxWidth >= 340.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${coin.rank ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp), maxLines = 1, softWrap = false)
            TokenIcon(coin.image, coin.symbol, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FitText(coin.symbol.uppercase(), style = MaterialTheme.typography.titleSmall, minScale = 0.7f)
                FitText("Cap " + Fmt.compact(coin.marketCap?.toBigDecimalOrNull(), cur), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.7f)
            }
            if (showSpark) {
                Spacer(Modifier.width(8.dp))
                Sparkline(coin.sparkline, if ((ch?.signum() ?: 0) >= 0) LocalExtra.current.gain else LocalExtra.current.loss, Modifier.width(if (narrow) 36.dp else 48.dp).height(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(if (narrow) 80.dp else 88.dp)) {
                FitText(livePrice ?: Fmt.price(coin.price?.toBigDecimalOrNull(), cur), style = MaterialTheme.typography.titleSmall, minScale = 0.7f)
                FitText(Fmt.pct(ch), style = MaterialTheme.typography.labelMedium, color = gainColor(ch), minScale = 0.8f)
            }
            IconButton(onClick = onFav, modifier = Modifier.size(40.dp)) {
                Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (fav) "Remover dos favoritos" else "Favoritar", Modifier.size(20.dp),
                    tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MarketCoinScreen(vm: MainViewModel, nav: NavHostController, id: String) {
    val s by vm.state.collectAsStateWithLifecycle()
    val h by vm.hub.collectAsStateWithLifecycle()
    val m by vm.markets.collectAsStateWithLifecycle()
    val watch by vm.watchlist.collectAsStateWithLifecycle()
    val coin = m?.first?.firstOrNull { it.id == id }
    val cur = s.currency
    var range by rememberSaveable { mutableStateOf(ChartRange.M1) }
    var pts by remember(id) { mutableStateOf<List<PricePoint>?>(null) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(id, range) { pts = null; vm.assetChart(range, "coingecko:$id") { pts = it } }
    LaunchedEffect(cur) { if (coin == null) vm.loadMarkets(cur, false) }
    val fav = watch.any { it.coingeckoId == id }
    val owned = h.agg.assets.firstOrNull { a -> a.priceKey == "coingecko:$id" || a.key == "cg:$id" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "top") {
            V2TopBar(coin?.name ?: "", { nav.popBackStack() }) {
                if (coin != null) IconButton(onClick = { vm.toggleWatch(coin) }) {
                    Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (fav) "Remover dos favoritos" else "Favoritar",
                        tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (coin == null) { item { SkeletonList(4) }; return@LazyColumn }
        item(key = "head") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TokenIcon(coin.image, coin.symbol, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(coin.symbol.uppercase(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        coin.rank?.let { Text("#$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                val sp = scrub?.let { pts?.getOrNull(it) }
                Text(sp?.let { Instant.ofEpochMilli(it.t).atZone(ZoneId.systemDefault()).format(marketDay) } ?: "Preço", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val lq = remember(h.agg) { liveQuotes(h) }[coin.id]
                val priceText = if (sp != null) h.price(sp.usd) else lq?.let { h.price(it.first) } ?: Fmt.price(coin.price?.toBigDecimalOrNull(), cur)
                SlotText(priceText, MaterialTheme.typography.displaySmall, replayKey = cur)
                Spacer(Modifier.height(6.dp))
                val ch = lq?.second ?: coin.change24hPct?.toDoubleOrNull()
                val p = pts
                val rp = if (p != null && p.size >= 2 && p.first().usd > 0) (p.last().usd - p.first().usd) / p.first().usd * 100 else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChangePill(pctOf(ch), upOf(ch)); Text("  24h", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    p.size < 2 -> EmptyLine("Sem histórico de preço.")
                    else -> {
                        val xs = remember(p) { LongArray(p.size) { p[it].t } }; val ys = remember(p) { FloatArray(p.size) { p[it].usd.toFloat() } }
                        LineChart(xs, ys, changeColor(up), Modifier.fillMaxSize()) { scrub = it }
                    }
                }
            }
            RangeChips(ChartRange.entries, range, { it.label }, { range = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        item(key = "stats") {
            CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                KeyValue("Capitalização", Fmt.compact(coin.marketCap?.toBigDecimalOrNull(), cur))
                KeyValue("Volume 24h", Fmt.compact(coin.volume24h?.toBigDecimalOrNull(), cur))
                coin.circulating?.toBigDecimalOrNull()?.let { KeyValue("Em circulação", Fmt.qty(it.setScale(0, java.math.RoundingMode.DOWN)) + " " + coin.symbol.uppercase()) }
                coin.maxSupply?.toBigDecimalOrNull()?.let { KeyValue("Oferta máxima", Fmt.qty(it.setScale(0, java.math.RoundingMode.DOWN))) }
                val hiLo = com.coinportracker.market.SparklineScale.extrema(coin.sparkline, cur, h.fx)
                if (hiLo != null) {
                    KeyValue("Máxima 7d", Fmt.price(hiLo.first, cur))
                    KeyValue("Mínima 7d", Fmt.price(hiLo.second, cur))
                } else if (coin.sparkline.size >= 2) {
                    KeyValue("Máxima 7d", "—")
                    KeyValue("Mínima 7d", "—")
                }
            }
        }
        if (owned != null) item(key = "owned") {
            CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), onClick = { nav.navigate("asset2/${android.net.Uri.encode(owned.key)}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Na sua carteira", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FitText(h.money(owned.valueUsd), style = MaterialTheme.typography.titleLarge)
                    }
                    Icon(androidx.compose.material.icons.Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


/** id da CoinGecko → (preço USD, variação 24 h %) da cotação ao vivo usada no portfólio. */
internal fun liveQuotes(h: com.coinportracker.data.HubState): Map<String, Pair<Double, Double?>> {
    val out = HashMap<String, Pair<Double, Double?>>()
    for (a in h.agg.assets) for (p in a.parts) {
        val k = p.holding.priceKey ?: continue
        val id = when {
            k.startsWith("coingecko:") -> k.removePrefix("coingecko:")
            k == "solana:So11111111111111111111111111111111111111112" -> "solana"
            else -> continue
        }
        val px = p.priceUsd ?: continue
        if (id !in out) out[id] = px to p.change24h
    }
    return out
}
