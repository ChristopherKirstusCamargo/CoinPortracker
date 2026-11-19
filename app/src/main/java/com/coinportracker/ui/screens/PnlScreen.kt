package com.coinportracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.data.ChartRange
import com.coinportracker.data.HubState
import com.coinportracker.history.HistoryInputs
import com.coinportracker.history.PosKind
import com.coinportracker.portfolio.CostSource
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.reveal
import java.math.BigDecimal
import kotlin.math.abs

/** Resultado real com o valor pago informado nos Ajustes (null se não informado ou sem câmbio). */
internal fun HubState.stakingYieldUsd(): Double = stakes.sumOf { it.rewardsUsd ?: 0.0 } + stakingIncomeUsd.toDouble()

internal fun HubState.realPnl(series: List<com.coinportracker.onchain.PricePoint>?, principalUsd: List<Double>? = null): com.coinportracker.portfolio.RealPnl? {
    val inv = settings.invested ?: return null
    val f = investedFx() ?: return null
    val yieldCur = stakingYieldUsd() * f
    return com.coinportracker.portfolio.RealPnlCalc.compute(inv.toDouble(), agg.totalUsd, f, series.orEmpty().map { it.t to it.usd }, settings.investedSince, pnl.income.toDouble(), principalUsd, stakingYieldCur = yieldCur)
}

/**
 * v5 — fonte única do Resultado real para Início, Análise e Lucro e prejuízo: usa a série de ganho de mercado do gráfico
 * (quando existe) para Lucro máximo, Devolvido e Maior queda, e o mesmo valor/total pago do topo.
 */
internal fun HubState.realPnlFor(chart: com.coinportracker.ui.ChartData?): com.coinportracker.portfolio.RealPnl? {
    val inv = settings.invested ?: return null
    val f = investedFx() ?: return null
    val pts = chart?.points
    val res = chart?.result
    return com.coinportracker.portfolio.RealPnlCalc.compute(inv.toDouble(), agg.totalUsd, f, pts.orEmpty().map { it.t to it.usd }, settings.investedSince,
        pnl.income.toDouble(), null, stakingYieldCur = stakingYieldUsd() * f, gainUsd = res?.gainSeries?.takeIf { it.size == pts?.size })
}

/**
 * Pontos que o gráfico desenha. Com total pago informado e histórico reconstruído: total pago + lucro em cada instante
 * (mesma linha do Resultado real; transferências não viram degrau nem "prejuízo" antes de as moedas chegarem).
 * Sem total pago: o valor do portfólio no tempo, como antes.
 */
internal fun HubState.chartPoints(chart: com.coinportracker.ui.ChartData?): List<com.coinportracker.onchain.PricePoint>? {
    val pts = chart?.points ?: return null
    if (settings.invested == null || investedFx() == null) return pts
    val line = com.coinportracker.portfolio.RealPnlCalc.line(agg.totalUsd, chart.result?.gainSeries, pts.size) ?: return pts
    return List(pts.size) { com.coinportracker.onchain.PricePoint(pts[it].t, line[it]) }
}

/** USD → moeda em que o valor pago foi informado. */
internal fun HubState.investedFx(): Double? = if (settings.investedCurrency == com.coinportracker.domain.Currency.USD) 1.0 else fx?.toDouble()

/** Valor pago convertido para USD (escala dos gráficos). */
internal fun HubState.investedUsd(): Double? { val inv = settings.invested?.toDouble()?.takeIf { it > 0 } ?: return null; val f = investedFx() ?: return null; return inv / f }

internal fun HubState.investedMoney(v: Double, signed: Boolean = false): String {
    val c = settings.investedCurrency
    val bd = java.math.BigDecimal.valueOf(v)
    return if (signed) Fmt.signedMoney(bd, c, hide) else Fmt.money(bd, c, hide)
}

