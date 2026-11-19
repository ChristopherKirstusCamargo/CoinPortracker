package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal

data class LivePrice(val key: String, val usd: Double, val change24hPct: Double?, val confidence: Double?, val symbol: String?, val fetchedAt: Long)
data class PricePoint(val t: Long, val usd: Double)

/**
 * Preços gratuitos e sem chave:
 * - DefiLlama coins API (preço atual em lote, variação 24 h e séries históricas para qualquer token EVM/Solana ou id CoinGecko);
 * - câmbio USD→BRL ao vivo pelo CoinGecko (BTC em BRL ÷ BTC em USD), com fallback para Frankfurter (BCE).
 */
class PriceService(private val http: HttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val policy = RatePolicy(minIntervalMs = 250, maxRetries = 2, baseBackoffMs = 1_500, maxBackoffMs = 10_000)
    private val base get() = com.coinportracker.dev.DevSources.priceUrl("defillama", "https://coins.llama.fi")

    suspend fun current(keys: Collection<String>): Outcome<Map<String, LivePrice>> {
        if (!com.coinportracker.dev.DevSources.priceEnabled("defillama")) return Outcome.Err("DefiLlama desligado nas configurações avançadas")
        val out = HashMap<String, LivePrice>()
        var lastErr: Outcome.Err? = null
        val now = clock()
        for (chunk in keys.distinct().chunked(60)) {
            val joined = chunk.joinToString(",")
            val p = http.get("llama-live", "$base/prices/current/$joined?searchWidth=6h", policy)
            if (p is Outcome.Err) { lastErr = p; continue }
            val ch = http.get("llama-live", "$base/percentage/$joined?period=24h", policy)
            val changes = (ch as? Outcome.Ok)?.value?.let { runCatching { parseChanges(chainJson.parseToJsonElement(it)) }.getOrNull() } ?: emptyMap()
            runCatching { parsePrices(chainJson.parseToJsonElement((p as Outcome.Ok).value), changes, now) }.getOrNull()?.forEach { out[it.key] = it }
        }
        return if (out.isEmpty() && lastErr != null) lastErr else Outcome.Ok(out)
    }

    /** Séries de preço em USD; [span] pontos espaçados por [period] (ex.: "1h", "4h", "1d", "7d"). */
    suspend fun chart(keys: Collection<String>, startSec: Long, span: Int, period: String): Map<String, List<PricePoint>> {
        val out = HashMap<String, List<PricePoint>>()
        for (chunk in keys.distinct().chunked(25)) {
            val r = http.get("llama", "$base/chart/${chunk.joinToString(",")}?start=$startSec&span=$span&period=$period&searchWidth=600", policy)
            if (r is Outcome.Ok) runCatching { parseChart(chainJson.parseToJsonElement(r.value)) }.getOrNull()?.let { out.putAll(it) }
        }
        return out
    }

    /**
     * Preços históricos em instantes exatos (DefiLlama batchHistorical, sem chave): usado para estimar o preço médio
     * pelo preço de mercado no momento de cada entrada on-chain. [want]: chave → instantes em segundos.
     */
    suspend fun historical(want: Map<String, Collection<Long>>): Map<String, List<PricePoint>> {
        val out = HashMap<String, MutableList<PricePoint>>()
        val flat = want.flatMap { (k, ts) -> ts.distinct().map { k to it } }
        for (chunk in flat.chunked(60)) {
            val byKey = chunk.groupBy({ it.first }, { it.second })
            val json = byKey.entries.joinToString(",", "{", "}") { (k, ts) -> "\"$k\":[" + ts.joinToString(",") + "]" }
            val url = "$base/batchHistorical?coins=" + java.net.URLEncoder.encode(json, "UTF-8") + "&searchWidth=6h"
            val r = http.get("llama", url, policy)
            if (r is Outcome.Ok) runCatching { parseChart(chainJson.parseToJsonElement(r.value)) }.getOrNull()?.forEach { (k, v) -> out.getOrPut(k) { ArrayList() }.addAll(v) }
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.t } }
    }

    /** Câmbio USD→BRL (BRL por 1 USD). */
    suspend fun usdBrl(): Outcome<BigDecimal> {
        val cg = if (!com.coinportracker.dev.DevSources.priceEnabled("coingecko")) null else http.get("coingecko", com.coinportracker.dev.DevSources.priceUrl("coingecko", "https://api.coingecko.com/api/v3").trimEnd('/') + "/simple/price?ids=tether&vs_currencies=brl,usd", policy)
        if (cg is Outcome.Ok) {
            val o = runCatching { chainJson.parseToJsonElement(cg.value).o()?.get("tether").o() }.getOrNull()
            val brl = o?.get("brl").s()?.toBigDecimalOrNull(); val usd = o?.get("usd").s()?.toBigDecimalOrNull()
            if (brl != null && usd != null && usd.signum() > 0) return Outcome.Ok(brl.divide(usd, java.math.MathContext.DECIMAL64))
        }
        val fx = if (!com.coinportracker.dev.DevSources.priceEnabled("frankfurter")) null else http.get("frankfurter", com.coinportracker.dev.DevSources.priceUrl("frankfurter", "https://api.frankfurter.dev/v1").trimEnd('/') + "/latest?from=USD&to=BRL", policy)
        if (fx is Outcome.Ok) runCatching { chainJson.parseToJsonElement(fx.value).o()?.get("rates").o()?.get("BRL").s()?.toBigDecimalOrNull() }.getOrNull()?.let { return Outcome.Ok(it) }
        return Outcome.Err("Câmbio USD/BRL indisponível")
    }

    companion object {
        fun parsePrices(el: JsonElement, changes: Map<String, Double>, now: Long): List<LivePrice> =
            el.o()?.get("coins").o().orEmpty().mapNotNull { (k, v) ->
                val o = v.o() ?: return@mapNotNull null
                val price = o["price"].s()?.toDoubleOrNull() ?: return@mapNotNull null
                LivePrice(k, price, changes[k], o["confidence"].s()?.toDoubleOrNull(), o["symbol"].s(), now)
            }

        fun parseChanges(el: JsonElement): Map<String, Double> =
            el.o()?.get("coins").o().orEmpty().mapNotNull { (k, v) -> v.s()?.toDoubleOrNull()?.let { k to it } }.toMap()

        fun parseChart(el: JsonElement): Map<String, List<PricePoint>> =
            el.o()?.get("coins").o().orEmpty().mapValues { (_, v) ->
                v.o()?.get("prices").a().orEmpty().mapNotNull { p ->
                    val po = p.o() ?: return@mapNotNull null
                    val t = po["timestamp"].s()?.toLongOrNull() ?: return@mapNotNull null
                    val u = po["price"].s()?.toDoubleOrNull() ?: return@mapNotNull null
                    PricePoint(t * 1000, u)
                }.sortedBy { it.t }
            }
    }
}
