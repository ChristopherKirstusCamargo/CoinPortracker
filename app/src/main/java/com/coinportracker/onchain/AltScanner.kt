package com.coinportracker.onchain

import com.coinportracker.core.Outcome
import com.coinportracker.network.HttpClient
import com.coinportracker.network.RatePolicy
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.math.BigInteger

/**
 * v3.2: leitura de saldo (somente leitura, endpoints públicos sem chave) para redes fora de EVM/Solana/Bitcoin.
 * Histórico não é lido nessas redes → o motor de histórico as trata como "histórico parcial" (saldo atual mantido
 * desde o início do gráfico; não gera lucro/prejuízo falso porque o resultado é por efeito de preço).
 */
class AltScanner(private val http: HttpClient) {
    private val policy = RatePolicy(minIntervalMs = 1_100, maxRetries = 2, baseBackoffMs = 1_200, maxBackoffMs = 8_000)

    suspend fun scan(net: Network, address: String): Outcome<ScanResult> {
        val a = address.trim()
        var last: Outcome.Err = Outcome.Err("${net.label}: sem provedor")
        for (base in com.coinportracker.dev.DevSources.rpcs(net)) {
            val host = base.substringAfter("://").substringBefore('/')
            val r = com.coinportracker.core.catchingNonCancel { read(net, base, host, a) }.getOrElse { Outcome.Err("${net.label}: resposta inválida de $host", it) }
            when (r) {
                is Outcome.Ok -> return Outcome.Ok(ScanResult(r.value, emptyList(), emptyList(), listOf(host)))
                is Outcome.Err -> last = Outcome.Err("${net.label}: ${r.message}", r.cause, r.retryable)
            }
        }
        return last
    }

    private fun native(net: Network, raw: BigInteger) =
        if (raw.signum() > 0) listOf(TokenHolding(net, "native", net.nativeSymbol, net.nativeName, net.nativeDecimals, scaled(raw, net.nativeDecimals), net.iconUrl,
            net.nativePriceKey, HoldingKind.NATIVE, true, coingeckoId = net.nativeCoingeckoId)) else emptyList()

    private fun dec(net: Network, s: String?): BigInteger = s?.let { BigDecimal(it).movePointRight(net.nativeDecimals).toBigInteger() } ?: BigInteger.ZERO

    private suspend fun getJson(host: String, url: String): Outcome<JsonElement> = when (val r = http.get("alt:$host", url, policy)) {
        is Outcome.Err -> r
        is Outcome.Ok -> Outcome.Ok(chainJson.parseToJsonElement(r.value))
    }
    private suspend fun postJson(host: String, url: String, body: String): Outcome<JsonElement> = when (val r = http.postJson("alt:$host", url, body, policy)) {
        is Outcome.Err -> r
        is Outcome.Ok -> Outcome.Ok(chainJson.parseToJsonElement(r.value))
    }

