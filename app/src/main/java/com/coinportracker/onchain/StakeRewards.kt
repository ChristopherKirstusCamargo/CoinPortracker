package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.math.BigInteger

/** Entrada/saída de lamports em uma conta de stake (criação, depósito extra, saque, split/merge). */
data class StakeFlow(val signature: String, val t: Long?, val lamports: BigInteger)

/**
 * Recompensas de uma conta de stake nativo de SOL.
 * Recompensas de inflação NÃO aparecem como transação: elas aumentam o saldo da conta a cada época.
 * Por isso: recompensas = saldo atual − Σ entradas/saídas de todas as transações da conta.
 */
data class StakeRewardInfo(
    val account: String,
    val currentLamports: BigInteger,
    val flows: List<StakeFlow>,
    val rewardsLamports: BigInteger,
    val activatedAt: Long?,
    /** Histórico de transações da conta lido por completo (sem isso as recompensas são estimativa por época). */
    val complete: Boolean,
    val lastEpoch: Long?,
    val lastEpochRewardLamports: BigInteger?,
    val lastEpochPostLamports: BigInteger?,
    /** Duração medida de uma época (ms). v5: as épocas não têm mais ~2,2 dias; com slots de ~0,215 s duram ~1,1 dia. */
    val epochMs: Long = StakeRewardsReader.EPOCH_MS,
) {
    val principalLamports: BigInteger get() = currentLamports - rewardsLamports
    /**
     * APR pela última época: recompensa líquida (já sem a comissão do validador) ÷ saldo antes dela × épocas por ano.
     * As épocas por ano vêm da duração MEDIDA da época (antes era fixo em 365,25/2,2 ≈ 166, o que dava ~2,2% em vez de ~4,5%).
     */
    val aprPct: Double? get() {
        val r = lastEpochRewardLamports ?: return null; val post = lastEpochPostLamports ?: return null
        val base = (post - r).toDouble().takeIf { it > 0 } ?: return null
        return r.toDouble() / base * epochsPerYear(epochMs) * 100.0
    }

    companion object {
        fun epochsPerYear(epochMs: Long): Double = 365.25 * 86_400_000.0 / epochMs.coerceAtLeast(3_600_000L)
    }
}

class StakeRewardsReader(private val rpc: JsonRpc, private val maxSigs: Int = 100) {
    private val net = Network.SOLANA

