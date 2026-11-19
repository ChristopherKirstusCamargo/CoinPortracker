package com.coinportracker.market

import com.coinportracker.core.Outcome
import com.coinportracker.domain.Currency
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

internal fun JsonElement?.dec(): BigDecimal? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.contentOrNull?.let { runCatching { BigDecimal(it) }.getOrNull() }
}
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * CoinGecko API pública (api.coingecko.com/api/v3).
 * Verificado em 12/11/2026: sem chave = limite por IP (compartilhado, não documentado em número);
 * plano Demo gratuito = 100 chamadas/min com chave `x_cg_demo_api_key`; histórico público limitado a 365 dias
 * (erro 10012 acima disso). Todas as requisições contam no limite, inclusive erros.
 */
class CoinGeckoProvider(private val http: HttpClient, private val demoKey: () -> String = { "" }) : MarketDataProvider {
    override val id = "coingecko"
    override val displayName = "CoinGecko"
    override val attribution = "Dados de mercado fornecidos por CoinGecko"
    override val limitsDescription = "Sem chave: limite por IP (compartilhado). Chave Demo gratuita opcional: 100 chamadas/min. Histórico: 365 dias."
    override val maxHistoryDays = 365
    private val base get() = com.coinportracker.dev.DevSources.priceUrl("coingecko", "https://api.coingecko.com/api/v3")
    private fun policy() = if (demoKey().isNotBlank()) RatePolicy(minIntervalMs = 700) else RatePolicy(minIntervalMs = 4_000)
    private fun headers() = demoKey().takeIf { it.isNotBlank() }?.let { mapOf("x-cg-demo-api-key" to it) } ?: emptyMap()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override fun idFor(coingeckoId: String?, paprikaId: String?) = coingeckoId

    /** Converte a resposta; formato inesperado (erro em JSON, campo ausente) vira Outcome.Err em vez de exceção. */
    private inline fun <T> Outcome<JsonElement>.parse(f: (JsonElement) -> T): Outcome<T> = when (this) {
        is Outcome.Err -> this
        is Outcome.Ok -> com.coinportracker.core.catchingNonCancel { f(value) }.fold({ Outcome.Ok(it) }, { Outcome.Err("Resposta inesperada do CoinGecko", it) })
    }

    private suspend fun getJson(path: String): Outcome<JsonElement> = when (val r = http.get(id, base + path, policy(), headers())) {
        is Outcome.Ok -> runCatching { Outcome.Ok(lenientJson.parseToJsonElement(r.value)) }.getOrElse { Outcome.Err("Resposta inválida do CoinGecko", it) }
        is Outcome.Err -> r
    }

    override suspend fun quotes(ids: List<String>, currencies: List<Currency>): Outcome<List<MarketQuote>> {
        if (ids.isEmpty()) return Outcome.Ok(emptyList())
        val out = ArrayList<MarketQuote>()
        for (chunk in ids.distinct().chunked(100)) {
            val vs = currencies.joinToString(",") { it.code }
            val r = getJson("/simple/price?ids=${enc(chunk.joinToString(","))}&vs_currencies=$vs&include_market_cap=true&include_24hr_vol=true&include_24hr_change=true&include_last_updated_at=true&precision=full")
            if (r is Outcome.Err) return r
            val obj = (r as Outcome.Ok).value.jsonObject
            for ((cid, v) in obj) {
                val o = v.jsonObject
                val updated = o["last_updated_at"]?.jsonPrimitive?.longOrNull?.times(1000)
                for (c in currencies) {
                    val price = o[c.code].dec() ?: continue
                    out += MarketQuote(cid, c, price, o["${c.code}_24h_change"].dec(), o["${c.code}_market_cap"].dec(), o["${c.code}_24h_vol"].dec(), providerUpdatedAt = updated)
                }
            }
        }
        return Outcome.Ok(out)
    }

    /** Dados de oferta/rank via /coins/markets (em lote), complementando /simple/price. */
    suspend fun supply(ids: List<String>, currency: Currency): Outcome<List<MarketCoin>> {
        if (ids.isEmpty()) return Outcome.Ok(emptyList())
        val out = ArrayList<MarketCoin>()
        for (chunk in ids.distinct().chunked(250)) {
            val r = getJson("/coins/markets?vs_currency=${currency.code}&ids=${enc(chunk.joinToString(","))}&per_page=250&page=1")
            if (r is Outcome.Err) return r
            out += parseMarkets((r as Outcome.Ok).value)
        }
        return Outcome.Ok(out)
    }

