package com.coinportracker.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.receive.PaymentUri
import com.coinportracker.receive.QrEncoder
import com.coinportracker.receive.ReceiveAddress
import com.coinportracker.receive.ReceiveAsset
import com.coinportracker.receive.ReceiveAssets
import com.coinportracker.ui.components.CpCard
import com.coinportracker.ui.components.TokenIcon
import com.coinportracker.ui.theme.Motion
import kotlinx.coroutines.delay

sealed class ReceiveStep {
    data object Assets : ReceiveStep()
    data class Addresses(val asset: ReceiveAsset) : ReceiveStep()
    data class Qr(val asset: ReceiveAsset, val addr: ReceiveAddress) : ReceiveStep()
}

/** [previewStep] força o passo nas prévias (sem interação). */
@Composable
fun ReceiveScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, previewStep: ReceiveStep? = null) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val assets = remember(h.wallets) { ReceiveAssets.from(h.wallets) }
    var step by remember { mutableStateOf(previewStep ?: ReceiveStep.Assets) }
    LaunchedEffect(previewStep) { if (previewStep != null) step = previewStep }

    Column(Modifier.fillMaxSize()) {
        val title = when (val s = step) {
            ReceiveStep.Assets -> "Receber"
            is ReceiveStep.Addresses -> s.asset.label
            is ReceiveStep.Qr -> s.asset.label
        }
        V2TopBar(title, {
            when (step) {
                ReceiveStep.Assets -> nav.popBackStack()
                is ReceiveStep.Addresses -> step = ReceiveStep.Assets
                is ReceiveStep.Qr -> {
                    val a = (step as ReceiveStep.Qr).asset
                    step = if (a.addresses.size > 1) ReceiveStep.Addresses(a) else ReceiveStep.Assets
                }
            }
        })
        AnimatedContent(step, transitionSpec = { Motion.fadeThrough() }, label = "recv") { s ->
            when (s) {
                ReceiveStep.Assets -> AssetList(assets, nav) { a ->
                    step = if (a.addresses.size == 1) ReceiveStep.Qr(a, a.addresses[0]) else ReceiveStep.Addresses(a)
                }
                is ReceiveStep.Addresses -> AddressList(s.asset) { addr -> step = ReceiveStep.Qr(s.asset, addr) }
                is ReceiveStep.Qr -> QrPane(s.asset, s.addr)
            }
        }
    }
}

@Composable
private fun AssetList(assets: List<ReceiveAsset>, nav: NavHostController, onPick: (ReceiveAsset) -> Unit) {
    if (assets.isEmpty()) {
        CpCard(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Link, null, Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text("Adicione uma carteira para receber", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.navigate("addWallet") }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Adicionar endereço") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Text("Qual ativo deseja receber?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp, top = 4.dp))
        }
        items(assets, key = { it.network.name }) { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(a) }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TokenIcon(a.network.iconUrl, a.symbol, 40.dp, a.network)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (a.addresses.size == 1) walletAddressShort(a.addresses[0].address) else "${a.addresses.size} endereços",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AddressList(asset: ReceiveAsset, onPick: (ReceiveAddress) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(asset.addresses, key = { "${it.walletId}-${it.address}" }) { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(a) }.padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.walletName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(walletAddressShort(a.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun QrPane(asset: ReceiveAsset, addr: ReceiveAddress) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    val payload = remember(asset.network, addr.address) { PaymentUri.payload(asset.network, addr.address) }
    val qrSize = with(LocalDensity.current) { 240.dp.roundToPx() }
    val qr = remember(payload, qrSize) { runCatching { QrEncoder.encode(payload, qrSize) }.getOrNull() }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(260.dp).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
            if (qr != null) Image(qr.asImageBitmap(), "QR", Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(18.dp))
        Text("Rede: ${asset.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(addr.address, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { com.coinportracker.security.SecureClipboard.copy(ctx, "Endereço", addr.address); copied = true },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copiado" else "Copiar")
            }
            OutlinedButton(
                onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, addr.address)
                    ctx.startActivity(Intent.createChooser(i, "Compartilhar endereço").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Compartilhar")
            }
        }
    }
}

/** Passos pré-montados para as capturas de tela. */
object ReceivePreview {
    fun assets(): ReceiveStep = ReceiveStep.Assets
    fun addresses(asset: ReceiveAsset): ReceiveStep = ReceiveStep.Addresses(asset)
    fun qr(asset: ReceiveAsset, addr: com.coinportracker.receive.ReceiveAddress): ReceiveStep = ReceiveStep.Qr(asset, addr)
}
