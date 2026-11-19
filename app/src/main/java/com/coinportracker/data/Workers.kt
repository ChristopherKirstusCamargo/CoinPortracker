package com.coinportracker.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coinportracker.CoinPortrackerApp
import com.coinportracker.core.Outcome
import java.util.concurrent.TimeUnit

/** Atualização periódica opcional (mín. 15 min, só com rede, respeitando bateria). */
class PriceRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as CoinPortrackerApp).container
        c.chain.refreshPrices() // preços das carteiras on-chain (leve)
        return when (c.market.refreshQuotes()) {
            is Outcome.Ok -> { c.afterRefresh(); Result.success() }
            is Outcome.Err -> if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val NAME = "price_refresh"
        fun schedule(ctx: Context, minutes: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (minutes <= 0) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<PriceRefreshWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
