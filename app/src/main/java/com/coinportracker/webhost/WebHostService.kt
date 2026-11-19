package com.coinportracker.webhost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.coinportracker.MainActivity
import com.coinportracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * v6.0 — serviço em primeiro plano do Web Host. Enquanto o painel está ligado, há SEMPRE uma notificação fixa com o
 * endereço e o botão Parar; pedidos de pareamento aparecem com Aprovar/Recusar. START_NOT_STICKY: se o sistema matar o
 * processo, o servidor não volta sozinho.
 */
class WebHostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    companion object {
        const val CHANNEL = "webhost"
        private const val NOTIF_ID = 6060
        private const val ACTION_START = "com.coinportracker.webhost.START"
        private const val ACTION_STOP = "com.coinportracker.webhost.STOP"
        private const val ACTION_APPROVE = "com.coinportracker.webhost.APPROVE"
        private const val ACTION_DENY = "com.coinportracker.webhost.DENY"
        private const val EXTRA_ID = "id"

        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, WebHostService::class.java).setAction(ACTION_START)) }
        fun stop(ctx: Context) {
            if (WebHostController.state.value.running || WebHostController.state.value.starting) ctx.startService(Intent(ctx, WebHostService::class.java).setAction(ACTION_STOP))
            else WebHostController.stop(ctx)
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Web Host (painel no navegador)", NotificationManager.IMPORTANCE_LOW).apply { description = "Mostra quando o painel está ligado e os pedidos de pareamento."; setShowBadge(false) })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        // obrigatório chamar startForeground logo, mesmo se o pedido for para parar
        goForeground(build(WebHostController.state.value))
        when (intent?.action) {
            ACTION_STOP -> { shutdown("Parado pela notificação."); return START_NOT_STICKY }
            ACTION_APPROVE -> intent.getStringExtra(EXTRA_ID)?.let { WebHostController.approve(this, it) }
            ACTION_DENY -> intent.getStringExtra(EXTRA_ID)?.let { WebHostController.deny(this, it) }
            ACTION_START, null -> {
                WebHostController.onPendingChanged = { scope.launch { notifyNow() } }
                scope.launch(Dispatchers.IO) {
                    WebHostController.start(this@WebHostService) { reason -> scope.launch { shutdown(reason) } }
                    if (!WebHostController.state.value.running) scope.launch { shutdown(WebHostController.state.value.error) }
                }
                if (watch == null) watch = scope.launch {
                    WebHostController.state.map { Triple(it.running, it.pending.map { p -> p.id }, it.primary?.ip to it.devices.size) }.distinctUntilChanged().collect { notifyNow() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, n, type) }.onFailure { stopSelf() }
    }

    private fun notifyNow() {
        val st = WebHostController.state.value
        if (!st.running && !st.starting) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(st)) }
    }

    private fun shutdown(reason: String?) {
        WebHostController.onPendingChanged = null
        WebHostController.stop(this, reason)
        watch?.cancel(); watch = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // o sistema ou o usuário encerrou o serviço: o servidor nunca fica rodando escondido
        WebHostController.onPendingChanged = null
        if (WebHostController.state.value.running) WebHostController.stop(this, "Serviço encerrado.")
        scope.cancel()
        super.onDestroy()
    }

    private fun pi(action: String, id: String? = null, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, WebHostService::class.java).setAction(action).apply { id?.let { putExtra(EXTRA_ID, it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun build(st: WebHostState): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val p = st.pending.firstOrNull()
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_web).setOngoing(true).setOnlyAlertOnce(p == null)
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Parar", pi(ACTION_STOP, req = 1))
        if (p != null) {
            b.setContentTitle("Pedido de acesso ao painel").setContentText("${p.name} (${p.ip}) quer ver seu portfólio.")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setOnlyAlertOnce(false)
                .addAction(0, "Aprovar", pi(ACTION_APPROVE, p.id, 2)).addAction(0, "Recusar", pi(ACTION_DENY, p.id, 3))
        } else {
            val where = st.url() ?: "aguardando rede Wi-Fi"
            val dev = st.devices.size.let { if (it == 0) "nenhum aparelho pareado" else if (it == 1) "1 aparelho pareado" else "$it aparelhos pareados" }
            b.setContentTitle(if (st.running) "Web Host ligado (só leitura)" else "Ligando o Web Host…").setContentText("$where · $dev · HTTP sem criptografia")
        }
        return b.build()
    }
}
