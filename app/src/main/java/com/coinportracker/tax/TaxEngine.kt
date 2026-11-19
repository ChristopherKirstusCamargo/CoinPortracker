package com.coinportracker.tax

import com.coinportracker.core.Decimals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class LotOrigin { PURCHASE, REWARD }

data class TaxLotEvent(
    val timestamp: Long,
    val asset: String,
    val symbol: String,
    val qty: BigDecimal,
    val kind: String,
    val hash: String? = null,
    val walletName: String? = null,
    val unitBrl: BigDecimal? = null,
    val estimatedPrice: Boolean = true,
)

data class AcquisitionLot(
    val asset: String,
    val symbol: String,
    val acquiredOn: LocalDate,
    val qty: BigDecimal,
    val costBrl: BigDecimal,
    val origin: LotOrigin,
    val estimated: Boolean,
    val hash: String? = null,
    val walletName: String? = null,
)

data class YearHolding(
    val year: Int,
    val asset: String,
    val symbol: String,
    val group: String,
    val code: String,
    val codeLabel: String,
    val qty: BigDecimal,
    val costBrl: BigDecimal,
    val marketBrl: BigDecimal?,
    val estimated: Boolean,
    val belowThreshold: Boolean,
    val description: String,
)

data class MonthlyDisposal(
    val month: YearMonth,
    val asset: String,
    val symbol: String,
    val qty: BigDecimal,
    val proceedsBrl: BigDecimal,
    val costBrl: BigDecimal,
    val gainBrl: BigDecimal,
    val estimated: Boolean,
    val hash: String? = null,
)

data class MonthSummary(
    val month: YearMonth,
    val proceedsBrl: BigDecimal,
    val costBrl: BigDecimal,
    val gainBrl: BigDecimal,
    val exempt: Boolean,
    val taxableGain: BigDecimal,
    val suggestedTax: BigDecimal,
    val disposals: List<MonthlyDisposal>,
    val estimated: Boolean,
)

data class StakingIncomeRow(
    val date: LocalDate,
    val symbol: String,
    val qty: BigDecimal,
    val valueBrl: BigDecimal?,
    val estimated: Boolean,
    val hash: String? = null,
)

data class TaxReport(
    val rules: TaxRules,
    val investedTotal: BigDecimal?,
    val investedAllocated: Boolean,
    val generatedAt: Long,
    val zone: String,
    val lots: List<AcquisitionLot>,
    val holdingsByYear: Map<Int, List<YearHolding>>,
    val months: List<MonthSummary>,
    val stakingIncome: List<StakingIncomeRow>,
    val notes: List<String>,
    val years: List<Int>,
)

data class TaxPosition(val asset: String, val symbol: String, val qty: BigDecimal, val priceBrl: BigDecimal?)

/**
 * Informe a partir das movimentações on-chain e, se houver, do total pago em
 * «Preço do portfólio». Custo médio (padrão usual para criptoativos no IRPF).
 *
 * Com total pago informado, os custos dos lotes de compra (não os de rendimento)
 * são reescalados para somar exatamente esse total — é um rateio, sempre marcado
 * como estimado.
 */
object TaxEngine {
    private val MC = Decimals.MC
    private val ZERO = BigDecimal.ZERO

