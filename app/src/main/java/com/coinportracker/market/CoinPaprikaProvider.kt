package com.coinportracker.market

import com.coinportracker.core.Outcome
import com.coinportracker.domain.Currency
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * CoinPaprika API gratuita (api.coinpaprika.com/v1). Verificado em 12/11/2026:
 * 20.000 chamadas/mês por IP, sem chave; 10 req/s; 2.000 ativos; 1 ano de preços diários;
 * uso pessoal; exige o crédito "Powered by CoinPaprika". Histórico apenas em USD.
 */
class CoinPaprikaProvider(private val http: HttpClient) : MarketDataProvider {
    override val id = "coinpaprika"
    override val displayName = "CoinPaprika"
    override val attribution = "Powered by CoinPaprika"
    override val limitsDescription = "20.000 chamadas/mês por IP, 10 req/s, 1 ano de histórico diário (USD), uso pessoal."
    override val maxHistoryDays = 365
    private val base get() = com.coinportracker.dev.DevSources.priceUrl("paprika", "https://api.coinpaprika.com/v1")
    private val policy = RatePolicy(minIntervalMs = 300)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private inline fun <T> Outcome<JsonElement>.parse(f: (JsonElement) -> T): Outcome<T> = when (this) {
        is Outcome.Err -> this
        is Outcome.Ok -> com.coinportracker.core.catchingNonCancel { f(value) }.fold({ Outcome.Ok(it) }, { Outcome.Err("Resposta inesperada do CoinPaprika", it) })
    }

    override fun idFor(coingeckoId: String?, paprikaId: String?) = paprikaId

    private suspend fun getJson(path: String): Outcome<JsonElement> = when (val r = http.get(id, base + path, policy)) {
        is Outcome.Ok -> runCatching { Outcome.Ok(lenientJson.parseToJsonElement(r.value)) }.getOrElse { Outcome.Err("Resposta inválida do CoinPaprika", it) }
        is Outcome.Err -> r
    }

    override suspend fun quotes(ids: List<String>, currencies: List<Currency>): Outcome<List<MarketQuote>> {
        val out = ArrayList<MarketQuote>()
        val q = currencies.joinToString(",") { it.name }
        for (pid in ids.distinct()) {
            val r = getJson("/tickers/${enc(pid)}?quotes=$q")
            if (r is Outcome.Err) return r
            val o = (r as Outcome.Ok).value.jsonObject
            val updated = o["last_updated"].str()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val quotes = o["quotes"]?.jsonObject ?: continue
            for (c in currencies) {
                val qo = quotes[c.name]?.jsonObject ?: continue
                val price = qo["price"].dec() ?: continue
                out += MarketQuote(pid, c, price, qo["percent_change_24h"].dec(), qo["market_cap"].dec(), qo["volume_24h"].dec(),
                    o["circulating_supply"].dec(), o["total_supply"].dec(), o["max_supply"].dec()?.takeIf { it.signum() > 0 },
                    o["rank"]?.jsonPrimitive?.intOrNull, updated)
            }
        }
        return Outcome.Ok(out)
    }

    override suspend fun history(id: String, currency: Currency, days: Int): Outcome<List<PricePoint>> {
        if (currency != Currency.USD) return Outcome.Err("CoinPaprika (plano gratuito) fornece histórico apenas em USD; conversão para BRL é feita com o câmbio diário identificado.")
        val start = LocalDate.now(ZoneOffset.UTC).minusDays(days.coerceIn(1, maxHistoryDays - 1).toLong())
        return getJson("/tickers/${enc(id)}/historical?start=$start&interval=1d").parse { el ->
            el.jsonArray.mapNotNull {
                val o = it.jsonObject
                val ts = o["timestamp"].str()?.let { s -> runCatching { Instant.parse(s) }.getOrNull() } ?: return@mapNotNull null
                val p = o["price"].dec() ?: return@mapNotNull null
                PricePoint(ts.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(), p)
            }
        }
    }

    override suspend fun search(query: String): Outcome<List<AssetSearchResult>> =
        getJson("/search/?q=${enc(query)}&c=currencies&limit=20").parse { el ->
            el.jsonObject["currencies"]?.jsonArray?.map {
                val o = it.jsonObject
                AssetSearchResult(o["id"].str()!!, o["symbol"].str().orEmpty().uppercase(), o["name"].str().orEmpty(), o["rank"]?.jsonPrimitive?.intOrNull)
            } ?: emptyList()
        }

    override suspend fun markets(currency: Currency, page: Int, perPage: Int): Outcome<List<MarketCoin>> =
        Outcome.Err("Ranking de mercado não disponível via CoinPaprika neste app (o endpoint retorna todos os ativos de uma vez e consumiria a cota mensal). Use o CoinGecko para o ranking.")

    override suspend fun global(currency: Currency): Outcome<GlobalData> {
        if (currency != Currency.USD) return Outcome.Err("Dados globais do CoinPaprika disponíveis apenas em USD.")
        return getJson("/global").parse { el ->
            val o = el.jsonObject
            GlobalData(o["market_cap_usd"].dec()?.toPlainString(), o["volume_24h_usd"].dec()?.toPlainString(), o["bitcoin_dominance_percentage"].dec()?.toPlainString(),
                null, o["cryptocurrencies_number"]?.jsonPrimitive?.intOrNull, o["market_cap_change_24h"].dec()?.toPlainString(), "USD")
        }
    }
}
