package com.coinportracker.ui.screens

import com.coinportracker.ui.theme.sharedKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coinportracker.core.Fmt
import com.coinportracker.data.HubState
import com.coinportracker.onchain.AssetGroup
import com.coinportracker.portfolio.AssetResult
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import java.math.BigDecimal
import java.math.RoundingMode

/** Formatação compartilhada (pt-BR), sempre a partir de USD convertido para a moeda escolhida. */
fun HubState.money(usd: Double?): String = Fmt.money(conv(usd), currency, hide)
fun HubState.money(usd: BigDecimal?): String = Fmt.money(conv(usd), currency, hide)
fun HubState.signed(usd: BigDecimal?): String = Fmt.signedMoney(conv(usd), currency, hide)
fun HubState.signed(usd: Double?): String = Fmt.signedMoney(conv(usd), currency, hide)
fun HubState.price(usd: Double?): String = Fmt.price(conv(usd), currency, false)
fun HubState.price(usd: BigDecimal?): String = Fmt.price(conv(usd), currency, false)
fun HubState.compact(usd: Double?): String = if (hide) Fmt.MASK else Fmt.compact(conv(usd), currency)
fun pctOf(d: Double?): String = if (d == null || d.isNaN()) "—" else Fmt.pct(BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_EVEN))
/** Sentido de um valor exibido com 2 casas: o que arredonda para zero ("0,00") fica neutro (cinza, sem seta). */
fun upOf(d: Double?): Boolean? = when { d == null || d.isNaN() -> null; d >= 0.005 -> true; d <= -0.005 -> false; else -> null }
fun upOf(d: BigDecimal?): Boolean? = when { d == null -> null; else -> d.setScale(2, RoundingMode.HALF_EVEN).signum().let { if (it > 0) true else if (it < 0) false else null } }

/** Barra superior das telas internas: voltar + título, sem elevação. */
@Composable
fun V2TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** Linha de ativo (lista principal): ícone + selo da rede, nome/quantidade, valor e variação ou P&L. */
enum class RowMetric { CHANGE_24H, PNL }

@Composable
fun AssetRow(h: HubState, a: AssetGroup, r: AssetResult?, metric: RowMetric, onClick: () -> Unit, spinFirst: Boolean = true) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.sharedKey("icon:" + a.key)) { TokenIcon(a.iconUrl, a.symbol, 42.dp, a.networks.firstOrNull(), showBadge = a.networks.isNotEmpty()) }
        Spacer(Modifier.width(12.dp))
        // esquerda: símbolo (+ selos) e o preço com a variação 24 h — o preço nunca divide espaço com a quantidade
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.symbol, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.staking) { Spacer(Modifier.width(6.dp)); Tag("Stake") }
                if (a.networks.size > 1) { Spacer(Modifier.width(6.dp)); Tag("${a.networks.size} redes") }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // o preço encolhe/corta primeiro; a variação 24 h é medida antes e nunca é cortada
                Text(h.price(a.priceUsd), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.change24h != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(pctOf(a.change24h), style = MaterialTheme.typography.bodySmall, color = changeColor(upOf(a.change24h)), maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // direita: valor e (quantidade | lucro), com largura limitada para não espremer o preço e a variação
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 168.dp)) {
            val v = h.money(a.valueUsd)
            SlotText(v, MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), spinOnFirst = if (spinFirst) 1 else -1, staggerMs = 22, durationMs = 620)
            Spacer(Modifier.height(2.dp))
            when (metric) {
                RowMetric.CHANGE_24H -> Text(if (h.hide) Fmt.MASK else "${Fmt.qty(a.amount.setScale(if (a.amount >= BigDecimal.TEN) 4 else 6, RoundingMode.DOWN).stripTrailingZeros())} ${a.symbol.take(8)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                RowMetric.PNL -> if (r?.pnl != null) FitText(if (h.hide) Fmt.MASK else "${h.signed(r.pnl)} (${Fmt.pct(r.pnlPct)})", style = MaterialTheme.typography.labelMedium, color = changeColor(upOf(r.pnl)))
                    else Text("sem preço médio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LocalExtra.current.cardHigh).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
fun LegendDot(color: Color) { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) }

/** Valor com rótulo pequeno (blocos de resumo). */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, sub: String? = null) {
    Column(modifier) {
        FitText(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.8f)
        Spacer(Modifier.height(4.dp))
        if (value.any { it.isDigit() }) SlotText(value, MaterialTheme.typography.titleMedium, color = color, spinOnFirst = -1, staggerMs = 18)
        else Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { FitText(it, style = MaterialTheme.typography.labelMedium, color = if (color == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else color.copy(alpha = 0.8f), minScale = 0.8f) }
    }
}

/** Esqueleto da lista de ativos (primeira carga). */
@Composable
fun AssetSkeleton(rows: Int = 5) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ShimmerBlock(Modifier.size(42.dp), 21.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { ShimmerBlock(Modifier.size(70.dp, 14.dp)); Spacer(Modifier.height(6.dp)); ShimmerBlock(Modifier.size(120.dp, 11.dp)) }
                Column(horizontalAlignment = Alignment.End) { ShimmerBlock(Modifier.size(80.dp, 14.dp)); Spacer(Modifier.height(6.dp)); ShimmerBlock(Modifier.size(48.dp, 11.dp)) }
            }
        }
    }
}

fun walletAddressShort(a: String?): String = when { a == null -> ""; a.length > 14 -> a.take(6) + "…" + a.takeLast(4); else -> a }
