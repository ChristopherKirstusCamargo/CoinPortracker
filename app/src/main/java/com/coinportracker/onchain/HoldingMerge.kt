package com.coinportracker.onchain

/**
 * Uma leitura parcial não pode apagar saldo que a rede não devolveu.
 * Nativo entra sempre pelo resultado novo (zero real some da lista).
 * Token ou stake só são substituídos quando aquela parte da leitura completou.
 */
object HoldingMerge {
    fun merge(previous: List<TokenHolding>, incoming: List<TokenHolding>, tokensComplete: Boolean, stakesComplete: Boolean): List<TokenHolding> {
        if (tokensComplete && stakesComplete) return incoming
        val fresh = incoming.map { it.tokenKey }.toSet()
        val kept = previous.filter { h ->
            if (h.tokenKey in fresh) return@filter false
            val token = h.kind == HoldingKind.TOKEN || h.kind == HoldingKind.LIQUID_STAKING
            val stake = h.kind == HoldingKind.STAKED
            (token && !tokensComplete) || (stake && !stakesComplete)
        }
        return incoming + kept
    }
}
