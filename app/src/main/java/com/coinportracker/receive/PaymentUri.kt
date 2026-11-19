package com.coinportracker.receive

import com.coinportracker.onchain.ChainFamily
import com.coinportracker.onchain.Network

/**
 * Monta o texto do QR para receber. O botão copiar sempre usa o endereço cru;
 * o QR usa o esquema padrão quando ele é amplamente reconhecido, para a carteira
 * do pagador abrir na rede certa (EIP-681 no EVM, BIP-21 no Bitcoin, etc.).
 * Onde não há esquema consolidado, o QR leva só o endereço.
 */
object PaymentUri {
    fun payload(network: Network, address: String): String {
        val a = address.trim()
        if (a.isEmpty()) return ""
        return when (network) {
            Network.BITCOIN -> "bitcoin:$a"
            Network.LITECOIN -> "litecoin:$a"
            Network.DOGECOIN -> "dogecoin:$a"
            Network.SOLANA -> "solana:$a"
            Network.STELLAR -> "web+stellar:pay?destination=$a"
            // ton://transfer exige a forma "url-safe" do endereço (base64 com - e _ em vez de + e /)
            Network.TON -> "ton://transfer/" + a.replace('+', '-').replace('/', '_')
            Network.CARDANO -> "web+cardano://addr/$a"
            else -> when {
                network.family == ChainFamily.EVM && network.chainId != null ->
                    "ethereum:$a@${network.chainId}"
                else -> a // Tron, XRP, Cosmos, Near, Sui, Aptos: sem esquema consolidado
            }
        }
    }
}
