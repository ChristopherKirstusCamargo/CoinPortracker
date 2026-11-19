package com.coinportracker.history

import kotlin.math.max

/**
 * Reconstrói o valor REAL do portfólio no tempo (como Uniswap/CoinStats), a partir do saldo atual de cada posição
 * e dos movimentos on-chain/manuais. Puro Kotlin (testável na JVM, sem Android).
 */
object HistoryEngine {

    /** Quantidade de cada posição no instante [t]: saldo atual menos tudo o que entrou depois de t (e mais o que saiu). */
    fun amountAt(p: Position, moves: List<Movement>, accrual: Accrual?, t: Long, now: Long): Double {
        var a = p.amount
        for (m in moves) if (m.t > t) a -= (m.delta - m.fee)
        if (accrual != null && accrual.amount > 0) {
            val span = (now - accrual.start).coerceAtLeast(1)
            val after = when {
                t <= accrual.start -> accrual.amount
                t >= now -> 0.0
                else -> accrual.amount * (now - t).toDouble() / span
            }
            a -= after
        }
        // Precisão numérica / lacunas de dados (ex.: taxa não registrada): nunca negativo
        return if (a < p.amount * 1e-9 + 1e-12) max(0.0, a) else a
    }

    /** Linha do tempo com [n] pontos entre [from] e [now] (inclusive). */
    fun timeline(from: Long, now: Long, n: Int): List<Long> {
        if (now <= from || n < 2) return listOf(now)
        return (0 until n).map { from + (now - from) * it / (n - 1) }
    }

    /**
     * Série do valor do portfólio.
     * - Carteiras com histórico completo: antes do primeiro movimento a posição vale zero.
     * - Carteiras com histórico parcial: a série só é confiável a partir de [Coverage.knownFrom]; o gráfico começa ali.
     * - Posições sem série de preço usam o preço atual (constante), só enquanto existiam.
     */
    fun reconstruct(
        positions: List<Position>,
        movements: List<Movement>,
        coverage: List<Coverage>,
        accruals: List<Accrual>,
        prices: PriceBook,
        rangeStart: Long,
        now: Long,
        points: Int,
        trimLeadingZeros: Boolean = true,
    ): HistoryResult {
        val byPos = movements.groupBy { it.pos }
        val accrualBy = accruals.associateBy { it.pos }
        // posições que já não existem mas tiveram movimentos (vendidas/enviadas) também contam no passado
        val known = positions.associateBy { it.id }.toMutableMap()
        for ((pid, _) in byPos) if (pid !in known) {
            val ref = positions.firstOrNull { it.id.tokenKey == pid.tokenKey && it.id.network == pid.network }
            known[pid] = Position(pid, ref?.groupKey ?: "key:${pid.network}:${pid.tokenKey}", ref?.priceKey, ref?.symbol ?: "?", 0.0, ref?.kind ?: PosKind.TOKEN, ref?.currentPrice)
        }
        val partialFrom = coverage.filter { !it.complete && it.knownFrom != null }.maxOfOrNull { it.knownFrom!! }
        val anyPartial = coverage.any { !it.complete }
        val start = max(rangeStart, partialFrom ?: Long.MIN_VALUE)
        var ts = timeline(start, now, points)
        fun valueAt(t: Long): Double {
            var v = 0.0
            for ((pid, p) in known) {
                val a = amountAt(p, byPos[pid].orEmpty(), accrualBy[pid], t, now)
                if (a <= 0.0) continue
                val px = prices.at(p.priceKey, t) ?: p.currentPrice ?: continue
                v += a * px
            }
            return v
        }
        var pts = ts.map { ValuePoint(it, valueAt(it)) }
        if (trimLeadingZeros) {
            // começa o gráfico quando o patrimônio começa (ignora poeira/airdrops antigos que criariam "linha reta e salto")
            val maxV = pts.maxOfOrNull { it.usd } ?: 0.0
            val firstReal = pts.indexOfFirst { it.usd > maxOf(1e-6, maxV * 0.01) }
            if (firstReal > 0) {
                val s2 = pts[firstReal - 1].t
                ts = timeline(s2, now, points)
                pts = ts.map { ValuePoint(it, valueAt(it)) }
            }
        }
        val first = pts.firstOrNull()?.t ?: now
        // fluxos externos (aportes/retiradas) valorizados ao preço da época — só para exibir e para a linha de principal
        var dep = 0.0; var wd = 0.0; var inc = 0.0
        val flowAt = ArrayList<Pair<Long, Double>>()
        for (m in movements) {
            if (m.t <= first || m.t > now) continue
            val p = known[m.pos] ?: continue
            val px = prices.at(p.priceKey, m.t) ?: p.currentPrice ?: continue
            when (m.kind) {
                FlowKind.DEPOSIT -> { dep += m.delta * px; flowAt += m.t to m.delta * px }
                FlowKind.WITHDRAW -> { wd += -m.delta * px; flowAt += m.t to m.delta * px }
                FlowKind.REWARD -> inc += m.delta * px
                else -> {}
            }
        }
        for (a in accruals) {
            val p = known[a.pos] ?: continue
            val span = (now - a.start).coerceAtLeast(1)
            val from = max(first, a.start)
            if (from >= now) continue
            val part = a.amount * (now - from).toDouble() / span
            val px = prices.average(p.priceKey, from, now) ?: p.currentPrice ?: continue
            inc += part * px
        }
        flowAt.sortBy { it.first }
        // Desempenho de MERCADO (efeito preço): em cada intervalo, quantidade no início × variação do preço.
        // Aportes, retiradas, carteiras recém-sincronizadas e taxas mudam a quantidade, não o preço: nunca viram lucro/prejuízo.
        val gains = DoubleArray(pts.size)
        var twr = 1.0
        for (i in 1 until pts.size) {
            val t0 = pts[i - 1].t; val t1 = pts[i].t
            var g = 0.0
            for ((pid, p) in known) {
                val a = amountAt(p, byPos[pid].orEmpty(), accrualBy[pid], t0, now)
                if (a <= 0.0) continue
                val p0 = prices.at(p.priceKey, t0) ?: continue
                val p1 = prices.at(p.priceKey, t1) ?: continue
                g += a * (p1 - p0)
            }
            gains[i] = gains[i - 1] + g
            val base = pts[i - 1].usd
            if (base > 1e-9) twr *= (1 + (g / base).coerceIn(-0.99, 10.0))
        }
        // principal no gráfico: valor inicial + aportes líquidos acumulados (degraus)
        var fi = 0; var acc = pts.firstOrNull()?.usd ?: 0.0
        val principal = DoubleArray(pts.size) { i ->
            while (fi < flowAt.size && flowAt[fi].first <= pts[i].t) { acc += flowAt[fi].second; fi++ }
            acc
        }
        return HistoryResult(pts, if (anyPartial) partialFrom else first, dep - wd, dep, wd, inc, anyPartial,
            marketUsd = gains.lastOrNull() ?: 0.0, twrPct = if (pts.size >= 2) (twr - 1) * 100 else null,
            gainSeries = gains.toList(), principalSeries = principal.toList())
    }

