package com.coinportracker.domain

import java.math.BigDecimal

enum class Currency(val symbol: String, val code: String, val label: String) {
    BRL("R$", "brl", "Real (BRL)"),
    USD("US$", "usd", "Dólar (USD)");

    fun other() = if (this == BRL) USD else BRL
}

enum class CostMethod(val label: String, val description: String) {
    WEIGHTED_AVERAGE("Custo médio ponderado", "O custo de cada unidade vendida é o custo médio de todas as unidades em carteira no momento da venda."),
    FIFO("FIFO (primeiro a entrar, primeiro a sair)", "As unidades vendidas são baixadas na ordem em que foram adquiridas, lote a lote.")
}

enum class TxType(val label: String, val help: String) {
    BUY("Compra", "Compra com dinheiro (BRL/USD). Conta como aporte de capital; a taxa é incorporada ao custo."),
    SELL("Venda", "Venda por dinheiro. Gera lucro/prejuízo realizado e retirada de capital."),
    SWAP("Swap", "Troca de um criptoativo por outro. Não é novo aporte; realiza resultado do ativo entregue se o valor de mercado for informado."),
    DEPOSIT("Depósito", "Entrada de cripto vinda de fora do portfólio rastreado, com custo de aquisição declarado. Conta como aporte."),
    WITHDRAWAL("Saque", "Saída de cripto para fora do portfólio rastreado. Conta como retirada de capital."),
    INTERNAL_TRANSFER("Transferência interna", "Movimento entre suas próprias carteiras. Não é aporte, retirada nem resultado. Só a taxa é despesa."),
    EXTERNAL_TRANSFER("Transferência externa", "Envio a terceiros (pagamento/presente). Sai do portfólio como retirada."),
    REWARD("Recompensa", "Recompensa recebida. Entra como rendimento pelo valor de mercado no recebimento."),
    STAKING("Staking", "Recompensa de staking. Entra como rendimento pelo valor de mercado no recebimento."),
    AIRDROP("Airdrop", "Tokens recebidos gratuitamente. Rendimento pelo valor de mercado informado (ou zero)."),
    NETWORK_FEE("Taxa de rede", "Taxa paga na rede. Despesa: em cripto, consome unidades; em dinheiro, é desembolso."),
    TRADING_FEE("Taxa de negociação", "Taxa avulsa de corretora. Despesa."),
    MANUAL_ADJUSTMENT("Ajuste manual", "Correção de quantidade (positiva ou negativa), sempre identificada como ajuste."),
    INCOME_DISTRIBUTION("Distribuição de rendimentos", "Rendimento distribuído (juros, lending, etc.). Entra como rendimento.");

    val isIncome get() = this == REWARD || this == STAKING || this == AIRDROP || this == INCOME_DISTRIBUTION
    val isFee get() = this == NETWORK_FEE || this == TRADING_FEE
    val needsPrice get() = this == BUY || this == SELL
    val needsToAsset get() = this == SWAP
    val needsToWallet get() = this == INTERNAL_TRANSFER
}

enum class ValidationStatus(val label: String) {
    VALID("Validada"),
    UNVERIFIED("Não verificada"),
    INCOMPLETE("Incompleta"),
    INVALID("Inválida")
}

enum class WalletType(val label: String) {
    COLD("Carteira fria"),
    HOT("Carteira quente"),
    EXCHANGE("Exchange"),
    MANUAL("Conta manual"),
    STAKING("Carteira de staking"),
    WATCH_ONLY("Carteira de observação"),
    OTHER("Outro")
}

enum class Chain(val label: String, val nativeSymbol: String, val isEvm: Boolean, val evmChainId: Long? = null) {
    BITCOIN("Bitcoin", "BTC", false),
    SOLANA("Solana", "SOL", false),
    ETHEREUM("Ethereum", "ETH", true, 1),
    ARBITRUM("Arbitrum One", "ETH", true, 42161),
    BASE("Base", "ETH", true, 8453),
    OPTIMISM("OP Mainnet", "ETH", true, 10),
    POLYGON("Polygon PoS", "POL", true, 137),
    BSC("BNB Smart Chain", "BNB", true, 56),
    EVM_CUSTOM("Outra rede EVM", "", true),
    OTHER("Outra / Não informada", "", false)
}

/** Transação normalizada para o motor contábil (independente de banco de dados). */
data class LedgerTx(
    val id: Long,
    val type: TxType,
    val timestamp: Long,
    val assetId: String,
    /** Quantidade sempre positiva, exceto MANUAL_ADJUSTMENT (pode ser negativa). */
    val quantity: BigDecimal,
    /** Preço por unidade de [assetId] na [quoteCurrency]. */
    val unitPrice: BigDecimal? = null,
    val quoteCurrency: Currency = Currency.BRL,
    val toAssetId: String? = null,
    val toQuantity: BigDecimal? = null,
    /** Taxa em dinheiro, na [quoteCurrency]. */
    val feeFiat: BigDecimal? = null,
    /** Taxa paga em cripto (unidades consumidas da carteira de origem). */
    val feeAssetId: String? = null,
    val feeQuantity: BigDecimal? = null,
    val fromWalletId: Long? = null,
    val toWalletId: Long? = null,
    /** Câmbio BRL por 1 USD na data da operação, quando conhecido. */
    val fxUsdBrl: BigDecimal? = null,
    /** Custo declarado (DEPOSIT / ajuste positivo), na quoteCurrency. */
    val declaredCost: BigDecimal? = null,
)

data class AssetInfo(
    val id: String,
    val symbol: String,
    val name: String,
    val chain: Chain = Chain.OTHER,
    val isStablecoin: Boolean = false,
    val categoryId: Long? = null,
    val providerId: String? = null,
)
