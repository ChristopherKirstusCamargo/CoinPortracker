package com.coinportracker.onchain

import java.math.BigDecimal

enum class HoldingKind { NATIVE, TOKEN, STAKED, LIQUID_STAKING }

/** Saldo de um ativo em uma rede, lido diretamente da blockchain. */
data class TokenHolding(
    val network: Network,
    /** "native", contrato EVM em minúsculas, mint SPL, ou "stake:<conta>". */
    val tokenKey: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val amount: BigDecimal,
    val iconUrl: String?,
    /** Chave de preço DefiLlama ("coingecko:solana", "base:0x…", "solana:<mint>"). */
    val priceKey: String?,
    val kind: HoldingKind,
    /** Token conhecido (lista pública/verificado). */
    val verified: Boolean,
    /** Sinal explícito de golpe vindo do indexador (ex.: reputação "scam"). */
    val flaggedScam: Boolean = false,
    val priceUsdHint: Double? = null,
    val coingeckoId: String? = null,
)

enum class Direction { IN, OUT, SELF, CONTRACT }

/** Movimento on-chain normalizado (histórico). */
data class ChainActivity(
    val network: Network,
    val hash: String,
    val timestamp: Long?,
    val direction: Direction,
    val symbol: String,
    val amount: BigDecimal?,
    val tokenKey: String?,
    val counterparty: String?,
    val fee: BigDecimal?,
    val failed: Boolean,
    val label: String?,
    val iconUrl: String? = null,
    val priceKey: String? = null,
)

data class ScanResult(
    val holdings: List<TokenHolding>,
    val activity: List<ChainActivity>,
    val warnings: List<String>,
    val sources: List<String>,
)

/** Metadados de token de uma lista pública. */
data class TokenMeta(val address: String, val symbol: String, val name: String, val decimals: Int, val logo: String?)
