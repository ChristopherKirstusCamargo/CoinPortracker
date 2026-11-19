package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.JsonElement
import java.math.BigInteger

/** Bitcoin por API Esplora pública (mempool.space, fallback blockstream.info). Endereços individuais (legacy, SegWit, Taproot). */
class BitcoinScanner(private val http: HttpClient) {
    private val policy = RatePolicy(minIntervalMs = 400, maxRetries = 2, baseBackoffMs = 1_000, maxBackoffMs = 8_000)
    /** v3.2: também Litecoin (mesma API Esplora em litecoinspace.org). */
    suspend fun scan(address: String, withHistory: Boolean, net: Network = Network.BITCOIN): Outcome<ScanResult> {
        val addr = address.trim()
        var last: Outcome.Err = Outcome.Err("${net.label}: sem provedor")
        for (base in com.coinportracker.dev.DevSources.rpcs(net)) {
            val host = base.substringAfter("://").substringBefore('/')
            val info = http.get("btc:$host", "$base/address/$addr", policy)
            if (info is Outcome.Err) { last = Outcome.Err("${net.label}: ${info.message}", info.cause, true); continue }
            val sats = runCatching { balanceSats(chainJson.parseToJsonElement((info as Outcome.Ok).value)) }.getOrNull()
            if (sats == null) { last = Outcome.Err("${net.label}: resposta inválida de $host"); continue }
            val holdings = if (sats.signum() > 0) listOf(TokenHolding(net, "native", net.nativeSymbol, net.nativeName, 8, scaled(sats, 8), net.iconUrl, net.nativePriceKey, HoldingKind.NATIVE, true, coingeckoId = net.nativeCoingeckoId)) else emptyList()
            val warnings = ArrayList<String>()
            val activity = if (withHistory) {
                when (val t = http.get("btc:$host", "$base/address/$addr/txs", policy)) {
                    is Outcome.Ok -> runCatching { parseTxs(chainJson.parseToJsonElement(t.value), addr, net) }.getOrElse { emptyList() }
                    is Outcome.Err -> { warnings += "${net.label}: histórico indisponível agora."; emptyList() }
                }
            } else emptyList()
            return Outcome.Ok(ScanResult(holdings, activity, warnings, listOf(host)))
        }
        return last
    }

    /** Histórico paginado (v3). */
    suspend fun history(addr: String, st: HistState, net: Network = Network.BITCOIN): Outcome<HistPage> {
        var last: Outcome.Err = Outcome.Err("${net.label}: sem provedor")
        for (base in com.coinportracker.dev.DevSources.rpcs(net)) {
            val host = base.substringAfter("://").substringBefore('/')
            when (val r = historyPaged(base, addr.trim(), st, net) { url -> http.get("btc:$host", url, policy) }) {
                is Outcome.Ok -> return r
                is Outcome.Err -> last = r
            }
        }
        return last
    }

    companion object {
        fun balanceSats(el: JsonElement): BigInteger {
            fun part(k: String): BigInteger { val s = el.o()!![k].o()!!; return s["funded_txo_sum"].s()!!.toBigInteger() - s["spent_txo_sum"].s()!!.toBigInteger() }
            return part("chain_stats") + part("mempool_stats")
        }

        fun parseTxs(el: JsonElement, addr: String, net: Network = Network.BITCOIN): List<ChainActivity> = el.a().orEmpty().mapNotNull { it.o() }.map { t ->
            val received = t["vout"].a().orEmpty().mapNotNull { it.o() }.filter { it["scriptpubkey_address"].s() == addr }.sumOf { it["value"].s()?.toBigInteger() ?: BigInteger.ZERO }
            val sent = t["vin"].a().orEmpty().mapNotNull { it.o()?.get("prevout").o() }.filter { it["scriptpubkey_address"].s() == addr }.sumOf { it["value"].s()?.toBigInteger() ?: BigInteger.ZERO }
            val delta = received - sent
            val st = t["status"].o()
            val fee = t["fee"].s()?.toBigInteger()
            ChainActivity(net, t["txid"].s() ?: "", st?.get("block_time").s()?.toLongOrNull()?.times(1000),
                when { delta.signum() > 0 -> Direction.IN; delta.signum() < 0 -> Direction.OUT; else -> Direction.SELF },
                net.nativeSymbol, scaled(delta.abs(), 8), "native", null, if (sent.signum() > 0 && fee != null) scaled(fee, 8) else null, false,
                if (st?.get("confirmed").s() == "true") null else "Pendente", net.iconUrl, net.nativePriceKey)
        }
    }
}
