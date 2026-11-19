package com.coinportracker.webhost

/** Dados do painel (JSON já formatado pelo app). Null = recurso inexistente. Chamado em threads do servidor. */
fun interface WebData { fun get(endpoint: String, query: Map<String, String>): String? }

/**
 * v6.0 — roteador do Web Host. Regras:
 *  - Host precisa ser um dos endereços deste celular (defesa contra DNS rebinding);
 *  - arquivos do painel e API só por GET; POST só em /auth/pair e /auth/logout, com Origin igual ao Host e JSON;
 *  - API exige sessão (cookie HttpOnly + SameSite=Strict) e o cabeçalho X-Requested-With: cpt (CSRF);
 *  - nenhum endpoint altera dados do portfólio: todos os controles ficam no app;
 *  - cabeçalhos de segurança em todas as respostas (CSP estrita, sem cache, sem iframe, sem referrer).
 */
class WebRouter(
    private val auth: WebAuth,
    private val data: WebData,
    private val assets: (String) -> ByteArray?,
    private val allowedHosts: () -> Set<String>,
    private val onPending: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val general = RateLimiter(120, 60, clock)
    private val authLimit = RateLimiter(10, 10, clock)
    @Volatile var secureCookie = false

    companion object {
        const val COOKIE = "cpt_s"
        const val PREVIEW_HOST = "cpt.preview"
        val CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        private val STATIC = mapOf(
            "/" to ("index.html" to "text/html; charset=utf-8"), "/index.html" to ("index.html" to "text/html; charset=utf-8"),
            "/app.js" to ("app.js" to "text/javascript; charset=utf-8"), "/app.css" to ("app.css" to "text/css; charset=utf-8"),
            "/icon.svg" to ("icon.svg" to "image/svg+xml"),
        )
        val API = setOf("summary", "assets", "allocation", "staking", "activity", "chart")
    }

    fun handle(req: HttpRequest, ip: String, preview: Boolean = false): HttpResponse = secure(route(req, ip, preview))

    fun secure(r: HttpResponse): HttpResponse {
        r.headers.putIfAbsent("Content-Security-Policy", CSP)
        r.headers["X-Content-Type-Options"] = "nosniff"
        r.headers["X-Frame-Options"] = "DENY"
        r.headers["Referrer-Policy"] = "no-referrer"
        r.headers["Cache-Control"] = "no-store"
        r.headers["Cross-Origin-Opener-Policy"] = "same-origin"
        r.headers["Cross-Origin-Resource-Policy"] = "same-origin"
        r.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
        return r
    }

    private fun route(req: HttpRequest, ip: String, preview: Boolean): HttpResponse {
        val host = req.header("host")?.lowercase() ?: return HttpResponse.error(400, "Host ausente")
        if (preview) { if (host != PREVIEW_HOST) return HttpResponse.error(421, "Host não permitido") }
        else {
            if (host !in allowedHosts()) return HttpResponse.error(421, "Host não permitido")
            if (auth.isBlocked(ip)) return HttpResponse.error(403, "Este aparelho foi bloqueado no celular.")
            if (!general.allow(ip)) return HttpResponse.error(429, "Muitas requisições. Aguarde um pouco.").also { it.headers["Retry-After"] = "30" }
        }
        val get = req.method == "GET" || req.method == "HEAD"
        STATIC[req.path]?.let { (file, type) ->
            if (!get) return methodNotAllowed("GET")
            val b = assets(file) ?: return HttpResponse.error(404, "Não encontrado")
            return HttpResponse(200, type, b)
        }
        return when {
            req.path == "/auth/pair" -> if (req.method != "POST") methodNotAllowed("POST") else if (preview) HttpResponse.error(404, "Não encontrado") else pair(req, ip, host)
            req.path == "/auth/status" -> if (!get) methodNotAllowed("GET") else if (preview) HttpResponse.error(404, "Não encontrado") else status(req, ip)
            req.path == "/auth/logout" -> if (req.method != "POST") methodNotAllowed("POST") else logout(req, host)
            req.path.startsWith("/api/v1/") -> api(req, ip, preview, get)
            else -> HttpResponse.error(404, "Não encontrado")
        }
    }

    private fun methodNotAllowed(allow: String) = HttpResponse.error(405, "Método não permitido (painel só leitura).").also { it.headers["Allow"] = allow }

    private fun sameOrigin(req: HttpRequest, host: String): Boolean {
        val o = req.header("origin") ?: return false
        return o.equals("http://$host", ignoreCase = true) || o.equals("https://$host", ignoreCase = true)
    }

    private fun cookie(token: String, maxAgeSec: Long) = "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAgeSec" + if (secureCookie) "; Secure" else ""

    private fun pair(req: HttpRequest, ip: String, host: String): HttpResponse {
        if (!authLimit.allow(ip)) return HttpResponse.error(429, "Muitas tentativas. Aguarde um minuto.").also { it.headers["Retry-After"] = "60" }
        if (!sameOrigin(req, host)) return HttpResponse.error(403, "Origem não permitida")
        if (req.header("content-type")?.startsWith("application/json") != true) return HttpResponse.error(415, "Envie JSON")
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]{1,32})\"").find(req.body.toString(Charsets.UTF_8))?.groupValues?.get(1) ?: return HttpResponse.error(400, "Código ausente")
        return when (val r = auth.pair(code, ip, req.header("user-agent"))) {
            is PairResult.Approved -> HttpResponse.json(200, """{"status":"ok"}""").also { it.headers["Set-Cookie"] = cookie(r.token, (r.device.expiresAt - clock()) / 1000) }
            is PairResult.Pending -> { onPending(); HttpResponse.json(202, """{"status":"pending","poll":${WebJson.str(r.pollToken)}}""") }
            is PairResult.Invalid -> HttpResponse.json(401, """{"error":"Código incorreto.","attemptsLeft":${r.attemptsLeft}}""")
            is PairResult.Locked -> HttpResponse.json(423, """{"error":"Muitas tentativas erradas. Tente de novo em ${(r.retryAfterSec + 59) / 60} min.","retryAfter":${r.retryAfterSec}}""").also { it.headers["Retry-After"] = r.retryAfterSec.toString() }
            PairResult.NoCode -> HttpResponse.error(410, "Código expirado ou já usado. Gere um novo no celular.")
            PairResult.Blocked -> HttpResponse.error(403, "Este aparelho foi bloqueado no celular.")
        }
    }

    private fun status(req: HttpRequest, ip: String): HttpResponse {
        val poll = req.header("x-pair") ?: return HttpResponse.error(400, "Consulta inválida")
        if (poll.length > 64) return HttpResponse.error(400, "Consulta inválida")
        return when (val r = auth.poll(poll, ip)) {
            is PairResult.Pending -> HttpResponse.json(202, """{"status":"pending"}""")
            is PairResult.Approved -> HttpResponse.json(200, """{"status":"ok"}""").also { it.headers["Set-Cookie"] = cookie(r.token, (r.device.expiresAt - clock()) / 1000) }
            PairResult.Blocked -> HttpResponse.error(403, "Pedido recusado no celular.")
            else -> HttpResponse.error(410, "Pedido expirado. Gere um novo código no celular.")
        }
    }

    private fun logout(req: HttpRequest, host: String): HttpResponse {
        if (!sameOrigin(req, host)) return HttpResponse.error(403, "Origem não permitida")
        auth.logout(req.cookie(COOKIE))
        return HttpResponse.json(200, """{"status":"ok"}""").also { it.headers["Set-Cookie"] = "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" }
    }

    private fun api(req: HttpRequest, ip: String, preview: Boolean, get: Boolean): HttpResponse {
        if (!get) return methodNotAllowed("GET")
        if (!preview && req.header("x-requested-with") != "cpt") return HttpResponse.error(403, "Requisição não permitida")
        val name = req.path.removePrefix("/api/v1/")
        val device = if (preview) null else auth.authenticate(req.cookie(COOKIE), ip) ?: return HttpResponse.error(401, "Sessão expirada ou não pareada.")
        if (name == "session") return HttpResponse.json(200, """{"device":${WebJson.str(device?.name ?: "Prévia no app")},"expiresAt":${device?.expiresAt ?: 0},"preview":$preview}""")
        if (name !in API) return HttpResponse.error(404, "Não encontrado")
        val body = runCatching { data.get(name, req.query) }.getOrElse { return HttpResponse.error(503, "Dados indisponíveis agora. Tente de novo.") }
            ?: return HttpResponse.error(404, "Não encontrado")
        return HttpResponse.json(200, body)
    }
}
