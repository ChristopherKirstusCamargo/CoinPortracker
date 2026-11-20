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

/** v5.1.1 — limites 200/200/2, mídia escolhida pelo seletor e tipos da pergunta. */
class V511Test {
    private fun item(i: Int, k: RollKind) = RollItem("${k.name.lowercase()}$i", k, "${k.name.lowercase()}$i" + BannerLibrary.ext(k), 1000L * i, i.toLong())
    private fun fill(k: RollKind, n: Int, start: BannerRoll = BannerRoll()) = (1..n).fold(start) { r, i -> RollRules.add(r, item(i, k)) }

    @Test fun limits_are_200_200_2() {
        assertEquals(200, RollRules.MAX_GIFS); assertEquals(200, RollRules.MAX_PHOTOS); assertEquals(Int.MAX_VALUE, RollRules.MAX_VIDEOS)
        val r = fill(RollKind.GIF, 199)
        assertNull(RollRules.canAdd(r, RollKind.GIF))
        val full = RollRules.add(r, item(200, RollKind.GIF))
        assertEquals(200, full.count(RollKind.GIF))
        assertSame(full, RollRules.add(full, item(201, RollKind.GIF)))
        assertNull(RollRules.canAdd(full, RollKind.PHOTO)) // limite é por tipo
    }

    @Test fun decode_caps_tampered_lists_at_200() {
        val many = (1..260).joinToString("\n") { "p$it|PHOTO|p$it.img|1|1" }
        assertEquals(200, RollRules.decode(many).size)
    }

    @Test fun current_is_selected_or_first_and_survives_removal() {
        var r = fill(RollKind.GIF, 2, BannerRoll(enabled = true)); r = fill(RollKind.PHOTO, 2, r)
        assertEquals("gif1", r.current?.id)
        r = r.copy(selected = "photo2")
        assertEquals("photo2", r.current?.id)
        assertEquals("gif1", RollRules.remove(r, "photo2").current?.id) // apagou a escolhida: volta para a 1ª
        assertEquals("gif1", r.copy(photosOn = false).current?.id) // tipo desligado: idem
    }

    @Test fun pickable_kinds_only_with_media_and_on() {
        var r = fill(RollKind.PHOTO, 1, BannerRoll(enabled = true))
        assertEquals(listOf(RollKind.PHOTO), r.pickableKinds) // um tipo só: o seletor pula a pergunta
        r = fill(RollKind.VIDEO, 1, r); r = fill(RollKind.GIF, 1, r)
        assertEquals(listOf(RollKind.GIF, RollKind.PHOTO), r.pickableKinds) // v5.1.5: vídeo nunca aparece no seletor
        assertEquals(listOf(RollKind.GIF, RollKind.PHOTO), r.copy(videosOn = false).pickableKinds)
    }

    @Test fun selection_roundtrips_and_is_not_in_backup() {
        val roll = fill(RollKind.GIF, 3, BannerRoll(enabled = true, migrated = true)).copy(selected = "gif2")
        val back = SettingsRepository.fromMap(SettingsRepository.toMap(AppSettings(roll = roll)))
        assertEquals(roll, back.roll)
        // 5.1.0 sem a chave nova: abre igual, com a 1ª mídia
        val old = SettingsRepository.toMap(AppSettings(roll = roll)) - "bnRollSel"
        val mig = SettingsRepository.fromMap(old)
        assertEquals(roll.items, mig.roll.items); assertEquals("gif1", mig.roll.current?.id)
    }

    @Test fun order_still_user_chosen_for_gifs_and_photos() {
        var r = fill(RollKind.PHOTO, 3)
        r = RollRules.move(r, "photo3", -2)
        assertEquals(listOf("photo3", "photo1", "photo2"), r.of(RollKind.PHOTO).map { it.id })
        assertEquals(listOf("photo3", "photo1", "photo2"), r.copy(enabled = true).carousel.map { it.id })
    }
}
