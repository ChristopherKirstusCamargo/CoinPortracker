package com.coinportracker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class VideoFormatTest {
    @Test fun initialTrimCapsAt60s() {
        val t = VideoFormat.initialTrim(180_000)
        assertEquals(0, t.startMs)
        assertEquals(60_000, t.endMs)
        assertEquals(60_000, t.lengthMs)
        assertEquals(180_000, t.durationMs)
    }

    @Test fun shortVideoUsesFullLength() {
        val t = VideoFormat.initialTrim(12_500)
        assertEquals(0, t.startMs)
        assertEquals(12_500, t.endMs)
    }

    @Test fun draggingEndPushesStartToKeep60s() {
        val t = VideoFormat.clampTrim(0, 90_000, 180_000, movedEnd = true)
        assertEquals(30_000, t.startMs)
        assertEquals(90_000, t.endMs)
        assertEquals(60_000, t.lengthMs)
    }

    @Test fun draggingStartPushesEndToKeep60s() {
        // janela de 80 s (10–90): ao mover o início, o fim acompanha para não passar de 60 s
        val t = VideoFormat.clampTrim(10_000, 90_000, 180_000, movedEnd = false)
        assertEquals(10_000, t.startMs)
        assertEquals(70_000, t.endMs)
        assertEquals(60_000, t.lengthMs)
    }

    @Test fun shorterThan60sIsAllowed() {
        val t = VideoFormat.clampTrim(50_000, 90_000, 180_000, movedEnd = false)
        assertEquals(50_000, t.startMs)
        assertEquals(90_000, t.endMs)
        assertEquals(40_000, t.lengthMs)
    }

    @Test fun cannotExceedDuration() {
        val t = VideoFormat.clampTrim(170_000, 200_000, 180_000, movedEnd = true)
        assertTrue(t.endMs <= 180_000)
        assertTrue(t.lengthMs <= 60_000)
        assertTrue(t.startMs >= 0)
    }

    @Test fun minLength() {
        val t = VideoFormat.clampTrim(1_000, 1_100, 10_000, movedEnd = true)
        assertTrue(t.lengthMs >= VideoFormat.MIN_DURATION_MS)
    }

    @Test fun zeroDuration() {
        val t = VideoFormat.clampTrim(0, 10, 0)
        assertEquals(0, t.lengthMs)
    }

    @Test fun fmtTime() {
        assertEquals("0:00", VideoFormat.fmtTime(0))
        assertEquals("0:59", VideoFormat.fmtTime(59_000))
        assertEquals("1:01", VideoFormat.fmtTime(61_000))
        assertEquals("2:00", VideoFormat.fmtTime(120_000))
    }

    @Test fun detectsMp4Ftyp() {
        val b = ByteArray(16)
        b[4] = 'f'.code.toByte(); b[5] = 't'.code.toByte(); b[6] = 'y'.code.toByte(); b[7] = 'p'.code.toByte()
        assertTrue(VideoFormat.isVideoStream(ByteArrayInputStream(b)))
    }

    @Test fun rejectsGifAndJpeg() {
        val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte()) + ByteArray(16)
        assertFalse(VideoFormat.isVideoStream(ByteArrayInputStream(gif)))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)
        assertFalse(VideoFormat.isVideoStream(ByteArrayInputStream(jpeg)))
    }

    @Test fun detectsByExtensionAndMime() {
        assertTrue(VideoFormat.isVideoPath("/data/user/0/app/files/video/banner.mp4"))
        assertTrue(VideoFormat.isVideoPath("/tmp/clip.webm?v=1"))
        assertFalse(VideoFormat.isVideoPath("asset:banner/default.gif"))
        assertFalse(VideoFormat.isVideoPath("/data/user/0/app/files/banner"))
        assertTrue(VideoFormat.isVideoMime("video/mp4"))
        assertFalse(VideoFormat.isVideoMime("image/gif"))
        assertFalse(VideoFormat.isVideoMime(null))
    }

    @Test fun sizeCapsAreModest() {
        assertEquals(20L * 1024 * 1024, VideoFormat.MAX_FILE_BYTES)
        assertEquals(40L * 1024 * 1024, VideoFormat.MAX_TOTAL_BYTES)
        assertEquals(720, VideoFormat.TARGET_HEIGHT)
        assertTrue(VideoFormat.TARGET_BITRATE <= 2_000_000)
        // 60 s a 1,8 Mbps cabe no teto de 20 MB com folga
        val bytes60 = VideoFormat.TARGET_BITRATE / 8.0 * 60
        assertTrue(bytes60 < VideoFormat.MAX_FILE_BYTES)
    }

    @Test fun missingFileWithMp4NameIsStillVideoPath() {
        val f = File("/tmp/does-not-exist-coinportracker.mp4")
        assertFalse(f.exists())
        assertTrue(VideoFormat.isVideoPath(f.absolutePath))
    }
}
