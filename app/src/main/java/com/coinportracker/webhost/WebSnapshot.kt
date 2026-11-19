package com.coinportracker.webhost

import com.coinportracker.core.Fmt
import com.coinportracker.data.ActivityClassifier
import com.coinportracker.data.ActivityKind
import com.coinportracker.data.ChartRange
import com.coinportracker.data.HubState
import com.coinportracker.database.ChainActivityEntity
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.PricePoint
import com.coinportracker.ui.ChartData
import com.coinportracker.ui.screens.chartPoints
import com.coinportracker.ui.screens.money
import com.coinportracker.ui.screens.investedMoney
import com.coinportracker.ui.screens.pctOf
import com.coinportracker.ui.screens.price
import com.coinportracker.ui.screens.realPnl
import com.coinportracker.ui.screens.signed
import com.coinportracker.ui.screens.stakingYieldUsd
import com.coinportracker.ui.screens.upOf
import com.coinportracker.ui.screens.walletAddressShort
import java.math.BigDecimal

/**
 * v6.0 — JSON do painel web, montado com as MESMAS funções de formatação e cálculo das telas do app (Resultado real,
 * moeda, ocultar saldos). Com "Ocultar saldos" ligado, todo valor sai mascarado e o gráfico só mostra a forma (0–1).
 * Nenhum endereço completo sai: só o recorte usado no app.
 */
object WebSnapshot {
    private fun s(v: String?) = WebJson.str(v)
    private fun up(b: Boolean?) = when (b) { true -> "\"up\""; false -> "\"down\""; null -> "null" }
    private fun arr(items: List<String>) = items.joinToString(",", "[", "]")

    fun summary(h: HubState, now: Long = System.currentTimeMillis()): String {
        val real = h.realPnl(null)
        val p = h.pnl
        return buildString {
            append("{\"ready\":").append(h.ready).append(",\"hidden\":").append(h.hide).append(",\"currency\":").append(s(h.currency.name))
            append(",\"total\":").append(s(h.money(h.agg.totalUsd)))
            append(",\"change24h\":").append(s(h.signed(h.agg.change24hUsd))).append(",\"change24hPct\":").append(s(pctOf(h.agg.change24hPct))).append(",\"change24hDir\":").append(up(upOf(h.agg.change24hPct)))
            if (real != null) {
                append(",\"invested\":").append(s(h.investedMoney(real.invested))).append(",\"value\":").append(s(h.investedMoney(real.value)))
                append(",\"result\":").append(s(h.investedMoney(real.result, signed = true))).append(",\"resultPct\":").append(s(pctOf(real.resultPct))).append(",\"resultDir\":").append(up(upOf(real.result)))
                if (real.income > 0.005) append(",\"income\":").append(s(h.investedMoney(real.income)))
            }
            append(",\"unrealized\":").append(s(h.signed(p.unrealized))).append(",\"realized\":").append(s(h.signed(p.realized)))
            append(",\"stakingRewards\":").append(s(h.money(h.stakingYieldUsd())))
            append(",\"assets\":").append(h.agg.assets.size).append(",\"wallets\":").append(h.wallets.size).append(",\"unpriced\":").append(h.agg.unpricedCount)
            append(",\"lastSync\":").append(h.wallets.mapNotNull { it.lastSyncAt }.maxOrNull() ?: 0)
            append(",\"errors\":").append(h.wallets.count { it.error != null }).append(",\"now\":").append(now).append('}')
        }
    }

    fun assets(h: HubState): String {
        val total = h.agg.totalUsd.takeIf { it > 0 }
        val rows = h.agg.assets.map { a ->
            val r = h.pnl.assets[a.key]
            buildString {
                append("{\"key\":").append(s(a.key.take(80))).append(",\"symbol\":").append(s(a.symbol.take(16))).append(",\"name\":").append(s(a.name.take(48)))
                append(",\"amount\":").append(s(Fmt.qty(a.amount, h.hide))).append(",\"price\":").append(s(h.price(a.priceUsd)))
                append(",\"value\":").append(s(h.money(a.valueUsd))).append(",\"sort\":").append(if (h.hide) 0.0 else a.valueUsd)
                append(",\"share\":").append(s(total?.let { pctOf(a.valueUsd / it * 100).removePrefix("+") } ?: "—"))
                append(",\"change24h\":").append(s(pctOf(a.change24h))).append(",\"change24hDir\":").append(up(upOf(a.change24h)))
                append(",\"pnl\":").append(s(r?.pnl?.let { h.signed(it) } ?: "—")).append(",\"pnlPct\":").append(s(r?.pnlPct?.let { Fmt.pct(it) } ?: "—")).append(",\"pnlDir\":").append(up(upOf(r?.pnl)))
                append(",\"networks\":").append(arr(a.networks.map { s(it.label) })).append(",\"staking\":").append(a.staking).append(",\"manual\":").append(a.manual).append('}')
            }
        }
        return "{\"hidden\":${h.hide},\"items\":${arr(rows)}}"
    }

