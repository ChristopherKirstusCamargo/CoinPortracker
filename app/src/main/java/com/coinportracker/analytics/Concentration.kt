package com.coinportracker.analytics

import com.coinportracker.core.Decimals
import com.coinportracker.core.sumDec
import java.math.BigDecimal

data class Slice(val key: String, val label: String, val value: BigDecimal, val pct: BigDecimal)

data class ConcentrationReport(
    val byAsset: List<Slice>,
    val hhi: BigDecimal?,          // 0..10000
    val top5Pct: BigDecimal?,
    val topAssetPct: BigDecimal?,
    val level: String,
)

object Concentration {
    /** HHI = Σ (participação%)². < 1500 baixa, 1500–2500 moderada, > 2500 alta (escala DOJ/FTC). */
    fun hhi(values: List<BigDecimal>): BigDecimal? {
        val total = values.sumDec()
        if (total.signum() <= 0) return null
        return values.map { v -> Decimals.pct(v, total)!!.let { it.multiply(it, Decimals.MC) } }.sumDec()
    }

    fun level(hhi: BigDecimal?): String = when {
        hhi == null -> "Indisponível"
        hhi < BigDecimal(1500) -> "Baixa"
        hhi <= BigDecimal(2500) -> "Moderada"
        else -> "Alta"
    }

    fun slices(groups: Map<String, Pair<String, BigDecimal>>): List<Slice> {
        val total = groups.values.map { it.second }.sumDec()
        return groups.map { (k, v) -> Slice(k, v.first, v.second, Decimals.pct(v.second, total) ?: BigDecimal.ZERO) }
            .sortedByDescending { it.value }
    }

    fun report(byAsset: List<Slice>): ConcentrationReport {
        val h = hhi(byAsset.map { it.value })
        val top5 = byAsset.take(5).map { it.pct }.sumDec()
        return ConcentrationReport(byAsset, h, if (byAsset.isEmpty()) null else top5, byAsset.firstOrNull()?.pct, level(h))
    }
}
