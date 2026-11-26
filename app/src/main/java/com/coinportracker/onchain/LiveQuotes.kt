package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.JsonElement

/**
 * Cotações de negociação ao vivo para os ativos mais comuns (Kraken, sem chave). O DefiLlama
 * entrega preços com alguns minutos de atraso e cache de CDN; aqui o último negócio da exchange
 * atualiza o saldo a cada ciclo. Só ativos com par conhecido entram, e um preço fora de ±5% do
 * DefiLlama é descartado.
 */
class LiveQuotes(private val http: HttpClient) {
    private val policy = RatePolicy(minIntervalMs = 1_000, maxRetries = 1, baseBackoffMs = 1_000, maxBackoffMs = 5_000)

    fun supported(keys: Collection<String>): List<String> = keys.filter { pairOf(it) != null }.distinct()

    /** Preço em USD por chave DefiLlama. */
    suspend fun fetch(keys: Collection<String>): Outcome<Map<String, Double>> {
        val want = supported(keys)
        if (want.isEmpty()) return Outcome.Ok(emptyMap())
        val pairs = want.mapNotNull { pairOf(it) }.distinct()
        val out = HashMap<String, Double>()
        var anyOk = false
        var lastErr: Outcome.Err? = null
        val base = com.coinportracker.dev.DevSources.priceUrl("kraken", "https://api.kraken.com").trimEnd('/')
        // lotes: um par desconhecido não zera a cotação dos outros
        for (chunk in pairs.chunked(12)) {
            val r = http.get("kraken", "$base/0/public/Ticker?pair=" + chunk.joinToString(","), policy)
            if (r is Outcome.Err) { lastErr = r; continue }
            val last = runCatching { parseKraken(chainJson.parseToJsonElement((r as Outcome.Ok).value)) }.getOrNull()
            if (last == null) { lastErr = Outcome.Err("Kraken: resposta inválida"); continue }
            anyOk = true
            for (k in want) { val p = pairOf(k) ?: continue; if (p in chunk) last[p]?.let { out[k] = it } }
        }
        return if (!anyOk && lastErr != null) lastErr else Outcome.Ok(out)
    }

    companion object {
        private val BY_KEY = mapOf(
            "coingecko:bitcoin" to "XBTUSD", "coingecko:wrapped-bitcoin" to "XBTUSD",
            "coingecko:ethereum" to "ETHUSD", "coingecko:weth" to "ETHUSD",
            "coingecko:solana" to "SOLUSD", "coingecko:wrapped-solana" to "SOLUSD",
            "coingecko:binancecoin" to "BNBUSD", "coingecko:wbnb" to "BNBUSD",
            "coingecko:polygon-ecosystem-token" to "POLUSD", "coingecko:matic-network" to "POLUSD",
            "coingecko:arbitrum" to "ARBUSD", "coingecko:optimism" to "OPUSD",
            "coingecko:chainlink" to "LINKUSD", "coingecko:dogecoin" to "XDGUSD",
            "coingecko:ripple" to "XRPUSD", "coingecko:litecoin" to "LTCUSD",
            "coingecko:cardano" to "ADAUSD", "coingecko:polkadot" to "DOTUSD",
            "coingecko:avalanche-2" to "AVAXUSD", "coingecko:cosmos" to "ATOMUSD",
            "coingecko:tron" to "TRXUSD", "coingecko:sui" to "SUIUSD",
            "coingecko:near" to "NEARUSD", "coingecko:uniswap" to "UNIUSD",
            "coingecko:aave" to "AAVEUSD", "coingecko:injective-protocol" to "INJUSD",
            "coingecko:pepe" to "PEPEUSD", "coingecko:shiba-inu" to "SHIBUSD",
            "coingecko:filecoin" to "FILUSD", "coingecko:aptos" to "APTUSD",
            "coingecko:celestia" to "TIAUSD", "coingecko:sei-network" to "SEIUSD",
            "coingecko:the-open-network" to "TONUSD", "coingecko:hedera-hashgraph" to "HBARUSD",
            "coingecko:internet-computer" to "ICPUSD", "coingecko:stellar" to "XLMUSD",
            "coingecko:bitcoin-cash" to "BCHUSD", "coingecko:dogwifcoin" to "WIFUSD",
            "coingecko:bittensor" to "TAOUSD", "coingecko:render-token" to "RENDERUSD",
            "coingecko:jupiter-exchange-solana" to "JUPUSD", "coingecko:bonk" to "BONKUSD",
            "solana:So11111111111111111111111111111111111111112" to "SOLUSD",
            "solana:JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "JUPUSD",
            "solana:DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "BONKUSD",
        )
        /** Nome do par na resposta da Kraken (alguns vêm com prefixos X/Z). */
        private val RESULT = mapOf(
            "XBTUSD" to "XXBTZUSD", "ETHUSD" to "XETHZUSD", "XDGUSD" to "XXDGZUSD",
            "XRPUSD" to "XXRPZUSD", "LTCUSD" to "XLTCZUSD", "XLMUSD" to "XXLMZUSD",
        )

        fun pairOf(key: String): String? = BY_KEY[key]

        fun wsSymbols(): List<String> = BY_KEY.values.toSet().map { wsSymbol(it) }.sorted()

        fun wsSymbol(pair: String): String = when (pair) {
            "XBTUSD" -> "BTC/USD"
            "XDGUSD" -> "DOGE/USD"
            else -> pair.removeSuffix("USD") + "/USD"
        }

        fun keysForWsSymbol(symbol: String): List<String> {
            val pair = when (symbol) {
                "BTC/USD" -> "XBTUSD"
                "DOGE/USD" -> "XDGUSD"
                else -> symbol.replace("/", "")
            }
            return BY_KEY.filterValues { it == pair }.keys.toList()
        }

        /** Ticker v2: `{"channel":"ticker","data":[{"symbol":"XRP/USD","last":0.95}]}`. */
        fun parseKrakenWs(el: JsonElement): Map<String, Double> {
            val o = el.o() ?: return emptyMap()
            if (o["channel"].s() != "ticker") return emptyMap()
            val out = HashMap<String, Double>()
            for (row in o["data"].a().orEmpty()) {
                val r = row.o() ?: continue
                val sym = r["symbol"].s() ?: continue
                val last = r["last"].s()?.toDoubleOrNull() ?: continue
                if (last <= 0.0 || last.isNaN() || last.isInfinite()) continue
                for (k in keysForWsSymbol(sym)) out[k] = last
            }
            return out
        }

        fun parseKraken(el: JsonElement): Map<String, Double> {
            val res = el.o()?.get("result").o() ?: return emptyMap()
            val out = HashMap<String, Double>()
            for (pair in BY_KEY.values.toSet()) {
                val alt = RESULT[pair]
                val node = (if (alt != null) res[alt] else null) ?: res[pair]
                val o = node.o() ?: continue
                o["c"].a()?.firstOrNull()?.s()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { out[pair] = it }
            }
            return out
        }

        /**
         * Junta a cotação ao vivo com o preço do DefiLlama: troca o preço e recalcula a variação 24 h
         * sobre o mesmo preço de 24 h atrás. Descarta cotações que destoam mais de [maxDiff].
         */
        fun merge(base: LivePrice, live: Double, now: Long, maxDiff: Double = 0.05): LivePrice {
            if (base.usd <= 0 || kotlin.math.abs(live / base.usd - 1) > maxDiff) return base
            val ch = base.change24hPct?.let { c -> val prev = base.usd / (1 + c / 100); if (prev > 0) (live / prev - 1) * 100 else c }
            return base.copy(usd = live, change24hPct = ch, fetchedAt = now)
        }
    }
}
