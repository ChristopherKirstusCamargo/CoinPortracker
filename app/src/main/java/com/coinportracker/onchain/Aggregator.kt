package com.coinportracker.onchain

import java.math.BigDecimal

enum class Visibility { VISIBLE, SMALL, SPAM, UNPRICED }

/** Origem de um saldo: carteira on-chain (id da carteira) ou posição manual do livro. */
data class HoldingSource(val walletId: Long, val walletName: String, val manual: Boolean = false)

data class PricedHolding(
    val source: HoldingSource,
    val holding: TokenHolding,
    val priceUsd: Double?,
    val change24h: Double?,
    val valueUsd: Double?,
    val visibility: Visibility,
)

data class AssetGroup(
    val key: String,
    val symbol: String,
    val name: String,
    val iconUrl: String?,
    val amount: BigDecimal,
    val valueUsd: Double,
    val priceUsd: Double?,
    val change24h: Double?,
    val networks: List<Network>,
    val priceKey: String?,
    val parts: List<PricedHolding>,
    val staking: Boolean,
    val manual: Boolean,
)

data class Slice(val key: String, val label: String, val valueUsd: Double, val share: Double, val color: Long?)

data class AggregatedPortfolio(
    val totalUsd: Double,
    val change24hUsd: Double,
    val change24hPct: Double?,
    val assets: List<AssetGroup>,
    val byNetwork: List<Slice>,
    val byWallet: List<Slice>,
    val staking: List<PricedHolding>,
    val hidden: List<PricedHolding>,
    val unpricedCount: Int,
)

/** Posição registrada manualmente (livro contábil da v1), valorizada junto com as carteiras on-chain. */
data class ManualPosition(val assetId: String, val symbol: String, val name: String, val quantity: BigDecimal, val coingeckoId: String?, val manualPriceUsd: Double?, val iconUrl: String?, val walletId: Long = -1, val walletName: String = "Manual")

object SpamFilter {
    private val bad = Regex("""(https?://|www\.|\.com\b|\.io\b|\.xyz\b|\.org\b|\.net\b|\.top\b|\.site\b|\.app\b|\.gift\b|t\.me|claim|visit|reward|airdrop|voucher|\$\s?\d)""", RegexOption.IGNORE_CASE)

    fun looksLikeSpam(symbol: String, name: String): Boolean = bad.containsMatchIn(symbol) || bad.containsMatchIn(name)

    fun classify(h: TokenHolding, price: LivePrice?, minUsd: Double): Pair<Visibility, Double?> {
        if (h.flaggedScam || (h.kind == HoldingKind.TOKEN && looksLikeSpam(h.symbol, h.name))) return Visibility.SPAM to null
        val p = price?.takeIf { (it.confidence ?: 1.0) >= 0.5 }?.usd
            ?: h.priceUsdHint?.takeIf { h.verified }
        if (p == null) return (if (h.kind == HoldingKind.TOKEN || h.kind == HoldingKind.LIQUID_STAKING) Visibility.UNPRICED else Visibility.VISIBLE) to null
        val v = h.amount.toDouble() * p
        if (v.isNaN() || v.isInfinite() || v > 1e13) return Visibility.SPAM to null
        return (if (v < minUsd && h.kind != HoldingKind.NATIVE && h.kind != HoldingKind.STAKED) Visibility.SMALL else Visibility.VISIBLE) to v
    }
}

object Aggregator {
    fun groupKey(h: TokenHolding): String = when {
        h.kind == HoldingKind.STAKED -> "staked:${h.coingeckoId ?: h.symbol}"
        h.coingeckoId != null -> "cg:${h.coingeckoId}"
        h.verified -> "sym:${h.symbol.uppercase()}"
        else -> "key:${h.priceKey ?: (h.network.name + h.tokenKey)}"
    }

    /** Chave de grupo de um ativo do livro de operações (mesma regra usada para posições manuais). */
    fun ledgerGroupKey(assetId: String, coingeckoId: String?): String = if (coingeckoId != null) "cg:$coingeckoId" else "manual:manual:$assetId"

    /** Ativo em staking herda o custo do ativo base (ex.: SOL em stake usa o preço médio de SOL). */
    fun costAlias(groupKey: String): String? = if (groupKey.startsWith("staked:")) "cg:" + groupKey.removePrefix("staked:") else null

