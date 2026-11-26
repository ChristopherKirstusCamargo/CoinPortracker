package com.coinportracker.onchain

import com.coinportracker.dev.DevSources
import com.coinportracker.network.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class WatchWallet(val id: Long, val address: String, val kind: AddressKind)

/** Acorda uma leitura real. [walletId] nulo = todas as carteiras daquelas redes. */
data class WatchWake(val walletId: Long?, val networks: Set<Network>)

/**
 * Avisos de rede em primeiro plano. Cada provedor foi conferido no protocolo que o app já usa:
 * - Solana: `logsSubscribe` no mesmo host do RPC HTTPS (publicnode e api.mainnet-beta).
 * - Bitcoin: WebSocket do mempool.space (`track-addresses`). Blockstream não tem este canal.
 * - Litecoin: o mesmo protocolo no litecoinspace.org (fork do mempool). Se a conexão cair, fica o polling.
 * - EVM: `eth_subscribe` `newHeads` no host wss do RPC. Um bloco novo não é saldo — só dispara a leitura daquela rede, no máximo a cada 12 s.
 * - XRP: `subscribe` de contas no cluster público (`wss://xrplcluster.com`).
 * - Stellar: stream SSE da Horizon (`/accounts/{id}/payments`).
 * Dogecoin, Tron, Cardano, TON, Cosmos, Near, Sui e Aptos não têm, nos provedores já configurados,
 * um canal público de conta sem chave. Essas redes ficam no polling adaptativo.
 * Kraken: ticker WebSocket v2 só no host oficial; URL customizada continua só em HTTP.
 */
