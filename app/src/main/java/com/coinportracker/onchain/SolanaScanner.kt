package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Leitura automática de carteiras Solana (Phantom, Solflare, Backpack...), somente leitura:
 * SOL nativo, tokens SPL e Token-2022, contas de stake nativo e staking líquido (mSOL, jitoSOL, JupSOL...).
 * Metadados (nome, símbolo, ícone, verificação) e preço vêm da API pública da Jupiter (lite-api, sem chave).
 */
class SolanaScanner(private val http: HttpClient, private val rpc: JsonRpc, private val historyLimit: Int = 15) {
    private val jupPolicy = RatePolicy(minIntervalMs = 300, maxRetries = 2, baseBackoffMs = 1_000, maxBackoffMs = 8_000)
    private val programs = listOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    private val net = Network.SOLANA

    data class JupToken(val mint: String, val symbol: String, val name: String, val icon: String?, val decimals: Int?, val verified: Boolean, val organicScore: Double?, val usdPrice: Double?)

    suspend fun scan(address: String, withHistory: Boolean, quick: Boolean = false): Outcome<ScanResult> = coroutineScope {
        val addr = address.trim()
        val warnings = ArrayList<String>()
        val bal = rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getBalance", """["$addr"]""")
        if (bal is Outcome.Err) return@coroutineScope Outcome.Err("Solana: ${bal.message}", bal.cause, true)
        val lamports = (bal as Outcome.Ok).value.o()?.get("value").s()?.toBigIntegerOrNull() ?: BigInteger.ZERO
        val holdings = ArrayList<TokenHolding>()
        if (lamports.signum() > 0) holdings += TokenHolding(net, "native", "SOL", "Solana", 9, scaled(lamports, 9), net.iconUrl, net.nativePriceKey, HoldingKind.NATIVE, true, coingeckoId = "solana")

        val tokenDefs = programs.map { pid -> async { rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getTokenAccountsByOwner", """["$addr",{"programId":"$pid"},{"encoding":"jsonParsed"}]""") } }
        val historyDef = if (withHistory) async { history(addr) } else null

        val raw = LinkedHashMap<String, Pair<BigDecimal, Int>>()
        var tokensComplete = true
        tokenDefs.forEach { d ->
            when (val r = d.await()) {
                is Outcome.Err -> { warnings += "Solana: tokens não lidos agora (${r.message})."; tokensComplete = false }
                is Outcome.Ok -> parseTokenAccounts(r.value).forEach { (mint, amt, dec) -> raw[mint] = (raw[mint]?.first ?: BigDecimal.ZERO).add(amt) to dec }
            }
        }
        val histRes = historyDef?.await()
        val histMints = (histRes as? Outcome.Ok)?.value.orEmpty().mapNotNull { it.tokenKey?.takeIf { k -> k != "native" } }
        val allMints = (raw.keys + histMints).distinct()
        val meta = if (allMints.isNotEmpty()) jupiterMeta(allMints) else emptyMap()
        for ((mint, p) in raw) {
            val m = meta[mint]
            val sym = m?.symbol ?: (mint.take(4) + "…")
            holdings += TokenHolding(net, mint, sym.take(16), (m?.name ?: "Token ${mint.take(4)}…${mint.takeLast(4)}").take(48), m?.decimals ?: p.second, p.first,
                m?.icon, "solana:$mint", if (StakingTokens.isLiquidStaking(sym)) HoldingKind.LIQUID_STAKING else HoldingKind.TOKEN,
                verified = m?.verified == true, priceUsdHint = m?.usdPrice)
        }
        val stakeDefs = if (quick) emptyList() else listOf(12, 44).map { off -> async { rpc.call(listOf(com.coinportracker.dev.DevSources.rpcs(net).first()), "getProgramAccounts", """["Stake11111111111111111111111111111111111111",{"encoding":"jsonParsed","filters":[{"memcmp":{"offset":$off,"bytes":"$addr"}}]}]""") } }
        val seen = HashSet<String>()
        var stakeErr = false
        stakeDefs.forEach { d ->
            when (val r = d.await()) {
                is Outcome.Err -> stakeErr = true
                is Outcome.Ok -> parseStakeAccounts(r.value).forEach { (acct, lamp, voter) ->
                    if (seen.add(acct)) holdings += TokenHolding(net, "stake:$acct", "SOL", "SOL em staking" + (voter?.let { " · ${it.take(4)}…" } ?: ""), 9,
                        scaled(lamp, 9), net.iconUrl, net.nativePriceKey, HoldingKind.STAKED, true, coingeckoId = "solana")
                }
            }
        }
        if (stakeErr && seen.isEmpty()) warnings += "Solana: contas de stake nativo não puderam ser consultadas agora (RPC público limitado)."
        val activity = when (val h = histRes) {
            null -> emptyList()
            is Outcome.Ok -> h.value.map { a -> meta[a.tokenKey]?.let { m -> a.copy(symbol = m.symbol.take(16), iconUrl = m.icon) } ?: a }
            is Outcome.Err -> { warnings += "Solana: histórico indisponível agora."; emptyList() }
        }
        Outcome.Ok(ScanResult(holdings, activity, warnings, listOf("Solana RPC público", "Jupiter"), tokensComplete = tokensComplete, stakesComplete = !quick && !stakeErr))
    }

    suspend fun jupiterMeta(mints: List<String>): Map<String, JupToken> {
        val out = HashMap<String, JupToken>()
        for (chunk in mints.chunked(100)) {
            val r = http.get("jup", "https://lite-api.jup.ag/tokens/v2/search?query=" + chunk.joinToString(","), jupPolicy)
            if (r is Outcome.Ok) runCatching { parseJupiter(chainJson.parseToJsonElement(r.value)) }.getOrNull()?.forEach { out[it.mint] = it }
        }
        return out
    }

    /** Histórico paginado (v3): incremental e aprofundando até a primeira transação da carteira. */
    suspend fun history(addr: String, st: HistState, onBatch: (suspend (List<ChainActivity>) -> Unit)? = null): Outcome<HistPage> {
        val r = historyPaged(addr.trim(), st, rpc, onBatch)
        if (r !is Outcome.Ok) return r
        val acts = r.value.activity
        val mints = acts.mapNotNull { it.tokenKey?.takeIf { k -> k != "native" } }.distinct()
        val meta = if (mints.isNotEmpty()) jupiterMeta(mints) else emptyMap()
        return Outcome.Ok(r.value.copy(activity = acts.map { a -> meta[a.tokenKey]?.let { m -> a.copy(symbol = m.symbol.take(16), iconUrl = m.icon) } ?: a }))
    }

    /** Movimentos de uma transação (SOL e tokens) para o dono [addr]. */
    internal suspend fun txActivity(sig: String, time: Long?, failed: Boolean, memo: String?, addr: String): List<ChainActivity> {
        val parsed = rpc.getTransaction(com.coinportracker.dev.DevSources.rpcs(net), sig)?.let { parseTxDeltas(it, addr) }
        if (parsed.isNullOrEmpty()) return listOf(ChainActivity(net, sig, time, Direction.CONTRACT, "SOL", null, null, null, null, failed, memo))
        return parsed.map { d ->
            ChainActivity(net, if (d.mint == null) sig else "$sig#${d.mint}", time, if (d.delta.signum() >= 0) Direction.IN else Direction.OUT,
                if (d.mint == null) "SOL" else d.mint.take(4) + "…", d.delta.abs(), d.mint ?: "native", null, d.fee, failed, null, null,
                if (d.mint == null) net.nativePriceKey else "solana:${d.mint}")
        }
    }

    private suspend fun history(addr: String): Outcome<List<ChainActivity>> {
        val sigs = rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getSignaturesForAddress", """["$addr",{"limit":$historyLimit}]""")
        if (sigs is Outcome.Err) return sigs
        val list = (sigs as Outcome.Ok).value.a().orEmpty().mapNotNull { it.o() }
        val out = ArrayList<ChainActivity>()
        for (s in list) {
            val sig = s["signature"].s() ?: continue
            val failed = s["err"] != null && s["err"] !is JsonNull
            val time = s["blockTime"].s()?.toLongOrNull()?.times(1000)
            val tx = rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getTransaction", """["$sig",{"encoding":"jsonParsed","maxSupportedTransactionVersion":0}]""")
            val parsed = (tx as? Outcome.Ok)?.value?.let { parseTxDeltas(it, addr) }
            if (parsed.isNullOrEmpty()) {
                out += ChainActivity(net, sig, time, Direction.CONTRACT, "SOL", null, null, null, null, failed, s["memo"].s())
            } else parsed.forEach { d ->
                out += ChainActivity(net, if (d.mint == null) sig else "$sig#${d.mint}", time, if (d.delta.signum() >= 0) Direction.IN else Direction.OUT,
                    if (d.mint == null) "SOL" else d.mint.take(4) + "…", d.delta.abs(), d.mint ?: "native", null, d.fee, failed, null, null,
                    if (d.mint == null) net.nativePriceKey else "solana:${d.mint}")
            }
        }
        return Outcome.Ok(out)
    }

    data class Delta(val mint: String?, val delta: BigDecimal, val fee: BigDecimal?)

    companion object {

        fun parseTokenAccounts(el: JsonElement): List<Triple<String, BigDecimal, Int>> = el.o()?.get("value").a().orEmpty().mapNotNull { acc ->
            val info = acc.o()?.get("account").o()?.get("data").o()?.get("parsed").o()?.get("info").o() ?: return@mapNotNull null
            val mint = info["mint"].s() ?: return@mapNotNull null
            val ta = info["tokenAmount"].o() ?: return@mapNotNull null
            val dec = ta["decimals"].s()?.toIntOrNull() ?: 0
            val amt = ta["amount"].s()?.toBigIntegerOrNull()?.let { scaled(it, dec) } ?: return@mapNotNull null
            if (amt.signum() <= 0) null else Triple(mint, amt, dec)
        }

        fun parseStakeAccounts(el: JsonElement): List<Triple<String, BigInteger, String?>> = el.a().orEmpty().mapNotNull { a ->
            val ao = a.o() ?: return@mapNotNull null
            val acct = ao["account"].o() ?: return@mapNotNull null
            val lamports = acct["lamports"].s()?.toBigIntegerOrNull() ?: return@mapNotNull null
            val voter = acct["data"].o()?.get("parsed").o()?.get("info").o()?.get("stake").o()?.get("delegation").o()?.get("voter").s()
            Triple(ao["pubkey"].s() ?: return@mapNotNull null, lamports, voter)
        }

        fun parseJupiter(el: JsonElement): List<JupToken> = el.a().orEmpty().mapNotNull { it.o() }.mapNotNull { o ->
            val id = o["id"].s() ?: return@mapNotNull null
            val tags = o["tags"].a()?.mapNotNull { it.s() }.orEmpty()
            JupToken(id, o["symbol"].s() ?: "?", o["name"].s() ?: "?", o["icon"].s(), o["decimals"].s()?.toIntOrNull(),
                o["isVerified"].s() == "true" || "verified" in tags || "strict" in tags, o["organicScore"].s()?.toDoubleOrNull(), o["usdPrice"].s()?.toDoubleOrNull())
        }

        /** Variação de SOL e de cada token da carteira dentro de uma transação (pré × pós saldos). */
        fun parseTxDeltas(el: JsonElement, owner: String): List<Delta> {
            val meta = el.o()?.get("meta").o() ?: return emptyList()
            val keys = el.o()?.get("transaction").o()?.get("message").o()?.get("accountKeys").a().orEmpty().map { k -> k.o()?.get("pubkey").s() ?: k.s() }
            val idx = keys.indexOf(owner)
            val out = ArrayList<Delta>()
            val fee = meta["fee"].s()?.toBigIntegerOrNull()
            val feePayer = idx == 0
            if (idx >= 0) {
                val pre = meta["preBalances"].a()?.getOrNull(idx).s()?.toBigIntegerOrNull()
                val post = meta["postBalances"].a()?.getOrNull(idx).s()?.toBigIntegerOrNull()
                if (pre != null && post != null) {
                    var d = post - pre
                    if (feePayer && fee != null) d += fee
                    if (d.signum() != 0) out += Delta(null, scaled(d.abs(), 9).let { if (d.signum() < 0) it.negate() else it }, if (feePayer && fee != null) scaled(fee, 9) else null)
                }
            }
            // v5: o dono pode ter mais de uma conta do mesmo token (ATA + conta auxiliar): soma por mint em vez de sobrescrever.
            // Usa a quantidade inteira (amount/decimals) quando existe, sem arredondamento de uiAmount.
            fun tokenMap(k: String): Map<String, BigDecimal> {
                val m = HashMap<String, BigDecimal>()
                for (b in meta[k].a().orEmpty().mapNotNull { it.o() }) {
                    if (b["owner"].s() != owner) continue
                    val ui = b["uiTokenAmount"].o()
                    val dec = ui?.get("decimals").s()?.toIntOrNull()
                    val v = ui?.get("amount").s()?.toBigIntegerOrNull()?.let { raw -> if (dec != null) BigDecimal(raw, dec) else null }
                        ?: ui?.get("uiAmountString").s()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val mint = b["mint"].s() ?: ""
                    m[mint] = (m[mint] ?: BigDecimal.ZERO).add(v)
                }
                return m
            }
            val pre = tokenMap("preTokenBalances"); val post = tokenMap("postTokenBalances")
            for (mint in (pre.keys + post.keys)) {
                val d = (post[mint] ?: BigDecimal.ZERO).subtract(pre[mint] ?: BigDecimal.ZERO)
                if (d.signum() != 0 && mint.isNotEmpty()) out += Delta(mint, d, null)
            }
            return out
        }
    }
}
