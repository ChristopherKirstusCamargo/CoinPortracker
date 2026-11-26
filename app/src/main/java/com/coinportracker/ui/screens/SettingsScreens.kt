package com.coinportracker.ui.screens

import androidx.compose.foundation.selection.toggleable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.BuildConfig
import com.coinportracker.data.HoldingsCsv
import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.Currency
import com.coinportracker.settings.BannerFit
import com.coinportracker.settings.BannerStyle
import com.coinportracker.settings.ThemeMode
import com.coinportracker.transactions.CsvCodec
import com.coinportracker.transactions.JsonCodec
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.reveal
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
    else Spacer(Modifier.height(12.dp))
    CpCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), content = content)
}

@Composable
internal fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // a linha inteira é o alvo (papel de interruptor para o TalkBack); o Switch só desenha o estado, sem um segundo alvo de toque
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        CpSwitch(checked)
    }
}

/**
 * Interruptor do app. Desligado = trilho escuro com contorno claro e polegar claro (claramente tocável no tema preto);
 * o visual esmaecido fica só para o que está de fato desabilitado.
 */
@Composable
internal fun CpSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)? = null, enabled: Boolean = true) {
    val on = MaterialTheme.colorScheme.onSurface
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedTrackColor = LocalExtra.current.cardHigh, uncheckedThumbColor = on.copy(alpha = 0.85f), uncheckedBorderColor = on.copy(alpha = 0.6f),
        disabledCheckedTrackColor = on.copy(alpha = 0.18f), disabledCheckedThumbColor = on.copy(alpha = 0.35f),
        disabledUncheckedTrackColor = androidx.compose.ui.graphics.Color.Transparent, disabledUncheckedThumbColor = on.copy(alpha = 0.2f), disabledUncheckedBorderColor = on.copy(alpha = 0.15f)))
}

/** Botão principal: habilitado = branco cheio; só o desabilitado fica apagado. */
@Composable
internal fun cpButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))

@Composable
internal fun LinkRow(title: String, sub: String? = null, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun <T> ChoiceRow(title: String?, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (title != null) { Text(title, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(8.dp)) }
        Segments(options, selected, label, onSelect, Modifier.fillMaxWidth())
    }
}

/** Categoria de ajustes (cada bloco abre a própria tela). */
enum class SettingsCat(val key: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    APPEARANCE("aparencia", "Aparência", Icons.Outlined.Palette),
    ANIMATIONS("animacoes", "Animações", Icons.Outlined.Animation),
    CURRENCY("moeda", "Moeda", Icons.Outlined.CurrencyExchange),
    BANNER("banner", "Banner, GIF e vídeo", Icons.Outlined.Gif),
    ROLL("rolagem", "Rolagem de banner", Icons.Outlined.Swipe),
    WALLETS("carteiras", "Carteiras", Icons.Outlined.AccountBalanceWallet),
    COST("portfolio", "Preço do portfólio", Icons.Outlined.Calculate),
    REFRESH("atualizacao", "Atualização do saldo", Icons.Outlined.Sync),
    SECURITY("seguranca", "Segurança e privacidade", Icons.Outlined.Lock),
    PRIVACY("privacidade", "Privacidade", Icons.Outlined.Shield),
    ALERTS("alertas", "Alertas", Icons.Outlined.Notifications),
    GOALS("metas", "Metas", Icons.Outlined.Flag),
    SHARE("compartilhar", "Compartilhar portfólio", Icons.Outlined.IosShare),
    WEBHOST("webhost", "Web Host (navegador)", Icons.Outlined.Computer),
    MORE("mais", "Mercado", Icons.Outlined.ShowChart),
    ABOUT("sobre", "Sobre", Icons.Outlined.Info),
    DEV("dev", "Avançado (Dev)", Icons.Outlined.Build),
}


