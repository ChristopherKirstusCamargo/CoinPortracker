package com.coinportracker.blockchain

import com.coinportracker.core.Outcome
import com.coinportracker.market.dec
import com.coinportracker.market.lenientJson
import com.coinportracker.market.str
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.BigInteger

data class ObservedBalance(val assetKey: String, val symbol: String, val quantity: BigDecimal, val kind: String, val coingeckoHint: String? = null)
data class ObservedTx(val hash: String, val timestamp: Long?, val direction: String, val amount: BigDecimal?, val symbol: String, val fee: BigDecimal? = null, val status: String? = null)
data class ObservedStake(val account: String, val lamports: BigDecimal, val delegatedStake: BigDecimal?, val voter: String?, val activationEpoch: String?)

data class SyncResult(
    val balances: List<ObservedBalance>,
    val txs: List<ObservedTx>,
    val stakes: List<ObservedStake> = emptyList(),
    val source: String,
    val limitations: List<String> = emptyList(),
)

private val SAT = BigDecimal("100000000")
private val LAMPORTS = BigDecimal("1000000000")

/** Bitcoin via API Esplora pública (mempool.space; fallback blockstream.info). Somente leitura. */
class BitcoinEsploraProvider(private val http: HttpClient, private val bases: List<String> = listOf("https://mempool.space/api", "https://blockstream.info/api")) {
    private val policy = RatePolicy(minIntervalMs = 1_000)

    suspend fun sync(address: String): Outcome<SyncResult> {
        var lastErr: Outcome.Err? = null
        for (base in bases) {
            val r = syncWith(base, address)
            if (r is Outcome.Ok) return r
            lastErr = r as Outcome.Err
        }
        return lastErr ?: Outcome.Err("Sem provedores Bitcoin configurados")
    }

    private suspend fun syncWith(base: String, address: String): Outcome<SyncResult> {
        val host = base.substringAfter("://").substringBefore('/')
        val info = http.get("btc:$host", "$base/address/$address", policy)
        if (info is Outcome.Err) return info
        val o = runCatching { lenientJson.parseToJsonElement((info as Outcome.Ok).value).jsonObject }.getOrElse { return Outcome.Err("Resposta inválida de $host", it) }
        fun stats(k: String) = o[k]!!.jsonObject.let { s -> s["funded_txo_sum"].dec()!!.subtract(s["spent_txo_sum"].dec()!!) }
        val confirmed = stats("chain_stats"); val pending = stats("mempool_stats")
        val balances = listOf(ObservedBalance("BTC", "BTC", confirmed.add(pending).divide(SAT), "NATIVE", "bitcoin"))
        val txsRes = http.get("btc:$host", "$base/address/$address/txs", policy)
        val txs = (txsRes as? Outcome.Ok)?.value?.let { body ->
            runCatching {
                lenientJson.parseToJsonElement(body).jsonArray.map { t ->
                    val to = t.jsonObject
                    val received = to["vout"]!!.jsonArray.filter { it.jsonObject["scriptpubkey_address"].str() == address }.sumOf { it.jsonObject["value"].dec() ?: BigDecimal.ZERO }
                    val sent = to["vin"]!!.jsonArray.mapNotNull { (it.jsonObject["prevout"] as? JsonObject) }.filter { it["scriptpubkey_address"].str() == address }.sumOf { it["value"].dec() ?: BigDecimal.ZERO }
                    val net = received.subtract(sent)
                    val status = to["status"]?.jsonObject
                    ObservedTx(
                        hash = to["txid"].str()!!,
                        timestamp = status?.get("block_time")?.jsonPrimitive?.longOrNull?.times(1000),
                        direction = when { net.signum() > 0 -> "IN"; net.signum() < 0 -> "OUT"; else -> "SELF" },
                        amount = net.abs().divide(SAT), symbol = "BTC", fee = to["fee"].dec()?.divide(SAT),
                        status = if (status?.get("confirmed")?.jsonPrimitive?.content == "true") "confirmada" else "pendente",
                    )
                }
            }.getOrNull()
        } ?: emptyList()
        return Outcome.Ok(SyncResult(balances, txs, source = host, limitations = listOf(
            "Histórico limitado às 50 transações mais recentes retornadas pela API Esplora.",
            "Endereços individuais apenas: xpub/descritores não são suportados (exigiriam derivação local de endereços)."
        )))
    }
}

