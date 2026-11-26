package com.coinportracker.ui

/**
 * Quando ler saldos de verdade. Preço e quantidade são relógios separados:
 * a cotação pode andar a cada poucos segundos; o saldo on-chain só muda com uma leitura
 * que a rede respondeu. Falha aumenta a espera e nunca antecipa um "sucesso".
 */
object BalanceCadence {
    const val LOW_POWER_MS = 5 * 60_000L
    private const val FAILURE_CAP_MS = 5 * 60_000L

    fun balanceEveryMs(live: Boolean, refreshSec: Int, wallets: Int, networks: Int, lowPower: Boolean): Long {
        if (lowPower) return LOW_POWER_MS
        if (live) {
            val load = wallets.coerceAtLeast(0) * networks.coerceAtLeast(1)
            return when {
                load <= 4 -> 12_000L
                load <= 10 -> 18_000L
                load <= 20 -> 24_000L
                else -> 30_000L
            }
        }
        if (refreshSec <= 0) return Long.MAX_VALUE
        return refreshSec * 1000L
    }

    /**
     * [failures] == 0 espera [intervalMs] desde o último sucesso.
     * Cada falha dobra a espera desde a última tentativa, com teto de 5 min.
     */
    fun balanceDue(lastSuccess: Long, lastAttempt: Long, failures: Int, intervalMs: Long, now: Long): Boolean {
        if (intervalMs <= 0L || intervalMs == Long.MAX_VALUE) return false
        if (lastSuccess == 0L && lastAttempt == 0L) return true
        val wait = if (failures <= 0) intervalMs
        else (intervalMs * (1L shl failures.coerceIn(1, 3))).coerceAtMost(FAILURE_CAP_MS).coerceAtLeast(intervalMs)
        val anchor = if (failures <= 0) lastSuccess else lastAttempt
        return now - anchor >= wait
    }
}
