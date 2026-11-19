package com.coinportracker.core

import com.coinportracker.domain.Currency
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    val LOCALE: Locale = Locale("pt", "BR")
    private val symbols = DecimalFormatSymbols(LOCALE)
    const val MASK = "••••••"
    /** A partir de 1 bilhão os valores em dinheiro viram notação compacta ("R$ 1,23 bi"), em todo o app. */
    const val BIG = 1e9
    private val PCT_CAP = BigDecimal(100_000)

    private fun big(v: BigDecimal) = v.abs() >= BigDecimal.valueOf(BIG)

    /** Notação compacta com 2 casas fixas para valores enormes ("1,23 bi", "4,56 tri", "7,89 qua"). */
    fun bigNumber(abs: Double): String {
        val (n, suf) = when {
            abs >= 1e18 -> return String.format(LOCALE, "%.2e", abs).replace("e+", "e")
            abs >= 1e15 -> abs / 1e15 to " qua"
            abs >= 1e12 -> abs / 1e12 to " tri"
            else -> abs / 1e9 to " bi"
        }
        return DecimalFormat("#,##0.00", symbols).format(n) + suf
    }

    fun money(v: BigDecimal?, c: Currency, hide: Boolean = false): String {
        if (v == null) return "—"
        if (hide) return "${c.symbol}\u00A0$MASK"
        val df = DecimalFormat("#,##0.00", symbols)
        val r = v.setScale(2, RoundingMode.HALF_EVEN)
        val s = if (big(r)) bigNumber(r.abs().toDouble()) else df.format(r.abs())
        return (if (r.signum() < 0) "-" else "") + "${c.symbol}\u00A0" + s
    }

    /** Preço unitário: mais casas para ativos baratos. */
    fun price(v: BigDecimal?, c: Currency, hide: Boolean = false): String {
        if (v == null) return "—"
        if (hide) return "${c.symbol}\u00A0$MASK"
        val abs = v.abs()
        if (big(abs)) return (if (v.signum() < 0) "-" else "") + "${c.symbol}\u00A0" + bigNumber(abs.toDouble())
        val pattern = when {
            abs >= BigDecimal(1) -> "#,##0.00"
            abs >= BigDecimal("0.01") -> "#,##0.0000"
            else -> "#,##0.00000000"
        }
        return (if (v.signum() < 0) "-" else "") + "${c.symbol}\u00A0" + DecimalFormat(pattern, symbols).format(abs)
    }

    fun signedMoney(v: BigDecimal?, c: Currency, hide: Boolean = false): String {
        if (v == null) return "—"
        if (hide) return "${c.symbol}\u00A0$MASK"
        val base = money(v.abs(), c)
        // sinal decidido sobre o valor já arredondado: -0,004 vira "R$ 0,00" (nunca "-R$ 0,00" ou "+R$ 0,00")
        val r = v.setScale(2, RoundingMode.HALF_EVEN)
        return when {
            r.signum() > 0 -> "+$base"
            r.signum() < 0 -> "-$base"
            else -> base
        }
    }

    fun qty(v: BigDecimal?, hide: Boolean = false): String {
        if (v == null) return "—"
        if (hide) return MASK
        if (v.abs() >= BigDecimal.valueOf(BIG)) return (if (v.signum() < 0) "-" else "") + bigNumber(v.abs().toDouble())
        val df = DecimalFormat("#,##0.##########", symbols)
        // poucas casas para quantidades grandes (evita "1.234.567,123456789")
        df.maximumFractionDigits = when { v.abs() >= BigDecimal(1_000_000) -> 2; v.abs() >= BigDecimal(1000) -> 4; else -> 10 }
        return df.format(v)
    }

    fun pct(v: BigDecimal?, signed: Boolean = true): String {
        if (v == null) return "—"
        val r = v.setScale(2, RoundingMode.HALF_EVEN)
        // v5: percentuais absurdos (base quase zero) ficam limitados: nada de "+3,72 bi%"
        if (r.abs() >= PCT_CAP) return (if (r.signum() < 0) "<-" else if (signed) ">+" else ">") + DecimalFormat("#,##0", symbols).format(PCT_CAP.subtract(BigDecimal.ONE)) + "%"
        val s = when {
            r.abs() >= BigDecimal(100_000) -> DecimalFormat("#,##0", symbols).format(r.abs())
            else -> DecimalFormat("#,##0.00", symbols).format(r.abs())
        } + "%"
        return when {
            signed && r.signum() > 0 -> "+$s"
            r.signum() < 0 -> "-$s"
            else -> s
        }
    }

    fun pctD(v: Double?, signed: Boolean = true): String =
        if (v == null || v.isNaN() || v.isInfinite()) "—" else pct(BigDecimal(v * 100.0), signed)

    fun num(v: Double?, decimals: Int = 2): String =
        if (v == null || v.isNaN() || v.isInfinite()) "—"
        else DecimalFormat("#,##0." + "0".repeat(decimals), symbols).format(v)

    fun compact(v: BigDecimal?, c: Currency): String {
        if (v == null) return "—"
        val neg = v.signum() < 0
        val d = v.abs().toDouble()
        if (d >= 1e15) return (if (neg) "-" else "") + "${c.symbol}\u00A0" + bigNumber(d)
        val (n, suf) = when {
            d >= 1e12 -> d / 1e12 to " tri"
            d >= 1e9 -> d / 1e9 to " bi"
            d >= 1e6 -> d / 1e6 to " mi"
            d >= 1e3 -> d / 1e3 to " mil"
            else -> d to ""
        }
        return (if (neg) "-" else "") + "${c.symbol}\u00A0" + DecimalFormat("#,##0.##", symbols).format(n) + suf
    }

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", LOCALE)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", LOCALE)
    private val shortFmt = DateTimeFormatter.ofPattern("dd/MM", LOCALE)

    fun date(epochMs: Long?): String = epochMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dateFmt) } ?: "—"
    fun dateTime(epochMs: Long?): String = epochMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dateTimeFmt) } ?: "—"
    fun day(d: LocalDate): String = d.format(dateFmt)
    fun shortDay(d: LocalDate): String = d.format(shortFmt)

    fun ago(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMs == null) return "nunca"
        val s = (now - epochMs) / 1000
        return when {
            s < 60 -> "agora"
            s < 3600 -> "há ${s / 60} min"
            s < 86400 -> "há ${s / 3600} h"
            else -> "há ${s / 86400} d"
        }
    }
}
