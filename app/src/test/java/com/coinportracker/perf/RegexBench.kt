package com.coinportracker.perf

import com.coinportracker.blockchain.AddressValidator
import com.coinportracker.dev.DevValidator
import com.coinportracker.onchain.AddressKind
import org.junit.Test

/** Microbenchmark JVM (não é teste de regressão): imprime ns/op dos caminhos quentes de validação/redação. */
class RegexBench {
    private fun bench(name: String, n: Int, f: () -> Unit) {
        repeat(n / 5) { f() }
        val t0 = System.nanoTime(); repeat(n) { f() }
        println("BENCH $name: ${(System.nanoTime() - t0) / n} ns/op")
    }
    @Test fun run() {
        val line = "→ rpc:api.mainnet-beta.solana.com getBalance 7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU 0x742d35Cc6634C0532925a3b844Bc454e4438f44e bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        bench("redact(log line)", 20_000) { DevValidator.redact(line) }
        bench("AddressKind.candidates(sol)", 20_000) { AddressKind.candidates("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU") }
        bench("looksLikeSecret(addr)", 20_000) { AddressValidator.looksLikeSecret("0x742d35Cc6634C0532925a3b844Bc454e4438f44e") }
    }
}
