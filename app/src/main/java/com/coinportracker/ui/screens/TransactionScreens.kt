package com.coinportracker.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.data.text
import com.coinportracker.database.TransactionEntity
import com.coinportracker.domain.Currency
import com.coinportracker.domain.TxType
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra
import java.util.Calendar

private fun parse(s: String) = Decimals.parse(s).text()

@Composable
fun TransactionFormScreen(vm: MainViewModel, nav: NavHostController, id: Long, presetAsset: String?, presetWallet: Long? = null) {
    val s by vm.state.collectAsStateWithLifecycle()
    val existing by remember(id) { vm.c.transactions.observe(id) }.collectAsStateWithLifecycle(null)
    val revisions by remember(id) { vm.c.transactions.revisions(id) }.collectAsStateWithLifecycle(emptyList())
    var loaded by remember { mutableStateOf(id == 0L) }
    var type by remember { mutableStateOf(TxType.BUY) }
    var ts by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var asset by remember { mutableStateOf(presetAsset) }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf(s.currency) }
    var toAsset by remember { mutableStateOf<String?>(null) }
    var toQty by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var feeQty by remember { mutableStateOf("") }
    var fromW by remember { mutableStateOf<Long?>(presetWallet) }
    var toW by remember { mutableStateOf<Long?>(null) }
    var hash by remember { mutableStateOf("") }
    var exch by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var fx by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(existing) {
        val e = existing ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        type = TxType.valueOf(e.type); ts = e.timestamp; asset = e.assetId; qty = e.quantity; price = e.unitPrice ?: ""; cur = Currency.valueOf(e.quoteCurrency)
        toAsset = e.toAssetId; toQty = e.toQuantity ?: ""; fee = e.feeFiat ?: ""; feeQty = e.feeQuantity ?: ""; fromW = e.fromWalletId; toW = e.toWalletId
        hash = e.txHash ?: ""; exch = e.exchange ?: ""; notes = e.notes ?: ""; fx = e.fxUsdBrl ?: ""; cost = e.declaredCost ?: ""; loaded = true
    }
    val ctx = LocalContext.current
    val assets = s.assets.values.sortedBy { it.symbol }
    val wallets = s.wallets.values.toList()
    ScreenScaffold(if (id == 0L) "Nova operação" else "Operação #$id", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Tipo", TxType.entries, type, { it.label }, { type = it })
            NoteText(type.help)
            OutlinedButton(onClick = {
                val c = Calendar.getInstance().apply { timeInMillis = ts }
                DatePickerDialog(ctx, { _, y, m, d -> c.set(y, m, d); TimePickerDialog(ctx, { _, h, mi -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); ts = c.timeInMillis }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show() },
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }, Modifier.fillMaxWidth()) { Text("Data e hora: ${Fmt.dateTime(ts)}") }
            Dropdown(if (type == TxType.SWAP) "Ativo entregue" else "Ativo", assets, assets.firstOrNull { it.id == asset }, { "${it.symbol} · ${it.name}" }, { asset = it.id })
            if (assets.isEmpty()) TextButton(onClick = { nav.navigate("addAsset") }) { Text("Cadastre um ativo primeiro") }
            DecimalField(if (type == TxType.MANUAL_ADJUSTMENT) "Quantidade (negativa reduz)" else "Quantidade", qty, { qty = it })
            if (type != TxType.INTERNAL_TRANSFER && type != TxType.WITHDRAWAL && !type.isFee) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(if (type == TxType.SWAP) "Valor unitário do ativo entregue" else "Preço unitário", price, { price = it }, Modifier.weight(1f),
                        supporting = if (type.needsPrice) "obrigatório" else "opcional")
                    Dropdown("Moeda", Currency.entries, cur, { it.name }, { cur = it }, Modifier.width(110.dp))
                }
                val total = Decimals.parse(qty)?.let { q -> Decimals.parse(price)?.multiply(q) }
                if (total != null) NoteText("Valor total: ${Fmt.money(total, cur)}")
            }
            if (type == TxType.SWAP) {
                Dropdown("Ativo recebido", assets, assets.firstOrNull { it.id == toAsset }, { "${it.symbol} · ${it.name}" }, { toAsset = it.id })
                DecimalField("Quantidade recebida", toQty, { toQty = it })
            }
            if (type == TxType.DEPOSIT || (type == TxType.MANUAL_ADJUSTMENT)) DecimalField("Custo de aquisição declarado (total, ${cur.name})", cost, { cost = it }, supporting = "Sem custo, a posição entra com custo zero e é sinalizada.")
            if (cur == Currency.USD || s.currency == Currency.USD) DecimalField("Câmbio BRL por USD na data (opcional)", fx, { fx = it }, supporting = "Sem câmbio, usa a taxa diária BCE salva (Frankfurter).")
            DecimalField("Taxa em dinheiro (${cur.name})", fee, { fee = it })
            DecimalField("Taxa em cripto (quantidade do próprio ativo)", feeQty, { feeQty = it })
            Dropdown(if (type == TxType.INTERNAL_TRANSFER) "Carteira de origem" else "Carteira", wallets, wallets.firstOrNull { it.id == fromW }, { it.name }, { fromW = it.id }, allowNone = "Sem carteira", onNone = { fromW = null })
            wallets.firstOrNull { it.id == fromW }?.takeIf { vm.c.chain.isWatch(it) }?.let {
                NoteText("Carteira on-chain: o saldo continua vindo da blockchain. Esta operação serve para o preço médio e o lucro/prejuízo (não duplica a quantidade).")
            }
            if (fromW == null) NoteText("Sem carteira: a quantidade entra como posição manual, somada ao total. Para dar custo a um saldo on-chain, escolha a carteira on-chain.")
            if (type == TxType.INTERNAL_TRANSFER) Dropdown("Carteira de destino", wallets, wallets.firstOrNull { it.id == toW }, { it.name }, { toW = it.id })
            TextFieldX("Hash / ID da transação (opcional)", hash, { hash = it })
            TextFieldX("Exchange ou provedor (opcional)", exch, { exch = it })
            TextFieldX("Observações", notes, { notes = it }, singleLine = false)
            if (id != 0L) TextFieldX("Motivo da alteração (fica no histórico)", reason, { reason = it })
            Button(enabled = asset != null && qty.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                val base = existing ?: TransactionEntity(type = type.name, timestamp = ts, assetId = asset!!, quantity = "0")
                val t = base.copy(
                    type = type.name, timestamp = ts, assetId = asset!!, quantity = parse(qty) ?: qty, unitPrice = parse(price), quoteCurrency = cur.name,
                    toAssetId = if (type == TxType.SWAP) toAsset else null, toQuantity = if (type == TxType.SWAP) parse(toQty) else null,
                    feeFiat = parse(fee), feeAssetId = if (parse(feeQty) != null) asset else null, feeQuantity = parse(feeQty),
                    fromWalletId = fromW, toWalletId = if (type == TxType.INTERNAL_TRANSFER) toW else fromW,
                    txHash = hash.ifBlank { null }, exchange = exch.ifBlank { null }, notes = notes.ifBlank { null }, fxUsdBrl = parse(fx), declaredCost = parse(cost),
                )
                vm.saveTx(t, reason.ifBlank { null }) { nav.popBackStack() }
            }) { Text("Salvar") }
            if (id != 0L) {
                s.ledger?.issues?.filter { it.txId == id }?.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall, color = if (it.severity.name == "ERROR") LocalExtra.current.loss else LocalExtra.current.warn) }
                existing?.let { e -> NoteText("Status: ${com.coinportracker.domain.ValidationStatus.valueOf(e.status).label} · origem: ${e.source} · criada ${Fmt.dateTime(e.createdAt)}") }
                OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Excluir operação", color = MaterialTheme.colorScheme.error) }
                SectionHeader("Histórico de alterações (${revisions.size})")
                revisions.forEach { r -> Text("${Fmt.dateTime(r.changedAt)} · ${when (r.action) { "CREATE" -> "criada"; "EDIT" -> "editada"; "DELETE" -> "excluída"; else -> "restaurada" }}${r.reason?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Excluir operação?", "A operação sai dos cálculos, mas fica registrada no histórico e pode ser restaurada.", "Excluir", true,
        { vm.deleteTx(id, reason.ifBlank { null }); nav.popBackStack() }, { confirmDelete = false })
}

