package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

internal fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonElement?.o(): JsonObject? = this as? JsonObject
internal fun JsonElement?.a(): JsonArray? = this as? JsonArray

fun scaled(raw: BigInteger, decimals: Int): BigDecimal = BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

/**
 * Leitura automática de carteiras EVM (somente leitura, sem chave):
 * 1) saldo nativo por eth_getBalance (RPC público com fallback);
 * 2) tokens: API Blockscout (descobre todos os tokens, com preço e ícone) e, se indisponível,
 *    Multicall3 balanceOf sobre a lista pública de tokens da rede (milhares de tokens em poucas chamadas);
 * 3) histórico: Blockscout (transações + transferências de tokens) quando disponível.
 */
class EvmScanner(
    private val http: HttpClient,
    private val rpc: JsonRpc,
    private val lists: TokenListSource,
    private val batchSize: Int = 400,
) {
    private val bsPolicy = RatePolicy(minIntervalMs = 250, maxRetries = 1, baseBackoffMs = 1_000, maxBackoffMs = 5_000)

    suspend fun customTokens(net: Network, address: String) = com.coinportracker.dev.CustomHoldings.tokens(rpc, net, address)

    suspend fun scan(net: Network, address: String, withHistory: Boolean): Outcome<ScanResult> = coroutineScope {
        val addr = address.trim()
        val warnings = ArrayList<String>()
        val sources = ArrayList<String>()
        val nativeDef = async { rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "eth_getBalance", """["$addr","latest"]""") }
        val bsTokensDef = async { com.coinportracker.dev.DevSources.indexer(net)?.let { blockscoutTokens(net, it, addr) } }
        val native = nativeDef.await()
        if (native is Outcome.Err) {
            bsTokensDef.cancel()
            return@coroutineScope Outcome.Err("${net.label}: ${native.message}", native.cause, true)
        }
        sources += "RPC público"
        val holdings = ArrayList<TokenHolding>()
        val wei = Abi.hexToBig((native as Outcome.Ok).value.s())
        if (wei.signum() > 0) holdings += TokenHolding(net, "native", net.nativeSymbol, net.nativeName, net.nativeDecimals, scaled(wei, net.nativeDecimals),
            nativeIcon(net), net.nativePriceKey, HoldingKind.NATIVE, verified = true, coingeckoId = net.nativeCoingeckoId)

        val bs = bsTokensDef.await()
        if (bs is Outcome.Ok) {
            holdings += bs.value.filterNot { it.tokenKey == POL_NATIVE_TOKEN }; sources += "Blockscout"
        } else {
            when (val mc = multicallTokens(net, addr)) {
                is Outcome.Ok -> { holdings += mc.value.filterNot { it.tokenKey == POL_NATIVE_TOKEN }; sources += "Multicall (lista pública de tokens)" }
                is Outcome.Err -> warnings += "${net.label}: tokens não lidos agora (${mc.message})."
            }
        }
        val activity = if (withHistory) {
            val idx = com.coinportracker.dev.DevSources.indexer(net)
            if (idx == null) emptyList() else when (val h = blockscoutHistory(net, idx, addr)) {
                is Outcome.Ok -> h.value
                is Outcome.Err -> { warnings += "${net.label}: histórico indisponível agora."; emptyList() }
            }
        } else emptyList()
        Outcome.Ok(ScanResult(holdings, activity, warnings, sources))
    }

    private fun nativeIcon(net: Network) = when (net.nativeSymbol) {
        "ETH" -> Network.ETHEREUM.iconUrl; "POL" -> Network.POLYGON.iconUrl; "BNB" -> Network.BSC.iconUrl; "AVAX" -> Network.AVALANCHE.iconUrl; else -> net.iconUrl
    }

    /** Multicall3.aggregate3 com balanceOf(owner) para cada token conhecido da rede, em lotes. */
    suspend fun multicallTokens(net: Network, owner: String): Outcome<List<TokenHolding>> {
        val tokens = lists.tokens(net)
        if (tokens.isEmpty()) return Outcome.Err("lista de tokens vazia")
        val call = Abi.balanceOfCall(owner)
        val out = ArrayList<TokenHolding>()
        var failures = 0
        for (chunk in tokens.chunked(batchSize)) {
            val data = withContext(Dispatchers.Default) { Abi.encodeAggregate3(chunk.map { it.address }, call) }
            when (val r = rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "eth_call", """[{"to":"${Abi.MULTICALL3}","data":"$data"},"latest"]""")) {
                is Outcome.Err -> { failures++; if (failures >= 2) return Outcome.Err(r.message) }
                is Outcome.Ok -> {
                    val values = withContext(Dispatchers.Default) { runCatching { Abi.decodeAggregate3Uints(r.value.s() ?: "0x") }.getOrNull() } ?: continue
                    chunk.zip(values).forEach { (t, v) ->
                        if (v != null && v.signum() > 0 && v.bitLength() < 200) {
                            out += TokenHolding(net, t.address, t.symbol.take(16), t.name.take(48), t.decimals, scaled(v, t.decimals), t.logo,
                                "${net.llamaChain}:${t.address}", if (StakingTokens.isLiquidStaking(t.symbol)) HoldingKind.LIQUID_STAKING else HoldingKind.TOKEN, verified = true)
                        }
                    }
                }
            }
        }
        return Outcome.Ok(out)
    }

    private suspend fun blockscoutGet(net: Network, base: String, path: String): Outcome<JsonElement> {
        val host = base.substringAfter("://")
        return when (val r = http.get("bs:$host", base + path, bsPolicy)) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val t = r.value.trimStart()
                if (!(t.startsWith("[") || t.startsWith("{"))) Outcome.Err("${net.label}: indexador indisponível")
                else runCatching { Outcome.Ok(chainJson.parseToJsonElement(t)) }.getOrElse { Outcome.Err("${net.label}: resposta inválida", it) }
            }
        }
    }

    suspend fun blockscoutTokens(net: Network, base: String, addr: String): Outcome<List<TokenHolding>> =
        blockscoutGet(net, base, "/api/v2/addresses/$addr/token-balances").map { parseBlockscoutTokens(net, it) }

    suspend fun blockscoutHistory(net: Network, base: String, addr: String): Outcome<List<ChainActivity>> {
        val txs = blockscoutGet(net, base, "/api/v2/addresses/$addr/transactions")
        if (txs is Outcome.Err) return txs
        val tts = blockscoutGet(net, base, "/api/v2/addresses/$addr/token-transfers?type=ERC-20")
        val list = parseBlockscoutTxs(net, addr, (txs as Outcome.Ok).value) + ((tts as? Outcome.Ok)?.value?.let { parseBlockscoutTransfers(net, addr, it) } ?: emptyList())
        return Outcome.Ok(list)
    }

    /** Histórico paginado (v3). */
    suspend fun history(net: Network, addr: String, st: HistState): Outcome<HistPage> {
        val base = com.coinportracker.dev.DevSources.indexer(net) ?: return Outcome.Err("${net.label}: sem indexador de histórico")
        return historyPaged(net, base, addr.trim(), st) { path -> blockscoutGet(net, base, path) }
    }

    companion object {
        /** Na Polygon, 0x…1010 é o próprio POL (ex-MATIC) nativo exposto como ERC-20: contaria em dobro com o saldo nativo do RPC. */
        const val POL_NATIVE_TOKEN = "0x0000000000000000000000000000000000001010"
        /** Transferências internas: só ETH/nativo RECEBIDO de contratos (ex.: venda de token por ETH, saque de protocolo). */
        fun parseBlockscoutInternal(net: Network, me: String, el: JsonElement): List<ChainActivity> = el.o()?.get("items").a().orEmpty().mapNotNull { it.o() }.mapNotNull { t ->
            val from = t["from"].o()?.get("hash").s()
            val to = t["to"].o()?.get("hash").s()
            val value = t["value"].s()?.toBigIntegerOrNull() ?: return@mapNotNull null
            if (value.signum() <= 0 || !to.equals(me, true) || from.equals(me, true)) return@mapNotNull null
            if (t["success"].s() == "false") return@mapNotNull null
            val hash = t["transaction_hash"].s() ?: return@mapNotNull null
            ChainActivity(net, "$hash#int${t["index"].s() ?: ""}", ts(t["timestamp"].s()), Direction.IN, net.nativeSymbol, scaled(value, net.nativeDecimals), "native",
                from, null, false, "Interna", null, net.nativePriceKey)
        }

        fun parseBlockscoutTokens(net: Network, el: JsonElement): List<TokenHolding> = el.a().orEmpty().mapNotNull { item ->
            val io = item.o() ?: return@mapNotNull null
            val tok = io["token"].o() ?: return@mapNotNull null
            if (tok["type"].s() != "ERC-20") return@mapNotNull null
            val decimals = tok["decimals"].s()?.toIntOrNull() ?: return@mapNotNull null
            val raw = io["value"].s()?.toBigIntegerOrNull() ?: return@mapNotNull null
            if (raw.signum() <= 0 || raw.bitLength() > 200) return@mapNotNull null
            val addr = tok["address_hash"].s()?.lowercase() ?: return@mapNotNull null
            val sym = tok["symbol"].s() ?: "?"
            val rep = tok["reputation"].s()
            TokenHolding(net, addr, sym.take(16), (tok["name"].s() ?: sym).take(48), decimals, scaled(raw, decimals), tok["icon_url"].s(),
                "${net.llamaChain}:$addr", if (StakingTokens.isLiquidStaking(sym)) HoldingKind.LIQUID_STAKING else HoldingKind.TOKEN,
                verified = tok["icon_url"].s() != null && tok["exchange_rate"].s() != null, flaggedScam = rep == "scam",
                priceUsdHint = tok["exchange_rate"].s()?.toDoubleOrNull())
        }

        private fun ts(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun parseBlockscoutTxs(net: Network, me: String, el: JsonElement): List<ChainActivity> = el.o()?.get("items").a().orEmpty().mapNotNull { it.o() }.map { t ->
            val from = t["from"].o()?.get("hash").s()
            val to = t["to"].o()?.get("hash").s()
            val value = t["value"].s()?.toBigIntegerOrNull() ?: BigInteger.ZERO
            val outgoing = from.equals(me, true)
            val incoming = to.equals(me, true)
            val dir = when {
                outgoing && incoming -> Direction.SELF
                value.signum() == 0 -> Direction.CONTRACT
                outgoing -> Direction.OUT
                else -> Direction.IN
            }
            val fee = t["fee"].o()?.get("value").s()?.toBigIntegerOrNull()
            ChainActivity(net, t["hash"].s() ?: "", ts(t["timestamp"].s()), dir, net.nativeSymbol,
                if (value.signum() > 0) scaled(value, net.nativeDecimals) else null, "native",
                if (outgoing) to else from, if (outgoing && fee != null) scaled(fee, net.nativeDecimals) else null,
                t["status"].s() == "error" || t["result"].s().let { it != null && it != "success" && it != "pending" },
                t["method"].s()?.takeIf { !it.startsWith("0x") }, null, net.nativePriceKey)
        }.filter { it.hash.isNotEmpty() }

        fun parseBlockscoutTransfers(net: Network, me: String, el: JsonElement): List<ChainActivity> = el.o()?.get("items").a().orEmpty().mapNotNull { it.o() }.mapNotNull { t ->
            val tok = t["token"].o() ?: return@mapNotNull null
            val from = t["from"].o()?.get("hash").s()
            val to = t["to"].o()?.get("hash").s()
            val scam = t["from"].o()?.get("is_scam").s() == "true" || tok["reputation"].s() == "scam"
            if (scam) return@mapNotNull null
            val total = t["total"].o()
            val dec = total?.get("decimals").s()?.toIntOrNull() ?: tok["decimals"].s()?.toIntOrNull() ?: 18
            val raw = total?.get("value").s()?.toBigIntegerOrNull()
            val outgoing = from.equals(me, true)
            val addr = tok["address_hash"].s()?.lowercase()
            ChainActivity(net, (t["transaction_hash"].s() ?: t["tx_hash"].s() ?: return@mapNotNull null) + "#" + (t["log_index"].s() ?: ""),
                ts(t["timestamp"].s()), if (outgoing && to.equals(me, true)) Direction.SELF else if (outgoing) Direction.OUT else Direction.IN,
                (tok["symbol"].s() ?: "?").take(16), raw?.let { scaled(it, dec) }, addr, if (outgoing) to else from, null, false,
                t["method"].s()?.takeIf { !it.startsWith("0x") }, tok["icon_url"].s(), addr?.let { "${net.llamaChain}:$it" })
        }
    }
}
