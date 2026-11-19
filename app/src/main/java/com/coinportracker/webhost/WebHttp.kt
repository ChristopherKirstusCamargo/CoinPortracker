package com.coinportracker.webhost

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** Requisição HTTP/1.1 já validada (cabeçalhos em minúsculas). */
class HttpRequest(
    val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun cookie(name: String): String? = header("cookie")?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

class HttpError(val status: Int, msg: String) : Exception(msg)

class HttpResponse(val status: Int, val contentType: String, val body: ByteArray, val headers: MutableMap<String, String> = LinkedHashMap()) {
    companion object {
        fun json(status: Int, json: String) = HttpResponse(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
        fun error(status: Int, message: String) = json(status, """{"error":${WebJson.str(message)}}""")
    }
}

/**
 * v6.0 — leitor HTTP/1.1 mínimo e defensivo para o Web Host: só GET/POST/HEAD, linha de pedido e cabeçalhos com teto,
 * corpo só com Content-Length (sem chunked) e com teto pequeno, sem caracteres de controle.
 */
object HttpParser {
    const val MAX_HEADER = 16 * 1024
    const val MAX_BODY = 4 * 1024
    private val METHODS = setOf("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "PATCH")

    fun read(input: InputStream, maxHeader: Int = MAX_HEADER, maxBody: Int = MAX_BODY): HttpRequest {
        val head = ByteArrayOutputStream(1024)
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw HttpError(400, "conexão encerrada")
            head.write(b)
            if (head.size() > maxHeader) throw HttpError(431, "cabeçalho grande demais")
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").dropLast(2)
        if (lines.isEmpty()) throw HttpError(400, "pedido vazio")
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "linha de pedido inválida")
        val method = parts[0]
        if (method !in METHODS) throw HttpError(501, "método não suportado")
        val target = parts[1]
        if (!target.startsWith("/") || target.length > 2048 || target.any { it.code < 0x21 || it.code > 0x7E }) throw HttpError(400, "endereço inválido")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i <= 0 || l.any { it.code < 0x20 && it != '\t' }) throw HttpError(400, "cabeçalho inválido")
            val k = l.substring(0, i).trim().lowercase()
            if (k.any { it == ' ' || it == '\t' }) throw HttpError(400, "cabeçalho inválido")
            val v = l.substring(i + 1).trim()
            if (k in headers && (k == "host" || k == "content-length" || k == "origin" || k == "cookie")) throw HttpError(400, "cabeçalho repetido")
            headers[k] = v
        }
        if (headers["transfer-encoding"] != null) throw HttpError(501, "transfer-encoding não suportado")
        val len = headers["content-length"]?.let { it.toIntOrNull() ?: throw HttpError(400, "content-length inválido") } ?: 0
        if (len < 0) throw HttpError(400, "content-length inválido")
        if (len > maxBody) throw HttpError(413, "corpo grande demais")
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val r = input.read(body, off, len - off); if (r < 0) throw HttpError(400, "corpo incompleto"); off += r }
        val path = target.substringBefore('?').substringBefore('#')
        if (path.contains("..") || path.contains("//") || path.contains('%') && path.contains("%2e", ignoreCase = true)) throw HttpError(400, "endereço inválido")
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.take(16).associate { kv ->
            fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault("")
            dec(kv.substringBefore('=')).take(32) to dec(kv.substringAfter('=', "")).take(128)
        }
        return HttpRequest(method, path, query, headers, body)
    }

    fun write(out: OutputStream, r: HttpResponse, head: Boolean = false) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in r.headers) { require(v.none { it == '\r' || it == '\n' }); sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(r.body)
        out.flush()
    }

    fun reason(s: Int) = when (s) {
        200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 301 -> "Moved Permanently"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 410 -> "Gone"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 421 -> "Misdirected Request"
        423 -> "Locked"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"; 503 -> "Service Unavailable"
        else -> "Status"
    }
}

/** Escrita de JSON segura (escapa também <, > e & para nunca virar HTML). */
object WebJson {
    fun str(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\""); ch == '\\' -> sb.append("\\\\")
            ch == '<' -> sb.append("\\u003c"); ch == '>' -> sb.append("\\u003e"); ch == '&' -> sb.append("\\u0026")
            ch == '\u2028' -> sb.append("\\u2028"); ch == '\u2029' -> sb.append("\\u2029")
            ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
