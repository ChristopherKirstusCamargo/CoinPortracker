package com.coinportracker.v6

import com.coinportracker.webhost.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WebHostTest {
    private var now = 1_000_000L
    private fun auth(persist: (WebAuthState) -> Unit = {}) = WebAuth(clock = { now }, persist = persist)

    @Test fun code_is_8_chars_from_unambiguous_alphabet_single_use_and_expires() {
        val a = auth().apply { requireApproval = false }
        val c = a.newCode()
        assertEquals(8, c.length); assertTrue(c.all { it in WebAuth.ALPHABET })
        val ok = a.pair(c.lowercase().chunked(4).joinToString("-"), "192.168.0.5", "Mozilla/5.0 (Windows NT 10.0) Chrome/120")
        assertTrue(ok is PairResult.Approved)
        assertEquals("Chrome no Windows", (ok as PairResult.Approved).device.name)
        assertEquals(PairResult.NoCode, a.pair(c, "192.168.0.6", null))                  // uso único
        val c2 = a.newCode(); now += WebAuth.CODE_TTL_MS + 1
        assertEquals(PairResult.NoCode, a.pair(c2, "192.168.0.5", null))                 // expirado
    }

    @Test fun only_hash_of_token_is_persisted_and_token_authenticates() {
        var saved: WebAuthState? = null
        val a = auth { saved = it }.apply { requireApproval = false }
        val r = a.pair(a.newCode(), "10.0.0.2", null) as PairResult.Approved
        val json = WebAuthStore.encode(saved!!)
        assertFalse(json.contains(r.token))
        assertTrue(json.contains(WebAuth.sha256(r.token)))
        assertNotNull(a.authenticate(r.token, "10.0.0.2"))
        assertNull(a.authenticate(r.token, "10.0.0.3"))                                   // preso ao IP
        assertNull(a.authenticate("x" + r.token, "10.0.0.2"))
        // reabrir com o estado salvo continua valendo
        val b = WebAuth(WebAuthStore.decode(json), clock = { now })
        assertNotNull(b.authenticate(r.token, "10.0.0.2"))
        now += 24 * 3_600_000L + 1
        assertNull(b.authenticate(r.token, "10.0.0.2"))                                   // expirou
    }

    @Test fun lockout_after_5_failures_and_code_invalidated_after_20() {
        val a = auth()
        val c = a.newCode()
        repeat(4) { assertTrue(a.pair("ZZZZZZZZ", "192.168.1.9", null) is PairResult.Invalid) }
        val locked = a.pair("ZZZZZZZZ", "192.168.1.9", null)
        assertTrue(locked is PairResult.Locked)
        assertTrue(a.pair(c, "192.168.1.9", null) is PairResult.Locked)                    // nem o código certo passa
        now += WebAuth.LOCK_MS + 1
        // depois do bloqueio, mais erros de outros IPs até 20 no total invalidam o código
        for (i in 0 until 15) a.pair("ZZZZZZZZ", "192.168.2.${i % 3 + 1}", null)
        assertEquals(PairResult.NoCode, a.pair(c, "192.168.3.3", null))
    }

    @Test fun approval_flow_poll_returns_token_once_and_deny_blocks() {
        val a = auth()
        val p = a.pair(a.newCode(), "192.168.0.7", "Firefox/120 Linux") as PairResult.Pending
        assertTrue(a.poll(p.pollToken, "192.168.0.7") is PairResult.Pending)
        assertNull(a.poll(p.pollToken, "192.168.0.8"))                                    // outro IP não consulta
        assertTrue(a.approve(p.pendingId))
        val ok = a.poll(p.pollToken, "192.168.0.7") as PairResult.Approved
        assertNotNull(a.authenticate(ok.token, "192.168.0.7"))
        assertNull(a.poll(p.pollToken, "192.168.0.7"))                                    // só uma vez
        val p2 = a.pair(a.newCode(), "192.168.0.9", null) as PairResult.Pending
        a.deny(p2.pendingId)
        assertEquals(PairResult.Blocked, a.poll(p2.pollToken, "192.168.0.9"))
    }

    @Test fun revoke_block_and_revokeAll() {
        val a = auth().apply { requireApproval = false }
        val t1 = (a.pair(a.newCode(), "10.0.0.2", null) as PairResult.Approved)
        val t2 = (a.pair(a.newCode(), "10.0.0.3", null) as PairResult.Approved)
        a.revoke(t1.device.id); assertNull(a.authenticate(t1.token, "10.0.0.2"))
        a.block("10.0.0.3"); assertNull(a.authenticate(t2.token, "10.0.0.3"))
        assertEquals(PairResult.Blocked, a.pair(a.newCode(), "10.0.0.3", null))
        a.unblock("10.0.0.3")
        val t3 = (a.pair(a.newCode(), "10.0.0.3", null) as PairResult.Approved)
        a.revokeAll(); assertNull(a.authenticate(t3.token, "10.0.0.3")); assertTrue(a.devices().isEmpty())
    }

    @Test fun rate_limiter_refills() {
        val r = RateLimiter(60, 3) { now }
        repeat(3) { assertTrue(r.allow("ip")) }
        assertFalse(r.allow("ip"))
        now += 1_000; assertTrue(r.allow("ip"))
    }

    private fun req(raw: String) = HttpParser.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    @Test fun parser_limits_and_rejections() {
        val r = req("GET /api/v1/activity?limit=5&q=sol%20x HTTP/1.1\r\nHost: 192.168.0.2:8787\r\nCookie: a=1; cpt_s=TOKEN\r\n\r\n")
        assertEquals("/api/v1/activity", r.path); assertEquals("sol x", r.query["q"]); assertEquals("TOKEN", r.cookie("cpt_s"))
        fun status(raw: String) = try { req(raw); 0 } catch (e: HttpError) { e.status }
        assertEquals(431, status("GET / HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n"))
        assertEquals(413, status("POST /auth/pair HTTP/1.1\r\nContent-Length: 99999\r\n\r\n"))
        assertEquals(501, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("GET /../etc HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))
        assertEquals(501, status("TRACE / HTTP/1.1\r\n\r\n"))
    }

    private val hosts = setOf("192.168.0.2:8787")
    private fun router(a: WebAuth) = WebRouter(a, WebData { e, _ -> if (e == "summary") """{"total":"R$ 1,00"}""" else null },
        assets = { n -> if (n == "index.html") "<html>".toByteArray() else null }, allowedHosts = { hosts }, clock = { now })
    private fun get(path: String, vararg h: Pair<String, String>) = HttpRequest("GET", path, emptyMap(), (mapOf("host" to "192.168.0.2:8787") + h).mapKeys { it.key.lowercase() }, ByteArray(0))

    @Test fun router_security_headers_host_check_and_get_only() {
        val a = auth(); val r = router(a)
        val idx = r.handle(get("/"), "192.168.0.9")
        assertEquals(200, idx.status)
        assertEquals(WebRouter.CSP, idx.headers["Content-Security-Policy"])
        assertEquals("nosniff", idx.headers["X-Content-Type-Options"]); assertEquals("DENY", idx.headers["X-Frame-Options"]); assertEquals("no-store", idx.headers["Cache-Control"])
        assertEquals(421, r.handle(get("/", "host" to "evil.example:8787"), "192.168.0.9").status)       // DNS rebinding
        assertEquals(405, r.handle(HttpRequest("POST", "/api/v1/summary", emptyMap(), mapOf("host" to "192.168.0.2:8787"), ByteArray(0)), "192.168.0.9").status)
        assertEquals(405, r.handle(HttpRequest("DELETE", "/", emptyMap(), mapOf("host" to "192.168.0.2:8787"), ByteArray(0)), "192.168.0.9").status)
    }

    @Test fun router_api_requires_session_and_csrf_header() {
        val a = auth().apply { requireApproval = false }; val r = router(a)
        assertEquals(403, r.handle(get("/api/v1/summary"), "192.168.0.9").status)                          // sem X-Requested-With
        assertEquals(401, r.handle(get("/api/v1/summary", "x-requested-with" to "cpt"), "192.168.0.9").status)
        val code = a.newCode()
        val bad = HttpRequest("POST", "/auth/pair", emptyMap(), mapOf("host" to "192.168.0.2:8787", "origin" to "http://evil.example", "content-type" to "application/json"), """{"code":"$code"}""".toByteArray())
        assertEquals(403, r.handle(bad, "192.168.0.9").status)                                             // CSRF: origem errada
        val pairReq = HttpRequest("POST", "/auth/pair", emptyMap(), mapOf("host" to "192.168.0.2:8787", "origin" to "http://192.168.0.2:8787", "content-type" to "application/json"), """{"code":"$code"}""".toByteArray())
        val ok = r.handle(pairReq, "192.168.0.9")
        assertEquals(200, ok.status)
        val cookie = ok.headers["Set-Cookie"]!!
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
        val token = cookie.substringAfter("cpt_s=").substringBefore(';')
        val s = r.handle(get("/api/v1/summary", "x-requested-with" to "cpt", "cookie" to "cpt_s=$token"), "192.168.0.9")
        assertEquals(200, s.status); assertTrue(String(s.body).contains("R$ 1,00"))
        assertEquals(404, r.handle(get("/api/v1/wallets/delete", "x-requested-with" to "cpt", "cookie" to "cpt_s=$token"), "192.168.0.9").status)
    }

    @Test fun preview_bypasses_network_auth_but_only_for_preview_host() {
        val r = router(auth())
        val pv = HttpRequest("GET", "/api/v1/summary", emptyMap(), mapOf("host" to WebRouter.PREVIEW_HOST), ByteArray(0))
        assertEquals(200, r.handle(pv, "preview", preview = true).status)
        assertEquals(421, r.handle(pv, "192.168.0.9").status)
    }

    @Test fun http_writer_rejects_header_injection() {
        val resp = HttpResponse.json(200, "{}").also { it.headers["X"] = "a\r\nSet-Cookie: x" }
        assertThrows(IllegalArgumentException::class.java) { HttpParser.write(ByteArrayOutputStream(), resp) }
    }

    @Test fun json_escapes_html() {
        assertEquals("\"\\u003cscript\\u003e\\\"x\\\"\\u0026\"", WebJson.str("<script>\"x\"&"))
    }

    @Test fun lan_classification() {
        assertEquals(LanAddress.Kind.WIFI, WebNet.kindOf("wlan0", "192.168.1.20", false))
        assertNull(WebNet.kindOf("rmnet_data0", "10.20.30.40", false))                    // dados móveis: nunca
        assertNull(WebNet.kindOf("wlan0", "8.8.8.8", false))
        assertNull(WebNet.kindOf("tun0", "100.101.1.2", false))
        assertEquals(LanAddress.Kind.TAILSCALE, WebNet.kindOf("tun0", "100.101.1.2", true))
        assertNull(WebNet.kindOf("tun0", "10.8.0.2", true))                               // outras VPNs não
        assertTrue(WebNet.isPrivate("172.20.1.1")); assertFalse(WebNet.isPrivate("172.32.1.1"))
        assertFalse(WebNet.remoteAllowed("100.64.0.1", false)); assertTrue(WebNet.remoteAllowed("100.64.0.1", true))
    }

    @Test fun mdns_query_and_answer() {
        // consulta A para coinportracker.local
        val q = ByteArrayOutputStream().apply { write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)); write(Mdns.encodeName("CoinPortracker.local")); write(byteArrayOf(0, 1, 0x80.toByte(), 1)) }.toByteArray()
        val qs = Mdns.parseQuestions(q)!!
        assertTrue(Mdns.asksForUs(qs)); assertTrue(qs[0].third)
        val ans = Mdns.answer("192.168.0.2")
        assertNull(Mdns.parseQuestions(ans))                                              // resposta não é consulta
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 0, 2), ans.copyOfRange(ans.size - 4, ans.size))
        assertNull(Mdns.parseQuestions(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 12)))  // ponteiro em laço
    }
}
