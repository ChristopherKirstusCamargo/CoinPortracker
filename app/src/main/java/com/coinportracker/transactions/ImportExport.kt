package com.coinportracker.transactions

import com.coinportracker.core.Decimals
import com.coinportracker.domain.Currency
import com.coinportracker.domain.TxType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formato portátil de uma operação (import/export/backup). Decimais como texto para não perder precisão. */
@Serializable
data class TxRecord(
    val timestamp: Long,
    val type: String,
    val asset: String,
    val quantity: String,
    val unitPrice: String? = null,
    val currency: String = "BRL",
    val toAsset: String? = null,
    val toQuantity: String? = null,
    val fee: String? = null,
    val feeAsset: String? = null,
    val feeQuantity: String? = null,
    val fromWallet: String? = null,
    val toWallet: String? = null,
    val txHash: String? = null,
    val exchange: String? = null,
    val notes: String? = null,
    val category: String? = null,
    val fxUsdBrl: String? = null,
    val declaredCost: String? = null,
)

data class ParsedRow(
    val line: Int,
    val record: TxRecord?,
    val errors: List<String>,
    val warnings: List<String>,
    val raw: Map<String, String>,
) {
    val valid get() = record != null && errors.isEmpty()
}

object TxValidator {
    fun validate(r: TxRecord): Pair<List<String>, List<String>> {
        val e = ArrayList<String>(); val w = ArrayList<String>()
        val type = parseType(r.type)
        if (type == null) e += "Tipo de operação desconhecido: '${r.type}'."
        if (r.asset.isBlank()) e += "Ativo obrigatório."
        val q = Decimals.parse(r.quantity)
        if (q == null) e += "Quantidade inválida: '${r.quantity}'."
        else if (q.signum() == 0 && type != TxType.NETWORK_FEE && type != TxType.TRADING_FEE) e += "Quantidade não pode ser zero."
        else if (q.signum() < 0 && type != TxType.MANUAL_ADJUSTMENT) e += "Quantidade negativa só em ajuste manual."
        if (r.unitPrice != null && r.unitPrice.isNotBlank()) {
            val p = Decimals.parse(r.unitPrice)
            if (p == null || p.signum() < 0) e += "Preço unitário inválido: '${r.unitPrice}'."
        } else if (type?.needsPrice == true) e += "${type.label} exige preço unitário."
        if (Currency.entries.none { it.name.equals(r.currency, true) }) e += "Moeda inválida: '${r.currency}' (use BRL ou USD)."
        if (type == TxType.SWAP) {
            if (r.toAsset.isNullOrBlank()) e += "Swap exige ativo de destino."
            val tq = Decimals.parse(r.toQuantity)
            if (tq == null || tq.signum() <= 0) e += "Swap exige quantidade de destino positiva."
            if (r.unitPrice.isNullOrBlank()) w += "Swap sem valor de mercado: o custo será transferido sem realizar resultado."
        }
        if (type == TxType.INTERNAL_TRANSFER && (r.fromWallet.isNullOrBlank() || r.toWallet.isNullOrBlank())) e += "Transferência interna exige carteira de origem e destino."
        r.fee?.takeIf { it.isNotBlank() }?.let { if (Decimals.parse(it)?.let { f -> f.signum() >= 0 } != true) e += "Taxa inválida: '$it'." }
        r.feeQuantity?.takeIf { it.isNotBlank() }?.let { if (Decimals.parse(it)?.let { f -> f.signum() >= 0 } != true) e += "Quantidade de taxa inválida: '$it'." }
        if (!r.feeQuantity.isNullOrBlank() && r.feeAsset.isNullOrBlank()) e += "Informe o ativo da taxa em cripto."
        r.fxUsdBrl?.takeIf { it.isNotBlank() }?.let { if (Decimals.parse(it)?.let { f -> f.signum() > 0 } != true) e += "Câmbio inválido: '$it'." }
        if (r.timestamp > System.currentTimeMillis() + 86_400_000L) w += "Data no futuro."
        if (type?.isIncome == true && r.unitPrice.isNullOrBlank()) w += "Rendimento sem preço: entrará com valor zero."
        if (type == TxType.DEPOSIT && r.declaredCost.isNullOrBlank() && r.unitPrice.isNullOrBlank()) w += "Depósito sem custo declarado: entrará com custo zero."
        return e to w
    }

