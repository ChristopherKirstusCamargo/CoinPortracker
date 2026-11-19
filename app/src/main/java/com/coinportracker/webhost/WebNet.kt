package com.coinportracker.webhost

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Endereço onde o painel pode ser aberto. */
data class LanAddress(val ip: String, val iface: String, val kind: Kind) {
    enum class Kind(val label: String) { WIFI("Wi-Fi"), HOTSPOT("Roteador do celular"), ETHERNET("Cabo/USB"), TAILSCALE("Tailscale") }
}

/**
 * v6.0 — só redes locais: o servidor recusa conexões que chegam por dados móveis ou VPNs comuns, e de origem fora das
 * faixas privadas. Tailscale (100.64.0.0/10, interface tun) só quando você permitir.
 */
object WebNet {
    fun kindOf(iface: String, ip: String, allowTailscale: Boolean): LanAddress.Kind? {
        val n = iface.lowercase()
        return when {
            n.startsWith("wlan") || n.startsWith("mlan") -> if (isPrivate(ip)) LanAddress.Kind.WIFI else null
            n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") -> if (isPrivate(ip)) LanAddress.Kind.HOTSPOT else null
            n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") || n.startsWith("bt-pan") -> if (isPrivate(ip)) LanAddress.Kind.ETHERNET else null
            n.startsWith("tun") && allowTailscale && isTailscale(ip) -> LanAddress.Kind.TAILSCALE
            else -> null
        }
    }

    private fun octets(ip: String): IntArray? = ip.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }?.toIntArray()

    /** 10/8, 172.16/12, 192.168/16 e 169.254/16 (link-local). */
    fun isPrivate(ip: String): Boolean {
        val o = octets(ip) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
    }
    fun isTailscale(ip: String): Boolean { val o = octets(ip) ?: return false; return o[0] == 100 && o[1] in 64..127 }

    /** Origem aceita: rede privada (ou Tailscale, se permitido). Loopback só para testes no próprio aparelho. */
    fun remoteAllowed(ip: String, allowTailscale: Boolean) = isPrivate(ip) || (allowTailscale && isTailscale(ip)) || ip == "127.0.0.1"

    fun addresses(allowTailscale: Boolean): List<LanAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a -> val ip = a.hostAddress ?: return@mapNotNull null; kindOf(ni.name, ip, allowTailscale)?.let { LanAddress(ip, ni.name, it) } }
        }.sortedBy { it.kind.ordinal }
    }.getOrDefault(emptyList())

    /** A conexão chegou por um endereço local permitido? (o servidor escuta em todas, mas só atende estas.) */
    fun localAllowed(local: InetAddress?, allowTailscale: Boolean): Boolean {
        if (local == null) return false
        if (local.isLoopbackAddress) return true
        val ip = (local as? Inet4Address)?.hostAddress ?: return false
        return addresses(allowTailscale).any { it.ip == ip }
    }
}

/**
 * Resposta mDNS mínima para "coinportracker.local" (registro A), para o navegador achar o celular pelo nome. Sem
 * dependências. Melhor esforço: alguns roteadores e sistemas bloqueiam multicast — o endereço IP sempre funciona.
 */
object Mdns {
    const val HOST = "coinportracker.local"
    private fun labels(name: String) = name.trimEnd('.').split('.')

    fun encodeName(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (l in labels(name)) { val b = l.toByteArray(Charsets.US_ASCII); out.write(b.size); out.write(b) }
        out.write(0); return out.toByteArray()
    }

    /** Pergunta(s) do pacote: lista de (nome, tipo, unicast?). Null se não for uma consulta bem formada. */
    fun parseQuestions(p: ByteArray, len: Int = p.size): List<Triple<String, Int, Boolean>>? = runCatching {
        if (len < 12) return null
        val flags = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null           // é resposta
        val qd = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        if (qd == 0 || qd > 16) return null
        var i = 12
        val out = ArrayList<Triple<String, Int, Boolean>>()
        repeat(qd) {
            val (name, next) = readName(p, i, len) ?: return null
            i = next
            if (i + 4 > len) return null
            val type = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val cls = ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF)
            out.add(Triple(name, type, cls and 0x8000 != 0)); i += 4
        }
        out
    }.getOrNull()

    private fun readName(p: ByteArray, start: Int, len: Int): Pair<String, Int>? {
        val parts = ArrayList<String>(); var i = start; var jumped = false; var end = -1; var hops = 0
        while (true) {
            if (i >= len) return null
            val l = p[i].toInt() and 0xFF
            when {
                l == 0 -> { if (!jumped) end = i + 1; break }
                l and 0xC0 == 0xC0 -> { if (i + 1 >= len || ++hops > 8) return null; if (!jumped) end = i + 2; jumped = true; i = ((l and 0x3F) shl 8) or (p[i + 1].toInt() and 0xFF) }
                l > 63 -> return null
                else -> { if (i + 1 + l > len) return null; parts.add(String(p, i + 1, l, Charsets.US_ASCII)); i += 1 + l }
            }
            if (parts.size > 32) return null
        }
        return parts.joinToString(".") to end
    }

    fun asksForUs(q: List<Triple<String, Int, Boolean>>) = q.any { it.first.equals(HOST, ignoreCase = true) && (it.second == 1 || it.second == 255) }

    /** Resposta com o registro A ([ip]), TTL 120 s, flag de cache-flush. */
    fun answer(ip: String, id: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x8400); u16(0); u16(1); u16(0); u16(0)
        out.write(encodeName(HOST)); u16(1); u16(0x8001)
        u16(0); u16(120); u16(4)
        ip.split('.').forEach { out.write(it.toInt()) }
        return out.toByteArray()
    }
}
