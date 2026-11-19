package com.coinportracker.history

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.onchain.JsonRpc
import com.coinportracker.onchain.StakeRewardsReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger

class HistoryEngineTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val sol = PosId(1, "SOLANA", "native")
    private val usdc = PosId(1, "SOLANA", "usdc")

    private fun book(vararg s: Pair<String, List<Pair<Long, Double>>>, current: Map<String, Double> = emptyMap()) =
        PriceBook(s.associate { (k, v) -> k to v.map { ValuePoint(it.first, it.second) } }, current, now)

    private val solPrice = (0..100).map { it * day to (100.0 + it) } // 100 → 200
    private val usdcPrice = (0..100).map { it * day to 1.0 }

    @Test fun assetOnlyCountsAfterItEnteredTheWallet() {
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 10.0, PosKind.NATIVE, 200.0))
        val mv = listOf(Movement(sol, 50 * day, 10.0, 0.0, "a", FlowKind.DEPOSIT))
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(1, "SOLANA", 50 * day, true)), emptyList(),
            book("coingecko:solana" to solPrice, current = mapOf("coingecko:solana" to 200.0)), 0, now, 101, trimLeadingZeros = false)
        val before = r.points.filter { it.t < 50 * day }
        assertTrue(before.isNotEmpty())
        assertTrue("antes da entrada o valor deve ser zero", before.all { it.usd == 0.0 })
        assertEquals(2000.0, r.points.last().usd, 1e-6)
        assertEquals(0.0, r.points.first().usd, 0.0)
        // aporte de 10 SOL a 150 não é lucro: P&L = 2000 − 0 − 1500 = 500
        assertEquals(500.0, r.pnlUsd!!, 1e-6)
    }

    @Test fun trimsLeadingZerosForAllRange() {
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 10.0, PosKind.NATIVE, 200.0))
        val mv = listOf(Movement(sol, 80 * day, 10.0, 0.0, "a", FlowKind.DEPOSIT))
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(1, "SOLANA", 80 * day, true)), emptyList(), book("coingecko:solana" to solPrice), 0, now, 50)
        assertTrue(r.points.first().t >= 79 * day)
        assertTrue(r.points.drop(1).all { it.usd > 0 })
    }

    @Test fun swapIsNotADepositAndKeepsValueContinuous() {
        val raw = listOf(
            RawMove(1, "SOLANA", "native", 10 * day, 10.0, 0.0, "dep", null, asset = "coingecko:solana"),
            RawMove(1, "SOLANA", "native", 60 * day, -5.0, 0.0, "swap", null, asset = "coingecko:solana"),
            RawMove(1, "SOLANA", "usdc", 60 * day, 800.0, 0.0, "swap", null, asset = "usdc"),
        )
        val mv = HistoryEngine.classify(raw, emptySet())
        assertEquals(FlowKind.DEPOSIT, mv[0].kind); assertEquals(FlowKind.TRADE_OUT, mv[1].kind); assertEquals(FlowKind.TRADE_IN, mv[2].kind)
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 5.0, PosKind.NATIVE, 200.0), Position(usdc, "sym:USDC", "usdc", "USDC", 800.0, PosKind.TOKEN, 1.0))
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(1, "SOLANA", 10 * day, true)), emptyList(), book("coingecko:solana" to solPrice, "usdc" to usdcPrice), 20 * day, now, 81)
        assertEquals(0.0, r.netFlowsUsd, 1e-9)
        val at59 = r.points.last { it.t <= 59 * day }.usd
        val at61 = r.points.first { it.t >= 61 * day }.usd
        assertEquals(10 * 159.0, at59, 15.0)
        assertEquals(5 * 161.0 + 800, at61, 15.0)
    }

    @Test fun internalTransferBetweenOwnWalletsIsNotAFlow() {
        val raw = listOf(
            RawMove(1, "BASE", "native", 10 * day, -1.0, 0.0001, "h", "0xbbb", asset = "coingecko:ethereum"),
            RawMove(2, "BASE", "native", 10 * day, 1.0, 0.0, "h", "0xaaa", asset = "coingecko:ethereum"),
        )
        val mv = HistoryEngine.classify(raw, setOf("0xaaa", "0xbbb"))
        assertTrue(mv.all { it.kind == FlowKind.INTERNAL })
    }

    @Test fun partialHistoryStartsChartWhereDataIsKnown() {
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 10.0, PosKind.NATIVE, 200.0))
        val r = HistoryEngine.reconstruct(pos, emptyList(), listOf(Coverage(1, "SOLANA", 90 * day, false)), emptyList(), book("coingecko:solana" to solPrice), 0, now, 20)
        assertTrue(r.partial)
        assertTrue(r.points.first().t >= 90 * day)
        assertEquals(90 * day, r.reliableFrom)
    }

    @Test fun averagePriceFromInflowsAtMarketPrice() {
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 1.0, PosKind.NATIVE, 200.0))
        val mv = listOf(
            Movement(sol, 10 * day, 1.0, 0.0, "a", FlowKind.DEPOSIT),
            Movement(sol, 30 * day, 1.0, 0.0, "b", FlowKind.DEPOSIT),
            Movement(sol, 40 * day, -1.0, 0.0, "c", FlowKind.WITHDRAW),
        )
        val b = book("coingecko:solana" to solPrice)
        val e = CostEstimator.estimate(pos, mv, emptyList(), listOf(Coverage(1, "SOLANA", 10 * day, true)), { k, t -> b.at(k, t) }, { it }, now)["cg:solana"]!!
        assertEquals(120.0, e.avgUsd, 1e-6)
        assertEquals(Confidence.HIGH, e.confidence)
        assertEquals(0.0, e.realizedUsd, 1e-9)
    }

    @Test fun swapRealizesProfitAtTimeOfTrade() {
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 1.0, PosKind.NATIVE, 200.0))
        val mv = listOf(Movement(sol, 0, 2.0, 0.0, "a", FlowKind.DEPOSIT), Movement(sol, 50 * day, -1.0, 0.0, "s", FlowKind.TRADE_OUT))
        val b = book("coingecko:solana" to solPrice)
        val e = CostEstimator.estimate(pos, mv, emptyList(), listOf(Coverage(1, "SOLANA", 0, true)), { k, t -> b.at(k, t) }, { it }, now)["cg:solana"]!!
        assertEquals(100.0, e.avgUsd, 1e-6)
        assertEquals(50.0, e.realizedUsd, 1e-6)
    }

    @Test fun openingBalanceLowersConfidence() {
        val pos = listOf(Position(sol, "cg:solana", "coingecko:solana", "SOL", 10.0, PosKind.NATIVE, 200.0))
        val mv = listOf(Movement(sol, 95 * day, 2.0, 0.0, "a", FlowKind.DEPOSIT))
        val b = book("coingecko:solana" to solPrice)
        val e = CostEstimator.estimate(pos, mv, emptyList(), listOf(Coverage(1, "SOLANA", 90 * day, false)), { k, t -> b.at(k, t) }, { it }, now)["cg:solana"]!!
        assertEquals(8.0, e.openingQty, 1e-9)
        assertEquals(Confidence.LOW, e.confidence)
        assertEquals((8 * 190.0 + 2 * 195.0) / 10, e.avgUsd, 1e-6)
    }

    @Test fun nativeStakingRewardsCountAsIncomeAndStakeStartsAtDelegation() {
        val stake = PosId(1, "SOLANA", "stake:ACC")
        val inp = HistoryInputs.build(
            positions = listOf(Position(stake, "staked:solana", "coingecko:solana", "SOL", 10.5, PosKind.STAKED, 200.0)),
            activity = listOf(
                ActivityRow(1, "SOLANA", "dep", 10 * day, "IN", 10.0, "native", null, null, false, "coingecko:solana", "SOL"),
                ActivityRow(1, "SOLANA", "del", 20 * day, "OUT", 10.0, "native", null, 0.000005, false, "coingecko:solana", "SOL"),
            ),
            coverage = listOf(Coverage(1, "SOLANA", 10 * day, true)),
            stakes = listOf(StakeInput(1, "ACC", listOf(Triple("del", 20 * day, 10.0)), 0.5, 20 * day)),
            ownAddresses = emptySet(), ledger = emptyList(), ledgerPriceKey = { null }, gonePriceOk = { _, _ -> true }, now = now,
        )
        val del = inp.movements.filter { it.hash == "del" }
        assertTrue("delegação é movimento interno", del.all { it.kind == FlowKind.INTERNAL })
        val b = book("coingecko:solana" to solPrice)
        val stakePos = inp.positions.first { it.id == stake }
        assertEquals(0.0, HistoryEngine.amountAt(stakePos, inp.movements.filter { it.pos == stake }, inp.accruals.firstOrNull(), 19 * day, now), 1e-9)
        assertEquals(10.25, HistoryEngine.amountAt(stakePos, inp.movements.filter { it.pos == stake }, inp.accruals.firstOrNull(), 60 * day, now), 1e-6)
        val e = CostEstimator.estimate(inp.positions, inp.movements, inp.accruals, inp.coverage, { k, t -> b.at(k, t) }, { g -> if (g.startsWith("staked:")) "cg:" + g.removePrefix("staked:") else g }, now)
        val est = e["cg:solana"]!!
        assertEquals(0.5, est.incomeQty, 1e-9)
        assertTrue("rendimento em USD > 0", est.incomeUsd > 0)
        assertEquals((10 * 110.0 + 0.5 * 160.0) / 10.5, est.avgUsd, 1.0)
        val r = HistoryEngine.reconstruct(inp.positions, inp.movements, inp.coverage, inp.accruals, b, 0, now, 101)
        assertTrue(r.incomeUsd > 0)
        assertEquals(10.5 * 200.0, r.points.last().usd, 1e-6)
    }

    @Test fun priceBookInterpolatesAndRespectsEdges() {
        val b = book("k" to listOf(0L to 10.0, day to 20.0), current = mapOf("k" to 30.0))
        assertEquals(15.0, b.at("k", day / 2)!!, 1e-9)
        assertNull(b.at("k", -10 * day))
        assertEquals(30.0, b.at("k", now)!!, 1e-9)
    }

    /** Teste real (opcional): conta de stake pública na mainnet da Solana. ./gradlew testDebugUnitTest -Plive=true */
    @Test fun liveStakeRewardsOnMainnet() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val rpc = JsonRpc(HttpClient())
        val acct = "A1NhRRwpvoRyGktq8BmmHqkyXybqM7NDPJAAfhLX32w4"
        val bal = rpc.call(listOf("https://api.mainnet-beta.solana.com", "https://solana-rpc.publicnode.com"), "getBalance", """["$acct"]""")
        val lamports = ((bal as Outcome.Ok).value as kotlinx.serialization.json.JsonObject)["value"].toString().toBigInteger()
        val r = StakeRewardsReader(rpc, maxSigs = 100).read(mapOf(acct to lamports))
        assertTrue(r.toString(), r is Outcome.Ok)
        val info = (r as Outcome.Ok).value.single()
        println("LIVE stake $acct: saldo=${info.currentLamports} principal=${info.principalLamports} recompensas=${info.rewardsLamports} lamports (" +
            "${info.rewardsLamports.toBigDecimal().movePointLeft(9)} SOL), ativada=${info.activatedAt}, completo=${info.complete}, " +
            "última época=${info.lastEpoch} +${info.lastEpochRewardLamports} APR≈${info.aprPct}")
        assertTrue("recompensas devem ser > 0 numa conta delegada há meses", info.rewardsLamports > BigInteger.ZERO)
        assertEquals(info.currentLamports, info.principalLamports + info.rewardsLamports)
    }

    // ---------- v3.2: desempenho de mercado ----------

    @Test fun depositIsNeverGainOrLossEvenWithPhantomOpeningBalance() {
        // carteira A sem histórico (saldo "fantasma" desde o início) + aporte grande em B; preços parados
        val a = PosId(2, "BITCOIN", "native"); val flat = (0..100).map { it * day to 190.0 }
        val pos = listOf(Position(a, "cg:x", "k:x", "X", 10.0, PosKind.NATIVE, 190.0),
            Position(sol, "cg:solana", "k:s", "SOL", 30.0, PosKind.NATIVE, 255.8))
        val mv = listOf(Movement(sol, 95 * day, 30.0, 0.0, "d", FlowKind.DEPOSIT))
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(2, "BITCOIN", null, false)), emptyList(),
            book("k:x" to flat, "k:s" to (0..100).map { it * day to 255.8 }), 0, now, 101)
        assertEquals(0.0, r.pnlUsd!!, 1e-6)
        assertEquals(0.0, r.pnlPct!!, 1e-9)
        // o método antigo (variação − aportes) daria exatamente 0 aqui, mas com preço do aporte divergente dava −1.643;
        // o principal sobe em degrau no aporte
        assertEquals(r.points.first().usd, r.principalSeries.first(), 1e-6)
        assertEquals(1900.0 + 30 * 255.8, r.principalSeries.last(), 1e-6)
    }

    @Test fun depositPricedDifferentlyDoesNotCreateLoss() {
        // aporte registrado a um preço "da hora" bem acima da série (ex.: fonte de preço diferente): antes virava prejuízo
        val pos = listOf(Position(sol, "cg:solana", "k:s", "SOL", 10.0, PosKind.NATIVE, 100.0))
        val mv = listOf(Movement(sol, 50 * day, 10.0, 0.0, "d", FlowKind.DEPOSIT))
        val series = (0..100).map { it * day to 100.0 }
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(1, "SOLANA", 50 * day, true)), emptyList(), book("k:s" to series), 0, now, 101)
        assertEquals(0.0, r.pnlUsd!!, 1e-6)
    }

    @Test fun timeWeightedReturnIgnoresDepositSize() {
        // 1 SOL de 100→200 (+100%), depois aporte de 99 SOL a 200 e preço parado: retorno do período continua +100%
        val pos = listOf(Position(sol, "cg:solana", "k:s", "SOL", 100.0, PosKind.NATIVE, 200.0))
        val mv = listOf(Movement(sol, 0, 1.0, 0.0, "a", FlowKind.DEPOSIT), Movement(sol, 60 * day, 99.0, 0.0, "b", FlowKind.DEPOSIT))
        val px = (0..100).map { it * day to if (it <= 50) 100.0 + it * 2 else 200.0 }
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(1, "SOLANA", 0, true)), emptyList(), book("k:s" to px), 0, now, 101, trimLeadingZeros = false)
        assertEquals(100.0, r.pnlUsd!!, 1e-6)
        assertEquals(100.0, r.pnlPct!!, 0.5)
    }

    @Test fun priceGapIsAStepNotADiagonal() {
        val px = (0..40).map { it * day to 100.0 } + (80..100).map { it * day to 200.0 }
        val b = book("k" to px)
        assertEquals(100.0, b.at("k", 60 * day)!!, 1e-9)   // dentro da lacuna: mantém o último preço conhecido
        assertEquals(100.0, b.at("k", 20 * day + day / 2)!!, 1e-9)
    }

    @Test fun chartStartsWhenHoldingsStartIgnoringDust() {
        val dust = PosId(1, "SOLANA", "dust")
        val pos = listOf(Position(sol, "cg:solana", "k:s", "SOL", 10.0, PosKind.NATIVE, 100.0), Position(dust, "d", "k:d", "D", 1.0, PosKind.TOKEN, 0.01))
        val mv = listOf(Movement(dust, 1 * day, 1.0, 0.0, "x", FlowKind.DEPOSIT), Movement(sol, 90 * day, 10.0, 0.0, "y", FlowKind.DEPOSIT))
        val r = HistoryEngine.reconstruct(pos, mv, listOf(Coverage(1, "SOLANA", 0, true)), emptyList(),
            book("k:s" to (0..100).map { it * day to 100.0 }, "k:d" to (0..100).map { it * day to 0.01 }), 0, now, 60)
        assertTrue("começa perto do aporte real, não no airdrop", r.points.first().t >= 88 * day)
    }
}
