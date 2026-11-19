package com.coinportracker.data

import com.coinportracker.database.ChainActivityEntity
import org.junit.Assert.*
import org.junit.Test

class ActivityClassifierTest {
    private fun row(dir: String, sym: String, hash: String = "h1", label: String? = null, ts: Long = 1_000L) =
        ChainActivityEntity(1, "SOLANA", hash, ts, dir, sym, "1.0", sym, null, null, false, label, null, null)

    @Test fun swapWhenSameHashHasInAndOut() {
        val list = listOf(row("OUT", "SOL"), row("IN", "USDC"))
        val c = ActivityClassifier.classify(list).single()
        assertEquals(ActivityKind.SWAP, c.kind)
    }

    @Test fun rewardFromLabel() {
        val c = ActivityClassifier.classify(listOf(row("IN", "SOL", label = "Stake reward"))).single()
        assertEquals(ActivityKind.REWARD, c.kind)
    }

    @Test fun simpleInAndOut() {
        val a = ActivityClassifier.classify(listOf(row("IN", "BTC", "a"), row("OUT", "BTC", "b")))
        assertEquals(setOf(ActivityKind.IN, ActivityKind.OUT), a.map { it.kind }.toSet())
    }
}
