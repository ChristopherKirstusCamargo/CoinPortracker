package com.coinportracker.webhost

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v6.0 — servidor HTTP/1.1 enxuto (sem dependências) para o Web Host. Leve de propósito: 4 threads, no máximo 16
 * conexões (4 por IP), 10 s de tempo limite, cabeçalho e corpo com teto. Atende só quem chega pela rede local.
 */
class WebServer(
    private val port: Int,
    private val router: WebRouter,
    private val allowTailscale: () -> Boolean,
    private val onAccess: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12)) { r -> Thread(r, "cpt-web").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val perIp = ConcurrentHashMap<String, AtomicInteger>()
    private val total = AtomicInteger()
    @Volatile private var localCache: Pair<Long, Set<String>> = 0L to emptySet()
    val boundPort: Int get() = server?.localPort ?: -1

    companion object { const val MAX_CONN = 16; const val MAX_PER_IP = 4; const val TIMEOUT_MS = 10_000 }

    /** Liga o servidor (lança se a porta estiver ocupada). */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port), 32)
        server = s
        acceptThread = Thread({ loop(s) }, "cpt-web-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt(); acceptThread = null
        pool.shutdownNow()
    }

    private fun localOk(sock: Socket): Boolean {
        val local = sock.localAddress ?: return false
        if (local.isLoopbackAddress) return true
        val now = System.currentTimeMillis()
        var c = localCache
        if (now - c.first > 5_000) { c = now to WebNet.addresses(allowTailscale()).map { it.ip }.toSet(); localCache = c }
        return (local.hostAddress ?: return false) in c.second
    }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
            val ip = sock.inetAddress?.hostAddress ?: ""
            if (!WebNet.remoteAllowed(ip, allowTailscale()) || !localOk(sock)) { runCatching { sock.close() }; continue }
            val n = perIp.computeIfAbsent(ip) { AtomicInteger() }
            if (total.incrementAndGet() > MAX_CONN || n.incrementAndGet() > MAX_PER_IP) { release(ip); runCatching { sock.close() }; continue }
            try { pool.execute { serve(sock, ip) } } catch (_: RejectedExecutionException) { release(ip); runCatching { sock.close() } }
        }
    }

    private fun release(ip: String) { total.decrementAndGet(); perIp[ip]?.let { if (it.decrementAndGet() <= 0) perIp.remove(ip) } }

    private fun serve(sock: Socket, ip: String) {
        try {
            sock.soTimeout = TIMEOUT_MS
            sock.tcpNoDelay = true
            val input = sock.getInputStream().buffered(8 * 1024)
            val out = sock.getOutputStream().buffered(16 * 1024)
            val resp = try {
                val req = HttpParser.read(input)
                onAccess(ip)
                router.handle(req, ip).also { if (req.method == "HEAD") { HttpParser.write(out, it, head = true); return } }
            } catch (e: HttpError) { router.secure(HttpResponse.error(e.status, e.message ?: "erro")) }
            HttpParser.write(out, resp)
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
            release(ip)
        }
    }
}
