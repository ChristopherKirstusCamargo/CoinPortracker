package com.coinportracker.dev

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy

data class ProbeResult(val ok: Boolean, val latencyMs: Long, val message: String)

/** "Testar": mede a latência e confirma se o endpoint responde. Sem gravar nada no portfólio. */
class EndpointProbe(private val http: HttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val policy = RatePolicy(minIntervalMs = 0, maxRetries = 0, baseBackoffMs = 0, maxBackoffMs = 1_000)

    suspend fun test(url: String, kind: String, format: String? = null): ProbeResult {
        val t0 = clock()
        val r = when {
            kind == EndpointKind.RPC.name || format == "rpc" -> rpc(url)
            kind == EndpointKind.INDEXER.name -> indexer(url)
            format == "kraken" -> get(url.trimEnd('/') + "/0/public/Ticker?pair=XBTUSD", "kraken")
            format == "defillama" -> get(url.trimEnd('/') + "/prices/current/coingecko:bitcoin", "llama")
            format == "coingecko" -> get(url.trimEnd('/') + "/ping", "coingecko")
            format == "paprika" -> get(url.trimEnd('/') + "/global", "paprika")
            format == "frankfurter" -> get(url.trimEnd('/') + "/latest?from=USD&to=BRL", "frankfurter")
            else -> get(url, "probe")
        }
        val ms = (clock() - t0).coerceAtLeast(0)
        return when (r) {
            is Outcome.Ok -> ProbeResult(true, ms, "OK · ${ms} ms")
            is Outcome.Err -> ProbeResult(false, ms, r.message.take(140) + " · ${ms} ms")
        }
    }

    private suspend fun rpc(url: String): Outcome<String> {
        val body = """{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}"""
        val evm = http.postJson("probe-rpc", url, body, policy)
        if (evm is Outcome.Ok && evm.value.contains("result")) return evm
        // Solana / outros: getHealth ou getBlockHeight
        val sol = http.postJson("probe-rpc", url, """{"jsonrpc":"2.0","id":1,"method":"getHealth","params":[]}""", policy)
        if (sol is Outcome.Ok) return sol
        return evm
    }

    private suspend fun indexer(url: String): Outcome<String> {
        val base = url.trimEnd('/')
        val a = http.get("probe-idx", "$base/api/v2/status", policy)
        if (a is Outcome.Ok) return a
        return http.get("probe-idx", "$base/api", policy)
    }

    private suspend fun get(url: String, key: String) = http.get(key, url, policy)
}
