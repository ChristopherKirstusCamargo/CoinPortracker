package com.coinportracker.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

data class RefreshPlan(val everyMs: Long, val live: Boolean, val lowPower: Boolean = false)

/**
 * Laço único de atualização em primeiro plano. Cada ciclo tem tempo-limite e erros não encerram o
 * laço; trocar a configuração reinicia o laço com o novo intervalo.
 */
object RefreshScheduler {
    const val LIVE_EVERY_MS = 5_000L

    fun plan(foreground: Boolean, refreshSec: Int, live: Boolean): RefreshPlan? = when {
        !foreground -> null
        live -> RefreshPlan(LIVE_EVERY_MS, true)
        refreshSec > 0 -> RefreshPlan(refreshSec * 1000L, false)
        else -> null
    }

    suspend fun run(plans: Flow<RefreshPlan?>, tick: suspend (RefreshPlan) -> Unit) {
        plans.distinctUntilChanged().collectLatest { p ->
            if (p == null) return@collectLatest
            while (currentCoroutineContext().isActive) {
                delay(p.everyMs)
                try { withTimeoutOrNull(maxOf(p.everyMs, 25_000L)) { tick(p) } }
                catch (e: CancellationException) { if (!currentCoroutineContext().isActive) throw e }
                catch (_: Throwable) { }
            }
        }
    }
}
