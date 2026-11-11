package com.khodroyar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khodroyar.app.R

/** Orbit: calm neutral surfaces, deliberate colour, Persian typography and one consistent rhythm. */
private fun mix(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

class AppPalette internal constructor(
    val wire: String, val label: String, val englishLabel: String,
    internal val light: Color, internal val dark: Color,
    internal val accentLight: Color, internal val accentDark: Color,
    internal val tertiaryLight: Color, internal val tertiaryDark: Color,
    internal val deep: Color, internal val trueBlack: Boolean = false,
) {
    val swatch: Color get() = light
    companion object {
        // Legacy wire values stay readable. A new palette is selected only for new installs.
        val ORBIT = AppPalette("orbit", "مدار · سبز اقیانوسی", "Orbit · Ocean", Color(0xFF086D61), Color(0xFF71DFCC),
            Color(0xFF426284), Color(0xFFA4C5EF), Color(0xFF8A5812), Color(0xFFF0C583), Color(0xFF103C36))
        val INDIGO = AppPalette("indigo", "نیلی", "Indigo", Color(0xFF4E56A6), Color(0xFFBEC3FF),
            Color(0xFF216E69), Color(0xFF8ADBD3), Color(0xFF86521C), Color(0xFFF0BF90), Color(0xFF262B59))
        val PETROL = AppPalette("petrol", "نفتی", "Petrol", Color(0xFF17635F), Color(0xFF7BD9CD),
            Color(0xFF566477), Color(0xFFBBCBDD), Color(0xFF875620), Color(0xFFF1C28B), Color(0xFF153C3A))
        val PURPLE = AppPalette("purple", "ارغوانی", "Plum", Color(0xFF765080), Color(0xFFDFB6E8),
            Color(0xFF3D6B65), Color(0xFF9FD3C9), Color(0xFF8A552A), Color(0xFFF2BD98), Color(0xFF432A49))
        val BLUE = AppPalette("blue", "آبی اقیانوس", "Blue", Color(0xFF32619C), Color(0xFFA8C9FF),
            Color(0xFF326D68), Color(0xFF9AD7CA), Color(0xFF875B22), Color(0xFFF0C48B), Color(0xFF203C60))
        val ORANGE = AppPalette("orange", "کهربایی", "Amber", Color(0xFF9C4D24), Color(0xFFFFBD99),
            Color(0xFF286D64), Color(0xFF9CDACC), Color(0xFF706023), Color(0xFFDECF8C), Color(0xFF532D1D))
        val BROWN = AppPalette("brown", "قهوه‌ای", "Earth", Color(0xFF795437), Color(0xFFEAC3A3),
            Color(0xFF4B6A4D), Color(0xFFB6D7AC), Color(0xFF72568A), Color(0xFFD5BAE9), Color(0xFF423020))
        val BLACK = AppPalette("black", "گرافیت", "Graphite", Color(0xFF35424A), Color(0xFFCFDCE2),
            Color(0xFF286F6A), Color(0xFF94D8CC), Color(0xFF7F6132), Color(0xFFE3CA97), Color(0xFF1A2227), true)
        val all = listOf(ORBIT, INDIGO, PETROL, BLUE, PURPLE, ORANGE, BROWN, BLACK)
        fun fromWire(wire: String?) = all.firstOrNull { it.wire == wire } ?: ORBIT
    }
}

private fun lightSchemeFor(p: AppPalette) = lightColorScheme(
    primary = p.light, onPrimary = Color.White,
    primaryContainer = mix(Color(0xFFE3F2ED), p.light, 0.035f), onPrimaryContainer = p.deep,
    secondary = p.accentLight, onSecondary = Color.White,
    secondaryContainer = mix(Color(0xFFEAF0F5), p.accentLight, 0.025f), onSecondaryContainer = mix(p.accentLight, Color.Black, 0.30f),
    tertiary = p.tertiaryLight, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF0D9), onTertiaryContainer = Color(0xFF65420D),
    background = Color(0xFFF4F6F7), onBackground = Color(0xFF172B32),
    surface = Color(0xFFFCFDFD), onSurface = Color(0xFF172B32),
    surfaceVariant = Color(0xFFE8EDEF), onSurfaceVariant = Color(0xFF53676E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFA),
    surfaceContainer = Color(0xFFF0F4F4), surfaceContainerHigh = Color(0xFFE9EFF0), surfaceContainerHighest = Color(0xFFE0E8E9),
    outline = Color(0xFF72868D), outlineVariant = Color(0xFFDCE5E6), surfaceTint = p.light,
    error = Color(0xFFAE3D42), onError = Color.White, errorContainer = Color(0xFFFFE9E8), onErrorContainer = Color(0xFF7F282E),
    inverseSurface = Color(0xFF1A3037), inverseOnSurface = Color(0xFFF0F7F6), inversePrimary = p.dark,
)
private fun darkSchemeFor(p: AppPalette) = darkColorScheme(
    primary = p.dark, onPrimary = p.deep, primaryContainer = mix(p.deep, p.light, 0.18f), onPrimaryContainer = mix(p.dark, Color.White, 0.23f),
    secondary = p.accentDark, onSecondary = mix(p.accentLight, Color.Black, 0.55f),
    secondaryContainer = mix(p.accentLight, Color.Black, 0.50f), onSecondaryContainer = p.accentDark,
    tertiary = p.tertiaryDark, onTertiary = Color(0xFF3F2B0E), tertiaryContainer = Color(0xFF49391F), onTertiaryContainer = p.tertiaryDark,
    background = if (p.trueBlack) Color(0xFF030607) else Color(0xFF0D171C), onBackground = Color(0xFFE3ECEF),
    surface = Color(0xFF142127), onSurface = Color(0xFFE3ECEF),
    surfaceVariant = Color(0xFF26373F), onSurfaceVariant = Color(0xFFA2B5BE),
    surfaceContainerLowest = Color(0xFF111E24), surfaceContainerLow = Color(0xFF17262D),
    surfaceContainer = Color(0xFF1D2D35), surfaceContainerHigh = Color(0xFF253740), surfaceContainerHighest = Color(0xFF30424A),
    outline = Color(0xFF82979F), outlineVariant = Color(0xFF2C414B), surfaceTint = p.dark,
    error = Color(0xFFFFAAA8), onError = Color(0xFF591C24), errorContainer = Color(0xFF552A31), onErrorContainer = Color(0xFFFFDAD7),
    inverseSurface = Color(0xFFE5EFF0), inverseOnSurface = Color(0xFF14262C), inversePrimary = p.light,
)

