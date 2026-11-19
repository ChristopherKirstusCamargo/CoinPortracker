package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Estado do histórico de uma carteira em uma rede: o histórico é lido aos poucos (incremental para frente e
 * aprofundando para trás a cada sincronização) até chegar à primeira transação da carteira.
 * [cursors]: endpoint → cursor para buscar transações mais antigas (ausente = esgotado, já chegou ao início).
 */
@Serializable
data class HistState(
    val started: Boolean = false,
    val newest: Map<String, String> = emptyMap(),
    val cursors: Map<String, String> = emptyMap(),
    val oldest: Map<String, Long> = emptyMap(),
) {
    val complete: Boolean get() = started && cursors.isEmpty()
    /** Desde quando o histórico é completo (para trás disso ainda falta ler). */
    val knownFrom: Long? get() = if (complete) oldest.values.minOrNull() else cursors.keys.mapNotNull { oldest[it] }.maxOrNull() ?: oldest.values.minOrNull()
}

data class HistPage(val activity: List<ChainActivity>, val state: HistState)

/** Limites por sincronização (protege os RPCs públicos gratuitos). */
object HistLimits {
    const val SOL_PAGE = 100
    const val SOL_MAX_TX_PER_SYNC = 160
    const val EVM_PAGES_FIRST = 4
    const val EVM_PAGES_BACKFILL = 3
    const val BTC_PAGES = 6
}

