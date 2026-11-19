package com.coinportracker.blockchain

import com.coinportracker.domain.Chain
import java.math.BigInteger
import java.security.MessageDigest

/** Validação de endereços PÚBLICOS. O app nunca aceita chaves privadas nem frases-semente. */
object AddressValidator {
    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun base58Decode(s: String): ByteArray? {
        if (s.isEmpty()) return null
        var n = BigInteger.ZERO
        for (ch in s) { val i = B58.indexOf(ch); if (i < 0) return null; n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(i.toLong())) }
        val bytes = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else if (n == BigInteger.ZERO) ByteArray(0) else it }
        val zeros = s.takeWhile { it == '1' }.length
        return ByteArray(zeros) + bytes
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) { val b = chk ushr 25; chk = (chk and 0x1ffffff) shl 5 xor v; for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor gen[i] }
        return chk
    }

    private fun bech32Valid(addr: String): Boolean {
        val a = addr.lowercase()
        if (addr != a && addr != addr.uppercase()) return false
        val pos = a.lastIndexOf('1')
        if (pos < 1 || pos + 7 > a.length || a.length > 90) return false
        val hrp = a.substring(0, pos)
        if (hrp != "bc") return false
        val data = a.substring(pos + 1).map { CHARSET.indexOf(it).also { i -> if (i < 0) return false } }
        val exp = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        val pm = polymod((exp + data).toIntArray())
        val witnessVersion = data[0]
        return (witnessVersion == 0 && pm == 1) || (witnessVersion in 1..16 && pm == 0x2bc830a3)
    }

    fun isValidBitcoin(addr: String): Boolean {
        val a = addr.trim()
        if (a.startsWith("bc1", true)) return bech32Valid(a)
        if (a.startsWith("xpub") || a.startsWith("ypub") || a.startsWith("zpub")) return false // chaves estendidas não suportadas
        if (!(a.startsWith("1") || a.startsWith("3"))) return false
        val raw = base58Decode(a) ?: return false
        if (raw.size != 25) return false
        val check = sha256(sha256(raw.copyOfRange(0, 21))).copyOfRange(0, 4)
        return check.contentEquals(raw.copyOfRange(21, 25)) && (raw[0] == 0.toByte() || raw[0] == 5.toByte())
    }

    // ---------- v3.2: outras redes ----------
    private fun base58Check(a: String, alphabet: String = B58): ByteArray? {
        var n = BigInteger.ZERO
        for (ch in a) { val i = alphabet.indexOf(ch); if (i < 0) return null; n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(i.toLong())) }
        val body = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else if (n == BigInteger.ZERO) ByteArray(0) else it }
        val raw = ByteArray(a.takeWhile { it == alphabet[0] }.length) + body
        if (raw.size < 5) return null
        val payload = raw.copyOfRange(0, raw.size - 4)
        return if (sha256(sha256(payload)).copyOfRange(0, 4).contentEquals(raw.copyOfRange(raw.size - 4, raw.size))) payload else null
    }
    private const val XRP58 = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"

    /** Bech32/Bech32m genérico com o prefixo [hrp] (checksum validado). */
    fun isValidBech32(addr: String, hrp: String): Boolean {
        val a = addr.trim().lowercase()
        if (addr.trim() != a && addr.trim() != addr.trim().uppercase()) return false
        val pos = a.lastIndexOf('1')
        if (pos < 1 || pos + 7 > a.length || a.substring(0, pos) != hrp) return false
        val data = a.substring(pos + 1).map { CHARSET.indexOf(it).also { i -> if (i < 0) return false } }
        val exp = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        val pm = polymod((exp + data).toIntArray())
        return pm == 1 || pm == 0x2bc830a3
    }
    fun isValidLitecoin(addr: String): Boolean {
        val a = addr.trim()
        if (a.startsWith("ltc1", true)) return isValidBech32(a, "ltc")
        if (!(a.startsWith("L") || a.startsWith("M"))) return false
        val p = base58Check(a) ?: return false
        return p.size == 21 && (p[0] == 0x30.toByte() || p[0] == 0x32.toByte())
    }
    fun isValidDogecoin(addr: String): Boolean {
        val a = addr.trim()
        if (!(a.startsWith("D") || a.startsWith("A") || a.startsWith("9"))) return false
        val p = base58Check(a) ?: return false
        return p.size == 21 && (p[0] == 0x1e.toByte() || p[0] == 0x16.toByte())
    }
    fun isValidTron(addr: String): Boolean {
        val a = addr.trim()
        if (!a.startsWith("T") || a.length != 34) return false
        val p = base58Check(a) ?: return false
        return p.size == 21 && p[0] == 0x41.toByte()
    }
    fun isValidXrp(addr: String): Boolean {
        val a = addr.trim()
        if (!a.startsWith("r") || a.length !in 25..35) return false
        val p = base58Check(a, XRP58) ?: return false
        return p.size == 21 && p[0] == 0.toByte()
    }
    private fun crc16x(b: ByteArray): Int { var c = 0; for (x in b) { c = c xor ((x.toInt() and 0xff) shl 8); repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 } }; return c and 0xffff }
    /** Stellar StrKey de conta: G + 55 caracteres base32 (versão 6<<3, CRC16-XModem). */
    fun isValidStellar(addr: String): Boolean {
        val a = addr.trim()
        if (a.length != 56 || !a.startsWith("G")) return false
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buf = 0L; var bits = 0; val out = java.io.ByteArrayOutputStream()
        for (ch in a) { val v = alpha.indexOf(ch); if (v < 0) return false; buf = (buf shl 5) or v.toLong(); bits += 5; if (bits >= 8) { bits -= 8; out.write(((buf shr bits) and 0xff).toInt()) } }
        val raw = out.toByteArray()
        if (raw.size != 35 || raw[0] != (6 shl 3).toByte()) return false
        val crc = crc16x(raw.copyOfRange(0, 33))
        return (raw[33].toInt() and 0xff) == (crc and 0xff) && (raw[34].toInt() and 0xff) == (crc shr 8)
    }
    /** TON: forma amigável (48 caracteres base64/base64url, CRC16) ou bruta "0:<64 hex>". */
    fun isValidTon(addr: String): Boolean {
        val a = addr.trim()
        if (Regex("^-?[01]:[0-9a-fA-F]{64}$").matches(a)) return true
        if (a.length != 48) return false
        val raw = runCatching { java.util.Base64.getDecoder().decode(a.replace('-', '+').replace('_', '/')) }.getOrNull() ?: return false
        if (raw.size != 36) return false
        val crc = crc16x(raw.copyOfRange(0, 34))
        return (raw[34].toInt() and 0xff) == (crc shr 8) && (raw[35].toInt() and 0xff) == (crc and 0xff)
    }

    fun isValidSolana(addr: String): Boolean {
        val a = addr.trim()
        if (a.length !in 32..44) return false
        return base58Decode(a)?.size == 32
    }

    fun isValidEvm(addr: String) = Regex("^0x[0-9a-fA-F]{40}$").matches(addr.trim())

    /** Heurística defensiva: rejeita entradas que parecem segredo (frase-semente ou chave privada). */
    fun looksLikeSecret(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        // frase-semente: 12–24 palavras, aceitando maiúsculas, vírgulas e numeração ("1. abandon 2. ability…")
        val words = t.lowercase().split(R_SPLIT).filter { it.isNotEmpty() && !it.matches(R_NUM) }
        if (words.size in 12..24 && words.all { it.matches(R_WORD) }) return true
        if (R_HEX64.matches(t)) return true            // chave privada EVM
        if (t.length in 86..90 && base58Decode(t)?.size == 64) return true      // keypair Solana (base58)
        if (SOLANA_JSON_KEY.matches(t.replace(R_WS, ""))) return true   // keypair Solana (id.json: 64 bytes)
        if (R_WIF.matches(t)) return true // WIF Bitcoin (main/testnet)
        if (R_XPRV.containsMatchIn(t)) return true // chave privada estendida
        if (R_SUI.containsMatchIn(t.lowercase())) return true      // Sui / Near
        if (R_XLMS.matches(t)) return true                    // seed Stellar (S…)
        return false
    }

    private val R_SPLIT = Regex("[\\s,;]+")
    private val R_NUM = Regex("^\\d+[.)]?$")
    private val R_WORD = Regex("^[a-z]{3,8}$")
    private val R_HEX64 = Regex("^(0x)?[0-9a-fA-F]{64}$")
    private val R_WS = Regex("\\s")
    private val R_WIF = Regex("^[5KL9c][1-9A-HJ-NP-Za-km-z]{50,51}$")
    private val R_XPRV = Regex("^[xyztuvYZ]prv[1-9A-HJ-NP-Za-km-z]{20,}")
    private val R_SUI = Regex("^(suiprivkey1|ed25519:)")
    private val R_XLMS = Regex("^S[A-Z2-7]{55}$")
    private val SOLANA_JSON_KEY = Regex("^\\[(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d),){63}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)]$")

    fun validate(chain: Chain, addr: String): String? {
        if (looksLikeSecret(addr)) return "Isto parece uma chave privada ou frase-semente. NUNCA digite segredos. O CoinPortracker só aceita endereços públicos."
        val ok = when {
            chain == Chain.BITCOIN -> isValidBitcoin(addr)
            chain == Chain.SOLANA -> isValidSolana(addr)
            chain.isEvm -> isValidEvm(addr)
            else -> addr.isNotBlank()
        }
        return if (ok) null else when (chain) {
            Chain.BITCOIN -> "Endereço Bitcoin inválido (aceitos: 1…, 3…, bc1…; xpub não suportado)."
            Chain.SOLANA -> "Endereço Solana inválido (base58, 32 bytes)."
            else -> "Endereço EVM inválido (0x + 40 hexadecimais)."
        }
    }
}
