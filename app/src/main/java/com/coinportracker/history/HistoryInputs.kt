package com.coinportracker.history

import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.TxType
import com.coinportracker.onchain.SpamFilter

/** Atividade on-chain normalizada (independente do Room, para testes). */
data class ActivityRow(
    val walletId: Long, val network: String, val hash: String, val t: Long?, val direction: String, val amount: Double?,
    val tokenKey: String?, val counterparty: String?, val fee: Double?, val failed: Boolean, val priceKey: String?, val symbol: String,
)

/** Fluxos de uma conta de stake de SOL (criação, depósitos e saques) + recompensas acumuladas. */
data class StakeInput(val walletId: Long, val account: String, val flows: List<Triple<String, Long, Double>>, val rewardsSol: Double, val activatedAt: Long?)

data class Inputs(val positions: List<Position>, val movements: List<Movement>, val coverage: List<Coverage>, val accruals: List<Accrual>)

object HistoryInputs {
    private val REBASING = setOf("STETH", "OETH", "EETH", "AETHWETH", "AETHUSDC", "AETHUSDT", "AETHDAI", "ABASUSDC", "ABASWETH", "AARBUSDC", "AARBWETH", "AOPTUSDC", "APOLUSDC")

    /** Token com saldo que cresce sozinho (rebase): stETH, aTokens da Aave. */
    fun isRebasing(symbol: String, name: String): Boolean = symbol.uppercase() in REBASING || name.contains("Aave", true) && symbol.startsWith("a")

    fun kindOf(kind: String, symbol: String, name: String): PosKind = when {
        kind == "STAKED" -> PosKind.STAKED
        isRebasing(symbol, name) && name.contains("Aave", true) -> PosKind.LENDING
        kind == "LIQUID_STAKING" -> PosKind.LIQUID_STAKING
        name.contains("Uniswap V2", true) || symbol.equals("UNI-V2", true) || symbol.endsWith("-LP", true) || symbol.equals("SLP", true) || name.contains(" LP", true) -> PosKind.LP
        name.contains("Compound", true) && symbol.startsWith("c") -> PosKind.LENDING
        kind == "NATIVE" -> PosKind.NATIVE
        else -> PosKind.TOKEN
    }

