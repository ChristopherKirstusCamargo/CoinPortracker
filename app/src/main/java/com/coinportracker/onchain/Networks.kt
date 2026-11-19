package com.coinportracker.onchain

/** Família de rede: determina o leitor (scanner) usado. */
enum class ChainFamily { EVM, SOLANA, BITCOIN,
    /** v3.2: redes lidas só pelo saldo (Stellar, Tron, XRP, Cardano, Dogecoin, TON, Cosmos, Near, Sui, Aptos) — histórico parcial. */
    ALT }

/**
 * Redes suportadas pela leitura automática de carteiras (watch-only).
 * Todos os endpoints são públicos, gratuitos e sem chave. A lista de RPCs é tentada em ordem (fallback).
 */
enum class Network(
    val label: String,
    val short: String,
    val family: ChainFamily,
    val chainId: Long?,
    val nativeSymbol: String,
    val nativeName: String,
    val nativeDecimals: Int,
    /** Chave de preço DefiLlama do ativo nativo (coingecko:<id>). */
    val nativeCoingeckoId: String,
    /** Prefixo de chave DefiLlama para tokens ("ethereum:0x..."). */
    val llamaChain: String,
    /** Slug da lista pública de tokens do CoinGecko (tokens.coingecko.com/<slug>/all.json). */
    val tokenListSlug: String?,
    val rpcs: List<String>,
    /** Instância Blockscout (API v2) com saldos de tokens, preços e histórico; pode exigir desafio anti-bot em IPs de datacenter. */
    val blockscout: String?,
    val explorerTx: String,
    val explorerAddress: String,
    val color: Long,
    val iconUrl: String,
) {
    ETHEREUM("Ethereum", "ETH", ChainFamily.EVM, 1, "ETH", "Ethereum", 18, "ethereum", "ethereum", "ethereum",
        listOf("https://ethereum-rpc.publicnode.com", "https://eth.drpc.org", "https://cloudflare-eth.com"),
        "https://eth.blockscout.com", "https://etherscan.io/tx/", "https://etherscan.io/address/", 0xFF627EEA,
        "https://assets.coingecko.com/coins/images/279/small/ethereum.png"),
    BASE("Base", "Base", ChainFamily.EVM, 8453, "ETH", "Ethereum", 18, "ethereum", "base", "base",
        listOf("https://base-rpc.publicnode.com", "https://mainnet.base.org"),
        "https://base.blockscout.com", "https://basescan.org/tx/", "https://basescan.org/address/", 0xFF0052FF,
        "https://assets.coingecko.com/asset_platforms/images/131/small/base.png"),
    ARBITRUM("Arbitrum", "Arb", ChainFamily.EVM, 42161, "ETH", "Ethereum", 18, "ethereum", "arbitrum", "arbitrum-one",
        listOf("https://arbitrum-one-rpc.publicnode.com", "https://arb1.arbitrum.io/rpc"),
        "https://arbitrum.blockscout.com", "https://arbiscan.io/tx/", "https://arbiscan.io/address/", 0xFF28A0F0,
        "https://assets.coingecko.com/coins/images/16547/small/arb.jpg"),
    OPTIMISM("Optimism", "OP", ChainFamily.EVM, 10, "ETH", "Ethereum", 18, "ethereum", "optimism", "optimistic-ethereum",
        listOf("https://optimism-rpc.publicnode.com", "https://mainnet.optimism.io"),
        "https://explorer.optimism.io", "https://optimistic.etherscan.io/tx/", "https://optimistic.etherscan.io/address/", 0xFFFF0420,
        "https://assets.coingecko.com/coins/images/25244/small/Optimism.png"),
    POLYGON("Polygon", "POL", ChainFamily.EVM, 137, "POL", "Polygon", 18, "polygon-ecosystem-token", "polygon", "polygon-pos",
        listOf("https://polygon-bor-rpc.publicnode.com", "https://polygon.drpc.org", "https://1rpc.io/matic"),
        "https://polygon.blockscout.com", "https://polygonscan.com/tx/", "https://polygonscan.com/address/", 0xFF8247E5,
        "https://assets.coingecko.com/coins/images/32440/small/polygon.png"),
    BSC("BNB Chain", "BNB", ChainFamily.EVM, 56, "BNB", "BNB", 18, "binancecoin", "bsc", "binance-smart-chain",
        listOf("https://bsc-rpc.publicnode.com", "https://bsc-dataseed.bnbchain.org"),
        null, "https://bscscan.com/tx/", "https://bscscan.com/address/", 0xFFF3BA2F,
        "https://assets.coingecko.com/coins/images/825/small/bnb-icon2_2x.png"),
    SOLANA("Solana", "SOL", ChainFamily.SOLANA, null, "SOL", "Solana", 9, "solana", "solana", null,
        listOf("https://api.mainnet-beta.solana.com", "https://solana-rpc.publicnode.com"),
        null, "https://solscan.io/tx/", "https://solscan.io/account/", 0xFF9945FF,
        "https://assets.coingecko.com/coins/images/4128/small/solana.png"),
    BITCOIN("Bitcoin", "BTC", ChainFamily.BITCOIN, null, "BTC", "Bitcoin", 8, "bitcoin", "bitcoin", null,
        listOf("https://mempool.space/api", "https://blockstream.info/api"),
        null, "https://mempool.space/tx/", "https://mempool.space/address/", 0xFFF7931A,
        "https://assets.coingecko.com/coins/images/1/small/bitcoin.png"),
    // ---- v3.2 ----
    AVALANCHE("Avalanche C-Chain", "AVAX", ChainFamily.EVM, 43114, "AVAX", "Avalanche", 18, "avalanche-2", "avax", "avalanche",
        listOf("https://avalanche-c-chain-rpc.publicnode.com", "https://api.avax.network/ext/bc/C/rpc"),
        null, "https://snowtrace.io/tx/", "https://snowtrace.io/address/", 0xFFE84142, "https://assets.coingecko.com/coins/images/12559/small/Avalanche_Circle_RedWhite_Trans.png"),
    LITECOIN("Litecoin", "LTC", ChainFamily.BITCOIN, null, "LTC", "Litecoin", 8, "litecoin", "litecoin", null,
        listOf("https://litecoinspace.org/api"), null, "https://litecoinspace.org/tx/", "https://litecoinspace.org/address/", 0xFF345D9D, "https://assets.coingecko.com/coins/images/2/small/litecoin.png"),
    DOGECOIN("Dogecoin", "DOGE", ChainFamily.ALT, null, "DOGE", "Dogecoin", 8, "dogecoin", "dogecoin", null,
        listOf("https://dogechain.com/api/v1", "https://api.blockcypher.com/v1/doge/main"), null, "https://dogechain.com/tx/", "https://dogechain.com/address/", 0xFFC2A633, "https://assets.coingecko.com/coins/images/5/small/dogecoin.png"),
    STELLAR("Stellar", "XLM", ChainFamily.ALT, null, "XLM", "Stellar", 7, "stellar", "stellar", null,
        listOf("https://horizon.stellar.org"), null, "https://stellar.expert/explorer/public/tx/", "https://stellar.expert/explorer/public/account/", 0xFF7D00FF, "https://assets.coingecko.com/coins/images/100/small/Stellar_symbol_black_RGB.png"),
    TRON("Tron", "TRX", ChainFamily.ALT, null, "TRX", "TRON", 6, "tron", "tron", null,
        listOf("https://api.trongrid.io"), null, "https://tronscan.org/#/transaction/", "https://tronscan.org/#/address/", 0xFFEB0029, "https://assets.coingecko.com/coins/images/1094/small/tron-logo.png"),
    XRP("XRP Ledger", "XRP", ChainFamily.ALT, null, "XRP", "XRP", 6, "ripple", "ripple", null,
        listOf("https://xrplcluster.com", "https://xrpl.ws"), null, "https://livenet.xrpl.org/transactions/", "https://livenet.xrpl.org/accounts/", 0xFF23292F, "https://assets.coingecko.com/coins/images/44/small/xrp-symbol-white-128.png"),
    CARDANO("Cardano", "ADA", ChainFamily.ALT, null, "ADA", "Cardano", 6, "cardano", "cardano", null,
        listOf("https://api.koios.rest/api/v1"), null, "https://cardanoscan.io/transaction/", "https://cardanoscan.io/address/", 0xFF0033AD, "https://assets.coingecko.com/coins/images/975/small/cardano.png"),
    TON("TON", "TON", ChainFamily.ALT, null, "TON", "Toncoin", 9, "the-open-network", "ton", null,
        listOf("https://toncenter.com/api/v2"), null, "https://tonviewer.com/transaction/", "https://tonviewer.com/", 0xFF0098EA, "https://assets.coingecko.com/coins/images/17980/small/ton_symbol.png"),
    COSMOS("Cosmos Hub", "ATOM", ChainFamily.ALT, null, "ATOM", "Cosmos", 6, "cosmos", "cosmos", null,
        listOf("https://cosmos-rest.publicnode.com", "https://rest.cosmos.directory/cosmoshub"), null, "https://www.mintscan.io/cosmos/tx/", "https://www.mintscan.io/cosmos/address/", 0xFF2E3148, "https://assets.coingecko.com/coins/images/1481/small/cosmos_hub.png"),
    NEAR("Near", "NEAR", ChainFamily.ALT, null, "NEAR", "Near", 24, "near", "near", null,
        listOf("https://free.rpc.fastnear.com", "https://rpc.mainnet.near.org"), null, "https://nearblocks.io/txns/", "https://nearblocks.io/address/", 0xFF00C08B, "https://assets.coingecko.com/coins/images/10365/small/near.jpg"),
    SUI("Sui", "SUI", ChainFamily.ALT, null, "SUI", "Sui", 9, "sui", "sui", null,
        listOf("https://graphql.mainnet.sui.io/graphql"), null, "https://suivision.xyz/txblock/", "https://suivision.xyz/account/", 0xFF4DA2FF, "https://assets.coingecko.com/coins/images/26375/small/sui-ocean-square.png"),
    APTOS("Aptos", "APT", ChainFamily.ALT, null, "APT", "Aptos", 8, "aptos", "aptos", null,
        listOf("https://api.mainnet.aptoslabs.com/v1", "https://aptos-rest.publicnode.com/v1"), null, "https://explorer.aptoslabs.com/txn/", "https://explorer.aptoslabs.com/account/", 0xFF06F7F7, "https://assets.coingecko.com/coins/images/26455/small/aptos_round.png");

    val nativePriceKey get() = "coingecko:$nativeCoingeckoId"

    companion object {
        val evm = entries.filter { it.family == ChainFamily.EVM }
        fun byName(n: String?): Network? = entries.firstOrNull { it.name == n }
    }
}

