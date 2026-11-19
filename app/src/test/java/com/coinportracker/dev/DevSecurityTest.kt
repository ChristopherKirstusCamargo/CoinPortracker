package com.coinportracker.dev

import org.junit.Assert.*
import org.junit.Test

class DevSecurityTest {
    private fun rpcCfg(url: String) = DevDefaults.empty().copy(chains = listOf(ChainSources("ETHEREUM", listOf(DevEndpoint("1", url, kind = "RPC")))))

    @Test fun httpsOnly_andNoSsrf() {
        listOf("http://rpc.example.com", "file:///data/data/com.coinportracker/databases/coinportracker.db", "content://media/external/1",
            "https://localhost:8545", "https://127.0.0.1", "https://10.0.0.2/rpc", "https://[::1]/", "https://169.254.169.254/latest/meta-data",
            "https://user:pass@rpc.example.com", "https://rpc.example.com/\nHost: x", "ftp://x.com")
            .forEach { u -> assertFalse(u, DevValidator.validate(rpcCfg(u)).ok) }
        assertTrue(DevValidator.validate(rpcCfg("https://ethereum-rpc.publicnode.com")).ok)
    }

    @Test fun limits_rejectBombs() {
        val many = DevDefaults.empty().copy(chains = listOf(ChainSources("ETHEREUM", (0 until 100).map { DevEndpoint("$it", "https://h$it.example.com", kind = "RPC") })))
        assertFalse(DevValidator.validate(many).ok)
        val long = rpcCfg("https://ok.example.com/" + "a".repeat(5_000))
        assertFalse(DevValidator.validate(long).ok)
        val tokens = DevDefaults.empty().copy(customTokens = (0 until 600).map { CustomToken("$it", "ETHEREUM", "0x" + "%040x".format(it), "T$it", 18) })
        assertFalse(DevValidator.validate(tokens).ok)
    }

    @Test fun badExplorerLink_rejected() {
        val c = DevDefaults.empty().copy(customChains = listOf(CustomEvmChain("c", "Chain", 777777, "https://rpc.example.com", explorerTx = "javascript:alert(1)//", nativeSymbol = "C")))
        assertFalse(DevValidator.validate(c).ok)
    }

    @Test fun regexes_areLinear() {
        // entradas patológicas não podem travar o validador (ReDoS)
        val evil = "a ".repeat(20_000) + "!"
        val t0 = System.nanoTime()
        DevValidator.secret(evil, "x"); DevValidator.redact("0x" + "a".repeat(100_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
}
