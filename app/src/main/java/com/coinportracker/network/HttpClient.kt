package com.coinportracker.network

import com.coinportracker.core.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Política de requisições por provedor: intervalo mínimo, tentativas e backoff exponencial. */
data class RatePolicy(val minIntervalMs: Long, val maxRetries: Int = 3, val baseBackoffMs: Long = 2_000, val maxBackoffMs: Long = 60_000)

class HttpError(val code: Int, message: String) : IOException(message)

/** Resposta maior que o limite (proteção contra respostas gigantes / esgotar memória). */
class ResponseTooLarge(limit: Long) : IOException("Resposta maior que ${limit / 1_000_000} MB")

/**
 * Cliente HTTP único do app. Sem cookies, sem cabeçalhos identificadores, sem telemetria.
 * Nada do portfólio do usuário é enviado: apenas identificadores públicos de ativos/endereços.
 *
 * v5: mapas de estado thread-safe (provedores diferentes rodam em paralelo), chamadas canceláveis
 * (cancelar a coroutine cancela o socket), limite de tamanho da resposta, URL inválida vira erro
 * (nunca exceção) e troca de timeouts reaproveita o pool de conexões.
 */
class HttpClient(
    client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val onRequest: suspend (String) -> Unit = {},
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
) {
    @Volatile private var client: OkHttpClient = client
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastCall = ConcurrentHashMap<String, Long>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private var timeouts: Triple<Int, Int, Int>? = null

    /** Aplica timeouts sem descartar o pool de conexões/dispatcher (TLS e keep-alive reaproveitados). No-op se iguais. */
    @Synchronized fun applyTimeouts(connectSec: Int, readSec: Int, callSec: Int) {
        val t = Triple(connectSec, readSec, callSec)
        if (t == timeouts) return
        timeouts = t
        client = client.newBuilder()
            .connectTimeout(connectSec.toLong().coerceIn(1, 120), TimeUnit.SECONDS)
            .readTimeout(readSec.toLong().coerceIn(1, 120), TimeUnit.SECONDS)
            .callTimeout(callSec.toLong().coerceIn(1, 120), TimeUnit.SECONDS)
            .build()
    }

    internal fun currentClient(): OkHttpClient = client

    private fun lockFor(key: String) = locks.getOrPut(key) { Mutex() }

    suspend fun get(providerKey: String, url: String, policy: RatePolicy, headers: Map<String, String> = emptyMap()): Outcome<String> =
        execute(providerKey, policy) { Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.header("Accept", "application/json").build() }

    suspend fun postJson(providerKey: String, url: String, body: String, policy: RatePolicy): Outcome<String> =
        execute(providerKey, policy) { Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build() }

    private suspend fun execute(key: String, policy: RatePolicy, build: () -> Request): Outcome<String> {
        // URL malformada (ex.: digitada na tela Dev) é erro definitivo, nunca exceção que derruba o chamador.
        val request = try { build() } catch (e: IllegalArgumentException) {
            return Outcome.Err("URL inválida: ${e.message ?: ""}".trim(), e, retryable = false)
        }
        return lockFor(key).withLock { executeLocked(key, policy, request) }
    }

    private suspend fun executeLocked(key: String, policy: RatePolicy, request: Request): Outcome<String> {
        var attempt = 0
        while (true) {
            val now = clock()
            val until = blockedUntil[key] ?: 0
            if (until > now) {
                return Outcome.Err("Limite de requisições do provedor atingido; nova tentativa liberada em ${(until - now) / 1000}s.", retryable = true)
            }
            val wait = (lastCall[key] ?: 0) + policy.minIntervalMs - now
            if (wait > 0) sleeper(wait)
            lastCall[key] = clock()
            val result: Outcome<String> = try {
                onRequest(key)
                com.coinportracker.dev.AppLog.add(key, "→")
                await(client.newCall(request)) { resp ->
                    when {
                        resp.isSuccessful -> { val body = readCapped(resp); com.coinportracker.dev.AppLog.add(key, "HTTP ${resp.code}"); Outcome.Ok(body) }
                        resp.code == 429 -> {
                            val retryAfter = parseRetryAfterMs(resp.header("Retry-After"), clock())
                            Outcome.Err("HTTP 429 (limite de requisições)", HttpError(429, retryAfter?.toString() ?: ""), retryable = true)
                        }
                        resp.code >= 500 -> { val body = readCapped(resp, 4_096); Outcome.Err("HTTP ${resp.code} (erro do servidor)", HttpError(resp.code, body.take(200)), retryable = true) }
                        else -> { val body = readCapped(resp, 4_096); Outcome.Err("HTTP ${resp.code}: ${body.take(200)}", HttpError(resp.code, body.take(200)), retryable = false) }
                    }
                }
            } catch (e: ResponseTooLarge) {
                com.coinportracker.dev.AppLog.add(key, "erro: ${e.message}")
                Outcome.Err("Resposta grande demais do provedor", e, retryable = false)
            } catch (e: IOException) {
                com.coinportracker.dev.AppLog.add(key, "erro: ${e.message ?: e.javaClass.simpleName}")
                Outcome.Err("Falha de rede: ${e.message ?: e.javaClass.simpleName}", e, retryable = true)
            }
            if (result is Outcome.Err && result.retryable && attempt < policy.maxRetries) {
                val retryAfter = (result.cause as? HttpError)?.takeIf { it.code == 429 }?.message?.toLongOrNull()
                val backoff = retryAfter ?: minOf(policy.maxBackoffMs, policy.baseBackoffMs shl attempt)
                if (backoff > policy.maxBackoffMs) { blockedUntil[key] = clock() + backoff; return result }
                attempt++
                sleeper(backoff)
                continue
            }
            if (result is Outcome.Err && (result.cause as? HttpError)?.code == 429) blockedUntil[key] = clock() + policy.maxBackoffMs
            return result
        }
    }

    /** Lê o corpo com teto de bytes (sem carregar respostas gigantes inteiras na memória). */
    private fun readCapped(resp: Response, limit: Long = maxBodyBytes): String {
        val body = resp.body ?: return ""
        val declared = body.contentLength()
        if (limit == maxBodyBytes && declared > limit) throw ResponseTooLarge(limit)
        val source = body.source()
        val ok = source.request(limit + 1)
        if (ok && limit == maxBodyBytes) throw ResponseTooLarge(limit)
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return if (ok) source.buffer.readString(limit, charset) else source.buffer.readString(charset)
    }

    companion object {
        /** Teto por resposta: as maiores legítimas (listas de tokens CoinGecko) têm ~3 MB. */
        const val MAX_BODY_BYTES = 16L * 1024 * 1024

        /** Retry-After em segundos ou data HTTP (RFC 9110). Devolve ms a esperar, ou null. */
        fun parseRetryAfterMs(v: String?, now: Long): Long? {
            val s = v?.trim().orEmpty()
            if (s.isEmpty()) return null
            s.toLongOrNull()?.let { return (it.coerceAtLeast(0) * 1000) }
            val t = runCatching { java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return null
            return (t - now).coerceAtLeast(0)
        }

        fun defaultClient(connectSec: Int = 10, readSec: Int = 20, callSec: Int = 30): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(connectSec.toLong().coerceIn(1, 120), TimeUnit.SECONDS)
            .readTimeout(readSec.toLong().coerceIn(1, 120), TimeUnit.SECONDS)
            .callTimeout(callSec.toLong().coerceIn(1, 120), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followSslRedirects(true)
            .followRedirects(true)
            .dns(PublicOnlyDns())
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 8 })
            // Confere o IP REAL da conexão (IP literal, redirecionamento ou DNS rebinding) antes de enviar a requisição.
            .addNetworkInterceptor { ch ->
                val addr = (ch.connection()?.route()?.socketAddress)?.address
                if (addr != null && UrlGuard.isInternal(addr)) throw IOException("Destino interno bloqueado (${ch.request().url.host})")
                ch.proceed(ch.request())
            }
            .addInterceptor { ch ->
                if (UrlGuard.isBlockedHostName(ch.request().url.host)) throw IOException("Host interno bloqueado: ${ch.request().url.host}")
                ch.proceed(ch.request())
            }
            .addInterceptor { ch -> ch.proceed(ch.request().newBuilder().header("User-Agent", "CoinPortracker/5.0 (Android; watch-only)").build()) }
            .build()
    }
}

