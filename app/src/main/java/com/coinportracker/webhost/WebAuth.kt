package com.coinportracker.webhost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Aparelho pareado (só o hash do token é guardado). */
@Serializable
data class WebDevice(val id: String, val name: String, val ip: String, val pairedAt: Long, val lastSeen: Long, val expiresAt: Long, val tokenHash: String)

/** Pedido de pareamento aguardando sua aprovação no celular. */
data class PendingPair(val id: String, val name: String, val ip: String, val at: Long, val pollHash: String, val approved: Boolean? = null, val tokenForBrowser: String? = null)

@Serializable
data class WebAuthState(val devices: List<WebDevice> = emptyList(), val blocked: List<String> = emptyList())

sealed interface PairResult {
    data class Approved(val token: String, val device: WebDevice) : PairResult
    data class Pending(val pollToken: String, val pendingId: String) : PairResult
    data class Invalid(val attemptsLeft: Int) : PairResult
    data class Locked(val retryAfterSec: Long) : PairResult
    data object NoCode : PairResult
    data object Blocked : PairResult
}

/**
 * v6.0 — autenticação do Web Host. Pareamento por código de 8 caracteres (≈40 bits, 5 min, uso único, invalidado após
 * 20 erros no total); bloqueio por IP após 5 erros (15 min, dobrando a cada reincidência); sessões com token aleatório de
 * 256 bits do qual só o SHA-256 fica salvo; expiração, vínculo opcional ao IP, revogação e bloqueio de aparelhos.
 * Thread-safe (todos os métodos sincronizados). Relógio e aleatoriedade injetáveis para testes.
 */
