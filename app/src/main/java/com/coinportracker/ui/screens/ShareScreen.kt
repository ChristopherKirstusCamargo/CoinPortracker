package com.coinportracker.ui.screens

import kotlinx.coroutines.sync.withLock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coinportracker.core.Fmt
import com.coinportracker.media.MediaHd
import com.coinportracker.share.*
import com.coinportracker.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Mídia de fundo carregada: GIF animado (bytes + Movie) ou imagem estática. */
private class BgMedia(val bytes: ByteArray?, val movie: android.graphics.Movie?, val still: Bitmap?, val videoPath: String? = null) {
    val animated get() = movie != null && bytes != null
    val isVideo get() = videoPath != null
    private var reuse: Bitmap? = null
    /** Quadro no instante [t]; para GIF reaproveita o mesmo bitmap (sem alocar a cada quadro da prévia). */
    @Suppress("DEPRECATION")
    fun frame(t: Int): Bitmap? {
        val m = movie ?: return still
        val b = reuse ?: Bitmap.createBitmap(m.width().coerceAtLeast(1), m.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { reuse = it }
        b.eraseColor(0); m.setTime(t); m.draw(android.graphics.Canvas(b), 0f, 0f)
        return b
    }
}

private fun decodeMedia(bytes: ByteArray?): BgMedia? {
    if (bytes == null || bytes.isEmpty()) return null
    val movie = if (com.coinportracker.media.GifDecoder.isGif(bytes)) ShareExport.movieOf(bytes) else null
    if (movie != null) return BgMedia(bytes, movie, null)
    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
    var sample = 1; while (opt.outWidth / sample > 1600 || opt.outHeight / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { BgMedia(null, null, it) }
}

/**
 * Compartilhar portfólio (estilo CoinStats/Axiom): cartão com a alocação em %, sem valores por padrão.
 * Fundo: banner do Início, qualquer foto/GIF do aparelho (GIF exporta animado), sólido ou degradê.
 * Layout adaptativo: prévia limitada pela altura da tela; em telas largas, prévia e opções lado a lado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareScreen(vm: com.coinportracker.ui.UiModel, nav: NavHostController, initial: ShareOptions = ShareOptions()) {
    val h by vm.hub.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderer = remember { ShareCardRenderer(ctx) }
    var o by remember { mutableStateOf(initial) }
    var gallery by remember { mutableStateOf<BgMedia?>(null) }
    var banner by remember { mutableStateOf<BgMedia?>(null) }
    var busy by remember { mutableStateOf<Float?>(null) }
    var lastFile by remember { mutableStateOf<Pair<File, String>?>(null) }
    val today = remember { Fmt.date(System.currentTimeMillis()) }
    val hdV by MediaHd.version.collectAsStateWithLifecycle()
    val bannerSrc = com.coinportracker.ui.components.rememberHdSource(h.settings.bannerUri.ifBlank { DEFAULT_BANNER }, h.settings.upscaleMedia)
    val shareVideo by com.coinportracker.media.VideoImport.sharePath.collectAsStateWithLifecycle()
    val videoJob by com.coinportracker.media.VideoImport.status(ctx).collectAsStateWithLifecycle(com.coinportracker.media.VideoJob())
    LaunchedEffect(bannerSrc) {
        banner = withContext(Dispatchers.IO) {
            if (com.coinportracker.media.VideoFormat.isVideoPath(bannerSrc)) BgMedia(null, null, null, bannerSrc.substringBefore("?v="))
            else runCatching { decodeMedia(MediaHd.readBytes(ctx, bannerSrc)) }.getOrNull()
        }
    }
    LaunchedEffect(shareVideo) {
        shareVideo?.let { gallery = BgMedia(null, null, null, it); o = o.copy(bg = ShareBg.GALLERY) }
    }

    val media: BgMedia? = when (o.bg) { ShareBg.BANNER -> banner; ShareBg.GALLERY -> gallery; else -> null }
    val data = remember(h.agg, o, h.currency) { ShareData.from(h, o.topN, if (o.showValues) Fmt.money(h.conv(h.agg.totalUsd), h.currency, false) else null, today) }

    // prévia (animada quando o fundo é GIF). v6.0: dois bitmaps alternados (um é desenhado em segundo plano enquanto o
    // outro está na tela) e um único desenho por vez: antes o mesmo bitmap era escrito fora da thread principal enquanto a
    // tela o lia (quadros rasgados/piscando) e uma troca rápida de fundo podia deixar dois desenhos simultâneos no mesmo Canvas.
    val pw = 540; val ph = 675
    val bufs = remember { Array(2) { Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888) } }
    val canvases = remember { Array(2) { android.graphics.Canvas(bufs[it]) } }
    val drawLock = remember { kotlinx.coroutines.sync.Mutex() }
    var front by remember { mutableIntStateOf(0) }
    val previewPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    LaunchedEffect(data, o, media) {
        val m = media
        suspend fun render(bg: Bitmap?) {
            val back = 1 - front
            drawLock.withLock { withContext(Dispatchers.Default) { renderer.draw(canvases[back], pw, ph, data, o, bg) } }
            front = back
        }
        if (m?.animated == true) {
            val dur = m.movie!!.duration().coerceAtLeast(100)
            var t = 0
            while (isActive) {
                val back = 1 - front
                drawLock.withLock { withContext(Dispatchers.Default) { m.frame(t)?.let { renderer.draw(canvases[back], pw, ph, data, o, it) } } }
                front = back
                delay(80); t = (t + 80) % dur
            }
        } else render(m?.still)
    }

    fun loadPicked(uri: Uri) = scope.launch {
        val video = isVideoUri(ctx, uri)
        if (video) {
            val p = com.coinportracker.media.VideoImport.offer(ctx, uri, com.coinportracker.media.VideoDest.SHARE)
            if (p == null) vm.toast("Não foi possível abrir esse vídeo.") else nav.navigate("trimVideo")
            return@launch
        }
        val m = withContext(Dispatchers.IO) { runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { s -> com.coinportracker.core.BoundedRead.bytes(s, com.coinportracker.core.BoundedRead.MEDIA) }
            decodeMedia(bytes)
        }.getOrNull() }
        if (m != null) { gallery = m; o = o.copy(bg = ShareBg.GALLERY) } else vm.toast("Não foi possível abrir essa mídia.")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadPicked(uri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadPicked(uri) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val lf = lastFile
        if (uri != null && lf != null) scope.launch(Dispatchers.IO) { val r = runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { out -> lf.first.inputStream().use { it.copyTo(out) } } }; withContext(Dispatchers.Main) { vm.toast(if (r.isSuccess) "Imagem salva." else "Não foi possível salvar a imagem.") } }
    }

    var stillFallback by remember { mutableStateOf(false) }
    suspend fun build(): Pair<File, String> {
        val m = media
        stillFallback = false
        val r = if (m?.isVideo == true && m.videoPath != null) {
            val fg = withContext(Dispatchers.Default) { renderer.foreground(1080, 1350, data, o) }
            try {
                val f = ShareVideoExport.mp4(ctx, File(m.videoPath.substringBefore("?v=")), fg) { busy = it }
                f to "video/mp4"
            } catch (e: Exception) {
                stillFallback = true
                val frame = withContext(Dispatchers.IO) {
                    val rtr = android.media.MediaMetadataRetriever()
                    runCatching { rtr.setDataSource(m.videoPath.substringBefore("?v=")); rtr.frameAtTime }.getOrNull().also { runCatching { rtr.release() } }
                }
                ShareExport.png(ctx, withContext(Dispatchers.Default) { renderer.render(1080, 1350, data, o, frame) }) to "image/png"
            }
        } else if (m?.animated == true) ShareExport.gif(ctx, renderer, data, o, m.bytes!!) { busy = it } to "image/gif"
        else ShareExport.png(ctx, withContext(Dispatchers.Default) { renderer.render(1080, 1350, data, o, m?.still) }) to "image/png"
        lastFile = r
        return r
    }
    fun export(save: Boolean) = scope.launch {
        busy = 0f
        runCatching {
            val (f, m) = build()
            if (!save) ShareExport.share(ctx, f, m)
            else if (ShareExport.saveToGallery(ctx, f, m)) vm.toast(if (m.startsWith("video/")) "Salvo em Filmes › CoinPortracker." else "Salvo em Imagens › CoinPortracker.")
            else saveAs.launch(f.name)
            if (stillFallback) vm.toast("O cartão foi salvo como imagem (o vídeo não pôde ser exportado).")
        }.onFailure { vm.toast("Não foi possível gerar o cartão.") }
        busy = null
    }

    val fgBmp = remember(data, o) { renderer.foreground(pw, ph, data, o) }
    val preview: @Composable (Modifier) -> Unit = { mod ->
        val vp = media?.videoPath
        if (vp != null) {
            Box(mod.aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp))) {
                com.coinportracker.ui.components.LoopingVideo(vp, Modifier.fillMaxSize())
                androidx.compose.foundation.Image(fgBmp.asImageBitmap(), null, Modifier.fillMaxSize())
            }
        } else {
            Canvas(mod.aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp))) {
                val shown = bufs[front]   // lê o estado: redesenha a cada quadro novo
                drawIntoCanvas { c -> c.nativeCanvas.drawBitmap(shown, null, android.graphics.RectF(0f, 0f, size.width, size.height), previewPaint) }
            }
        }
    }
    val options: @Composable () -> Unit = {
        CpCard {
            Text("Fundo", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareBg.entries.forEach { b ->
                    FilterChip(selected = o.bg == b, onClick = {
                        if (b == ShareBg.GALLERY && gallery == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) else o = o.copy(bg = b)
                    }, label = { Text(b.label, maxLines = 1) })
                }
            }
            if (o.bg == ShareBg.GALLERY) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Text("Trocar") }
                TextButton(onClick = { pickFile.launch(arrayOf("image/gif", "image/*", "video/*")) }) { Text("Arquivos") }
            }
            Spacer(Modifier.height(14.dp))
            Text("Gráfico", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Segments(ShareStyle.entries, o.style, { it.label }, { o = o.copy(style = it) })
            Spacer(Modifier.height(14.dp))
            Text("Posições", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Segments(listOf(3, 5, 8), o.topN, { "Top $it" }, { o = o.copy(topN = it) })
            Spacer(Modifier.height(6.dp))
            ShareSwitch("Redes", o.showChains) { o = o.copy(showChains = it) }
            ShareSwitch("Quantidade de ativos", o.showCount) { o = o.copy(showCount = it) }
            ShareSwitch("Data", o.showDate) { o = o.copy(showDate = it) }
            ShareSwitch("Valor total", o.showValues) { o = o.copy(showValues = it) }
        }
    }
    val actions: @Composable () -> Unit = {
        val p = busy
        Column(Modifier.fillMaxWidth()) {
            val vj = videoJob
            if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(bottom = 10.dp))
            else if (vj.dest == com.coinportracker.media.VideoDest.SHARE && (vj.state == com.coinportracker.media.VideoJobState.QUEUED || vj.state == com.coinportracker.media.VideoJobState.RUNNING))
                LinearProgressIndicator(progress = { vj.progress }, Modifier.fillMaxWidth().padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = p == null, colors = cpButtonColors(), onClick = { export(save = false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Compartilhar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(enabled = p == null, onClick = { export(save = true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Salvar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        V2TopBar("Compartilhar portfólio", { nav.popBackStack() })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight * 0.9f
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { preview(Modifier.fillMaxHeight()) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { options() }
                }
            } else {
                // a prévia ocupa no máximo ~45% da altura útil, sem nunca passar da largura
                val pvH = minOf(maxHeight * 0.45f, (maxWidth - 32.dp) * 1.25f)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { preview(Modifier.height(pvH)) }
                    Spacer(Modifier.height(16.dp))
                    options()
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) { actions() }
        }
    }
}

@Composable
private fun ShareSwitch(title: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        CpSwitch(v, onChange)
    }
}
