package com.coinportracker.analytics

import com.coinportracker.core.Decimals
import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.TxType
import com.coinportracker.transactions.CapitalFlow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.pow

data class DayPoint(
    val day: LocalDate,
    val equity: BigDecimal?,          // null quando falta cotação de algum ativo com saldo
    val netContributions: BigDecimal,
    val flow: BigDecimal,             // fluxo externo líquido no dia (+aporte, −retirada)
    val missingAssets: Set<String>,
)

/** Séries de preço diário por ativo (moeda do relatório). Chave: epochDay. */
typealias PriceBook = Map<String, TreeMap<Long, BigDecimal>>

object PortfolioHistory {
    /** Cotações podem ser reaproveitadas por até este número de dias (fins de semana de fontes de câmbio etc.). */
    const val MAX_CARRY_DAYS = 3L

    fun dayOf(ts: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    /** Variações de quantidade por (ativo, carteira) que uma operação provoca. */
    fun quantityDeltas(tx: LedgerTx): List<Triple<String, Long?, BigDecimal>> {
        val src = tx.fromWalletId ?: tx.toWalletId
        val dst = tx.toWalletId ?: tx.fromWalletId
        val out = ArrayList<Triple<String, Long?, BigDecimal>>()
        when (tx.type) {
            TxType.BUY, TxType.DEPOSIT, TxType.REWARD, TxType.STAKING, TxType.AIRDROP, TxType.INCOME_DISTRIBUTION ->
                out += Triple(tx.assetId, dst, tx.quantity)
            TxType.SELL, TxType.WITHDRAWAL, TxType.EXTERNAL_TRANSFER, TxType.NETWORK_FEE, TxType.TRADING_FEE ->
                out += Triple(tx.assetId, src, tx.quantity.negate())
            TxType.SWAP -> {
                out += Triple(tx.assetId, src, tx.quantity.negate())
                if (tx.toAssetId != null && tx.toQuantity != null) out += Triple(tx.toAssetId, dst, tx.toQuantity)
            }
            TxType.INTERNAL_TRANSFER -> {
                out += Triple(tx.assetId, tx.fromWalletId, tx.quantity.negate())
                out += Triple(tx.assetId, tx.toWalletId, tx.quantity)
            }
            TxType.MANUAL_ADJUSTMENT -> out += Triple(tx.assetId, if (tx.quantity.signum() >= 0) dst else src, tx.quantity)
        }
        if (tx.feeAssetId != null && tx.feeQuantity != null && tx.feeQuantity.signum() > 0) out += Triple(tx.feeAssetId, src, tx.feeQuantity.negate())
        return out
    }

    fun priceAt(book: PriceBook, assetId: String, day: Long): BigDecimal? {
        val e = book[assetId]?.floorEntry(day) ?: return null
        return if (day - e.key <= MAX_CARRY_DAYS) e.value else null
    }

    fun build(
        txs: List<LedgerTx>,
        flows: List<CapitalFlow>,
        prices: PriceBook,
        to: LocalDate,
        assetFilter: Set<String>? = null,
        walletFilter: Set<Long>? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayPoint> {
        if (txs.isEmpty()) return emptyList()
        val sorted = txs.sortedWith(compareBy<LedgerTx> { it.timestamp }.thenBy { it.id })
        val start = dayOf(sorted.first().timestamp, zone)
        val deltasByDay = TreeMap<Long, MutableList<Triple<String, Long?, BigDecimal>>>()
        for (t in sorted) deltasByDay.getOrPut(dayOf(t.timestamp, zone).toEpochDay()) { ArrayList() } += quantityDeltas(t)
        val flowByDay = TreeMap<Long, BigDecimal>()
        for (f in flows) {
            if (assetFilter != null && f.assetId !in assetFilter) continue
            val d = dayOf(f.timestamp, zone).toEpochDay()
            flowByDay[d] = (flowByDay[d] ?: BigDecimal.ZERO).add(f.signed, Decimals.MC)
        }
        val holdings = HashMap<String, BigDecimal>()
        var net = BigDecimal.ZERO
        val out = ArrayList<DayPoint>()
        var d = start.toEpochDay()
        val end = to.toEpochDay()
        while (d <= end) {
            deltasByDay[d]?.forEach { (asset, wallet, q) ->
                if (assetFilter != null && asset !in assetFilter) return@forEach
                if (walletFilter != null && (wallet == null || wallet !in walletFilter)) return@forEach
                holdings[asset] = (holdings[asset] ?: BigDecimal.ZERO).add(q, Decimals.MC)
            }
            val flow = if (walletFilter == null) flowByDay[d] ?: BigDecimal.ZERO else BigDecimal.ZERO
            net = net.add(flow, Decimals.MC)
            var eq = BigDecimal.ZERO
            val missing = HashSet<String>()
            for ((asset, q) in holdings) {
                if (q.signum() <= 0) continue
                val p = priceAt(prices, asset, d)
                if (p == null) missing += asset else eq = eq.add(q.multiply(p, Decimals.MC), Decimals.MC)
            }
            out += DayPoint(LocalDate.ofEpochDay(d), if (missing.isEmpty()) eq else null, net, flow, missing)
            d++
        }
        return out
    }

    /**
     * Retorno ponderado pelo tempo (TWR), encadeando retornos diários com fluxos no início do dia:
     * r_t = V_t / (V_{t−1} + F_t) − 1. Dias sem cotação completa quebram a cadeia e são descartados.
     */
    fun twr(points: List<DayPoint>): Metric {
        var acc = 1.0
        var n = 0
        for (i in 1 until points.size) {
            val prev = points[i - 1].equity ?: continue
            val cur = points[i].equity ?: continue
            val base = prev.add(points[i].flow).toDouble()
            if (base <= 0.0) continue
            acc *= cur.toDouble() / base
            n++
        }
        return if (n < 7) Metric(null, n, 7, "TWR exige ao menos 7 dias com patrimônio completo.")
        else Metric(acc - 1.0, n, 7, "Produto de (1 + retorno diário) com fluxos neutralizados; $n dias usados.")
    }

    /** Retornos diários do patrimônio com fluxos neutralizados (base para volatilidade/Sharpe/VaR). */
    fun dailyReturns(points: List<DayPoint>): List<Double> {
        val out = ArrayList<Double>()
        for (i in 1 until points.size) {
            val prev = points[i - 1].equity ?: continue
            val cur = points[i].equity ?: continue
            val base = prev.add(points[i].flow).toDouble()
            if (base > 0.0) out += cur.toDouble() / base - 1.0
        }
        return out
    }

    /**
     * Retorno ponderado pelo dinheiro (MWR/XIRR) anualizado. Fluxos do investidor:
     * aporte = saída de caixa (negativo), retirada = entrada (positivo), patrimônio final = entrada.
     */
    fun xirr(flows: List<CapitalFlow>, finalValue: BigDecimal, finalDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Metric {
        if (flows.isEmpty()) return Metric(null, 0, 2, "Sem fluxos de capital.")
        val cfs = flows.map { dayOf(it.timestamp, zone) to -it.signed.toDouble() } + (finalDate to finalValue.toDouble())
        val t0 = cfs.minOf { it.first }
        val spanDays = ChronoUnit.DAYS.between(t0, finalDate)
        if (spanDays < 30) return Metric(null, cfs.size, 2, "MWR exige ao menos 30 dias entre o primeiro aporte e hoje.")
        if (cfs.none { it.second < 0 } || cfs.none { it.second > 0 }) return Metric(null, cfs.size, 2, "Fluxos sem troca de sinal.")
        fun npv(r: Double) = cfs.sumOf { (d, v) -> v / (1 + r).pow(ChronoUnit.DAYS.between(t0, d) / 365.0) }
        var lo = -0.9999; var hi = 100.0
        var flo = npv(lo); val fhi = npv(hi)
        if (flo * fhi > 0) return Metric(null, cfs.size, 2, "Não foi possível encontrar uma taxa (IRR) única.")
        repeat(200) {
            val mid = (lo + hi) / 2
            val fm = npv(mid)
            if (abs(fm) < 1e-9) return Metric(mid, cfs.size, 2, "Taxa anual que zera o valor presente dos fluxos (XIRR).")
            if (flo * fm < 0) hi = mid else { lo = mid; flo = fm }
        }
        return Metric((lo + hi) / 2, cfs.size, 2, "Taxa anual que zera o valor presente dos fluxos (XIRR), por bisseção.")
    }
}
