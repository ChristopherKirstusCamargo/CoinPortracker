package com.coinportracker.ui.anim

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Formatação de valores intermediários na "Contagem suave": reaproveita o próprio texto exibido como molde
 * (prefixo "R$ ", sinal, separadores pt-BR e casas decimais), então a contagem nunca muda o formato do número.
 */
object NumberMorph {
    data class Template(val prefix: String, val suffix: String, val decimals: Int, val signed: Boolean, val value: Double, val dotDecimal: Boolean)

    private fun isSign(c: Char) = c == '-' || c == '+' || c == '−'

    /** null quando o texto não é um número formatado (ex.: valor oculto "•••"). */
    fun parse(text: String): Template? {
        val first = text.indexOfFirst { it.isDigit() }
        val last = text.indexOfLast { it.isDigit() }
        if (first < 0) return null
        val core = text.substring(first, last + 1)
        if (core.any { !it.isDigit() && it != '.' && it != ',' }) return null
        val rawPrefix = text.substring(0, first)
        val suffix = text.substring(last + 1)
        val signed = rawPrefix.any(::isSign)
        val neg = rawPrefix.any { it == '-' || it == '−' }
        val dotDecimal = !core.contains(',') && core.count { it == '.' } == 1 && core.substringAfter('.').length != 3
        val decSep = if (dotDecimal) '.' else ','
        val decimals = if (core.contains(decSep)) core.substringAfterLast(decSep).length else 0
        val digits = if (dotDecimal) core else core.replace(".", "").replace(',', '.')
        val v = digits.toDoubleOrNull() ?: return null
        return Template(rawPrefix, suffix, decimals, signed, if (neg) -v else v, dotDecimal)
    }

    /** Formata [value] no molde [t] (sinal no mesmo lugar do original). */
    fun format(t: Template, value: Double): String {
        if (value.isNaN() || value.isInfinite()) return t.prefix + "0" + t.suffix
        val bd = BigDecimal.valueOf(kotlin.math.abs(value)).setScale(t.decimals, RoundingMode.HALF_EVEN)
        val intPart = bd.toBigInteger().toString()
        val grouped = if (t.dotDecimal) intPart else intPart.reversed().chunked(3).joinToString(".").reversed()
        val frac = if (t.decimals > 0) (if (t.dotDecimal) "." else ",") + bd.toPlainString().substringAfter('.').padEnd(t.decimals, '0') else ""
        val isZero = bd.signum() == 0
        val prefix = if (!t.signed) t.prefix else {
            val sign = if (value < 0 && !isZero) "-" else if (t.prefix.contains('+')) "+" else ""
            val i = t.prefix.indexOfFirst(::isSign)
            if (i < 0) t.prefix else t.prefix.substring(0, i) + sign + t.prefix.substring(i + 1)
        }
        return prefix + grouped + frac + t.suffix
    }

    /** Interpolação linear entre dois números no molde do texto de destino. */
    fun lerpText(from: Double, to: Template, f: Float): String = format(to, from + (to.value - from) * f)
}