/** Tipo de carteira observada: endereço EVM vale para todas as redes EVM ao mesmo tempo. */
enum class AddressKind(val label: String, val networks: List<Network>, val stored: String, val about: String, val hasHistory: Boolean) {
    EVM("EVM (Ethereum, Base, Arbitrum, Optimism, Polygon, BNB, Avalanche)", Network.evm, "EVM_MULTI",
        "Saldo nativo + tokens ERC-20 em 7 redes, staking líquido (stETH, cbETH…) e histórico (Avalanche e BNB: só saldo).", true),
    SOLANA("Solana", listOf(Network.SOLANA), "SOLANA", "SOL, todos os tokens SPL/Token-2022, contas de stake nativo e histórico recente.", true),
    BITCOIN("Bitcoin", listOf(Network.BITCOIN), "BITCOIN", "Saldo e histórico do endereço (Esplora / mempool.space).", true),
    LITECOIN("Litecoin", listOf(Network.LITECOIN), "LITECOIN", "Saldo e histórico do endereço (litecoinspace.org).", true),
    DOGECOIN("Dogecoin", listOf(Network.DOGECOIN), "DOGECOIN", "Saldo do endereço. Histórico parcial (não lido).", false),
    STELLAR("Stellar", listOf(Network.STELLAR), "STELLAR", "XLM e ativos com trustline (USDC…) pela Horizon. Histórico parcial.", false),
    TRON("Tron", listOf(Network.TRON), "TRON", "TRX e tokens TRC-20 (USDT, USDC…) pela TronGrid. Histórico parcial.", false),
    XRP("XRP Ledger", listOf(Network.XRP), "XRP", "XRP e trustlines (RLUSD…) pelo XRPL público. Histórico parcial.", false),
    CARDANO("Cardano", listOf(Network.CARDANO), "CARDANO", "Saldo de ADA do endereço (Koios). Histórico parcial.", false),
    TON("TON", listOf(Network.TON), "TON", "Saldo de TON (toncenter). Histórico parcial.", false),
    COSMOS("Cosmos Hub", listOf(Network.COSMOS), "COSMOS", "Saldo de ATOM disponível (não inclui stake). Histórico parcial.", false),
    NEAR("Near", listOf(Network.NEAR), "NEAR", "Saldo de NEAR da conta. Histórico parcial.", false),
    SUI("Sui", listOf(Network.SUI), "SUI", "Saldo de SUI (GraphQL público). Histórico parcial.", false),
    APTOS("Aptos", listOf(Network.APTOS), "APTOS", "Saldo de APT (API pública). Histórico parcial.", false);

