package com.coinportracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.coinportracker.blockchain.AddressValidator
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.core.Outcome
import com.coinportracker.data.dec
import com.coinportracker.data.text
import com.coinportracker.database.EvmTokenEntity
import com.coinportracker.database.StakingPositionEntity
import com.coinportracker.database.WalletEntity
import com.coinportracker.domain.Chain
import com.coinportracker.domain.TxType
import com.coinportracker.domain.WalletType
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun StakingScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.state.collectAsStateWithLifecycle()
    val positions by vm.c.db.staking().observeAll().collectAsStateWithLifecycle(emptyList())
    var edit by remember { mutableStateOf<StakingPositionEntity?>(null) }
    var reward by remember { mutableStateOf<StakingPositionEntity?>(null) }
    val cur = s.currency; val hide = s.settings.hideValues
    ScreenScaffold("Staking e rendimentos", onBack = { nav.popBackStack() }, fab = {
        FloatingActionButton(onClick = { s.assets.keys.firstOrNull()?.let { edit = StakingPositionEntity(assetId = it, stakedQuantity = "", initialQuantity = "", startDate = System.currentTimeMillis()) } ?: vm.toast("Cadastre um ativo primeiro") }) { Icon(Icons.Outlined.Add, "Nova posição") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 90.dp)) {
            item {
                val inc = s.ledger?.incomeEvents?.filter { it.type == TxType.STAKING }.orEmpty()
                PremiumCard {
                    MetricRow("Recompensas de staking recebidas", Fmt.money(inc.fold(BigDecimal.ZERO) { a, e -> a.add(e.value) }, cur, hide))
                    MetricRow("Registros de recompensa", inc.size.toString())
                    NoteText("Recebido = recompensas registradas como operações de Staking (valor na data). APY informado gera apenas ESTIMATIVA, nunca é somado ao resultado.")
                }
            }
            if (positions.isEmpty()) item { EmptyState("Sem posições de staking", "Registre ativo, quantidade, validador, APY informado e período de bloqueio.") }
            items(positions, key = { it.id }) { p ->
                val received = s.ledger?.incomeEvents?.filter { it.type == TxType.STAKING && it.assetId == p.assetId && it.timestamp >= p.startDate }.orEmpty()
                val recQty = received.fold(BigDecimal.ZERO) { a, e -> a.add(e.quantity) }
                val days = (System.currentTimeMillis() - p.startDate) / 86_400_000.0
                val est = p.reportedApyPct.dec()?.let { apy -> p.initialQuantity.dec()?.let { q -> q.multiply(BigDecimal(Math.pow(1 + apy.toDouble() / 100, days / 365.0) - 1)) } }
                Spacer(Modifier.height(10.dp))
                PremiumCard(onClick = { edit = p }) {
                    Row { Text("${s.symbol(p.assetId)} · ${p.validator ?: p.provider ?: "validador não informado"}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (!p.active) Pill("encerrada", MaterialTheme.colorScheme.onSurfaceVariant) }
                    MetricRow("Em staking", Fmt.qty(p.stakedQuantity.dec(), hide))
                    MetricRow("Quantidade inicial", Fmt.qty(p.initialQuantity.dec(), hide))
                    MetricRow("Início", Fmt.date(p.startDate) + " · ${days.toInt()} dias")
                    MetricRow("APY informado", p.reportedApyPct?.let { "$it%" } ?: "—")
                    MetricRow("Recompensas recebidas (registradas)", Fmt.qty(recQty, hide) + " " + s.symbol(p.assetId), LocalExtra.current.gain)
                    MetricRow("Rendimento ESTIMADO pelo APY", est?.let { "≈ " + Fmt.qty(it, hide) } ?: "—", sub = "estimativa, não é rendimento recebido")
                    if (est != null && est.signum() > 0) MetricRow("Recebido / estimado", Fmt.pct(Decimals.pct(recQty, est), false))
                    MetricRow("Bloqueio / saída", "${p.lockDays?.let { "$it d" } ?: "—"} / ${p.exitDays?.let { "$it d" } ?: "—"}")
                    p.notes?.let { NoteText(it) }
                    TextButton(onClick = { reward = p }) { Text("+ Registrar recompensa recebida") }
                }
            }
        }
    }
    edit?.let { p ->
        var asset by remember(p) { mutableStateOf(p.assetId) }; var staked by remember(p) { mutableStateOf(p.stakedQuantity) }; var initial by remember(p) { mutableStateOf(p.initialQuantity) }
        var apy by remember(p) { mutableStateOf(p.reportedApyPct ?: "") }; var lock by remember(p) { mutableStateOf(p.lockDays?.toString() ?: "") }; var exit by remember(p) { mutableStateOf(p.exitDays?.toString() ?: "") }
        var validator by remember(p) { mutableStateOf(p.validator ?: "") }; var provider by remember(p) { mutableStateOf(p.provider ?: "") }; var notes by remember(p) { mutableStateOf(p.notes ?: "") }; var active by remember(p) { mutableStateOf(p.active) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (p.id == 0L) "Nova posição de staking" else "Editar posição") }, text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Dropdown("Ativo", s.assets.values.sortedBy { it.symbol }, s.assets[asset], { it.symbol }, { asset = it.id })
                DecimalField("Quantidade em staking", staked, { staked = it }); DecimalField("Quantidade inicial", initial, { initial = it })
                DecimalField("APY informado (%)", apy, { apy = it }); TextFieldX("Bloqueio (dias)", lock, { lock = it.filter(Char::isDigit) }); TextFieldX("Prazo de saída (dias)", exit, { exit = it.filter(Char::isDigit) })
                TextFieldX("Validador", validator, { validator = it }); TextFieldX("Provedor", provider, { provider = it }); TextFieldX("Observações", notes, { notes = it }, singleLine = false)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Spacer(Modifier.width(8.dp)); Text("Ativa") }
            }
        }, confirmButton = { TextButton(enabled = Decimals.parse(staked) != null, onClick = {
            val e = p.copy(assetId = asset, stakedQuantity = Decimals.parse(staked).text()!!, initialQuantity = Decimals.parse(initial).text() ?: Decimals.parse(staked).text()!!, reportedApyPct = Decimals.parse(apy).text(),
                lockDays = lock.toIntOrNull(), exitDays = exit.toIntOrNull(), validator = validator.ifBlank { null }, provider = provider.ifBlank { null }, notes = notes.ifBlank { null }, active = active)
            vm.viewModelScope.launch { if (e.id == 0L) vm.c.db.staking().insert(e) else vm.c.db.staking().update(e) }; edit = null
        }) { Text("Salvar") } }, dismissButton = { Row { if (p.id != 0L) TextButton(onClick = { vm.viewModelScope.launch { vm.c.db.staking().delete(p) }; edit = null }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { edit = null }) { Text("Cancelar") } } })
    }
    reward?.let { p ->
        var q by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { reward = null }, title = { Text("Recompensa de ${s.symbol(p.assetId)}") }, text = {
            Column { DecimalField("Quantidade recebida", q, { q = it }); DecimalField("Preço unitário na data (${cur.name})", price, { price = it }, supporting = "Sem preço, o rendimento conta como zero (sinalizado).") }
        }, confirmButton = { TextButton(enabled = Decimals.parse(q) != null, onClick = {
            vm.saveTx(com.coinportracker.database.TransactionEntity(type = TxType.STAKING.name, timestamp = System.currentTimeMillis(), assetId = p.assetId, quantity = Decimals.parse(q).text()!!, unitPrice = Decimals.parse(price).text(),
                quoteCurrency = cur.name, fromWalletId = p.walletId, toWalletId = p.walletId, stakingPositionId = p.id, notes = "Recompensa de staking", source = "MANUAL"), null) { vm.toast("Recompensa registrada no livro.") }
            reward = null
        }) { Text("Registrar") } }, dismissButton = { TextButton(onClick = { reward = null }) { Text("Cancelar") } })
    }
}
