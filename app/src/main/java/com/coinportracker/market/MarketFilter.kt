package com.coinportracker.market

/**
 * Ranking "Top 100 cripto" do Mercado. O /coins/markets do CoinGecko mistura ativos do mundo real
 * tokenizados (fundos de Tesouro, crédito privado/HELOC, ouro, ações) com criptomoedas — ex.: "Figure
 * Heloc" (FIGR_HELOC) em 9º. Eles saem do ranking; stablecoins continuam (são cripto).
 *
 * Fonte da exclusão: a categoria "tokenized-products" do próprio CoinGecko (atualizada 1×/dia e guardada
 * em cache), somada a uma lista embutida para funcionar offline e no primeiro uso.
 */
object MarketFilter {
    const val CATEGORY = "tokenized-products"
    const val TOP = 100
    /** Pedimos mais que 100 para completar o ranking depois de tirar os excluídos. */
    const val FETCH = 150

    /** Conferido em 13/11/2026 (top 150 do CoinGecko × categoria tokenized-products). */
    val BUNDLED: Set<String> = setOf(
        "figure-heloc", "tether-gold", "pax-gold", "ondo-us-dollar-yield", "hashnote-usyc",
        "spiko-amundi-overnight-swap-fund-eur", "blackrock-usd-institutional-digital-liquidity-fund",
        "superstate-short-duration-us-government-securities-fund-ustb", "janus-henderson-anemoy-aaa-clo-fund",
        "spiko-us-t-bills-money-market-fund", "franklin-onchain-u-s-government-money-fund", "openeden-tbill",
        "ondo-short-term-us-government-bond-fund", "kinesis-gold", "kinesis-silver",
    )

    private val NAME_HINTS = Regex("(?i)\\b(heloc|treasur(y|ies)|t-?bills?|money market|government securities|clo fund|swap fund|liquidity fund|bond fund|tokenized stock|xstock)\\b")

    fun isTokenizedRwa(c: MarketCoin, excluded: Set<String>): Boolean =
        c.id in excluded || c.id in BUNDLED || NAME_HINTS.containsMatchIn(c.name)

    /** Remove RWA tokenizados, deduplica, ordena por capitalização e renumera 1..100. */
    fun top(list: List<MarketCoin>, excluded: Set<String>, n: Int = TOP): List<MarketCoin> =
        list.asSequence()
            .filter { it.id.isNotBlank() && !isTokenizedRwa(it, excluded) }
            .distinctBy { it.id }
            .take(n)
            .mapIndexed { i, c -> c.copy(rank = i + 1) }
            .toList()
}

/**
 * Faixa mínima do eixo Y para minigráficos (sparklines). Sem isso uma stablecoin que oscila ±0,3%
 * ocupa a altura toda e parece "ruído selvagem". [minRelSpan] = altura mínima relativa ao preço médio (3%).
 * Fonte única da regra: a UI (sparkRange em Kit.kt) delega para cá, nunca aplica uma segunda escala.
 */
object SparkRange {
    data class Range(val lo: Double, val hi: Double) { val span: Double get() = hi - lo }

    fun of(values: List<Double>, minRelSpan: Double = 0.03): Range? {
        val v = values.filter { it.isFinite() }
        if (v.size < 2) return null
        val lo = v.min(); val hi = v.max()
        val mid = (lo + hi) / 2
        val minSpan = kotlin.math.abs(mid) * minRelSpan
        if (hi - lo >= minSpan && hi > lo) return Range(lo, hi)
        val half = maxOf(minSpan, 1e-12) / 2
        return Range(mid - half, mid + half)
    }

    /** y normalizado 0..1 (0 = base) dentro da faixa. */
    fun norm(v: Double, r: Range): Double = if (r.span <= 0) 0.5 else ((v - r.lo) / r.span).coerceIn(0.0, 1.0)
}