    companion object {
        /**
         * Todas as redes possíveis para o endereço, validando checksum quando o formato tem (base58check, bech32,
         * StrKey). Mais de um resultado = ambíguo (ex.: 0x + 64 hex vale para Sui e Aptos) → a tela pede para escolher.
         */
        private val R_EVM = Regex("^0x[0-9a-fA-F]{40}$")
        private val R_H64 = Regex("^0x[0-9a-fA-F]{64}$")
        private val R_ADA = Regex("^addr1[02-9ac-hj-np-z]{50,120}$")
        private val R_NEAR = Regex("^([a-z0-9_-]+\\.)*[a-z0-9_-]+\\.(near|tg)$")
        private val R_B58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
        fun candidates(raw: String): List<AddressKind> {
            val a = raw.trim()
            if (a.isEmpty()) return emptyList()
            val V = com.coinportracker.blockchain.AddressValidator
            val out = ArrayList<AddressKind>()
            if (R_EVM.matches(a)) out += EVM
            if (R_H64.matches(a)) { out += SUI; out += APTOS }
            if (V.isValidBitcoin(a)) out += BITCOIN
            if (V.isValidLitecoin(a)) out += LITECOIN
            if (V.isValidDogecoin(a)) out += DOGECOIN
            if (V.isValidTron(a)) out += TRON
            if (V.isValidXrp(a)) out += XRP
            if (V.isValidStellar(a)) out += STELLAR
            if (R_ADA.matches(a)) out += CARDANO
            if (V.isValidBech32(a, "cosmos")) out += COSMOS
            if (V.isValidTon(a)) out += TON
            if (R_NEAR.matches(a)) out += NEAR // contas implícitas (64 hex) não: parecem chave privada
            if (out.isEmpty() && R_B58.matches(a) && V.base58Decode(a)?.size == 32) out += SOLANA
            return out
        }
        /** Compatível com v1–v3.1: primeiro candidato (ou null). */
        fun detect(raw: String): AddressKind? = candidates(raw).firstOrNull()
        fun fromStored(chain: String?): AddressKind? = entries.firstOrNull { it.stored == chain }
        fun storedName(k: AddressKind) = k.stored
    }
}
