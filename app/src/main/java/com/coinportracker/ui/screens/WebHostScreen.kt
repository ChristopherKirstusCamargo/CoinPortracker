package com.coinportracker.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.settings.WebHostPrefs
import com.coinportracker.ui.components.CpCard
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.webhost.HttpRequest
import com.coinportracker.webhost.LanAddress
import com.coinportracker.webhost.WebHostController
import com.coinportracker.webhost.WebHostService
import com.coinportracker.webhost.WebRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v6.0 — Web Host: o painel do portfólio no navegador do computador, na mesma rede Wi-Fi. Tudo se controla aqui
 * (ligar, parar, código, aprovar, aparelhos, bloqueio, ajustes); o navegador só lê.
 */
@Composable
fun WebHostScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val h by vm.hub.collectAsStateWithLifecycle()
    val p = h.settings.web
    val st by WebHostController.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    var help by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { WebHostController.refresh(ctx) } }
    LaunchedEffect(p) { WebHostController.applyPrefs(ctx, p) }
    // relógio para a contagem do código (só enquanto há código)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.code) { while (st.code != null) { now = System.currentTimeMillis(); delay(1000) } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { WebHostService.start(ctx) }
    fun startHost() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        else WebHostService.start(ctx)
    }
    fun save(f: (WebHostPrefs) -> WebHostPrefs) = vm.updateSettings { it.copy(web = f(it.web)) }

    Column(Modifier.fillMaxSize()) {
        V2TopBar("Web Host", { nav.popBackStack() }) { TextButton(onClick = { help = true }) { Text("?", style = MaterialTheme.typography.titleMedium) } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            CpCard {
                Text("CoinPortracker Web", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Veja seu portfólio no navegador do computador, na mesma rede Wi-Fi. Somente leitura: nada pode ser alterado pelo navegador.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                val statusText = when {
                    st.starting -> "Ligando…"
                    st.running && st.addresses.isEmpty() -> "Ligado, mas sem Wi-Fi: conecte o celular a uma rede Wi-Fi (ou ligue o roteador do celular)."
                    st.running -> "Ligado · ${st.devices.size.let { if (it == 1) "1 aparelho pareado" else "$it aparelhos pareados" }}"
                    else -> st.error ?: st.stoppedReason ?: "Desligado"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (st.running) LocalExtra.current.gain else MaterialTheme.colorScheme.onSurfaceVariant))
                    Spacer(Modifier.width(10.dp))
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                if (st.running || st.starting) OutlinedButton(onClick = { WebHostService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Parar") }
                else Button(onClick = { startHost() }, colors = cpButtonColors(), modifier = Modifier.fillMaxWidth()) { Text("Ligar o painel") }
                Text("Conexão sem criptografia (HTTP) — use só no seu Wi-Fi de confiança. Enquanto estiver ligado, uma notificação fixa mostra isso.",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn, modifier = Modifier.padding(top = 10.dp))
            }

            if (st.running) {
                val url = st.pairUrl()
                if (url != null && st.code != null) {
                    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(url) { qr = withContext(Dispatchers.Default) { runCatching { com.coinportracker.receive.QrEncoder.encode(url, 480).asImageBitmap() }.getOrNull() } }
                    Group("Parear um navegador") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            qr?.let { Image(it, "QR para abrir o painel", Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))) } ?: Box(Modifier.size(220.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(st.code!!.chunked(4).joinToString("-"), fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                            val left = ((st.codeExpiresAt - now) / 1000).coerceAtLeast(0)
                            Text(if (left > 0) "Vale por ${left / 60}:${"%02d".format(left % 60)} · uso único" else "Código expirado",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { WebHostController.newCode(ctx) }) { Text("Gerar novo código") }
                        }
                        Note("Leia o QR com a câmera do celular/tablet, ou abra o endereço abaixo no computador e digite o código.")
                    }
                } else if (st.addresses.isNotEmpty()) Group("Parear um navegador") {
                    LinkRow("Gerar código de pareamento", "Necessário para cada navegador novo") { WebHostController.newCode(ctx) }
                }
                if (st.addresses.isNotEmpty()) Group("Endereço") {
                    st.addresses.forEach { a ->
                        LinkRow(st.url(a)!!, a.kind.label + (if (a.kind == LanAddress.Kind.TAILSCALE) " · cifrado pelo Tailscale" else " · HTTP"), "Copiar") { clip.setText(AnnotatedString(st.url(a)!!)) }
                    }
                    if (p.mdns) Note(if (st.mdns) "Também por nome: ${st.mdnsUrl} (funciona na maioria das redes; se não abrir, use o IP)." else "O nome coinportracker.local não pôde ser anunciado nesta rede. Use o IP.")
                    Note("Se o IP mudar (troca de rede), o painel continua no novo endereço; é só abrir o novo IP.")
                }
                if (st.pending.isNotEmpty()) Group("Pedidos de acesso") {
                    st.pending.forEach { r ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            Text("IP ${r.ip} · há ${((now - r.at) / 1000).coerceAtLeast(0)} s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { WebHostController.approve(ctx, r.id) }, colors = cpButtonColors()) { Text("Aprovar") }
                                OutlinedButton(onClick = { WebHostController.deny(ctx, r.id) }) { Text("Recusar") }
                                TextButton(onClick = { WebHostController.block(ctx, r.ip) }) { Text("Bloquear IP") }
                            }
                        }
                    }
                }
            }

            Group("Aparelhos pareados") {
                if (st.devices.isEmpty()) Note("Nenhum aparelho pareado.")
                st.devices.forEach { d ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text("IP ${d.ip} · visto ${Fmt.ago(d.lastSeen)} · expira ${Fmt.dateTime(d.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { WebHostController.revoke(ctx, d.id) }) { Text("Desconectar") }
                            TextButton(onClick = { WebHostController.block(ctx, d.ip) }) { Text("Bloquear IP") }
                        }
                    }
                }
                if (st.devices.isNotEmpty()) LinkRow("Encerrar todas as sessões", "Todos os navegadores precisarão parear de novo") { WebHostController.revokeAll(ctx) }
                st.blocked.forEach { ip -> LinkRow("IP bloqueado: $ip", null, "Desbloquear") { WebHostController.unblock(ctx, ip) } }
            }

            Group("Acesso") {
                SwitchRow("Aprovar cada novo aparelho", "Além do código, você confirma no celular", p.requireApproval) { v -> save { it.copy(requireApproval = v) } }
                ChoiceRow("Sessão dura", WebHostPrefs.SESSION_CHOICES, p.sessionHours, { if (it < 24) "$it h" else if (it == 24) "1 dia" else "7 dias" }) { v -> save { it.copy(sessionHours = v) } }
                SwitchRow("Prender a sessão ao IP", "Se o computador mudar de IP, precisa parear de novo", p.bindToIp) { v -> save { it.copy(bindToIp = v) } }
                ChoiceRow("Desligar sozinho sem acessos", WebHostPrefs.IDLE_CHOICES, p.autoStopMin, { when (it) { 0 -> "Nunca"; 15 -> "15 min"; 60 -> "1 h"; else -> "4 h" } }) { v -> save { it.copy(autoStopMin = v) } }
            }
            Group("Rede") {
                SwitchRow("Nome coinportracker.local", "Anuncia o painel por nome na rede local (mDNS)", p.mdns) { v -> save { it.copy(mdns = v) } }
                SwitchRow("Permitir Tailscale", "Aceita acesso pela sua rede Tailscale (cifrada). Veja o “?”", p.allowTailscale) { v -> save { it.copy(allowTailscale = v) } }
                var port by remember(p.port) { mutableStateOf(p.port.toString()) }
                val pv = port.toIntOrNull()
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Porta") }, singleLine = true, enabled = !st.running && !st.starting,
                    isError = pv == null || pv !in 1024..65535, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(if (st.running) "Pare o painel para trocar a porta." else "Entre 1024 e 65535 (padrão 8787).") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LaunchedEffect(pv) { if (pv != null && pv in 1024..65535 && pv != p.port) { delay(600); save { it.copy(port = pv) } } }
                Note("Porta, nome e Tailscale valem na próxima vez que o painel for ligado.")
            }

            Group("Prévia") {
                LinkRow(if (preview) "Fechar prévia" else "Ver prévia do painel", "O mesmo painel do navegador, aqui dentro, sem rede") { preview = !preview }
                if (preview) WebPreview(Modifier.fillMaxWidth().height(620.dp).padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)))
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, confirmButton = { TextButton(onClick = { help = false }) { Text("Ok") } }, title = { Text("Como funciona") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "1. Conecte o celular e o computador à mesma rede Wi-Fi.",
                "2. Toque em Ligar o painel. Uma notificação fixa fica visível enquanto ele estiver ligado.",
                "3. No computador, abra o endereço mostrado (ex.: http://192.168.0.10:8787) e digite o código. No celular/tablet, leia o QR.",
                "4. Aprove o pedido aqui ou pela notificação. Pronto: o painel atualiza sozinho a cada 15 s.",
                "Segurança: só aparelhos da sua rede local conseguem abrir; cada navegador precisa do código de uso único (e da sua aprovação); a sessão expira; você desconecta ou bloqueia qualquer aparelho aqui; erros repetidos bloqueiam o IP. O navegador só lê — não há como alterar nada por lá.",
                "Criptografia: na rede local a conexão é HTTP, sem criptografia. Quem controla a mesma rede pode ler o tráfego. Use só em redes de confiança. Um certificado HTTPS próprio faria o navegador mostrar alerta de risco, então o app não finge ser seguro.",
                "Tailscale (opcional, gratuito para uso pessoal): instale o app Tailscale no celular e no computador com a mesma conta, ligue \"Permitir Tailscale\" aqui e abra http://<IP 100.x do celular>:8787. O tráfego vai cifrado pelo WireGuard do Tailscale. Sem túneis públicos e sem serviços pagos.",
                "Consumo: o servidor é leve e para sozinho após o tempo sem acesso que você escolher. Ele nunca liga sozinho.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    })
}

