package com.coinportracker.webhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.coinportracker.CoinPortrackerApp
import com.coinportracker.data.AppContainer
import com.coinportracker.data.ChartRange
import com.coinportracker.settings.WebHostPrefs
import com.coinportracker.ui.ChartData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.ConcurrentHashMap

data class WebHostState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val port: Int = 0,
    val addresses: List<LanAddress> = emptyList(),
    val mdns: Boolean = false,
    val code: String? = null,
    val codeExpiresAt: Long = 0,
    val pending: List<PendingPair> = emptyList(),
    val devices: List<WebDevice> = emptyList(),
    val blocked: List<String> = emptyList(),
    val lastAccessAt: Long = 0,
    val startedAt: Long = 0,
    val stoppedReason: String? = null,
) {
    val primary: LanAddress? get() = addresses.firstOrNull()
    fun url(a: LanAddress? = primary) = a?.let { "http://${it.ip}:$port/" }
    fun pairUrl() = primary?.let { a -> code?.let { "http://${a.ip}:$port/#p=$it" } }
    val mdnsUrl: String get() = "http://${Mdns.HOST}:$port/"
}

/**
 * v6.0 — controle único do Web Host (um servidor por vez). Liga só por ação sua (pelo serviço em primeiro plano, com
 * notificação), para sozinho após o tempo sem acesso escolhido e nunca liga de novo por conta própria.
 * Toda a administração (código, aprovação, aparelhos, bloqueio, parar) fica no app; o navegador só lê.
 */
object WebHostController {
    private val _state = MutableStateFlow(WebHostState())
    val state: StateFlow<WebHostState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: WebServer? = null
    private var jobs = mutableListOf<Job>()
    private var mdnsSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var prefs = WebHostPrefs()
    @Volatile private var lastAccess = 0L
    private var authRef: WebAuth? = null
    private var routerRef: WebRouter? = null
    private val chartCache = ConcurrentHashMap<ChartRange, Pair<Long, ChartData?>>()
    /** Avisado quando chega um pedido de pareamento (o serviço mostra a notificação com Aprovar/Recusar). */
    @Volatile var onPendingChanged: (() -> Unit)? = null

    private fun container(ctx: Context): AppContainer = (ctx.applicationContext as CoinPortrackerApp).container
    private fun stateFile(ctx: Context) = File(File(ctx.noBackupFilesDir, "webhost").apply { mkdirs() }, "state.json")

    @Synchronized fun auth(ctx: Context): WebAuth = authRef ?: run {
        val f = stateFile(ctx)
        WebAuth(WebAuthStore.decode(runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull()), persist = { st ->
            runCatching { val tmp = File(f.parentFile, "state.json.tmp"); tmp.writeText(WebAuthStore.encode(st)); tmp.renameTo(f) }
        }).also { authRef = it }
    }

    /** Roteador (o mesmo do servidor e da prévia dentro do app). */
    @Synchronized fun router(ctx: Context): WebRouter = routerRef ?: run {
        val app = ctx.applicationContext
        WebRouter(auth(app), data(app), assets = { name -> runCatching { app.assets.open("web/$name").use { it.readBytes() } }.getOrNull() },
            allowedHosts = { hosts() }, onPending = { refresh(app); onPendingChanged?.invoke() }).also { routerRef = it }
    }

    private fun hosts(): Set<String> {
        val st = _state.value
        val p = st.port
        return (st.addresses.flatMap { listOf("${it.ip}:$p") } + listOf("${Mdns.HOST}:$p", "127.0.0.1:$p", "localhost:$p")).map { it.lowercase() }.toSet() +
            (if (p == 80) st.addresses.map { it.ip } + Mdns.HOST else emptyList())
    }

    private fun data(app: Context) = WebData { endpoint, q ->
        val c = container(app)
        runBlocking {
            withTimeout(12_000) {
                val h = c.hub.state.value
                when (endpoint) {
                    "summary" -> WebSnapshot.summary(h)
                    "assets" -> WebSnapshot.assets(h)
                    "allocation" -> WebSnapshot.allocation(h)
                    "staking" -> WebSnapshot.staking(h)
                    "activity" -> WebSnapshot.activity(h, c.db.chain().recentActivity(600), runCatching { c.history.stakeSignatures.first() }.getOrDefault(emptySet()), q)
                    "chart" -> {
                        val r = WebSnapshot.range(q)
                        val now = System.currentTimeMillis()
                        val cached = chartCache[r]?.takeIf { now - it.first < 30_000 }?.second
                        val d = cached ?: runCatching { c.history.chart(r, h, emptyList(), c.portfolio.state.value.assets) }.getOrNull()
                            ?.let { ChartData(r, it.points, it.result, it.source) }.also { chartCache[r] = now to it }
                        WebSnapshot.chart(h, r, d)
                    }
                    else -> null
                }
            }
        }
    }

    fun refresh(ctx: Context) {
        val a = auth(ctx)
        _state.update { it.copy(pending = a.pendingList(), devices = a.devices(), blocked = a.blockedIps(), code = a.currentCode()?.first, codeExpiresAt = a.currentCode()?.second ?: 0, lastAccessAt = lastAccess) }
    }