    fun build(
        events: List<TaxLotEvent>,
        positions: List<TaxPosition>,
        investedBrl: BigDecimal?,
        rules: TaxRules,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        walletHint: String? = null,
    ): TaxReport {
        val notes = ArrayList<String>()
        if (investedBrl != null && investedBrl.signum() > 0) {
            notes += "O custo de aquisição dos lotes de compra foi rateado pelo total informado em Preço do portfólio (${brl(investedBrl)}). Não é o comprovante de cada compra."
        } else {
            notes += "Sem total pago informado: o custo usa o preço de mercado na data da movimentação, quando existe. Lotes sem preço ficam sem custo e o informe fica incompleto."
        }
        notes += "Posições em 31/12 entram pelo custo de aquisição, nunca pelo valor de mercado."
        notes += "A isenção mensal de ${brl(rules.exemption())} considera o total alienado no mês, de todos os criptoativos juntos."
        notes += rules.disclaimer

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val incoming = events.filter { it.timestamp > 0 && it.qty.signum() > 0 }
        val openings = residualOpenings(incoming, positions, today, zone)
        if (openings.isNotEmpty()) {
            notes += "O histórico on-chain não explica toda a posição atual. A diferença entrou como lote estimado em 01/01/${today.year}, para o informe do ano corrente acompanhar o portfólio. Anos anteriores ficam só com o histórico conhecido."
        }
        val ordered = (openings + incoming).sortedWith(compareBy({ it.timestamp }, { it.kind }))
        val costs = assignPurchaseCosts(ordered, positions, investedBrl)
        val allocated = investedBrl != null && investedBrl.signum() > 0 && costs.values.any { it.origin == LotOrigin.PURCHASE }

        val full = replay(ordered, costs, Long.MAX_VALUE, zone)
        if (full.lots.any { it.estimated } || full.disposals.any { it.estimated }) {
            notes += "Itens marcados como estimados usam preço de mercado na data ou o rateio do total pago. Guarde seus comprovantes."
        }

        val years = LinkedHashSet<Int>()
        incoming.forEach { years += Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().year }
        years += today.year
        if (years.isEmpty()) years += today.year

        val priceNow = positions.associate { it.asset to it.priceBrl }
        val holdings = LinkedHashMap<Int, List<YearHolding>>()
        for (y in years.sorted()) {
            val end = LocalDate.of(y, 12, 31).atStartOfDay(zone).toInstant().toEpochMilli() + (24 * 3600_000L - 1)
            val snap = replay(ordered, costs, end, zone)
            val rows = snap.books.mapNotNull { (asset, b) ->
                if (b.qty.signum() <= 0) return@mapNotNull null
                val code = rules.codeFor(b.symbol)
                val market = if (y >= today.year) priceNow[asset]?.let { px -> b.qty.multiply(px, MC) } else null
                holding(y, b, code, rules, market, walletHint)
            }.sortedBy { it.symbol }
            holdings[y] = rows
        }

        val months = full.disposals.groupBy { it.month }.toSortedMap().map { (ym, list) ->
            val proceeds = list.fold(ZERO) { a, d -> a.add(d.proceedsBrl, MC) }
            val cost = list.fold(ZERO) { a, d -> a.add(d.costBrl, MC) }
            val gain = proceeds.subtract(cost, MC)
            val exempt = proceeds <= rules.exemption()
            val taxable = if (exempt) ZERO else gain.max(ZERO)
            MonthSummary(ym, proceeds, cost, gain, exempt, taxable, rules.taxOnGain(taxable), list, list.any { it.estimated })
        }

        return TaxReport(
            rules = rules,
            investedTotal = investedBrl,
            investedAllocated = allocated,
            generatedAt = now,
            zone = zone.id,
            lots = full.lots.sortedWith(compareBy({ it.acquiredOn }, { it.symbol })),
            holdingsByYear = holdings,
            months = months,
            stakingIncome = full.income.sortedBy { it.date },
            notes = notes,
            years = years.sorted(),
        )
    }

    private data class Assigned(val costBrl: BigDecimal, val estimated: Boolean, val origin: LotOrigin)
    private data class Book(val asset: String, val symbol: String, var qty: BigDecimal = ZERO, var cost: BigDecimal = ZERO, var estimated: Boolean = false)
    private data class Replay(
        val books: Map<String, Book>,
        val lots: List<AcquisitionLot>,
        val disposals: List<MonthlyDisposal>,
        val income: List<StakingIncomeRow>,
    )

    /** Posição atual menos o que o histórico já explica, como compra estimada em 01/01 do ano corrente. */
    private fun residualOpenings(events: List<TaxLotEvent>, positions: List<TaxPosition>, today: LocalDate, zone: ZoneId): List<TaxLotEvent> {
        if (positions.isEmpty()) return emptyList()
        val net = HashMap<String, BigDecimal>()
        val keyBySym = HashMap<String, String>()
        val symLabel = HashMap<String, String>()
        for (e in events) {
            val s = e.symbol.uppercase()
            val signed = when (e.kind) {
                "IN", "SWAP_IN", "REWARD" -> e.qty
                "OUT", "SWAP_OUT" -> e.qty.negate()
                else -> ZERO
            }
            net[s] = (net[s] ?: ZERO).add(signed, MC)
            keyBySym.putIfAbsent(s, e.asset)
            symLabel.putIfAbsent(s, e.symbol)
        }
        val ts = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<TaxLotEvent>()
        for ((sym, ps) in positions.groupBy { it.symbol.uppercase() }) {
            val qty = ps.fold(ZERO) { a, p -> a.add(p.qty, MC) }
            if (qty.signum() <= 0) continue
            val gap = qty.subtract(net[sym] ?: ZERO, MC)
            if (gap.signum() <= 0) continue
            val head = ps.maxByOrNull { it.qty } ?: continue
            out += TaxLotEvent(
                timestamp = ts,
                asset = keyBySym[sym] ?: head.asset,
                symbol = symLabel[sym] ?: head.symbol,
                qty = gap,
                kind = "IN",
                unitBrl = head.priceBrl,
                estimatedPrice = true,
            )
        }
        return out
    }