    private suspend fun read(net: Network, base: String, host: String, a: String): Outcome<List<TokenHolding>> = when (net) {
        Network.STELLAR -> getJson(host, "$base/accounts/$a").map { parseStellar(it) }
        Network.TRON -> getJson(host, "$base/v1/accounts/$a").map { parseTron(it) }
        Network.XRP -> postJson(host, base, """{"method":"account_info","params":[{"account":"$a","ledger_index":"validated"}]}""").flatMap { info ->
            val err = info.o()?.get("result").o()?.get("error").s()
            if (err == "actNotFound") Outcome.Ok(emptyList())
            else if (err != null) Outcome.Err(err)
            else {
                val lines = (postJson(host, base, """{"method":"account_lines","params":[{"account":"$a","ledger_index":"validated"}]}""") as? Outcome.Ok)?.value
                Outcome.Ok(parseXrp(info, lines))
            }
        }
        Network.CARDANO -> postJson(host, "$base/address_info", """{"_addresses":["$a"]}""").map { el ->
            native(net, el.a()?.firstOrNull().o()?.get("balance").s()?.toBigIntegerOrNull() ?: BigInteger.ZERO)
        }
        Network.DOGECOIN -> getJson(host, if (host.contains("blockcypher")) "$base/addrs/$a/balance" else "$base/address/balance/$a").map { el ->
            val o = el.o()
            if (o?.containsKey("final_balance") == true) native(net, o["final_balance"].s()?.toBigIntegerOrNull() ?: BigInteger.ZERO)
            else { if (o?.get("success").s() != "1") throw IllegalStateException("dogechain"); native(net, dec(net, o?.get("balance").s())) }
        }
        Network.TON -> getJson(host, "$base/getAddressBalance?address=${java.net.URLEncoder.encode(a, "UTF-8")}").map { el ->
            if (el.o()?.get("ok").s() != "true") throw IllegalStateException("toncenter")
            native(net, el.o()!!["result"].s()?.toBigIntegerOrNull() ?: BigInteger.ZERO)
        }
        Network.COSMOS -> getJson(host, "$base/cosmos/bank/v1beta1/balances/$a").map { el ->
            native(net, el.o()?.get("balances").a().orEmpty().mapNotNull { it.o() }.firstOrNull { it["denom"].s() == "uatom" }?.get("amount").s()?.toBigIntegerOrNull() ?: BigInteger.ZERO)
        }
        Network.NEAR -> postJson(host, base, """{"jsonrpc":"2.0","id":1,"method":"query","params":{"request_type":"view_account","finality":"final","account_id":"$a"}}""").map { el ->
            native(net, el.o()?.get("result").o()?.get("amount").s()?.toBigIntegerOrNull() ?: BigInteger.ZERO)
        }
        Network.SUI -> postJson(host, base, """{"query":"{ address(address:\"$a\") { balance(coinType:\"0x2::sui::SUI\") { totalBalance } } }"}""").map { el ->
            native(net, el.o()?.get("data").o()?.get("address").o()?.get("balance").o()?.get("totalBalance").s()?.toBigIntegerOrNull() ?: BigInteger.ZERO)
        }
        Network.APTOS -> when (val r = http.get("alt:$host", "$base/accounts/$a/balance/0x1::aptos_coin::AptosCoin", policy)) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(native(net, r.value.trim().trim('"').toBigIntegerOrNull() ?: BigInteger.ZERO))
        }
        else -> Outcome.Err("rede não suportada aqui")
    }

    companion object {
        /** Ativos conhecidos fora de EVM/Solana com preço garantido (chave CoinGecko). */
        val STELLAR_KNOWN = mapOf("USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN" to "usd-coin",
            "EURC:GDHU6WRG4IEQXM5NZ4BMPKOXHW76MZM4Y2IEMFDVXBSDP6SJY4ITNPP2" to "euro-coin")
        val TRON_KNOWN = mapOf("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t" to Triple("USDT", "Tether USD", "tether"),
            "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8" to Triple("USDC", "USD Coin", "usd-coin"))
        val XRP_KNOWN = mapOf("rMxCKbEDwqr76QuheSUMdEGf4B9xJ8m5De" to Triple("RLUSD", "Ripple USD", "ripple-usd"))

        fun parseStellar(el: JsonElement): List<TokenHolding> {
            val net = Network.STELLAR
            return el.o()?.get("balances").a().orEmpty().mapNotNull { it.o() }.mapNotNull { b ->
                val amt = b["balance"].s()?.toBigDecimalOrNull()?.stripTrailingZeros() ?: return@mapNotNull null
                if (amt.signum() <= 0) return@mapNotNull null
                if (b["asset_type"].s() == "native") TokenHolding(net, "native", "XLM", "Stellar", 7, amt, net.iconUrl, net.nativePriceKey, HoldingKind.NATIVE, true, coingeckoId = "stellar")
                else {
                    val code = b["asset_code"].s() ?: return@mapNotNull null; val issuer = b["asset_issuer"].s() ?: ""
                    val cg = STELLAR_KNOWN["$code:$issuer"]
                    TokenHolding(net, "$code:$issuer", code.take(12), if (cg != null) code else "$code (Stellar)", 7, amt, null, cg?.let { "coingecko:$it" },
                        HoldingKind.TOKEN, verified = cg != null, coingeckoId = cg)
                }
            }
        }

        fun parseTron(el: JsonElement): List<TokenHolding> {
            val net = Network.TRON
            val d = el.o()?.get("data").a()?.firstOrNull().o() ?: return emptyList()
            val out = ArrayList<TokenHolding>()
            val sun = d["balance"].s()?.toBigIntegerOrNull() ?: BigInteger.ZERO
            if (sun.signum() > 0) out += TokenHolding(net, "native", "TRX", "TRON", 6, scaled(sun, 6), net.iconUrl, net.nativePriceKey, HoldingKind.NATIVE, true, coingeckoId = "tron")
            // TRC-20: só tokens conhecidos (a lista da TronGrid vem cheia de airdrops de golpe)
            for (m in d["trc20"].a().orEmpty().mapNotNull { it.o() }) for ((contract, v) in m) {
                val k = TRON_KNOWN[contract] ?: continue
                val raw = v.s()?.toBigIntegerOrNull() ?: continue
                if (raw.signum() > 0) out += TokenHolding(net, contract, k.first, k.second, 6, scaled(raw, 6), null, "coingecko:${k.third}", HoldingKind.TOKEN, true, coingeckoId = k.third)
            }
            return out
        }

        fun parseXrp(info: JsonElement, lines: JsonElement?): List<TokenHolding> {
            val net = Network.XRP
            val out = ArrayList<TokenHolding>()
            val drops = info.o()?.get("result").o()?.get("account_data").o()?.get("Balance").s()?.toBigIntegerOrNull() ?: BigInteger.ZERO
            if (drops.signum() > 0) out += TokenHolding(net, "native", "XRP", "XRP", 6, scaled(drops, 6), net.iconUrl, net.nativePriceKey, HoldingKind.NATIVE, true, coingeckoId = "ripple")
            lines?.o()?.get("result").o()?.get("lines").a().orEmpty().mapNotNull { it.o() }.forEach { l ->
                val bal = l["balance"].s()?.toBigDecimalOrNull() ?: return@forEach
                if (bal.signum() <= 0) return@forEach
                val issuer = l["account"].s() ?: ""
                val cur = l["currency"].s() ?: return@forEach
                val k = XRP_KNOWN[issuer]
                val sym = k?.first ?: if (cur.length == 40) runCatching { String(java.math.BigInteger(cur, 16).toByteArray()).trim('\u0000').take(12) }.getOrDefault(cur.take(6)) else cur
                out += TokenHolding(net, "$cur:$issuer", sym, k?.second ?: "$sym (XRPL)", 15, bal.stripTrailingZeros(), null, k?.let { "coingecko:${it.third}" },
                    HoldingKind.TOKEN, verified = k != null, coingeckoId = k?.third)
            }
            return out
        }
    }
}

private inline fun <A, B> Outcome<A>.flatMap(f: (A) -> Outcome<B>): Outcome<B> = when (this) { is Outcome.Ok -> f(value); is Outcome.Err -> this }
