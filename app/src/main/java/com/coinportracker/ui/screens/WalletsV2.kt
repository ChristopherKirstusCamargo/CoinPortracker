package com.coinportracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.data.WalletSummary
import com.coinportracker.domain.WalletType
import com.coinportracker.onchain.AddressKind
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.Refreshable
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.reveal

@Composable
fun WalletsV2Screen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<WalletSummary?>(null) }
    var rename by remember { mutableStateOf<WalletSummary?>(null) }
    var remove by remember { mutableStateOf<WalletSummary?>(null) }
    var newManual by remember { mutableStateOf(false) }
    val clipCtx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize()) {
        V2TopBar("Carteiras", { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate("addWallet") }) { Icon(Icons.Outlined.Add, "Adicionar carteira") }
        }
        Refreshable(refreshing, { vm.fullSync(true) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "total") {
                    Text("Visão combinada: ${h.money(h.agg.totalUsd)} em " + (if (h.wallets.size == 1) "1 carteira" else "${h.wallets.size} carteiras"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sync.warnings.isNotEmpty()) Text("Avisos da última sincronização: " + sync.warnings.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn, modifier = Modifier.padding(top = 4.dp))
                }
                items(h.wallets, key = { it.id }) { w ->
                    CpCard(Modifier.animateItem(fadeInSpec = com.coinportracker.ui.theme.Motion.fade(), placementSpec = com.coinportracker.ui.theme.Motion.itemPlacement, fadeOutSpec = com.coinportracker.ui.theme.Motion.fade(150)).reveal(), onClick = { menuFor = w }) {
                        // linha 1: selo + nome (linha inteira para o nome, sem disputar espaço com o valor)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WalletBadge(w.kind)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (w.watch) walletAddressShort(w.address) + " · " + (w.kind?.label?.substringBefore(" (") ?: "") else "Manual (operações registradas)",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // linha 2: valor + lucro
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            FitText(h.money(w.valueUsd), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp))
                            w.pnlUsd?.takeIf { it.signum() != 0 }?.let { ChangePill(h.signed(it).removePrefix("+").removePrefix("−").removePrefix("-"), upOf(it)) }
                        }
                        // linha 3: redes (ícones sobrepostos, discretos) + status
                        if (w.watch) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width((16 + 11 * (w.networks.take(6).size - 1).coerceAtLeast(0)).dp).height(16.dp)) {
                                    w.networks.take(6).forEachIndexed { i, n -> NetworkDot(n, Modifier.offset(x = (11 * i).dp).size(16.dp)) }
                                }
                                if (w.networks.isNotEmpty()) Spacer(Modifier.width(8.dp))
                                Text(when {
                                    w.error != null -> "Falha: ${w.error.take(40)}"
                                    w.lastSyncAt == null -> "Sincronizando…"
                                    else -> (if (w.networks.size == 1) w.networks.first().label else "${w.networks.size} redes") + " · atualizada ${Fmt.ago(w.lastSyncAt)}"
                                }, style = MaterialTheme.typography.labelSmall, color = if (w.error != null) LocalExtra.current.warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { w ->
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(w.name) }, text = {
            Column {
                if (w.address != null) {
                    Text(w.address, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(onClick = { menuFor = null; com.coinportracker.ui.TabNav.switchTab(nav, "assets") }) { Text("Ver ativos (Ativos › Carteiras)") }
                if (w.watch) TextButton(onClick = { vm.syncWallet(w.id); menuFor = null }) { Text("Sincronizar agora") }
                if (w.address != null) TextButton(onClick = { com.coinportracker.security.SecureClipboard.copy(clipCtx, "Endereço", w.address); vm.toast("Endereço copiado."); menuFor = null }) { Text("Copiar endereço") }
                TextButton(onClick = { rename = w; menuFor = null }) { Text("Renomear") }
                TextButton(onClick = { remove = w; menuFor = null }) { Text("Remover", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Fechar") } })
    }
    rename?.let { w ->
        var name by remember { mutableStateOf(w.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Renomear carteira") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = { TextButton(onClick = { vm.renameWallet(w.id, name); rename = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancelar") } })
    }
    remove?.let { w ->
        ConfirmDialog("Remover ${w.name}?", "Os saldos dessa carteira saem do app. Operações registradas continuam no histórico. Nada muda na blockchain.", "Remover", true,
            onConfirm = { vm.removeWallet(w.id); remove = null }, onDismiss = { remove = null })
    }
    if (newManual) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newManual = false }, title = { Text("Carteira manual") },
            text = { Column {
                Text("Saldos fora da blockchain, como corretoras.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome (ex.: Binance)") })
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.addWallet(name.trim(), WalletType.EXCHANGE.name); newManual = false }) { Text("Criar") } },
            dismissButton = { TextButton(onClick = { newManual = false }) { Text("Cancelar") } })
    }
}

@Composable
fun AddWalletScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, initialAddress: String = "") {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val check = remember(address) { if (address.isBlank()) null else vm.validateAddress(address) }
    val cands = remember(address, check) { if (check?.first == null) emptyList() else AddressKind.candidates(address) }
    var picked by remember(address) { mutableStateOf<AddressKind?>(null) }
    // ambíguo (ex.: Sui × Aptos): o usuário escolhe; sem escolha não adiciona
    val kind = if (cands.size > 1) picked else check?.first
    val hexKey = cands.isNotEmpty() && cands.all { it == AddressKind.SUI || it == AddressKind.APTOS }
    var confirmPublic by remember(address) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Adicionar carteira", { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Cole o endereço público. A rede é detectada sozinha.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(address, { address = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Endereço público") }, minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), shape = RoundedCornerShape(14.dp),
                isError = check?.second != null,
                trailingIcon = { IconButton(onClick = { clip.getText()?.text?.let { address = it.trim() } }) { Icon(Icons.Outlined.ContentPaste, "Colar") } })
            check?.second?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            if (cands.size > 1) {
                Spacer(Modifier.height(14.dp))
                Text("Esse formato vale para mais de uma rede. Qual é?", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cands.forEach { c -> androidx.compose.material3.FilterChip(selected = picked == c, onClick = { picked = c },
                        label = { Text(c.label.substringBefore(" (")) }, leadingIcon = { NetworkDot(c.networks.first(), Modifier.size(18.dp)) }) }
                }
            }
            if (hexKey) {
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { confirmPublic = !confirmPublic }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(confirmPublic, { confirmPublic = it })
                    Text("Confirmo que é um endereço público (0x + 64 caracteres também é o formato de uma chave privada — nunca cole chaves).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind != null) {
                Spacer(Modifier.height(14.dp))
                CpCard {
                    Text("Detectado: ${kind.label.substringBefore(" (")}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    Row { kind.networks.forEach { n -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 10.dp)) {
                        NetworkDot(n, Modifier.size(28.dp)); Text(n.label.substringBefore(" "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } } }
                    Spacer(Modifier.height(10.dp))
                    Text(kind.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!kind.hasHistory) { Spacer(Modifier.height(6.dp)); Tag("Histórico parcial") }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome (opcional)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            }
            Spacer(Modifier.height(20.dp))
            Button(enabled = kind != null && !busy && (!hexKey || confirmPublic), onClick = { busy = true; vm.addWatchWallet(address, name, kind) { ok -> busy = false; if (ok) nav.popBackStack() } },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text(if (busy) "Adicionando…" else "Adicionar e sincronizar") }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Somente leitura. Nunca digite sua seed ou chave privada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
