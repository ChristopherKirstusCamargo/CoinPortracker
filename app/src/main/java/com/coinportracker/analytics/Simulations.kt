package com.coinportracker.analytics

import com.coinportracker.core.Decimals
import java.math.BigDecimal
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Simulações matemáticas — NÃO são previsões de mercado. */
object Simulations {
    private val mc = Decimals.MC

    data class AvgImpact(val newQty: BigDecimal, val newCost: BigDecimal, val newAvg: BigDecimal?, val avgChangePct: BigDecimal?)

    /** Impacto de uma nova compra no preço médio (taxa incorporada ao custo). */
    fun buyImpact(qty: BigDecimal, cost: BigDecimal, buyQty: BigDecimal, buyPrice: BigDecimal, fee: BigDecimal = BigDecimal.ZERO): AvgImpact {
        val nq = qty.add(buyQty, mc)
        val nc = cost.add(buyQty.multiply(buyPrice, mc), mc).add(fee, mc)
        val oldAvg = Decimals.div(cost, qty)
        val newAvg = Decimals.div(nc, nq)
        return AvgImpact(nq, nc, newAvg, if (oldAvg != null && newAvg != null) Decimals.pct(newAvg.subtract(oldAvg, mc), oldAvg) else null)
    }

    data class PartialSale(val proceeds: BigDecimal, val costRemoved: BigDecimal, val gain: BigDecimal, val remainingQty: BigDecimal, val remainingCost: BigDecimal)

    /** Venda parcial pelo custo médio. */
    fun partialSaleAvg(qty: BigDecimal, cost: BigDecimal, sellQty: BigDecimal, price: BigDecimal, fee: BigDecimal = BigDecimal.ZERO): PartialSale? {
        if (sellQty.signum() <= 0 || sellQty > qty || qty.signum() <= 0) return null
        val removed = if (sellQty.compareTo(qty) == 0) cost else cost.multiply(sellQty, mc).divide(qty, mc)
        val proceeds = sellQty.multiply(price, mc).subtract(fee, mc)
        return PartialSale(proceeds, removed, proceeds.subtract(removed, mc), qty.subtract(sellQty, mc), cost.subtract(removed, mc))
    }

    data class ProjectionPoint(val month: Int, val invested: BigDecimal, val value: BigDecimal)

    /** Projeção determinística com aportes mensais e retorno anual premissa (composto mensal). */
    fun dcaProjection(initial: BigDecimal, monthly: BigDecimal, annualReturnPct: BigDecimal, months: Int): List<ProjectionPoint> {
        val r = (1.0 + annualReturnPct.toDouble() / 100.0).pow(1.0 / 12.0) - 1.0
        val rm = BigDecimal(r.toString())
        var value = initial
        var invested = initial
        val out = ArrayList<ProjectionPoint>()
        out += ProjectionPoint(0, invested, value)
        for (m in 1..months) {
            value = value.multiply(BigDecimal.ONE.add(rm, mc), mc).add(monthly, mc)
            invested = invested.add(monthly, mc)
            out += ProjectionPoint(m, invested, value)
        }
        return out
    }

    /** Compras recorrentes a preços hipotéticos: retorna (quantidade acumulada, custo, preço médio). */
    fun recurringBuys(amountPerBuy: BigDecimal, prices: List<BigDecimal>): Triple<BigDecimal, BigDecimal, BigDecimal?> {
        var q = BigDecimal.ZERO; var c = BigDecimal.ZERO
        for (p in prices) if (p.signum() > 0) { q = q.add(amountPerBuy.divide(p, mc), mc); c = c.add(amountPerBuy, mc) }
        return Triple(q, c, Decimals.div(c, q))
    }

    data class ScenarioResult(val name: String, val value: BigDecimal, val change: BigDecimal, val changePct: BigDecimal?)

    /** Cenário: aplica variação % por ativo (default para ativos não especificados). */
    fun scenario(name: String, holdings: Map<String, BigDecimal>, shocksPct: Map<String, BigDecimal>, defaultShockPct: BigDecimal): ScenarioResult {
        val base = holdings.values.fold(BigDecimal.ZERO) { a, b -> a.add(b, mc) }
        val after = holdings.entries.fold(BigDecimal.ZERO) { acc, (k, v) ->
            val s = shocksPct[k] ?: defaultShockPct
            acc.add(v.multiply(BigDecimal.ONE.add(s.divide(Decimals.HUNDRED, mc), mc), mc), mc)
        }
        val ch = after.subtract(base, mc)
        return ScenarioResult(name, after, ch, Decimals.pct(ch, base))
    }

    enum class Distribution(val label: String) { NORMAL("Normal (log-retornos)"), STUDENT_T("t de Student (caudas pesadas, ν=4)"), BOOTSTRAP("Bootstrap histórico") }
    enum class CorrelationScenario(val label: String) { HISTORICAL("Correlação histórica"), FULL("Correlação total (ρ = 1)"), NONE("Sem correlação (ρ = 0)") }

