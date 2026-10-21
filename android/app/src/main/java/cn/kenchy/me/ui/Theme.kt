package cn.kenchy.me.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em

// 与储蓄网站同一套配色：暖米色底 + 深绿强调
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val line2: Color,
    val accent: Color,
    val accentSoft: Color,
    val net: Color,
    val salary: Color,
    val cost: Color,
    val fund: Color,
    val up: Color,
    val down: Color,
    val danger: Color,
    val warn: Color,
    val warnSoft: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFF4F2ED), surface = Color(0xFFFFFEFB), ink = Color(0xFF1D1C19), ink2 = Color(0xFF57544D),
    ink3 = Color(0xFF8D887E), line = Color(0xFFE6E2D9), line2 = Color(0xFFEFECE5), accent = Color(0xFF1F5C4A),
    accentSoft = Color(0xFFE3EEE9), net = Color(0xFFB9D3C8), salary = Color(0xFFC2703D), cost = Color(0xFFD8B98A),
    fund = Color(0xFF8FB5C9), up = Color(0xFF2C7A5C), down = Color(0xFFB4532F), danger = Color(0xFFB23B2A),
    warn = Color(0xFFB7791F), warnSoft = Color(0xFFF6EBD7), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF141513), surface = Color(0xFF1C1D1A), ink = Color(0xFFECEBE6), ink2 = Color(0xFFB4B1A8),
    ink3 = Color(0xFF7F7C74), line = Color(0xFF2E2F2B), line2 = Color(0xFF262724), accent = Color(0xFF7FC4A6),
    accentSoft = Color(0xFF23332C), net = Color(0xFF2F4A3F), salary = Color(0xFFE0925C), cost = Color(0xFF8A6F45),
    fund = Color(0xFF4F7489), up = Color(0xFF7FC4A6), down = Color(0xFFE48C69), danger = Color(0xFFEF7A66),
    warn = Color(0xFFE0B45C), warnSoft = Color(0xFF3A3122), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

object T {
    val c: Palette @Composable get() = LocalPalette.current
}

// 数字统一用等宽数字，方便上下对齐比较
// 行高按字号比例（em），中文和数字在同一行里盒子一样高，不会一高一低
val Num = TextStyle(
    fontFeatureSettings = "tnum",
    lineHeight = 1.35.em,
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
    ),
)

@Composable
fun MeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.bg, background = p.bg, surface = p.surface, onSurface = p.ink,
        onBackground = p.ink, surfaceVariant = p.line2, onSurfaceVariant = p.ink2, outline = p.line,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.ink, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerLow = p.bg, error = p.danger,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bg, surface = p.surface, onSurface = p.ink,
        onBackground = p.ink, surfaceVariant = p.line2, onSurfaceVariant = p.ink2, outline = p.line,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.ink, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerLow = p.bg, error = p.danger,
    )
    val base = MaterialTheme.typography
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // 默认文字样式的行高也按字号比例，只写 fontSize 的 Text 不会被撑成 23sp 高
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides typography.bodyLarge.copy(
                    lineHeight = 1.35.em,
                    lineHeightStyle = Num.lineHeightStyle,
                ),
                content = content,
            )
        }
    }
}