/** Prévia local: o WebView carrega o painel de um endereço interno, servido direto pelo roteador (sem rede, sem arquivos). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPreview(modifier: Modifier) {
    val ctx = LocalContext.current
    val router = remember { WebHostController.router(ctx) }
    AndroidView(modifier = modifier, factory = { c ->
        WebView(c).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.setGeolocationEnabled(false)
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != WebRouter.PREVIEW_HOST
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val u = request.url
                    if (u.host != WebRouter.PREVIEW_HOST || u.scheme != "https") return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    val q = u.queryParameterNames.associateWith { u.getQueryParameter(it).orEmpty() }
                    val headers = request.requestHeaders.mapKeys { it.key.lowercase() } + ("host" to WebRouter.PREVIEW_HOST)
                    val r = router.handle(HttpRequest(request.method, u.path ?: "/", q, headers, ByteArray(0)), "preview", preview = true)
                    val type = r.contentType.substringBefore(';').trim()
                    return WebResourceResponse(type, "utf-8", r.status, com.coinportracker.webhost.HttpParser.reason(r.status), r.headers, java.io.ByteArrayInputStream(r.body))
                }
            }
            loadUrl("https://${WebRouter.PREVIEW_HOST}/")
        }
    }, onRelease = { it.stopLoading(); it.destroy() })
}