    suspend fun read(accounts: Map<String, BigInteger>): Outcome<List<StakeRewardInfo>> {
        if (accounts.isEmpty()) return Outcome.Ok(emptyList())
        val epochInfo = rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getEpochInfo", "[]")
        val epoch = (epochInfo as? Outcome.Ok)?.value?.o()?.get("epoch").s()?.toLongOrNull()
        val slotsInEpoch = (epochInfo as? Outcome.Ok)?.value?.o()?.get("slotsInEpoch").s()?.toLongOrNull()
        val perf = rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getRecentPerformanceSamples", "[30]")
        val epochMs = measuredEpochMs(slotsInEpoch, (perf as? Outcome.Ok)?.value)
        val inflation = if (epoch != null) {
            val keys = accounts.keys.joinToString(",") { "\"$it\"" }
            (rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getInflationReward", """[[${keys}],{"epoch":${epoch - 1}}]""") as? Outcome.Ok)?.value?.let { parseInflation(it, accounts.keys.toList()) }
        } else null
        // época de ativação de cada conta (para estimar quando o histórico não puder ser lido por completo)
        val keysArr = accounts.keys.joinToString(",") { "\"$it\"" }
        val activation = (rpc.call(com.coinportracker.dev.DevSources.rpcs(net), "getMultipleAccounts", """[[$keysArr],{"encoding":"jsonParsed"}]""") as? Outcome.Ok)?.value?.let { parseActivation(it, accounts.keys.toList()) }.orEmpty()
        val out = ArrayList<StakeRewardInfo>()
        for ((acct, lamports) in accounts) {
            // histórico completo só no RPC oficial (RPCs alternativos guardam poucos dias)
            val sigs = rpc.call(listOf(com.coinportracker.dev.DevSources.rpcs(net).first()), "getSignaturesForAddress", """["$acct",{"limit":$maxSigs}]""")
            if (sigs is Outcome.Err) return Outcome.Err(sigs.message)
            val list = (sigs as Outcome.Ok).value.a().orEmpty().mapNotNull { it.o() }
            val flows = ArrayList<StakeFlow>()
            var createdSeen = false
            for (s in list) {
                val sig = s["signature"].s() ?: continue
                if (s["err"] != null && s["err"] !is JsonNull) continue
                val time = s["blockTime"].s()?.toLongOrNull()?.times(1000)
                val txv = rpc.getTransaction(com.coinportracker.dev.DevSources.rpcs(net), sig) ?: continue
                val d = lamportDelta(txv, acct) ?: continue
                if (preBalance(txv, acct)?.signum() == 0) createdSeen = true
                if (d.signum() != 0) flows += StakeFlow(sig, time, d)
            }
            // completo = lemos desde a criação da conta (saldo anterior zero)
            val complete = list.size < maxSigs && createdSeen
            val net = flows.fold(BigInteger.ZERO) { a, f -> a + f.lamports }
            val inf = inflation?.get(acct)
            val actEpoch = activation[acct]
            val rewards = if (complete) (lamports - net).max(BigInteger.ZERO)
                else estimateRewards(lamports, inf, epoch, actEpoch)
            val activatedAt = if (complete) flows.mapNotNull { it.t }.minOrNull()
                else if (epoch != null && actEpoch != null) System.currentTimeMillis() - ((epoch - actEpoch) * epochMs) else null
            out += StakeRewardInfo(acct, lamports, if (complete) flows.sortedBy { it.t ?: 0 } else emptyList(), rewards, activatedAt, complete,
                epoch?.minus(1), inf?.first, inf?.second, epochMs)
        }
        return Outcome.Ok(out)
    }

    companion object {
        /** Valor de reserva quando a duração não pode ser medida (432.000 slots × ~0,4 s ≈ 2 dias). */
        const val EPOCH_MS = 2L * 86_400_000L

        /**
         * Duração da época = slots por época × segundos por slot medidos (getRecentPerformanceSamples).
         * Fora de uma faixa plausível (6 h a 4 dias), usa [EPOCH_MS].
         */
        fun measuredEpochMs(slotsInEpoch: Long?, perf: JsonElement?): Long {
            val samples = perf.a().orEmpty().mapNotNull { it.o() }
            val slots = samples.sumOf { it["numSlots"].s()?.toLongOrNull() ?: 0L }
            val secs = samples.sumOf { it["samplePeriodSecs"].s()?.toLongOrNull() ?: 0L }
            if (slotsInEpoch == null || slotsInEpoch <= 0 || slots <= 0 || secs <= 0) return EPOCH_MS
            val ms = (slotsInEpoch.toDouble() * secs / slots * 1000).toLong()
            return if (ms in 6L * 3_600_000L..4L * 86_400_000L) ms else EPOCH_MS
        }

        /**
         * Estimativa quando o histórico da conta não pôde ser lido inteiro: taxa por época da última época,
         * composta desde a ativação: recompensas ≈ saldo × (1 − 1/(1+r)^n).
         */
        fun estimateRewards(current: BigInteger, last: Pair<BigInteger, BigInteger>?, epoch: Long?, activationEpoch: Long?): BigInteger {
            if (last == null || epoch == null || activationEpoch == null) return BigInteger.ZERO
            val base = (last.second - last.first).toDouble().takeIf { it > 0 } ?: return BigInteger.ZERO
            val r = last.first.toDouble() / base
            val n = (epoch - activationEpoch - 1).coerceAtLeast(0)
            val share = 1 - 1 / Math.pow(1 + r, n.toDouble())
            return java.math.BigDecimal(current).multiply(java.math.BigDecimal(share)).toBigInteger().max(BigInteger.ZERO)
        }

        fun preBalance(el: JsonElement, account: String): BigInteger? {
            val meta = el.o()?.get("meta").o() ?: return null
            val keys = el.o()?.get("transaction").o()?.get("message").o()?.get("accountKeys").a().orEmpty().map { k -> k.o()?.get("pubkey").s() ?: k.s() }
            val i = keys.indexOf(account).takeIf { it >= 0 } ?: return null
            return meta["preBalances"].a()?.getOrNull(i).s()?.toBigIntegerOrNull()
        }

        /** getMultipleAccounts (jsonParsed) → conta → época de ativação da delegação. */
        fun parseActivation(el: JsonElement, accounts: List<String>): Map<String, Long> {
            val arr = el.o()?.get("value").a() ?: return emptyMap()
            val out = HashMap<String, Long>()
            arr.forEachIndexed { i, v ->
                val e = v.o()?.get("data").o()?.get("parsed").o()?.get("info").o()?.get("stake").o()?.get("delegation").o()?.get("activationEpoch").s()?.toLongOrNull() ?: return@forEachIndexed
                accounts.getOrNull(i)?.let { out[it] = e }
            }
            return out
        }

        /** Variação de lamports de [account] na transação (pós − pré). */
        fun lamportDelta(el: JsonElement, account: String): BigInteger? {
            val meta = el.o()?.get("meta").o() ?: return null
            val keys = el.o()?.get("transaction").o()?.get("message").o()?.get("accountKeys").a().orEmpty().map { k -> k.o()?.get("pubkey").s() ?: k.s() }
            val i = keys.indexOf(account).takeIf { it >= 0 } ?: return null
            val pre = meta["preBalances"].a()?.getOrNull(i).s()?.toBigIntegerOrNull() ?: return null
            val post = meta["postBalances"].a()?.getOrNull(i).s()?.toBigIntegerOrNull() ?: return null
            return post - pre
        }

        /** getInflationReward → conta → (recompensa, saldo pós). */
        fun parseInflation(el: JsonElement, accounts: List<String>): Map<String, Pair<BigInteger, BigInteger>> {
            val arr = el.a() ?: return emptyMap()
            val out = HashMap<String, Pair<BigInteger, BigInteger>>()
            arr.forEachIndexed { i, v ->
                val o = v.o() ?: return@forEachIndexed
                val amt = o["amount"].s()?.toBigIntegerOrNull() ?: return@forEachIndexed
                val post = o["postBalance"].s()?.toBigIntegerOrNull() ?: return@forEachIndexed
                accounts.getOrNull(i)?.let { out[it] = amt to post }
            }
            return out
        }
    }
}
