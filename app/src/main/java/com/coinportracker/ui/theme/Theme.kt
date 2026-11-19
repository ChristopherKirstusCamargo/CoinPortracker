package com.coinportracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coinportracker.R
import com.coinportracker.settings.ThemeMode

/**
 * Identidade v2: preto e branco, minimalista e escuro (marca C com seta); cor só em ganhos/perdas (verde/vermelho, estilo CoinStats),
 * cartões planos de cantos generosos e bordas finíssimas (Trust/Uniswap). Nomes antigos mantidos para as telas legadas.
 */
object Palette {
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF0B0B0B)
    val Navy900 = Color(0xFF0B0B0B)
    val Navy800 = Color(0xFF151515)
    val Navy700 = Color(0xFF1E1E1E)
    val Navy600 = Color(0xFF292929)
    val Graphite = Color(0xFF2F2F2F)
    val Electric = Color(0xFFF2F2F2)
    val ElectricSoft = Color(0xFFD9D9D9)
    val Violet = Color(0xFFBDBDBD)
    val VioletDeep = Color(0xFF262626)
    val TextHi = Color(0xFFF5F5F7)
    val TextMid = Color(0xFFA3A3A3)
    val TextLow = Color(0xFF6E6E6E)
    val Gain = Color(0xFF34D399)
    val Loss = Color(0xFFFF5C70)
    val Warn = Color(0xFFFFB547)
    val chart = listOf(Color(0xFFF2F2F2), Color(0xFFBDBDBD), Color(0xFF8C8C8C), Color(0xFF666666), Color(0xFF4A4A4A), Color(0xFFD9D9D9),
        Color(0xFFA6A6A6), Color(0xFF7A7A7A), Color(0xFF575757), Color(0xFF3A3A3A))
}

@Immutable
data class ExtraColors(
    val gain: Color, val loss: Color, val warn: Color, val cardBorder: Color, val cardTop: Color, val cardBottom: Color,
    val accentGlow: Color, val subtle: Color, val card: Color = cardTop, val cardHigh: Color = cardTop, val onAccent: Color = Color.Black,
    val isDark: Boolean = true, val shimmer: Color = Color(0x14FFFFFF), val divider: Color = Color(0x0FFFFFFF),
)

val LocalExtra = staticCompositionLocalOf {
    ExtraColors(Palette.Gain, Palette.Loss, Palette.Warn, Color(0x12FFFFFF), Palette.Navy800, Palette.Navy800, Palette.Electric, Palette.TextLow)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val InterDisplay = FontFamily(Font(R.font.inter_display_semibold, FontWeight.SemiBold))

/** Números tabulares (alinhados) para valores que mudam ao vivo. */
const val TNUM = "tnum"

private fun dark(bg: Color, card: Color, high: Color, outline: Color): ColorScheme = darkColorScheme(
    primary = Palette.Electric, onPrimary = Color(0xFF0B0B0B), primaryContainer = Palette.VioletDeep, onPrimaryContainer = Palette.TextHi,
    secondary = Palette.Violet, onSecondary = Color.Black, secondaryContainer = Color(0xFF262626), onSecondaryContainer = Palette.TextHi,
    tertiary = Palette.TextMid, background = bg, onBackground = Palette.TextHi, surface = bg, onSurface = Palette.TextHi,
    surfaceVariant = high, onSurfaceVariant = Palette.TextMid, surfaceContainer = card, surfaceContainerHigh = high,
    surfaceContainerHighest = Palette.Navy600, surfaceContainerLow = card, surfaceContainerLowest = bg,
    outline = outline, outlineVariant = Color(0x14FFFFFF), error = Palette.Loss, onError = Color.White, inverseSurface = Palette.TextHi, inverseOnSurface = bg,
)

private val graphite = dark(Palette.Navy900, Palette.Navy800, Palette.Navy700, Palette.Graphite)
private val amoled = dark(Palette.Black, Color(0xFF0E0E0E), Color(0xFF181818), Color(0xFF262626))

private val light: ColorScheme = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White, primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF444444), tertiary = Color(0xFF666666), background = Color(0xFFF5F5F5), onBackground = Color(0xFF111111),
    surface = Color(0xFFF5F5F5), onSurface = Color(0xFF111111), surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEEEEEE), surfaceContainerHighest = Color(0xFFE3E3E3), surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF616161), outline = Color(0xFFD6D6D6), outlineVariant = Color(0xFFE8E8E8), error = Color(0xFFE5484D),
)