class ChainWatch(private val http: HttpClient) {
    private val sockets: OkHttpClient by lazy {
        http.currentClient().newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun run(
        wallets: List<WatchWallet>,
        onWake: suspend (WatchWake) -> Unit,
        onPrices: suspend (Map<String, Double>) -> Unit,
    ) {
        val usable = wallets.mapNotNull { w -> WatchSignals.safeAddress(w.address)?.let { w.copy(address = it) } }
        val gate = WakeGate()
        supervisorScope {
            val kraken = WatchSignals.krakenWebSocket(
                DevSources.priceUrl("kraken", "https://api.kraken.com"),
                DevSources.priceEnabled("kraken"),
            )
            if (kraken != null) launch { kraken(kraken, onPrices) }
            usable.filter { it.kind == AddressKind.SOLANA }.take(MAX_PER_FAMILY).forEach { w ->
                launch { solana(w) { if (gate.allow("sol:${w.id}")) onWake(WatchWake(w.id, setOf(Network.SOLANA))) } }
            }
            val btc = usable.filter { it.kind == AddressKind.BITCOIN }.take(MAX_PER_FAMILY)
            if (btc.isNotEmpty()) launch { esplora(Network.BITCOIN, btc, gate, onWake) }
            val ltc = usable.filter { it.kind == AddressKind.LITECOIN }.take(MAX_PER_FAMILY)
            if (ltc.isNotEmpty()) launch { esplora(Network.LITECOIN, ltc, gate, onWake) }
            if (usable.any { it.kind == AddressKind.EVM }) {
                Network.evm.forEach { net -> launch { evm(net, gate, onWake) } }
            }
            val xrp = usable.filter { it.kind == AddressKind.XRP }.take(MAX_PER_FAMILY)
            if (xrp.isNotEmpty()) launch { xrpl(xrp, gate, onWake) }
            val xlm = usable.filter { it.kind == AddressKind.STELLAR }.take(MAX_PER_FAMILY)
            xlm.forEach { w -> launch { stellar(w) { if (gate.allow("xlm:${w.id}")) onWake(WatchWake(w.id, setOf(Network.STELLAR))) } } }
        }
    }

    private suspend fun solana(w: WatchWallet, onLog: suspend () -> Unit) {
        val urls = DevSources.rpcs(Network.SOLANA).mapNotNull { WatchSignals.toWebSocket(it) }.distinct()
        rotate(urls) { url ->
            session(url) { ws, incoming ->
                ws.send("""{"jsonrpc":"2.0","id":1,"method":"logsSubscribe","params":[{"mentions":"${w.address}"},{"commitment":"confirmed"}]}""")
                for (msg in incoming) if (WatchSignals.isSolanaLog(msg)) onLog()
            }
        }
    }

    private suspend fun esplora(net: Network, wallets: List<WatchWallet>, gate: WakeGate, onWake: suspend (WatchWake) -> Unit) {
        val urls = DevSources.rpcs(net).mapNotNull { WatchSignals.esploraWebSocket(it) }.distinct()
        val byAddr = wallets.associateBy { it.address }
        rotate(urls) { url ->
            session(url) { ws, incoming ->
                val list = wallets.joinToString(",") { "\"${it.address}\"" }
                ws.send("""{"track-addresses":[$list]}""")
                for (msg in incoming) {
                    for (addr in WatchSignals.mempoolAddresses(msg, byAddr.keys)) {
                        val w = byAddr[addr] ?: continue
                        if (gate.allow("esplora:${w.id}")) onWake(WatchWake(w.id, setOf(net)))
                    }
                }
            }
        }
    }

    private suspend fun evm(net: Network, gate: WakeGate, onWake: suspend (WatchWake) -> Unit) {
        val urls = DevSources.rpcs(net).mapNotNull { WatchSignals.toWebSocket(it) }.distinct()
        rotate(urls) { url ->
            session(url) { ws, incoming ->
                ws.send("""{"jsonrpc":"2.0","id":1,"method":"eth_subscribe","params":["newHeads"]}""")
                for (msg in incoming) {
                    if (WatchSignals.isEvmHead(msg) && gate.allow("evm:${net.name}", 12_000L)) onWake(WatchWake(null, setOf(net)))
                }
            }
        }
    }

    private suspend fun xrpl(wallets: List<WatchWallet>, gate: WakeGate, onWake: suspend (WatchWake) -> Unit) {
        val urls = DevSources.rpcs(Network.XRP).mapNotNull { WatchSignals.toWebSocket(it) }.distinct()
        val byAddr = wallets.associateBy { it.address }
        rotate(urls) { url ->
            session(url) { ws, incoming ->
                val accounts = wallets.joinToString(",") { "\"${it.address}\"" }
                ws.send("""{"id":1,"command":"subscribe","accounts":[$accounts]}""")
                for (msg in incoming) {
                    for (addr in WatchSignals.xrplAccounts(msg, byAddr.keys)) {
                        val w = byAddr[addr] ?: continue
                        if (gate.allow("xrp:${w.id}")) onWake(WatchWake(w.id, setOf(Network.XRP)))
                    }
                }
            }
        }
    }

    private suspend fun stellar(w: WatchWallet, onEvent: suspend () -> Unit) {
        val bases = DevSources.rpcs(Network.STELLAR).filter { it.startsWith("https://") }
        rotate(bases) { base ->
            val url = base.trimEnd('/') + "/accounts/${w.address}/payments?cursor=now"
            val lines = Channel<Unit>(Channel.CONFLATED)
            val call = sockets.newCall(Request.Builder().url(url).header("Accept", "text/event-stream").build())
            val reader = thread(name = "xlm-sse", isDaemon = true) {
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val src = resp.body?.source() ?: return@use
                        while (true) {
                            val line = src.readUtf8Line() ?: break
                            if (WatchSignals.stellarData(line)) lines.trySend(Unit)
                        }
                    }
                } catch (_: Throwable) {
                } finally { lines.close() }
            }
            try {
                for (ignored in lines) onEvent()
            } finally {
                call.cancel()
                reader.interrupt()
            }
        }
    }

    private suspend fun kraken(url: String, onPrices: suspend (Map<String, Double>) -> Unit) {
        val symbols = LiveQuotes.wsSymbols()
        if (symbols.isEmpty()) return
        rotate(listOf(url)) { endpoint ->
            session(endpoint) { ws, incoming ->
                val list = symbols.joinToString(",") { "\"$it\"" }
                ws.send("""{"method":"subscribe","params":{"channel":"ticker","symbol":[$list]}}""")
                val buf = HashMap<String, Double>()
                var lastFlush = 0L
                for (msg in incoming) {
                    val ticks = WatchSignals.krakenPrices(msg)
                    if (ticks.isEmpty()) continue
                    buf.putAll(ticks)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= 400L) {
                        val snap = HashMap(buf)
                        buf.clear()
                        lastFlush = now
                        onPrices(snap)
                    }
                }
            }
        }
    }

    private suspend fun rotate(urls: List<String>, body: suspend (String) -> Unit) {
        if (urls.isEmpty()) return
        var i = 0
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            try {
                body(urls[i % urls.size])
                backoff = 2_000L
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
            } catch (_: Throwable) {
            }
            i++
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun session(url: String, block: suspend (WebSocket, Channel<String>) -> Unit) {
        val incoming = Channel<String>(64)
        val opened = CompletableDeferred<Unit>()
        val req = Request.Builder().url(url).build()
        val ws = sockets.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.isCompleted) opened.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                incoming.close()
                if (!opened.isCompleted) opened.completeExceptionally(IOException("fechado $code"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
                if (!opened.isCompleted) opened.completeExceptionally(t)
            }
        })
        try {
            withTimeout(20_000) { opened.await() }
            block(ws, incoming)
        } finally {
            ws.close(1000, null)
            ws.cancel()
            incoming.close()
        }
    }

    private companion object {
        const val MAX_PER_FAMILY = 8
    }
}

/** Não relê a mesma conta em rajada. */
class WakeGate(private val clock: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<String, Long>()
    fun allow(key: String, minMs: Long = 8_000L): Boolean {
        val now = clock()
        val prev = last[key]
        if (prev != null && now - prev < minMs) return false
        last[key] = now
        return true
    }
}
