package com.coinportracker.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * "Saldo ao vivo (suave)": entre dois valores REAIS buscados (a cada 5 s), o saldo exibido anda em
 * passos de 1 s do valor anterior até o novo. Nunca extrapola: cada passo fica entre o valor
 * exibido e o alvo real, então não há "pulos" inventados de R$ 10–20 — só a variação que aconteceu.
 */
object LiveBalance {
    const val FETCH_EVERY_MS = RefreshScheduler.LIVE_EVERY_MS
    const val STEPS = 5

    /** Próximo valor: avança 1/[stepsLeft] da distância restante; com stepsLeft ≤ 1 chega exatamente no alvo. */
    fun step(display: Double, target: Double, stepsLeft: Int): Double {
        if (stepsLeft <= 1 || display == target) return target
        val next = display + (target - display) / stepsLeft
        // garantia numérica: nunca passa do alvo
        return if (target >= display) next.coerceIn(display, target) else next.coerceIn(target, display)
    }

    /** Sequência completa de passos (usada nos testes). */
    fun path(from: Double, to: Double, steps: Int = STEPS): List<Double> {
        var d = from
        return (steps downTo 1).map { left -> d = step(d, to, left); d }
    }
}

/** Valor exibido no Início: igual ao real com o modo desligado; suavizado (1 s) com ele ligado; pausa fora da tela. */
@Composable
fun rememberLiveTotal(target: Double, enabled: Boolean): Double {
    var shown by remember { mutableDoubleStateOf(target) }
    if (!enabled) SideEffect { shown = target }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(target, enabled) {
        if (!enabled || shown == 0.0 || kotlin.math.abs(target - shown) > kotlin.math.abs(shown) * 0.02) { shown = target; return@LaunchedEffect }
        for (left in LiveBalance.STEPS downTo 1) {
            delay(1000)
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            shown = LiveBalance.step(shown, target, left)
        }
    }
    return if (enabled) shown else target
}