private fun type(): Typography {
    fun t(size: Float, w: FontWeight = FontWeight.Normal, ls: Float = 0f, lh: Float = size * 1.35f, fam: FontFamily = Inter) =
        TextStyle(fontFamily = fam, fontWeight = w, fontSize = size.sp, letterSpacing = ls.sp, lineHeight = lh.sp, fontFeatureSettings = TNUM)
    return Typography(
        displayLarge = t(44f, FontWeight.SemiBold, -1.4f, 50f, InterDisplay),
        displayMedium = t(38f, FontWeight.SemiBold, -1.2f, 44f, InterDisplay),
        displaySmall = t(32f, FontWeight.SemiBold, -0.9f, 38f, InterDisplay),
        headlineSmall = t(22f, FontWeight.Bold, -0.4f, 28f),
        titleLarge = t(19f, FontWeight.Bold, -0.3f, 24f),
        titleMedium = t(15.5f, FontWeight.SemiBold, -0.1f, 21f),
        titleSmall = t(14f, FontWeight.SemiBold, 0f, 19f),
        bodyLarge = t(15f, FontWeight.Medium, lh = 21f),
        bodyMedium = t(13.5f, FontWeight.Medium, lh = 19f),
        bodySmall = t(12f, lh = 16f),
        labelLarge = t(13.5f, FontWeight.SemiBold, 0f),
        labelMedium = t(12f, FontWeight.SemiBold, 0.1f),
        labelSmall = t(10.5f, FontWeight.SemiBold, 0.2f),
    )
}

private val typography = type()

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CoinPortrackerTheme(mode: ThemeMode, reduceMotion: Boolean = false, lowPower: Boolean = false, anim: com.coinportracker.settings.AnimPrefs = com.coinportracker.settings.AnimPrefs(), content: @Composable () -> Unit) {
    Motion.prefs = anim
    val scheme = when (mode) { ThemeMode.AMOLED -> amoled; ThemeMode.NAVY -> graphite; ThemeMode.LIGHT -> light }
    val extra = when (mode) {
        ThemeMode.LIGHT -> ExtraColors(Color(0xFF0E9F6E), Color(0xFFE5484D), Color(0xFFD97706), Color(0x0F000000), Color.White, Color.White,
            Color(0xFF111111), Color(0xFF8A8A8A), card = Color.White, cardHigh = Color(0xFFF0F0F0), onAccent = Color.White, isDark = false,
            shimmer = Color(0x0D000000), divider = Color(0x0A000000))
        ThemeMode.AMOLED -> ExtraColors(Palette.Gain, Palette.Loss, Palette.Warn, Color(0x10FFFFFF), Color(0xFF0E0E0E), Color(0xFF0E0E0E),
            Palette.Electric, Palette.TextLow, card = Color(0xFF0E0E0E), cardHigh = Color(0xFF181818))
        ThemeMode.NAVY -> ExtraColors(Palette.Gain, Palette.Loss, Palette.Warn, Color(0x10FFFFFF), Palette.Navy800, Palette.Navy800,
            Palette.Electric, Palette.TextLow, card = Palette.Navy800, cardHigh = Palette.Navy700)
    }
    CompositionLocalProvider(LocalExtra provides extra, LocalReducedMotion provides (reduceMotion || rememberSystemReducedMotion()), LocalLowPower provides lowPower, LocalAnim provides anim) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