    /**
     * Classifica movimentos brutos de uma transação: se no mesmo hash (mesma carteira) há entrada e saída de ativos
     * diferentes, é uma troca (TRADE_*); movimentos que aparecem com sinais opostos em duas carteiras do usuário no
     * mesmo hash (ou com contraparte = outra carteira do usuário) são INTERNAL.
     */
    fun classify(raw: List<RawMove>, ownAddresses: Set<String>): List<Movement> {
        val byTx = raw.groupBy { it.walletId to it.baseHash }
        val byHashAll = raw.groupBy { it.baseHash }
        return raw.map { r ->
            val sameTx = byTx[r.walletId to r.baseHash].orEmpty()
            val hasIn = sameTx.any { it.delta > 0 && it.asset != r.asset }
            val hasOut = sameTx.any { it.delta < 0 && it.asset != r.asset }
            // mesmo ativo saindo de uma posição do usuário e entrando em outra (outra carteira ou conta de stake) = interno
            val mirrored = byHashAll[r.baseHash].orEmpty().any { it !== r && (it.walletId != r.walletId || it.tokenKey != r.tokenKey) && it.asset == r.asset && it.delta * r.delta < 0 }
            val toOwn = r.counterparty?.lowercase()?.let { it in ownAddresses } == true
            val kind = when {
                r.reward -> FlowKind.REWARD
                r.delta == 0.0 -> FlowKind.INTERNAL
                mirrored || toOwn -> FlowKind.INTERNAL
                r.delta > 0 && hasOut -> FlowKind.TRADE_IN
                r.delta < 0 && hasIn -> FlowKind.TRADE_OUT
                r.delta > 0 -> FlowKind.DEPOSIT
                else -> FlowKind.WITHDRAW
            }
            Movement(PosId(r.walletId, r.network, r.tokenKey), r.t, r.delta, r.fee, r.baseHash, kind)
        }
    }
}

/** Movimento bruto antes da classificação. */
data class RawMove(
    val walletId: Long, val network: String, val tokenKey: String, val t: Long, val delta: Double, val fee: Double,
    val baseHash: String, val counterparty: String?, val reward: Boolean = false,
    /** Identidade do ativo para casar pernas (chave de preço; SOL em stake e SOL nativo são o mesmo ativo). */
    val asset: String = tokenKey,
)
