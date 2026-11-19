package com.coinportracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.CoinPortrackerApp
import com.coinportracker.core.Fmt
import com.coinportracker.tax.*
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.components.*
import java.time.LocalDate

@Composable
fun AccountingScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val activity by vm.allActivity.collectAsStateWithLifecycle()
    val stakeSigs by vm.stakeSignatures.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val taxRepo = remember { (ctx.applicationContext as? CoinPortrackerApp)?.container?.tax ?: TaxRulesRepository(ctx) }
    val rules by taxRepo.rules.collectAsState(TaxRules.default())
    // v5: o informe (classificação com assinaturas de stake + replay fiscal + rendimentos de staking) é montado fora
    // da thread principal. Antes era recalculado na composição a cada emissão do hub, travando a rolagem.
    // Enquanto recalcula, a tela continua mostrando o informe anterior.
    val reportState by produceState<TaxReport?>(null, h, activity, rules, stakeSigs) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            TaxFromPortfolio.report(h, activity, stakeSigs, rules)
        }
    }
    var year by rememberSaveable { mutableStateOf(0) }
    val full = vm as? MainViewModel
    var pending by remember { mutableStateOf<Pair<String, suspend () -> ByteArray>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val p = pending; pending = null
        if (uri != null && p != null) full?.writeBytes(uri, p.second, p.first)
    }
    fun export(name: String, ok: String, bytes: suspend () -> ByteArray) { pending = ok to bytes; save.launch(name) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val report = reportState
    if (report == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            V2TopBar("Contábil", if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null)
            ShimmerBlock(Modifier.fillMaxWidth().height(110.dp).padding(top = 4.dp), 20.dp)
            Spacer(Modifier.height(12.dp))
            ShimmerBlock(Modifier.fillMaxWidth().height(48.dp), 14.dp)
            Spacer(Modifier.height(22.dp))
            ShimmerBlock(Modifier.fillMaxWidth().height(160.dp), 20.dp)
        }
        return
    }
    val years = report.years
    val selYear = year.takeIf { it in years } ?: years.lastOrNull() ?: LocalDate.now().year
    val holdings = report.holdingsByYear[selYear].orEmpty()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item(key = "top") { V2TopBar("Contábil", if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null) }
        item(key = "rules") {
            CpCard(Modifier.padding(top = 4.dp)) {
                Text(report.rules.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("Regras vigentes em ${report.rules.effectiveFrom}  ·  versão ${report.rules.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(report.rules.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (report.investedAllocated) {
                    Spacer(Modifier.height(8.dp))
                    Text("Custo rateado pelo total pago" + (report.investedTotal?.let { " (${TaxEngine.brl(it)})" } ?: "") + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "export") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { export("coinportracker-informe-${selYear}.csv", "CSV do informe salvo.") { TaxCsv.build(report).toByteArray(Charsets.UTF_8) } }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), enabled = full != null, colors = cpButtonColors()) { Text("Exportar CSV", maxLines = 1) }
                OutlinedButton(onClick = { export("coinportracker-informe-${selYear}.pdf", "PDF do informe salvo.") { TaxPdf.build(report) } }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), enabled = full != null) { Text("Exportar PDF", maxLines = 1) }
            }
        }
        item(key = "bensTitle") { SectionTitle("Bens e Direitos — 31/12") }
        if (years.size > 1) item(key = "year") {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(years, key = { it }) { y -> SortChip("31/12/$y", y == selYear) { year = y } }
            }
        } else item(key = "yearOne") {
            Text("31/12/$selYear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (holdings.isEmpty()) item(key = "bensEmpty") { EmptyLine("Nenhuma posição com custo nesta data.") }
        items(holdings, key = { "h:${it.year}:${it.asset}" }) { row ->
            CpCard(Modifier.padding(bottom = 8.dp)) {
                Text("${row.symbol}  ·  grupo ${row.group}  código ${row.code}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.codeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                KeyValue("Quantidade", Fmt.qty(row.qty))
                KeyValue("Custo de aquisição", TaxEngine.brl(row.costBrl))
                if (row.belowThreshold) KeyValue("Declaração", "Abaixo de ${TaxEngine.brl(report.rules.threshold())} (facultativa por espécie)")
                if (row.estimated) Text("Custo estimado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "lotsTitle") { SectionTitle("Livro de aquisição") }
        if (report.lots.isEmpty()) item { EmptyLine("Nenhum lote montado a partir da blockchain.") }
        items(report.lots, key = { "l:${it.hash}:${it.symbol}:${it.acquiredOn}:${it.qty}" }) { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${l.symbol}  ·  ${if (l.origin == LotOrigin.REWARD) "Rendimento" else "Entrada"}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(Fmt.date(l.acquiredOn.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()), l.walletName, if (l.estimated) "estimado" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 170.dp)) {
                    FitText(Fmt.qty(l.qty), style = MaterialTheme.typography.titleSmall)
                    FitText(TaxEngine.brl(l.costBrl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "mTitle") { SectionTitle("Alienações por mês") }
        if (report.months.isEmpty()) item { EmptyLine("Nenhuma alienação no histórico.") }
        items(report.months, key = { "m:${it.month}" }) { m ->
            CpCard(Modifier.padding(bottom = 8.dp)) {
                Text(m.month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Fmt.LOCALE)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                KeyValue("Total alienado", TaxEngine.brl(m.proceedsBrl))
                KeyValue("Custo", TaxEngine.brl(m.costBrl))
                KeyValue("Ganho", TaxEngine.brl(m.gainBrl), changeColor(upOf(m.gainBrl)))
                KeyValue("Isenção (${TaxEngine.brl(report.rules.exemption())})", if (m.exempt) "Dentro do limite" else "Acima do limite")
                if (!m.exempt) {
                    KeyValue("Ganho tributável", TaxEngine.brl(m.taxableGain))
                    KeyValue("Imposto sugerido · DARF ${report.rules.darfCode}", TaxEngine.brl(m.suggestedTax))
                    Text(report.rules.darfDue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                if (m.estimated) Text("Valores estimados", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "stTitle") { SectionTitle("Rendimentos de staking") }
        if (report.stakingIncome.isEmpty()) item { EmptyLine("Nenhum rendimento de staking identificado no histórico.") }
        items(report.stakingIncome, key = { "s:${it.hash}:${it.date}:${it.qty}" }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.symbol}  ·  ${Fmt.date(r.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.estimated) Text("estimado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 170.dp)) {
                    FitText(Fmt.qty(r.qty), style = MaterialTheme.typography.titleSmall)
                    r.valueBrl?.let { FitText(TaxEngine.brl(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item(key = "notes") {
            SectionTitle("Notas")
            report.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp)) }
        }
    }
}
