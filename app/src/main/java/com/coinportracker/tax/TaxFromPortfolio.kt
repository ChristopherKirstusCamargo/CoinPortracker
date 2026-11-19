package com.coinportracker.tax

import com.coinportracker.data.ActivityKind
import com.coinportracker.data.ClassifiedActivity
import com.coinportracker.data.HubState
import com.coinportracker.domain.Currency
import java.math.BigDecimal

/** Converte o portfólio e o histórico on-chain no que o motor fiscal precisa (valores em BRL). */
object TaxFromPortfolio {
    /**
     * Informe completo (v5): só carteiras ativas, carteira ↔ stake e carteira ↔ carteira como "Entre contas"
     * (nunca venda/compra) e as recompensas de staking nativo (que não aparecem como transação) como rendimento.
     */
    fun report(h: HubState, activity: List<com.coinportracker.database.ChainActivityEntity>, stakeSignatures: Set<String>, rules: TaxRules,
               now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): TaxReport {
        val ids = h.wallets.map { it.id }.toSet()
        val classified = com.coinportracker.data.ActivityClassifier.classify(activity.filter { it.walletId in ids }, stakeSignatures)
        return TaxEngine.build(
            events = events(h, classified) + stakingRewardEvents(h, now, zone),
            positions = positions(h),
            investedBrl = investedBrl(h),
            rules = rules,
            now = now,
            zone = zone,
            walletHint = walletHint(h),
        )
    }

    /**
     * Recompensas do stake nativo de SOL entram no saldo da conta a cada época, sem transação. Viram rendimento,
     * divididas por ano-calendário proporcionalmente ao tempo desde a ativação (valor pelo preço médio do período).
     */
    fun stakingRewardEvents(h: HubState, now: Long, zone: java.time.ZoneId): List<TaxLotEvent> {
        val out = ArrayList<TaxLotEvent>()
        for (s in h.stakes) {
            if (s.rewardsSol <= 0 || s.rewardsSol.isNaN()) continue
            val unitUsd = s.rewardsUsd?.takeIf { it > 0 }?.let { it / s.rewardsSol }
            val unitBrl = unitUsd?.let { usdToBrl(h, it) }
            val start = (s.activatedAt ?: now).coerceAtMost(now)
            val span = (now - start).coerceAtLeast(1)
            var y = java.time.Instant.ofEpochMilli(start).atZone(zone).year
            val lastYear = java.time.Instant.ofEpochMilli(now).atZone(zone).year
            var allocated = BigDecimal.ZERO
            val total = BigDecimal.valueOf(s.rewardsSol)
            while (y <= lastYear) {
                val yEnd = java.time.LocalDate.of(y, 12, 31).atStartOfDay(zone).toInstant().toEpochMilli() + 86_399_999L
                val segEnd = minOf(yEnd, now)
                val segStart = maxOf(start, java.time.LocalDate.of(y, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli())
                val qty = if (y == lastYear) total.subtract(allocated) else total.multiply(BigDecimal.valueOf((segEnd - segStart).coerceAtLeast(0).toDouble() / span), com.coinportracker.core.Decimals.MC)
                allocated = allocated.add(qty)
                if (qty.signum() > 0) out += TaxLotEvent(segEnd, "coingecko:solana", "SOL", qty, "REWARD", "stake:${s.account}", null, unitBrl, true)
                y++
            }
        }
        return out
    }

    fun events(h: HubState, classified: List<ClassifiedActivity>): List<TaxLotEvent> {
        val priceUsd = HashMap<String, Double>()
        for (a in h.agg.assets) {
            a.priceUsd?.let { p ->
                priceUsd[a.symbol.uppercase()] = p
                priceUsd[a.key] = p
                a.parts.forEach { part ->
                    part.priceUsd?.let { pp ->
                        priceUsd[part.holding.symbol.uppercase()] = pp
                        part.holding.priceKey?.let { k -> priceUsd[k] = pp }
                        priceUsd[part.holding.tokenKey] = pp
                    }
                }
            }
        }
        val names = h.wallets.associate { it.id to it.name }
        val out = ArrayList<TaxLotEvent>()
        for (c in classified) {
            for (row in c.rows) {
                val qty = row.amount?.toBigDecimalOrNull()?.abs() ?: continue
                if (qty.signum() <= 0) continue
                val kind = when (c.kind) {
                    ActivityKind.REWARD -> "REWARD"
                    ActivityKind.IN -> "IN"
                    ActivityKind.OUT -> "OUT"
                    ActivityKind.SWAP -> if (row.direction == "IN") "SWAP_IN" else if (row.direction == "OUT") "SWAP_OUT" else continue
                    ActivityKind.SELF, ActivityKind.OTHER -> continue
                }
                val sym = row.symbol.ifBlank { "???" }
                val key = row.priceKey ?: row.tokenKey ?: sym.uppercase()
                val unitUsd = priceUsd[key] ?: priceUsd[sym.uppercase()]
                val unitBrl = unitUsd?.let { usdToBrl(h, it) }
                out += TaxLotEvent(
                    timestamp = c.timestamp,
                    asset = key,
                    symbol = sym,
                    qty = qty,
                    kind = kind,
                    hash = row.hash,
                    walletName = names[row.walletId],
                    unitBrl = unitBrl,
                    estimatedPrice = true,
                )
            }
        }
        return out
    }

    fun positions(h: HubState): List<TaxPosition> = h.agg.assets.map { a ->
        TaxPosition(a.priceKey ?: a.key, a.symbol, a.amount, a.priceUsd?.let { usdToBrl(h, it) })
    }

    fun investedBrl(h: HubState): BigDecimal? {
        val inv = h.settings.invested ?: return null
        return when (h.settings.investedCurrency) {
            Currency.BRL -> inv
            Currency.USD -> h.fx?.let { inv.multiply(it) }
        }
    }

    fun usdToBrl(h: HubState, usd: Double): BigDecimal? {
        val fx = h.fx?.toDouble() ?: return if (h.settings.investedCurrency == Currency.BRL) null else BigDecimal.valueOf(usd)
        return BigDecimal.valueOf(usd * fx)
    }

    fun walletHint(h: HubState): String? {
        val n = h.wallets.size
        return when (n) {
            0 -> null
            1 -> h.wallets.first().name
            else -> "$n carteiras"
        }
    }
}