    data class MonteCarloConfig(
        val initialValue: Double,
        val horizonDays: Int,
        val simulations: Int,
        val monthlyContribution: Double,
        val annualVolatility: Double,
        val annualDrift: Double,
        val distribution: Distribution,
        val seed: Long = 42L,
        val historicalReturns: List<Double> = emptyList(),
    )

    data class MonteCarloResult(
        val config: MonteCarloConfig,
        val p5: Double, val p25: Double, val p50: Double, val p75: Double, val p95: Double,
        val mean: Double,
        val probLoss: Double,
        val totalInvested: Double,
        val histogram: List<Pair<Double, Int>>,
        val percentilePaths: List<List<Double>>, // p5, p50, p95 ao longo do tempo (amostra semanal)
    )

    /** Volatilidade da carteira a partir das volatilidades e da matriz de correlação (σp = √(wᵀΣw)). */
    fun portfolioVolatility(weights: List<Double>, vols: List<Double>, corr: (Int, Int) -> Double): Double {
        var s = 0.0
        for (i in weights.indices) for (j in weights.indices) s += weights[i] * weights[j] * vols[i] * vols[j] * (if (i == j) 1.0 else corr(i, j))
        return sqrt(max(s, 0.0))
    }

    fun monteCarlo(cfg: MonteCarloConfig): MonteCarloResult {
        require(cfg.simulations in 100..20000) { "Quantidade de simulações deve estar entre 100 e 20000." }
        require(cfg.horizonDays in 1..3650) { "Horizonte deve estar entre 1 e 3650 dias." }
        if (cfg.distribution == Distribution.BOOTSTRAP) require(cfg.historicalReturns.size >= 30) { "Bootstrap exige ao menos 30 retornos históricos." }
        val rnd = Random(cfg.seed)
        val dt = 1.0 / 365.0
        val sigma = cfg.annualVolatility * sqrt(dt)
        val mu = (cfg.annualDrift - 0.5 * cfg.annualVolatility * cfg.annualVolatility) * dt
        val logHist = cfg.historicalReturns.filter { it > -1 }.map { ln(1 + it) }
        val finals = DoubleArray(cfg.simulations)
        val sampleEvery = max(1, cfg.horizonDays / 52)
        val samples = (0..cfg.horizonDays step sampleEvery).toList()
        val pathSamples = Array(samples.size) { DoubleArray(cfg.simulations) }
        val dailyContribution = cfg.monthlyContribution * 12.0 / 365.0
        for (s in 0 until cfg.simulations) {
            var v = cfg.initialValue
            var si = 0
            pathSamples[si++][s] = v
            for (d in 1..cfg.horizonDays) {
                val shock = when (cfg.distribution) {
                    Distribution.NORMAL -> mu + sigma * gaussian(rnd)
                    Distribution.STUDENT_T -> mu + sigma * studentT(rnd, 4.0) / sqrt(4.0 / 2.0)
                    Distribution.BOOTSTRAP -> logHist[rnd.nextInt(logHist.size)]
                }
                v = v * exp(shock) + dailyContribution
                if (si < samples.size && samples[si] == d) pathSamples[si++][s] = v
            }
            finals[s] = v
        }
        val sorted = finals.sorted()
        fun pct(p: Double) = Statistics.percentile(sorted, p)
        val invested = cfg.initialValue + dailyContribution * cfg.horizonDays
        val minV = sorted.first(); val maxV = pct(0.99)
        val bins = 24
        val width = max((maxV - minV) / bins, 1e-9)
        val hist = (0 until bins).map { b -> (minV + width * (b + 0.5)) to sorted.count { it >= minV + width * b && (it < minV + width * (b + 1) || (b == bins - 1 && it <= maxV)) } }
        val paths = listOf(0.05, 0.5, 0.95).map { p -> pathSamples.map { Statistics.percentile(it.toList(), p) } }
        return MonteCarloResult(cfg, pct(0.05), pct(0.25), pct(0.5), pct(0.75), pct(0.95), sorted.average(),
            sorted.count { it < invested }.toDouble() / sorted.size, invested, hist, paths)
    }

    private fun gaussian(r: Random): Double {
        var u: Double; var v: Double; var s: Double
        do { u = r.nextDouble() * 2 - 1; v = r.nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * sqrt(-2.0 * ln(s) / s)
    }

    /** t de Student via Z / √(χ²ν/ν). Variância = ν/(ν−2); normalizada pelo chamador. */
    private fun studentT(r: Random, nu: Double): Double {
        val z = gaussian(r)
        var chi = 0.0
        repeat(nu.toInt()) { val g = gaussian(r); chi += g * g }
        return z / sqrt(chi / nu)
    }
}