    fun allocation(h: HubState): String {
        val total = h.agg.totalUsd.takeIf { it > 0 } ?: 0.0
        val byAsset = h.agg.assets.take(12).map { a -> slice(h, a.symbol, a.valueUsd, if (total > 0) a.valueUsd / total else 0.0) } +
            (h.agg.assets.drop(12).sumOf { it.valueUsd }.takeIf { it > 0 }?.let { listOf(slice(h, "Outros", it, if (total > 0) it / total else 0.0)) } ?: emptyList())
        val byNet = h.agg.byNetwork.map { slice(h, it.label, it.valueUsd, it.share) }
        val byWallet = h.agg.byWallet.map { slice(h, it.label, it.valueUsd, it.share) }
        return "{\"byAsset\":${arr(byAsset)},\"byNetwork\":${arr(byNet)},\"byWallet\":${arr(byWallet)}}"
    }

    private fun slice(h: HubState, label: String, usd: Double, share: Double) =
        "{\"label\":${s(label.take(48))},\"value\":${s(h.money(usd))},\"share\":${"%.4f".format(java.util.Locale.ROOT, share.coerceIn(0.0, 1.0))}}"

    fun staking(h: HubState): String {
        val items = h.stakes.map { st ->
            "{\"account\":${s(walletAddressShort(st.account))},\"principal\":${s(Fmt.qty(BigDecimal.valueOf(st.principalSol), h.hide) + " SOL")}," +
                "\"rewards\":${s(Fmt.qty(BigDecimal.valueOf(st.rewardsSol), h.hide) + " SOL")},\"rewardsValue\":${s(h.money(st.rewardsUsd))}," +
                "\"apr\":${s(st.aprPct?.let { Fmt.pctD(it, signed = false) } ?: "—")},\"complete\":${st.complete},\"epochs\":${st.epochs.size}}"
        }
        return "{\"total\":${s(h.money(h.stakingYieldUsd()))},\"items\":${arr(items)}}"
    }

    fun activity(h: HubState, rows: List<ChainActivityEntity>, stakeSigs: Set<String>, q: Map<String, String>): String {
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val offset = q["offset"]?.toIntOrNull()?.coerceIn(0, 5000) ?: 0
        val kind = q["kind"]?.let { k -> ActivityKind.entries.firstOrNull { it.name == k } }
        val text = q["q"]?.trim()?.lowercase()?.take(40).orEmpty()
        val names = h.wallets.associate { it.id to it.name }
        val all = ActivityClassifier.classify(rows, stakeSigs, keepUndated = true)
            .filter { kind == null || it.kind == kind }
            .filter { c -> text.isEmpty() || c.rows.any { r -> r.symbol.lowercase().contains(text) || (Network.byName(r.network)?.label ?: r.network).lowercase().contains(text) || names[r.walletId]?.lowercase()?.contains(text) == true } }
            .sortedByDescending { it.timestamp }
        val page = all.drop(offset).take(limit).map { c ->
            val r = c.primary
            val amount = r.amount?.toBigDecimalOrNull()
            val sign = when (r.direction) { "IN" -> "+"; "OUT" -> "−"; else -> "" }
            "{\"kind\":${s(c.kind.name)},\"label\":${s(c.kind.label)},\"symbol\":${s(r.symbol.take(16))}," +
                "\"amount\":${s(if (amount == null) "—" else if (h.hide) Fmt.MASK else sign + Fmt.qty(amount.abs()))},\"parts\":${c.rows.size}," +
                "\"network\":${s(Network.byName(r.network)?.label ?: r.network.take(24))},\"wallet\":${s(names[r.walletId]?.take(40) ?: "")}," +
                "\"time\":${c.timestamp},\"failed\":${r.failed}}"
        }
        return "{\"total\":${all.size},\"offset\":$offset,\"items\":${arr(page)}}"
    }

    fun range(q: Map<String, String>): ChartRange = ChartRange.entries.firstOrNull { it.name == q["range"] } ?: ChartRange.M1

    fun chart(h: HubState, range: ChartRange, data: ChartData?): String {
        val pts: List<PricePoint> = h.chartPoints(data).orEmpty()
        if (pts.size < 2) return "{\"range\":${s(range.name)},\"points\":[],\"normalized\":${h.hide}}"
        val vals = pts.map { p -> if (h.hide) p.usd else h.conv(p.usd)?.toDouble() ?: p.usd }
        val (lo, hi) = vals.min() to vals.max()
        val out = pts.indices.map { i ->
            val v = if (h.hide) (if (hi > lo) (vals[i] - lo) / (hi - lo) else 0.5) else vals[i]
            "[${pts[i].t},${"%.6f".format(java.util.Locale.ROOT, v)}]"
        }
        return "{\"range\":${s(range.name)},\"label\":${s(range.label)},\"normalized\":${h.hide},\"currency\":${s(h.currency.name)}," +
            "\"start\":${s(if (h.hide) null else h.money(pts.first().usd))},\"end\":${s(if (h.hide) null else h.money(pts.last().usd))},\"points\":${arr(out)}}"
    }
}
