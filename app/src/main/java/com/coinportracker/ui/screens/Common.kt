package com.coinportracker.ui.screens

import com.coinportracker.analytics.DayPoint
import java.time.LocalDate

enum class Period(val label: String, val days: Long?) { D1("24h", 1), D7("7d", 7), D30("30d", 30), D90("90d", 90), Y1("1a", 365), Y3("3a", 1095), ALL("Tudo", null) }

/** Recorta a série respeitando os dados reais disponíveis (sem preencher lacunas). */
fun List<DayPoint>.window(p: Period): List<DayPoint> {
    val d = p.days ?: return this
    val from = LocalDate.now().minusDays(d)
    return filter { !it.day.isBefore(from) }
}

fun LocalDate.ms(): Long = toEpochDay() * 86_400_000L