/** Solana via JSON-RPC público (api.mainnet-beta.solana.com). Somente leitura. */
class SolanaRpcProvider(private val http: HttpClient, private val rpcUrl: String = "https://api.mainnet-beta.solana.com") {
    private val policy = RatePolicy(minIntervalMs = 400)
    private val programs = listOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")

    private suspend fun rpc(method: String, params: String): Outcome<JsonElement> {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        return when (val r = http.postJson("sol", rpcUrl, body, policy)) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val o = runCatching { lenientJson.parseToJsonElement(r.value).jsonObject }.getOrElse { return Outcome.Err("Resposta RPC inválida", it) }
                o["error"]?.takeIf { it !is JsonNull }?.let { return Outcome.Err("RPC Solana: ${it.jsonObject["message"].str()}") }
                Outcome.Ok(o["result"]!!)
            }
        }
    }

    suspend fun sync(address: String): Outcome<SyncResult> {
        val bal = rpc("getBalance", """["$address"]""")
        if (bal is Outcome.Err) return bal
        val lamports = (bal as Outcome.Ok).value.jsonObject["value"].dec() ?: BigDecimal.ZERO
        val balances = arrayListOf(ObservedBalance("SOL", "SOL", lamports.divide(LAMPORTS), "NATIVE", "solana"))
        val limitations = arrayListOf<String>()
        for (pid in programs) {
            when (val t = rpc("getTokenAccountsByOwner", """["$address",{"programId":"$pid"},{"encoding":"jsonParsed"}]""")) {
                is Outcome.Ok -> t.value.jsonObject["value"]?.jsonArray?.forEach { acc ->
                    val info = acc.jsonObject["account"]!!.jsonObject["data"]!!.jsonObject["parsed"]!!.jsonObject["info"]!!.jsonObject
                    val mint = info["mint"].str()!!
                    val amt = info["tokenAmount"]!!.jsonObject["uiAmountString"].str()?.let { BigDecimal(it) } ?: BigDecimal.ZERO
                    if (amt.signum() > 0) balances += ObservedBalance("spl:$mint", mint.take(4) + "…" + mint.takeLast(4), amt, "SPL")
                }
                is Outcome.Err -> limitations += "Tokens SPL (${pid.take(6)}…) indisponíveis: ${t.message}"
            }
        }
        val stakes = ArrayList<ObservedStake>()
        when (val s = rpc("getProgramAccounts", """["Stake11111111111111111111111111111111111111",{"encoding":"jsonParsed","filters":[{"memcmp":{"offset":44,"bytes":"$address"}}]}]""")) {
            is Outcome.Ok -> s.value.jsonArray.forEach { a ->
                val ao = a.jsonObject
                val acct = ao["account"]!!.jsonObject
                val info = acct["data"]?.jsonObject?.get("parsed")?.jsonObject?.get("info")?.jsonObject
                val del = info?.get("stake")?.jsonObject?.get("delegation")?.jsonObject
                stakes += ObservedStake(ao["pubkey"].str()!!, acct["lamports"].dec()!!.divide(LAMPORTS), del?.get("stake").dec()?.divide(LAMPORTS), del?.get("voter").str(), del?.get("activationEpoch").str())
            }
            is Outcome.Err -> limitations += "Contas de stake não consultadas (o RPC público pode recusar getProgramAccounts): ${s.message}"
        }
        stakes.forEach { balances += ObservedBalance("stake:${it.account}", "SOL (stake)", it.lamports, "STAKE", "solana") }
        val txs = when (val h = rpc("getSignaturesForAddress", """["$address",{"limit":25}]""")) {
            is Outcome.Ok -> h.value.jsonArray.map { e ->
                val o = e.jsonObject
                ObservedTx(o["signature"].str()!!, o["blockTime"]?.jsonPrimitive?.longOrNull?.times(1000), "?", null, "SOL",
                    status = if (o["err"] == null || o["err"] is JsonNull) "sucesso" else "falhou")
            }
            is Outcome.Err -> { limitations += "Histórico indisponível: ${h.message}"; emptyList() }
        }
        limitations += "Histórico Solana mostra apenas assinaturas recentes (25), sem valores: obter valores exigiria uma chamada por transação."
        limitations += "Tokens SPL são identificados pelo mint; vincule o mint a um ativo para valorizá-lo."
        return Outcome.Ok(SyncResult(balances, txs, stakes, "Solana RPC público", limitations))
    }
}

