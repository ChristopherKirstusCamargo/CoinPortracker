package com.coinportracker.portfolio

/**
 * Resultado real a partir do total que saiu do bolso do usuário (principal), na moeda em que ele informou.
 * Dois conceitos separados (v3.2):
 *  - principal = dinheiro investido (fonte da verdade quando informado);
 *  - lucro = valor acima do principal. Quando o valor está abaixo, é "perda sobre o aporte".
 *  - rendimento de staking NÃO entra no resultado: é informado à parte em [income].
 *    O SOL (ou outro ativo) em stake, como principal, entra normalmente no valor e no P&L.
 * Valores em [cur] (BRL ou USD); fx converte USD → moeda informada.
 */
data class RealPnl(
    val invested: Double,
    val value: Double,
    val result: Double,
    val resultPct: Double,
    /** Quanto o portfólio precisa subir (%) para voltar ao valor pago; 0 quando já está no lucro. */
    val breakEvenPct: Double,
    /** Pior e melhor momento (valor − principal da época) dentro da série, com o instante. */
    val worst: Pair<Long, Double>?,
    val best: Pair<Long, Double>?,
    /** Maior queda de um topo até um fundo, medida em valor/principal (aportes não contam como alta). */
    val maxDrawdown: Double?,
    /** Rendimentos de staking, excluídos do [result] e mostrados à parte. */
    val income: Double,
    /** Resultado de mercado (preço), igual a [result] quando o rendimento foi excluído. */
    val market: Double,
    /** Maior lucro já atingido (≥ 0). */
    val peakProfit: Double = 0.0,
    /** Quanto desse lucro máximo foi devolvido ao mercado (sem contar perda do principal). */
    val givenBack: Double = 0.0,
    /** Quanto o valor está abaixo do principal (≥ 0). */
    val lossOnPrincipal: Double = 0.0,
) {
    val inProfit get() = result >= 0
}

object RealPnlCalc {
    /**
     * [seriesUsd] valor do portfólio no tempo; [principalUsd] (opcional, mesmo tamanho) principal reconstruído
     * (valor inicial + aportes) — é reescalado para terminar exatamente no total informado.
     */
    /**
     * [stakingYieldCur] valor dos rendimentos de staking na moeda informada (já convertidos).
     * São excluídos do [RealPnl.result]: Resultado = valor − investido − rendimentos.
     * O principal em stake (a quantidade original) permanece no valor e no P&L.
     * [incomeUsd] é aceito por compatibilidade e só é usado se [stakingYieldCur] for 0.
     */
    fun compute(investedCur: Double, valueUsd: Double, fx: Double, seriesUsd: List<Pair<Long, Double>>, since: Long, incomeUsd: Double,
                principalUsd: List<Double>? = null, stakingYieldCur: Double = 0.0, gainUsd: List<Double>? = null): RealPnl? {
        if (investedCur <= 0 || fx <= 0 || valueUsd.isNaN() || valueUsd.isInfinite() || investedCur.isNaN() || investedCur.isInfinite()) return null
        val value = valueUsd * fx
        val yieldCur = stakingYieldCur.takeIf { !it.isNaN() && !it.isInfinite() }?.coerceAtLeast(0.0) ?: 0.0
        val income = if (yieldCur > 0) yieldCur else (incomeUsd * fx).takeIf { !it.isNaN() }?.coerceAtLeast(0.0) ?: 0.0
        // Só o rendimento de staking (stakingYieldCur) sai do resultado. incomeUsd antigo permanece só informativo.
        val result = value - investedCur - yieldCur
        val base = baseOf(investedCur, value, yieldCur, result, income)
        // v5: com a série de ganho de MERCADO (efeito preço), o lucro em cada instante é o resultado de hoje menos o que
        // o preço fez desde então. Transferências que chegam (entre carteiras suas ou de fora) e o período antes de as
        // moedas chegarem não mexem no lucro: só o preço mexe. É a mesma linha que o gráfico desenha.
        val g = gainUsd?.takeIf { it.size == seriesUsd.size && it.isNotEmpty() && it.none { v -> v.isNaN() } }
        if (g != null) {
            val gEnd = g.last()
            val idx = seriesUsd.indices.filter { seriesUsd[it].first >= since }
            val profits = idx.map { seriesUsd[it].first to result - (gEnd - g[it]) * fx }
            return withPath(base, profits, investedCur, result)
        }
        // Sem série de ganho (fotos salvas do patrimônio): principal reto no total pago, como na v4.1.
        val useP = principalUsd != null && principalUsd.size == seriesUsd.size && (principalUsd.lastOrNull() ?: 0.0) > 1e-9
        val k = if (useP) investedCur / principalUsd!!.last() else 0.0
        val profits = seriesUsd.indices.filter { seriesUsd[it].first >= since && !seriesUsd[it].second.isNaN() }.map { i ->
            val p = if (useP) principalUsd!![i] * k else investedCur
            seriesUsd[i].first to seriesUsd[i].second * fx - yieldCur - p
        }
        return withPath(base, profits, investedCur, result)
    }

