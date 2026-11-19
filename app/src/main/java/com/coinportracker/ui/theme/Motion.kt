package com.coinportracker.ui.theme

import androidx.compose.animation.togetherWith

import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/**
 * Especificação única de movimento (inspirada no Material 3 "emphasized"):
 * todas as telas usam estas durações, curvas e molas. Com "Remover animações" ligado
 * no sistema, [LocalReducedMotion] = true e as animações viram cortes/fades instantâneos.
 */
object Motion {
    /** v5.0.1: preferências de animação vigentes (Ajustes → Animações); padrões = v5.0. */
    @Volatile var prefs: com.coinportracker.settings.AnimPrefs = com.coinportracker.settings.AnimPrefs()
    /** Duração com o multiplicador global. */
    fun d(ms: Int): Int = prefs.dur(ms)
    private fun sd(ms: Int): Int = prefs.dur(ms, prefs.screenSpeed)
    private fun sp(): Float = (prefs.speed).coerceIn(0.25f, 4f)
    const val SHORT = 150
    const val MEDIUM = 280
    const val LONG = 420
    const val STAGGER = 45
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecel: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccel: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> spatial() = spring<T>(dampingRatio = 0.86f, stiffness = 520f * sp() * sp())
    fun <T> press() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium * sp() * sp())
    val itemPlacement get() = spring(dampingRatio = 0.9f, stiffness = 480f * sp() * sp(), visibilityThreshold = IntOffset.VisibilityThreshold)
    fun <T> fade(ms: Int = MEDIUM) = tween<T>(d(ms), easing = Standard)
    /** Curva escolhida para os números. */
    fun numberEasing(c: com.coinportracker.settings.AnimCurve): Easing = when (c) {
        com.coinportracker.settings.AnimCurve.SMOOTH -> EmphasizedDecel
        com.coinportracker.settings.AnimCurve.LINEAR -> LinearEasing
        com.coinportracker.settings.AnimCurve.EXPRESSIVE -> Emphasized
        com.coinportracker.settings.AnimCurve.SPRING -> CubicBezierEasing(0.34f, 1.36f, 0.64f, 1f)
    }
    fun <T> numberSpec(p: com.coinportracker.settings.AnimPrefs = prefs): FiniteAnimationSpec<T> =
        if (p.curve == com.coinportracker.settings.AnimCurve.SPRING) spring(dampingRatio = 0.72f, stiffness = 380f * (560f / p.dur(p.numberMs)).let { it * it })
        else tween(p.dur(p.numberMs), easing = numberEasing(p.curve))
    val haptics: Boolean get() = prefs.haptics

    // Transições de navegação
    /** Abas: "fade through" (sai rápido, entra com leve zoom). */
    fun tabEnter(): EnterTransition = when (prefs.screen) {
        com.coinportracker.settings.ScreenAnim.NONE -> EnterTransition.None
        com.coinportracker.settings.ScreenAnim.FADE -> fadeIn(tween(sd(240), easing = Standard))
        else -> fadeIn(tween(sd(210), delayMillis = sd(70), easing = EmphasizedDecel)) + scaleIn(tween(sd(210), delayMillis = sd(70), easing = EmphasizedDecel), initialScale = 0.96f)
    }
    fun tabExit(): ExitTransition = if (prefs.screen == com.coinportracker.settings.ScreenAnim.NONE) ExitTransition.None else fadeOut(tween(sd(90), easing = EmphasizedAccel))
    /** Telas internas: eixo compartilhado X (desliza 30 dp + fade). */
    fun pushEnter(dir: Int = 1): EnterTransition = when (prefs.screen) {
        com.coinportracker.settings.ScreenAnim.NONE -> EnterTransition.None
        com.coinportracker.settings.ScreenAnim.FADE -> fadeIn(tween(sd(MEDIUM), easing = Standard))
        com.coinportracker.settings.ScreenAnim.SCALE -> scaleIn(tween(sd(MEDIUM + 60), easing = Emphasized), initialScale = if (dir > 0) 0.92f else 1.06f) + fadeIn(tween(sd(MEDIUM), sd(40), Standard))
        com.coinportracker.settings.ScreenAnim.SLIDE -> slideInHorizontally(tween(sd(MEDIUM + 60), easing = Emphasized)) { (it * 0.10f * dir).toInt() } + fadeIn(tween(sd(MEDIUM), sd(40), Standard))
    }
    /** Troca de conteúdo (números/rótulos): fade through rápido com leve deslize vertical. */
    fun fadeThrough(): androidx.compose.animation.ContentTransform =
        (fadeIn(tween(d(200), d(60), EmphasizedDecel)) + androidx.compose.animation.slideInVertically(tween(d(260), easing = Emphasized)) { it / 4 }) togetherWith
            fadeOut(tween(d(90), easing = EmphasizedAccel))
    fun pushExit(dir: Int = 1): ExitTransition = when (prefs.screen) {
        com.coinportracker.settings.ScreenAnim.NONE -> ExitTransition.None
        com.coinportracker.settings.ScreenAnim.FADE -> fadeOut(tween(sd(SHORT), easing = Standard))
        com.coinportracker.settings.ScreenAnim.SCALE -> scaleOut(tween(sd(MEDIUM + 60), easing = Emphasized), targetScale = if (dir > 0) 1.04f else 0.94f) + fadeOut(tween(sd(SHORT), easing = Standard))
        com.coinportracker.settings.ScreenAnim.SLIDE -> slideOutHorizontally(tween(sd(MEDIUM + 60), easing = Emphasized)) { (-it * 0.10f * dir).toInt() } + fadeOut(tween(sd(SHORT), easing = Standard))
    }
}

