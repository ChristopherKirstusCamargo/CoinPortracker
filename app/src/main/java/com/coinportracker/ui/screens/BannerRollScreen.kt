package com.coinportracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.media.BannerLibrary
import com.coinportracker.media.VideoDest
import com.coinportracker.media.VideoImport
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.settings.RollRules
import com.coinportracker.settings.SettingsRepository
import com.coinportracker.ui.theme.LocalExtra
import com.coinportracker.ui.theme.LocalLowPower
import com.coinportracker.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/** O seletor do sistema recusa pedir mais que o teto dele (100 em geral): pede no máximo isso por vez. */
private fun pickerMax(want: Int): Int = minOf(want, if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { android.provider.MediaStore.getPickImagesMaxLimit() }.getOrDefault(100) else 100).coerceAtLeast(2)

/** v5.1.0 — Ajustes → Rolagem de banner (tela própria, separada do Banner). */
@Composable
fun BannerRollScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val roll = h.settings.roll
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val pending by VideoImport.rollPending.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }

    fun addImages(uris: List<android.net.Uri>, kind: RollKind) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true
            var msg: String? = null
            for (u in uris) { BannerLibrary.importImage(ctx, u, repo)?.let { msg = it }; if (msg != null && msg == RollRules.limitMessage(kind)) break }
            busy = false
            msg?.let { vm.toast(it) }
        }
    }
    val pickGifs = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(pickerMax(RollRules.MAX_GIFS))) { addImages(it, RollKind.GIF) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(pickerMax(RollRules.MAX_PHOTOS))) { addImages(it, RollKind.PHOTO) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            RollRules.canAdd(repo.current().roll, RollKind.VIDEO, VideoImport.rollPending.value)?.let { vm.toast(it); return@launch }
            val p = VideoImport.offer(ctx, uri, VideoDest.ROLL)
            if (p == null) vm.toast("Não foi possível abrir esse vídeo.") else nav.navigate("trimVideo")
        }
    }
    fun guard(kind: RollKind, extra: Int = 0, go: () -> Unit) { RollRules.canAdd(roll, kind, extra)?.let { vm.toast(it) } ?: go() }
    val storage by produceState(0L, roll.items) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BannerLibrary.storageBytes(ctx) } }

    Column(Modifier.fillMaxSize()) {
        V2TopBar("Rolagem de banner", { nav.popBackStack() })
        // v5.1.1: lista preguiçosa (até 200 GIFs + 200 fotos): só as linhas visíveis compõem e decodificam miniaturas
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item("hint") {
                Column {
                    TapHintCard(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp))
                    Text("Toque no banner da Início para escolher a mídia: primeiro o tipo, depois o GIF ou a foto.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                }
            }
            item("switches") {
                Group("Rolagem") {
                    SwitchRow("Ativar rolagem", if (roll.items.isEmpty()) "Adicione mídias abaixo" else null, roll.enabled) { on -> vm.updateSettings { it.copy(roll = it.roll.copy(enabled = on)) } }
                    SwitchRow("GIFs no seletor", null, roll.gifsOn) { on -> vm.updateSettings { it.copy(roll = it.roll.copy(gifsOn = on)) } }
                    SwitchRow("Fotos no seletor", null, roll.photosOn) { on -> vm.updateSettings { it.copy(roll = it.roll.copy(photosOn = on)) } }
                    Text("Cópias guardadas no app, na qualidade original · Armazenamento: ${RollRules.fmtBytes(storage)}" + if (h.settings.lowPower) " · Baixo consumo: imagens paradas" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                    RollRules.cleanNoteText(h.settings.videoCleanNote)?.let { note ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.updateSettings { it.copy(videoCleanNote = "") } }) { Text("Ok") }
                        }
                    }
                }
            }
            mediaSection("GIFs", roll.of(RollKind.GIF), RollRules.MAX_GIFS, true, vm, repo, busy, scope, ctx, selected = roll.current?.id.takeIf { roll.active }) {
                guard(RollKind.GIF) { pickGifs.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif"))) }
            }
            mediaSection("Fotos", roll.of(RollKind.PHOTO), RollRules.MAX_PHOTOS, true, vm, repo, busy, scope, ctx, selected = roll.current?.id.takeIf { roll.active }) {
                guard(RollKind.PHOTO) { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            if (pending > 0) item("job") { VideoJobRow(ctx) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.mediaSection(
    title: String, items: List<RollItem>, max: Int, orderable: Boolean, vm: com.coinportracker.ui.UiModel, repo: SettingsRepository,
    busy: Boolean, scope: kotlinx.coroutines.CoroutineScope, ctx: android.content.Context, extraCount: Int = 0, note: String? = null, selected: String? = null, onAdd: () -> Unit,
) {
    item("h-$title") {
        Column {
            Text("$title · ${items.size + extraCount}" + if (max == Int.MAX_VALUE) "" else "/$max", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp))
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
        }
    }
    itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
        // v5.1.3: tocar na linha deixa como banner atual (o mesmo que escolher no seletor da Início)
        val inUse = item.id == selected
        Row(Modifier.fillMaxWidth().animateItem(placementSpec = com.coinportracker.ui.theme.Motion.itemPlacement).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Usar como banner") { vm.updateSettings { it.copy(roll = it.roll.copy(selected = item.id)) } }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (orderable) "${i + 1}º" else "Vídeo ${i + 1}") + if (inUse) " · Em uso" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(RollRules.fmtBytes(item.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (orderable) {
                IconButton(onClick = { vm.updateSettings { it.copy(roll = RollRules.move(it.roll, item.id, -1)) } }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Subir") }
                IconButton(onClick = { vm.updateSettings { it.copy(roll = RollRules.move(it.roll, item.id, 1)) } }, enabled = i < items.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Descer") }
            }
            IconButton(onClick = { scope.launch { BannerLibrary.remove(ctx, repo, item.id) } }) { Icon(Icons.Outlined.Close, "Remover") }
        }
    }
    item("a-$title") {
        val full = items.size + extraCount >= max
        OutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), shape = RoundedCornerShape(12.dp)) {
            Text(if (full) "Limite atingido" else when (title) { "GIFs" -> "Adicionar GIF"; "Fotos" -> "Adicionar foto"; else -> "Adicionar vídeo" }, maxLines = 1)
        }
    }
}

@Composable
private fun Thumb(item: RollItem) {
    val ctx = LocalContext.current
    val bmp by produceState(BannerLibrary.cachedThumb(item, 192), item.id) { if (value == null) value = BannerLibrary.thumb(ctx, item) }
    Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(LocalExtra.current.cardHigh), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        if (item.kind == RollKind.VIDEO) Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.85f))
    }
}

