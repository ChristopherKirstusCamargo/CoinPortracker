package com.coinportracker.data

import androidx.compose.runtime.Immutable
import com.coinportracker.core.Decimals
import com.coinportracker.database.CostBasisEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.Currency
import com.coinportracker.onchain.AddressKind
import com.coinportracker.onchain.AggregatedPortfolio
import com.coinportracker.onchain.Aggregator
import com.coinportracker.onchain.HoldingSource
import com.coinportracker.onchain.ManualPosition
import com.coinportracker.onchain.Network
import com.coinportracker.data.ChainPortfolioRepository.Companion.toHolding
import com.coinportracker.data.ChainPortfolioRepository.Companion.toLive
import com.coinportracker.portfolio.HoldingsPnlCalc
import com.coinportracker.portfolio.HoldingsResult
import com.coinportracker.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal

@Immutable
data class WalletSummary(
    val id: Long, val name: String, val address: String?, val kind: AddressKind?, val valueUsd: Double,
    val pnlUsd: BigDecimal?, val networks: List<Network>, val lastSyncAt: Long?, val error: String?, val watch: Boolean,
)

/** Estado único e imutável do portfólio combinado (on-chain + manual), já calculado fora da thread principal. */
@Immutable
data class HubState(
    val ready: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val fx: BigDecimal? = null,
    val agg: AggregatedPortfolio = AggregatedPortfolio(0.0, 0.0, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0),
    val pnl: HoldingsResult = HoldingsPnlCalc.compute(emptyList(), emptyMap(), emptyMap(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
    val wallets: List<WalletSummary> = emptyList(),
    val userCost: Map<String, CostBasisEntity> = emptyMap(),
    /** groupKey → assetId do livro de operações (para abrir/registrar operações do ativo). */
    val ledgerAsset: Map<String, String> = emptyMap(),
    val stakingIncomeUsd: BigDecimal = BigDecimal.ZERO,
    /** assetId do livro de operações → símbolo. */
    val symbols: Map<String, String> = emptyMap(),
    /** Staking nativo de SOL lido da blockchain (com recompensas reais). */
    val stakes: List<StakeView> = emptyList(),
    /** Preço médio estimado automaticamente (grupo de custo → estimativa). */
    val estimates: Map<String, com.coinportracker.history.CostEstimate> = emptyMap(),
) {
    fun symbol(id: String) = symbols[id] ?: id.removePrefix("custom:").uppercase()
    val currency: Currency get() = settings.baseCurrency
    val hide: Boolean get() = settings.hideValues
    /** Converte um valor em USD para a moeda de exibição. */
    fun conv(usd: BigDecimal?): BigDecimal? = when {
        usd == null -> null
        currency == Currency.USD -> usd
        else -> fx?.let { usd.multiply(it, Decimals.MC) }
    }
    fun conv(usd: Double?): BigDecimal? = usd?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { conv(BigDecimal.valueOf(it)) }
    /** Converte da moeda de exibição para USD (entrada do usuário). */
    fun toUsd(v: BigDecimal, from: Currency): BigDecimal? = if (from == Currency.USD) v else fx?.takeIf { it.signum() > 0 }?.let { Decimals.div(v, it) }
}

/**
 * Junta carteiras on-chain, posições manuais do livro de operações, preços ao vivo, câmbio e preço médio
 * em um [HubState]. Tudo roda em Dispatchers.Default com debounce para não recalcular a cada linha gravada.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class PortfolioHub(private val c: AppContainer, scope: CoroutineScope) {

    private val base = combine(c.chain.holdings, c.chain.livePrices, c.db.wallets().observeAll(), c.chain.fxFlow, c.db.chain().observeCostBasis()) { h, p, w, fx, cb ->
        Inputs(h, p, w, fx?.json?.toBigDecimalOrNull(), cb)
    }

    private val extra = combine(c.history.estimates, c.history.stakeViews) { e, st -> e to st }

    val state: StateFlow<HubState> = combine(base, c.portfolio.state, c.settings.settings, extra) { i, ps, s, x -> Quad(i, ps, s, x) }
        .debounce(60).conflate()
        .map { q -> compute(q.a, q.b, q.c, q.d.first, q.d.second) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, HubState())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private data class Inputs(
        val holdings: List<com.coinportracker.database.ChainHoldingEntity>,
        val prices: List<com.coinportracker.database.LivePriceEntity>,
        val wallets: List<WalletEntity>,
        val fx: BigDecimal?,
        val costBasis: List<CostBasisEntity>,
    )

    private fun compute(i: Inputs, ps: PortfolioState, s: AppSettings, est: Map<String, com.coinportracker.history.CostEstimate> = emptyMap(), stakes: List<StakeView> = emptyList()): HubState {
        val fx = i.fx ?: ps.latestFx?.usdBrl?.toBigDecimalOrNull()
        // a mesma carteira cadastrada duas vezes conta uma vez só (lista e total), mesmo antes da limpeza no banco
        val dupIds = com.coinportracker.data.WalletDedupe.duplicates(i.wallets)
        val active = i.wallets.filter { !it.archived && it.id !in dupIds }
        val byId = active.associateBy { it.id }
        val watchIds = active.filter { c.chain.isWatch(it) }.map { it.id }.toSet()
        val prices = i.prices.associate { it.key to it.toLive() }

        // on-chain
        val onchain = i.holdings.mapNotNull { e ->
            val w = byId[e.walletId] ?: return@mapNotNull null
            val h = e.toHolding() ?: return@mapNotNull null
            if ("${h.network.name}:${h.tokenKey}" in s.hiddenTokens) return@mapNotNull null
            HoldingSource(w.id, w.name) to h
        }

        // v3.1: tudo vem da blockchain; operações manuais e preço médio digitado por ativo não entram mais no cálculo
        val manual = emptyList<ManualPosition>()
        val ledgerAsset = HashMap<String, String>()
        val agg = Aggregator.aggregate(onchain, manual, prices, minUsd = if (s.hideSmallBalances) 1.0 else 0.0)
        // v5: com o total pago informado, o custo por ativo é o rateio desse total (mesma regra do Contábil)
        val investedUsd = s.invested?.takeIf { it.signum() > 0 }?.let { inv ->
            if (s.investedCurrency == Currency.USD) inv else fx?.takeIf { it.signum() > 0 }?.let { Decimals.div(inv, it) }
        }
        val yieldByGroup = HashMap<String, BigDecimal>()
        for (st in stakes) st.rewardsUsd?.takeIf { it > 0 && !it.isNaN() && !it.isInfinite() }?.let { yieldByGroup.merge("staked:solana", BigDecimal.valueOf(it)) { a, b -> a.add(b, Decimals.MC) } }
        val pnl = HoldingsPnlCalc.compute(agg.assets, emptyMap(), emptyMap(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, if (s.useEstimatedCost) est else emptyMap(),
            investedUsd = investedUsd, yieldUsd = yieldByGroup)
        val userCost = emptyMap<String, CostBasisEntity>()

        val sliceValue = agg.byWallet.associate { it.key to it.valueUsd }
        val walletNets = HashMap<Long, MutableSet<Network>>()
        for ((src, h) in onchain) walletNets.getOrPut(src.walletId) { LinkedHashSet() } += h.network
        val wallets = active.filter { it.id in watchIds }.map { w ->
            val kind = AddressKind.fromStored(w.chain)
            WalletSummary(w.id, w.name, w.address, kind, sliceValue[w.id.toString()] ?: 0.0, pnl.byWallet[w.id.toString()]?.pnl,
                walletNets[w.id]?.toList().orEmpty(), w.lastSyncAt, w.lastSyncError, w.id in watchIds)
        }.sortedWith(compareByDescending<WalletSummary> { it.watch }.thenByDescending { it.valueUsd })

        return HubState(true, s, fx, agg, pnl, wallets, userCost, ledgerAsset, BigDecimal.ZERO, ps.assets.mapValues { it.value.symbol }, stakes, est)
    }
}
