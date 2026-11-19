package com.coinportracker.history

import kotlin.math.max
import kotlin.math.min

enum class Confidence(val label: String) { HIGH("Alta"), MEDIUM("Média"), LOW("Baixa") }

/** Preço médio estimado automaticamente a partir do histórico on-chain. */
data class CostEstimate(
    val costGroup: String,
    val avgUsd: Double,
    val qty: Double,
    val costUsd: Double,
    /** Lucro/prejuízo realizado em trocas (swaps) on-chain, USD. */
    val realizedUsd: Double,
    /** Rendimentos (staking, juros, recompensas) valorizados no recebimento, USD. */
    val incomeUsd: Double,
    /** Quantidade recebida como rendimento. */
    val incomeQty: Double,
    /** 0..1: quanto do saldo é explicado por entradas com preço conhecido. */
    val score: Double,
    val confidence: Confidence,
    val inflows: Int,
    /** Saldo anterior ao histórico disponível (custo aproximado pelo preço do início do histórico). */
    val openingQty: Double,
    val firstAt: Long?,
)

/**
 * Estima o preço médio (custo médio ponderado móvel) de cada ativo usando o preço de mercado no instante de cada
 * entrada on-chain (depósito, perna de compra de swap, recompensa). Saídas reduzem a quantidade ao custo médio;
 * a perna de venda de um swap realiza lucro/prejuízo ao preço da época. Taxas reduzem a quantidade sem reduzir o custo
 * (a taxa vira custo). Transferências entre as próprias carteiras não alteram o custo.
 * Erro típico: o do preço horário de referência (DefiLlama, ~0,1–1 %) — muito menor que um preço médio "chutado".
 */
object CostEstimator {

    fun estimate(
        positions: List<Position>,
        movements: List<Movement>,
        accruals: List<Accrual>,
        coverage: List<Coverage>,
        /** Preço USD no instante (idealmente o histórico horário exato). */
        priceAt: (priceKey: String?, t: Long) -> Double?,
        /** Grupo de custo: SOL em stake usa o mesmo custo de SOL. */
        costGroupOf: (groupKey: String) -> String,
        now: Long,
    ): Map<String, CostEstimate> {
        val posById = positions.associateBy { it.id }
        fun groupOf(id: PosId): Position? = posById[id] ?: positions.firstOrNull { it.id.network == id.network && it.id.tokenKey == id.tokenKey }
        val covBy = coverage.associateBy { it.walletId to it.network }
        val groups = LinkedHashMap<String, MutableList<Position>>()
        for (p in positions) groups.getOrPut(costGroupOf(p.groupKey)) { ArrayList() } += p
        val movesBy = movements.groupBy { m -> groupOf(m.pos)?.let { costGroupOf(it.groupKey) } }
        val out = HashMap<String, CostEstimate>()

        for ((g, ps) in groups) {
            val moves = movesBy[g].orEmpty().sortedBy { it.t }
            val accs = accruals.filter { a -> ps.any { it.id == a.pos } }
            val current = ps.sumOf { it.amount }
            val priceKey = ps.firstOrNull { it.priceKey != null && it.kind != PosKind.STAKED }?.priceKey ?: ps.firstNotNullOfOrNull { it.priceKey }
            if (current <= 0 && moves.isEmpty()) continue
            val netMoves = moves.sumOf { it.delta - it.fee }
            val accrued = accs.sumOf { it.amount }
            val opening = max(0.0, current - netMoves - accrued)
            // início do histórico conhecido destas posições
            val knownFrom = ps.mapNotNull { covBy[it.id.walletId to it.id.network]?.knownFrom }.minOrNull() ?: moves.firstOrNull()?.t
            var qty = 0.0; var cost = 0.0; var realized = 0.0; var income = 0.0; var incomeQty = 0.0
            var inQty = 0.0; var pricedInQty = 0.0; var inflows = 0
            var openingPriced = false
            // saldo anterior ao histórico: só entra no custo se soubermos de quando ele é (início do histórico);
            // sem histórico nenhum, NÃO é custeado pelo preço de hoje (isso zeraria o lucro e enganaria)
            if (opening > 1e-12 && knownFrom != null) {
                val p0 = priceAt(priceKey, knownFrom)
                if (p0 != null) { qty = opening; cost = opening * p0; openingPriced = true }
            }
            // recompensas acumuladas sem transação viram entradas ao preço médio do período
            data class Ev(val t: Long, val delta: Double, val fee: Double, val kind: FlowKind, val price: Double?)
            val evs = ArrayList<Ev>()
            for (m in moves) evs += Ev(m.t, m.delta, m.fee, m.kind, priceAt(groupOf(m.pos)?.priceKey ?: priceKey, m.t))
            for (a in accs) {
                if (a.amount <= 0) continue
                val mid = a.start + (now - a.start) / 2
                evs += Ev(mid, a.amount, 0.0, FlowKind.REWARD, priceAt(priceKey, mid))
            }
            evs.sortBy { it.t }
            for (e in evs) {
                when (e.kind) {
                    FlowKind.DEPOSIT, FlowKind.TRADE_IN, FlowKind.REWARD -> if (e.delta > 0) {
                        inflows++; inQty += e.delta
                        val p = e.price
                        if (p != null) { cost += e.delta * p; pricedInQty += e.delta } else if (qty > 0) cost += e.delta * (cost / qty)
                        if (e.kind == FlowKind.REWARD) { incomeQty += e.delta; if (p != null) income += e.delta * p }
                        qty += e.delta
                    }
                    FlowKind.WITHDRAW, FlowKind.TRADE_OUT -> if (e.delta < 0) {
                        val q = min(-e.delta, qty)
                        val avg = if (qty > 0) cost / qty else 0.0
                        if (e.kind == FlowKind.TRADE_OUT && e.price != null) realized += q * (e.price - avg)
                        cost -= q * avg; qty -= q
                    }
                    FlowKind.INTERNAL -> {}
                }
                if (e.fee > 0) qty = max(0.0, qty - e.fee) // a taxa consome unidades; o custo fica (vira custo)
                if (qty <= 1e-15) { qty = 0.0; cost = 0.0 }
            }
            if (qty <= 0 || cost <= 0) {
                if (realized != 0.0 || income != 0.0) out[g] = CostEstimate(g, 0.0, 0.0, 0.0, realized, income, incomeQty, 0.0, Confidence.LOW, inflows, opening, moves.firstOrNull()?.t)
                continue
            }
            // escala para o saldo atual (pequenas lacunas, ex.: taxas não registradas)
            val avg = cost / qty
            val total = inQty + opening
            val priceCov = if (inQty > 0) pricedInQty / inQty else 1.0
            val histCov = if (total > 0) inQty / total else 0.0
            val score = (histCov * priceCov + (1 - histCov) * (if (openingPriced) 0.35 else 0.0)).coerceIn(0.0, 1.0)
            val conf = when { score >= 0.85 -> Confidence.HIGH; score >= 0.6 -> Confidence.MEDIUM; else -> Confidence.LOW }
            out[g] = CostEstimate(g, avg, current, avg * current, realized, income, incomeQty, score, conf, inflows, opening, evs.firstOrNull()?.t ?: knownFrom)
        }
        return out
    }
}