class WebAuth(
    initial: WebAuthState = WebAuthState(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val rnd: SecureRandom = SecureRandom(),
    private val persist: (WebAuthState) -> Unit = {},
) {
    private var devices = initial.devices.toMutableList()
    private val blocked = initial.blocked.toMutableSet()
    private var code: String? = null
    private var codeExpires = 0L
    private var codeFailures = 0
    private val fails = HashMap<String, IntArray>()       // ip → [falhas, nível]
    private val lockedUntil = HashMap<String, Long>()
    private val pending = LinkedHashMap<String, PendingPair>()
    var sessionHours = 24
    var requireApproval = true
    var bindToIp = true

    companion object {
        const val CODE_LEN = 8
        const val CODE_TTL_MS = 5 * 60_000L
        const val MAX_FAILS_IP = 5
        const val MAX_FAILS_CODE = 20
        const val LOCK_MS = 15 * 60_000L
        const val PENDING_TTL_MS = 2 * 60_000L
        const val MAX_DEVICES = 16
        const val MAX_PENDING = 4
        /** Sem 0/O, 1/I/L e U: fácil de digitar e ler. */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun normalizeCode(s: String) = s.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')
        private fun eq(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
        fun deviceName(ua: String?): String {
            val u = ua.orEmpty()
            val os = when { "Windows" in u -> "Windows"; "Android" in u -> "Android"; "iPhone" in u || "iPad" in u -> "iOS"; "Mac OS" in u -> "macOS"; "CrOS" in u -> "ChromeOS"; "Linux" in u -> "Linux"; else -> "" }
            val br = when { "Edg/" in u -> "Edge"; "OPR/" in u -> "Opera"; "Firefox/" in u -> "Firefox"; "Chrome/" in u -> "Chrome"; "Safari/" in u -> "Safari"; else -> "Navegador" }
            return if (os.isEmpty()) br else "$br no $os"
        }
    }

    private fun token(bytes: Int = 32): String = ByteArray(bytes).also { rnd.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun save() = persist(WebAuthState(devices.toList(), blocked.toList()))

    @Synchronized fun newCode(): String {
        val c = String(CharArray(CODE_LEN) { ALPHABET[rnd.nextInt(ALPHABET.length)] })
        code = c; codeExpires = clock() + CODE_TTL_MS; codeFailures = 0
        return c
    }
    @Synchronized fun currentCode(): Pair<String, Long>? = code?.takeIf { clock() < codeExpires }?.let { it to codeExpires }
    @Synchronized fun clearCode() { code = null; codeExpires = 0 }

    @Synchronized fun isBlocked(ip: String) = ip in blocked

    @Synchronized fun pair(input: String, ip: String, userAgent: String?): PairResult {
        val now = clock()
        if (ip in blocked) return PairResult.Blocked
        lockedUntil[ip]?.let { if (now < it) return PairResult.Locked((it - now + 999) / 1000) else lockedUntil.remove(ip) }
        val c = code?.takeIf { now < codeExpires } ?: return PairResult.NoCode
        if (!eq(normalizeCode(input).take(CODE_LEN * 2), c)) {
            val f = fails.getOrPut(ip) { IntArray(2) }
            f[0]++; codeFailures++
            if (codeFailures >= MAX_FAILS_CODE) clearCode()
            if (f[0] >= MAX_FAILS_IP) { lockedUntil[ip] = now + (LOCK_MS shl f[1].coerceAtMost(6)); f[0] = 0; f[1]++; return PairResult.Locked((LOCK_MS shl (f[1] - 1).coerceAtMost(6)) / 1000) }
            return PairResult.Invalid(MAX_FAILS_IP - f[0])
        }
        clearCode()                    // uso único
        fails.remove(ip)
        val name = deviceName(userAgent)
        if (!requireApproval) { val (t, d) = createSession(name, ip, now); return PairResult.Approved(t, d) }
        pending.values.removeAll { now - it.at > PENDING_TTL_MS }
        while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
        val poll = token(24)
        val p = PendingPair(token(9), name, ip, now, sha256(poll))
        pending[p.id] = p
        return PairResult.Pending(poll, p.id)
    }

    private fun createSession(name: String, ip: String, now: Long): Pair<String, WebDevice> {
        val t = token()
        val d = WebDevice(token(9), name, ip, now, now, now + sessionHours * 3_600_000L, sha256(t))
        devices.removeAll { it.expiresAt <= now }
        while (devices.size >= MAX_DEVICES) devices.removeAt(devices.indices.minBy { devices[it].lastSeen })
        devices.add(d); save()
        return t to d
    }

    @Synchronized fun pendingList(): List<PendingPair> { val now = clock(); pending.values.removeAll { now - it.at > PENDING_TTL_MS }; return pending.values.filter { it.approved == null } }

    @Synchronized fun approve(id: String): Boolean {
        val p = pending[id]?.takeIf { it.approved == null && clock() - it.at <= PENDING_TTL_MS } ?: return false
        val (t, _) = createSession(p.name, p.ip, clock())
        pending[id] = p.copy(approved = true, tokenForBrowser = t)
        return true
    }
    @Synchronized fun deny(id: String): Boolean { val p = pending[id] ?: return false; pending[id] = p.copy(approved = false); return true }

    /** Consulta do navegador aguardando aprovação: devolve o token uma única vez. */
    @Synchronized fun poll(pollToken: String, ip: String): PairResult? {
        val h = sha256(pollToken)
        val e = pending.entries.firstOrNull { eq(it.value.pollHash, h) } ?: return null
        val p = e.value
        if (p.ip != ip) return null
        return when (p.approved) {
            null -> PairResult.Pending(pollToken, p.id)
            false -> { pending.remove(e.key); PairResult.Blocked }
            true -> { pending.remove(e.key); devices.firstOrNull { it.tokenHash == sha256(p.tokenForBrowser!!) }?.let { PairResult.Approved(p.tokenForBrowser!!, it) } }
        }
    }

    /** Sessão válida para este token e IP (atualiza "visto por último" no máximo a cada minuto). */
    @Synchronized fun authenticate(token: String?, ip: String): WebDevice? {
        if (token.isNullOrEmpty() || token.length > 128 || ip in blocked) return null
        val now = clock(); val h = sha256(token)
        val i = devices.indexOfFirst { eq(it.tokenHash, h) }
        if (i < 0) return null
        val d = devices[i]
        if (now >= d.expiresAt) { devices.removeAt(i); save(); return null }
        if (bindToIp && d.ip != ip) return null
        if (now - d.lastSeen > 60_000) { devices[i] = d.copy(lastSeen = now); save() }
        return devices[i]
    }

    @Synchronized fun devices(): List<WebDevice> { val now = clock(); if (devices.removeAll { it.expiresAt <= now }) save(); return devices.toList() }
    @Synchronized fun blockedIps(): List<String> = blocked.sorted()
    @Synchronized fun revoke(id: String) { if (devices.removeAll { it.id == id }) save() }
    @Synchronized fun revokeAll() { devices.clear(); pending.clear(); save() }
    @Synchronized fun block(ip: String) { blocked.add(ip); devices.removeAll { it.ip == ip }; pending.values.removeAll { it.ip == ip }; save() }
    @Synchronized fun unblock(ip: String) { if (blocked.remove(ip)) save() }
    @Synchronized fun logout(token: String?) { if (token != null) { val h = sha256(token); if (devices.removeAll { eq(it.tokenHash, h) }) save() } }
}

/** Balde de fichas por chave (ex.: IP). */
class RateLimiter(private val perMinute: Int, private val burst: Int = perMinute, private val clock: () -> Long = System::currentTimeMillis) {
    private val buckets = HashMap<String, DoubleArray>()
    @Synchronized fun allow(key: String): Boolean {
        val now = clock().toDouble()
        if (buckets.size > 512) buckets.entries.removeAll { now - it.value[1] > 120_000 }
        val b = buckets.getOrPut(key) { doubleArrayOf(burst.toDouble(), now) }
        b[0] = (b[0] + (now - b[1]) * perMinute / 60_000.0).coerceAtMost(burst.toDouble()); b[1] = now
        if (b[0] < 1.0) return false
        b[0] -= 1.0; return true
    }
}

object WebAuthStore {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(s: String?): WebAuthState = s?.let { runCatching { json.decodeFromString(WebAuthState.serializer(), it) }.getOrNull() } ?: WebAuthState()
    fun encode(s: WebAuthState): String = json.encodeToString(WebAuthState.serializer(), s)
}
