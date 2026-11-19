package com.coinportracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coinportracker.CoinPortrackerApp
import com.coinportracker.dev.*
import com.coinportracker.onchain.Network
import com.coinportracker.ui.components.CpCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private fun repoOf(ctx: android.content.Context): DevConfigRepository =
    (ctx.applicationContext as? CoinPortrackerApp)?.container?.dev ?: DevConfigRepository(ctx)

private fun probeOf(ctx: android.content.Context): EndpointProbe =
    (ctx.applicationContext as? CoinPortrackerApp)?.container?.probe ?: EndpointProbe(com.coinportracker.network.HttpClient())

private fun newId() = UUID.randomUUID().toString().take(8)

@Composable
fun DevSettingsScreen(nav: androidx.navigation.NavHostController, section: String? = null) {
    val ctx = LocalContext.current
    val repo = remember { repoOf(ctx) }
    val probe = remember { probeOf(ctx) }
    val cfg by repo.config.collectAsState()
    val shown = remember(cfg) { DevDefaults.materialize(cfg) }
    when (section) {
        null, "hub" -> DevHub(nav, repo)
        "manual" -> DevManualScreen(nav)
        "chains" -> DevChainsScreen(nav, repo, shown)
        "prices" -> DevPricesScreen(nav, repo, shown, probe)
        "custom" -> DevCustomScreen(nav, repo, shown)
        "net" -> DevNetScreen(nav, repo, shown)
        "diag" -> DevDiagScreen(nav, repo, shown)
        "io" -> DevIoScreen(nav, repo)
        "tax" -> DevTaxScreen(nav)
        else -> if (section.startsWith("chain:")) DevChainEditScreen(nav, repo, shown, section.removePrefix("chain:"), probe)
        else DevHub(nav, repo)
    }
}

@Composable
private fun DevHub(nav: androidx.navigation.NavHostController, repo: DevConfigRepository) {
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Avançado (Dev)", { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            Text("Fontes, redes e diagnóstico. Só dados e endereços — nunca chave privada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            HubRow("Fontes por rede", "RPC e indexador, ordem, testar, failover") { nav.navigate("dev/chains") }
            HubRow("Fontes de preço", "Kraken, DefiLlama, CoinGecko…") { nav.navigate("dev/prices") }
            HubRow("Redes e tokens", "Cadeia EVM e contratos personalizados") { nav.navigate("dev/custom") }
            HubRow("Rede e intervalos", "Timeouts e limpar cache") { nav.navigate("dev/net") }
            HubRow("Diagnóstico", "Saúde das fontes e registros") { nav.navigate("dev/diag") }
            HubRow("Exportar e importar", "Configuração e backup cifrado") { nav.navigate("dev/io") }
            HubRow("Regras fiscais", "IRPF, isenção, códigos e alíquotas") { nav.navigate("dev/tax") }
            Spacer(Modifier.height(20.dp))
            val cfgNow by repo.config.collectAsState()
            val canUndo = remember(cfgNow) { repo.canUndo() }
            var confirmAll by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { if (canUndo) repo.undo() }, enabled = canUndo, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Desfazer última alteração") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Restaurar tudo ao padrão") }
            if (confirmAll) Confirm("Restaurar tudo ao padrão?", "As fontes, redes personalizadas e timeouts voltam ao original. Isso não apaga suas carteiras.") {
                if (it) repo.resetSection("all"); confirmAll = false
            }
        }
    }
}

@Composable
private fun HubRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DevChainsScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository, cfg: DevConfig) {
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Fontes por rede", { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            cfg.chains.forEach { ch ->
                val n = Network.byName(ch.chain)
                val on = ch.endpoints.count { it.enabled }
                HubRow(n?.label ?: ch.chain, com.coinportracker.core.Plural.of(on, "ligada", "ligadas") + if (ch.endpoints.size != on) " · " + com.coinportracker.core.Plural.of(ch.endpoints.size - on, "desligada", "desligadas") else "") {
                    nav.navigate("dev/chain:${ch.chain}")
                }
            }
            Spacer(Modifier.height(16.dp))
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Restaurar fontes das redes") }
            if (confirm) Confirm("Restaurar fontes das redes?", "Volta a lista padrão de RPC e indexadores.") { if (it) repo.resetSection("chains"); confirm = false }
        }
    }
}

