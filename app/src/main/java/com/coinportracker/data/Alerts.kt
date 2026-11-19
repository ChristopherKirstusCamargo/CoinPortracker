package com.coinportracker.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.coinportracker.R
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.database.AlertEntity
import com.coinportracker.database.QuoteEntity
import com.coinportracker.domain.Currency
import java.math.BigDecimal

enum class AlertKind(val label: String, val unit: String) {
    PRICE_ABOVE("Preço acima de", "preço"),
    PRICE_BELOW("Preço abaixo de", "preço"),
    CHANGE_24H("Variação 24h (±%) maior que", "%"),
    PORTFOLIO_CHANGE("Variação do patrimônio (±%) desde o último disparo", "%"),
    PORTFOLIO_DAY("Variação do portfólio no dia (±%)", "%"),
    CONCENTRATION("Maior ativo acima de (% do patrimônio)", "%"),
    GOAL("Patrimônio atingir a meta", "valor"),
}

data class AlertHit(val alert: AlertEntity, val message: String, val newBaseline: String?)

/** Avaliação 100% local. Alertas sem repetição disparam uma vez; com repetição, no máximo 1×/6h. */
object AlertEvaluator {
    const val REPEAT_COOLDOWN_MS = 6 * 3_600_000L

    /** Tipos cuja mensagem contém valores do patrimônio do usuário (não só preço público de mercado). */
    fun revealsPortfolio(kind: String): Boolean = kind in setOf(AlertKind.PORTFOLIO_CHANGE.name, AlertKind.PORTFOLIO_DAY.name, AlertKind.CONCENTRATION.name, AlertKind.GOAL.name)

    fun redactedMessage(a: AlertEntity): String {
        val k = runCatching { AlertKind.valueOf(a.kind) }.getOrNull()
        return "Alerta disparado: ${a.label.ifBlank { k?.label ?: "portfólio" }}. Abra o app para ver os valores."
    }

    data class Price(val id: String, val symbol: String, val price: java.math.BigDecimal, val change24hPct: java.math.BigDecimal?, val currency: Currency, val source: String = "")

    fun evaluate(alerts: List<AlertEntity>, quotes: List<QuoteEntity>, equity: BigDecimal?, topSharePct: BigDecimal?, currency: Currency, now: Long, dayChangePct: BigDecimal? = null, live: List<Price> = emptyList()): Pair<List<AlertHit>, List<AlertEntity>> {
        val q = quotes.associateBy { it.assetId to it.currency }
        // v5: o preço ao vivo só vale para alertas na MESMA moeda (antes um alerta em R$ podia ser comparado com o preço em US$)
        val liveById = live.groupBy { it.id }
        val liveBySym = live.groupBy { it.symbol.uppercase() }
        val hits = ArrayList<AlertHit>()
        val baselineUpdates = ArrayList<AlertEntity>()
        for (a in alerts.filter { it.enabled }) {
            val kind = runCatching { AlertKind.valueOf(a.kind) }.getOrNull() ?: continue
            if (a.lastTriggeredAt != null && (!a.repeat || now - a.lastTriggeredAt < REPEAT_COOLDOWN_MS)) continue
            val th = a.threshold.dec() ?: continue
            val cur = runCatching { Currency.valueOf(a.currency) }.getOrDefault(currency)
            val quote = a.assetId?.let { q[it to cur.name] }
            val livePx = a.assetId?.let { id -> (liveById[id] ?: liveBySym[id.uppercase()])?.firstOrNull { it.currency == cur } }
            val price = livePx?.price ?: quote?.price?.dec()
            val ch24 = livePx?.change24hPct ?: quote?.change24hPct?.dec()
            val src = livePx?.source ?: quote?.source
            when (kind) {
                AlertKind.PRICE_ABOVE -> if (price != null && price >= th) hits += AlertHit(a, "${a.label}: ${Fmt.price(price, cur)} ≥ ${Fmt.price(th, cur)}" + (src?.let { " (fonte $it)" } ?: ""), null)
                AlertKind.PRICE_BELOW -> if (price != null && price <= th) hits += AlertHit(a, "${a.label}: ${Fmt.price(price, cur)} ≤ ${Fmt.price(th, cur)}" + (src?.let { " (fonte $it)" } ?: ""), null)
                AlertKind.CHANGE_24H -> ch24?.let { ch -> if (ch.abs() >= th) hits += AlertHit(a, "${a.label}: variação 24h de ${Fmt.pct(ch)}", null) }
                AlertKind.PORTFOLIO_CHANGE -> if (equity != null && equity.signum() > 0) {
                    val base = a.lastValue.dec()
                    if (base == null) baselineUpdates += a.copy(lastValue = equity.toPlainString())
                    else Decimals.pct(equity.subtract(base), base)?.let { ch -> if (ch.abs() >= th) hits += AlertHit(a, "Patrimônio variou ${Fmt.pct(ch)}: ${Fmt.money(base, currency)} → ${Fmt.money(equity, currency)}", equity.toPlainString()) }
                }
                AlertKind.PORTFOLIO_DAY -> dayChangePct?.let { ch -> if (ch.abs() >= th) hits += AlertHit(a, "Portfólio no dia: ${Fmt.pct(ch)} (limite ±${Fmt.pct(th, false)})", equity?.toPlainString()) }
                AlertKind.CONCENTRATION -> if (topSharePct != null && topSharePct >= th) hits += AlertHit(a, "Maior ativo representa ${Fmt.pct(topSharePct, false)} do patrimônio (limite ${Fmt.pct(th, false)})", null)
                AlertKind.GOAL -> if (equity != null && equity >= th) hits += AlertHit(a, "Meta atingida: patrimônio de ${Fmt.money(equity, currency)} ≥ ${Fmt.money(th, currency)}", null)
            }
        }
        return hits to baselineUpdates
    }
}

object Notifier {
    const val CHANNEL = "alerts"
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alertas de preço e patrimônio", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Alertas configurados por você, processados no aparelho." })
        }
    }

    /** Toque na notificação abre o app pela tela inicial (o bloqueio biométrico continua valendo). */
    private fun launchIntent(ctx: Context): android.app.PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) ?: return null
        return android.app.PendingIntent.getActivity(ctx, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        // Notificações sensíveis: conteúdo oculto na tela de bloqueio.
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_alert).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // versão pública explícita para a tela de bloqueio (sem valores)
            .setPublicVersion(NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_alert).setContentTitle("CoinPortracker").setContentText("Novo alerta").build())
            .setContentIntent(launchIntent(ctx))
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
