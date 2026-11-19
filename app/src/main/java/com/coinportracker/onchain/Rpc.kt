package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val chainJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** JSON-RPC com fallback entre vários endpoints públicos (o primeiro que responder vence). */
open class JsonRpc(private val http: HttpClient, private val policy: RatePolicy = RatePolicy(minIntervalMs = 120, maxRetries = 2, baseBackoffMs = 1_000, maxBackoffMs = 8_000)) {

    open suspend fun call(urls: List<String>, method: String, params: String): Outcome<JsonElement> {
        var last: Outcome.Err = Outcome.Err("Nenhum RPC configurado")
        for (url in urls) {
            val host = url.substringAfter("://").substringBefore('/')
            val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
            when (val r = http.postJson("rpc:$host", url, body, policy)) {
                is Outcome.Err -> { com.coinportracker.dev.DevHealth.fail(url, r.message); last = Outcome.Err("$host: ${r.message}", r.cause, r.retryable) }
                is Outcome.Ok -> {
                    val o = runCatching { chainJson.parseToJsonElement(r.value).jsonObject }.getOrNull()
                    if (o == null) { last = Outcome.Err("$host: resposta inválida"); continue }
                    val err = o["error"]
                    if (err != null && err !is JsonNull) {
                        val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                        last = Outcome.Err("$host: $msg"); continue
                    }
                    val res = o["result"] ?: JsonNull
                    com.coinportracker.dev.DevHealth.ok(url)
                    return Outcome.Ok(res)
                }
            }
        }
        return last
    }

    /**
     * getTransaction confiável: transações antigas só existem no RPC oficial (os alternativos guardam poucos dias).
     * Tenta o primeiro endpoint com pausas (limite de taxa) e só então os demais; resultado nulo conta como falha.
     */
    open suspend fun getTransaction(urls: List<String>, sig: String): JsonElement? {
        val params = """["$sig",{"encoding":"jsonParsed","maxSupportedTransactionVersion":0}]"""
        repeat(3) { attempt ->
            val r = call(listOf(urls.first()), "getTransaction", params)
            if (r is Outcome.Ok && r.value !is JsonNull) return r.value
            kotlinx.coroutines.delay(700L * (attempt + 1))
        }
        for (u in urls.drop(1)) { val r = call(listOf(u), "getTransaction", params); if (r is Outcome.Ok && r.value !is JsonNull) return r.value }
        return null
    }
}
