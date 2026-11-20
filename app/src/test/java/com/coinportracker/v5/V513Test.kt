package com.coinportracker.v5

import androidx.test.core.app.ApplicationProvider
import com.coinportracker.media.BannerLibrary
import com.coinportracker.media.MediaHd
import com.coinportracker.settings.AppSettings
import com.coinportracker.settings.BannerRoll
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.settings.RollRules
import com.coinportracker.settings.SettingsRepository
import com.coinportracker.settings.bannerSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v5.1.3 — fonte única do banner (seleção) e "Melhorar resolução" na Rolagem (ligar/desligar volta ao original). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class V513Test {
    private fun item(i: Int, k: RollKind) = RollItem("${k.name.lowercase()}$i", k, "${k.name.lowercase()}$i" + BannerLibrary.ext(k), 1000L * i, i.toLong())
    private fun fill(k: RollKind, n: Int, start: BannerRoll = BannerRoll(enabled = true)) = (1..n).fold(start) { r, i -> RollRules.add(r, item(i, k)) }
    private val lib = "/lib"; private val def = "asset:banner/default.gif"

    @Test fun selected_roll_item_is_the_single_banner_source() {
        val roll = fill(RollKind.GIF, 3).copy(selected = "gif2")
        val s = AppSettings(bannerUri = "/data/old/banner?v=1", roll = roll)
        // Início, prévia do Banner e HD usam este mesmo caminho: a mídia escolhida, não o banner antigo
        assertEquals("/lib/gif2.gif", bannerSource(s, lib, def))
        // rolagem desligada: volta ao banner único
        assertEquals("/data/old/banner?v=1", bannerSource(s.copy(roll = roll.copy(enabled = false)), lib, def))
        assertEquals(def, bannerSource(AppSettings(), lib, def))
        // escolhida removida: a 1ª da sequência
        assertEquals("/lib/gif1.gif", bannerSource(s.copy(roll = RollRules.remove(roll, "gif2")), lib, def))
    }

    @Test fun selection_persists_across_restart() {
        val roll = fill(RollKind.PHOTO, 4).copy(selected = "photo3")
        val back = SettingsRepository.fromMap(SettingsRepository.toMap(AppSettings(roll = roll)))
        assertEquals("photo3", back.roll.current?.id)
        assertEquals("/lib/photo3.img", bannerSource(back, lib, def))
    }

    @Test fun gallery_add_with_roll_active_selects_new_item_and_shows_its_kind() {
        var r = fill(RollKind.GIF, 2).copy(photosOn = false)
        r = RollRules.add(r, item(9, RollKind.PHOTO))
        r = RollRules.selectAdded(r, "photo9", select = true)
        assertEquals("photo9", r.current?.id); assertTrue(r.photosOn)
        assertSame(r, RollRules.selectAdded(r, "gif1", select = false))
    }

    @Test fun upscale_on_uses_hd_copy_and_off_deletes_it_and_reverts_to_original() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val orig = File(BannerLibrary.dir(ctx), "p_test.img").apply { writeBytes(ByteArray(64) { it.toByte() }); setLastModified(System.currentTimeMillis() - 60_000) }
        val src = orig.absolutePath
        val hd = MediaHd.hdFile(ctx, src).apply { writeBytes(ByteArray(128)) }
        // ligado e pronto: a tela usa a cópia HD
        assertTrue(MediaHd.resolve(ctx, src, true).startsWith(hd.absolutePath))
        // desligado: apaga as cópias HD e volta ao original, que fica intacto
        MediaHd.disableAll(ctx)
        assertFalse(hd.exists())
        assertEquals(src, MediaHd.resolve(ctx, src, true))
        assertEquals(src, MediaHd.resolve(ctx, src, false))
        assertTrue(orig.isFile); assertEquals(64L, orig.length())
    }

    @Test fun removing_roll_media_forgets_its_hd_copy() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val src = File(BannerLibrary.dir(ctx), "g_rm.gif").apply { writeBytes(ByteArray(8)) }.absolutePath
        val hd = MediaHd.hdFile(ctx, src).apply { writeBytes(ByteArray(8)) }
        MediaHd.forget(ctx, src)
        assertFalse(hd.exists())
    }
}