@Composable
fun PnlScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val p = h.pnl
    val rows = remember(h.pnl, h.agg) {
        h.agg.assets.mapNotNull { a -> p.assets[a.key]?.let { a to it } }.sortedByDescending { (_, r) -> r.pnl?.abs() ?: BigDecimal.valueOf(-1) }
    }
    val cd = chart?.takeIf { it.range == range }
    val pts = remember(h, cd) { h.chartPoints(cd) }
    val real = remember(h, cd) { h.realPnlFor(cd) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item(key = "top") { V2TopBar("Lucro e prejuízo", { nav.popBackStack() }) }
        item(key = "real") {
            if (real == null) CpCard(Modifier.padding(top = 4.dp), onClick = { nav.navigate("settings/portfolio") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Resultado real", style = MaterialTheme.typography.titleSmall)
                        Text("Informe quanto você pagou pelo portfólio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else CpCard(Modifier.padding(top = 4.dp)) {
                Text("Resultado real", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(h.investedMoney(real.result, signed = true), style = MaterialTheme.typography.headlineSmall, color = changeColor(upOf(real.result)), modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp)); ChangePill(pctOf(real.resultPct), upOf(real.result))
                }
                Spacer(Modifier.height(12.dp))
                pts?.takeIf { it.size >= 2 }?.let { p ->
                    val invUsd = h.investedUsd()
                    val xs = remember(p) { LongArray(p.size) { p[it].t } }
                    val ys = remember(p) { FloatArray(p.size) { p[it].usd.toFloat() } }
                    val bs = remember(invUsd, p) { invUsd?.let { v -> FloatArray(p.size) { v.toFloat() } } }
                    PnlChart(xs, ys, bs, changeColor(real.inProfit), LocalExtra.current.gain, LocalExtra.current.loss, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.fillMaxWidth().height(150.dp)) {}
                }
                Spacer(Modifier.height(10.dp))
                // três números principais
                val topLabel = if (real.inProfit) "Lucro" else "Perda"
                val topValue = h.investedMoney(if (real.inProfit) real.result else -real.lossOnPrincipal, signed = true)
                if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) {
                    // fonte muito grande: as três linhas empilhadas, valores inteiros
                    KeyValue("Investido", h.investedMoney(real.invested))
                    KeyValue("Valor atual", h.investedMoney(real.value))
                    KeyValue(topLabel, topValue, changeColor(real.inProfit))
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigStat("Investido", h.investedMoney(real.invested), "do seu bolso", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    BigStat("Valor atual", h.investedMoney(real.value), null, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    BigStat(topLabel, topValue, null, changeColor(real.inProfit), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                if (real.peakProfit > 0.005) KeyValue("Lucro máximo", h.investedMoney(real.peakProfit, signed = true), changeColor(true))
                if (real.givenBack > 0.005) KeyValue("Devolvido do lucro", h.investedMoney(-real.givenBack, signed = true), changeColor(false))
                // decomposição: preço (do histórico reconstruído) + rendimentos + outros (taxas, ajustes, histórico parcial)
                val f = h.investedFx() ?: 1.0
                val mkt = cd?.result?.takeIf { range == ChartRange.ALL }?.marketUsd?.times(f)
                if (mkt != null) {
                    KeyValue("Valorização (preço)", h.investedMoney(mkt, signed = true), changeColor(upOf(mkt)))
                    if (real.income > 0.005) KeyValue("Rendimentos de staking", h.investedMoney(real.income, signed = true), changeColor(upOf(real.income)))
                    val other = real.result - mkt
                    if (kotlin.math.abs(other) >= 0.5) KeyValue("Outros (taxas, ajustes)", h.investedMoney(other, signed = true), changeColor(upOf(other)))
                } else if (real.income > 0.005) {
                    KeyValue("Valorização", h.investedMoney(real.market, signed = true), changeColor(upOf(real.market)))
                    KeyValue("Rendimentos de staking", h.investedMoney(real.income, signed = true), changeColor(upOf(real.income)))
                }
                if (real.breakEvenPct > 0) KeyValue("Alta para empatar", "+" + Fmt.num(real.breakEvenPct, 1) + "%")
                real.maxDrawdown?.takeIf { it > 0.0005 }?.let { KeyValue("Maior queda ${range.periodLabel()}", "-" + Fmt.num(it * 100, 1) + "%", changeColor(false)) }
                Spacer(Modifier.height(10.dp))
                RangeChips(ChartRange.entries, range, { it.label }, vm::setRange)
            }
        }
        // período
        item(key = "period") {
            val data = chart?.takeIf { it.range == range }
            val res = data?.result
            CpCard(Modifier.padding(top = 12.dp)) {
                Text("Desempenho de mercado ${range.periodLabel()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(if (res != null) h.signed(res.pnlUsd) else "—", style = MaterialTheme.typography.headlineSmall, color = changeColor(upOf(res?.pnlUsd)), modifier = Modifier.weight(1f, fill = false))
                    res?.pnlPct?.let { Spacer(Modifier.width(8.dp)); ChangePill(pctOf(it), upOf(res.pnlUsd)) }
                }
                if (real == null) { Spacer(Modifier.height(12.dp)); RangeChips(ChartRange.entries, range, { it.label }, vm::setRange) }
                if (res != null) {
                    Spacer(Modifier.height(10.dp))
                    KeyValue("Efeito de preço", h.signed(res.marketUsd), changeColor(upOf(res.marketUsd)))
                    KeyValue("Rendimentos de staking no período", h.signed(res.incomeUsd), changeColor(upOf(res.incomeUsd)))
                    KeyValue("Aportes líquidos (fora)", h.signed(res.netFlowsUsd))
                    KeyValue("Valor no início", h.money(res.first))
                    KeyValue("Valor agora", h.money(res.last))
                    if (res.partial && res.reliableFrom != null) Text("Histórico desde ${Fmt.date(res.reliableFrom)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                } else if (data != null) Text("Sem histórico suficiente para este período ainda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        // total desde o custo
        item(key = "total") {
            val hasCost = p.openCost.signum() != 0 || p.realized.signum() != 0 || p.income.signum() != 0
            val estimated = remember(p) { p.assets.values.any { it.source == CostSource.ESTIMATED } }
            CpCard(Modifier.padding(top = 12.dp).reveal()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Estimativa por ativo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                    if (hasCost && estimated) { Spacer(Modifier.width(6.dp)); Tag("Estimado") }
                }
                Spacer(Modifier.height(4.dp))
                if (!hasCost) {
                    // nenhum ativo com custo conhecido: um "—" limpo em vez de várias linhas de R$ 0,00
                    Text("—", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Sem preço médio", h.money(p.valueWithoutCost))
                    return@CpCard
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(h.signed(p.total), style = MaterialTheme.typography.headlineSmall, color = changeColor(upOf(p.total)), modifier = Modifier.weight(1f, fill = false))
                    p.totalPct?.let { Spacer(Modifier.width(8.dp)); ChangePill(Fmt.pct(it), upOf(p.total)) }
                }
                // só as parcelas que existem (zero não vira barra vazia "R$ 0,00")
                val parts = listOf("Não realizado" to p.unrealized, "Realizado" to p.realized, "Rendimentos" to p.income).filter { it.second.signum() != 0 }
                if (parts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val maxAbs = parts.maxOf { it.second.abs() }.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
                    parts.forEach { (l, v) -> BreakdownBar(l, h.signed(v), (v.abs().toDouble() / maxAbs.toDouble()).toFloat(), changeColor(upOf(v))) }
                }
                Spacer(Modifier.height(6.dp))
                if (p.openCost.signum() != 0) KeyValue("Custo das posições abertas", h.money(p.openCost))
                if (p.missingCost > 0) KeyValue("Sem preço médio", h.money(p.valueWithoutCost))
            }
        }
        // staking e DeFi
        val defi = h.agg.assets.flatMap { a -> a.parts.map { a to it } }.mapNotNull { (a, part) ->
            val k = HistoryInputs.kindOf(part.holding.kind.name, part.holding.symbol, part.holding.name)
            if (k == PosKind.LIQUID_STAKING || k == PosKind.LENDING || k == PosKind.LP) Triple(a, part, k) else null
        }
        if (h.stakes.isNotEmpty() || defi.isNotEmpty()) item(key = "stakeTitle") { SectionTitle("Staking") }
        items(h.stakes, key = { "st:" + it.account }) { s -> StakeCard(h, s) }
        if (defi.isNotEmpty()) item(key = "defi") {
            CpCard(Modifier.padding(top = 8.dp).reveal()) {
                defi.forEach { (a, part, k) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TokenIcon(part.holding.iconUrl ?: a.iconUrl, part.holding.symbol, 32.dp, part.holding.network, showBadge = true)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(part.holding.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when (k) { PosKind.LIQUID_STAKING -> "Staking líquido"; PosKind.LENDING -> "Empréstimo (lending)"; else -> "Liquidez (LP)" } + " · " + part.holding.network.label,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        FitText(h.money(part.priceUsd?.let { part.holding.amount.toDouble() * it }), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        // por ativo
        item(key = "assetsTitle") { SectionTitle("Por ativo") }
        items(rows, key = { "a:" + it.first.key }) { (a, r) ->
            Row(Modifier.reveal().fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { nav.navigate("asset2/${android.net.Uri.encode(a.key)}") }.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TokenIcon(a.iconUrl, a.symbol, 38.dp, a.networks.firstOrNull(), showBadge = a.networks.isNotEmpty())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.symbol, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (a.staking) { Spacer(Modifier.width(6.dp)); Tag("Stake") }
                    }
                    Text(sourceLine(h, r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 150.dp)) {
                    FitText(if (r.pnl != null) h.signed(r.pnl) else "—", style = MaterialTheme.typography.titleSmall, color = changeColor(upOf(r.pnl)))
                    Text(r.pnlPct?.let { Fmt.pct(it) } ?: "sem custo", style = MaterialTheme.typography.labelMedium, color = if (r.pnl != null) changeColor(upOf(r.pnl)) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** "PM R$ 1.234,56 · Estimado · Alta" */
internal fun sourceLine(h: HubState, r: com.coinportracker.portfolio.AssetResult): String {
    val pm = r.avgCost?.let { "PM ${h.price(it)}" }
    val src = when (r.source) {
        CostSource.ESTIMATED -> "Estimado · confiança ${r.estimate?.confidence?.label?.lowercase() ?: "—"}"
        CostSource.USER -> "Informado por você"
        CostSource.LEDGER -> "Suas operações"
        CostSource.NONE -> "Sem histórico de compra"
        CostSource.ALLOCATED -> "Estimado · rateio do total pago"
    }
    // as duas linhas de SOL (em stake e livre) precisam ser distinguíveis
    val kind = if (r.groupKey.startsWith("staked:")) "Em stake" else null
    return listOfNotNull(kind, pm, src).joinToString(" · ")
}

@Composable
private fun BigStat(label: String, value: String, sub: String?, color: Color, modifier: Modifier) {
    Column(modifier) {
        FitText(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.8f)
        Spacer(Modifier.height(2.dp))
        FitText(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = color)
        sub?.let { FitText(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.8f) }
    }
}

@Composable
private fun BreakdownBar(label: String, value: String, frac: Float, color: Color) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(value, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1) }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(LocalExtra.current.cardHigh)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0.015f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StakeCard(h: HubState, s: com.coinportracker.data.StakeView) {
    CpCard(Modifier.padding(top = 8.dp).reveal()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(com.coinportracker.onchain.Network.SOLANA.iconUrl, "SOL", 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("SOL em stake", style = MaterialTheme.typography.titleSmall)
                Text("Conta ${walletAddressShort(s.account)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.aprPct?.let { Tag("APR ${Fmt.pct(java.math.BigDecimal.valueOf(it), signed = false)}") }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Stat("Recompensas", if (h.hide) Fmt.MASK else "+${Fmt.num(s.rewardsSol, 4)} SOL", Modifier.weight(1f), LocalExtra.current.gain, sub = s.rewardsUsd?.let { h.money(it) })
            Stat("Delegado", if (h.hide) Fmt.MASK else "${Fmt.num(s.principalSol, 4)} SOL", Modifier.weight(1f))
        }
        s.lastEpochSol?.let {
            Spacer(Modifier.height(8.dp))
            // quebra no separador (nunca no meio da data), as duas partes alinhadas à esquerda
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val st = MaterialTheme.typography.bodySmall; val c = MaterialTheme.colorScheme.onSurfaceVariant
                Text("Última época: " + (if (h.hide) Fmt.MASK else "+${Fmt.num(it, 6)} SOL"), style = st, color = c, maxLines = 1)
                s.activatedAt?.let { a -> Text("· ativo desde ${Fmt.date(a)}", style = st, color = c, maxLines = 1) }
            }
        }
        if (!s.complete) Text("Estimado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Valor do portfólio (na moeda do valor pago) com a linha do valor pago: acima em verde, abaixo em vermelho. */
@Composable
private fun InvestedChart(pts: List<com.coinportracker.onchain.PricePoint>, invested: Double, fx: Double, modifier: Modifier) {
    val x = LocalExtra.current
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val vs = remember(pts, fx) { pts.map { it.usd * fx } }
    androidx.compose.foundation.Canvas(modifier) {
        val lo = minOf(vs.min(), invested); val hi = maxOf(vs.max(), invested)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun yOf(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat().coerceIn(1f, size.height - 1f)
        val stepX = size.width / (vs.size - 1).coerceAtLeast(1)
        val yInv = yOf(invested)
        val path = androidx.compose.ui.graphics.Path()
        vs.forEachIndexed { i, v -> val px = i * stepX; if (i == 0) path.moveTo(px, yOf(v)) else path.lineTo(px, yOf(v)) }
        val area = androidx.compose.ui.graphics.Path().apply { addPath(path); lineTo(size.width, yInv); lineTo(0f, yInv); close() }
        clipRect(bottom = yInv) { drawPath(area, x.gain.copy(alpha = 0.18f)) }
        clipRect(top = yInv) { drawPath(area, x.loss.copy(alpha = 0.18f)) }
        clipRect(bottom = yInv) { drawPath(path, x.gain, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
        clipRect(top = yInv) { drawPath(path, x.loss, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
        drawLine(line.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, yInv), androidx.compose.ui.geometry.Offset(size.width, yInv), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
}
