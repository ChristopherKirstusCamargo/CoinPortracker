package com.coinportracker.market

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Câmbio USD/BRL via Frankfurter (api.frankfurter.dev/v1): taxas de referência do Banco Central Europeu,
 * gratuito e sem chave. Publica apenas em dias úteis (~16h CET); fins de semana usam a última taxa (identificado).
 */
class FrankfurterFxProvider(private val http: HttpClient) : FxProvider {
    override val id = "frankfurter"
    private val base get() = com.coinportracker.dev.DevSources.priceUrl("frankfurter", "https://api.frankfurter.dev/v1")
    private val policy = RatePolicy(minIntervalMs = 1_000)

    override suspend fun latestUsdBrl(): Outcome<Pair<Long, BigDecimal>> = when (val r = http.get(id, "$base/latest?from=USD&to=BRL", policy)) {
        is Outcome.Err -> r
        is Outcome.Ok -> runCatching {
            val o = lenientJson.parseToJsonElement(r.value).jsonObject
            Outcome.Ok(LocalDate.parse(o["date"].str()!!).toEpochDay() to o["rates"]!!.jsonObject["BRL"].dec()!!)
        }.getOrElse { Outcome.Err("Resposta de câmbio inválida", it) }
    }

    override suspend fun seriesUsdBrl(fromEpochDay: Long, toEpochDay: Long): Outcome<List<Pair<Long, BigDecimal>>> {
        val from = LocalDate.ofEpochDay(fromEpochDay); val to = LocalDate.ofEpochDay(toEpochDay)
        return when (val r = http.get(id, "$base/$from..$to?from=USD&to=BRL", policy)) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching {
                val rates = lenientJson.parseToJsonElement(r.value).jsonObject["rates"]!!.jsonObject
                Outcome.Ok(rates.mapNotNull { (d, v) -> v.jsonObject["BRL"].dec()?.let { LocalDate.parse(d).toEpochDay() to it } }.sortedBy { it.first })
            }.getOrElse { Outcome.Err("Resposta de câmbio inválida", it) }
        }
    }
}
