package com.coinportracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Decimals
import com.coinportracker.core.Fmt
import com.coinportracker.data.text
import com.coinportracker.domain.Chain
import com.coinportracker.domain.CostMethod
import com.coinportracker.domain.WalletType
import com.coinportracker.portfolio.Explanation
import com.coinportracker.transactions.LedgerEngine
import com.coinportracker.ui.MainViewModel
import com.coinportracker.ui.Refreshable
import com.coinportracker.ui.charts.LineChart
import com.coinportracker.ui.charts.Series
import com.coinportracker.ui.components.*
import com.coinportracker.ui.theme.LocalExtra

@Composable
fun AddAssetScreen(vm: MainViewModel, nav: NavHostController) {
    val results by vm.search.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    LaunchedEffect(q) { kotlinx.coroutines.delay(450); vm.searchAssets(q) }
    ScreenScaffold("Adicionar ativo", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            TextFieldX("Buscar no CoinGecko (nome ou símbolo)", q, { q = it })
            TextButton(onClick = { custom = true }) { Text("Ativo não encontrado? Cadastrar token personalizado") }
            LazyColumn {
                items(results, key = { it.providerId }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { vm.addAssetFromSearch(r, r.symbol in setOf("USDT", "USDC", "DAI", "BRZ", "FDUSD", "PYUSD", "USDE")) { id -> nav.popBackStack(); nav.navigate("txForm?asset=$id") } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetAvatar(r.symbol, colorFor(r.providerId), 32.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(r.name, style = MaterialTheme.typography.titleSmall); Text("${r.symbol} · ${r.providerId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        r.rank?.let { Pill("#$it", MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
    if (custom) {
        var sym by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
        var chain by remember { mutableStateOf(Chain.OTHER) }; var brl by remember { mutableStateOf("") }; var usd by remember { mutableStateOf("") }
        var stable by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { custom = false }, title = { Text("Token personalizado") }, text = {
            Column {
                TextFieldX("Símbolo", sym, { sym = it }); TextFieldX("Nome", name, { name = it })
                Dropdown("Rede", Chain.entries, chain, { it.label }, { chain = it })
                DecimalField("Preço manual BRL (opcional)", brl, { brl = it }); DecimalField("Preço manual USD (opcional)", usd, { usd = it })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(stable, { stable = it }); Text("Stablecoin") }
            }
        }, confirmButton = { TextButton(enabled = sym.isNotBlank(), onClick = {
            vm.addCustomAsset(sym, name, chain.name, Decimals.parse(brl).text(), Decimals.parse(usd).text(), stable) { id -> custom = false; nav.popBackStack(); nav.navigate("txForm?asset=$id") }
        }) { Text("Cadastrar") } }, dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancelar") } })
    }
}
