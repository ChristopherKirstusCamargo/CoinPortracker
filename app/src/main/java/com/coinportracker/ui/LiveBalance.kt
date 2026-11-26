package com.coinportracker.ui

import androidx.compose.runtime.Composable

/**
 * O número na tela é sempre o último patrimônio calculado (quantidade confirmada × cotação).
 * [step] e [path] existem só para a transição visual não ultrapassar os dois valores reais;
 * a UI não os usa como saldo. A animação dos dígitos fica no SlotText.
 */
object LiveBalance {
    /** Valor que a Início pode mostrar: o alvo real, sem passos intermediários. */
    fun displayed(target: Double): Double = target

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

/**
 * Patrimônio exibido. [enabled] continua na assinatura (o modo ao vivo acelera as leituras reais);
 * o valor em si não é interpolado — um número intermediário pareceria um saldo que a rede não devolveu.
 */
@Composable
fun rememberLiveTotal(target: Double, enabled: Boolean): Double = if (enabled) LiveBalance.displayed(target) else target
