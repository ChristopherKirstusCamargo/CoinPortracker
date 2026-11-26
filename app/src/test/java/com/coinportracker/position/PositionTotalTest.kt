package com.coinportracker.position

import com.coinportracker.onchain.Aggregator
import com.coinportracker.onchain.HoldingKind
import com.coinportracker.onchain.HoldingSource
import com.coinportracker.onchain.LivePrice
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.TokenHolding
import com.coinportracker.settings.AppSettings
import com.coinportracker.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PositionTotalTest {
    private val now = 1_700_000_000_000L
    private val fresh = now - 60_000L
    private val old = now - 3 * 60 * 60_000L
    private val staleMs = 15 * 60_000L
    private val phantom = HoldingSource(1, "Phantom")
    private val ledger = HoldingSource(2, "Ledger")
    private val stakeAccount = "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"

    private fun bd(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal?) {
        if (actual == null || bd(expected).compareTo(actual) != 0) throw AssertionError("esperado $expected, obtido $actual")
    }

    private fun sol(src: HoldingSource, amount: String, kind: HoldingKind = HoldingKind.NATIVE, key: String = "native") =
        src to TokenHolding(Network.SOLANA, key, "SOL", "Solana", 9, bd(amount), null, "coingecko:solana", kind, true, coingeckoId = "solana")

    private fun lst(src: HoldingSource, symbol: String, amount: String, key: String = symbol.lowercase()) =
        src to TokenHolding(Network.SOLANA, key, symbol, symbol, 9, bd(amount), null, "solana:$key", HoldingKind.LIQUID_STAKING, true)

    private fun agg(rows: List<Pair<HoldingSource, TokenHolding>>, minUsd: Double = 0.0) =
        Aggregator.aggregate(rows, emptyList(), mapOf("coingecko:solana" to LivePrice("coingecko:solana", 100.0, 1.0, 1.0, "SOL", fresh)), minUsd)

    private fun clocks(vararg p: Pair<Long, WalletClock>) = mapOf(*p)
    private fun freshClock(id: Long) = id to WalletClock(fresh, false)

    private fun build(
        rows: List<Pair<HoldingSource, TokenHolding>>,
        anchor: String,
        clocks: Map<Long, WalletClock> = clocks(freshClock(1), freshClock(2)),
        asOf: Map<String, Long> = rows.associate { (s, h) -> PositionAggregator.holdingAsOfKey(s.walletId, h.network.name, h.tokenKey) to fresh },
        rates: List<VerifiedRate> = emptyList(),
        minUsd: Double = 0.0,
    ): PositionTotal {
        val a = agg(rows, minUsd)
        return PositionAggregator.build(anchor, a.assets, a.hidden, clocks, asOf, now, staleMs, rates)!!
    }

    @Test fun stakeAndTwoWalletsSumTheSameUnit() {
        val rows = listOf(
            sol(phantom, "1.5"),
            sol(phantom, "6.5", HoldingKind.STAKED, "stake:$stakeAccount"),
            sol(ledger, "2"),
        )
        val fromLiquid = build(rows, "cg:solana")
        val fromStake = build(rows, "staked:solana")
        eq("10", fromLiquid.total)
        eq("10", fromStake.total)
        assertEquals(fromLiquid.total, fromStake.total)
        assertEquals(2, fromLiquid.walletCount)
        assertEquals(setOf("Phantom", "Ledger"), fromLiquid.lines.map { it.walletName }.toSet())
        assertEquals(1, fromLiquid.lines.count { it.kind == LineKind.STAKED })
        assertEquals(2, fromLiquid.lines.count { it.kind == LineKind.FREE })
        assertTrue(fromLiquid.aside.isEmpty())
        assertFalse(fromLiquid.stale)
        assertFalse(fromLiquid.partial)
        assertFalse(fromLiquid.lines.joinToString().contains(stakeAccount))
    }

    @Test fun staleWalletDoesNotMakeAFreshWalletStaleButTheTotalIsMarked() {
        val rows = listOf(sol(phantom, "4"), sol(ledger, "6", HoldingKind.STAKED, "stake:$stakeAccount"))
        val asOf = mapOf(
            PositionAggregator.holdingAsOfKey(1, "SOLANA", "native") to fresh,
            PositionAggregator.holdingAsOfKey(2, "SOLANA", "stake:$stakeAccount") to old,
        )
        val p = build(rows, "cg:solana", clocks(freshClock(1), 2L to WalletClock(old, false)), asOf)
        eq("10", p.total)
        assertFalse(p.lines.first { it.walletId == 1L }.stale)
        assertTrue(p.lines.first { it.walletId == 2L }.stale)
        assertEquals(PositionAggregator.FRESH_STALE, p.lines.first { it.walletId == 2L }.freshness)
        assertTrue(p.stale)
        assertFalse(p.partial)
    }

    @Test fun keptStakeAndFailedWalletArePartial() {
        val rows = listOf(sol(phantom, "3"), sol(phantom, "7", HoldingKind.STAKED, "stake:$stakeAccount"))
        val asOf = mapOf(
            PositionAggregator.holdingAsOfKey(1, "SOLANA", "native") to fresh,
            PositionAggregator.holdingAsOfKey(1, "SOLANA", "stake:$stakeAccount") to fresh - 5 * 60_000L,
        )
        val kept = build(rows, "cg:solana", clocks(1L to WalletClock(fresh, false)), asOf)
        val stake = kept.lines.first { it.kind == LineKind.STAKED }
        assertTrue(stake.partial)
        assertEquals(PositionAggregator.FRESH_KEPT, stake.freshness)
        assertTrue(kept.partial)
        eq("10", kept.total)

        val failed = build(rows, "cg:solana", clocks(1L to WalletClock(fresh, true)))
        assertTrue(failed.lines.all { it.partial })
        assertEquals(PositionAggregator.FRESH_PARTIAL, failed.lines.first().freshness)
        assertTrue(failed.partial)
    }

    @Test fun liquidStakingWithoutRateStaysOutOfTheTotal() {
        val rows = listOf(sol(phantom, "8"), lst(phantom, "mSOL", "3"), lst(ledger, "jitoSOL", "1.5"))
        val p = build(rows, "cg:solana")
        eq("8", p.total)
        assertEquals(2, p.aside.size)
        assertTrue(p.aside.all { it.counted == null && it.excludedNote == PositionAggregator.NO_RATE })
        assertEquals(setOf("mSOL", "jitoSOL"), p.aside.map { it.symbol }.toSet())
        val own = build(rows, "sym:MSOL")
        eq("3", own.total)
        assertTrue(own.aside.isEmpty())
        assertNotNull(own.footnote)
        assertTrue(own.footnote!!.contains("SOL"))
        assertTrue(own.footnote!!.contains("taxa de conversão"))
    }

    @Test fun verifiedRateConvertsAndABlankOrAddressSourceDoesNot() {
        val rows = listOf(sol(phantom, "8"), lst(phantom, "mSOL", "2"))
        val good = VerifiedRate("mSOL", bd("1.25"), "Marinade")
        val p = build(rows, "cg:solana", rates = listOf(good))
        eq("10.5", p.total)
        assertTrue(p.aside.isEmpty())
        val line = p.lines.first { it.kind == LineKind.LIQUID_STAKING }
        eq("2.5", line.counted)
        eq("2", line.ownAmount)
        assertEquals("Marinade", line.rateSource)
        eq("1.25", line.rate)

        val blank = build(rows, "cg:solana", rates = listOf(VerifiedRate("MSOL", bd("1.25"), "  ")))
        eq("8", blank.total)
        assertEquals(1, blank.aside.size)
        val leaked = build(rows, "cg:solana", rates = listOf(VerifiedRate("mSOL", bd("1.25"), "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2")))
        eq("8", leaked.total)
        val negative = build(rows, "cg:solana", rates = listOf(VerifiedRate("mSOL", bd("-1"), "Marinade")))
        eq("8", negative.total)
    }

    @Test fun stEthIsNotSolAndSpamIsDropped() {
        val steth = phantom to TokenHolding(Network.ETHEREUM, "0xsteth", "stETH", "Lido", 18, bd("0.75"), null, "coingecko:staked-ether", HoldingKind.LIQUID_STAKING, true, coingeckoId = "staked-ether")
        val eth = phantom to TokenHolding(Network.ETHEREUM, "native", "ETH", "Ether", 18, bd("1"), null, "coingecko:ethereum", HoldingKind.NATIVE, true, coingeckoId = "ethereum")
        val spam = ledger to TokenHolding(Network.SOLANA, "scam", "mSOL", "mSOL", 9, bd("99"), null, "solana:scam", HoldingKind.LIQUID_STAKING, false, flaggedScam = true)
        val rows = listOf(sol(phantom, "4"), steth, eth, spam)
        val solPos = build(rows, "cg:solana")
        eq("4", solPos.total)
        assertTrue(solPos.aside.none { it.symbol == "stETH" || it.ownAmount.compareTo(bd("99")) == 0 })
        val ethPos = build(rows, "cg:ethereum")
        eq("1", ethPos.total)
        assertEquals(1, ethPos.aside.size)
        assertEquals("stETH", ethPos.aside.single().symbol)
    }

    @Test fun hiddenSmallLiquidStakeIsStillListed() {
        val rows = listOf(sol(phantom, "5"), lst(phantom, "mSOL", "0.0001"))
        val p = build(rows, "cg:solana", minUsd = 1.0)
        eq("5", p.total)
        assertEquals("mSOL", p.aside.single().symbol)
    }

    @Test fun unknownLiquidTokenIsItsOwnPosition() {
        val foo = phantom to TokenHolding(Network.SOLANA, "foo", "FOOSOL", "Foo", 9, bd("4"), null, "solana:foo", HoldingKind.LIQUID_STAKING, true)
        val rows = listOf(sol(phantom, "2"), foo)
        val solPos = build(rows, "cg:solana")
        eq("2", solPos.total)
        assertTrue(solPos.aside.isEmpty())
        val own = build(rows, "sym:FOOSOL")
        eq("4", own.total)
        assertNull(own.footnote)
    }

    @Test fun cardNameRejectsAddressesAndSettingsMigrate() {
        assertEquals("Ana", CardName.sanitize("  Ana  "))
        assertEquals("Chris 2", CardName.sanitize("Chris 2"))
        assertEquals("", CardName.sanitize("0x8f3a91c2d4e5f60718293a4b5c6d7e8f9a0b1c2d"))
        assertEquals("", CardName.sanitize(stakeAccount))
        assertEquals("", CardName.sanitize("Ana\n0x8f3a91c2d4e5f607"))
        assertEquals("", SettingsRepository.fromMap(emptyMap()).positionCardName)
        val old = SettingsRepository.toMap(AppSettings(positionCardName = "Ana")) - "posName"
        assertEquals("", SettingsRepository.fromMap(old).positionCardName)
        assertEquals("Ana", SettingsRepository.fromMap(SettingsRepository.toMap(AppSettings(positionCardName = "Ana"))).positionCardName)
        assertEquals("", SettingsRepository.fromMap(mapOf("posName" to stakeAccount)).positionCardName)
        assertTrue(SettingsRepository.toMap(AppSettings()).containsKey("posName"))
    }
}