    private fun assignPurchaseCosts(events: List<TaxLotEvent>, positions: List<TaxPosition>, invested: BigDecimal?): Map<Int, Assigned> {
        val out = HashMap<Int, Assigned>()
        val purchaseIdx = ArrayList<Int>()
        events.forEachIndexed { i, e ->
            when (e.kind) {
                "IN", "SWAP_IN" -> {
                    purchaseIdx += i
                    val c = e.unitBrl?.let { e.qty.multiply(it, MC) } ?: ZERO
                    out[i] = Assigned(c, e.estimatedPrice || e.unitBrl == null, LotOrigin.PURCHASE)
                }
                "REWARD" -> {
                    val c = e.unitBrl?.let { e.qty.multiply(it, MC) } ?: ZERO
                    out[i] = Assigned(c, e.estimatedPrice || e.unitBrl == null, LotOrigin.REWARD)
                }
            }
        }
        if (invested == null || invested.signum() <= 0 || purchaseIdx.isEmpty()) return out
        val weights = purchaseIdx.map { i ->
            val e = events[i]
            val assigned = out[i]!!.costBrl
            if (assigned.signum() > 0) assigned
            else {
                val p = positions.firstOrNull { it.asset == e.asset }?.priceBrl
                if (p != null && p.signum() > 0) e.qty.multiply(p, MC) else e.qty
            }
        }
        val sum = weights.fold(ZERO) { a, w -> a.add(w, MC) }
        if (sum.signum() <= 0) {
            val each = invested.divide(BigDecimal(purchaseIdx.size), MC)
            purchaseIdx.forEachIndexed { k, i ->
                val c = if (k == purchaseIdx.lastIndex) invested.subtract(each.multiply(BigDecimal(k), MC), MC) else each
                out[i] = Assigned(c, true, LotOrigin.PURCHASE)
            }
            return out
        }
        var acc = ZERO
        purchaseIdx.forEachIndexed { k, i ->
            val c = if (k == purchaseIdx.lastIndex) invested.subtract(acc) else invested.multiply(weights[k], MC).divide(sum, MC)
            acc = acc.add(c, MC)
            out[i] = Assigned(c, true, LotOrigin.PURCHASE)
        }
        return out
    }

    private fun replay(events: List<TaxLotEvent>, costs: Map<Int, Assigned>, untilInclusive: Long, zone: ZoneId): Replay {
        val books = LinkedHashMap<String, Book>()
        val lots = ArrayList<AcquisitionLot>()
        val disposals = ArrayList<MonthlyDisposal>()
        val income = ArrayList<StakingIncomeRow>()
        fun day(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        fun book(e: TaxLotEvent) = books.getOrPut(e.asset) { Book(e.asset, e.symbol) }
        events.forEachIndexed { i, e ->
            if (e.timestamp > untilInclusive) return@forEachIndexed
            when (e.kind) {
                "IN", "SWAP_IN", "REWARD" -> {
                    val a = costs[i] ?: Assigned(ZERO, true, if (e.kind == "REWARD") LotOrigin.REWARD else LotOrigin.PURCHASE)
                    val b = book(e)
                    b.qty = b.qty.add(e.qty, MC)
                    b.cost = b.cost.add(a.costBrl, MC)
                    if (a.estimated) b.estimated = true
                    val lot = AcquisitionLot(e.asset, e.symbol, day(e.timestamp), e.qty, a.costBrl, a.origin, a.estimated, e.hash, e.walletName)
                    lots += lot
                    if (a.origin == LotOrigin.REWARD) {
                        income += StakingIncomeRow(day(e.timestamp), e.symbol, e.qty, a.costBrl.takeIf { it.signum() > 0 }, a.estimated, e.hash)
                    }
                }
                "OUT", "SWAP_OUT" -> {
                    val b = book(e)
                    val proceeds = e.unitBrl?.let { e.qty.multiply(it, MC) } ?: ZERO
                    val take = e.qty.min(b.qty.max(ZERO))
                    val cost = if (b.qty.signum() > 0) b.cost.multiply(take, MC).divide(b.qty, MC) else ZERO
                    b.qty = b.qty.subtract(take, MC)
                    b.cost = b.cost.subtract(cost, MC).max(ZERO)
                    if (e.qty > take) b.estimated = true
                    disposals += MonthlyDisposal(
                        month = YearMonth.from(day(e.timestamp)),
                        asset = e.asset, symbol = e.symbol, qty = e.qty,
                        proceedsBrl = proceeds, costBrl = cost, gainBrl = proceeds.subtract(cost, MC),
                        estimated = b.estimated || e.estimatedPrice || e.unitBrl == null, hash = e.hash,
                    )
                }
            }
        }
        return Replay(books, lots, disposals, income)
    }

    private fun holding(year: Int, b: Book, code: AssetCodeRule, rules: TaxRules, market: BigDecimal?, walletHint: String?): YearHolding {
        val below = b.cost < rules.threshold()
        val desc = buildString {
            append("Quantidade: ${b.qty.stripTrailingZeros().toPlainString()} ${b.symbol}. ")
            append("Custódia própria")
            walletHint?.let { append(" ($it)") }
            append(". Custo de aquisição: ${brl(b.cost)}.")
            if (b.estimated) append(" Custo estimado (rateio do total pago e/ou preço de mercado na data).")
        }
        return YearHolding(year, b.asset, b.symbol, rules.group, code.code, code.label, b.qty, b.cost, market, b.estimated, below, desc)
    }

    internal fun brl(v: BigDecimal): String {
        val s = v.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
        val parts = s.split('.')
        val intp = parts[0].replace("-", "")
        val neg = v.signum() < 0
        val grouped = intp.reversed().chunked(3).joinToString(".").reversed()
        return (if (neg) "-R$ " else "R$ ") + grouped + "," + (parts.getOrNull(1) ?: "00")
    }
}