@Composable
private fun DevChainEditScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository, cfg: DevConfig, chain: String, probe: EndpointProbe) {
    val n = Network.byName(chain)
    var list by remember(cfg, chain) { mutableStateOf(cfg.chains.firstOrNull { it.chain == chain }?.endpoints ?: DevDefaults.endpointsFor(n ?: Network.ETHEREUM)) }
    var addUrl by remember { mutableStateOf("") }
    var addKind by remember { mutableStateOf(EndpointKind.RPC) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var probes by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }
    fun commit(next: List<DevEndpoint>) {
        list = next
        val chains = cfg.chains.filterNot { it.chain == chain } + ChainSources(chain, next)
        val v = repo.save(cfg.copy(chains = chains))
        err = v.errors.firstOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        V2TopBar(n?.label ?: chain, { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            Text("A ordem é a do failover: o primeiro ligado que responder vence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            list.forEachIndexed { i, e ->
                CpCard(Modifier.padding(bottom = 8.dp), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.label.ifBlank { DevDefaults.hostOf(e.url) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (e.kind == "INDEXER") "Indexador" else "RPC"} · ${e.url}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            probes[e.id]?.let { Text(it.message, style = MaterialTheme.typography.labelSmall, color = if (it.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        CpSwitch(e.enabled, { on -> commit(list.map { if (it.id == e.id) it.copy(enabled = on) else it }) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (i > 0) commit(list.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }, enabled = i > 0) { Text("Subir") }
                        TextButton(onClick = { if (i < list.lastIndex) commit(list.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }, enabled = i < list.lastIndex) { Text("Descer") }
                        TextButton(onClick = {
                            scope.launch {
                                val r = probe.test(e.url, e.kind); probes = probes + (e.id to r)
                            }
                        }) { Text("Testar") }
                        TextButton(onClick = { commit(list.filterNot { it.id == e.id }) }) { Text("Remover") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(addUrl, { addUrl = it }, label = { Text("Novo endpoint (https://…)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = addKind == EndpointKind.RPC, onClick = { addKind = EndpointKind.RPC }, label = { Text("RPC") })
                FilterChip(selected = addKind == EndpointKind.INDEXER, onClick = { addKind = EndpointKind.INDEXER }, label = { Text("Indexador") })
                Button(onClick = {
                    val e = DevEndpoint(newId(), addUrl.trim(), true, addKind.name, DevDefaults.hostOf(addUrl.trim()))
                    val next = list + e
                    val v = DevValidator.validate(cfg.copy(chains = cfg.chains.filterNot { it.chain == chain } + ChainSources(chain, next)))
                    if (v.ok) { commit(next); addUrl = "" } else err = v.errors.first()
                }, enabled = addUrl.isNotBlank(), shape = RoundedCornerShape(12.dp)) { Text("Adicionar") }
            }
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Restaurar padrão desta rede") }
            if (confirm) Confirm("Restaurar esta rede?", "Volta os endpoints padrão de ${n?.label ?: chain}.") {
                if (it) {
                    val rest = cfg.chains.filterNot { c -> c.chain == chain }
                    repo.save(cfg.copy(chains = rest)); list = DevDefaults.endpointsFor(n ?: Network.ETHEREUM)
                }
                confirm = false
            }
        }
    }
}

@Composable
private fun DevPricesScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository, cfg: DevConfig, probe: EndpointProbe) {
    var list by remember(cfg) { mutableStateOf(cfg.prices) }
    var addUrl by remember { mutableStateOf("") }
    var addLabel by remember { mutableStateOf("") }
    var addFmt by remember { mutableStateOf("defillama") }
    var err by remember { mutableStateOf<String?>(null) }
    var probes by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    fun commit(next: List<PriceSource>) { list = next; err = repo.save(cfg.copy(prices = next)).errors.firstOrNull() }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Fontes de preço", { nav.popBackStack() }) { IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            list.forEachIndexed { i, p ->
                CpCard(Modifier.padding(bottom = 8.dp), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            Text(p.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            probes[p.id]?.let { Text(it.message, style = MaterialTheme.typography.labelSmall) }
                        }
                        CpSwitch(p.enabled, { on -> commit(list.map { if (it.id == p.id) it.copy(enabled = on) else it }) })
                    }
                    Row {
                        TextButton(onClick = { if (i > 0) commit(list.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }, enabled = i > 0) { Text("Subir") }
                        TextButton(onClick = { if (i < list.lastIndex) commit(list.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }, enabled = i < list.lastIndex) { Text("Descer") }
                        TextButton(onClick = { scope.launch { probes = probes + (p.id to probe.test(p.url, "API", p.format)) } }) { Text("Testar") }
                    }
                }
            }
            Text("Adicionar endpoint (formato já suportado)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            OutlinedTextField(addLabel, { addLabel = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(addUrl, { addUrl = it }, label = { Text("URL base (https://…)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("defillama" to "DefiLlama", "kraken" to "Kraken", "coingecko" to "CoinGecko").forEach { (k, l) ->
                    FilterChip(selected = addFmt == k, onClick = { addFmt = k }, label = { Text(l) })
                }
            }
            Button(onClick = {
                val p = PriceSource("custom-" + newId(), true, addUrl.trim(), addFmt, addLabel.trim().ifBlank { DevDefaults.hostOf(addUrl) })
                val next = list + p
                val v = DevValidator.validate(cfg.copy(prices = next))
                if (v.ok) { commit(next); addUrl = ""; addLabel = "" } else err = v.errors.first()
            }, enabled = addUrl.isNotBlank(), modifier = Modifier.padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("Adicionar") }
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(14.dp)) { Text("Restaurar fontes de preço") }
            if (confirm) Confirm("Restaurar fontes de preço?", "Volta à lista e à ordem padrão.") { if (it) { repo.resetSection("prices"); list = DevDefaults.prices() }; confirm = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevCustomScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository, cfg: DevConfig) {
    var name by remember { mutableStateOf("") }
    var chainId by remember { mutableStateOf("") }
    var rpc by remember { mutableStateOf("") }
    var explorer by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("18") }
    var tokChain by remember { mutableStateOf("ETHEREUM") }
    var tokContract by remember { mutableStateOf("") }
    var tokSymbol by remember { mutableStateOf("") }
    var tokDec by remember { mutableStateOf("18") }
    var err by remember { mutableStateOf<String?>(null) }
    val ctxCustom = LocalContext.current
    val probe = remember(ctxCustom) { probeOf(ctxCustom) }
    val scope = rememberCoroutineScope()
    var probes by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }
    fun save(c: DevConfig) { err = repo.save(c).errors.firstOrNull() }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Redes e tokens", { nav.popBackStack() }) { IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            Text("Rede EVM personalizada", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            cfg.customChains.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.name, style = MaterialTheme.typography.titleSmall)
                        Text("id ${n.chainId} · ${n.nativeSymbol}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        probes[n.id]?.let { Text(it.message, style = MaterialTheme.typography.labelSmall) }
                    }
                    CpSwitch(n.enabled, { on -> save(cfg.copy(customChains = cfg.customChains.map { if (it.id == n.id) it.copy(enabled = on) else it })) })
                    TextButton(onClick = { scope.launch { probes = probes + (n.id to probe.test(n.rpc, EndpointKind.RPC.name)) } }) { Text("Testar") }
                    TextButton(onClick = { save(cfg.copy(customChains = cfg.customChains.filterNot { it.id == n.id }, customTokens = cfg.customTokens.filterNot { it.chain == n.id })) }) { Text("Remover") }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(chainId, { chainId = it }, label = { Text("Chain id") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(rpc, { rpc = it }, label = { Text("RPC (https://…)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(explorer, { explorer = it }, label = { Text("API do explorador (opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(symbol, { symbol = it }, label = { Text("Símbolo") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(decimals, { decimals = it }, label = { Text("Decimais") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Button(onClick = {
                val n = CustomEvmChain(newId(), name.trim(), chainId.toLongOrNull() ?: 0, rpc.trim(), explorer.trim(), nativeSymbol = symbol.trim(), nativeDecimals = decimals.toIntOrNull() ?: 18, nativeName = name.trim())
                val next = cfg.copy(customChains = cfg.customChains + n)
                val v = DevValidator.validate(next)
                if (v.ok) { save(next); name = ""; chainId = ""; rpc = ""; explorer = ""; symbol = ""; decimals = "18" } else err = v.errors.first()
            }, enabled = name.isNotBlank() && rpc.isNotBlank(), modifier = Modifier.padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("Adicionar rede") }

            Spacer(Modifier.height(24.dp))
            Text("Token personalizado", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            cfg.customTokens.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.symbol} · ${Network.byName(t.chain)?.label ?: t.chain}", style = MaterialTheme.typography.titleSmall)
                        Text(t.contract, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CpSwitch(t.enabled, { on -> save(cfg.copy(customTokens = cfg.customTokens.map { if (it.id == t.id) it.copy(enabled = on) else it })) })
                    TextButton(onClick = { save(cfg.copy(customTokens = cfg.customTokens.filterNot { it.id == t.id })) }) { Text("Remover") }
                }
            }
            val chainOptions = Network.evm.map { it.name to it.label } + cfg.customChains.map { it.id to it.name }
            // seletor simples em chips
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chainOptions.forEach { (k, l) -> FilterChip(selected = tokChain == k, onClick = { tokChain = k }, label = { Text(l, maxLines = 1) }) }
            }
            OutlinedTextField(tokContract, { tokContract = it }, label = { Text("Contrato (0x…)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tokSymbol, { tokSymbol = it }, label = { Text("Símbolo") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(tokDec, { tokDec = it }, label = { Text("Decimais") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Button(onClick = {
                val t = CustomToken(newId(), tokChain, tokContract.trim(), tokSymbol.trim(), tokDec.toIntOrNull() ?: 18, tokSymbol.trim())
                val next = cfg.copy(customTokens = cfg.customTokens + t)
                val v = DevValidator.validate(next)
                if (v.ok) { save(next); tokContract = ""; tokSymbol = ""; tokDec = "18" } else err = v.errors.first()
            }, enabled = tokContract.isNotBlank() && tokSymbol.isNotBlank(), modifier = Modifier.padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("Adicionar token") }
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(14.dp)) { Text("Remover redes e tokens personalizados") }
            if (confirm) Confirm("Remover personalizados?", "Só as redes e tokens que você adicionou aqui. As carteiras permanecem.") { if (it) repo.resetSection("custom"); confirm = false }
        }
    }
}

@Composable
private fun DevNetScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository, cfg: DevConfig) {
    var connect by remember(cfg) { mutableStateOf(cfg.net.connectTimeoutSec.toString()) }
    var read by remember(cfg) { mutableStateOf(cfg.net.readTimeoutSec.toString()) }
    var call by remember(cfg) { mutableStateOf(cfg.net.callTimeoutSec.toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf(false) }
    var confirmCache by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Rede e intervalos", { nav.popBackStack() }) { IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            OutlinedTextField(connect, { connect = it }, label = { Text("Timeout de conexão (s)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(read, { read = it }, label = { Text("Timeout de leitura (s)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(call, { call = it }, label = { Text("Timeout da chamada (s)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                val n = DevNetSettings(connect.toIntOrNull() ?: 0, read.toIntOrNull() ?: 0, call.toIntOrNull() ?: 0)
                val v = repo.save(cfg.copy(net = n)); err = v.errors.firstOrNull(); if (v.ok) err = "Salvo"
            }, Modifier.padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("Salvar") }
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { nav.navigate("settings/atualizacao") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Abrir atualização do saldo") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirmCache = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Limpar cache") }
            if (cleared) Text("Cache limpo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (confirmCache) Confirm("Limpar cache?", "Apaga cotações e listas em cache. Os saldos e carteiras ficam. Na próxima atualização tudo é buscado de novo.") {
                if (it) scope.launch {
                    val app = ctx.applicationContext as? CoinPortrackerApp
                    if (app != null) {
                        withContext(Dispatchers.IO) {
                            app.container.db.marketCache().clearAll()
                            app.container.db.quotes().clearAll()
                            app.container.db.priceHistory().clearAll()
                            java.io.File(app.cacheDir, "tokenlists").deleteRecursively()
                        }
                    }
                    cleared = true
                }
                confirmCache = false
            }
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp)) { Text("Restaurar timeouts") }
            if (confirm) Confirm("Restaurar timeouts?", "Volta para 10 / 20 / 30 s.") { if (it) repo.resetSection("net"); confirm = false }
        }
    }
}

@Composable
private fun DevDiagScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository, cfg: DevConfig) {
    var redact by remember(cfg) { mutableStateOf(cfg.logRedactAddresses) }
    var tick by remember { mutableIntStateOf(0) }
    val failing = remember(tick) { DevHealth.failing() }
    val logs = remember(tick, redact) { AppLog.lines(redact) }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Diagnóstico", { nav.popBackStack() }) { IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            Text("Fontes com falha", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            if (failing.isEmpty()) Text("Nenhuma falha registrada nesta sessão.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            failing.forEach { h ->
                Text("${DevDefaults.hostOf(h.url)} · ${h.message}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registros", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { tick++ }) { Text("Atualizar") }
                TextButton(onClick = { AppLog.clear(); tick++ }) { Text("Limpar") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Ocultar endereços", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                CpSwitch(redact, { on -> redact = on; repo.save(cfg.copy(logRedactAddresses = on)); tick++ })
            }
            logs.takeLast(80).asReversed().forEach { l ->
                Text("${l.tag}  ${l.msg}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp), maxLines = 3)
            }
        }
    }
}

@Composable
private fun DevIoScreen(nav: androidx.navigation.NavHostController, repo: DevConfigRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            // "wt": trunca o arquivo (com "w" alguns provedores deixam lixo do arquivo antigo no fim)
            val r = runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(repo.exportJson().toByteArray()) } }
            withContext(Dispatchers.Main) { msg = if (r.isSuccess) "Configuração exportada." else "Falha ao exportar: ${r.exceptionOrNull()?.message}" }
        }
    }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pendingImport = uri
    }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Exportar e importar", { nav.popBackStack() }) { IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            Text("A configuração avançada (fontes, redes, tokens, timeouts). Não inclui carteiras.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { export.launch("coinportracker-dev.json") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Exportar configuração") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Importar configuração") }
            if (pendingImport != null) Confirm("Importar configuração?", "A configuração avançada atual será substituída pela do arquivo. As carteiras não mudam. A alteração pode ser desfeita se algo der errado.") { ok ->
                val uri = pendingImport
                pendingImport = null
                if (ok && uri != null) scope.launch(Dispatchers.IO) {
                    val raw = runCatching { ctx.contentResolver.openInputStream(uri)?.use { com.coinportracker.core.BoundedRead.text(it, com.coinportracker.core.BoundedRead.DEV_CONFIG) } }
                        .getOrElse { e -> withContext(Dispatchers.Main) { msg = e.message ?: "Não foi possível ler o arquivo." }; return@launch } ?: return@launch
                    val v = repo.importJson(raw)
                    withContext(Dispatchers.Main) { msg = if (v.ok) "Configuração importada." else v.errors.first() }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Backup completo (carteiras e dados), cifrado — o mesmo de Segurança e backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.navigate("settings/seguranca") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Abrir segurança e backup") }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Confirm(title: String, body: String, on: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = { on(false) }, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = { on(true) }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = { on(false) }) { Text("Cancelar") } })
}


@Composable
private fun DevTaxScreen(nav: androidx.navigation.NavHostController) {
    val ctx = LocalContext.current
    val repo = remember { (ctx.applicationContext as? CoinPortrackerApp)?.container?.tax ?: com.coinportracker.tax.TaxRulesRepository(ctx) }
    val rules by repo.rules.collectAsState(com.coinportracker.tax.TaxRules.default())
    val scope = rememberCoroutineScope()
    var exemption by remember(rules) { mutableStateOf(rules.monthlyExemptionBrl) }
    var threshold by remember(rules) { mutableStateOf(rules.declarationThresholdBrl) }
    var darf by remember(rules) { mutableStateOf(rules.darfCode) }
    var confirm by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { val json = repo.exportJson(); withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } } } }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            // leitura fora da thread principal e com teto de tamanho
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { com.coinportracker.core.BoundedRead.text(it, com.coinportracker.core.BoundedRead.TAX_RULES) } }.getOrNull() }
            if (text == null) { importMsg = "Importação recusada: arquivo ilegível ou grande demais."; return@launch }
            importMsg = runCatching { repo.importJson(text) }.fold(
                { "Regras importadas." },
                { e -> "Importação recusada: " + (e.message?.take(140)?.takeIf { it.isNotBlank() } ?: "arquivo inválido.") })
        }
    }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Regras fiscais", { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate("dev/manual") }) { Icon(Icons.Outlined.HelpOutline, "Ajuda") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 8.dp, 16.dp, 32.dp)) {
            Text("Regras vigentes em ${rules.effectiveFrom}  ·  versão ${rules.version}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(rules.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(rules.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            importMsg?.let { m ->
                Spacer(Modifier.height(8.dp))
                Text(m, style = MaterialTheme.typography.bodySmall, color = if (m.startsWith("Importação recusada")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(exemption, { exemption = it }, Modifier.fillMaxWidth(), label = { Text("Isenção mensal (R$)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(threshold, { threshold = it }, Modifier.fillMaxWidth(), label = { Text("Limite para declarar por espécie (R$)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(darf, { darf = it }, Modifier.fillMaxWidth(), label = { Text("Código do DARF") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { scope.launch { runCatching { repo.save(rules.copy(monthlyExemptionBrl = exemption.trim().replace(",", "."), declarationThresholdBrl = threshold.trim().replace(",", "."), darfCode = darf.trim())) }.onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Regras inválidas", android.widget.Toast.LENGTH_LONG).show() } } }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Salvar") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { saveDoc.launch("coinportracker-regras-fiscais.json") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Exportar regras") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openDoc.launch(arrayOf("application/json", "*/*")) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Importar regras") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Restaurar padrão") }
            if (confirm) Confirm("Restaurar regras padrão?", "Volta à tabela vigente embarcada no aplicativo.") {
                if (it) scope.launch { repo.reset() }; confirm = false
            }
            Spacer(Modifier.height(20.dp))
            Text("Fontes", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            rules.sources.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp)) }
        }
    }
}
