package com.coinportracker.analytics

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estatísticas de risco/retorno. São ESTIMATIVAS estatísticas sobre dados históricos
 * (Double é aceitável aqui: não é contabilidade). Cada métrica informa as observações
 * usadas e retorna null quando os dados são insuficientes.
 */
data class Metric(val value: Double?, val observations: Int, val minRequired: Int, val note: String) {
    val available get() = value != null
}

object Statistics {
    const val PERIODS_PER_YEAR = 365.0 // cripto negocia 24/7
    const val MIN_VOL_OBS = 30
    const val MIN_CORR_OBS = 30
    const val MIN_VAR_OBS = 60

    fun simpleReturns(values: List<Double>): List<Double> =
        values.zipWithNext().mapNotNull { (a, b) -> if (a > 0) b / a - 1.0 else null }

    fun logReturns(values: List<Double>): List<Double> =
        values.zipWithNext().mapNotNull { (a, b) -> if (a > 0 && b > 0) ln(b / a) else null }

    fun mean(xs: List<Double>) = if (xs.isEmpty()) Double.NaN else xs.sum() / xs.size

    fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return Double.NaN
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m).pow(2) } / (xs.size - 1))
    }

    fun annualizedVolatility(returns: List<Double>): Metric {
        if (returns.size < MIN_VOL_OBS) return Metric(null, returns.size, MIN_VOL_OBS, "Volatilidade exige ao menos $MIN_VOL_OBS retornos diários.")
        return Metric(stdev(returns) * sqrt(PERIODS_PER_YEAR), returns.size, MIN_VOL_OBS, "Desvio-padrão dos retornos diários × √365.")
    }

    /** Drawdown máximo (valor negativo, ex.: -0.35 = -35%). */
    fun maxDrawdown(values: List<Double>): Metric {
        if (values.size < 2) return Metric(null, values.size, 2, "Drawdown exige ao menos 2 pontos.")
        var peak = values.first()
        var mdd = 0.0
        for (v in values) {
            peak = max(peak, v)
            if (peak > 0) mdd = minOf(mdd, v / peak - 1.0)
        }
        return Metric(mdd, values.size, 2, "Maior queda do pico ao vale no período.")
    }

    fun drawdownSeries(values: List<Double>): List<Double> {
        var peak = Double.NEGATIVE_INFINITY
        return values.map { v -> peak = max(peak, v); if (peak > 0) v / peak - 1.0 else 0.0 }
    }

    /** Sharpe anualizado. [riskFreeAnnual] em fração (ex.: 0.10). */
    fun sharpe(returns: List<Double>, riskFreeAnnual: Double): Metric {
        if (returns.size < MIN_VOL_OBS) return Metric(null, returns.size, MIN_VOL_OBS, "Sharpe exige ao menos $MIN_VOL_OBS retornos.")
        val rfDaily = (1 + riskFreeAnnual).pow(1 / PERIODS_PER_YEAR) - 1
        val excess = returns.map { it - rfDaily }
        val sd = stdev(excess)
        if (sd == 0.0 || sd.isNaN()) return Metric(null, returns.size, MIN_VOL_OBS, "Volatilidade nula.")
        return Metric(mean(excess) / sd * sqrt(PERIODS_PER_YEAR), returns.size, MIN_VOL_OBS, "(retorno médio − taxa livre de risco) ÷ volatilidade, anualizado.")
    }

    fun sortino(returns: List<Double>, riskFreeAnnual: Double): Metric {
        if (returns.size < MIN_VOL_OBS) return Metric(null, returns.size, MIN_VOL_OBS, "Sortino exige ao menos $MIN_VOL_OBS retornos.")
        val rfDaily = (1 + riskFreeAnnual).pow(1 / PERIODS_PER_YEAR) - 1
        val excess = returns.map { it - rfDaily }
        val downside = sqrt(excess.sumOf { if (it < 0) it * it else 0.0 } / excess.size)
        if (downside == 0.0) return Metric(null, returns.size, MIN_VOL_OBS, "Sem retornos negativos no período: Sortino indefinido.")
        return Metric(mean(excess) / downside * sqrt(PERIODS_PER_YEAR), returns.size, MIN_VOL_OBS, "Excesso de retorno ÷ desvio negativo, anualizado.")
    }

    fun correlation(a: List<Double>, b: List<Double>): Metric {
        val n = minOf(a.size, b.size)
        if (n < MIN_CORR_OBS) return Metric(null, n, MIN_CORR_OBS, "Correlação exige ao menos $MIN_CORR_OBS retornos alinhados.")
        val x = a.takeLast(n); val y = b.takeLast(n)
        val mx = mean(x); val my = mean(y)
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) { val dx = x[i] - mx; val dy = y[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        if (sxx == 0.0 || syy == 0.0) return Metric(null, n, MIN_CORR_OBS, "Série constante.")
        return Metric(sxy / sqrt(sxx * syy), n, MIN_CORR_OBS, "Correlação de Pearson dos retornos diários.")
    }

    fun beta(asset: List<Double>, benchmark: List<Double>): Metric {
        val n = minOf(asset.size, benchmark.size)
        if (n < MIN_CORR_OBS) return Metric(null, n, MIN_CORR_OBS, "Beta exige ao menos $MIN_CORR_OBS retornos alinhados.")
        val x = asset.takeLast(n); val y = benchmark.takeLast(n)
        val mx = mean(x); val my = mean(y)
        var cov = 0.0; var varB = 0.0
        for (i in 0 until n) { cov += (x[i] - mx) * (y[i] - my); varB += (y[i] - my).pow(2) }
        if (varB == 0.0) return Metric(null, n, MIN_CORR_OBS, "Benchmark sem variação.")
        return Metric(cov / varB, n, MIN_CORR_OBS, "Covariância ÷ variância do benchmark.")
    }

    /** Percentil por interpolação linear (p em [0,1]). */
    fun percentile(xs: List<Double>, p: Double): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        val pos = p * (s.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** VaR histórico diário (perda como fração positiva) no nível de confiança dado. */
    fun historicalVaR(returns: List<Double>, confidence: Double = 0.95): Metric {
        if (returns.size < MIN_VAR_OBS) return Metric(null, returns.size, MIN_VAR_OBS, "VaR histórico exige ao menos $MIN_VAR_OBS retornos.")
        return Metric(-percentile(returns, 1 - confidence), returns.size, MIN_VAR_OBS, "Perda diária não excedida em ${(confidence * 100).toInt()}% dos dias observados.")
    }

    fun annualizedReturn(totalReturn: Double, days: Double): Double? =
        if (days < 365 || totalReturn <= -1) null else (1 + totalReturn).pow(365.0 / days) - 1
}
