package com.coinportracker.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.coinportracker.settings.AnimPrefs
import com.coinportracker.settings.NumberAnim
import com.coinportracker.ui.anim.NumberMorph
import com.coinportracker.ui.theme.Motion

/** Encolhe proporcionalmente quando o número não cabe (mesma regra do rolo de dígitos). */
internal fun Modifier.shrinkToFit(): Modifier = this.layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val sc = if (c.hasBoundedWidth && p.width > c.maxWidth) c.maxWidth.toFloat() / p.width else 1f
    layout((p.width * sc).toInt(), (p.height * sc).toInt()) {
        p.placeWithLayer(0, 0) { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
    }
}

/**
 * Estilos de número além do rolo: contagem suave, desfoque, deslizar e sem animação. Algarismos tabulares
 * (largura estável); só este texto recompõe.
 */
@Composable
internal fun NumberText(text: String, style: TextStyle, color: Color, modifier: Modifier, p: AnimPrefs, replayKey: Any?) {
    val st = remember(style, color) { style.copy(fontFeatureSettings = "tnum, lnum", color = color) }
    val base = modifier.semantics { contentDescription = text }.shrinkToFit()
    when (p.number) {
        NumberAnim.NONE, NumberAnim.ROLL -> Text(text, style = st, maxLines = 1, softWrap = false, modifier = base)
        NumberAnim.COUNT -> CountText(text, st, base, p, replayKey)
        NumberAnim.FADE -> AnimatedContent(text, base, transitionSpec = {
            val ms = p.dur(p.numberMs)
            fadeIn(tween(ms, easing = Motion.numberEasing(p.curve))) togetherWith fadeOut(tween(ms * 2 / 3)) using SizeTransform(clip = false)
        }, contentAlignment = Alignment.CenterStart, label = "nfade") { t ->
            val blur by transition.animateFloat({ tween(p.dur(p.numberMs)) }, label = "b") { if (it == EnterExitState.Visible) 0f else 8f }
            Text(t, style = st, maxLines = 1, softWrap = false, modifier = if (Build.VERSION.SDK_INT >= 31 && blur > 0.2f) Modifier.blur(blur.dp) else Modifier)
        }
        NumberAnim.SLIDE -> {
            var prev by remember(replayKey) { mutableStateOf<Double?>(null) }
            val v = remember(text) { NumberMorph.parse(text)?.value }
            val up = v == null || prev == null || v >= prev!!
            SideEffect { if (v != null) prev = v }
            AnimatedContent(text, base, transitionSpec = {
                val spec = Motion.numberSpec<androidx.compose.ui.unit.IntOffset>(p)
                val dir = if (up) 1 else -1
                (slideInVertically(spec) { h -> h * dir / 2 } + fadeIn(tween(p.dur(p.numberMs) / 2))) togetherWith
                    (slideOutVertically(spec) { h -> -h * dir / 2 } + fadeOut(tween(p.dur(p.numberMs) / 3))) using SizeTransform(clip = false)
            }, contentAlignment = Alignment.CenterStart, label = "nslide") { t -> Text(t, style = st, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun CountText(text: String, st: TextStyle, modifier: Modifier, p: AnimPrefs, replayKey: Any?) {
    val target = remember(text) { NumberMorph.parse(text) }
    if (target == null) { Text(text, style = st, maxLines = 1, softWrap = false, modifier = modifier); return }
    val anim = remember(replayKey) { Animatable(target.value.toFloat()) }
    var from by remember(replayKey) { mutableDoubleStateOf(target.value) }
    var shownTarget by remember(replayKey) { mutableStateOf(target) }
    var progress by remember(replayKey) { mutableFloatStateOf(1f) }
    LaunchedEffect(target, replayKey) {
        if (target.value == shownTarget.value) { shownTarget = target; return@LaunchedEffect }
        // continua do valor exibido no momento (sem pulo se a contagem anterior foi interrompida)
        from = from + (shownTarget.value - from) * progress
        shownTarget = target
        anim.snapTo(0f); progress = 0f
        anim.animateTo(1f, Motion.numberSpec(p)) { progress = value }
        progress = 1f; from = target.value
    }
    val shown = if (progress >= 1f) text else NumberMorph.lerpText(from, shownTarget, progress)
    Text(shown, style = st, maxLines = 1, softWrap = false, modifier = modifier)
}
