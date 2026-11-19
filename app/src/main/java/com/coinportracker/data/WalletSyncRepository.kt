package com.coinportracker.data

import com.coinportracker.blockchain.AddressValidator
import com.coinportracker.blockchain.BitcoinEsploraProvider
import com.coinportracker.blockchain.EvmRpcProvider
import com.coinportracker.blockchain.SolanaRpcProvider
import com.coinportracker.blockchain.SyncResult
import com.coinportracker.core.Outcome
import com.coinportracker.database.AppDatabase
import com.coinportracker.database.OnchainBalanceEntity
import com.coinportracker.database.OnchainTxEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.domain.Chain
import com.coinportracker.domain.TxType
import java.math.BigDecimal

/**
 * Sincronização watch-only: lê saldos públicos e grava como "saldo observado".
 * Nunca altera o livro automaticamente: diferenças são mostradas e o usuário decide reconciliar
 * (o que cria um AJUSTE MANUAL identificado, preservando a auditoria).
 */
class WalletSyncRepository(
    private val db: AppDatabase,
    private val btc: BitcoinEsploraProvider,
    private val sol: SolanaRpcProvider,
    private val evm: EvmRpcProvider,
    private val tx: TransactionRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var lastLimitations: Map<Long, List<String>> = emptyMap(); private set

    suspend fun sync(walletId: Long): Outcome<SyncResult> {
        val w = db.wallets().get(walletId) ?: return Outcome.Err("Carteira não encontrada")
        val addr = w.address?.trim() ?: return Outcome.Err("Carteira sem endereço público.")
        val chain = runCatching { Chain.valueOf(w.chain ?: "") }.getOrNull() ?: return Outcome.Err("Rede não informada.")
        AddressValidator.validate(chain, addr)?.let { return Outcome.Err(it) }
        val r: Outcome<SyncResult> = when {
            chain == Chain.BITCOIN -> btc.sync(addr)
            chain == Chain.SOLANA -> sol.sync(addr)
            chain.isEvm -> {
                val net = w.evmNetworkId?.let { db.evm().network(it) } ?: db.evm().networks().firstOrNull { it.chainId == chain.evmChainId }
                    ?: return Outcome.Err("Rede EVM não configurada.")
                val tokens = db.evm().tokens(net.id).map { EvmRpcProvider.Token(it.contract, it.symbol, it.decimals, it.coingeckoId) }
                evm.sync(net.rpcUrl, net.nativeSymbol, net.nativeCoingeckoId, addr, tokens)
            }
            else -> Outcome.Err("Sincronização não suportada para esta rede.")
        }
        val now = clock()
        when (r) {
            is Outcome.Ok -> {
                val assets = db.assets().all()
                val rows = r.value.balances.map { b ->
                    val assetId = assets.firstOrNull { a -> b.coingeckoHint != null && a.coingeckoId == b.coingeckoHint }?.id
                        ?: assets.firstOrNull { a -> a.contractAddress != null && keyMatches(b.assetKey, a.contractAddress) }?.id
                    OnchainBalanceEntity(walletId, b.assetKey, b.symbol, assetId, b.quantity.toPlainString(), chain.name, b.kind, now, r.value.source)
                }
                db.onchain().replaceBalances(walletId, rows)
                db.onchain().upsertTxs(r.value.txs.map { OnchainTxEntity(walletId, it.hash, it.timestamp, it.direction, it.amount?.toPlainString(), it.symbol, it.fee?.toPlainString(), it.status, now) })
                db.wallets().update(w.copy(lastSyncAt = now, lastSyncError = null))
                lastLimitations = lastLimitations + (walletId to r.value.limitations)
            }
            is Outcome.Err -> db.wallets().update(w.copy(lastSyncError = r.message, lastSyncAt = w.lastSyncAt))
        }
        return r
    }

    /** ERC-20: endereço hexadecimal sem diferenciar maiúsculas; SPL (base58): comparação exata. */
    private fun keyMatches(assetKey: String, contract: String) = when {
        assetKey.startsWith("erc20:") -> assetKey.removePrefix("erc20:").equals(contract.trim(), ignoreCase = true)
        assetKey.startsWith("spl:") -> assetKey.removePrefix("spl:") == contract.trim()
        else -> false
    }

    /** Vincula um saldo observado (ex.: mint SPL ou contrato ERC-20) a um ativo cadastrado; vale para as próximas sincronizações. */
    suspend fun link(walletId: Long, assetKey: String, assetId: String) {
        val asset = db.assets().get(assetId) ?: return
        val contract = assetKey.substringAfter(':', "").takeIf { it.isNotBlank() }
        if (contract != null && asset.contractAddress.isNullOrBlank()) db.assets().upsert(asset.copy(contractAddress = contract))
        db.onchain().allBalances().firstOrNull { it.walletId == walletId && it.assetKey == assetKey }?.let { db.onchain().upsertBalances(listOf(it.copy(assetId = assetId))) }
    }

    /**
     * Cria um ajuste manual para igualar o saldo do livro ao saldo observado na carteira.
     * [unitCost]: custo unitário declarado para entradas (null = custo zero, sinalizado no livro).
     */
    suspend fun reconcile(walletId: Long, assetId: String, ledgerQty: BigDecimal, observedQty: BigDecimal, unitCost: BigDecimal?, currency: String): Long? {
        val diff = observedQty.subtract(ledgerQty)
        if (diff.signum() == 0) return null
        return tx.save(TransactionEntity(
            type = TxType.MANUAL_ADJUSTMENT.name, timestamp = clock(), assetId = assetId, quantity = diff.toPlainString(),
            unitPrice = if (diff.signum() > 0) unitCost?.toPlainString() else null, quoteCurrency = currency,
            fromWalletId = walletId, toWalletId = walletId, notes = "Reconciliação com saldo on-chain observado (${observedQty.toPlainString()}).", source = "RECONCILE",
        ), "Reconciliação on-chain")
    }
}