/** Resumo de uma linha do estado atual de cada categoria (aparece no bloco). */
private fun summary(c: SettingsCat, h: com.coinportracker.data.HubState): String {
    val st = h.settings
    return when (c) {
        SettingsCat.APPEARANCE -> "Tema ${st.theme.label.lowercase()}" + if (st.hideValues) " · saldos ocultos" else ""
        SettingsCat.ANIMATIONS -> if (st.lowPower) "Desligadas no modo de baixo consumo" else st.anim.number.label + " · " + com.coinportracker.ui.screens.speedLabel(st.anim.speed)
        SettingsCat.CURRENCY -> if (st.baseCurrency == Currency.BRL) "Real (R$)" else "Dólar (US$)"
        SettingsCat.BANNER -> if (!st.banner.enabled) "Desligado" else "Ligado · opacidade ${(st.banner.opacity * 100).toInt()}%" + (if (st.banner.hideOverlays) " · sem textos" else "") + if (st.upscaleMedia) " · HD" else ""
        SettingsCat.ROLL -> com.coinportracker.settings.RollRules.summary(st.roll)
        SettingsCat.WALLETS -> when (h.wallets.size) { 0 -> "Nenhuma carteira"; 1 -> "1 carteira"; else -> "${h.wallets.size} carteiras" }
        SettingsCat.COST -> st.invested?.let { com.coinportracker.core.Fmt.money(it, st.investedCurrency, st.hideValues) } ?: "Não informado"
        SettingsCat.REFRESH -> (if (st.foregroundRefreshSec == 0) "Manual" else "A cada ${st.foregroundRefreshSec} s") + if (st.liveBalance) " · ao vivo" else ""
        SettingsCat.SECURITY -> listOfNotNull(if (st.biometricLock) "Bloqueio ligado" else "Bloqueio desligado", if (st.secureScreen) "tela protegida" else null, if (st.hideValues) "saldos ocultos" else null).joinToString(" · ")
        SettingsCat.PRIVACY -> if (st.secureScreen) "Tela protegida" else "Capturas permitidas"
        SettingsCat.ALERTS -> if (st.alertsEnabled) "Ligados" else "Desligados"
        SettingsCat.GOALS -> st.goal?.let { com.coinportracker.core.Fmt.money(it, st.goalCurrency, st.hideValues) } ?: "Nenhuma meta"
        SettingsCat.SHARE -> "Cartão da alocação"
        SettingsCat.WEBHOST -> com.coinportracker.webhost.WebHostController.state.value.let { w -> if (w.running) "Ligado" + (w.primary?.let { " · ${it.ip}:${w.port}" } ?: "") else "Painel no navegador, só leitura" }
        SettingsCat.MORE -> "Top 100 e favoritos"
        SettingsCat.ABOUT -> "Versão ${BuildConfig.VERSION_NAME}"
        SettingsCat.DEV -> "Fontes, redes e diagnóstico"
    }
}

