package com.coinportracker.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.data.ActivityClassifier
import com.coinportracker.data.ActivityKind
import com.coinportracker.data.ClassifiedActivity
import com.coinportracker.database.ChainActivityEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.domain.TxType
import com.coinportracker.onchain.Network
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.Refreshable
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ActView(val label: String) { CHAIN("On-chain"), MANUAL("Operações") }
private val dayHeader = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Fmt.LOCALE)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Fmt.LOCALE)

private fun dayLabel(ts: Long): String {
    if (ts <= 0L) return "Sem data"
    val d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) { today -> "Hoje"; today.minusDays(1) -> "Ontem"; else -> d.format(dayHeader).replaceFirstChar { it.uppercase() } }
}

@Composable
fun ActivityScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, initialView: ActView = ActView.CHAIN) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val chain by vm.activity.collectAsStateWithLifecycle()
    val txs by vm.allTx.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(initialView) }
    val ctx = LocalContext.current
    val walletNames = remember(h.wallets) { h.wallets.associate { it.id to it.name } }
    val unitPrice = remember(h.agg) { h.agg.assets.flatMap { a -> a.parts.mapNotNull { p -> p.holding.priceKey?.let { k -> p.priceUsd?.let { k to it } } } }.toMap() }
    var walletFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var assetFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val stakeSigs by vm.stakeSignatures.collectAsStateWithLifecycle()
    val histSt by vm.activityStatus.collectAsStateWithLifecycle()
    // v6.0: abrir a aba relê o histórico (se a última leitura tiver mais de 2 min); puxar para atualizar força a releitura
    LaunchedEffect(Unit) { vm.refreshActivity(false) }
    val classified = remember(chain, stakeSigs) { ActivityClassifier.classify(chain, stakeSigs, keepUndated = true) }
    val assetOptions = remember(classified) { classified.flatMap { it.rows.map { r -> r.symbol } }.distinct().sorted() }
    val chainRows = remember(classified, walletFilter, kindFilter, assetFilter) {
        classified.filter { c ->
            (walletFilter == null || c.primary.walletId == walletFilter) &&
                (kindFilter == null || c.kind.name == kindFilter) &&
                (assetFilter == null || c.rows.any { it.symbol == assetFilter })
        }
    }
    // rótulos de dia calculados uma vez por lista filtrada (antes: formatação de data por linha a cada atualização de preço)
    val dayLabels = remember(chainRows) { chainRows.map { dayLabel(it.timestamp) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2TopBar("Atividade", null)
            Refreshable(refreshing || (histSt.running && chain.isEmpty()), { vm.fullSync(true) }) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                com.coinportracker.ui.ScrollToTopOnReselect("activity", listState)
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)) {
                    if (view == ActView.CHAIN) {
                        item(key = "filters") {
                            Column {
                                if (h.wallets.count { it.watch } > 1) {
                                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                        item { SortChip("Todas", walletFilter == null) { walletFilter = null } }
                                        items(h.wallets.filter { it.watch }, key = { it.id }) { w -> SortChip(w.name, walletFilter == w.id) { walletFilter = w.id } }
                                    }
                                }
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                    item { SortChip("Todos os tipos", kindFilter == null) { kindFilter = null } }
                                    items(ActivityKind.entries.filter { it != ActivityKind.OTHER }, key = { it.name }) { k -> SortChip(k.label, kindFilter == k.name) { kindFilter = k.name } }
                                }
                                if (assetOptions.size > 1) {
                                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                        item { SortChip("Todos os ativos", assetFilter == null) { assetFilter = null } }
                                        items(assetOptions, key = { it }) { s -> SortChip(s, assetFilter == s) { assetFilter = s } }
                                    }
                                }
                            }
                        }
                        // Rendimento: recompensas de staking por época (não são transações na blockchain, por isso vêm da conta de stake)
                        val stakeRows = if (kindFilter == ActivityKind.REWARD.name && assetFilter.let { it == null || it == "SOL" }) h.stakes.filter { walletFilter == null || it.walletId == walletFilter } else emptyList()
                        stakeRows.forEach { sv ->
                            item(key = "stake:${sv.account}", contentType = "stake") { StakeSummaryRow(sv, h, walletNames[sv.walletId]) }
                            val epochs = sv.epochs.ifEmpty { sv.lastEpochSol?.takeIf { it > 0 }?.let { listOf(com.coinportracker.data.EpochReward(-1, it, null, System.currentTimeMillis())) }.orEmpty() }
                            epochs.forEach { e -> item(key = "ep:${sv.account}:${e.epoch}", contentType = "epoch") { EpochRow(e, h, unitPrice["coingecko:solana"]) } }
                        }
                        val filtered = walletFilter != null || kindFilter != null || assetFilter != null
                        val watchCount = h.wallets.count { it.watch }
                        // v6.0: aviso das redes que falharam (mesmo com a lista cheia), sempre com o motivo e "Tentar de novo"
                        if (histSt.failed.isNotEmpty() && !histSt.running && classified.isNotEmpty()) item(key = "hist-warn") {
                            HistoryNotice(failedText(histSt, h), "Tentar de novo") { vm.refreshActivity(true) }
                        }
                        if (chainRows.isEmpty() && stakeRows.isEmpty()) item(key = "empty") {
                            ActivityEmptyState(ActivityEmpty.of(watchCount, classified.isEmpty(), filtered, histSt), histSt, h,
                                onRetry = { vm.refreshActivity(true) }, onClear = { walletFilter = null; kindFilter = null; assetFilter = null },
                                onAddWallet = { nav.navigate("addWallet") })
                        }
                        var lastDay = ""
                        chainRows.forEachIndexed { i, c ->
                            val d = dayLabels[i]
                            if (d != lastDay) { lastDay = d; item(key = "d:$d:${c.timestamp}", contentType = "day") { DayHeader(d) } }
                            item(key = "c:${c.primary.walletId}:${c.primary.network}:${c.primary.hash}:${c.kind}", contentType = "chain") {
                                Box(Modifier.animateItem(fadeInSpec = com.coinportracker.ui.theme.Motion.fade(), placementSpec = com.coinportracker.ui.theme.Motion.itemPlacement, fadeOutSpec = com.coinportracker.ui.theme.Motion.fade(150))) {
                                    ClassifiedRow(c, h, walletNames[c.primary.walletId], unitPrice) {
                                        Network.byName(c.primary.network)?.let { n -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.explorerTx + c.primary.hash))) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StakeSummaryRow(sv: com.coinportracker.data.StakeView, h: com.coinportracker.data.HubState, wallet: String?) {
    val x = LocalExtra.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        DirIcon(Icons.Outlined.Savings, Network.SOLANA)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Recompensas de staking", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(wallet ?: "Staking", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Acumulado desde o início · fora do resultado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 140.dp)) {
            FitText("+${Fmt.qty(java.math.BigDecimal.valueOf(sv.rewardsSol).setScale(6, RoundingMode.DOWN).stripTrailingZeros(), h.hide)} SOL", style = MaterialTheme.typography.titleSmall, color = x.gain, minScale = 0.75f)
            sv.rewardsUsd?.let { Text(if (h.hide) Fmt.MASK else h.money(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

@Composable
private fun EpochRow(e: com.coinportracker.data.EpochReward, h: com.coinportracker.data.HubState, solUsd: Double?) {
    val x = LocalExtra.current
    Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (e.epoch >= 0) "Época ${e.epoch}" else "Última época", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(Instant.ofEpochMilli(e.t).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("+${Fmt.qty(java.math.BigDecimal.valueOf(e.sol).setScale(6, RoundingMode.DOWN).stripTrailingZeros(), h.hide)} SOL", style = MaterialTheme.typography.bodyMedium, color = x.gain, maxLines = 1)
            (e.usd ?: solUsd?.let { e.sol * it })?.let { Text(if (h.hide) Fmt.MASK else h.money(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

@Composable
private fun DayHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun DirIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, network: Network?) {
    Box(Modifier.size(40.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(LocalExtra.current.cardHigh), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
        if (network != null) Box(Modifier.size(16.dp).align(Alignment.BottomEnd).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.background).padding(1.dp)) { NetworkDot(network, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ChainRow(a: ChainActivityEntity, wallet: String?, value: String?, onClick: () -> Unit) {
    val n = Network.byName(a.network)
    val (icon, title) = when (a.direction) {
        "IN" -> Icons.Outlined.SouthWest to "Recebido"
        "OUT" -> Icons.Outlined.NorthEast to "Enviado"
        "SELF" -> Icons.Outlined.SyncAlt to "Entre suas contas"
        else -> Icons.Outlined.Code to (a.label ?: "Interação com contrato")
    }
    val amt = a.amount?.toBigDecimalOrNull()
    val x = LocalExtra.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        DirIcon(icon, n)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (a.failed) "$title (falhou)" else title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(listOfNotNull(wallet, a.counterparty?.let { walletAddressShort(it) }, a.timestamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (amt != null) Spacer(Modifier.width(10.dp))
        if (amt != null) Column(horizontalAlignment = Alignment.End) {
            val sign = when (a.direction) { "IN" -> "+"; "OUT" -> "-"; else -> "" }
            Text("$sign${Fmt.qty(amt.setScale(6, RoundingMode.DOWN).stripTrailingZeros())} ${a.symbol.take(10)}", style = MaterialTheme.typography.titleSmall,
                color = when (a.direction) { "IN" -> x.gain; else -> MaterialTheme.colorScheme.onSurface }, maxLines = 1)
            value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}


@Composable
private fun ClassifiedRow(c: ClassifiedActivity, h: com.coinportracker.data.HubState, wallet: String?, unitPrice: Map<String, Double>, onClick: () -> Unit) {
    val a = c.primary
    val n = Network.byName(a.network)
    val icon = when (c.kind) {
        ActivityKind.IN, ActivityKind.REWARD -> Icons.Outlined.SouthWest
        ActivityKind.OUT -> Icons.Outlined.NorthEast
        ActivityKind.SWAP -> Icons.Outlined.SwapHoriz
        ActivityKind.SELF -> Icons.Outlined.SyncAlt
        ActivityKind.OTHER -> Icons.Outlined.Code
    }
    val x = LocalExtra.current
    val amt = a.amount?.toBigDecimalOrNull()
    val value = a.priceKey?.let { unitPrice[it] }?.let { u -> amt?.let { q -> if (h.hide) Fmt.MASK else h.money(q.toDouble() * u) } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        DirIcon(icon, n)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val title = when (c.kind) {
                ActivityKind.SWAP -> "Swap · " + c.rows.joinToString(" → ") { it.symbol }
                else -> c.kind.label + if (a.failed) " (falhou)" else ""
            }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // nome da carteira numa linha própria (antes dividia a linha com o endereço e a hora e era cortado)
            if (wallet != null) Text(wallet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(a.counterparty?.let { walletAddressShort(it) }, a.timestamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt) }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.kind == ActivityKind.SWAP) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 140.dp)) {
                c.rows.filter { it.direction == "OUT" }.forEach { r ->
                    val q = r.amount?.toBigDecimalOrNull()
                    if (q != null) FitText("-${Fmt.qty(q.setScale(6, RoundingMode.DOWN).stripTrailingZeros(), h.hide)} ${r.symbol.take(8)}", style = MaterialTheme.typography.titleSmall, minScale = 0.75f)
                }
                c.rows.filter { it.direction == "IN" }.forEach { r ->
                    val q = r.amount?.toBigDecimalOrNull()
                    if (q != null) FitText("+${Fmt.qty(q.setScale(6, RoundingMode.DOWN).stripTrailingZeros(), h.hide)} ${r.symbol.take(8)}", style = MaterialTheme.typography.titleSmall, color = x.gain, minScale = 0.75f)
                }
            }
        } else if (amt != null) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 140.dp)) {
                val sign = when (c.kind) { ActivityKind.IN, ActivityKind.REWARD -> "+"; ActivityKind.OUT -> "-"; else -> "" }
                FitText("$sign${Fmt.qty(amt.setScale(6, RoundingMode.DOWN).stripTrailingZeros(), h.hide)} ${a.symbol.take(10)}", style = MaterialTheme.typography.titleSmall,
                    color = when (c.kind) { ActivityKind.IN, ActivityKind.REWARD -> x.gain; else -> MaterialTheme.colorScheme.onSurface }, minScale = 0.75f)
                value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun TxRowV2(t: TransactionEntity, sym: String, toSym: String?, wallets: Map<Long, String>, onClick: () -> Unit) {
    val type = runCatching { TxType.valueOf(t.type) }.getOrNull()
    val icon = when (type) {
        TxType.BUY -> Icons.Outlined.AddShoppingCart; TxType.SELL -> Icons.Outlined.Sell; TxType.SWAP -> Icons.Outlined.SwapHoriz
        TxType.STAKING, TxType.REWARD, TxType.INCOME_DISTRIBUTION -> Icons.Outlined.Savings; TxType.INTERNAL_TRANSFER -> Icons.Outlined.SyncAlt
        TxType.DEPOSIT -> Icons.Outlined.SouthWest; TxType.WITHDRAWAL -> Icons.Outlined.NorthEast; else -> Icons.Outlined.ReceiptLong
    }
    val q = t.quantity.toBigDecimalOrNull()
    val price = t.unitPrice?.toBigDecimalOrNull()
    val cur = runCatching { com.coinportracker.domain.Currency.valueOf(t.quoteCurrency) }.getOrDefault(com.coinportracker.domain.Currency.BRL)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        DirIcon(icon, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${type?.label ?: t.type} · $sym" + (toSym?.let { " → $it" } ?: ""), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.fromWalletId?.let { wallets[it] }, price?.let { "a ${Fmt.price(it, cur)}" }, t.exchange).joinToString(" · ").ifEmpty { Fmt.dateTime(t.timestamp) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${Fmt.qty(q?.stripTrailingZeros())} $sym", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (q != null && price != null) FitText(Fmt.money(q.multiply(price), cur), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** v6.0 — qual estado vazio mostrar (puro, testado na JVM). */
enum class ActivityEmpty { NO_WALLETS, LOADING, ERROR, FILTERED, NOTHING_FOUND, NOT_READ_YET
    ;
    companion object {
        fun of(watchWallets: Int, noRows: Boolean, filtered: Boolean, st: com.coinportracker.data.HistoryStatus): ActivityEmpty = when {
            watchWallets == 0 -> NO_WALLETS
            !noRows && filtered -> FILTERED
            !noRows -> FILTERED
            st.running -> LOADING
            st.nets.isNotEmpty() && st.nets.none { it.state == com.coinportracker.data.NetHistoryState.OK } && st.failed.isNotEmpty() -> ERROR
            st.lastAt == null -> NOT_READ_YET
            else -> NOTHING_FOUND
        }
    }
}

private fun netLabel(n: String) = Network.byName(n)?.label ?: n.lowercase().replaceFirstChar { it.uppercase() }

private fun failedText(st: com.coinportracker.data.HistoryStatus, h: com.coinportracker.data.HubState): String {
    val names = h.wallets.associate { it.id to it.name }
    val parts = st.failed.take(3).joinToString("; ") { f -> "${netLabel(f.network)}${if (h.wallets.size > 1) " (" + (names[f.walletId] ?: "carteira") + ")" else ""}: ${f.message ?: "sem resposta"}" }
    return "Parte do histórico não foi lida agora. $parts${if (st.failed.size > 3) "; e mais ${st.failed.size - 3}" else ""}."
}

@Composable
private fun HistoryNotice(text: String, action: String, onAction: () -> Unit) {
    val x = LocalExtra.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(x.divider.copy(alpha = 0.35f)).padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(action) }
    }
}

@Composable
private fun ActivityEmptyState(kind: ActivityEmpty, st: com.coinportracker.data.HistoryStatus, h: com.coinportracker.data.HubState,
                               onRetry: () -> Unit, onClear: () -> Unit, onAddWallet: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (kind) {
            ActivityEmpty.LOADING -> {
                repeat(4) { ShimmerBlock(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 4.dp)) }
                Spacer(Modifier.height(10.dp))
                Text(if (st.fetched > 0) "Lendo o histórico nas redes… ${st.fetched} movimentações até agora" else "Lendo o histórico nas redes…",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActivityEmpty.NO_WALLETS -> {
                Text("Nenhuma carteira acompanhada", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text("Adicione o endereço público de uma carteira para ver entradas, saídas, swaps e rendimentos.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onAddWallet) { Text("Adicionar carteira") }
            }
            ActivityEmpty.ERROR -> {
                Text("Não foi possível ler o histórico", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(failedText(st, h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onRetry) { Text("Tentar de novo") }
            }
            ActivityEmpty.FILTERED -> {
                Text("Nada com esses filtros", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClear) { Text("Limpar filtros") }
            }
            ActivityEmpty.NOT_READ_YET -> {
                Text("O histórico ainda não foi lido", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRetry) { Text("Ler agora") }
            }
            ActivityEmpty.NOTHING_FOUND -> {
                Text("Nenhuma movimentação encontrada", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val ok = st.nets.filter { it.state == com.coinportracker.data.NetHistoryState.OK }.map { netLabel(it.network) }.distinct()
                val un = st.unsupported.map { netLabel(it.network) }.distinct()
                val lines = buildList {
                    if (ok.isNotEmpty()) add("Redes lidas: ${ok.joinToString(", ")}.")
                    if (un.isNotEmpty()) add("Sem histórico público gratuito (só saldo): ${un.joinToString(", ")}.")
                    st.lastAt?.let { add("Última leitura: ${Fmt.dateTime(it)}.") }
                }
                Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onRetry) { Text("Ler de novo") }
            }
        }
    }
}
