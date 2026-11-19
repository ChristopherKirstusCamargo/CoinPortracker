package com.coinportracker.dev

import java.util.concurrent.ConcurrentHashMap

data class SourceHealth(val url: String, val ok: Boolean?, val at: Long, val message: String, val latencyMs: Long? = null)

object DevHealth {
    private val map = ConcurrentHashMap<String, SourceHealth>()
    fun ok(url: String, latencyMs: Long? = null) { map[url] = SourceHealth(url, true, System.currentTimeMillis(), "OK", latencyMs) }
    fun fail(url: String, message: String) { map[url] = SourceHealth(url, false, System.currentTimeMillis(), message) }
    fun all(): List<SourceHealth> = map.values.sortedBy { it.url }
    fun failing(): List<SourceHealth> = all().filter { it.ok == false }
    fun clear() { map.clear() }
}