internal fun orbitColorScheme(palette: AppPalette, dark: Boolean): ColorScheme = if (dark) darkSchemeFor(palette) else lightSchemeFor(palette)

val AppFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = AppFontFamily, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp,
)
private val PersianTypography = Typography(
    displayLarge = type(40, 52, FontWeight.Bold), displayMedium = type(34, 46, FontWeight.Bold), displaySmall = type(30, 42, FontWeight.Bold),
    headlineLarge = type(28, 40, FontWeight.Bold), headlineMedium = type(24, 36, FontWeight.Bold), headlineSmall = type(21, 32, FontWeight.Bold),
    titleLarge = type(19, 30, FontWeight.Bold), titleMedium = type(16, 26, FontWeight.SemiBold), titleSmall = type(14, 23, FontWeight.SemiBold),
    bodyLarge = type(16, 28), bodyMedium = type(14, 24), bodySmall = type(12, 21),
    labelLarge = type(14, 22, FontWeight.SemiBold), labelMedium = type(12, 20, FontWeight.Medium), labelSmall = type(11, 18, FontWeight.Medium),
)
private val AppShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp))

object AppDimens {
    val screenPadding = 20.dp
    val gutter = 16.dp
    val gap = 12.dp
    val tightGap = 6.dp
    val cardPadding = 20.dp
    val sectionSpacing = 20.dp
    val tileMinHeight = 112.dp
    val tileCompactMinHeight = 92.dp
    val rowMinHeight = 64.dp
    val buttonHeight = 54.dp
}
class AppAccents(val chart: List<Color>, val positive: Color, val negative: Color, val headerGradient: List<Color>, val heroGradient: List<Color>)
private fun accents(p: AppPalette, dark: Boolean) = AppAccents(
    chart = if (dark) listOf(p.dark, Color(0xFFA7C9FF), Color(0xFFF2C082), Color(0xFFDBB9ED), Color(0xFFF4ADA8), Color(0xFFA1CFA8))
        else listOf(p.light, Color(0xFF4776A8), Color(0xFFA96C23), Color(0xFF9365A3), Color(0xFFB35759), Color(0xFF62805A)),
    positive = if (dark) Color(0xFF86DABD) else Color(0xFF14765B), negative = if (dark) Color(0xFFFFAAA8) else Color(0xFFAE3D42),
    headerGradient = listOf(p.deep, p.deep),
    heroGradient = listOf(Color(0xFF142E35), mix(Color(0xFF163B3B), p.deep, 0.35f)),
)
val LocalAppAccents = staticCompositionLocalOf { accents(AppPalette.ORBIT, false) }
val LocalAppPalette = staticCompositionLocalOf { AppPalette.ORBIT }
val LocalDarkTheme = staticCompositionLocalOf { false }
val appAccents: AppAccents @Composable @ReadOnlyComposable get() = LocalAppAccents.current
@Composable @ReadOnlyComposable fun headerBrush(): Brush = Brush.verticalGradient(LocalAppAccents.current.headerGradient)
@Composable @ReadOnlyComposable fun heroBrush(): Brush = Brush.linearGradient(LocalAppAccents.current.heroGradient)

@Composable
fun CarManagerTheme(darkTheme: Boolean = isSystemInDarkTheme(), palette: AppPalette = AppPalette.ORBIT, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppAccents provides accents(palette, darkTheme), LocalAppPalette provides palette, LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = orbitColorScheme(palette, darkTheme), typography = PersianTypography,
            shapes = AppShapes, content = content)
    }
}