val LocalReducedMotion = staticCompositionLocalOf { false }

/** v5.0.1: preferências de animação (Ajustes → Animações). */
val LocalAnim = staticCompositionLocalOf { com.coinportracker.settings.AnimPrefs() }

/** Ligado só pelo interruptor em Ajustes (nunca automaticamente). */
val LocalLowPower = staticCompositionLocalOf { false }

/** v3.2: elementos compartilhados entre lista e detalhe (ícone do ativo "voa" da linha para o cabeçalho). */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalSharedScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalNavScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedKey(key: String): Modifier {
    val s = LocalSharedScope.current ?: return this
    val a = LocalNavScope.current ?: return this
    if (LocalReducedMotion.current) return this
    return with(s) { this@sharedKey.sharedElement(rememberSharedContentState(key), a, boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 420f) }) }
}

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * Entrada escalonada (cartões aparecem em sequência: sobe 16 dp + fade). Executa só na primeira
 * exibição de cada posição; só altera a camada gráfica (sem recomposição por quadro).
 */
@Composable
fun Modifier.staggeredEntrance(index: Int, enabled: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    if (!enabled || reduced || index > 10 || !LocalAnim.current.listAppear) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(Motion.d(index * Motion.STAGGER).toLong()); p.animateTo(1f, tween(Motion.d(Motion.LONG), easing = Motion.EmphasizedDecel)) }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 16f * density
    }
}

/**
 * Revelação ao rolar (v3): quando um cartão entra na tela ele surge com fade + sobe 12 dp + escala 0,97→1.
 * Discreto (380 ms, desacelerado, sem quique). Só camada gráfica; desligado com "Remover animações".
 */
@Composable
fun Modifier.reveal(delayMs: Int = 0): Modifier {
    if (LocalReducedMotion.current || !LocalAnim.current.listAppear) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { if (delayMs > 0) delay(delayMs.toLong()); p.animateTo(1f, tween(Motion.d(380), easing = Motion.EmphasizedDecel)) }
    return this.graphicsLayer {
        val v = p.value
        alpha = 0.001f + 0.999f * v
        translationY = (1f - v) * 12f * density
        val s = 0.97f + 0.03f * v
        scaleX = s; scaleY = s
    }
}

/** Paralaxe leve: desloca o conteúdo numa fração da rolagem (usado no GIF do topo). */
fun Modifier.parallax(offsetPx: () -> Float, factor: Float = 0.35f): Modifier = this.graphicsLayer { translationY = offsetPx() * factor }