/** Executa a chamada de forma cancelável: cancelar a coroutine cancela o socket do OkHttp. */
private suspend fun <T> await(call: Call, onResponse: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            val r = try { response.use(onResponse) } catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e); return }
            if (cont.isActive) cont.resume(r)
        }
    })
}

/**
 * DNS que recusa endereços internos (loopback, rede local, link-local, CGNAT, multicast…), inclusive
 * quando um nome público resolve para eles (DNS rebinding). Assim um endpoint digitado ou importado na
 * tela Dev nunca alcança o próprio aparelho nem a rede local (SSRF). Todos os padrões são públicos.
 */
class PublicOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (UrlGuard.isBlockedHostName(hostname)) throw UnknownHostException("Host interno bloqueado: $hostname")
        val all = delegate.lookup(hostname)
        val pub = all.filter { !UrlGuard.isInternal(it) }
        if (pub.isEmpty()) throw UnknownHostException("Host resolve só para endereço interno: $hostname")
        return pub
    }
}

/** Regras de URL/host compartilhadas pelo validador da tela Dev e pelo DNS. */
object UrlGuard {
    const val MAX_URL_LEN = 2_048

    fun isBlockedHostName(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".home.arpa") || h == "metadata.google.internal") return true
        literal(h)?.let { return isInternal(it) }
        // formas numéricas ambíguas (decimal/hex/octal: "2130706433", "0x7f.1") — nunca legítimas aqui
        if (Regex("^((0x[0-9a-f]*|[0-9]+)\\.?){1,4}$").matches(h)) return true
        return false
    }

    /** IP literal (v4 pontuado ou v6) sem consultar DNS; null se for nome. */
    private fun literal(h: String): InetAddress? {
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
        if (v4 != null) {
            val b = v4.groupValues.drop(1).map { it.toInt() }
            if (b.any { it > 255 }) return null
            return InetAddress.getByAddress(b.map { it.toByte() }.toByteArray())
        }
        if (h.contains(':') && Regex("^[0-9a-f:.%a-z]+$").matches(h)) return runCatching { InetAddress.getByName(h) }.getOrNull()
        return null
    }

    fun isInternal(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xff; val b1 = b[1].toInt() and 0xff
            if (b0 == 0) return true                                   // 0.0.0.0/8
            if (b0 == 100 && b1 in 64..127) return true                // CGNAT 100.64/10
            if (b0 == 192 && b1 == 0 && (b[2].toInt() and 0xff) == 0) return true // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true       // benchmark 198.18/15
            if (b0 >= 240) return true                                 // reservado / broadcast
        }
        if (a is Inet6Address) {
            val b0 = b[0].toInt() and 0xff
            if ((b0 and 0xfe) == 0xfc) return true                     // ULA fc00::/7
            // IPv4 mapeado (::ffff:a.b.c.d) ou compatível
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
            if (mapped) return isInternal(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        }
        return false
    }
}
