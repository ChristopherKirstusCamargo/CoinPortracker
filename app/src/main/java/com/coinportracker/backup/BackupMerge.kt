package com.coinportracker.backup

import com.coinportracker.database.*

/**
 * v6.0 — restauração SEM apagar nada: cada registro do backup entra só se ainda não existe no app.
 * Carteiras são reconhecidas pela rede + endereço (ou nome + tipo, nas manuais); os IDs do backup são remapeados para os
 * do aparelho em tudo que aponta para eles (operações, histórico, saldos, staking). Funções puras, testadas na JVM.
 */
object BackupMerge {
    private val EVM_ADDR = Regex("0x[0-9a-fA-F]{40}")

    /** Identidade estável de uma carteira (endereço EVM sem diferenciar maiúsculas; os demais exatos). */
    fun walletKey(w: WalletEntity): String {
        val a = w.address?.trim()
        return if (!a.isNullOrEmpty()) "addr|${w.chain}|${if (EVM_ADDR.matches(a)) a.lowercase() else a}" else "manual|${w.type}|${w.name.trim().lowercase()}"
    }

    /** id do backup → id existente no aparelho (as que faltam ficam de fora e devem ser inseridas). */
    fun matchWallets(current: List<WalletEntity>, backup: List<WalletEntity>): Map<Long, Long> {
        val byKey = current.associateBy { walletKey(it) }
        return backup.mapNotNull { b -> byKey[walletKey(b)]?.let { b.id to it.id } }.toMap()
    }

    fun categoryKey(c: CategoryEntity) = c.name.trim().lowercase()
    fun networkKey(n: EvmNetworkEntity) = n.chainId.toString()

    /** Operação já existente: mesma impressão digital (quando há) ou mesmo tipo, data, ativo e quantidade. */
    fun txKey(t: TransactionEntity) = t.fingerprint.ifBlank { "${t.type}|${t.timestamp}|${t.assetId}|${t.quantity}|${t.toAssetId}|${t.toQuantity}" }
    fun alertKey(a: AlertEntity) = "${a.kind}|${a.assetId}|${a.coingeckoId}|${a.threshold}|${a.currency}"
    fun stakingKey(s: StakingPositionEntity) = "${s.assetId}|${s.onchainAccount}|${s.startDate}|${s.stakedQuantity}"
    fun goalKey(g: GoalEntity) = "${g.name.trim().lowercase()}|${g.kind}|${g.assetId}"
    fun tokenKey(t: EvmTokenEntity) = "${t.networkId}|${t.contract.lowercase()}"

    /** Só o que falta, pela chave natural. */
    fun <T, K> absent(current: List<T>, backup: List<T>, key: (T) -> K): List<T> {
        val have = current.mapTo(HashSet()) { key(it) }
        return backup.filter { have.add(key(it)) }
    }

    fun remapWallet(id: Long?, map: Map<Long, Long>): Long? = id?.let { map[it] }

    /** Linhas do histórico on-chain com o ID de carteira do aparelho (as de carteiras sem correspondência saem). */
    fun remapActivity(rows: List<ChainActivityEntity>, map: Map<Long, Long>) = rows.mapNotNull { r -> map[r.walletId]?.let { r.copy(walletId = it) } }
    fun remapHoldings(rows: List<ChainHoldingEntity>, map: Map<Long, Long>) = rows.mapNotNull { r -> map[r.walletId]?.let { r.copy(walletId = it) } }
    fun remapStates(rows: List<HistoryStateEntity>, map: Map<Long, Long>) = rows.mapNotNull { r -> map[r.walletId]?.let { r.copy(walletId = it) } }

    /** Chaves do cache local que valem a pena restaurar (recompensas de staking, épocas e preços por hora já lidos). */
    val CACHE_KEYS = setOf("stake:rewards:v3", "stake:views:v1", "stake:epochs:v1", "prices:hourly:v3")

    /** Cache de staking com IDs de carteira remapeados (os JSONs guardam "walletId"). */
    fun remapCacheJson(json: String, map: Map<Long, Long>): String =
        Regex("\"walletId\"\\s*:\\s*(-?\\d+)").replace(json) { m -> val id = m.groupValues[1].toLong(); "\"walletId\":${map[id] ?: id}" }
}
