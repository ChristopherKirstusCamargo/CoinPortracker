package com.coinportracker.v6

import com.coinportracker.core.Outcome
import com.coinportracker.data.HistoryStatus
import com.coinportracker.data.HistoryText
import com.coinportracker.data.NetHistory
import com.coinportracker.data.NetHistoryState
import com.coinportracker.network.HttpClient
import com.coinportracker.onchain.*
import com.coinportracker.settings.RollRules
import com.coinportracker.ui.screens.ActivityEmpty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** RPC falso: a carteira tem [chain] assinaturas (mais nova primeiro); o oficial pode falhar. */
private class FakeRpc(var chain: MutableList<String>, var officialFails: Boolean = false) : JsonRpc(HttpClient()) {
    val calls = mutableListOf<String>()
    override suspend fun call(urls: List<String>, method: String, params: String): Outcome<JsonElement> {
        calls += params
        if (officialFails && urls.first() == Network.SOLANA.rpcs.first()) {
            if (urls.size == 1) return Outcome.Err("HTTP 429")
        }
        val o = Json.parseToJsonElement(params).jsonArray[1].jsonObject
        val limit = o["limit"]!!.jsonPrimitive.int
        val before = o["before"]?.jsonPrimitive?.content
        val until = o["until"]?.jsonPrimitive?.content
        var list = chain.toList()
        if (before != null) list = list.dropWhile { it != before }.drop(1)
        if (until != null) list = list.takeWhile { it != until }
        val page = list.take(limit)
        return Outcome.Ok(JsonArray(page.map { s -> buildJsonObject { put("signature", s); put("blockTime", 1_700_000_000 + chain.size - chain.indexOf(s)) } }))
    }
    override suspend fun getTransaction(urls: List<String>, sig: String): JsonElement? = null
}

class ActivityHistoryTest {
    private fun scan(rpc: FakeRpc) = SolanaScanner(HttpClient(), rpc)
    private fun sigs(n: Int, prefix: String = "s") = (n downTo 1).map { "$prefix$it" }.toMutableList()

    @Test fun empty_first_read_is_retried_later_root_cause() = runBlocking {
        val rpc = FakeRpc(mutableListOf())
        val first = (scan(rpc).historyPaged("Addr", HistState(), rpc) as Outcome.Ok).value
        assertTrue(first.activity.isEmpty())
        // antes da v6.0 este estado ("começado", sem assinatura) nunca mais lia nada
        rpc.chain = sigs(3)
        val second = (scan(rpc).historyPaged("Addr", first.state, rpc) as Outcome.Ok).value
        assertEquals(listOf("s3", "s2", "s1"), second.activity.map { it.hash })
        assertEquals("s3", second.state.newest["sig"])
    }

    @Test fun forward_read_with_more_than_budget_keeps_a_gap_and_fills_it_next_sync() = runBlocking {
        val rpc = FakeRpc(sigs(5, "old"))
        val st1 = (scan(rpc).historyPaged("Addr", HistState(), rpc) as Outcome.Ok).value.state
        assertTrue(st1.complete)
        rpc.chain = (sigs(250, "new") + sigs(5, "old")).toMutableList()
        val p2 = (scan(rpc).historyPaged("Addr", st1, rpc) as Outcome.Ok).value
        assertEquals(HistLimits.SOL_MAX_TX_PER_SYNC, p2.activity.size)
        assertEquals("new250", p2.state.newest["sig"])
        val p3 = (scan(rpc).historyPaged("Addr", p2.state, rpc) as Outcome.Ok).value
        val read = (p2.activity + p3.activity).map { it.hash }.toSet()
        assertEquals((1..250).map { "new$it" }.toSet(), read.filter { it.startsWith("new") }.toSet())     // nenhum trecho do meio perdido
        assertNull(p3.state.cursors["sig:gap"])
    }

    @Test fun official_failure_on_first_page_uses_alternate_without_declaring_complete() = runBlocking {
        val rpc = FakeRpc(sigs(4), officialFails = true)
        val r = scan(rpc).historyPaged("Addr", HistState(), rpc)
        val p = (r as Outcome.Ok).value
        assertEquals(4, p.activity.size)
        assertFalse(p.state.complete)
    }

    @Test fun batches_are_flushed_incrementally() = runBlocking {
        val rpc = FakeRpc(sigs(45))
        val batches = mutableListOf<Int>()
        scan(rpc).historyPaged("Addr", HistState(), rpc) { batches += it.size }
        assertEquals(listOf(20, 20, 5), batches)
    }

    @Test fun empty_state_logic() {
        val idle = HistoryStatus()
        assertEquals(ActivityEmpty.NO_WALLETS, ActivityEmpty.of(0, true, false, idle))
        assertEquals(ActivityEmpty.LOADING, ActivityEmpty.of(1, true, false, idle.copy(running = true)))
        assertEquals(ActivityEmpty.NOT_READ_YET, ActivityEmpty.of(1, true, false, idle))
        assertEquals(ActivityEmpty.FILTERED, ActivityEmpty.of(1, false, true, idle))
        val failed = idle.copy(lastAt = 1, nets = listOf(NetHistory(1, "SOLANA", NetHistoryState.ERROR, "x")))
        assertEquals(ActivityEmpty.ERROR, ActivityEmpty.of(1, true, false, failed))
        assertEquals(ActivityEmpty.NOTHING_FOUND, ActivityEmpty.of(1, true, false, idle.copy(lastAt = 1, nets = listOf(NetHistory(1, "SOLANA", NetHistoryState.OK)))))
    }

    @Test fun friendly_errors_are_ptbr() {
        assertTrue(HistoryText.friendly("HTTP 429 Too Many Requests").contains("limitou"))
        assertNotEquals("java.net.SocketTimeoutException: timeout", HistoryText.friendly("java.net.SocketTimeoutException: timeout"))
    }

    @Test fun video_clean_note_text() {
        assertNull(RollRules.cleanNoteText(""))
        assertNull(RollRules.cleanNoteText(RollRules.cleanNote(0, 0, 1)))
        val t = RollRules.cleanNoteText(RollRules.cleanNote(2, 5L * 1024 * 1024, 1))!!
        assertTrue(t, t.startsWith("2 vídeos antigos") && t.contains("galeria"))
    }

    @Test fun gif_decoder_refuses_over_budget_and_lastUse() {
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        assertEquals(1, com.coinportracker.media.GifDecoder.decode(gif).frames.size)
        assertThrows(com.coinportracker.media.GifDecoder.TooLarge::class.java) { com.coinportracker.media.GifDecoder.decode(gif, maxTotalPixels = 1) }
        assertArrayEquals(intArrayOf(2, 3, -1, -1), com.coinportracker.media.MediaEnhancer.lastUse(intArrayOf(0, 1, 0, 1)))
    }
}