@Composable
private fun SettingsTile(c: SettingsCat, sub: String, onClick: () -> Unit) {
    CpCard(Modifier.padding(bottom = 8.dp), onClick = onClick, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(LocalExtra.current.cardHigh), contentAlignment = Alignment.Center) {
                Icon(c.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TileSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

/** Ajustes v3: blocos por categoria (cada um abre a própria tela). */
@Composable
fun SettingsScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    fun open(c: SettingsCat) = when (c) {
        SettingsCat.BANNER -> nav.navigate("banner")
        SettingsCat.ROLL -> nav.navigate("bannerRoll")
        SettingsCat.WALLETS -> nav.navigate("wallets2")
        SettingsCat.SHARE -> nav.navigate("share")
        SettingsCat.PRIVACY -> nav.navigate("settings/seguranca")
        SettingsCat.MORE -> nav.navigate("market")
        SettingsCat.DEV -> nav.navigate("dev")
        SettingsCat.WEBHOST -> nav.navigate("webhost")
        else -> nav.navigate("settings/${c.key}")
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.coinportracker.ui.ScrollToTopOnReselect("settings", listState)
    val sections = listOf(
        "Personalização" to listOf(SettingsCat.APPEARANCE, SettingsCat.ANIMATIONS, SettingsCat.CURRENCY, SettingsCat.BANNER, SettingsCat.ROLL),
        "Portfólio" to listOf(SettingsCat.WALLETS, SettingsCat.COST, SettingsCat.GOALS, SettingsCat.REFRESH, SettingsCat.ALERTS),
        "Proteção" to listOf(SettingsCat.SECURITY),
        "Geral" to listOf(SettingsCat.WEBHOST, SettingsCat.MORE, SettingsCat.DEV, SettingsCat.ABOUT),
    )
    Column(Modifier.fillMaxSize()) {
        V2TopBar("Ajustes", null)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            sections.forEach { (title, cats) ->
                item(key = "t:$title") { TileSection(title) }
                cats.forEach { c -> item(key = c.key) { Box(Modifier.reveal()) { SettingsTile(c, summary(c, h)) { open(c) } } } }
            }
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
}

/** Tela de uma categoria de ajustes. */
@Composable
fun SettingsCategoryScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, key: String) {
    val cat = if (key == "privacidade") SettingsCat.SECURITY else SettingsCat.entries.firstOrNull { it.key == key } ?: SettingsCat.ABOUT
    val h by vm.hub.collectAsStateWithLifecycle()
    val st = h.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        V2TopBar(cat.title, { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            when (cat) {
                SettingsCat.APPEARANCE -> {
                    Group("Tema") {
                        ChoiceRow(null, ThemeMode.entries, st.theme, { it.label }) { t -> vm.updateSettings { it.copy(theme = t) } }
                    }
                }
                SettingsCat.ANIMATIONS -> AnimationsSection(vm)
                SettingsCat.CURRENCY -> Group("Moeda principal") {
                    ChoiceRow(null, Currency.entries, st.baseCurrency, { if (it == Currency.BRL) "Real (R$)" else "Dólar (US$)" }) { c -> vm.updateSettings { it.copy(baseCurrency = c) } }
                }
                SettingsCat.COST -> PortfolioCostSection(vm, nav)
                SettingsCat.REFRESH -> {
                    Group("Atualização automática") {
                        ChoiceRow(null, listOf(0, 30, 60), st.foregroundRefreshSec, { if (it == 0) "Desligada" else "${it} s" }) { v -> vm.updateSettings { it.copy(foregroundRefreshSec = v) } }
                        SwitchRow("Saldo ao vivo", "Cotações em segundos e saldos on-chain em cerca de 15–30 s, ou quando a rede avisa. O número só muda com valor real.", st.liveBalance) { v -> vm.updateSettings { it.copy(liveBalance = v) } }
                    }
                    Group("Lista de ativos") {
                        SwitchRow("Ocultar saldos pequenos", "Abaixo de US$ 1", st.hideSmallBalances) { v -> vm.updateSettings { it.copy(hideSmallBalances = v) } }
                    }
                    Group("Bateria") {
                        SwitchRow("Modo de baixo consumo", "Desliga animações, vídeo e o upscale, e atualiza com menos frequência. Só liga se você ativar.", st.lowPower) { v -> vm.updateSettings { it.copy(lowPower = v) } }
                    }
                }
                SettingsCat.SECURITY, SettingsCat.PRIVACY -> SecuritySection(vm, nav)
                SettingsCat.ALERTS -> { AlertsSettingsSection(vm, nav); return@Column }
                SettingsCat.GOALS -> GoalSettingsSection(vm)
                SettingsCat.ABOUT -> {
                    Group("CoinPortracker ${BuildConfig.VERSION_NAME}") {
                        Note("Somente leitura: o app nunca pede frase secreta nem chave privada.")
                        Note("Sem conta, anúncios ou rastreamento.")
                        Note("Não é recomendação de investimento.")
                    }
                }
                SettingsCat.BANNER, SettingsCat.ROLL, SettingsCat.WALLETS, SettingsCat.SHARE, SettingsCat.MORE, SettingsCat.DEV, SettingsCat.WEBHOST -> Unit
            }
        }
    }
}

@Composable
private fun SecuritySection(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val st = h.settings
    val full = vm as? MainViewModel
    var pwdFor by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<String, suspend () -> ByteArray>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val today = LocalDate.now().toString()
    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingExport; pendingExport = null
        if (uri != null && p != null) full?.writeBytes(uri, p.second, p.first)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { pendingRestore = uri; pwdFor = "restore" } }
    var fullPw by remember { mutableStateOf<CharArray?>(null) }
    val saveFull = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = fullPw; fullPw = null
        if (uri != null && pw != null) full?.exportFullBackup(uri, pw) else pw?.fill(' ')
    }
    val flow by (full?.backupFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow<com.coinportracker.backup.BackupFlow>(com.coinportracker.backup.BackupFlow.Idle) }).collectAsStateWithLifecycle()
    val undoAt by (full?.restoreUndoAt ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Long?>(null) }).collectAsStateWithLifecycle()
    fun export(name: String, ok: String, bytes: suspend () -> ByteArray) { pendingExport = ok to bytes; saveDoc.launch(name) }

    Group("Bloqueio") {
        SwitchRow("Bloqueio do app", "Biometria ou PIN/padrão do aparelho", st.biometricLock) { v ->
            val reason = if (v) com.coinportracker.security.AppLock.unavailableReason(ctx) else null
            if (reason != null) vm.toast(reason) else vm.updateSettings { it.copy(biometricLock = v) }
        }
        if (st.biometricLock) ChoiceRow("Bloquear ao sair após", listOf(0, 30, 60, 300), st.autoLockSeconds, { if (it == 0) "Na hora" else if (it < 60) "${it} s" else "${it / 60} min" }) { v -> vm.updateSettings { it.copy(autoLockSeconds = v) } }
        SwitchRow("Proteger tela", "Bloqueia capturas e oculta a prévia nos recentes", st.secureScreen) { v -> vm.updateSettings { it.copy(secureScreen = v) } }
        SwitchRow("Ocultar saldos", "Toque duas vezes no saldo do Início para alternar", st.hideValues) { v -> vm.updateSettings { it.copy(hideValues = v) } }
    }
    Group("Backup") {
        LinkRow("Backup completo criptografado", "Carteiras, histórico, staking, fotos e GIFs, ajustes, regras fiscais e Dev") { pwdFor = "export" }
        LinkRow("Restaurar backup", "Junta com o que já está no app — nada é apagado") { openBackup.launch(arrayOf("*/*")) }
        undoAt?.let { at ->
            LinkRow("Desfazer ajustes da restauração", "Volta aos ajustes de antes de " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(at)) + ". Os dados restaurados continuam.") { full?.undoRestoreSettings() }
        }
        Note("Ficam de fora: a chave de API do CoinGecko e os aparelhos pareados do Web Host.")
    }
    when (val f = flow) {
        is com.coinportracker.backup.BackupFlow.Busy -> AlertDialog(onDismissRequest = {}, title = { Text("Backup") }, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(14.dp)); Text(f.text, style = MaterialTheme.typography.bodySmall) }
        }, confirmButton = {})
        is com.coinportracker.backup.BackupFlow.Review -> AlertDialog(onDismissRequest = { full?.cancelRestore() }, title = { Text("Restaurar este backup?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                com.coinportracker.backup.RestoreText.summary(f.preview).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Nada do que já está no app será apagado: só entra o que falta.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }, confirmButton = { TextButton(onClick = { full?.applyRestore() }) { Text("Restaurar") } }, dismissButton = { TextButton(onClick = { full?.cancelRestore() }) { Text("Cancelar") } })
        is com.coinportracker.backup.BackupFlow.Done -> AlertDialog(onDismissRequest = { full?.dismissBackupFlow() }, title = { Text("Backup restaurado") }, text = { Text(f.text, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { full?.dismissBackupFlow() }) { Text("Ok") } })
        com.coinportracker.backup.BackupFlow.Idle -> Unit
    }

    if (pwdFor != null) {
        var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
        val isExport = pwdFor == "export"
        AlertDialog(onDismissRequest = { pwdFor = null; pendingRestore = null }, title = { Text(if (isExport) "Senha do backup" else "Restaurar backup") }, text = {
            Column {
                Text(if (isExport) "Mínimo de 8 caracteres. Sem a senha, o backup NÃO pode ser recuperado." else "Informe a senha (em branco só para backups antigos sem criptografia). Nada do que já está no app será apagado: você verá um resumo antes de confirmar.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(p1, { p1 = it }, label = { Text("Senha") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (isExport) OutlinedTextField(p2, { p2 = it }, label = { Text("Confirmar senha") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = p2.isNotEmpty() && p1 != p2)
            }
        }, confirmButton = {
            TextButton(enabled = !isExport || (p1.length >= 8 && p1 == p2), onClick = {
                if (isExport) { fullPw = p1.toCharArray(); saveFull.launch("coinportracker-$today.cptbk") }
                else pendingRestore?.let { full?.inspectBackup(it, p1.ifEmpty { null }) }
                pwdFor = null; pendingRestore = null
            }) { Text(if (isExport) "Escolher local" else "Restaurar") }
        }, dismissButton = { TextButton(onClick = { pwdFor = null; pendingRestore = null }) { Text("Cancelar") } })
    }
}

/** Ajustes do Banner/GIF com prévia ao vivo (as mudanças valem na hora e ficam salvas). */
@Composable
fun BannerSettingsScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val st = h.settings
    // estado local para arraste fluido; grava no DataStore com pequeno atraso
    var b by remember { mutableStateOf(vm.hub.value.settings.banner) }
    LaunchedEffect(b) { kotlinx.coroutines.delay(120); if (b != vm.hub.value.settings.banner) vm.updateSettings { it.copy(banner = b) } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // v5.1.3: fonte única — com a Rolagem ativa, a prévia, o HD e a Início mostram a mídia escolhida no seletor
    val src = remember(st.roll, st.bannerUri) { com.coinportracker.media.BannerLibrary.source(ctx, st) }
    val rollItem = if (st.roll.active) st.roll.current else null
    val isVideoSrc = if (rollItem != null) rollItem.kind == com.coinportracker.settings.RollKind.VIDEO else com.coinportracker.media.VideoFormat.isVideoPath(st.bannerUri)
    fun onPickedMain(uri: android.net.Uri, video: Boolean) {
        // v5.1.3: com a Rolagem ativa, Galeria/Arquivos adicionam à Rolagem e já deixam como banner atual
        val toRoll = vm.hub.value.settings.roll.active
        if (video && toRoll) { vm.toast("A Rolagem aceita só GIFs e fotos."); return }
        if (video) scope.launch {
            if (toRoll) com.coinportracker.settings.RollRules.canAdd(vm.hub.value.settings.roll, com.coinportracker.settings.RollKind.VIDEO, com.coinportracker.media.VideoImport.rollPending.value)?.let { vm.toast(it); return@launch }
            val p = com.coinportracker.media.VideoImport.offer(ctx, uri, if (toRoll) com.coinportracker.media.VideoDest.ROLL else com.coinportracker.media.VideoDest.BANNER, select = toRoll)
            if (p == null) vm.toast("Não foi possível abrir esse vídeo.") else nav.navigate("trimVideo")
        } else if (toRoll) scope.launch {
            com.coinportracker.media.BannerLibrary.importImage(ctx, uri, com.coinportracker.settings.SettingsRepository(ctx.applicationContext), select = true)?.let { vm.toast(it) }
        } else vm.setBanner(uri)
    }
    fun onPicked(uri: android.net.Uri) { scope.launch { onPickedMain(uri, isVideoUri(ctx, uri)) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPicked(uri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    val media = rememberBannerMedia(com.coinportracker.ui.components.rememberHdSource(src, st.upscaleMedia), b.speed)
    val bg = MaterialTheme.colorScheme.background

    Column(Modifier.fillMaxSize()) {
        V2TopBar("Banner, GIF e vídeo", { nav.popBackStack() })
        // prévia fixa no topo
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height((b.heightDp * 0.62f).dp.coerceAtLeast(120.dp)).clip(RoundedCornerShape(20.dp)).background(bg)
            .then(Modifier.background(LocalExtra.current.card.copy(alpha = 0f)))) {
            BannerBackdrop(media, b, bg, Modifier.matchParentSize())
            com.coinportracker.ui.components.HdBadge(com.coinportracker.ui.components.rememberHdStatus(src, st.upscaleMedia), Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.CenterStart).padding(20.dp)) {
                Text("Patrimônio total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FitText(h.money(h.agg.totalUsd), style = MaterialTheme.typography.displaySmall)
                ChangePill(pctOf(h.agg.change24hPct), upOf(h.agg.change24hPct))
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Group("Mídia") {
                SwitchRow("Mostrar banner", null, b.enabled) { b = b.copy(enabled = it) }
                if (rollItem != null) {
                    val n = st.roll.carousel.filter { it.kind == rollItem.kind }.indexOfFirst { it.id == rollItem.id } + 1
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav.navigate("bannerRoll") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Em uso: ${com.coinportracker.ui.components.kindLabel(rollItem.kind, 1)} $n da Rolagem", style = MaterialTheme.typography.bodyLarge)
                            Text("Toque no banner da Início para trocar. Galeria e Arquivos adicionam à Rolagem.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!isVideoSrc) {
                    SwitchRow("Melhorar resolução", if (st.lowPower) "Pausado enquanto o modo de baixo consumo estiver ligado" else "IA no aparelho · em segundo plano", st.upscaleMedia) { on -> vm.updateSettings { it.copy(upscaleMedia = on) } }
                    androidx.compose.animation.AnimatedVisibility(st.upscaleMedia) { com.coinportracker.ui.components.HdStatusCard(src, st.upscaleMedia) }
                }
                VideoJobRow(ctx)
                SwitchRow("Ocultar textos sobre o banner", null, b.hideOverlays) { b = b.copy(hideOverlays = it) }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = if (st.bannerUri.isNotBlank()) 0.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Galeria", maxLines = 1) }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("image/gif", "image/*", "video/*")) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Arquivos", maxLines = 1) }
                }
                // em linha própria: antes dividia a linha com os outros dois botões e quebrava em 3 linhas
                if (st.bannerUri.isNotBlank() && rollItem == null) TextButton(onClick = { vm.setBanner(null) }, Modifier.fillMaxWidth().padding(bottom = 4.dp)) { Text("Usar banner padrão", maxLines = 1) }
            }
            Group("Aparência") {
                SliderRow("Opacidade", "${(b.opacity * 100).toInt()}%", b.opacity, 0.05f..1f) { b = b.copy(opacity = it) }
                SliderRow("Desfoque", if (android.os.Build.VERSION.SDK_INT >= 31) "${b.blurDp.toInt()} dp" else "Android 12+", b.blurDp, 0f..24f) { b = b.copy(blurDp = it) }
                SwitchRow("Esmaecer nas bordas", null, b.fade) { b = b.copy(fade = it) }
                SliderRow("Cantos arredondados", "${b.cornerDp.toInt()} dp", b.cornerDp, 0f..40f) { b = b.copy(cornerDp = it) }
            }
            Group("Tamanho e posição") {
                SliderRow("Altura", "${b.heightDp} dp", b.heightDp.toFloat(), 160f..420f) { b = b.copy(heightDp = it.toInt()) }
                SliderRow("Escala (largura)", "${"%.2f".format(b.scale)}×", b.scale, 0.5f..3f) { b = b.copy(scale = it) }
                SliderRow("Posição horizontal", "${(b.offsetX * 100).toInt()}", b.offsetX, -1f..1f) { b = b.copy(offsetX = it) }
                SliderRow("Posição vertical", "${(b.offsetY * 100).toInt()}", b.offsetY, -1f..1f) { b = b.copy(offsetY = it) }
                ChoiceRow("Encaixe", BannerFit.entries, b.fit, { it.label }) { b = b.copy(fit = it) }
            }
            Group("Animação") {
                SliderRow("Velocidade", if (b.speed == 0f) "Pausado" else "${"%.2f".format(b.speed)}×", b.speed, 0f..3f) { b = b.copy(speed = if (it < 0.05f) 0f else it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    listOf(0f to "Pausar", 0.5f to "0,5×", 1f to "1×", 2f to "2×").forEach { (v, l) -> SortChip(l, b.speed == v) { b = b.copy(speed = v) } }
                }
            }
            OutlinedButton(onClick = { b = BannerStyle() }, Modifier.fillMaxWidth().padding(top = 20.dp), shape = RoundedCornerShape(14.dp)) { Text("Restaurar padrão") }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row { Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Text(value, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(v.coerceIn(range.start, range.endInclusive), onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = LocalExtra.current.cardHigh))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioCostSection(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val st = h.settings
    var text by remember(st.investedTotal) { mutableStateOf(st.invested?.let { com.coinportracker.core.Fmt.num(it.toDouble(), 2).replace(".", "") } ?: "") }
    var cur by remember(st.investedCurrency) { mutableStateOf(st.investedCurrency) }
    var since by remember(st.investedSince) { mutableStateOf(st.investedSince) }
    var pickDate by remember { mutableStateOf(false) }
    val parsed = com.coinportracker.core.Decimals.parse(text)?.takeIf { it.signum() > 0 }
    val dirty = parsed?.stripTrailingZeros() != st.invested?.stripTrailingZeros() || cur != st.investedCurrency || since != st.investedSince
    var savedAt by remember { mutableStateOf(0L) }
    var justSaved by remember { mutableStateOf(false) }
    LaunchedEffect(savedAt) { if (savedAt > 0) { justSaved = true; kotlinx.coroutines.delay(1600); justSaved = false } }
    Group("Quanto você pagou") {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Valor total (${cur.symbol})") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            isError = text.isNotBlank() && parsed == null, shape = RoundedCornerShape(12.dp))
        ChoiceRow(null, Currency.entries, cur, { if (it == Currency.BRL) "Real (R$)" else "Dólar (US$)" }) { cur = it }
        LinkRow("Desde", value = if (since > 0) com.coinportracker.core.Fmt.date(since) else "Sempre") { pickDate = true }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.invested != null) OutlinedButton(onClick = { text = ""; vm.updateSettings { it.copy(investedTotal = "", investedSince = 0L) } }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Remover", maxLines = 1) }
            Button(enabled = parsed != null && dirty, colors = cpButtonColors(), onClick = {
                val v = parsed!!.stripTrailingZeros().toPlainString()
                vm.updateSettings { it.copy(investedTotal = v, investedCurrency = cur, investedSince = since) }; savedAt = System.currentTimeMillis()
            }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                // feedback rápido no próprio botão (sem toast): "Salvo ✓" por 1,6 s
                androidx.compose.animation.AnimatedContent(justSaved && !dirty, transitionSpec = { com.coinportracker.ui.theme.Motion.fadeThrough() }, label = "save") { ok ->
                    Text(if (ok) "Salvo ✓" else "Salvar", maxLines = 1)
                }
            }
        }
        Text("Só o valor que saiu do seu bolso.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
    }
    // Avançado (recolhido): opções que quase ninguém precisa mexer
    var adv by rememberSaveable { mutableStateOf(false) }
    Group(null) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { adv = !adv }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Avançado", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val rot by androidx.compose.animation.core.animateFloatAsState(if (adv) 90f else 0f, label = "rot")
            Icon(Icons.Outlined.ChevronRight, null, Modifier.graphicsLayer { rotationZ = rot }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.animation.AnimatedVisibility(adv) {
            SwitchRow("Estimar preço médio pela blockchain", "Usa as entradas de cada carteira para estimar o preço médio por ativo", st.useEstimatedCost) { v -> vm.updateSettings { it.copy(useEstimatedCost = v) } }
        }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = since.takeIf { it > 0 } ?: System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton(onClick = { since = state.selectedDateMillis ?: 0L; pickDate = false }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { since = 0L; pickDate = false }) { Text("Sempre") } }) { DatePicker(state) }
    }
}


@Composable
internal fun VideoJobRow(ctx: android.content.Context) {
    val job by com.coinportracker.media.VideoImport.status(ctx).collectAsStateWithLifecycle(
        com.coinportracker.media.VideoJob()
    )
    when (job.state) {
        com.coinportracker.media.VideoJobState.QUEUED, com.coinportracker.media.VideoJobState.RUNNING -> {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(if (job.state == com.coinportracker.media.VideoJobState.QUEUED) "Preparando vídeo" else "Preparando vídeo  ${(job.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                if (job.state == com.coinportracker.media.VideoJobState.QUEUED) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        com.coinportracker.media.VideoJobState.FAILED -> job.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        }
        else -> {}
    }
}


@Composable
private fun GoalSettingsSection(vm: com.coinportracker.ui.UiModel) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val st = h.settings
    var text by remember(st.goalTarget) { mutableStateOf(st.goal?.let { com.coinportracker.core.Fmt.num(it.toDouble(), 2) } ?: "") }
    var cur by remember(st.goalCurrency) { mutableStateOf(st.goalCurrency) }
    val parsed = com.coinportracker.core.Decimals.parse(text)?.takeIf { it.signum() > 0 }
    val dirty = parsed?.stripTrailingZeros() != st.goal?.stripTrailingZeros() || cur != st.goalCurrency
    Group("Valor alvo") {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Patrimônio alvo (${cur.symbol})") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            isError = text.isNotBlank() && parsed == null, shape = RoundedCornerShape(12.dp))
        ChoiceRow(null, Currency.entries, cur, { if (it == Currency.BRL) "Real (R$)" else "Dólar (US$)" }) { cur = it }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.goal != null) OutlinedButton(onClick = { text = ""; vm.updateSettings { it.copy(goalTarget = "") } }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Remover", maxLines = 1) }
            Button(enabled = parsed != null && dirty, colors = cpButtonColors(), onClick = {
                vm.updateSettings { it.copy(goalTarget = parsed!!.stripTrailingZeros().toPlainString(), goalCurrency = cur) }
            }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Salvar", maxLines = 1) }
        }
    }
}

/** v6.0: a detecção de vídeo lê o provedor de conteúdo (pode ser lento, ex.: nuvem): sempre fora da thread principal. */
internal suspend fun isVideoUri(ctx: android.content.Context, uri: android.net.Uri): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching { com.coinportracker.media.VideoFormat.isVideoMime(ctx.contentResolver.getType(uri)) || (ctx.contentResolver.openInputStream(uri)?.use { com.coinportracker.media.VideoFormat.isVideoStream(it) } ?: false) }.getOrDefault(false)
}