    /**
     * Monta as entradas do motor: posições visíveis, movimentos classificados, cobertura do histórico e rendimentos acumulados.
     * [gonePriceOk] decide se um token que já não está na carteira (vendido/enviado) é confiável para entrar no passado.
     */
    fun build(
        positions: List<Position>,
        activity: List<ActivityRow>,
        coverage: List<Coverage>,
        stakes: List<StakeInput>,
        ownAddresses: Set<String>,
        ledger: List<LedgerTx>,
        ledgerPriceKey: (assetId: String) -> String?,
        gonePriceOk: (priceKey: String, symbol: String) -> Boolean,
        now: Long,
    ): Inputs {
        val posKeys = positions.map { it.id.network to it.id.tokenKey }.toSet()
        val raw = ArrayList<RawMove>()
        val gone = LinkedHashMap<PosId, Position>()
        for (a in activity) {
            val tk = a.tokenKey ?: continue
            val t = a.t ?: continue
            if (t > now) continue
            val held = (a.network to tk) in posKeys
            if (!held) {
                val pk = a.priceKey ?: continue
                if (SpamFilter.looksLikeSpam(a.symbol, a.symbol) || !gonePriceOk(pk, a.symbol)) continue
                val id = PosId(a.walletId, a.network, tk)
                if (id !in gone) gone[id] = Position(id, if (pk.startsWith("coingecko:")) "cg:" + pk.removePrefix("coingecko:") else "key:$pk", pk, a.symbol, 0.0, if (tk == "native") PosKind.NATIVE else PosKind.TOKEN, null)
            }
            val evm = a.network != "SOLANA" && a.network != "BITCOIN"
            val amt = if (a.failed && evm) 0.0 else (a.amount ?: 0.0)
            val signed = when (a.direction) { "IN" -> amt; "OUT" -> -amt; else -> 0.0 }
            // Bitcoin/Litecoin (UTXO): o valor gravado já é a variação líquida do saldo (recebido − gasto), com a taxa dentro.
            // Descontar a taxa de novo contava a taxa duas vezes no passado (v5).
            val utxo = a.network == "BITCOIN" || a.network == "LITECOIN"
            val fee = if (tk == "native" && !utxo) (a.fee ?: 0.0) else 0.0
            if (signed == 0.0 && fee == 0.0) continue
            raw += RawMove(a.walletId, a.network, tk, t, signed, fee, a.hash.substringBefore('#'), a.counterparty, asset = a.priceKey ?: tk)
        }
        // contas de stake: entradas/saídas reais; o principal sai da carteira principal na mesma transação (interno)
        val accruals = ArrayList<Accrual>()
        for (s in stakes) {
            val tk = "stake:${s.account}"
            for ((sig, t, sol) in s.flows) raw += RawMove(s.walletId, "SOLANA", tk, t, sol, 0.0, sig, null, asset = "coingecko:solana")
            if (s.rewardsSol > 0 && s.activatedAt != null) accruals += Accrual(PosId(s.walletId, "SOLANA", tk), s.activatedAt, s.rewardsSol)
        }
        // operações manuais (livro): só para carteiras manuais (as on-chain já vêm da blockchain)
        for (tx in ledger) {
            val pk = ledgerPriceKey(tx.assetId)
            val src = tx.fromWalletId ?: tx.toWalletId ?: -1L
            val dst = tx.toWalletId ?: tx.fromWalletId ?: -1L
            fun mv(w: Long, asset: String, d: Double, kind: FlowKind) {
                val tk = "manual:$asset"
                if (("MANUAL" to tk) !in posKeys && ledgerPriceKey(asset) == null) return
                raw += RawMove(w, "MANUAL", tk, tx.timestamp, d, 0.0, "ledger:${tx.id}", null, reward = kind == FlowKind.REWARD, asset = (ledgerPriceKey(asset) ?: tk) + if (kind == FlowKind.INTERNAL) "" else "#${tx.id}:${kind.name}")
            }
            val q = tx.quantity.toDouble()
            when (tx.type) {
                TxType.BUY, TxType.DEPOSIT -> mv(dst, tx.assetId, q, FlowKind.DEPOSIT)
                TxType.REWARD, TxType.STAKING, TxType.AIRDROP, TxType.INCOME_DISTRIBUTION -> mv(dst, tx.assetId, q, FlowKind.REWARD)
                TxType.SELL, TxType.WITHDRAWAL, TxType.EXTERNAL_TRANSFER -> mv(src, tx.assetId, -q, FlowKind.WITHDRAW)
                TxType.MANUAL_ADJUSTMENT -> mv(dst, tx.assetId, q, if (q >= 0) FlowKind.DEPOSIT else FlowKind.WITHDRAW)
                TxType.SWAP -> { mv(src, tx.assetId, -q, FlowKind.TRADE_OUT); tx.toAssetId?.let { to -> mv(dst, to, tx.toQuantity?.toDouble() ?: 0.0, FlowKind.TRADE_IN) } }
                TxType.INTERNAL_TRANSFER -> { mv(src, tx.assetId, -q, FlowKind.INTERNAL); mv(dst, tx.assetId, q, FlowKind.INTERNAL) }
                else -> {}
            }
            if (tx.feeAssetId != null && tx.feeQuantity != null) mv(src, tx.feeAssetId, -tx.feeQuantity.toDouble(), FlowKind.WITHDRAW)
        }
        val classified = HistoryEngine.classify(raw, ownAddresses).map { m ->
            if (m.pos.network != "MANUAL") return@map m
            // operações manuais já trazem o tipo certo no sufixo do ativo
            val r = raw.firstOrNull { it.baseHash == m.hash && it.walletId == m.pos.walletId && it.tokenKey == m.pos.tokenKey && it.t == m.t && it.delta == m.delta }
            val manualKind = r?.asset?.substringAfterLast(':', "")?.let { k -> FlowKind.entries.firstOrNull { it.name == k } }
            if (m.pos.network == "MANUAL" && manualKind != null) m.copy(kind = manualKind) else m
        }
        // tokens que rendem por rebase (stETH, aTokens): crescimento sem transação = rendimento (só com histórico completo)
        val covBy = coverage.associateBy { it.walletId to it.network }
        val byPos = classified.groupBy { it.pos }
        for (p in positions) {
            if (p.kind != PosKind.LENDING && !(p.kind == PosKind.LIQUID_STAKING && isRebasing(p.symbol, ""))) continue
            if (covBy[p.id.walletId to p.id.network]?.complete != true) continue
            val ms = byPos[p.id].orEmpty()
            val net = ms.sumOf { it.delta - it.fee }
            val growth = p.amount - net
            val start = ms.filter { it.delta > 0 }.minOfOrNull { it.t } ?: continue
            if (growth > p.amount * 1e-6) accruals += Accrual(p.id, start, growth)
        }
        return Inputs(positions + gone.values.filter { g -> positions.none { it.id == g.id } }, classified, coverage, accruals)
    }
}
