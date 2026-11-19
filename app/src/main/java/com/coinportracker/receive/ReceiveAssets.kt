package com.coinportracker.receive

import com.coinportracker.data.WalletSummary
import com.coinportracker.onchain.Network

/** Um endereço observado, pronto para receber na [network]. */
data class ReceiveAddress(
    val walletId: Long,
    val walletName: String,
    val address: String,
    val network: Network,
)

/** Ativo/rede no passo 1, só o que o usuário já tem no portfólio. */
data class ReceiveAsset(
    val network: Network,
    val addresses: List<ReceiveAddress>,
) {
    val symbol: String get() = network.nativeSymbol
    val label: String get() = network.label
}

/**
 * Monta a lista "Qual ativo deseja receber?".
 *
 * - Só entram carteiras de observação com endereço.
 * - A rede aparece se a carteira tem um endereço compatível (ex.: sem endereço
 *   Stellar, XLM não aparece; no dia em que for adicionado, entra sozinho).
 * - EVM: um endereço vale nas redes EVM; listamos cada rede do [WalletSummary.networks]
 *   (as que o app já associa àquela carteira) para o usuário escolher a rede certa.
 *   Se [networks] vier vazio, caímos nas redes do [AddressKind].
 * - Vários endereços (Bitcoin e outros) vão para o passo 2.
 * - xpub não é suportado neste app: só endereços já adicionados.
 */
object ReceiveAssets {
    fun from(wallets: List<WalletSummary>): List<ReceiveAsset> {
        val byNet = LinkedHashMap<Network, MutableList<ReceiveAddress>>()
        for (w in wallets) {
            if (!w.watch) continue
            val addr = w.address?.trim().orEmpty()
            if (addr.isEmpty()) continue
            val nets = w.networks.ifEmpty { w.kind?.networks.orEmpty() }
            // Defesa: endereço reconhecível de OUTRA rede nunca vira QR desta (ex.: bc1… listado como Solana).
            val compatible = com.coinportracker.onchain.AddressKind.candidates(addr).flatMap { it.networks }.toSet()
            for (n in nets) {
                if (compatible.isNotEmpty() && n !in compatible) continue
                byNet.getOrPut(n) { mutableListOf() }.add(ReceiveAddress(w.id, w.name, addr, n))
            }
        }
        // ordem: valor agregado (soma das carteiras) desc, depois a ordem do enum
        val value = wallets.associate { it.id to it.valueUsd }
        return byNet.entries
            .map { (n, addrs) ->
                val distinct = addrs.distinctBy { it.address.lowercase() to it.walletId }
                ReceiveAsset(n, distinct)
            }
            .sortedWith(
                compareByDescending<ReceiveAsset> { it.addresses.sumOf { a -> value[a.walletId] ?: 0.0 } }
                    .thenBy { it.network.ordinal },
            )
    }
}
