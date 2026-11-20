package com.coinportracker.v5

import com.coinportracker.media.BannerLibrary
import com.coinportracker.settings.AppSettings
import com.coinportracker.settings.BannerRoll
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.settings.RollRules
import com.coinportracker.settings.SettingsRepository
import org.junit.Assert.*
import org.junit.Test

/** v5.1.0 — Rolagem de banner: limites, ordem, codificação e migração. */
class V510Test {
    private fun item(i: Int, k: RollKind) = RollItem("${k.name.lowercase()}$i", k, "${k.name.lowercase()}$i" + BannerLibrary.ext(k), 1000L * i, i.toLong())
    private fun fill(k: RollKind, n: Int, start: BannerRoll = BannerRoll()) = (1..n).fold(start) { r, i -> RollRules.add(r, item(i, k)) }

    // 5.1.1: limites subiram para 200/200/2 (ver V511Test)
    @Test fun limits_gifs_photos_videos() {
        var r = fill(RollKind.GIF, 205); r = fill(RollKind.PHOTO, 205, r); r = fill(RollKind.VIDEO, 5, r)
        assertEquals(200, r.count(RollKind.GIF)); assertEquals(200, r.count(RollKind.PHOTO)); assertEquals(5, r.count(RollKind.VIDEO)) // 5.1.4: vídeos sem limite
        assertEquals("Limite de 200 GIFs atingido.", RollRules.canAdd(r, RollKind.GIF))
        assertEquals("Limite de 200 fotos atingido.", RollRules.canAdd(r, RollKind.PHOTO))
        assertNull(RollRules.canAdd(r, RollKind.VIDEO))
        assertEquals(405, r.items.size)
    }

    @Test fun pending_video_counts_toward_limit() {
        val r = fill(RollKind.VIDEO, 1)
        assertNull(RollRules.canAdd(r, RollKind.VIDEO))
        assertNull(RollRules.canAdd(r, RollKind.VIDEO, pending = 50)) // 5.1.4: sem limite de quantidade
        assertEquals(60_000L, RollRules.MAX_VIDEO_MS)
    }

    @Test fun rejects_duplicate_ids_and_unsafe_file_names() {
        val r = RollRules.add(BannerRoll(), item(1, RollKind.GIF))
        assertSame(r, RollRules.add(r, item(1, RollKind.GIF)))
        assertSame(r, RollRules.add(r, RollItem("x", RollKind.PHOTO, "../secret")))
        assertSame(r, RollRules.add(r, RollItem("y", RollKind.PHOTO, "/data/x.img")))
        assertFalse(RollRules.validFile(".hidden")); assertTrue(RollRules.validFile("p_abc_1234.img"))
    }

    // v5.1.5: vídeos saíram da Rolagem (os já salvos ficam fora da sequência)
    @Test fun carousel_order_is_gifs_then_photos_without_videos() {
        var r = BannerRoll(enabled = true)
        r = RollRules.add(r, item(1, RollKind.VIDEO)); r = RollRules.add(r, item(1, RollKind.PHOTO)); r = RollRules.add(r, item(1, RollKind.GIF))
        r = RollRules.add(r, item(2, RollKind.PHOTO)); r = RollRules.add(r, item(2, RollKind.GIF))
        assertEquals(listOf("gif1", "gif2", "photo1", "photo2"), r.carousel.map { it.id })
        assertEquals(listOf("gif1", "gif2"), r.copy(photosOn = false).carousel.map { it.id })
        assertFalse(r.copy(gifsOn = false, photosOn = false, videosOn = false).active)
        assertFalse(r.copy(enabled = false).active)
        assertTrue(r.active)
    }

    @Test fun move_reorders_within_kind_only() {
        var r = fill(RollKind.GIF, 3); r = fill(RollKind.PHOTO, 2, r)
        r = RollRules.move(r, "gif3", -2)
        assertEquals(listOf("gif3", "gif1", "gif2"), r.of(RollKind.GIF).map { it.id })
        r = RollRules.move(r, "gif3", -5) // já é o primeiro
        assertEquals("gif3", r.of(RollKind.GIF).first().id)
        r = RollRules.move(r, "photo1", 1)
        assertEquals(listOf("photo2", "photo1"), r.of(RollKind.PHOTO).map { it.id })
        assertEquals(listOf("gif3", "gif1", "gif2"), r.of(RollKind.GIF).map { it.id })
        val v = fill(RollKind.VIDEO, 2)
        assertEquals(v, RollRules.move(v, "video2", -1)) // vídeos não têm ordem
    }

