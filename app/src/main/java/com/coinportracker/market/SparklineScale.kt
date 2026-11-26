package com.coinportracker.market

import com.coinportracker.domain.Currency
import java.math.BigDecimal

/**
 * A CoinGecko devolve `sparkline_in_7d.price` em USD mesmo quando `vs_currency` é BRL
 * (o preço, a capitalização e o volume já vêm na moeda pedida). Sem esta conversão,
 * máxima/mínima de 7 dias aparecem como se fossem reais — XRP a R$ 5,46 com máxima "R$ 0,99".
 */
object SparklineScale {
    fun extrema(spark: List<Double>, currency: Currency, brlPerUsd: BigDecimal?): Pair<BigDecimal, BigDecimal>? {
        if (spark.size < 2) return null
        if (spark.any { it.isNaN() || it.isInfinite() || it < 0 }) return null
        val factor = when (currency) {
            Currency.USD -> BigDecimal.ONE
            Currency.BRL -> brlPerUsd?.takeIf { it.signum() > 0 } ?: return null
        }
        val max = BigDecimal.valueOf(spark.max()).multiply(factor)
        val min = BigDecimal.valueOf(spark.min()).multiply(factor)
        return max to min
    }
}