/** Redes EVM via JSON-RPC público configurável. Somente leitura (eth_getBalance / eth_call balanceOf). */
class EvmRpcProvider(private val http: HttpClient) {
    private val policy = RatePolicy(minIntervalMs = 300)

    private suspend fun call(rpcUrl: String, method: String, params: String): Outcome<String> {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        val host = rpcUrl.substringAfter("://").substringBefore('/')
        return when (val r = http.postJson("evm:$host", rpcUrl, body, policy)) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val o = runCatching { lenientJson.parseToJsonElement(r.value).jsonObject }.getOrElse { return Outcome.Err("Resposta RPC inválida", it) }
                o["error"]?.takeIf { it !is JsonNull }?.let { return Outcome.Err("RPC $host: ${it.jsonObject["message"].str()}") }
                o["result"].str()?.let { Outcome.Ok(it) } ?: Outcome.Err("RPC $host sem resultado")
            }
        }
    }

    fun hexToBig(hex: String): BigInteger = if (hex == "0x" || hex.isBlank()) BigInteger.ZERO else BigInteger(hex.removePrefix("0x"), 16)
    fun scale(raw: BigInteger, decimals: Int): BigDecimal = BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros()

    data class Token(val contract: String, val symbol: String, val decimals: Int, val coingeckoId: String?)

    suspend fun decimals(rpcUrl: String, contract: String): Outcome<Int> =
        call(rpcUrl, "eth_call", """[{"to":"$contract","data":"0x313ce567"},"latest"]""").map { hexToBig(it).toInt() }

    suspend fun sync(rpcUrl: String, nativeSymbol: String, nativeCg: String?, address: String, tokens: List<Token>): Outcome<SyncResult> {
        val bal = call(rpcUrl, "eth_getBalance", """["$address","latest"]""")
        if (bal is Outcome.Err) return bal
        val balances = arrayListOf(ObservedBalance(nativeSymbol, nativeSymbol, scale(hexToBig((bal as Outcome.Ok).value), 18), "NATIVE", nativeCg))
        val limitations = arrayListOf<String>()
        val padded = address.removePrefix("0x").lowercase().padStart(64, '0')
        for (t in tokens) {
            when (val r = call(rpcUrl, "eth_call", """[{"to":"${t.contract}","data":"0x70a08231$padded"},"latest"]""")) {
                is Outcome.Ok -> { val q = scale(hexToBig(r.value), t.decimals); if (q.signum() > 0) balances += ObservedBalance("erc20:${t.contract.lowercase()}", t.symbol, q, "ERC20", t.coingeckoId) }
                is Outcome.Err -> limitations += "Token ${t.symbol}: ${r.message}"
            }
        }
        limitations += "Histórico de transações EVM não é oferecido por RPC público sem chave (exploradores como Etherscan exigem chave). Registre operações manualmente ou importe CSV."
        limitations += "Tokens ERC-20 só são consultados se cadastrados (contrato + decimais); não há descoberta automática gratuita e confiável."
        return Outcome.Ok(SyncResult(balances, emptyList(), source = rpcUrl.substringAfter("://").substringBefore('/'), limitations = limitations))
    }
}
