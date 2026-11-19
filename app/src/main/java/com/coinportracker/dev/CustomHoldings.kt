package com.coinportracker.dev

import com.coinportracker.onchain.HoldingKind
import com.coinportracker.onchain.JsonRpc
import com.coinportracker.onchain.Network
import com.coinportracker.onchain.TokenHolding
import com.coinportracker.onchain.Abi
import com.coinportracker.core.Outcome
import java.math.BigDecimal

/** Saldos de tokens EVM adicionados nas configurações avançadas (redes já suportadas). */
object CustomHoldings {
    suspend fun tokens(rpc: JsonRpc, net: Network, owner: String): List<TokenHolding> {
        val list = DevSources.customTokens(net.name)
        if (list.isEmpty()) return emptyList()
        val rpcs = DevSources.rpcs(net)
        val addr = owner.trim()
        val out = ArrayList<TokenHolding>()
        for (t in list) {
            val contract = t.contract.trim()
            val data = "0x70a08231" + addr.removePrefix("0x").removePrefix("0X").lowercase().padStart(64, '0')
            val r = rpc.call(rpcs, "eth_call", """[{"to":"$contract","data":"$data"},"latest"]""")
            val wei = when (r) {
                is Outcome.Ok -> runCatching { Abi.hexToBig(r.value.toString().trim('"')) }.getOrNull()
                is Outcome.Err -> null
            } ?: continue
            if (wei.signum() == 0) continue
            val amt = BigDecimal(wei).movePointLeft(t.decimals)
            val key = net.llamaChain + ":" + contract.lowercase()
            out += TokenHolding(net, contract.lowercase(), t.symbol, t.name.ifBlank { t.symbol }, t.decimals, amt, null, key, HoldingKind.TOKEN, verified = true)
        }
        return out
    }
}
