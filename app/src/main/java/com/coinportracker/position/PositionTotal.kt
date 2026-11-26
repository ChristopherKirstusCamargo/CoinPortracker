package com.coinportracker.position

import com.coinportracker.core.Decimals
import com.coinportracker.onchain.Aggregator
import com.coinportracker.onchain.AssetGroup
import com.coinportracker.onchain.HoldingKind
import com.coinportracker.onchain.PricedHolding
import com.coinportracker.onchain.Visibility
import java.math.BigDecimal

/**
 * De qual ativo-base um token de staking líquido é derivado.
 * Isto só agrupa a linha. Não converte quantidade: sem taxa verificada, o saldo fica de fora do total.
 */
data class LstLink(val symbol: String, val underlyingId: String, val underlyingSymbol: String)

object LstCatalog {
    private fun sol(s: String) = LstLink(s, "solana", "SOL")
    private fun eth(s: String) = LstLink(s, "ethereum", "ETH")
    private fun bnb(s: String) = LstLink(s, "binancecoin", "BNB")
    private fun pol(s: String) = LstLink(s, "polygon-ecosystem-token", "POL")

    private val bySymbol: Map<String, LstLink> = listOf(
        sol("MSOL"), sol("JITOSOL"), sol("BSOL"), sol("JUPSOL"), sol("INF"), sol("BNSOL"), sol("HSOL"), sol("VSOL"),
        sol("DSOL"), sol("STSOL"), sol("LST"), sol("BBSOL"), sol("PICOSOL"), sol("JSOL"), sol("STKESOL"),
        eth("STETH"), eth("WSTETH"), eth("RETH"), eth("CBETH"), eth("WEETH"), eth("EETH"), eth("EZETH"), eth("RSETH"),
        eth("METH"), eth("SWETH"), eth("SFRXETH"), eth("OSETH"), eth("ETHX"), eth("ANKRETH"), eth("LSETH"),
        bnb("SLISBNB"), bnb("ANKRBNB"), bnb("STKBNB"), bnb("BNBX"),
        pol("STMATIC"), pol("MATICX"),
        LstLink("SUSDE", "ethena-usde", "USDE"),
        LstLink("SUSDS", "usds", "USDS"),
        LstLink("SDAI", "dai", "DAI"),
    ).associateBy { it.symbol }

    fun link(symbol: String): LstLink? = bySymbol[symbol.uppercase()]
}

/**
 * Taxa já conhecida pelo app: quantas unidades do ativo-base valem 1 token.
 * [source] em branco, ou um texto com cara de endereço, não é taxa verificável e é ignorada.
 */
data class VerifiedRate(
    val symbol: String,
    val underlyingPerToken: BigDecimal,
    val source: String,
) {
    fun usable(): Boolean {
        val src = source.trim()
        if (src.isEmpty() || src.length > 48) return false
        if (CardName.looksLikeAddress(src)) return false
        return underlyingPerToken.signum() > 0
    }

    fun label(): String = source.trim()
}

enum class LineKind { FREE, STAKED, LIQUID_STAKING }

data class PositionLine(
    val walletId: Long,
    val walletName: String,
    val networkLabel: String,
    val symbol: String,
    /** Quantidade no token em si (mSOL continua em mSOL). */
    val ownAmount: BigDecimal,
    /** Unidades do ativo desta tela somadas no total. Nulo quando a linha fica de fora. */
    val counted: BigDecimal?,
    val kind: LineKind,
    val valueUsd: Double?,
    val rate: BigDecimal?,
    val rateSource: String?,
    val excludedNote: String?,
    val freshness: String?,
    val stale: Boolean,
    val partial: Boolean,
    val iconUrl: String?,
    val updatedAt: Long?,
)

data class PositionTotal(
    val key: String,
    val symbol: String,
    val name: String,
    val iconUrl: String?,
    val total: BigDecimal,
    val valueUsd: Double?,
    /** Alguma linha somada não tem cotação. [valueUsd] é só a soma do que tem preço. */
    val valueIncomplete: Boolean,
    val lines: List<PositionLine>,
    val aside: List<PositionLine>,
    val stale: Boolean,
    val partial: Boolean,
    val footnote: String?,
) {
    val walletCount: Int get() = (lines + aside).map { it.walletId }.distinct().size
}

/** Relógio da carteira, como a tela de carteiras já mostra: horário da última leitura e se ela falhou. */
data class WalletClock(val syncedAt: Long?, val failed: Boolean)

object PositionAggregator {
    const val NO_RATE = "Sem taxa de conversão verificada. Não entra no total."
    const val FRESH_STALE = "Desatualizado"
    const val FRESH_PARTIAL = "Leitura parcial"
    const val FRESH_KEPT = "Leitura anterior mantida"

    /** Uma linha guardada de uma leitura anterior (stake pulado, token incompleto) fica mais velha que a carteira. */
    const val KEPT_AFTER_MS = 60_000L

    fun canonicalKey(groupKey: String): String =
        if (groupKey.startsWith("staked:")) "cg:" + groupKey.removePrefix("staked:") else groupKey

    fun underlyingIdOfKey(canonical: String): String? =
        canonical.takeIf { it.startsWith("cg:") }?.removePrefix("cg:")

    fun holdingAsOfKey(walletId: Long, network: String, tokenKey: String) = "$walletId|$network|$tokenKey"

    fun rowKey(p: PricedHolding): String {
        val k = Aggregator.groupKey(p.holding)
        return if (p.source.manual && p.holding.coingeckoId == null) "manual:${p.holding.tokenKey}" else k
    }

