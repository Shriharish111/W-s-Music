package echopc

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

/** W's MUSIC design tokens: near-black surfaces, cool-grey text, one animated neon accent. */
object Ws {
    val bg0 = Color(0xFF06060B)
    val bg1 = Color(0xFF0B0B13)
    val surface = Color(0xFF11111C)
    val surfaceHi = Color(0xFF191926)
    val surfaceTop = Color(0xFF212133)
    val border = Color(0xFF26263A)
    val text = Color(0xFFEEEEF8)
    val muted = Color(0xFF9494B0)
    val faint = Color(0xFF5A5A75)
    val danger = Color(0xFFFF5470)

    val defaultAccent = Color(0xFF8B5CF6) // violet
    val defaultAccent2 = Color(0xFF22D3EE) // cyan
}

@Immutable
class Accent(val primary: Color, val secondary: Color) {
    /** Lighter tint of the primary for text and icons (the raw colour can be harsh on dark UIs). */
    val highlight: Color get() = androidx.compose.ui.graphics.lerp(primary, Color.White, 0.28f)
}

val LocalAccent = compositionLocalOf { Accent(Ws.defaultAccent, Ws.defaultAccent2) }

/** Bahnschrift ships with Windows 10/11 (1709+): a clean, technical, slightly condensed face. */
val UiFont: FontFamily = run {
    val file = File(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts\\bahnschrift.ttf")
    if (file.isFile) {
        runCatching {
            FontFamily(
                Font(file, FontWeight.Normal),
                Font(file, FontWeight.Medium),
                Font(file, FontWeight.SemiBold),
                Font(file, FontWeight.Bold),
            )
        }.getOrDefault(FontFamily.Default)
    } else FontFamily.Default
}

private fun t(size: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, lineHeight: Int = (size * 1.3f).toInt()) =
    TextStyle(fontFamily = UiFont, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp, lineHeight = lineHeight.sp)

private val WsTypography = Typography(
    displayLarge = t(44, FontWeight.Bold, 1f),
    headlineLarge = t(30, FontWeight.Bold, 1.5f),
    headlineMedium = t(24, FontWeight.Bold, 1f),
    titleLarge = t(20, FontWeight.SemiBold, 0.5f),
    titleMedium = t(16, FontWeight.SemiBold, 0.2f),
    titleSmall = t(14, FontWeight.SemiBold, 0.2f),
    bodyLarge = t(15),
    bodyMedium = t(13),
    bodySmall = t(12),
    labelLarge = t(13, FontWeight.SemiBold, 0.8f),
    labelMedium = t(11, FontWeight.SemiBold, 1.4f),
    labelSmall = t(10, FontWeight.Medium, 1.2f),
)

@Composable
fun WsTheme(accent: Accent, content: @Composable () -> Unit) {
    // Cross-fade the accent when the song (and so the album art) changes.
    val primary by animateColorAsState(accent.primary, tween(700))
    val secondary by animateColorAsState(accent.secondary, tween(700))
    val animated = Accent(primary, secondary)
    val scheme = darkColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primary.copy(alpha = 0.22f),
        onPrimaryContainer = Ws.text,
        secondary = secondary,
        background = Ws.bg0,
        onBackground = Ws.text,
        surface = Ws.surface,
        onSurface = Ws.text,
        surfaceVariant = Ws.surfaceHi,
        onSurfaceVariant = Ws.muted,
        outline = Ws.border,
        error = Ws.danger,
    )
    CompositionLocalProvider(LocalAccent provides animated) {
        MaterialTheme(colorScheme = scheme, typography = WsTypography, content = content)
    }
}