    override suspend fun history(id: String, currency: Currency, days: Int): Outcome<List<PricePoint>> {
        val d = days.coerceIn(1, maxHistoryDays)
        return getJson("/coins/${enc(id)}/market_chart?vs_currency=${currency.code}&days=$d&interval=daily").parse { el ->
            val byDay = LinkedHashMap<Long, BigDecimal>()
            el.jsonObject["prices"]?.jsonArray?.forEach { p ->
                val a = p.jsonArray
                val ts = a[0].jsonPrimitive.longOrNull ?: return@forEach
                val price = a[1].dec() ?: return@forEach
                byDay[Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()] = price
            }
            byDay.map { PricePoint(it.key, it.value) }
        }
    }

    override suspend fun search(query: String): Outcome<List<AssetSearchResult>> =
        getJson("/search?query=${enc(query)}").parse { el ->
            (el as? JsonObject)?.get("coins")?.let { it as? JsonArray }?.take(25)?.mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                AssetSearchResult(o["id"].str() ?: return@mapNotNull null, o["symbol"].str().orEmpty().uppercase(), o["name"].str().orEmpty(), o["market_cap_rank"]?.jsonPrimitive?.intOrNull, o["thumb"].str())
            } ?: emptyList()
        }

    // v5: item malformado/parcial é ignorado (antes um único item sem "id" derrubava a lista inteira)
    private fun parseMarkets(el: JsonElement): List<MarketCoin> = (el as? JsonArray)?.mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        val id = o["id"].str()?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
        MarketCoin(
            id = id, symbol = o["symbol"].str().orEmpty().uppercase(), name = o["name"].str().orEmpty(), image = o["image"].str(),
            price = o["current_price"].dec()?.toPlainString(), change24hPct = o["price_change_percentage_24h"].dec()?.toPlainString(),
            marketCap = o["market_cap"].dec()?.toPlainString(), volume24h = o["total_volume"].dec()?.toPlainString(),
            rank = o["market_cap_rank"]?.jsonPrimitive?.intOrNull, circulating = o["circulating_supply"].dec()?.toPlainString(),
            maxSupply = o["max_supply"].dec()?.toPlainString(),
            sparkline = (o["sparkline_in_7d"] as? JsonObject)?.get("price")?.jsonArray?.mapNotNull { p -> p.dec()?.toDouble() } ?: emptyList(),
        )
    } ?: emptyList()

    /** Ids de uma categoria do CoinGecko (ex.: tokenized-products), por capitalização. */
    suspend fun categoryIds(category: String, perPage: Int = 250): Outcome<Set<String>> =
        getJson("/coins/markets?vs_currency=usd&category=${enc(category)}&order=market_cap_desc&per_page=${perPage.coerceIn(1, 250)}&page=1").parse { el ->
            (el as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("id").str() }?.toSet() ?: emptySet()
        }

    internal fun parseMarketsForTest(el: JsonElement) = parseMarkets(el)

    override suspend fun markets(currency: Currency, page: Int, perPage: Int): Outcome<List<MarketCoin>> =
        getJson("/coins/markets?vs_currency=${currency.code}&order=market_cap_desc&per_page=${perPage.coerceIn(1, 250)}&page=$page&sparkline=true").parse { parseMarkets(it) }

    override suspend fun global(currency: Currency): Outcome<GlobalData> = getJson("/global").parse { el ->
        val d = (el as? JsonObject)?.get("data") as? JsonObject ?: throw IllegalStateException("CoinGecko /global sem dados")
        GlobalData(
            d["total_market_cap"]?.jsonObject?.get(currency.code).dec()?.toPlainString(),
            d["total_volume"]?.jsonObject?.get(currency.code).dec()?.toPlainString(),
            d["market_cap_percentage"]?.jsonObject?.get("btc").dec()?.toPlainString(),
            d["market_cap_percentage"]?.jsonObject?.get("eth").dec()?.toPlainString(),
            d["active_cryptocurrencies"]?.jsonPrimitive?.intOrNull,
            d["market_cap_change_percentage_24h_usd"].dec()?.toPlainString(),
            currency.name,
        )
    }
}
