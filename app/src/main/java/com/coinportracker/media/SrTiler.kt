package com.coinportracker.media

/**
 * Executa um modelo de super-resolução de entrada fixa ([tile]×[tile]×3, RGB 0..1) sobre uma imagem de qualquer
 * tamanho, em blocos com margem de [pad] px (as bordas de cada bloco são descartadas para não aparecer emenda).
 * Independente do TFLite para poder ser testado na JVM.
 */
class SrTiler(val tile: Int = 128, val pad: Int = 10, val scale: Int = 4) {
    init { require(tile > 2 * pad) }

    /** [infer] recebe a entrada (tile²×3) e preenche a saída ((tile·scale)²×3). Retorna ARGB (w·scale)×(h·scale). */
    /** Número de blocos que [run] vai processar (para progresso e estimativa de tempo). */
    fun tiles(w: Int, h: Int): Int { val core = tile - 2 * pad; return ((w + core - 1) / core) * ((h + core - 1) / core) }

    fun run(px: IntArray, w: Int, h: Int, deadlineMs: Long = Long.MAX_VALUE, onTile: (done: Int, total: Int) -> Unit = { _, _ -> }, infer: (FloatArray, FloatArray) -> Unit): IntArray {
        val total = tiles(w, h); var done = 0
        val core = tile - 2 * pad
        val ow = w * scale; val oh = h * scale
        val out = IntArray(ow * oh)
        val inp = FloatArray(tile * tile * 3)
        val res = FloatArray(tile * scale * tile * scale * 3)
        val ts = tile * scale
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                if (System.currentTimeMillis() > deadlineMs) throw SrTimeout()
                val ox = tx - pad; val oy = ty - pad
                var i = 0
                for (y in 0 until tile) {
                    val sy = (oy + y).coerceIn(0, h - 1) * w
                    for (x in 0 until tile) {
                        val c = px[sy + (ox + x).coerceIn(0, w - 1)]
                        inp[i++] = ((c shr 16) and 0xFF) / 255f; inp[i++] = ((c shr 8) and 0xFF) / 255f; inp[i++] = (c and 0xFF) / 255f
                    }
                }
                infer(inp, res)
                onTile(++done, total)
                val cw = minOf(core, w - tx); val ch = minOf(core, h - ty)
                for (y in 0 until ch * scale) {
                    val ry = (pad * scale + y) * ts; val dy = (ty * scale + y) * ow
                    val srcRowAlpha = ((ty * scale + y) / scale) * w
                    for (x in 0 until cw * scale) {
                        val ri = (ry + pad * scale + x) * 3
                        val a = px[srcRowAlpha + (tx * scale + x) / scale] ushr 24
                        out[dy + tx * scale + x] = (a shl 24) or (to8(res[ri]) shl 16) or (to8(res[ri + 1]) shl 8) or to8(res[ri + 2])
                    }
                }
                tx += core
            }
            ty += core
        }
        return out
    }

    private fun to8(v: Float): Int { val q = (v * 255f + 0.5f).toInt(); return if (q < 0) 0 else if (q > 255) 255 else q }
}

class SrTimeout : RuntimeException("super-resolução excedeu o tempo")