/** Solana: assinaturas paginadas (until = mais nova conhecida; before = cursor para o passado). */
suspend fun SolanaScanner.historyPaged(addr: String, st0: HistState, rpc: JsonRpc, onBatch: (suspend (List<ChainActivity>) -> Unit)? = null): Outcome<HistPage> {
    val net = Network.SOLANA
    val ep = "sig"
    // v6.0 — causa raiz da Atividade vazia: quando a 1ª leitura voltava sem nenhuma assinatura (carteira adicionada antes de
    // receber a 1ª transação, ou um nó que respondeu lista vazia), o estado ficava "começado e completo" sem a assinatura mais
    // nova. Daí em diante a leitura para frente exigia essa assinatura e a para trás já estava "esgotada": nada novo era lido,
    // nunca mais. Agora um estado sem assinatura conhecida recomeça do topo.
    val st = if (st0.started && st0.newest[ep] == null) HistState() else st0
    val all = com.coinportracker.dev.DevSources.rpcs(net)
    val sigs = ArrayList<kotlinx.serialization.json.JsonObject>()
    var newest = st.newest[ep]
    var cursor = st.cursors[ep]
    var exhausted = st.started && cursor == null
    var gap = st.cursors[GAP]
    var gapUntil = st.newest[GAP_UNTIL]
    var budget = HistLimits.SOL_MAX_TX_PER_SYNC
    // lê páginas entre [before] (exclusive) e [until] (exclusive), mais novas primeiro; devolve o cursor se faltou orçamento
    suspend fun between(before: String?, until: String): Outcome<String?> {
        var b = before
        while (budget > 0) {
            val lim = minOf(HistLimits.SOL_PAGE, budget)
            // para frente qualquer nó serve (não decide onde o histórico começa): o oficial primeiro, os alternativos se ele falhar
            val r = rpc.call(all, "getSignaturesForAddress", """["$addr",{"limit":$lim,"until":"$until"${b?.let { ""","before":"$it"""" } ?: ""}}]""")
            if (r is Outcome.Err) return r
            val page = (r as Outcome.Ok).value.a().orEmpty().mapNotNull { it.o() }
            sigs += page; budget -= page.size
            if (page.size < lim) return Outcome.Ok(null)
            b = page.last()["signature"].s() ?: return Outcome.Ok(null)
        }
        return Outcome.Ok(b)
    }
    // 1a) primeiro, a lacuna pendente de uma leitura anterior
    if (gap != null && gapUntil != null && budget > 0) {
        val r = between(gap, gapUntil!!)
        if (r is Outcome.Ok) { gap = r.value; if (gap == null) gapUntil = null }
    }
    // 1) para frente: novas desde a última leitura. v6.0: antes só a 1ª página (100) era lida e a mais nova virava a
    // referência: com mais de 100 novas, o trecho do meio nunca era lido. Agora pagina e, se o orçamento acabar, guarda a lacuna.
    // Só avança se não houver lacuna antiga pendente (senão uma 2ª lacuna se perderia; a leitura seguinte tenta de novo).
    if (st.started && newest != null && gap == null) {
        val old = newest
        val from = sigs.size
        val r = between(null, old)
        if (r is Outcome.Err) return r
        val left = (r as Outcome.Ok).value
        newest = sigs.getOrNull(from)?.get("signature").s() ?: newest
        if (left != null) { gap = left; gapUntil = old }
    }
    // 2) para trás: aprofunda até o início da carteira
    while (!exhausted && budget > 0) {
        val lim = minOf(HistLimits.SOL_PAGE, budget)
        val before = if (!st.started && cursor == null && sigs.isEmpty()) "" else cursor?.let { ""","before":"$it"""" } ?: ""
        // histórico completo só no RPC oficial (os alternativos guardam poucos dias e dariam um falso "início")
        var r = rpc.call(listOf(all.first()), "getSignaturesForAddress", """["$addr",{"limit":$lim$before}]""")
        var viaAlt = false
        if (r is Outcome.Err && sigs.isEmpty() && cursor == null && all.size > 1) {
            // v6.0: o oficial falhou na 1ª página: mostra o mais recente por um alternativo, sem declarar o histórico completo
            r = rpc.call(all.drop(1), "getSignaturesForAddress", """["$addr",{"limit":$lim}]""")
            viaAlt = true
        }
        if (r is Outcome.Err) { if (sigs.isEmpty()) return r else break }
        val page = (r as Outcome.Ok).value.a().orEmpty().mapNotNull { it.o() }
        sigs += page
        budget -= page.size
        if (viaAlt) {
            // nada a afirmar sobre o início: sem assinaturas, fica como "não começado" e a próxima leitura tenta o oficial de novo
            cursor = page.lastOrNull()?.get("signature").s() ?: return Outcome.Ok(HistPage(emptyList(), st0.takeIf { !it.started } ?: HistState()))
            break
        }
        if (page.size < lim) { exhausted = true; cursor = null } else cursor = page.last()["signature"].s()
        if (page.isEmpty()) break
    }
    val out = ArrayList<ChainActivity>()
    var oldestTs = st.oldest[ep]
    var flushed = 0
    for (s in sigs) {
        val sig = s["signature"].s() ?: continue
        val time = s["blockTime"].s()?.toLongOrNull()?.times(1000)
        if (time != null && (oldestTs == null || time < oldestTs)) oldestTs = time
        out += txActivity(sig, time, s["err"] != null && s["err"] !is JsonNull, s["memo"].s(), addr)
        // v6.0: grava aos poucos (a lista enche enquanto lê, em vez de ficar vazia até a última de 160 transações)
        if (onBatch != null && out.size - flushed >= 20) { onBatch(out.subList(flushed, out.size).toList()); flushed = out.size }
    }
    if (sigs.isNotEmpty() && newest == null) newest = sigs.first()["signature"].s()
    var cursors = if (exhausted || cursor == null) st.cursors - ep else st.cursors + (ep to cursor)
    cursors = if (gap != null && gapUntil != null) cursors + (GAP to gap!!) else cursors - GAP
    var newMap = if (newest != null) st.newest + (ep to newest) else st.newest
    newMap = if (gap != null && gapUntil != null) newMap + (GAP_UNTIL to gapUntil!!) else newMap - GAP_UNTIL
    if (onBatch != null && out.size > flushed) onBatch(out.subList(flushed, out.size).toList())
    return Outcome.Ok(HistPage(out, HistState(true, newMap, cursors, if (oldestTs != null) st.oldest + (ep to oldestTs) else st.oldest)))
}

private const val GAP = "sig:gap"
private const val GAP_UNTIL = "sig:gapUntil"

/** EVM (Blockscout v2): transações, transferências ERC-20 e transferências internas (ETH recebido de contratos). */
suspend fun EvmScanner.historyPaged(net: Network, base: String, addr: String, st: HistState, get: suspend (String) -> Outcome<kotlinx.serialization.json.JsonElement>): Outcome<HistPage> {
    val eps = linkedMapOf("tx" to "/api/v2/addresses/$addr/transactions", "tt" to "/api/v2/addresses/$addr/token-transfers?type=ERC-20", "it" to "/api/v2/addresses/$addr/internal-transactions")
    val out = ArrayList<ChainActivity>()
    var newest = st.newest; var cursors = st.cursors; var oldest = st.oldest
    var anyOk = false
    for ((ep, path) in eps) {
        fun parse(el: kotlinx.serialization.json.JsonElement): List<ChainActivity> = when (ep) {
            "tx" -> EvmScanner.parseBlockscoutTxs(net, addr, el)
            "tt" -> EvmScanner.parseBlockscoutTransfers(net, addr, el)
            else -> EvmScanner.parseBlockscoutInternal(net, addr, el)
        }
        fun withParams(p: String) = if (p.isBlank()) path else path + (if ('?' in path) "&" else "?") + p
        // para frente
        var pages = 0; var params = ""; var stop = false
        val firstRun = !st.started || ep !in st.newest && ep !in st.cursors && !st.oldest.containsKey(ep)
        val maxPages = if (firstRun) HistLimits.EVM_PAGES_FIRST else 2
        var newCursor: String? = cursors[ep]
        var epExhausted = !firstRun && ep !in cursors
        while (!stop && pages < maxPages) {
            val r = get(withParams(params))
            if (r is Outcome.Err) { if (ep == "tx" && !anyOk) return r; break }
            anyOk = true
            val el = (r as Outcome.Ok).value
            val items = parse(el)
            if (pages == 0 && items.isNotEmpty()) newest = newest + (ep to items.first().hash)
            val known = st.newest[ep]
            val idx = if (!firstRun && known != null) items.indexOfFirst { it.hash == known } else -1
            out += if (idx >= 0) items.take(idx) else items
            items.mapNotNull { it.timestamp }.minOrNull()?.let { t -> if (firstRun) oldest = oldest + (ep to minOf(t, oldest[ep] ?: Long.MAX_VALUE)) }
            val next = nextParams(el)
            pages++
            if (idx >= 0 || next == null) stop = true
            if (firstRun) { newCursor = next; if (next == null) epExhausted = true }
            params = next ?: ""
        }
        // para trás (aprofundar)
        if (!firstRun && !epExhausted && newCursor != null) {
            var p = newCursor; var n = 0
            while (p != null && n < HistLimits.EVM_PAGES_BACKFILL) {
                val r = get(withParams(p))
                if (r is Outcome.Err) break
                val el = (r as Outcome.Ok).value
                val items = parse(el)
                out += items
                items.mapNotNull { it.timestamp }.minOrNull()?.let { t -> oldest = oldest + (ep to minOf(t, oldest[ep] ?: Long.MAX_VALUE)) }
                p = nextParams(el); n++
            }
            newCursor = p
            if (p == null) epExhausted = true
        }
        cursors = if (epExhausted || newCursor == null) cursors - ep else cursors + (ep to newCursor!!)
    }
    return Outcome.Ok(HistPage(out, HistState(true, newest, cursors, oldest)))
}

/** next_page_params do Blockscout → query string. */
internal fun nextParams(el: kotlinx.serialization.json.JsonElement): String? {
    val p = el.o()?.get("next_page_params") as? JsonObject ?: return null
    return p.entries.mapNotNull { (k, v) -> v.s()?.let { "$k=${java.net.URLEncoder.encode(it, "UTF-8")}" } }.joinToString("&").ifBlank { null }
}

/** Bitcoin (Esplora): /txs (mais recentes) e /txs/chain/:último para o passado. */
suspend fun BitcoinScanner.historyPaged(base: String, addr: String, st: HistState, net: Network = Network.BITCOIN, get: suspend (String) -> Outcome<String>): Outcome<HistPage> {
    val ep = "btc"
    val out = ArrayList<ChainActivity>()
    var oldest = st.oldest[ep]
    val first = get("$base/address/$addr/txs")
    if (first is Outcome.Err) return first
    val firstItems = runCatching { BitcoinScanner.parseTxs(chainJson.parseToJsonElement((first as Outcome.Ok).value), addr, net) }.getOrElse { emptyList() }
    val known = st.newest[ep]
    val idx = if (known != null) firstItems.indexOfFirst { it.hash == known } else -1
    out += if (idx >= 0) firstItems.take(idx) else firstItems
    var cursor: String? = if (!st.started) firstItems.lastOrNull { it.label == null }?.hash.takeIf { firstItems.count { it.label == null } >= 25 } else st.cursors[ep]
    var n = 0
    while (cursor != null && n < HistLimits.BTC_PAGES) {
        val r = get("$base/address/$addr/txs/chain/$cursor")
        if (r is Outcome.Err) break
        val items = runCatching { BitcoinScanner.parseTxs(chainJson.parseToJsonElement((r as Outcome.Ok).value), addr, net) }.getOrElse { emptyList() }
        out += items
        cursor = if (items.size >= 25) items.last().hash else null
        n++
    }
    out.mapNotNull { it.timestamp }.minOrNull()?.let { t -> oldest = minOf(t, oldest ?: Long.MAX_VALUE) }
    val newest = firstItems.firstOrNull()?.hash ?: known
    return Outcome.Ok(HistPage(out, HistState(true, if (newest != null) st.newest + (ep to newest) else st.newest,
        if (cursor != null) st.cursors + (ep to cursor) else st.cursors - ep, if (oldest != null) st.oldest + (ep to oldest!!) else st.oldest)))
}
