package com.coinportracker.data

import com.coinportracker.core.Decimals
import com.coinportracker.database.AssetEntity
import com.coinportracker.database.TransactionEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.AssetInfo
import com.coinportracker.domain.Chain
import com.coinportracker.domain.Currency
import com.coinportracker.domain.LedgerTx
import com.coinportracker.domain.TxType
import com.coinportracker.transactions.Fingerprint
import com.coinportracker.transactions.TxRecord
import java.math.BigDecimal

fun String?.dec(): BigDecimal? = this?.takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
fun BigDecimal?.text(): String? = this?.stripTrailingZeros()?.toPlainString()

fun TransactionEntity.toLedger(): LedgerTx = LedgerTx(
    id = id, type = TxType.valueOf(type), timestamp = timestamp, assetId = assetId,
    quantity = quantity.dec() ?: BigDecimal.ZERO, unitPrice = unitPrice.dec(), quoteCurrency = Currency.valueOf(quoteCurrency),
    toAssetId = toAssetId, toQuantity = toQuantity.dec(), feeFiat = feeFiat.dec(), feeAssetId = feeAssetId, feeQuantity = feeQuantity.dec(),
    fromWalletId = fromWalletId, toWalletId = toWalletId, fxUsdBrl = fxUsdBrl.dec(), declaredCost = declaredCost.dec(),
)

fun AssetEntity.info() = AssetInfo(id, symbol, name, runCatching { Chain.valueOf(chain) }.getOrDefault(Chain.OTHER), isStablecoin, categoryId, coingeckoId)

fun TransactionEntity.toRecord(assets: Map<String, AssetEntity>, wallets: Map<Long, WalletEntity>): TxRecord = TxRecord(
    timestamp = timestamp, type = type, asset = assets[assetId]?.symbol ?: assetId, quantity = quantity, unitPrice = unitPrice,
    currency = quoteCurrency, toAsset = toAssetId?.let { assets[it]?.symbol ?: it }, toQuantity = toQuantity, fee = feeFiat,
    feeAsset = feeAssetId?.let { assets[it]?.symbol ?: it }, feeQuantity = feeQuantity,
    fromWallet = fromWalletId?.let { wallets[it]?.name }, toWallet = toWalletId?.let { wallets[it]?.name },
    txHash = txHash, exchange = exchange, notes = notes, fxUsdBrl = fxUsdBrl, declaredCost = declaredCost,
)

/** Impressão digital usando símbolos (mesma forma que a importação usa). */
fun TransactionEntity.fingerprintWith(assets: Map<String, AssetEntity>, wallets: Map<Long, WalletEntity>) = Fingerprint.of(toRecord(assets, wallets))

fun normalizeDecimal(s: String?): String? = Decimals.parse(s)?.text()
