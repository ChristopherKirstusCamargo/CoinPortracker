package com.coinportracker.network

import com.coinportracker.core.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class HttpClientTest {
    private lateinit var server: MockWebServer
    private val noRetry = RatePolicy(minIntervalMs = 0, maxRetries = 0, baseBackoffMs = 1, maxBackoffMs = 10)
    // MockWebServer roda em localhost: nos testes usamos um OkHttp sem o filtro anti-SSRF
    private fun client(maxBody: Long = HttpClient.MAX_BODY_BYTES, sleeps: MutableList<Long> = mutableListOf()) =
        HttpClient(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(), sleeper = { sleeps += it }, maxBodyBytes = maxBody)

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { runCatching { server.shutdown() } }

    @Test fun ok_body() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"a\":1}"))
        val r = client().get("p", server.url("/x").toString(), noRetry)
        assertEquals("{\"a\":1}", (r as Outcome.Ok).value)
    }

    @Test fun invalidUrl_isErrNotException() = runBlocking {
        val r = client().get("p", "not a url", noRetry)
        assertTrue(r is Outcome.Err && !r.retryable)
    }

    @Test fun hugeResponse_rejected() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        val r = client(maxBody = 1_000).get("p", server.url("/big").toString(), noRetry)
        assertTrue(r is Outcome.Err)
        assertTrue((r as Outcome.Err).cause is ResponseTooLarge)
    }

    @Test fun bodyAtLimit_accepted() = runBlocking {
        server.enqueue(MockResponse().setBody("y".repeat(1_000)))
        val r = client(maxBody = 1_000).get("p", server.url("/ok").toString(), noRetry)
        assertEquals(1_000, (r as Outcome.Ok).value.length)
    }

    @Test fun retry429_honorsRetryAfter_thenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setBody("ok"))
        val sleeps = mutableListOf<Long>()
        val r = client(sleeps = sleeps).get("p", server.url("/r").toString(), RatePolicy(0, maxRetries = 2, baseBackoffMs = 10, maxBackoffMs = 5_000))
        assertEquals("ok", (r as Outcome.Ok).value)
        assertTrue(sleeps.contains(2_000L))
    }

    @Test fun retry5xx_withBackoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("fine"))
        val sleeps = mutableListOf<Long>()
        val r = client(sleeps = sleeps).get("p", server.url("/s").toString(), RatePolicy(0, maxRetries = 3, baseBackoffMs = 100, maxBackoffMs = 1_000))
        assertEquals("fine", (r as Outcome.Ok).value)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test fun client4xx_notRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val r = client().get("p", server.url("/n").toString(), RatePolicy(0, maxRetries = 3))
        assertTrue(r is Outcome.Err && !r.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test fun parallelProviders_noRace() = runBlocking {
        repeat(60) { server.enqueue(MockResponse().setBody("b$it")) }
        val c = client()
        val res = (0 until 60).map { i -> async(kotlinx.coroutines.Dispatchers.IO) { c.get("prov$i", server.url("/p$i").toString(), noRetry) } }.awaitAll()
        assertEquals(60, res.count { it is Outcome.Ok })
    }

    @Test fun cancellation_cancelsSocket() = runBlocking {
        server.enqueue(MockResponse().setBody("late").setBodyDelay(10, TimeUnit.SECONDS))
        val c = client()
        val t0 = System.nanoTime()
        val job = launch { c.get("p", server.url("/slow").toString(), noRetry) }
        delay(200); job.cancel(); job.join()
        assertTrue("cancelamento deve ser imediato", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    @Test fun applyTimeouts_keepsConnectionPool() {
        val c = HttpClient()
        val pool = c.currentClient().connectionPool
        c.applyTimeouts(5, 6, 7)
        assertSame(pool, c.currentClient().connectionPool)
        assertEquals(7_000, c.currentClient().callTimeoutMillis)
        val same = c.currentClient(); c.applyTimeouts(5, 6, 7)
        assertSame("mesmos valores não recriam o cliente", same, c.currentClient())
    }

    @Test fun defaultClient_blocksLocalhost() = runBlocking {
        server.enqueue(MockResponse().setBody("secret"))
        val r = HttpClient().get("p", server.url("/x").toString(), noRetry)
        assertTrue(r is Outcome.Err)
        assertEquals(0, server.requestCount)
        val r2 = HttpClient().get("p", "https://127.0.0.1:${server.port}/x", noRetry)
        assertTrue(r2 is Outcome.Err)
    }

    @Test fun retryAfter_httpDate() {
        val now = java.time.ZonedDateTime.parse("Fri, 13 Nov 2026 16:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(30_000L, HttpClient.parseRetryAfterMs("Fri, 13 Nov 2026 16:00:30 GMT", now))
        assertEquals(5_000L, HttpClient.parseRetryAfterMs("5", now))
        assertNull(HttpClient.parseRetryAfterMs("garbage", now))
    }
}

class UrlGuardTest {
    private fun fixed(vararg a: InetAddress) = object : okhttp3.Dns { override fun lookup(hostname: String) = a.toList() }
    @Test fun blocksInternalHosts() {
        listOf("localhost", "LOCALHOST.", "foo.localhost", "printer.local", "127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.169.254",
            "0.0.0.0", "100.64.0.1", "::1", "[::1]", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2130706433", "0x7f000001", "metadata.google.internal")
            .forEach { assertTrue(it, UrlGuard.isBlockedHostName(it)) }
    }
    @Test fun allowsPublicHosts() {
        listOf("api.mainnet-beta.solana.com", "mempool.space", "1.1.1.1", "8.8.8.8", "rpc.example.io", "2606:4700:4700::1111")
            .forEach { assertFalse(it, UrlGuard.isBlockedHostName(it)) }
    }
    @Test fun dnsRebindingFiltered() {
        val dns = PublicOnlyDns(fixed(InetAddress.getByName("127.0.0.1")))
        assertThrows(java.net.UnknownHostException::class.java) { dns.lookup("evil.example") }
        val mixed = PublicOnlyDns(fixed(InetAddress.getByName("10.0.0.1"), InetAddress.getByName("1.1.1.1")))
        assertEquals(listOf(InetAddress.getByName("1.1.1.1")), mixed.lookup("x.example"))
    }
}