@Composable
fun ImportScreen(vm: MainViewModel, nav: NavHostController) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(vm::loadImport) }
    var selected by remember(preview) { mutableStateOf(preview?.indices?.filter { preview!![it].importable }?.toSet() ?: emptySet()) }
    var editing by remember { mutableStateOf<Int?>(null) }
    ScreenScaffold("Importar operações", onBack = { vm.cancelImport(); nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            if (preview == null) {
                Text("Formatos aceitos: CSV (vírgula ou ponto e vírgula; cabeçalhos em português ou inglês) e JSON exportado pelo CoinPortracker.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                NoteText("Colunas: date, type, asset, quantity, unit_price, currency, to_asset, to_quantity, fee, fee_asset, fee_quantity, from_wallet, to_wallet, tx_hash, exchange, notes, category, fx_usd_brl, declared_cost")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { pick.launch(arrayOf("text/*", "application/json", "text/csv", "text/comma-separated-values")) }, Modifier.fillMaxWidth()) { Text("Escolher arquivo") }
            } else {
                val p = preview!!
                Text("${p.size} linhas · ${p.count { it.importable }} prontas · ${p.count { !it.parsed.valid }} com erro · ${p.count { it.duplicateExisting || it.duplicateInFile }} duplicadas", style = MaterialTheme.typography.titleSmall)
                NoteText("Nada é gravado antes da confirmação. Duplicadas e linhas com erro ficam de fora; toque numa linha para corrigir.")
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(p) { i, r ->
                        val rec = r.parsed.record
                        Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i in selected, { c -> selected = if (c && r.importable) selected + i else selected - i }, enabled = r.importable)
                            Column(Modifier.weight(1f)) {
                                Text("L${r.parsed.line}: ${rec?.type} ${rec?.quantity} ${rec?.asset} ${rec?.unitPrice?.let { "@ $it ${rec.currency}" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                                Text(Fmt.dateTime(rec?.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                r.parsed.errors.forEach { Text("✕ $it", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.loss) }
                                r.parsed.warnings.forEach { Text("! $it", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.warn) }
                                if (r.duplicateExisting) Text("Duplicada de operação existente", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.warn)
                                if (r.duplicateInFile) Text("Duplicada dentro do arquivo", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.warn)
                                if (r.unknownAssets.isNotEmpty()) Text("Ativos novos (serão criados como personalizados): ${r.unknownAssets.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (r.unknownWallets.isNotEmpty()) Text("Carteiras novas: ${r.unknownWallets.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.cancelImport() }, Modifier.weight(1f)) { Text("Cancelar") }
                    Button(enabled = selected.isNotEmpty(), onClick = { vm.confirmImport(selected); nav.popBackStack() }, modifier = Modifier.weight(1f)) { Text("Importar ${selected.size}") }
                }
            }
        }
    }
    editing?.let { i ->
        val r = preview?.getOrNull(i) ?: return@let
        val rec = r.parsed.record
        val fields = listOf("date", "type", "asset", "quantity", "unit_price", "currency", "to_asset", "to_quantity", "fee")
        val init = r.parsed.raw.ifEmpty { mapOf("date" to (rec?.timestamp?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: ""), "type" to (rec?.type ?: ""), "asset" to (rec?.asset ?: ""), "quantity" to (rec?.quantity ?: ""),
            "unit_price" to (rec?.unitPrice ?: ""), "currency" to (rec?.currency ?: "BRL"), "to_asset" to (rec?.toAsset ?: ""), "to_quantity" to (rec?.toQuantity ?: ""), "fee" to (rec?.fee ?: "")) }
        val values = remember(i) { mutableStateMapOf<String, String>().apply { putAll(init) } }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Corrigir linha ${r.parsed.line}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { fields.forEach { f -> TextFieldX(f, values[f] ?: "", { values[f] = it }) } }
        }, confirmButton = { TextButton(onClick = { vm.editPreviewRow(i, values.toMap()); editing = null }) { Text("Revalidar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } })
    }
}
