package com.coinportracker.ui.components

import android.graphics.Movie
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.coinportracker.media.BannerLibrary
import com.coinportracker.settings.BannerStyle
import com.coinportracker.settings.RollItem
import com.coinportracker.settings.RollKind
import com.coinportracker.ui.theme.LocalLowPower
import com.coinportracker.ui.theme.LocalReducedMotion
import com.coinportracker.ui.theme.Motion
import kotlinx.coroutines.withContext

/**
 * v5.1.1 — banner da Rolagem na Início: mostra a mídia escolhida no seletor (toque no banner),
 * no arquivo original (GIF animado inteiro, foto até 1080×1920 sem nunca ficar abaixo da tela, vídeo
 * no ExoPlayer). A troca é um fade com leve zoom; com "Sem animação" vira corte. Baixo consumo: estático.
 */
@Composable
fun RollBanner(item: RollItem?, style: BannerStyle, background: Color, modifier: Modifier, upscale: Boolean = false) {
    if (item == null || !style.enabled) return
    val still = LocalReducedMotion.current || LocalLowPower.current
    AnimatedContent(
        targetState = item, modifier = modifier, contentKey = { it.id }, label = "rollBanner",
        transitionSpec = {
            if (still) (fadeIn(snap()) togetherWith fadeOut(snap()))
            else (fadeIn(tween(Motion.d(420), easing = Motion.EmphasizedDecel)) + scaleIn(tween(Motion.d(520), easing = Motion.Emphasized), initialScale = 1.04f)) togetherWith
                fadeOut(tween(Motion.d(320), delayMillis = Motion.d(80), easing = Motion.Standard))
        },
    ) { it ->
        RollPage(it, style, background, active = it.id == item.id, upscale = upscale)
    }
}

@Composable
private fun RollPage(item: RollItem, style: BannerStyle, background: Color, active: Boolean, upscale: Boolean) {
    val low = LocalLowPower.current
    val media = rememberRollMedia(item, upscale, style.speed)
    Box(Modifier.fillMaxSize()) {
        if (media != null) {
            // opacidade aplicada uma vez só (quadro parado + vídeo por cima não somam)
            val inner = remember(style) { style.copy(opacity = 1f, fade = false) }
            Box(Modifier.matchParentSize().graphicsLayer { alpha = style.opacity.coerceIn(0f, 1f) }) {
                BannerBackdrop(BannerMedia(media.movie, media.still, anim = media.anim), inner, background, Modifier.matchParentSize(), animate = active)
                if (item.kind == RollKind.VIDEO && active && !low && media.videoPath != null)
                    VideoBackdrop(media.videoPath, inner, background, Modifier.matchParentSize())
            }
            if (style.fade) BannerFade(background, Modifier.matchParentSize())
        }
    }
}

/** Decodifica fora da thread principal. GIF: o arquivo inteiro (qualidade original). Foto: até 1080×1920 (nunca abaixo disso). */
@Composable
private fun rememberRollMedia(item: RollItem, upscale: Boolean, speed: Float = 1f): BannerMedia? {
    val needMovie = android.os.Build.VERSION.SDK_INT < 28 || (speed != 1f && speed != 0f)
    val ctx = LocalContext.current
    // v5.1.3: "Melhorar resolução" vale para a Rolagem: usa a cópia HD quando pronta (o original fica intacto)
    val orig = remember(item.id) { BannerLibrary.file(ctx, item).absolutePath }
    val src = if (item.kind == RollKind.VIDEO) orig else rememberHdSource(orig, upscale)
    var last by remember(item.id) { mutableStateOf<BannerMedia?>(null) }
    val m by produceState(last, item.id, src, needMovie) {
        value = withContext(BannerLibrary.decodeDispatcher) {
            runCatching {
                val f = java.io.File(src.substringBefore("?v=")).takeIf { it.isFile } ?: BannerLibrary.file(ctx, item)
                if (!f.isFile) return@runCatching null
                when (item.kind) {
                    RollKind.VIDEO -> BannerMedia(null, BannerLibrary.videoFrame(f.absolutePath, 1080)?.asImageBitmap(), f.absolutePath)
                    RollKind.GIF -> {
                        // v6.0: Android 9+ com 1×: drawable animado (sem o arquivo inteiro na memória, sem decodificar na thread principal)
                        if (!needMovie) BannerGif.animated(ctx, f.absolutePath)?.let { return@runCatching BannerMedia(null, null, anim = it) }
                        val bytes = f.readBytes()
                        @Suppress("DEPRECATION")
                        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width() > 0 && it.duration() > 0 }
                        if (movie != null) BannerMedia(movie, null) else BannerLibrary.decodeBounded(f.absolutePath, 1080, 1920)?.let { BannerMedia(null, it.asImageBitmap()) }
                    }
                    RollKind.PHOTO -> BannerLibrary.decodeBounded(f.absolutePath, 1080, 1920)?.let { BannerMedia(null, it.asImageBitmap()) }
                }
            }.getOrNull()
        } ?: value
        last = value
    }
    return m
}
