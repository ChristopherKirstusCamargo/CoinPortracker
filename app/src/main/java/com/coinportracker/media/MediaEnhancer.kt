package com.coinportracker.media

import com.coinportracker.share.GifEncoder
import java.io.ByteArrayOutputStream

/** Super-resolução 4× de uma imagem ARGB (w×h → 4w×4h). Lança exceção se não puder rodar. */
typealias Sr4x = (px: IntArray, w: Int, h: Int) -> IntArray

/** Como a mídia foi ampliada (registrado para diagnóstico e testes). */
enum class EnhanceMethod { ML, LANCZOS }

class Enhanced<T>(val data: T, val width: Int, val height: Int, val method: EnhanceMethod)

/**
 * Ampliação de imagens e GIFs, puro JVM. Com [sr] (modelo Real-ESRGAN via TFLite) a imagem é ampliada 4× pelo
 * modelo e reduzida com Lanczos para o tamanho final; sem modelo, sem orçamento ou em caso de falha, usa
 * Lanczos-3 + nitidez ([Upscaler]).
 */
object MediaEnhancer {
    const val GIF_MAX_WIDTH = 960
    const val GIF_MAX_TOTAL_PIXELS = 60_000_000L
    /** Pixels de entrada (soma dos quadros ÚNICOS) que o modelo processa num GIF; acima disso, Lanczos. */
    const val GIF_ML_BUDGET = 8_000_000L
    /** Máximo de quadros únicos enviados ao modelo. */
    const val GIF_ML_MAX_FRAMES = 150
    /** Maior imagem estática (entrada) enviada ao modelo. */
    const val STILL_ML_MAX_PIXELS = 700_000L
    /** v3.2: amplia no máximo 2× (e nunca além da largura da tela): mais que isso não aparece no celular e custa 4× mais. */
    const val ML_MAX_FACTOR = 2.0
    /** Orçamentos de tempo (v3.2): foto ~20 s, GIF ~2 min; passou disso → Lanczos. */
    const val STILL_BUDGET_MS = 20_000L
    const val GIF_BUDGET_MS = 110_000L

    /**
     * Quando o fator final é < 2, o modelo (sempre 4×) produziria pixels demais: reduzimos a entrada para metade do
     * tamanho final antes (com antialiasing). Mesmo resultado na tela, até 4× menos trabalho.
     */
    internal fun mlInput(px: IntArray, w: Int, h: Int, dw: Int, dh: Int): Triple<IntArray, Int, Int> {
        val iw = (dw + 1) / 2; val ih = (dh + 1) / 2
        return if (iw < w * 0.95 && ih < h * 0.95) Triple(Upscaler.resample(px, w, h, iw, ih), iw, ih) else Triple(px, w, h)
    }

    fun enhanceStill(px: IntArray, w: Int, h: Int, targetW: Int, sr: Sr4x?): Enhanced<IntArray>? {
        val useMl = sr != null && w.toLong() * h <= STILL_ML_MAX_PIXELS
        val (dw, dh) = Upscaler.targetSize(w, h, targetW, maxFactor = if (useMl) ML_MAX_FACTOR else Upscaler.MAX_FACTOR) ?: return null
        if (useMl) {
            val (ip, iw, ih) = mlInput(px, w, h, dw, dh)
            val up = runCatching { sr!!(ip, iw, ih) }.getOrNull()
            if (up != null) return Enhanced(Upscaler.resample(up, iw * 4, ih * 4, dw, dh), dw, dh, EnhanceMethod.ML)
        }
        return Enhanced(Upscaler.upscale(px, w, h, dw, dh), dw, dh, EnhanceMethod.LANCZOS)
    }

    /** Índice do primeiro quadro idêntico para cada quadro (GIFs repetem muitos quadros: processa só uma vez). */
    internal fun dedup(frames: List<IntArray>): IntArray {
        val firstByHash = HashMap<Int, MutableList<Int>>()
        return IntArray(frames.size) { i ->
            val f = frames[i]; val hsh = f.contentHashCode()
            val same = firstByHash[hsh]?.firstOrNull { frames[it].contentEquals(f) }
            if (same != null) same else { firstByHash.getOrPut(hsh) { ArrayList() } += i; i }
        }
    }

