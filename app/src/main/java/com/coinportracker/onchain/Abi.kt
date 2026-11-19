package com.coinportracker.onchain

import java.math.BigInteger

/** Codificação ABI mínima (sem bibliotecas): balanceOf ERC-20 e Multicall3.aggregate3. */
object Abi {
    const val MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11"
    private const val BALANCE_OF = "70a08231"
    private const val AGGREGATE3 = "82ad56cb"

    fun word(n: Long): String = n.toString(16).padStart(64, '0')
    fun addressWord(addr: String): String = addr.removePrefix("0x").removePrefix("0X").lowercase().padStart(64, '0')

    fun balanceOfCall(owner: String): String = BALANCE_OF + addressWord(owner)

    fun hexToBig(hex: String?): BigInteger {
        val h = hex?.removePrefix("0x").orEmpty()
        return if (h.isEmpty()) BigInteger.ZERO else BigInteger(h, 16)
    }

    /** aggregate3((address target, bool allowFailure, bytes callData)[]) com allowFailure = true. */
    fun encodeAggregate3(targets: List<String>, callDataHex: String): String {
        val cd = callDataHex.removePrefix("0x")
        val cdLen = cd.length / 2
        val padded = cd + "0".repeat((64 - (cd.length % 64)) % 64)
        val n = targets.size
        val elem = targets.map { t -> addressWord(t) + word(1) + word(0x60) + word(cdLen.toLong()) + padded }
        val sb = StringBuilder("0x").append(AGGREGATE3).append(word(0x20)).append(word(n.toLong()))
        var off = n * 32L
        for (e in elem) { sb.append(word(off)); off += e.length / 2 }
        elem.forEach { sb.append(it) }
        return sb.toString()
    }

    /** Decodifica (bool success, bytes returnData)[]; devolve o uint256 de cada chamada (null quando falhou ou não é uint256). */
    fun decodeAggregate3Uints(resultHex: String): List<BigInteger?> {
        val b = hexBytes(resultHex)
        fun u(o: Int): Int {
            if (o < 0 || o + 32 > b.size) throw IllegalArgumentException("ABI fora do limite")
            val v = BigInteger(1, b.copyOfRange(o, o + 32))
            if (v.bitLength() > 31) throw IllegalArgumentException("ABI offset inválido")
            return v.toInt()
        }
        val base = u(0)
        val n = u(base)
        val out = ArrayList<BigInteger?>(n)
        for (i in 0 until n) {
            val eo = base + 32 + u(base + 32 + 32 * i)
            val ok = u(eo) != 0
            val dOff = eo + u(eo + 32)
            val len = u(dOff)
            out += if (ok && len == 32 && dOff + 64 <= b.size) BigInteger(1, b.copyOfRange(dOff + 32, dOff + 64)) else null
        }
        return out
    }

    fun hexBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x")
        require(h.length % 2 == 0) { "hex ímpar" }
        return ByteArray(h.length / 2) { i -> ((Character.digit(h[2 * i], 16) shl 4) or Character.digit(h[2 * i + 1], 16)).toByte() }
    }
}
