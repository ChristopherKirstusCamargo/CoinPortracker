package com.coinportracker.ui.screens

import androidx.compose.foundation.selection.toggleable
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.data.AlertKind
import com.coinportracker.data.Notifier
import com.coinportracker.database.AlertEntity
import com.coinportracker.ui.components.*
import java.math.BigDecimal

@Composable
fun AlertsSettingsSection(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val st = h.settings
    var adding by remember { mutableStateOf(false) }
    // permissão relida ao voltar para a tela (ex.: depois de permitir nas configurações do Android)
    var canNotify by remember { mutableStateOf(Notifier.canNotify(ctx)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val now = Notifier.canNotify(ctx)
                if (now && !canNotify && pendingEnable.value) { vm.updateSettings { it.copy(alertsEnabled = true) }; pendingEnable.value = false }
                canNotify = now
            }
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        canNotify = ok
        if (ok) { vm.updateSettings { it.copy(alertsEnabled = true) }; pendingEnable.value = false }
        else {
            // negado de vez ("não perguntar de novo"): o Android não mostra mais o pedido, então abre a tela de notificações do app
            val act = ctx as? android.app.Activity
            val permanently = act != null && Build.VERSION.SDK_INT >= 33 && !act.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (permanently) { pendingEnable.value = true; openNotificationSettings(ctx) }
            else vm.toast("Sem permissão, os alertas não aparecem na barra de notificações.")
        }
    }
    fun requestAndEnable() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (!Notifier.canNotify(ctx)) { pendingEnable.value = true; openNotificationSettings(ctx) } // notificações do app desligadas (Android < 13)
        else vm.updateSettings { it.copy(alertsEnabled = true) }
    }
    Column {
        Text("Os alertas rodam no próprio aparelho, sem servidor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        CpCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SwitchRowLocal("Alertas ligados", if (canNotify) "Notificações permitidas" else "Toque para permitir notificações", st.alertsEnabled && canNotify) { on ->
                if (on) requestAndEnable() else vm.updateSettings { it.copy(alertsEnabled = false) }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (alerts.isEmpty()) Text("Nenhum alerta ainda.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        alerts.forEach { a ->
            CpCard(Modifier.padding(bottom = 8.dp), padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.titleSmall)
                        Text(alertSub(a), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CpSwitch(a.enabled, { on -> vm.saveAlert(a.copy(enabled = on)) })
                    IconButton(onClick = { vm.deleteAlert(a) }) { Icon(Icons.Outlined.Delete, "Excluir") }
                }
            }
        }
        Button(onClick = { adding = true; if (!st.alertsEnabled || !canNotify) requestAndEnable() }, Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp), shape = RoundedCornerShape(14.dp)) { Text("Novo alerta") }
    }
    if (adding) AddAlertDialog(h, onDismiss = { adding = false }, onSave = { vm.saveAlert(it); adding = false })
}

/** Pedido de ativar pendente enquanto o usuário está nas configurações de notificação do Android. */
private val pendingEnable = androidx.compose.runtime.mutableStateOf(false)

private fun openNotificationSettings(ctx: android.content.Context) {
    val i = if (Build.VERSION.SDK_INT >= 26) android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
    runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SwitchRowLocal(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        CpSwitch(checked, null)
    }
}

private fun alertSub(a: AlertEntity): String {
    val kind = runCatching { AlertKind.valueOf(a.kind) }.getOrNull()
    return listOfNotNull(kind?.label, a.threshold, if (a.repeat) "repete" else "uma vez").joinToString(" · ")
}

@Composable
private fun AddAlertDialog(h: com.coinportracker.data.HubState, onDismiss: () -> Unit, onSave: (AlertEntity) -> Unit) {
    val assets = h.agg.assets.filter { it.priceUsd != null }
    var kind by remember { mutableStateOf(AlertKind.PRICE_ABOVE) }
    var assetKey by remember { mutableStateOf(assets.firstOrNull()?.key) }
    var threshold by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(false) }
    val parsed = threshold.replace(",", ".").toBigDecimalOrNull()
    val needsAsset = kind == AlertKind.PRICE_ABOVE || kind == AlertKind.PRICE_BELOW || kind == AlertKind.CHANGE_24H
    val kinds = listOf(AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW, AlertKind.PORTFOLIO_DAY)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo alerta") },
        text = {
            Column {
                kinds.forEach { k ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(k == kind, { kind = k })
                        Text(k.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (needsAsset) {
                    Spacer(Modifier.height(8.dp))
                    Text("Ativo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        assets.forEach { a ->
                            item(key = a.key) { SortChip(a.symbol, a.key == assetKey) { assetKey = a.key } }
                        }
                    }
                }
                OutlinedTextField(threshold, { threshold = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(if (kind == AlertKind.PORTFOLIO_DAY) "Variação (%)" else "Preço (${h.currency.symbol})") }, singleLine = true)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(repeat, { repeat = it })
                    Text("Repetir (no máximo a cada 6 h)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && parsed.signum() > 0 && (!needsAsset || assetKey != null), onClick = {
                val a = assets.firstOrNull { it.key == assetKey }
                val label = when (kind) {
                    AlertKind.PORTFOLIO_DAY -> "Portfólio no dia ±${Fmt.num(parsed!!.toDouble(), 1)}%"
                    else -> "${a?.symbol ?: "Ativo"} ${if (kind == AlertKind.PRICE_BELOW) "abaixo" else "acima"} de ${Fmt.price(parsed!!, h.currency)}"
                }
                onSave(AlertEntity(kind = kind.name, assetId = if (needsAsset) assetKey else null, label = label, threshold = parsed!!.stripTrailingZeros().toPlainString(), currency = h.currency.name, repeat = repeat, createdAt = System.currentTimeMillis()))
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