    /**
     * GIF ampliado ou null quando não vale a pena (já grande, muitos quadros ou inválido).
     * Com modelo: processa só os quadros únicos; mede o 1º quadro e, se a estimativa passar de [budgetMs], vai
     * direto para o Lanczos (tudo ou nada: misturar métodos faria o GIF cintilar).
     */
    fun enhanceGif(bytes: ByteArray, targetW: Int, sr: Sr4x? = null, onProgress: (Float) -> Unit = {}, budgetMs: Long = GIF_BUDGET_MS,
                   clock: () -> Long = System::currentTimeMillis): Enhanced<ByteArray>? {
        // v6.0: antes o GIF inteiro era decodificado (todos os quadros na memória) ANTES de qualquer limite: um GIF de
        // 800×800 com 300 quadros pedia ~770 MB e derrubava o app no trabalho em segundo plano. Agora há teto na leitura
        // (um quarto da memória do app) e os quadros ampliados saem da memória assim que não são mais usados.
        val g = runCatching { GifDecoder.decode(bytes, maxTotalPixels = memBudgetPixels) }.getOrNull() ?: return null
        val map = dedup(g.frames)
        val unique = map.indices.filter { map[it] == it }
        val inPixels = g.width.toLong() * g.height * unique.size
        val mlCandidate = sr != null && inPixels <= GIF_ML_BUDGET && unique.size <= GIF_ML_MAX_FRAMES
        val (dw, dh) = Upscaler.targetSize(g.width, g.height, minOf(targetW, GIF_MAX_WIDTH), maxFactor = if (mlCandidate) ML_MAX_FACTOR else Upscaler.MAX_FACTOR) ?: return null
        if (dw.toLong() * dh * g.frames.size > GIF_MAX_TOTAL_PIXELS) return null
        // o caminho com modelo guarda todos os quadros únicos ampliados até o fim: só quando cabem no orçamento
        val useMl = mlCandidate && dw.toLong() * dh * unique.size <= memBudgetPixels
        if (useMl) {
            val t0 = clock()
            val cache = HashMap<Int, IntArray>()
            val ok = runCatching {
                unique.forEachIndexed { k, i ->
                    val (ip, iw, ih) = mlInput(opaque(g.frames[i]), g.width, g.height, dw, dh)
                    cache[i] = Upscaler.resample(sr!!(ip, iw, ih), iw * 4, ih * 4, dw, dh)
                    val el = clock() - t0
                    if (k == 0 && el * unique.size > budgetMs) throw SrTimeout()  // estimativa estoura o orçamento
                    if (el > budgetMs) throw SrTimeout()
                    onProgress(0.9f * (k + 1) / unique.size)
                }
            }.isSuccess
            if (ok) return Enhanced(encode(g, dw, dh, {}) { idx, _ -> cache[map[idx]]!! }, dw, dh, EnhanceMethod.ML).also { onProgress(1f) }
        }
        val lz = HashMap<Int, IntArray>()
        val lastUse = lastUse(map)
        return Enhanced(encode(g, dw, dh, onProgress) { idx, f ->
            val key = map[idx]
            val out = lz[key] ?: Upscaler.upscale(opaque(f), g.width, g.height, dw, dh).also { if (lastUse[key] > idx) lz[key] = it }
            if (lastUse[key] <= idx) lz.remove(key)
            out
        }, dw, dh, EnhanceMethod.LANCZOS)
    }

    /** Para cada quadro único, o último índice que o reutiliza (o cache solta o quadro depois dele). */
    internal fun lastUse(map: IntArray): IntArray { val l = IntArray(map.size) { -1 }; for (i in map.indices) l[map[i]] = i; return l }

    /** Um quarto da memória do app, em pixels ARGB (4 bytes). */
    internal val memBudgetPixels: Long get() = (Runtime.getRuntime().maxMemory() / 16).coerceIn(4_000_000L, 40_000_000L)

    private fun encode(g: GifFrames, dw: Int, dh: Int, onProgress: (Float) -> Unit, up: (Int, IntArray) -> IntArray): ByteArray {
        val out = ByteArrayOutputStream(dw * dh)
        val enc = GifEncoder(out, dw, dh)
        g.frames.forEachIndexed { i, f ->
            enc.addFrameLocal(up(i, f), g.delaysMs[i])
            onProgress((i + 1f) / g.frames.size)
        }
        enc.finish()
        return out.toByteArray()
    }

    /** O GIF reencodado não usa transparência: pixels transparentes viram preto (o banner fica sobre fundo escuro). */
    private fun opaque(f: IntArray) = IntArray(f.size) { i -> val c = f[i]; if ((c ushr 24) < 128) 0xFF000000.toInt() else c or 0xFF000000.toInt() }
}