/**
 * v5.1.1 — cartão pequeno em preto e branco: um dedo minimalista toca o banner, ondas de toque se abrem e
 * uma janela (o seletor) surge no centro. Vetor no Canvas; parado no baixo consumo ou com "Sem animação".
 */
@Composable
fun TapHintCard(modifier: Modifier = Modifier) {
    val still = LocalReducedMotion.current || LocalLowPower.current
    val t = if (still) remember { mutableFloatStateOf(0.62f) } else {
        val inf = rememberInfiniteTransition(label = "tapHint")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "t")
    }
    val line = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.height(88.dp).clip(shape).background(Color.Black).border(1.dp, line.copy(alpha = 0.14f), shape)) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            val sw = 1.6.dp.toPx()
            val v = t.value
            // fases: 0–0.25 dedo desce · 0.25–0.55 ondas do toque · 0.45–0.85 seletor aparece · depois some
            fun ph(a: Float, b: Float) = ((v - a) / (b - a)).coerceIn(0f, 1f)
            val ease = { x: Float -> FastOutSlowInEasing.transform(x) }
            // banner (retângulo largo)
            val bw = w * 0.58f; val bh = h * 0.52f; val bl = w / 2 - bw / 2; val bt = h * 0.16f
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(bl, bt), Size(bw, bh), CornerRadius(8.dp.toPx()), style = Stroke(sw))
            // seletor: janela central com uma grade 3×2
            val pop = ease(ph(0.45f, 0.70f)) * (1f - ph(0.88f, 1f))
            if (pop > 0f) {
                val pw = bw * 0.62f * (0.8f + 0.2f * pop); val phh = bh * 0.80f * (0.8f + 0.2f * pop)
                val pl = w / 2 - pw / 2; val pt = bt + bh / 2 - phh / 2
                drawRoundRect(Color.Black, Offset(pl, pt), Size(pw, phh), CornerRadius(6.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = 0.55f * pop), Offset(pl, pt), Size(pw, phh), CornerRadius(6.dp.toPx()), style = Stroke(sw))
                val g = 3.dp.toPx(); val cw = (pw - g * 4) / 3; val ch = (phh - g * 3) / 2
                for (r in 0..1) for (c in 0..2) {
                    val cell = ease(ph(0.52f + r * 0.08f, 0.70f + r * 0.08f)) * (1f - ph(0.88f, 1f))
                    drawRoundRect(Color.White.copy(alpha = 0.28f * cell), Offset(pl + g + c * (cw + g), pt + g + r * (ch + g) + (1f - cell) * 4.dp.toPx()), Size(cw, ch), CornerRadius(2.dp.toPx()))
                }
            }
            // ondas do toque
            val tap = Offset(w / 2 + bw * 0.18f, bt + bh * 0.58f)
            val rip = ph(0.22f, 0.60f)
            if (rip in 0.001f..0.999f) for (k in 0..1) {
                val q = (rip - k * 0.25f).coerceIn(0f, 1f)
                if (q > 0f) drawCircle(Color.White.copy(alpha = 0.5f * (1f - q)), 6.dp.toPx() + q * 22.dp.toPx(), tap, style = Stroke(sw))
            }
            // dedo: desce até tocar e sobe de novo
            val down = ease(ph(0f, 0.22f)) * (1f - ease(ph(0.30f, 0.55f)))
            val u = h / 90f
            val lift = (1f - down) * 10f * u
            val fx = tap.x - 4.5f * u; val fy = tap.y - 2f * u + lift
            fun rr(x: Float, y: Float, ww: Float, hh: Float, r: Float) = Path().apply { addRoundRect(RoundRect(fx + x * u, fy + y * u, fx + (x + ww) * u, fy + (y + hh) * u, CornerRadius(r * u))) }
            var hand = Path.combine(PathOperation.Union, rr(0f, 0f, 9f, 30f, 4.5f), rr(-5f, 22f, 28f, 26f, 9f))
            hand = Path.combine(PathOperation.Union, hand, rr(8f, 19f, 8f, 12f, 4f))
            hand = Path.combine(PathOperation.Union, hand, rr(15f, 21f, 8f, 11f, 4f))
            hand = Path.combine(PathOperation.Union, hand, rr(-11f, 28f, 11f, 8f, 4f))
            drawPath(hand, Color.Black)
            drawPath(hand, Color.White, style = Stroke(sw * 1.2f))
            drawCircle(Color.White.copy(alpha = 0.25f + 0.5f * down), 2.5.dp.toPx(), Offset(fx + 4.5f * u, fy + 3.5f * u))
        }
    }
}