    fun parseType(s: String): TxType? {
        val t = s.trim()
        return TxType.entries.firstOrNull { it.name.equals(t, true) || it.label.equals(t, true) }
            ?: when (t.lowercase()) {
                "buy", "compra" -> TxType.BUY
                "sell", "venda" -> TxType.SELL
                "deposit" -> TxType.DEPOSIT
                "withdraw", "withdrawal", "saque" -> TxType.WITHDRAWAL
                "transfer", "transferencia", "transferência" -> TxType.INTERNAL_TRANSFER
                "fee", "taxa" -> TxType.NETWORK_FEE
                "reward", "rewards" -> TxType.REWARD
                "staking reward", "stake" -> TxType.STAKING
                "interest", "income" -> TxType.INCOME_DISTRIBUTION
                else -> null
            }
    }
}

/** Impressão digital para detecção de duplicidade. */
object Fingerprint {
    fun of(r: TxRecord): String {
        val minute = r.timestamp / 60_000L
        fun n(s: String?) = Decimals.parse(s)?.stripTrailingZeros()?.toPlainString() ?: ""
        val hash = r.txHash?.trim()?.lowercase().orEmpty()
        return if (hash.isNotEmpty()) "h:$hash:${r.type.uppercase()}:${r.asset.uppercase()}"
        else listOf(TxValidator.parseType(r.type)?.name ?: r.type, r.asset.uppercase(), n(r.quantity), n(r.unitPrice), r.currency.uppercase(), minute.toString(), r.toAsset?.uppercase().orEmpty(), n(r.toQuantity)).joinToString("|")
    }
}

object CsvCodec {
    val HEADER = listOf("date", "type", "asset", "quantity", "unit_price", "currency", "to_asset", "to_quantity", "fee", "fee_asset", "fee_quantity",
        "from_wallet", "to_wallet", "tx_hash", "exchange", "notes", "category", "fx_usd_brl", "declared_cost")

    private val aliases = mapOf(
        "data" to "date", "timestamp" to "date", "datetime" to "date", "time" to "date",
        "tipo" to "type", "operation" to "type", "operacao" to "type", "operação" to "type",
        "ativo" to "asset", "symbol" to "asset", "simbolo" to "asset", "símbolo" to "asset", "coin" to "asset",
        "quantidade" to "quantity", "amount" to "quantity", "qty" to "quantity",
        "preco" to "unit_price", "preço" to "unit_price", "price" to "unit_price", "preco_unitario" to "unit_price", "preço_unitário" to "unit_price",
        "moeda" to "currency", "fiat" to "currency",
        "taxa" to "fee", "fees" to "fee", "hash" to "tx_hash", "txid" to "tx_hash", "observacoes" to "notes", "observações" to "notes", "notas" to "notes",
        "carteira" to "from_wallet", "wallet" to "from_wallet", "categoria" to "category", "cambio" to "fx_usd_brl", "câmbio" to "fx_usd_brl"
    )

