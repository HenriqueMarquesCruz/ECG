package br.ufmg.sistole.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import br.ufmg.sistole.data.ThemeMode

/** Tokens de cor do design system Sístole (tokens.json). */
@Immutable
data class SistoleColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val paper: Color,
    val gridMinor: Color,
    val gridMajor: Color,
    val trace: Color,
    val brand: Color,
    val brandSoft: Color,
    val onBrand: Color,
    val statusOk: Color,
    val statusWarn: Color,
    val statusAlert: Color,
    val onAlert: Color,
    val onWarn: Color,
    val okSoft: Color,
    val warnSoft: Color,
    val alertSoft: Color,
    val scrim: Color,
    val shadow: Color,
) {
    val rec get() = statusAlert
    val focus get() = brand
}

/** Cores dos eletrodos (padrão IEC), iguais nos dois temas. */
object ElectrodeColors {
    val R = Color(0xFFE5484D)
    val L = Color(0xFFF2C200)
    val F = Color(0xFF2F9E62)
    val N = Color(0xFF111312)
    // Padrão AHA: RA branco, LA preto, LL vermelho, RL verde.
    val R_AHA = Color(0xFFF2F2F2)
    val L_AHA = Color(0xFF1A1A1A)
    val F_AHA = Color(0xFFE5484D)
    val N_AHA = Color(0xFF2F9E62)
}

val MonitorColors = SistoleColors(
    isDark = true,
    bg = Color(0xFF0B0E0D),
    surface = Color(0xFF121716),
    surfaceRaised = Color(0xFF1A211F),
    line = Color(0xFF28322F),
    lineStrong = Color(0xFF5B6A65),
    ink = Color(0xFFEAF1ED),
    inkMuted = Color(0xFF97A49E),
    inkFaint = Color(0xFF5F6B67),
    paper = Color(0xFF070A09),
    gridMinor = Color(0xFF121B18),
    gridMajor = Color(0xFF1F2D29),
    trace = Color(0xFF5FF0A8),
    brand = Color(0xFF5FF0A8),
    brandSoft = Color(0x245FF0A8),
    onBrand = Color(0xFF03140C),
    statusOk = Color(0xFF56C8F5),
    statusWarn = Color(0xFFFFC247),
    statusAlert = Color(0xFFFF5A5F),
    onAlert = Color(0xFF1C0506),
    onWarn = Color(0xFF1F1500),
    okSoft = Color(0x1F56C8F5),
    warnSoft = Color(0x1FFFC247),
    alertSoft = Color(0x24FF5A5F),
    scrim = Color(0x99000000),
    shadow = Color(0x80000000),
)

val PapelColors = SistoleColors(
    isDark = false,
    bg = Color(0xFFF4EFE6),
    surface = Color(0xFFFFFCF7),
    surfaceRaised = Color(0xFFEBE4D8),
    line = Color(0xFFDCD2C3),
    lineStrong = Color(0xFF8A7F70),
    ink = Color(0xFF191B1A),
    inkMuted = Color(0xFF5A5F5B),
    inkFaint = Color(0xFF948F86),
    paper = Color(0xFFFFFAF4),
    gridMinor = Color(0xFFF6E1DA),
    gridMajor = Color(0xFFE9B8AB),
    trace = Color(0xFF1A1A1A),
    brand = Color(0xFF0E7A4F),
    brandSoft = Color(0x1A0E7A4F),
    onBrand = Color(0xFFFFFFFF),
    statusOk = Color(0xFF0A6B95),
    statusWarn = Color(0xFF8A5A00),
    statusAlert = Color(0xFFB3121F),
    onAlert = Color(0xFFFFFFFF),
    onWarn = Color(0xFFFFFFFF),
    okSoft = Color(0x170A6B95),
    warnSoft = Color(0x1A8A5A00),
    alertSoft = Color(0x14B3121F),
    scrim = Color(0x73191B1A),
    shadow = Color(0x2E281E14),
)

val LocalSistoleColors = staticCompositionLocalOf { MonitorColors }

object S {
    val c: SistoleColors
        @Composable get() = LocalSistoleColors.current
}

@Composable
fun SistoleTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.MONITOR -> true
        ThemeMode.PAPEL -> false
    }
    val colors = if (dark) MonitorColors else PapelColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.brand, onPrimary = colors.onBrand,
            background = colors.bg, onBackground = colors.ink,
            surface = colors.surface, onSurface = colors.ink,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.inkMuted,
            outline = colors.lineStrong, outlineVariant = colors.line,
            error = colors.statusAlert, onError = colors.onAlert,
        )
    } else {
        lightColorScheme(
            primary = colors.brand, onPrimary = colors.onBrand,
            background = colors.bg, onBackground = colors.ink,
            surface = colors.surface, onSurface = colors.ink,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.inkMuted,
            outline = colors.lineStrong, outlineVariant = colors.line,
            error = colors.statusAlert, onError = colors.onAlert,
        )
    }
    CompositionLocalProvider(LocalSistoleColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTypography, content = content)
    }
}
