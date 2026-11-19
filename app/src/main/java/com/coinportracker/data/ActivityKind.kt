package com.coinportracker.data

import com.coinportracker.database.ChainActivityEntity

enum class ActivityKind(val label: String) {
    IN("Entrada"),
    OUT("Saída"),
    SWAP("Swap"),
    REWARD("Rendimento"),
    SELF("Entre contas"),
    OTHER("Outro"),
}

data class ClassifiedActivity(
    val kind: ActivityKind,
    val rows: List<ChainActivityEntity>,
    val primary: ChainActivityEntity,
    val timestamp: Long,
)

/** Classifica o histórico on-chain já gravado (Solana, Bitcoin e demais redes). */
object ActivityClassifier {
    /**
     * [stakeSignatures]: assinaturas das transações das suas contas de stake de SOL (criar, depositar, sacar).
     * v5: movimentos entre contas do próprio usuário viram "Entre contas" (SELF) e nunca entrada/saída:
     *  - mesma transação com o mesmo ativo saindo de uma carteira sua e entrando em outra carteira sua;
     *  - SOL saindo da carteira para a sua conta de stake (ou voltando dela).
     * Antes, delegar SOL aparecia como "Saída" e entrava no Contábil como VENDA (e o saque do stake como compra nova).
     */
    fun classify(rows: List<ChainActivityEntity>, stakeSignatures: Set<String> = emptySet(), keepUndated: Boolean = false): List<ClassifiedActivity> {
        val internal = internalRows(rows, stakeSignatures)
        // v6.0: a Atividade mostra também o que a rede devolveu sem data (no fim, em "Sem data"); o Contábil continua sem elas
        val valid = if (keepUndated) rows else rows.filter { it.timestamp != null }
        val groups = valid.groupBy { "${it.walletId}:${it.network}:${it.hash}" }.values
        val out = ArrayList<ClassifiedActivity>(groups.size)
        for (g in groups) {
            val head = g.maxBy { it.timestamp ?: 0L }
            val ts = head.timestamp ?: if (keepUndated) 0L else continue
            val dirs = g.map { it.direction }.toSet()
            val kind = when {
                g.all { rowKey(it) in internal } -> ActivityKind.SELF
                isReward(g) -> ActivityKind.REWARD
                g.size >= 2 && "IN" in dirs && "OUT" in dirs -> ActivityKind.SWAP
                dirs.singleOrNull() == "SELF" || (dirs == setOf("IN", "OUT") && g.all { it.symbol.equals(head.symbol, true) } && head.direction == "SELF") -> ActivityKind.SELF
                dirs.singleOrNull() == "IN" -> ActivityKind.IN
                dirs.singleOrNull() == "OUT" -> ActivityKind.OUT
                "IN" in dirs && "OUT" !in dirs -> ActivityKind.IN
                "OUT" in dirs && "IN" !in dirs -> ActivityKind.OUT
                else -> ActivityKind.OTHER
            }
            out += ClassifiedActivity(kind, g.sortedBy { it.symbol }, head, ts)
        }
        return out.sortedByDescending { it.timestamp }
    }

    private fun baseHash(r: ChainActivityEntity) = r.hash.substringBefore('#')
    private fun asset(r: ChainActivityEntity) = r.priceKey ?: r.tokenKey ?: r.symbol.uppercase()
    internal fun rowKey(r: ChainActivityEntity) = "${r.walletId}|${r.network}|${r.hash}|${r.direction}"

    /** Linhas que são movimento entre contas do próprio usuário (ver [classify]). */
    internal fun internalRows(rows: List<ChainActivityEntity>, stakeSignatures: Set<String>): Set<String> {
        val out = HashSet<String>()
        for ((h, g) in rows.groupBy { baseHash(it) }) {
            if (h in stakeSignatures) {
                // carteira ↔ conta de stake: só o SOL nativo da carteira
                g.filter { it.network == "SOLANA" && it.tokenKey == "native" && (it.direction == "IN" || it.direction == "OUT") }.forEach { out += rowKey(it) }
            }
            if (g.map { it.walletId }.distinct().size < 2) continue
            for (r in g) {
                if (r.direction != "IN" && r.direction != "OUT") continue
                val opp = if (r.direction == "IN") "OUT" else "IN"
                if (g.any { it.walletId != r.walletId && it.direction == opp && asset(it) == asset(r) }) out += rowKey(r)
            }
        }
        return out
    }

    private fun isReward(g: List<ChainActivityEntity>): Boolean {
        if (g.any { it.direction != "IN" && it.direction != "SELF" }) return false
        return g.any { r ->
            val t = listOfNotNull(r.label, r.counterparty, r.symbol, r.tokenKey).joinToString(" ").lowercase()
            t.contains("reward") || t.contains("recompensa") || t.contains("staking") ||
                t.contains("epoch") || t.contains("stake reward") || (r.label?.contains("stake", true) == true && r.direction == "IN")
        }
    }
}