    fun splitLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (q) {
                if (ch == '"') { if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(ch)
            } else when (ch) {
                '"' -> q = true
                delim -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Divide em registros respeitando quebras de linha dentro de aspas. */
    fun records(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(); val sb = StringBuilder(); var q = false; var line = 1; var start = 1
        for (ch in text.removePrefix("\uFEFF")) {
            if (ch == '"') q = !q
            if ((ch == '\n') && !q) { val s = sb.toString().trimEnd('\r'); if (s.isNotBlank()) out += start to s; sb.clear(); line++; start = line; continue }
            if (ch == '\n') line++
            sb.append(ch)
        }
        if (sb.isNotBlank()) out += start to sb.toString().trimEnd('\r')
        return out
    }

    fun parseDate(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        runCatching { return Instant.parse(t).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm")
        for (p in patterns) runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p)).atZone(zone).toInstant().toEpochMilli() }
        for (p in listOf("yyyy-MM-dd", "dd/MM/yyyy")) runCatching { return LocalDate.parse(t, DateTimeFormatter.ofPattern(p)).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    fun parse(text: String): List<ParsedRow> {
        val recs = records(text)
        if (recs.isEmpty()) return emptyList()
        val first = recs.first().second
        val delim = if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
        val header = splitLine(first, delim).map { h -> h.trim().lowercase().replace(' ', '_').let { aliases[it] ?: it } }
        return recs.drop(1).map { (ln, line) ->
            val cells = splitLine(line, delim)
            val raw = header.mapIndexed { i, h -> h to (cells.getOrNull(i)?.trim() ?: "") }.toMap()
            rowFromMap(ln, raw)
        }
    }

    fun rowFromMap(ln: Int, raw: Map<String, String>): ParsedRow {
        fun g(k: String) = raw[k]?.takeIf { it.isNotBlank() }
        val errs = ArrayList<String>()
        val ts = g("date")?.let { parseDate(it) }
        if (ts == null) errs += "Data inválida ou ausente: '${raw["date"] ?: ""}'."
        val rec = TxRecord(
            timestamp = ts ?: 0L,
            type = g("type") ?: "",
            asset = (g("asset") ?: "").uppercase(),
            quantity = g("quantity") ?: "",
            unitPrice = g("unit_price"),
            currency = (g("currency") ?: "BRL").uppercase(),
            toAsset = g("to_asset")?.uppercase(),
            toQuantity = g("to_quantity"),
            fee = g("fee"),
            feeAsset = g("fee_asset")?.uppercase(),
            feeQuantity = g("fee_quantity"),
            fromWallet = g("from_wallet"),
            toWallet = g("to_wallet"),
            txHash = g("tx_hash"),
            exchange = g("exchange"),
            notes = g("notes"),
            category = g("category"),
            fxUsdBrl = g("fx_usd_brl"),
            declaredCost = g("declared_cost"),
        )
        val (e, w) = TxValidator.validate(rec)
        return ParsedRow(ln, rec, errs + e, w, raw)
    }

    private fun esc(s: String?): String {
        if (s == null) return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == ';' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun export(records: List<TxRecord>): String {
        val sb = StringBuilder(HEADER.joinToString(",")).append('\n')
        for (r in records) {
            sb.append(listOf(Instant.ofEpochMilli(r.timestamp).toString(), r.type, r.asset, r.quantity, r.unitPrice, r.currency, r.toAsset, r.toQuantity,
                r.fee, r.feeAsset, r.feeQuantity, r.fromWallet, r.toWallet, r.txHash, r.exchange, r.notes, r.category, r.fxUsdBrl, r.declaredCost)
                .joinToString(",") { esc(it) }).append('\n')
        }
        return sb.toString()
    }

    /** Tabela genérica (relatórios). */
    fun table(header: List<String>, rows: List<List<String?>>): String =
        (listOf(header) + rows).joinToString("\n") { r -> r.joinToString(",") { esc(it) } } + "\n"
}

object JsonCodec {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    data class TxFile(val format: String = "coinportracker.transactions", val version: Int = 1, val generatedAt: Long, val transactions: List<TxRecord>)

    fun export(records: List<TxRecord>, now: Long = System.currentTimeMillis()): String = json.encodeToString(TxFile(generatedAt = now, transactions = records))

    fun parse(text: String): List<ParsedRow> {
        val recs: List<TxRecord> = runCatching { json.decodeFromString<TxFile>(text).transactions }
            .recoverCatching { json.decodeFromString<List<TxRecord>>(text) }
            .getOrElse { throw IllegalArgumentException("JSON inválido: não é um arquivo de transações do CoinPortracker (${it.message?.take(120)}).") }
        return recs.mapIndexed { i, r -> val (e, w) = TxValidator.validate(r); ParsedRow(i + 1, r, e, w, emptyMap()) }
    }
}

/** Marca duplicidades contra registros existentes e dentro do próprio arquivo. */
object DuplicateDetector {
    data class Marked(val row: ParsedRow, val duplicateOfExisting: Boolean, val duplicateInFile: Boolean)

    fun mark(rows: List<ParsedRow>, existing: Set<String>): List<Marked> {
        val seen = HashSet<String>()
        return rows.map { r ->
            val fp = r.record?.let { Fingerprint.of(it) }
            Marked(r, fp != null && fp in existing, fp != null && !seen.add(fp))
        }
    }
}

fun BigDecimal?.plain(): String? = this?.stripTrailingZeros()?.toPlainString()
