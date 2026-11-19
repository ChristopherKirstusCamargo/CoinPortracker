package com.coinportracker.history

/**
 * Câmbio histórico (v5). O motor de histórico trabalha em USD, mas o usuário pensa em reais: um ponto de 30 dias atrás
 * valia `usd × câmbio daquele dia`, não `usd × câmbio de hoje`. Converter o passado pelo câmbio de hoje mistura a variação
 * do dólar com a do preço (em nov/2026 o dólar subiu ~15% em 30 dias e o app mostrava "−15%" de mercado e um
 * "lucro máximo" que nunca existiu em reais).
 *
 * [factor] devolve `câmbio(t) / câmbio(agora)`: multiplicando um preço em USD por ele, o valor continua em "USD de hoje",
 * mas `× câmbio de hoje` (o que as telas já fazem) dá exatamente o valor em reais da época.
 * Só converte quando a série diária cobre o intervalo; senão devolve null e o chamador mantém o comportamento anterior.
 */
object FxRebase {
    private const val DAY = 86_400_000L
    /** Taxa do BCE é publicada ~16h CET: tratamos cada dia como válido às 15h UTC. */
    private const val PUBLISH_OFFSET = 15L * 3_600_000L

    /**
     * [daily]: dia (epochDay UTC) → BRL por 1 USD. [fxNow]: câmbio atual (mesmo usado nas telas).
     * [from]: início do intervalo; precisa de taxa até [maxEdgeDays] antes dele.
     */
    fun factor(daily: Map<Long, Double>, fxNow: Double, from: Long, now: Long, maxEdgeDays: Int = 5): ((Long) -> Double)? {
        if (fxNow <= 0 || fxNow.isNaN()) return null
        val pts = daily.entries.filter { it.value > 0 && !it.value.isNaN() }.sortedBy { it.key }
            .map { it.key * DAY + PUBLISH_OFFSET to it.value }.filter { it.first < now }
        if (pts.isEmpty()) return null
        if (pts.first().first - from > maxEdgeDays * DAY) return null
        val ts = LongArray(pts.size + 1); val vs = DoubleArray(pts.size + 1)
        pts.forEachIndexed { i, (t, v) -> ts[i] = t; vs[i] = v }
        // último ponto = agora, com o câmbio ao vivo (sem degrau no fim do gráfico)
        ts[pts.size] = now; vs[pts.size] = fxNow
        return { t ->
            val rate = when {
                t <= ts[0] -> vs[0]
                t >= now -> fxNow
                else -> {
                    var lo = 0; var hi = ts.size - 1
                    while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (ts[mid] <= t) lo = mid else hi = mid }
                    val span = (ts[hi] - ts[lo]).coerceAtLeast(1)
                    vs[lo] + (vs[hi] - vs[lo]) * ((t - ts[lo]).toDouble() / span)
                }
            }
            rate / fxNow
        }
    }

    /** Aplica o fator a cada ponto das séries de preço (USD → "USD de hoje" equivalente ao valor em reais da época). */
    fun rebase(series: Map<String, List<ValuePoint>>, f: (Long) -> Double): Map<String, List<ValuePoint>> =
        series.mapValues { (_, s) -> s.map { ValuePoint(it.t, it.usd * f(it.t)) } }
}