    @Test fun remove_keeps_order() {
        val r = RollRules.remove(fill(RollKind.PHOTO, 3), "photo2")
        assertEquals(listOf("photo1", "photo3"), r.items.map { it.id })
    }

    @Test fun encode_decode_roundtrip_and_tolerant() {
        var r = fill(RollKind.GIF, 2); r = fill(RollKind.VIDEO, 1, r)
        assertEquals(r.items, RollRules.decode(RollRules.encode(r.items)))
        val junk = RollRules.encode(r.items) + "\nbad\nz|NOPE|f.img|1|1\ngif1|GIF|dup.gif|1|1\nq|PHOTO|../x|1|1"
        assertEquals(r.items, RollRules.decode(junk))
        // nunca passa dos limites mesmo com dados adulterados
        val many = (1..30).joinToString("\n") { "v$it|VIDEO|v$it.mp4|1|1" }
        assertEquals(30, RollRules.decode(many).size) // 5.1.4: vídeos sem limite
        assertTrue(RollRules.decode(null).isEmpty())
    }

    @Test fun settings_roundtrip_and_backup_excludes_media_list() {
        val roll = fill(RollKind.PHOTO, 2, BannerRoll(enabled = true, gifsOn = false, migrated = true))
        val s = AppSettings(roll = roll)
        val back = SettingsRepository.fromMap(SettingsRepository.toMap(s))
        assertEquals(roll, back.roll)
        // configurações antigas (5.0.1) sem as chaves novas: rolagem desligada, banner único igual
        val old = SettingsRepository.toMap(AppSettings(bannerUri = "/data/x/banner")).filterKeys { !it.startsWith("bnRoll") }
        val mig = SettingsRepository.fromMap(old)
        assertFalse(mig.roll.enabled); assertFalse(mig.roll.migrated); assertTrue(mig.roll.items.isEmpty())
        assertEquals("/data/x/banner", mig.bannerUri)
    }

    @Test fun migration_puts_old_banner_first_of_its_kind() {
        var r = fill(RollKind.PHOTO, 2)
        r = RollRules.addFirst(r, RollItem("legacy", RollKind.PHOTO, "legacy.img"))
        assertEquals("legacy", r.of(RollKind.PHOTO).first().id)
        assertSame(r, RollRules.addFirst(r, RollItem("legacy", RollKind.PHOTO, "legacy.img"))) // idempotente
        val full = fill(RollKind.GIF, RollRules.MAX_GIFS)
        assertSame(full, RollRules.addFirst(full, RollItem("legacy", RollKind.GIF, "legacy.gif")))
    }

    @Test fun detect_kind_by_content() {
        val gif = "GIF89a".toByteArray() + ByteArray(10)
        assertEquals(RollKind.GIF, BannerLibrary.detect(gif, gif.size))
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(4)
        assertEquals(RollKind.VIDEO, BannerLibrary.detect(mp4, mp4.size))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(12)
        assertEquals(RollKind.PHOTO, BannerLibrary.detect(png, png.size))
        assertNull(BannerLibrary.detect(ByteArray(16), 0))
    }

    @Test fun video_target_keeps_highest_resolution_up_to_1080p() {
        assertNull(BannerLibrary.videoTarget(1920, 1080)) // já é 1080p: mantém
        assertNull(BannerLibrary.videoTarget(1280, 720)) // não amplia
        assertEquals(1920 to 1080, BannerLibrary.videoTarget(3840, 2160))
        assertEquals(1080 to 1920, BannerLibrary.videoTarget(2160, 3840)) // vertical
        val t = BannerLibrary.videoTarget(3000, 1500)!!
        assertEquals(1080, t.second); assertEquals(0, t.first % 2)
    }

    @Test fun summary_texts() {
        assertEquals("Nenhuma mídia", RollRules.summary(BannerRoll()))
        var r = fill(RollKind.GIF, 3, BannerRoll(enabled = true)); r = fill(RollKind.VIDEO, 1, r)
        assertEquals("3 GIFs · 1 vídeo", RollRules.summary(r))
        assertEquals("Desligada · 3 GIFs · 1 vídeo", RollRules.summary(r.copy(enabled = false)))
    }
}
