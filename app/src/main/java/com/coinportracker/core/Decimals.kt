package com.coinportracker.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Regras explícitas de precisão decimal.
 *
 * - Todos os valores financeiros usam [BigDecimal]. Nunca Double/Float na contabilidade.
 * - Cálculos internos usam [MC] (34 dígitos significativos, HALF_EVEN — padrão IEEE 754 decimal128).
 * - Arredondamento monetário somente na apresentação/exportação: 2 casas, HALF_EVEN (bancário).
 * - Quantidades de criptoativos: até 18 casas (precisão de wei/lamport cabem).
 */
object Decimals {
    val MC: MathContext = MathContext(34, RoundingMode.HALF_EVEN)
    const val MONEY_SCALE = 2
    const val QTY_SCALE = 18
    const val PRICE_SCALE = 8
    val ZERO: BigDecimal = BigDecimal.ZERO
    val ONE: BigDecimal = BigDecimal.ONE
    val HUNDRED: BigDecimal = BigDecimal(100)

    fun money(v: BigDecimal): BigDecimal = v.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
    fun qty(v: BigDecimal): BigDecimal = v.setScale(QTY_SCALE, RoundingMode.HALF_EVEN).stripTrailingZerosSafe()

    /** Divisão segura com contexto de 34 dígitos; retorna null para divisor zero. */
    fun div(a: BigDecimal, b: BigDecimal): BigDecimal? =
        if (b.signum() == 0) null else a.divide(b, MC)

    fun pct(part: BigDecimal, whole: BigDecimal): BigDecimal? =
        div(part, whole)?.multiply(HUNDRED, MC)

    fun parse(text: String?): BigDecimal? {
        if (text == null) return null
        var t = text.trim().replace("\u00A0", "").replace(" ", "")
        if (t.isEmpty()) return null
        t = t.removePrefix("R$").removePrefix("US$").removePrefix("$")
        // Aceita formatos "1.234,56" (pt-BR) e "1,234.56" / "1234.56".
        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        t = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            lastComma >= 0 -> {
                // Apenas vírgula: decimal pt-BR, a não ser que haja múltiplas vírgulas (milhar).
                if (t.count { it == ',' } > 1) t.replace(",", "") else t.replace(',', '.')
            }
            lastDot >= 0 && t.count { it == '.' } > 1 -> t.replace(".", "")
            else -> t
        }
        return try { BigDecimal(t) } catch (e: NumberFormatException) { null }
    }
}

fun BigDecimal.stripTrailingZerosSafe(): BigDecimal =
    if (this.signum() == 0) BigDecimal.ZERO else this.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

fun BigDecimal.isZero() = this.signum() == 0
fun BigDecimal.isPositive() = this.signum() > 0
fun BigDecimal.isNegative() = this.signum() < 0
operator fun BigDecimal.times(o: BigDecimal): BigDecimal = this.multiply(o, Decimals.MC)
fun Iterable<BigDecimal>.sumDec(): BigDecimal = fold(BigDecimal.ZERO) { a, b -> a.add(b, Decimals.MC) }
