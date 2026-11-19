package com.coinportracker.history

/**
 * Modelos do motor de histórico real do portfólio (v3).
 *
 * Ideia central: a blockchain informa o saldo ATUAL de cada posição e a lista de movimentos (entradas/saídas).
 * Andando para trás no tempo a partir do saldo atual, desfazendo cada movimento, obtemos quanto de cada ativo
 * havia em cada instante. Valor do portfólio em t = Σ quantidade(t) × preço histórico(t).
 * Assim um ativo só conta a partir do momento em que entrou na carteira (nada de "saldo atual × preço antigo").
 */

/** Uma posição = um ativo em uma carteira e rede (ex.: SOL nativo da carteira 3 na Solana). */
data class PosId(val walletId: Long, val network: String, val tokenKey: String)

enum class PosKind { NATIVE, TOKEN, STAKED, LIQUID_STAKING, LENDING, LP, MANUAL }

data class Position(
    val id: PosId,
    /** Chave de agrupamento do ativo (mesma do Aggregator: "cg:solana", "staked:solana"…). */
    val groupKey: String,
    /** Chave de preço DefiLlama ("coingecko:solana", "base:0x…"). */
    val priceKey: String?,
    val symbol: String,
    val amount: Double,
    val kind: PosKind,
    /** Preço atual (USD) usado quando não há série histórica. */
    val currentPrice: Double?,
)

enum class FlowKind {
    /** Entrada vinda de fora (depósito, recebimento). */
    DEPOSIT,
    /** Saída para fora (envio, saque). */
    WITHDRAW,
    /** Perna de entrada de uma troca (swap) dentro da carteira. */
    TRADE_IN,
    /** Perna de saída de uma troca. */
    TRADE_OUT,
    /** Recompensa / rendimento (staking, airdrop, juros). */
    REWARD,
    /** Movimento entre carteiras do próprio usuário (não é aporte nem retirada). */
    INTERNAL,
}

/** Movimento de quantidade de uma posição. [delta] tem sinal (+ entrada, − saída) e NÃO inclui a taxa; [fee] ≥ 0 é a taxa paga em unidades do ativo. */
data class Movement(
    val pos: PosId,
    val t: Long,
    val delta: Double,
    val fee: Double,
    val hash: String,
    val kind: FlowKind,
)

/** Até onde o histórico de uma carteira/rede é conhecido. complete = chegou à primeira transação da carteira. */
data class Coverage(val walletId: Long, val network: String, val knownFrom: Long?, val complete: Boolean)

/**
 * Rendimento que se acumula sem transação (stake nativo de SOL, tokens rebase como stETH/aTokens).
 * [amount] unidades acumuladas linearmente entre [start] e agora.
 */
data class Accrual(val pos: PosId, val start: Long, val amount: Double)

data class ValuePoint(val t: Long, val usd: Double)

/** Resultado da reconstrução do valor do portfólio no tempo. */
data class HistoryResult(
    val points: List<ValuePoint>,
    /** Instante a partir do qual a série é confiável (antes disso falta histórico de alguma carteira). */
    val reliableFrom: Long?,
    /** Aportes (+) e retiradas (−) externos no período, em USD. */
    val netFlowsUsd: Double,
    val depositsUsd: Double,
    val withdrawalsUsd: Double,
    /** Rendimentos (staking, juros) acumulados no período, em USD. */
    val incomeUsd: Double,
    val partial: Boolean,
    /** Ganho de MERCADO no período (Σ quantidade × variação de preço por intervalo), em USD. Aportes nunca entram. */
    val marketUsd: Double = 0.0,
    /** Retorno ponderado no tempo (encadeado por intervalo), em %. */
    val twrPct: Double? = null,
    /** Ganho de mercado acumulado em cada ponto (mesmo tamanho de [points]). */
    val gainSeries: List<Double> = emptyList(),
    /** Principal em cada ponto: valor inicial + aportes − retiradas acumulados (degraus). */
    val principalSeries: List<Double> = emptyList(),
) {
    val first: Double? get() = points.firstOrNull()?.usd
    val last: Double? get() = points.lastOrNull()?.usd
    /**
     * Resultado do período = ganho de mercado (efeito preço). Depósitos e carteiras novas não contam (v3.2).
     * v5: o rendimento de staking fica fora, como no Resultado real (regra do usuário); ele aparece à parte em [incomeUsd].
     * Assim o % (TWR, que já era só preço) e o valor contam a mesma história e batem com a variação do Resultado real.
     */
    val pnlUsd: Double? get() = if (points.size >= 2) marketUsd else null
    /** Retorno do período ponderado no tempo (não é distorcido por aportes no meio do período). */
    val pnlPct: Double? get() = if (points.size >= 2) twrPct else null
}

/** Livro de preços históricos por chave (séries ordenadas por tempo). */
class PriceBook(private val series: Map<String, List<ValuePoint>>, private val current: Map<String, Double> = emptyMap(), private val now: Long = System.currentTimeMillis()) {
    /** Lacuna máxima interpolada por série: 3× o passo mediano. Acima disso o preço fica em degrau (nada de reta inventada). */
    private val maxGap = HashMap<String, Long>()
    private fun gapOf(key: String, s: List<ValuePoint>): Long = maxGap.getOrPut(key) {
        if (s.size < 3) Long.MAX_VALUE else {
            val d = LongArray(s.size - 1) { s[it + 1].t - s[it].t }; d.sort(); d[d.size / 2] * 3
        }
    }
    fun has(key: String?) = key != null && !series[key].isNullOrEmpty()
    fun keys() = series.keys

    /** Preço em t: último ponto ≤ t; antes da série, o primeiro ponto se estiver a até [edgeMs]; depois dela, o preço atual. */
    fun at(key: String?, t: Long, edgeMs: Long = 4L * 86_400_000L): Double? {
        if (key == null) return null
        val s = series[key]
        if (s.isNullOrEmpty()) return current[key]
        if (t >= now - 60_000L && current[key] != null) return current[key]
        var lo = 0; var hi = s.size - 1
        if (t < s[0].t) return if (s[0].t - t <= edgeMs) s[0].usd else null
        if (t >= s[hi].t) {
            // entre o último ponto e "agora": interpola até o preço atual para não criar degrau
            val c = current[key] ?: return s[hi].usd
            if (now - s[hi].t > gapOf(key, s)) return s[hi].usd
            val span = (now - s[hi].t).toDouble()
            return if (span <= 0) c else s[hi].usd + (c - s[hi].usd) * ((t - s[hi].t) / span).coerceIn(0.0, 1.0)
        }
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (s[mid].t <= t) lo = mid else hi = mid - 1 }
        val a = s[lo]; val b = s.getOrNull(lo + 1) ?: return a.usd
        if (b.t - a.t > gapOf(key, s)) return a.usd
        val f = ((t - a.t).toDouble() / (b.t - a.t).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return a.usd + (b.usd - a.usd) * f
    }

    /** Preço médio ponderado no tempo entre [from] e [to] (para valorizar recompensas acumuladas). */
    fun average(key: String?, from: Long, to: Long, samples: Int = 48): Double? {
        if (key == null || to <= from) return at(key, to)
        var sum = 0.0; var n = 0
        for (i in 0..samples) { at(key, from + (to - from) * i / samples)?.let { sum += it; n++ } }
        return if (n == 0) null else sum / n
    }
}
