package com.coinportracker.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.data.HubState
import com.coinportracker.position.*
import com.coinportracker.share.ShareExport
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.LocalReducedMotion
import com.coinportracker.ui.theme.reveal
import com.coinportracker.ui.theme.sharedKey
import com.coinportracker.ui.theme.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

internal fun positionOf(h: HubState, anchor: String, now: Long = System.currentTimeMillis()): PositionTotal? {
    val clocks = h.wallets.associate { it.id to WalletClock(it.lastSyncAt, !it.error.isNullOrBlank()) }
    val staleMs = h.settings.staleAfterMin.coerceAtLeast(0).toLong() * 60_000L
    return PositionAggregator.build(anchor, h.agg.assets, h.agg.hidden, clocks, h.holdingAsOf, now, staleMs)
}

private fun amountLine(qty: BigDecimal, symbol: String, hide: Boolean): String =
    if (hide) "${Fmt.MASK} $symbol" else "${Fmt.qty(qty)} $symbol"

@Composable
fun PositionScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, anchor: String) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val key = remember(anchor) { Uri.decode(anchor) }
    val pos = remember(h, key) { positionOf(h, key) }
    if (pos == null) {
        Column {
            V2TopBar("Posição total", { nav.popBackStack() })
            EmptyLine(if (h.ready) "Este ativo não está mais no portfólio." else "Carregando…")
        }
        return
    }
    val x = LocalExtra.current
    val warn = pos.partial || pos.stale
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "top") {
            V2TopBar("Posição total", { nav.popBackStack() }) {
                IconButton(onClick = { nav.navigate("positionShare/${Uri.encode(key)}") }) {
                    Icon(Icons.Outlined.Share, "Gerar cartão")
                }
            }
        }
        item(key = "hero") {
            Column(Modifier.padding(horizontal = 20.dp).staggeredEntrance(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.sharedKey("icon:$key")) { TokenIcon(pos.iconUrl, pos.symbol, 52.dp) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(pos.symbol, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pos.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                SlotText(amountLine(pos.total, pos.symbol, h.hide), MaterialTheme.typography.displaySmall, spinOnFirst = 1, staggerMs = 18, durationMs = 720)
                Spacer(Modifier.height(8.dp))
                val fiat = when {
                    pos.valueUsd == null -> "Sem cotação"
                    pos.valueIncomplete -> h.money(pos.valueUsd) + " · cotação incompleta"
                    else -> h.money(pos.valueUsd)
                }
                Text(fiat, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val oldest = pos.lines.mapNotNull { it.updatedAt }.minOrNull()
                val calm = when {
                    warn -> null
                    oldest != null -> "Saldos ${Fmt.ago(oldest)}"
                    else -> null
                }
                val wallets = if (pos.walletCount == 1) "1 carteira" else "${pos.walletCount} carteiras"
                Text(listOfNotNull(wallets, calm).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (warn) item(key = "warn") {
            val label = when {
                pos.partial && pos.stale -> "Leitura parcial · dados desatualizados"
                pos.partial -> "Leitura parcial"
                else -> "Dados desatualizados"
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = x.warn,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).reveal())
        }
        item(key = "split") {
            val free = pos.lines.filter { it.kind == LineKind.FREE }.fold(BigDecimal.ZERO) { a, l -> a.add(l.counted ?: BigDecimal.ZERO, Decimals.MC) }
            val staked = pos.lines.filter { it.kind == LineKind.STAKED }.fold(BigDecimal.ZERO) { a, l -> a.add(l.counted ?: BigDecimal.ZERO, Decimals.MC) }
            val liquid = pos.lines.filter { it.kind == LineKind.LIQUID_STAKING }.fold(BigDecimal.ZERO) { a, l -> a.add(l.counted ?: BigDecimal.ZERO, Decimals.MC) }
            CpCard(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp).staggeredEntrance(1)) {
                Row {
                    if (free.signum() > 0) Stat("Livre", amountLine(free, pos.symbol, h.hide), Modifier.weight(1f))
                    if (staked.signum() > 0) Stat("Em stake", amountLine(staked, pos.symbol, h.hide), Modifier.weight(1f))
                    if (liquid.signum() > 0) Stat("Líquido", amountLine(liquid, pos.symbol, h.hide), Modifier.weight(1f))
                    if (free.signum() == 0 && staked.signum() == 0 && liquid.signum() == 0) {
                        Text("Nenhum saldo somado.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "where") { SectionTitle("Por carteira", Modifier.padding(horizontal = 20.dp)) }
        val wallets = pos.lines.groupBy { it.walletId }.entries.sortedByDescending { e -> e.value.sumOf { it.counted?.toDouble() ?: 0.0 } }
        items(wallets, key = { "w${it.key}" }) { (id, lines) ->
            val sub = lines.fold(BigDecimal.ZERO) { a, l -> a.add(l.counted ?: BigDecimal.ZERO, Decimals.MC) }
            CpCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).reveal()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lines.first().walletName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(amountLine(sub, pos.symbol, h.hide), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                lines.forEach { line ->
                    Spacer(Modifier.height(12.dp))
                    LineBlock(h, line, pos.symbol)
                }
            }
        }
        if (pos.aside.isNotEmpty()) {
            item(key = "asideTitle") { SectionTitle("Fora do total", Modifier.padding(horizontal = 20.dp)) }
            items(pos.aside, key = { "a${it.walletId}:${it.symbol}:${it.networkLabel}" }) { line ->
                CpCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).reveal()) {
                    LineBlock(h, line, line.symbol)
                    Spacer(Modifier.height(8.dp))
                    Text(line.excludedNote ?: PositionAggregator.NO_RATE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        pos.footnote?.let { note ->
            item(key = "note") {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).reveal())
            }
        }
        item(key = "chart") {
            TextButton(onClick = { nav.navigate("asset2/${Uri.encode(key)}") }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).reveal()) {
                Icon(Icons.AutoMirrored.Outlined.ShowChart, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Preço e gráfico")
            }
        }
    }
}

@Composable
private fun LineBlock(h: HubState, line: PositionLine, totalSymbol: String) {
    val kind = when (line.kind) {
        LineKind.FREE -> "Livre"
        LineKind.STAKED -> "Em stake"
        LineKind.LIQUID_STAKING -> "Staking líquido"
    }
    val qty = if (line.counted != null && line.symbol != totalSymbol) {
        amountLine(line.ownAmount, line.symbol, h.hide) + "  →  " + amountLine(line.counted, totalSymbol, h.hide)
    } else amountLine(line.counted ?: line.ownAmount, if (line.counted != null) totalSymbol else line.symbol, h.hide)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (line.networkLabel.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(line.networkLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(qty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            line.rateSource?.let { src ->
                val per = line.rate
                if (per != null) Text("1 ${line.symbol} = ${Fmt.qty(per)} $totalSymbol · $src", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(h.money(line.valueUsd), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            line.freshness?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.warn, maxLines = 1) }
        }
    }
}

private fun cardModel(h: HubState, p: PositionTotal, showFiat: Boolean, signature: String): PositionCardModel {
    val status = when {
        p.partial -> "Leitura parcial"
        p.stale -> "Dados desatualizados"
        showFiat && p.valueIncomplete -> "Cotação incompleta"
        else -> null
    }
    val fiat = if (!showFiat || p.valueUsd == null) null else Fmt.money(h.conv(p.valueUsd), h.currency, false)
    return PositionCardModel(p.symbol, p.name, "${Fmt.qty(p.total)} ${p.symbol}", fiat, signature, status)
}

@Composable
fun PositionShareScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, anchor: String) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val key = remember(anchor) { Uri.decode(anchor) }
    val pos = remember(h, key) { positionOf(h, key) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val renderer = remember { PositionCardRenderer(ctx) }
    if (pos == null) {
        Column { V2TopBar("Cartão", { nav.popBackStack() }); EmptyLine(if (h.ready) "Este ativo não está mais no portfólio." else "Carregando…") }
        return
    }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    val typed = draft ?: h.settings.positionCardName
    val signature = CardName.sanitize(typed)
    val rejected = typed.isNotBlank() && signature.isBlank()
    var showFiat by rememberSaveable { mutableStateOf(false) }
    var logo by remember(pos.iconUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pos.iconUrl) {
        val url = pos.iconUrl
        if (url.isNullOrBlank()) { logo = null; return@LaunchedEffect }
        val bmp = withContext(Dispatchers.IO) {
            val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).size(512).build()
            val r = ctx.imageLoader.execute(req)
            (r as? SuccessResult)?.drawable as? BitmapDrawable
        }?.bitmap
        logo = bmp
    }
    val model = remember(pos, showFiat, signature, h.currency, h.fx, h.hide) { cardModel(h, pos, showFiat, signature) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model, logo) {
        preview = withContext(Dispatchers.Default) { renderer.render(model, logo) }
    }
    var lastFile by remember { mutableStateOf<File?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val f = lastFile
        if (uri != null && f != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { out -> f.inputStream().use { it.copyTo(out) } } }.isSuccess
            withContext(Dispatchers.Main) { vm.toast(if (ok) "Imagem salva." else "Não foi possível salvar a imagem.") }
        }
    }
    fun export(save: Boolean) = scope.launch {
        val b = preview ?: return@launch
        runCatching {
            val f = ShareExport.png(ctx, b, "coinportracker-posicao.png")
            lastFile = f
            if (!save) ShareExport.share(ctx, f, "image/png", "Compartilhar posição")
            else if (ShareExport.saveToGallery(ctx, f, "image/png")) vm.toast("Salvo em Imagens › CoinPortracker.")
            else saveAs.launch(f.name)
        }.onFailure { vm.toast("Não foi possível gerar o cartão.") }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        V2TopBar("Cartão", { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            val frame = Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(22.dp))
            val shown = preview
            if (shown == null) {
                ShimmerBlock(frame, 22.dp)
            } else if (reduced) {
                Image(shown.asImageBitmap(), "Prévia do cartão", frame, contentScale = ContentScale.Fit)
            } else {
                Crossfade(shown, label = "card") { bmp ->
                    Image(bmp.asImageBitmap(), "Prévia do cartão", frame, contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.height(16.dp))
            CpCard {
                Text("Nome no cartão", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Aparece como assinatura. Fica neste aparelho.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    typed, { v ->
                        val cut = v.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(CardName.MAX)
                        draft = cut
                        vm.updateSettings { it.copy(positionCardName = CardName.sanitize(cut)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Seu nome") },
                    isError = rejected,
                    supportingText = {
                        Text(
                            if (rejected) "Use um nome, sem endereço." else "O cartão não leva endereço nem carteira.",
                            color = if (rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Mostrar valor em ${h.currency.symbol.trim()}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (pos.valueUsd == null) "Sem cotação para este total." else "Desligado por privacidade.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CpSwitch(showFiat && pos.valueUsd != null, { if (pos.valueUsd != null) showFiat = it }, enabled = pos.valueUsd != null)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = preview != null, onClick = { export(false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp), colors = cpButtonColors()) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Compartilhar")
                }
                OutlinedButton(enabled = preview != null, onClick = { export(true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Salvar")
                }
            }
        }
    }
}