    /** Chamado pelo serviço. Não faz nada se já estiver ligado. */
    @Synchronized fun start(ctx: Context, onStopped: (String?) -> Unit) {
        if (server != null) return
        val app = ctx.applicationContext
        _state.value = WebHostState(starting = true)
        val c = container(app)
        prefs = runBlocking { runCatching { c.settings.current().web }.getOrDefault(WebHostPrefs()) }
        val a = auth(app)
        a.sessionHours = prefs.sessionHours; a.requireApproval = prefs.requireApproval; a.bindToIp = prefs.bindToIp
        val addrs = WebNet.addresses(prefs.allowTailscale)
        _state.value = WebHostState(starting = true, port = prefs.port, addresses = addrs)
        val r = router(app)
        val srv = WebServer(prefs.port, r, allowTailscale = { prefs.allowTailscale }, onAccess = { lastAccess = System.currentTimeMillis() })
        try { srv.start() } catch (e: Exception) {
            _state.value = WebHostState(error = if (e is java.net.BindException) "A porta ${prefs.port} já está em uso. Escolha outra em Ajustes do Web Host." else "Não foi possível ligar: ${e.message}")
            onStopped(_state.value.error); return
        }
        server = srv
        lastAccess = System.currentTimeMillis()
        a.newCode()
        _state.value = WebHostState(running = true, port = prefs.port, addresses = addrs, startedAt = System.currentTimeMillis())
        refresh(app)
        watchNetwork(app)
        if (prefs.mdns) startMdns(app)
        jobs += scope.launch {
            while (isActive) {
                delay(15_000)
                refresh(app)
                val idle = prefs.autoStopMin
                if (idle > 0 && System.currentTimeMillis() - lastAccess > idle * 60_000L) { stop(app, "Desligado após $idle min sem acessos."); onStopped("Desligado após $idle min sem acessos."); break }
            }
        }
    }

    @Synchronized fun stop(ctx: Context, reason: String? = null) {
        jobs.forEach { it.cancel() }; jobs.clear()
        server?.stop(); server = null
        stopMdns()
        netCallback?.let { cb -> runCatching { (ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }
        netCallback = null
        authRef?.clearCode()
        chartCache.clear()
        _state.value = WebHostState(stoppedReason = reason, devices = authRef?.devices().orEmpty(), blocked = authRef?.blockedIps().orEmpty())
    }

    fun newCode(ctx: Context) { if (server != null) { auth(ctx).newCode(); refresh(ctx) } }
    fun approve(ctx: Context, id: String) { auth(ctx).approve(id); refresh(ctx); onPendingChanged?.invoke() }
    fun deny(ctx: Context, id: String) { auth(ctx).deny(id); refresh(ctx); onPendingChanged?.invoke() }
    fun revoke(ctx: Context, id: String) { auth(ctx).revoke(id); refresh(ctx) }
    fun revokeAll(ctx: Context) { auth(ctx).revokeAll(); refresh(ctx) }
    fun block(ctx: Context, ip: String) { auth(ctx).block(ip); refresh(ctx) }
    fun unblock(ctx: Context, ip: String) { auth(ctx).unblock(ip); refresh(ctx) }

    /** Aplica na hora as preferências de sessão (porta, mDNS e Tailscale valem ao ligar de novo). */
    fun applyPrefs(ctx: Context, p: WebHostPrefs) {
        prefs = prefs.copy(sessionHours = p.sessionHours, requireApproval = p.requireApproval, bindToIp = p.bindToIp, autoStopMin = p.autoStopMin)
        auth(ctx).apply { sessionHours = p.sessionHours; requireApproval = p.requireApproval; bindToIp = p.bindToIp }
    }

    private fun watchNetwork(app: Context) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) = update()
            override fun onLost(network: AndroidNetwork) = update()
            override fun onLinkPropertiesChanged(network: AndroidNetwork, lp: android.net.LinkProperties) = update()
            fun update() { scope.launch { delay(800); _state.update { it.copy(addresses = WebNet.addresses(prefs.allowTailscale)) } } }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun startMdns(app: Context) {
        val port = prefs.port
        // anúncio padrão do Android (aparece como "CoinPortracker" em navegadores/apps que listam serviços)
        runCatching {
            val m = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply { serviceName = "CoinPortracker"; serviceType = "_http._tcp"; this.port = port }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = m; nsdListener = l
        }
        // resposta para coinportracker.local (melhor esforço)
        scope.launch {
            try {
                val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("cpt-mdns").apply { setReferenceCounted(false); acquire() }
                val group = InetAddress.getByName("224.0.0.251")
                val sock = MulticastSocket(5353).apply { reuseAddress = true; timeToLive = 255 }
                @Suppress("DEPRECATION") sock.joinGroup(group)
                mdnsSocket = sock
                _state.update { it.copy(mdns = true) }
                val buf = ByteArray(1500)
                while (isActive && !sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: Exception) { break }
                    val qs = Mdns.parseQuestions(pkt.data, pkt.length) ?: continue
                    if (!Mdns.asksForUs(qs)) continue
                    val from = pkt.address?.hostAddress ?: continue
                    if (!WebNet.remoteAllowed(from, prefs.allowTailscale)) continue
                    val ip = _state.value.addresses.firstOrNull { it.kind != LanAddress.Kind.TAILSCALE && sameSubnet(it.ip, from) }?.ip
                        ?: _state.value.addresses.firstOrNull { it.kind != LanAddress.Kind.TAILSCALE }?.ip ?: continue
                    val ans = Mdns.answer(ip)
                    runCatching { sock.send(DatagramPacket(ans, ans.size, group, 5353)) }
                    if (qs.any { it.third }) runCatching { sock.send(DatagramPacket(ans, ans.size, pkt.address, pkt.port)) }
                }
            } catch (_: Exception) {
                _state.update { it.copy(mdns = false) }
            }
        }
    }

    private fun sameSubnet(a: String, b: String) = a.substringBeforeLast('.') == b.substringBeforeLast('.')

    private fun stopMdns() {
        runCatching { mdnsSocket?.close() }; mdnsSocket = null
        runCatching { multicastLock?.release() }; multicastLock = null
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }; nsd = null; nsdListener = null
    }
}
