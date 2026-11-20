package com.coinportracker.v6

import com.coinportracker.data.HubState
import com.coinportracker.webhost.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class WebHostE2eTest {
    private fun raw(port: Int, req: String): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(req.toByteArray()); s.getOutputStream().flush()
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun real_socket_serves_index_rejects_bad_requests_and_stops() {
        var port = 0
        val auth = WebAuth()
        val router = WebRouter(auth, WebData { _, _ -> "{}" }, assets = { if (it == "index.html") "<!doctype html>".toByteArray() else null }, allowedHosts = { setOf("127.0.0.1:$port") })
        val srv = WebServer(0, router, allowTailscale = { false })
        srv.start(); port = srv.boundPort
        try {
            val ok = raw(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertTrue(ok, ok.startsWith("HTTP/1.1 200"))
            assertTrue(ok.contains("Content-Security-Policy: default-src 'none'"))
            assertTrue(ok.contains("<!doctype html>"))
            assertTrue(raw(port, "GET / HTTP/1.1\r\nHost: attacker.test\r\n\r\n").startsWith("HTTP/1.1 421"))
            assertTrue(raw(port, "GET /api/v1/summary HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-Requested-With: cpt\r\n\r\n").startsWith("HTTP/1.1 401"))
            assertTrue(raw(port, "BREW / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 501"))
        } finally { srv.stop() }
        assertThrows(Exception::class.java) { Socket("127.0.0.1", port).use { it.getOutputStream().write(1) } }
    }

    @Test fun snapshot_masks_values_and_normalizes_chart_when_hidden() {
        val h = HubState(ready = true).let { it.copy(settings = it.settings.copy(hideValues = true)) }
        val s = WebSnapshot.summary(h)
        assertTrue(s, s.contains("\"hidden\":true"))
        assertFalse(s.contains("0,00"))
        val c = WebSnapshot.chart(h, com.coinportracker.data.ChartRange.M1,
            com.coinportracker.ui.ChartData(com.coinportracker.data.ChartRange.M1, listOf(com.coinportracker.onchain.PricePoint(1, 100.0), com.coinportracker.onchain.PricePoint(2, 300.0))))
        assertTrue(c, c.contains("\"normalized\":true") && c.contains("[1,0.000000]") && c.contains("[2,1.000000]") && !c.contains("300"))
        val shown = WebSnapshot.summary(HubState(ready = true))
        assertTrue(shown.contains("\"hidden\":false"))
    }
}
