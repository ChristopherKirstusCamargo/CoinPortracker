package com.coinportracker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.settings.RollRules
import com.coinportracker.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v5.1.0 — arquivos da Rolagem de banner. Cópias no armazenamento privado do app (filesDir/banner_roll),
 * sem permissões persistentes da galeria. Não entram no backup.
 */
object BannerLibrary {
    fun dir(ctx: Context) = File(ctx.filesDir, "banner_roll").apply { mkdirs() }
    fun file(ctx: Context, item: RollItem) = File(dir(ctx), item.file)

    /** v5.1.3: caminho do banner que vale agora (fonte única; ver [com.coinportracker.settings.bannerSource]). */
    fun source(ctx: Context, s: com.coinportracker.settings.AppSettings): String =
        com.coinportracker.settings.bannerSource(s, dir(ctx).absolutePath, com.coinportracker.ui.components.DEFAULT_BANNER)

    fun newId(kind: RollKind): String =
        kind.name.lowercase().take(1) + "_" + System.currentTimeMillis().toString(36) + "_" + (1000..9999).random()

    fun ext(kind: RollKind) = when (kind) { RollKind.GIF -> ".gif"; RollKind.PHOTO -> ".img"; RollKind.VIDEO -> ".mp4" }

    /** Tipo pelo conteúdo (não pela extensão). */
    fun detect(head: ByteArray, n: Int): RollKind? {
        if (n >= 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) return RollKind.GIF
        if (n >= 12 && VideoFormat.isVideoStream(head.copyOf(n).inputStream())) return RollKind.VIDEO
        return if (n > 0) RollKind.PHOTO else null
    }

