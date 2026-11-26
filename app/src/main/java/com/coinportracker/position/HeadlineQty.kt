package com.coinportracker.position

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Quantidade de manchete: no máximo 1 casa, pt-BR, sempre para baixo.
 * 10,19 vira 10,1 e nunca 10,2. Um saldo positivo menor que 0,1 não vira zero: aparece como "< 0,1".
 */
object HeadlineQty {
    private val symbols = DecimalFormatSymbols(Locale("pt", "BR"))

    fun number(amount: BigDecimal): String {
        if (amount.signum() > 0 && amount.setScale(1, RoundingMode.DOWN).signum() == 0) return "< 0,1"
        val df = DecimalFormat("#,##0.#", symbols)
        df.roundingMode = RoundingMode.DOWN
        return df.format(amount.setScale(1, RoundingMode.DOWN))
    }

    fun text(amount: BigDecimal, symbol: String): String = number(amount) + " " + symbol
}