    fun build(
        anchorKey: String,
        groups: List<AssetGroup>,
        hidden: List<PricedHolding>,
        clocks: Map<Long, WalletClock>,
        updatedAt: Map<String, Long>,
        now: Long,
        staleAfterMs: Long,
        rates: List<VerifiedRate> = emptyList(),
    ): PositionTotal? {
        val key = canonicalKey(anchorKey)
        val selves = groups.filter { canonicalKey(it.key) == key }
        val hiddenSelf = hidden.firstOrNull { it.visibility != Visibility.SPAM && it.holding.amount.signum() > 0 && canonicalKey(rowKey(it)) == key }
        if (selves.isEmpty() && hiddenSelf == null) return null
        val preferred = selves.firstOrNull { g -> g.parts.none { it.holding.kind == HoldingKind.STAKED } } ?: selves.firstOrNull()
        val symbol = preferred?.symbol ?: hiddenSelf!!.holding.symbol
        val rawName = (preferred?.name ?: hiddenSelf!!.holding.name).removeSuffix(" em staking").trim()
        val name = rawName.ifBlank { symbol }
        val icon = selves.firstNotNullOfOrNull { it.iconUrl }
            ?: preferred?.parts?.firstNotNullOfOrNull { it.holding.iconUrl }
            ?: hiddenSelf?.holding?.iconUrl
        val underlyingId = underlyingIdOfKey(key)

        val counted = ArrayList<PositionLine>()
        val aside = ArrayList<PositionLine>()
        val seen = HashSet<String>()
        val pool = ArrayList<PricedHolding>(groups.sumOf { it.parts.size } + hidden.size)
        for (g in groups) pool += g.parts
        pool += hidden

        for (p in pool) {
            if (p.visibility == Visibility.SPAM) continue
            val h = p.holding
            if (h.amount.signum() <= 0) continue
            val id = "${p.source.walletId}|${h.network.name}|${h.tokenKey}|${h.kind}"
            if (!seen.add(id)) continue
            val link = LstCatalog.link(h.symbol)
            val self = canonicalKey(rowKey(p)) == key
            val attached = !self && link != null && link.underlyingId == underlyingId
            if (!self && !attached) continue
            val rate = rates.firstOrNull { it.symbol.equals(h.symbol, ignoreCase = true) && it.usable() }
            val asOf = updatedAt[holdingAsOfKey(p.source.walletId, h.network.name, h.tokenKey)]
            val clock = clocks[p.source.walletId]
            val (stale, partial, fresh) = freshness(asOf, clock, now, staleAfterMs)
            val value = p.valueUsd?.takeIf { !it.isNaN() && !it.isInfinite() }
            if (attached && rate == null) {
                aside += PositionLine(
                    p.source.walletId, p.source.walletName, h.network.label, h.symbol, h.amount, null,
                    LineKind.LIQUID_STAKING, value, null, null, NO_RATE, fresh, stale, partial, h.iconUrl, asOf,
                )
            } else {
                val converted = attached && rate != null
                val qty = if (converted) h.amount.multiply(rate!!.underlyingPerToken, Decimals.MC) else h.amount
                val kind = when {
                    converted || h.kind == HoldingKind.LIQUID_STAKING -> LineKind.LIQUID_STAKING
                    h.kind == HoldingKind.STAKED -> LineKind.STAKED
                    else -> LineKind.FREE
                }
                counted += PositionLine(
                    p.source.walletId, p.source.walletName, h.network.label, h.symbol, h.amount, qty,
                    kind, value, if (converted) rate!!.underlyingPerToken else null, if (converted) rate!!.label() else null,
                    null, fresh, stale, partial, h.iconUrl, asOf,
                )
            }
        }

        counted.sortWith(compareBy<PositionLine> { it.walletName.lowercase() }.thenBy { it.kind.ordinal }.thenByDescending { it.counted })
        aside.sortByDescending { it.ownAmount }

        var total = BigDecimal.ZERO
        var valueSum = 0.0
        var anyValue = false
        var missingValue = false
        for (l in counted) {
            total = total.add(l.counted ?: BigDecimal.ZERO, Decimals.MC)
            if (l.valueUsd == null) missingValue = true else { valueSum += l.valueUsd; anyValue = true }
        }
        val link = LstCatalog.link(symbol)
        val onUnderlying = link != null && link.underlyingId == underlyingId
        val footnote = if (link != null && !onUnderlying) {
            val rate = rates.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) && it.usable() }
            if (rate == null) "Sem taxa de conversão verificada, este saldo não entra na posição de ${link.underlyingSymbol}."
            else "Na posição de ${link.underlyingSymbol}, 1 $symbol entra como ${rate.underlyingPerToken.stripTrailingZeros().toPlainString()} ${link.underlyingSymbol} (${rate.label()})."
        } else null

        return PositionTotal(
            key, symbol, name, icon, total.stripTrailingZeros(),
            if (anyValue) valueSum else null, missingValue && counted.isNotEmpty(),
            counted, aside, counted.any { it.stale }, counted.any { it.partial }, footnote,
        )
    }

    private fun freshness(updatedAt: Long?, clock: WalletClock?, now: Long, staleAfterMs: Long): Triple<Boolean, Boolean, String?> {
        val ref = updatedAt ?: clock?.syncedAt
        val stale = ref == null || now - ref > staleAfterMs
        val kept = updatedAt != null && clock?.syncedAt != null && clock.syncedAt - updatedAt > KEPT_AFTER_MS
        val failed = clock?.failed == true
        val partial = failed || kept
        val label = when {
            kept -> FRESH_KEPT
            failed -> FRESH_PARTIAL
            stale -> FRESH_STALE
            else -> null
        }
        return Triple(stale, partial, label)
    }
}