    fun aggregate(
        onchain: List<Pair<HoldingSource, TokenHolding>>,
        manual: List<ManualPosition>,
        prices: Map<String, LivePrice>,
        minUsd: Double,
        manualSource: HoldingSource = HoldingSource(-1, "Manual", manual = true),
    ): AggregatedPortfolio {
        val priced = ArrayList<PricedHolding>(onchain.size + manual.size)
        for ((src, h) in onchain) {
            val lp = h.priceKey?.let { prices[it] }
            val (vis, value) = SpamFilter.classify(h, lp, minUsd)
            val unit = if (value != null && h.amount.signum() > 0) value / h.amount.toDouble() else null
            priced += PricedHolding(src, h, unit, lp?.change24hPct, value, vis)
        }
        for (m in manual) {
            if (m.quantity.signum() <= 0) continue
            val key = m.coingeckoId?.let { "coingecko:$it" }
            val lp = key?.let { prices[it] }
            val unit = lp?.usd ?: m.manualPriceUsd
            val h = TokenHolding(Network.ETHEREUM, "manual:${m.assetId}", m.symbol, m.name, 18, m.quantity, m.iconUrl, key, HoldingKind.TOKEN, true, coingeckoId = m.coingeckoId)
            val src = if (m.walletId == -1L) manualSource else HoldingSource(m.walletId, m.walletName, manual = true)
            priced += PricedHolding(src, h, unit, lp?.change24hPct, unit?.let { it * m.quantity.toDouble() }, if (unit == null) Visibility.UNPRICED else Visibility.VISIBLE)
        }
        val visible = priced.filter { it.visibility == Visibility.VISIBLE }
        val hidden = priced.filter { it.visibility != Visibility.VISIBLE }
        val total = visible.sumOf { it.valueUsd ?: 0.0 }
        var prevTotal = 0.0
        for (p in visible) {
            val v = p.valueUsd ?: continue
            val c = p.change24h
            prevTotal += if (c != null && c > -100) v / (1 + c / 100.0) else v
        }
        val change = total - prevTotal
        val changePct = if (prevTotal > 0) change / prevTotal * 100 else null

        val groups = visible.groupBy { if (it.source.manual) groupKey(it.holding).let { k -> if (it.holding.coingeckoId == null) "manual:${it.holding.tokenKey}" else k } else groupKey(it.holding) }
            .map { (k, parts) ->
                val top = parts.maxBy { it.valueUsd ?: 0.0 }
                val value = parts.sumOf { it.valueUsd ?: 0.0 }
                val amount = parts.fold(BigDecimal.ZERO) { a, p -> a.add(p.holding.amount) }
                val wChange = parts.filter { it.change24h != null && (it.valueUsd ?: 0.0) > 0 }
                val chg = if (wChange.isEmpty()) null else wChange.sumOf { it.change24h!! * (it.valueUsd ?: 0.0) } / wChange.sumOf { it.valueUsd ?: 0.0 }
                AssetGroup(k, top.holding.symbol, if (top.holding.kind == HoldingKind.STAKED) "${top.holding.symbol} em staking" else top.holding.name,
                    parts.firstNotNullOfOrNull { it.holding.iconUrl }, amount, value, top.priceUsd, chg,
                    parts.filter { !it.source.manual }.map { it.holding.network }.distinct(), top.holding.priceKey, parts.sortedByDescending { it.valueUsd ?: 0.0 },
                    parts.any { it.holding.kind == HoldingKind.STAKED || it.holding.kind == HoldingKind.LIQUID_STAKING }, parts.all { it.source.manual })
            }.sortedByDescending { it.valueUsd }

        fun slices(items: Map<String, Pair<String, Double>>, color: (String) -> Long?): List<Slice> =
            items.entries.filter { it.value.second > 0 }.sortedByDescending { it.value.second }
                .map { (k, v) -> Slice(k, v.first, v.second, if (total > 0) v.second / total else 0.0, color(k)) }

        val byNet = LinkedHashMap<String, Pair<String, Double>>()
        val byWallet = LinkedHashMap<String, Pair<String, Double>>()
        for (p in visible) {
            val v = p.valueUsd ?: continue
            val nk = if (p.source.manual) "MANUAL" else p.holding.network.name
            val nl = if (p.source.manual) "Manual" else p.holding.network.label
            byNet[nk] = nl to ((byNet[nk]?.second ?: 0.0) + v)
            val wk = p.source.walletId.toString()
            byWallet[wk] = p.source.walletName to ((byWallet[wk]?.second ?: 0.0) + v)
        }
        val staking = visible.filter { it.holding.kind == HoldingKind.STAKED || it.holding.kind == HoldingKind.LIQUID_STAKING }.sortedByDescending { it.valueUsd ?: 0.0 }
        return AggregatedPortfolio(total, change, changePct, groups,
            slices(byNet) { k -> Network.byName(k)?.color }, slices(byWallet) { null }, staking,
            hidden.sortedByDescending { it.valueUsd ?: 0.0 }, hidden.count { it.visibility == Visibility.UNPRICED })
    }

    /**
     * Evolução do valor dos ativos atuais: Σ quantidade × preço histórico em cada instante.
     * Linha do tempo = série mais longa; demais séries pelo último ponto anterior (ou o primeiro disponível).
     * Ativos sem série entram como constante pelo preço atual. O último ponto é sempre o valor ao vivo.
     */
    fun valueSeries(weights: Map<String, Double>, series: Map<String, List<PricePoint>>, current: Map<String, Double>, now: Long): List<PricePoint> {
        val usable = weights.filter { (k, w) -> w > 0 && !series[k].isNullOrEmpty() }
        val constant = weights.filter { (k, w) -> w > 0 && series[k].isNullOrEmpty() }.entries.sumOf { (k, w) -> w * (current[k] ?: 0.0) }
        val live = constant + usable.entries.sumOf { (k, w) -> w * (current[k] ?: series[k]!!.last().usd) }
        if (usable.isEmpty()) return emptyList()
        val timeline = usable.keys.maxBy { k -> series[k]!!.size.toDouble() * 1e15 + usable[k]!! * series[k]!!.last().usd }
            .let { k -> series[k]!!.map { it.t } }.filter { it < now }
        val out = ArrayList<PricePoint>(timeline.size + 1)
        val idx = HashMap<String, Int>()
        for (t in timeline) {
            var sum = constant
            for ((k, w) in usable) {
                val s = series[k]!!
                var i = idx[k] ?: 0
                while (i + 1 < s.size && s[i + 1].t <= t) i++
                idx[k] = i
                sum += w * s[i].usd
            }
            out += PricePoint(t, sum)
        }
        out += PricePoint(now, live)
        return out
    }
}
