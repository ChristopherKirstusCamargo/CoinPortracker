package com.coinportracker.media

import java.io.File
import java.io.InputStream

/**
 * Regras do vídeo de fundo: no máximo 60 s, transcode para ~720p sem áudio,
 * teto de tamanho no armazenamento privado. Sem upscale de IA.
 */
object VideoFormat {
    const val MAX_DURATION_MS = 60_000L
    const val MIN_DURATION_MS = 500L
    const val TARGET_HEIGHT = 720
    const val TARGET_BITRATE = 1_800_000
    const val LOW_BITRATE = 1_000_000
    const val MIN_BITRATE = 600_000
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
    /** v5.1.0 Rolagem: até 1080p, começa em 8 Mb/s (≈ 60 MB por minuto) e desce se passar do teto. */
    val ROLL_BITRATES = intArrayOf(8_000_000, 5_000_000, 3_000_000)

    data class TrimWindow(val startMs: Long, val endMs: Long, val durationMs: Long) {
        val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    }

    /**
     * Janela de corte válida: 0 ≤ start < end ≤ duration, comprimento entre
     * [MIN_DURATION_MS] e min(60 s, duração). Se a janela passar de 60 s, o
     * outro extremo acompanha (arrastar o fim empurra o início, e vice-versa).
     */
    fun clampTrim(startMs: Long, endMs: Long, durationMs: Long, maxMs: Long = MAX_DURATION_MS, minMs: Long = MIN_DURATION_MS, movedEnd: Boolean? = null): TrimWindow {
        val dur = durationMs.coerceAtLeast(0)
        if (dur <= 0) return TrimWindow(0, 0, 0)
        val maxLen = minOf(maxMs, dur)
        val minLen = minOf(minMs, maxLen)
        var s = startMs
        var e = endMs
        if (e < s) if (movedEnd == true) e = s else s = e
        if (e - s > maxLen) {
            if (movedEnd == false) e = s + maxLen else s = e - maxLen
        }
        if (e - s < minLen) {
            if (movedEnd == false) e = (s + minLen).coerceAtMost(dur).also { if (it - s < minLen) s = (it - minLen).coerceAtLeast(0) }
            else s = (e - minLen).coerceAtLeast(0).also { if (e - it < minLen) e = (it + minLen).coerceAtMost(dur) }
        }
        s = s.coerceIn(0, (dur - minLen).coerceAtLeast(0))
        e = e.coerceIn((s + minLen).coerceAtMost(dur), dur)
        if (e - s > maxLen) {
            if (movedEnd == false) e = s + maxLen else s = (e - maxLen).coerceAtLeast(0)
        }
        return TrimWindow(s, e, dur)
    }

    /** Janela inicial: do começo, até 60 s ou o fim do vídeo. */
    fun initialTrim(durationMs: Long): TrimWindow = clampTrim(0, minOf(MAX_DURATION_MS, durationMs), durationMs)

    fun isVideoPath(path: String): Boolean {
        val p = path.substringBefore("?v=").lowercase()
        if (p.startsWith("asset:")) return false
        return VIDEO_EXT.any { p.endsWith(it) } || isVideoFile(File(p))
    }

    fun isVideoFile(f: File): Boolean {
        if (!f.isFile || f.length() < 12) return VIDEO_EXT.any { f.name.lowercase().endsWith(it) } && f.isFile
        return f.inputStream().use { isVideoStream(it) } || VIDEO_EXT.any { f.name.lowercase().endsWith(it) }
    }

    fun isVideoStream(input: InputStream): Boolean {
        val b = ByteArray(16)
        val n = input.read(b)
        if (n < 12) return false
        // ISO BMFF (mp4, mov, 3gp): bytes 4..7 = 'ftyp'
        if (b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()) return true
        // WebM / Matroska: 0x1A45DFA3
        if (b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()) return true
        // AVI: 'RIFF' .... 'AVI '
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
            && n >= 12 && b[8] == 'A'.code.toByte() && b[9] == 'V'.code.toByte() && b[10] == 'I'.code.toByte()) return true
        return false
    }

    fun isVideoMime(mime: String?): Boolean = mime?.startsWith("video/") == true

    fun fmtTime(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val m = t / 60
        val s = t % 60
        return "%d:%02d".format(m, s)
    }

    private val VIDEO_EXT = listOf(".mp4", ".webm", ".mkv", ".mov", ".3gp", ".m4v", ".avi")
}