    fun storageBytes(ctx: Context): Long = dir(ctx).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Copia uma imagem/GIF escolhida para a biblioteca. Devolve null quando entrou, ou a mensagem curta do motivo.
     * O limite é conferido de novo dentro da gravação (atômica) para nunca passar de 200. O arquivo é copiado byte a byte
     * (qualidade original) para o armazenamento privado: continua funcionando mesmo se for apagado da galeria.
     */
    suspend fun importImage(ctx: Context, uri: Uri, settings: SettingsRepository, select: Boolean = false): String? = withContext(Dispatchers.IO) {
        val tmp = File(dir(ctx), ".in-" + System.nanoTime())
        try {
            val head = ByteArray(16)
            var n = 0
            var kind: RollKind? = null
            var tooBig = false
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                n = input.read(head).coerceAtLeast(0)
                kind = detect(head, n)
                val k = kind ?: return@use
                if (k == RollKind.VIDEO) return@use
                val cap = RollRules.maxBytes(k)
                tmp.outputStream().use { out ->
                    out.write(head, 0, n)
                    val buf = ByteArray(64 * 1024)
                    var total = n.toLong()
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        total += r
                        if (total > cap) { tooBig = true; break }
                        out.write(buf, 0, r)
                    }
                }
            } ?: return@withContext "Não foi possível ler o arquivo."
            val k = kind ?: return@withContext "Não foi possível ler o arquivo."
            if (k == RollKind.VIDEO) return@withContext "Adicione vídeos na seção Vídeos."
            if (tooBig) return@withContext RollRules.tooBigMessage(k)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return@withContext "Formato de imagem não suportado."
            RollRules.canAdd(settings.current().roll, k)?.let { return@withContext it }
            val id = newId(k)
            val dst = File(dir(ctx), id + ext(k))
            if (!tmp.renameTo(dst)) return@withContext "Não foi possível salvar o arquivo."
            val item = RollItem(id, k, dst.name, dst.length(), System.currentTimeMillis())
            if (!addItem(settings, item, select = select)) { dst.delete(); return@withContext RollRules.limitMessage(k) }
            null
        } catch (e: Exception) {
            "Não foi possível adicionar: ${e.message ?: "erro"}"
        } finally { tmp.delete() }
    }

    /** Grava o item (fim do seu tipo) e liga a rolagem. false = recusado (limite). */
    suspend fun addItem(settings: SettingsRepository, item: RollItem, first: Boolean = false, select: Boolean = false): Boolean {
        var ok = false
        settings.update { s ->
            val next = if (first) RollRules.addFirst(s.roll, item) else RollRules.add(s.roll, item)
            ok = next !== s.roll
            // select: veio da Galeria/Arquivos do Banner com a Rolagem ativa → vira o banner atual (e o tipo fica visível)
            if (ok) s.copy(roll = RollRules.selectAdded(next.copy(enabled = true), item.id, select)) else s
        }
        return ok
    }

    suspend fun remove(ctx: Context, settings: SettingsRepository, id: String) = withContext(Dispatchers.IO) {
        var removed: RollItem? = null
        settings.update { s -> removed = s.roll.items.firstOrNull { it.id == id }; s.copy(roll = RollRules.remove(s.roll, id)) }
        removed?.let { file(ctx, it).delete(); MediaHd.forget(ctx, file(ctx, it).absolutePath) }
        forget(id)
    }

    /**
     * Uma vez só: o banner atual (se você escolheu um) entra como primeiro item. A rolagem continua desligada,
     * então a Início fica igual até você ligá-la. Também limpa itens sem arquivo e arquivos sem item.
     */
    suspend fun migrateAndTidy(ctx: Context, settings: SettingsRepository) = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.current()
            if (!s.roll.migrated) {
                val legacy = s.bannerUri.substringBefore("?v=").takeIf { it.isNotBlank() && !it.startsWith("asset:") }?.let(::File)?.takeIf { it.isFile }
                var item: RollItem? = null
                if (legacy != null) {
                    val head = ByteArray(16); val n = legacy.inputStream().use { it.read(head) }.coerceAtLeast(0)
                    val k = detect(head, n)
                    if (k != null && legacy.length() <= RollRules.maxBytes(k)) {
                        val id = newId(k); val dst = File(dir(ctx), id + ext(k))
                        legacy.copyTo(dst, overwrite = true)
                        item = RollItem(id, k, dst.name, dst.length(), System.currentTimeMillis())
                    }
                }
                settings.update { cur ->
                    val r = item?.let { RollRules.addFirst(cur.roll, it) } ?: cur.roll
                    cur.copy(roll = r.copy(migrated = true))
                }
            }
            // itens cujo arquivo sumiu
            val cur = settings.current().roll
            val missing = cur.items.filter { !file(ctx, it).isFile }.map { it.id }.toSet()
            if (missing.isNotEmpty()) settings.update { it.copy(roll = it.roll.copy(items = it.roll.items.filterNot { i -> i.id in missing })) }
            // arquivos sem item (ex.: app fechado no meio de uma cópia)
            val known = settings.current().roll.items.map { it.file }.toSet()
            dir(ctx).listFiles()?.forEach { f -> if (f.isFile && f.name !in known && System.currentTimeMillis() - f.lastModified() > 60_000) f.delete() }
        }
        runCatching { purgeHiddenVideos(ctx, settings) }
        runCatching { tidyVideoLeftovers(ctx, settings.current()) }
    }

    /**
     * v6.0 — desde a 5.1.5 a Rolagem aceita só GIFs e fotos; os vídeos que já estavam nela ficaram ocultos, sem uso,
     * mas ainda ocupavam espaço (até 64 MB cada). Agora eles saem da lista e do armazenamento (junto com a cópia HD),
     * uma vez, e a tela da Rolagem mostra quanto espaço foi liberado. Os originais na galeria não são tocados.
     */
    suspend fun purgeHiddenVideos(ctx: Context, settings: SettingsRepository): Pair<Int, Long> {
        val vids = settings.current().roll.items.filter { it.kind == RollKind.VIDEO }
        if (vids.isEmpty()) return 0 to 0L
        var bytes = 0L
        settings.update { s -> s.copy(roll = s.roll.copy(items = s.roll.items.filterNot { it.kind == RollKind.VIDEO }, selected = s.roll.selected.takeIf { sel -> vids.none { it.id == sel } } ?: "")) }
        for (v in vids) {
            val f = file(ctx, v)
            bytes += f.length().coerceAtLeast(0)
            f.delete(); MediaHd.forget(ctx, f.absolutePath); forget(v.id)
        }
        settings.update { it.copy(videoCleanNote = RollRules.cleanNote(vids.size, bytes, System.currentTimeMillis())) }
        return vids.size to bytes
    }

    /** Sobras de importação de vídeo (app fechado no meio) e o vídeo do banner único quando ele não é mais o banner. */
    fun tidyVideoLeftovers(ctx: Context, s: com.coinportracker.settings.AppSettings, now: Long = System.currentTimeMillis()) {
        if (VideoImport.rollPending.value > 0) return
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("roll-") && f.name.endsWith(".mp4") || f.name.startsWith("video-in")) && now - f.lastModified() > 6 * 3_600_000L) f.delete()
        }
        val banner = VideoImport.bannerFile(ctx)
        if (banner.isFile && !s.bannerUri.substringBefore("?v=").endsWith(banner.absolutePath) && now - banner.lastModified() > 10 * 60_000L) {
            banner.delete(); MediaHd.forget(ctx, banner.absolutePath)
        }
    }

    // ---- decodificação limitada (memória previsível) ----

    /** Bitmap reduzido para caber em [maxW]×[maxH] (inSampleSize em potência de 2). */
    fun decodeBounded(path: String, maxW: Int, maxH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var ss = 1
        while (o.outWidth / (ss * 2) >= maxW || o.outHeight / (ss * 2) >= maxH) ss *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = ss })
    }

    /** Primeiro quadro do vídeo, reduzido. */
    fun videoFrame(path: String, maxW: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (android.os.Build.VERSION.SDK_INT >= 27 && w > maxW && w > 0 && h > 0)
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxW, (h.toLong() * maxW / w).toInt().coerceAtLeast(1))
            else r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) { null } finally { runCatching { r.release() } }
    }

    /**
     * v5.1.1: miniaturas em cache por bytes (1/8 da memória do app, até 64 MB), chave = id@lado.
     * Decodificação fora da thread principal com no máximo 3 em paralelo, para a grade do seletor
     * continuar fluida mesmo com 200 itens.
     */
    private val thumbs = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt().coerceAtLeast(8 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)
    private fun forget(id: String) { thumbs.snapshot().keys.filter { it.startsWith("$id@") }.forEach { thumbs.remove(it) } }
    fun cachedThumb(item: RollItem, px: Int): Bitmap? = thumbs.get("${item.id}@$px")
    suspend fun thumb(ctx: Context, item: RollItem, px: Int = 192): Bitmap? = thumbs.get("${item.id}@$px") ?: withContext(decodeDispatcher) {
        val p = file(ctx, item).absolutePath
        runCatching { if (item.kind == RollKind.VIDEO) videoFrame(p, px) else decodeBounded(p, px, px) }.getOrNull()?.also { thumbs.put("${item.id}@$px", it) }
    }

    /** Lado (px) das miniaturas da grade do seletor (nítidas até ~130 dp numa tela 3x). */
    const val GRID_PX = 384

    /** v5.1.3: pré-carrega as primeiras miniaturas de cada tipo (em segundo plano), para o seletor abrir já cheio. */
    suspend fun prefetch(ctx: Context, items: List<RollItem>, perKind: Int = 15) {
        items.groupBy { it.kind }.values.flatMap { it.take(perKind) }.forEach { runCatching { thumb(ctx, it, GRID_PX) } }
    }

    /** GIF animado na grade do seletor (API 28+): AnimatedImageDrawable no tamanho da célula, nítido e leve. */
    suspend fun animatedGif(ctx: Context, item: RollItem, px: Int): android.graphics.drawable.Drawable? {
        if (android.os.Build.VERSION.SDK_INT < 28 || item.kind != RollKind.GIF) return null
        return withContext(decodeDispatcher) {
            runCatching {
                val src = android.graphics.ImageDecoder.createSource(file(ctx, item))
                android.graphics.ImageDecoder.decodeDrawable(src) { d, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val f = px.toFloat() / minOf(w, h).coerceAtLeast(1)
                    if (f < 1f) d.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                }.takeIf { it is android.graphics.drawable.AnimatedImageDrawable }
            }.getOrNull()
        }
    }

    /** Dimensões exibidas (com rotação) do vídeo de origem. */
    fun videoDisplaySize(path: String): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) { null } finally { runCatching { r.release() } }
    }

    /** Alvo até 1080p no lado menor, sem ampliar; dimensões pares. null = manter o tamanho original. */
    fun videoTarget(w: Int, h: Int, maxShort: Int = RollRules.VIDEO_MAX_SHORT_SIDE): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val short = minOf(w, h)
        if (short <= maxShort) return null
        val f = maxShort.toDouble() / short
        fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt().coerceAtLeast(2)
        return even(w * f) to even(h * f)
    }

    /** Usado pelo worker de vídeo ao terminar: move o arquivo pronto para a biblioteca e registra. */
    suspend fun adoptVideo(ctx: Context, ready: File, select: Boolean = false): String? {
        val settings = SettingsRepository(ctx.applicationContext)
        val k = RollKind.VIDEO
        if (RollRules.canAdd(settings.current().roll, k) != null) { ready.delete(); return RollRules.limitMessage(k) }
        val id = newId(k); val dst = File(dir(ctx), id + ext(k))
        if (!ready.renameTo(dst)) { ready.copyTo(dst, overwrite = true); ready.delete() }
        val ok = addItem(settings, RollItem(id, k, dst.name, dst.length(), System.currentTimeMillis()), select = select)
        if (!ok) { dst.delete(); return RollRules.limitMessage(k) }
        return null
    }

}