    private fun baseOf(investedCur: Double, value: Double, yieldCur: Double, result: Double, income: Double) = RealPnl(
        invested = investedCur, value = value, result = result, resultPct = result / investedCur * 100,
        breakEvenPct = if (result >= 0 || (value - yieldCur) <= 0) 0.0 else (investedCur / (value - yieldCur) - 1) * 100,
        worst = null, best = null, maxDrawdown = null,
        income = income, market = result - (if (yieldCur > 0) 0.0 else income),
        lossOnPrincipal = maxOf(0.0, -result),
    )

    /**
     * Lucro máximo, devolvido e maior queda a partir do caminho do lucro (moeda informada).
     * Maior queda = maior recuo de (principal + lucro) de um topo até um fundo, em % do topo: relativo ao principal,
     * nunca à quantidade que existia antes de uma transferência chegar.
     */
    private fun withPath(base: RealPnl, profits: List<Pair<Long, Double>>, investedCur: Double, result: Double): RealPnl {
        var peakE = Double.NEGATIVE_INFINITY; var dd = 0.0
        for ((_, pr) in profits) {
            val e = investedCur + pr
            if (e > peakE) peakE = e
            if (peakE > 0) dd = maxOf(dd, ((peakE - e) / peakE).coerceIn(0.0, 1.0))
        }
        val peakProfit = maxOf(0.0, result, profits.maxOfOrNull { it.second } ?: 0.0)
        return base.copy(
            worst = profits.minByOrNull { it.second }, best = profits.maxByOrNull { it.second },
            maxDrawdown = if (profits.size >= 2) dd else null,
            peakProfit = peakProfit, givenBack = (peakProfit - maxOf(result, 0.0)).coerceAtLeast(0.0),
        )
    }

    /**
     * Linha do gráfico do P&L com total pago informado (mesmas unidades da série, USD): valor de hoje menos o efeito preço
     * desde cada ponto. Termina exatamente no valor atual; transferências não criam degraus. null sem série de ganho.
     */
    fun line(valueUsd: Double, gainUsd: List<Double>?, n: Int): List<Double>? {
        val g = gainUsd?.takeIf { it.size == n && n > 0 && it.none { v -> v.isNaN() } } ?: return null
        val gEnd = g.last()
        return List(n) { (valueUsd - (gEnd - g[it])).coerceAtLeast(0.0) }
    }

    /** Linha do principal quando o usuário informou só o total pago: reta, sem degraus de transferência. */
    fun flatPrincipal(n: Int, investedCur: Double): List<Double> = List(n.coerceAtLeast(0)) { investedCur }

    /** Principal reescalado (na moeda informada) para desenhar a linha tracejada; termina exatamente no total pago. */
    fun scaledPrincipal(investedCur: Double, principalUsd: List<Double>, fx: Double): List<Double> {
        val last = principalUsd.lastOrNull()?.takeIf { it > 1e-9 } ?: return principalUsd.map { investedCur }
        return principalUsd.map { it / last * investedCur }
    }
}
