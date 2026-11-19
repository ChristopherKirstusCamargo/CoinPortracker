package com.coinportracker.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Gera os arquivos do cartão (PNG ou GIF animado), abre o menu de compartilhar e salva na galeria. */
object ShareExport {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    suspend fun png(ctx: Context, bmp: Bitmap): File = withContext(Dispatchers.IO) {
        File(dir(ctx), "coinportracker-carteira.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** Decodifica um GIF (bytes) e devolve o quadro no instante [t] (ms). */
    @Suppress("DEPRECATION")
    fun gifFrame(movie: Movie, t: Int): Bitmap {
        val b = Bitmap.createBitmap(movie.width().coerceAtLeast(1), movie.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        movie.setTime(t); movie.draw(Canvas(b), 0f, 0f)
        return b
    }

    @Suppress("DEPRECATION")
    fun movieOf(bytes: ByteArray): Movie? = Movie.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width() > 0 && it.duration() > 0 }

    /**
     * GIF animado: o cartão desenhado sobre cada quadro do GIF do banner (até 3 s, ~12 qps, 600×750).
     * Arquivo típico: 2–6 MB.
     */
    suspend fun gif(ctx: Context, renderer: ShareCardRenderer, d: ShareData, o: ShareOptions, gifBytes: ByteArray, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Default) {
        val movie = movieOf(gifBytes) ?: error("GIF inválido")
        val w = 600; val h = 750
        val step = 80
        val dur = movie.duration().coerceIn(step, 3000)
        val frames = (dur / step).coerceIn(1, 38)
        val f = File(dir(ctx), "coinportracker-carteira.gif")
        f.outputStream().buffered(1 shl 16).use { out ->
            val enc = GifEncoder(out, w, h)
            val px = IntArray(w * h)
            val card = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(card)
            for (i in 0 until frames) {
                val bg = gifFrame(movie, i * step)
                renderer.draw(c, w, h, d, o, bg)
                bg.recycle()
                card.getPixels(px, 0, w, 0, 0, w, h)
                enc.addFrame(px, step)
                withContext(Dispatchers.Main) { onProgress((i + 1f) / frames) }
            }
            enc.finish()
        }
        f
    }

    fun share(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".share", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartilhar carteira").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android 10+: salva em Imagens/CoinPortracker sem pedir permissão. Retorna false nas versões antigas (a tela usa "Salvar como"). */
    suspend fun saveToGallery(ctx: Context, f: File, mime: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val video = mime.startsWith("video/")
        val v = ContentValues().apply {
            val name = "coinportracker-" + System.currentTimeMillis() + "." + f.extension
            if (video) {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CoinPortracker")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CoinPortracker")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val r = ctx.contentResolver
        val uri = r.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@withContext false
        r.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0); r.update(uri, v, null, null)
        true
    }
}
