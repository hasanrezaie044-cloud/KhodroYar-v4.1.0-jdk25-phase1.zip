package com.khodroyar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design system.
 *
 * The scheme is no longer a single hard-coded palette. Every colour slot is derived
 * from a small [ThemeSeed] (primary for light, primary for dark, an accent, and the
 * darkest gradient stop), so adding a colour theme is a five-line change and every
 * screen keeps working without touching a single call site: they all read the same
 * semantic Material slots plus [AppAccents].
 *
 * The user picks the colour theme in Settings → ظاهر برنامه; the choice is persisted
 * (`AppSettings.themeColor`) and applied on top of the light / dark / system mode.
 */

/* ---------------------------------------------------------------- colour maths */

/** Linear blend of two colours; `t = 0` returns [a], `t = 1` returns [b]. */
private fun mix(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f,
    )
}

private val Ink = Color(0xFF0E1A1D)
private val InkMuted = Color(0xFF46595B)

/* -------------------------------------------------------------------- palettes */

/**
 * One colour theme. `label` is what the settings picker shows, `wire` is what gets
 * persisted, and `swatch` is the dot drawn in the picker.
 */
class AppPalette internal constructor(
    val wire: String,
    val label: String,
    val englishLabel: String,
    internal val light: Color,
    internal val dark: Color,
    internal val accentLight: Color,
    internal val accentDark: Color,
    internal val tertiaryLight: Color,
    internal val tertiaryDark: Color,
    internal val deep: Color,
    internal val trueBlack: Boolean = false,
) {
    val swatch: Color get() = light

    companion object {
        val PETROL = AppPalette(
            wire = "petrol", label = "نفتی (پیش‌فرض)", englishLabel = "Petrol",
            light = Color(0xFF056B5C), dark = Color(0xFF4FDCC4),
            accentLight = Color(0xFFA1560E), accentDark = Color(0xFFFFB77C),
            tertiaryLight = Color(0xFF3B4F9E), tertiaryDark = Color(0xFF9FB2FF),
            deep = Color(0xFF04463C),
        )
        val PURPLE = AppPalette(
            wire = "purple", label = "بنفش", englishLabel = "Purple",
            light = Color(0xFF6D28D9), dark = Color(0xFFC9A9FF),
            accentLight = Color(0xFF0E7490), accentDark = Color(0xFF7FD8F0),
            tertiaryLight = Color(0xFFBE185D), tertiaryDark = Color(0xFFFFA3C4),
            deep = Color(0xFF2E1065),
        )
        val BLUE = AppPalette(
            wire = "blue", label = "آبی", englishLabel = "Blue",
            light = Color(0xFF1D4ED8), dark = Color(0xFF9FC0FF),
            accentLight = Color(0xFF0891B2), accentDark = Color(0xFF7ADCEF),
            tertiaryLight = Color(0xFF7C3AED), tertiaryDark = Color(0xFFCBB2FF),
            deep = Color(0xFF0B2A6B),
        )
        val ORANGE = AppPalette(
            wire = "orange", label = "نارنجی", englishLabel = "Orange",
            light = Color(0xFFC2410C), dark = Color(0xFFFFB08A),
            accentLight = Color(0xFF0F766E), accentDark = Color(0xFF6FD6C6),
            tertiaryLight = Color(0xFFB45309), tertiaryDark = Color(0xFFF2C173),
            deep = Color(0xFF7C2D12),
        )
        val BROWN = AppPalette(
            wire = "brown", label = "قهوه‌ای", englishLabel = "Brown",
            light = Color(0xFF6B4423), dark = Color(0xFFE0BC96),
            accentLight = Color(0xFF4D7C0F), accentDark = Color(0xFFB6D97A),
            tertiaryLight = Color(0xFF92400E), tertiaryDark = Color(0xFFE9B980),
            deep = Color(0xFF3E2416),
        )
        val BLACK = AppPalette(
            wire = "black", label = "مشکی", englishLabel = "Black",
            light = Color(0xFF2F3437), dark = Color(0xFFD8DEE3),
            accentLight = Color(0xFF0E7490), accentDark = Color(0xFF86D5EA),
            tertiaryLight = Color(0xFF52525B), tertiaryDark = Color(0xFFC3C6CC),
            deep = Color(0xFF101315), trueBlack = true,
        )

        /** Picker order. */
        val all: List<AppPalette> = listOf(PETROL, PURPLE, BLUE, ORANGE, BROWN, BLACK)

        fun fromWire(wire: String?): AppPalette = all.firstOrNull { it.wire == wire } ?: PETROL
    }
}

/* ------------------------------------------------------- generated colour schemes */

private fun lightSchemeFor(p: AppPalette): ColorScheme = lightColorScheme(
    primary = p.light,
    onPrimary = Color.White,
    primaryContainer = mix(p.light, Color.White, 0.80f),
    onPrimaryContainer = mix(p.light, Color.Black, 0.50f),
    inversePrimary = p.dark,
    secondary = p.accentLight,
    onSecondary = Color.White,
    secondaryContainer = mix(p.accentLight, Color.White, 0.80f),
    onSecondaryContainer = mix(p.accentLight, Color.Black, 0.55f),
    tertiary = p.tertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = mix(p.tertiaryLight, Color.White, 0.82f),
    onTertiaryContainer = mix(p.tertiaryLight, Color.Black, 0.55f),
    background = mix(Color(0xFFEFF3F2), p.light, 0.10f),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = mix(Color(0xFFE4EAE8), p.light, 0.12f),
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = mix(Color.White, p.light, 0.035f),
    surfaceContainer = mix(Color.White, p.light, 0.065f),
    surfaceContainerHigh = mix(Color.White, p.light, 0.10f),
    surfaceContainerHighest = mix(Color.White, p.light, 0.14f),
    surfaceTint = p.light,
    outline = mix(Color(0xFF98A4A2), p.light, 0.20f),
    outlineVariant = mix(Color(0xFFC7D1CF), p.light, 0.16f),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410E0A),
    scrim = Color(0xFF000000),
)

private fun darkSchemeFor(p: AppPalette): ColorScheme {
    val base = if (p.trueBlack) Color(0xFF000000) else mix(Color(0xFF080D0F), p.dark, 0.05f)
    val surface = if (p.trueBlack) Color(0xFF0B0B0C) else mix(Color(0xFF111A1C), p.dark, 0.05f)
    return darkColorScheme(
        primary = p.dark,
        onPrimary = mix(p.deep, Color.Black, 0.25f),
        primaryContainer = mix(p.deep, p.light, 0.45f),
        onPrimaryContainer = mix(p.dark, Color.White, 0.35f),
        inversePrimary = p.light,
        secondary = p.accentDark,
        onSecondary = mix(p.accentLight, Color.Black, 0.55f),
        secondaryContainer = mix(p.accentLight, Color.Black, 0.35f),
        onSecondaryContainer = mix(p.accentDark, Color.White, 0.30f),
        tertiary = p.tertiaryDark,
        onTertiary = mix(p.tertiaryLight, Color.Black, 0.55f),
        tertiaryContainer = mix(p.tertiaryLight, Color.Black, 0.35f),
        onTertiaryContainer = mix(p.tertiaryDark, Color.White, 0.30f),
        background = base,
        onBackground = Color(0xFFE6EDEB),
        surface = surface,
        onSurface = Color(0xFFE6EDEB),
        surfaceVariant = mix(surface, p.dark, 0.10f),
        onSurfaceVariant = mix(Color(0xFFB2C0BF), p.dark, 0.12f),
        surfaceContainerLowest = if (p.trueBlack) Color(0xFF000000) else mix(base, Color.Black, 0.35f),
        surfaceContainerLow = mix(surface, Color.Black, 0.20f),
        surfaceContainer = mix(surface, p.dark, 0.04f),
        surfaceContainerHigh = mix(surface, p.dark, 0.09f),
        surfaceContainerHighest = mix(surface, p.dark, 0.14f),
        surfaceTint = p.dark,
        outline = mix(Color(0xFF41504F), p.dark, 0.18f),
        outlineVariant = mix(Color(0xFF2B3A3B), p.dark, 0.12f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690004),
        errorContainer = Color(0xFF930009),
        onErrorContainer = Color(0xFFFFDAD5),
        scrim = Color(0xFF000000),
    )
}

/* -------------------------------------------------------------- typography */

private val PersianTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/* ------------------------------------------------------------------ tokens */

/**
 * Layout tokens. Every screen pulls spacing and card minimum heights from here, which
 * is what keeps neighbouring cards the same size on every screen and at every screen
 * width. No magic dimension numbers at the call sites any more.
 */
object AppDimens {
    val screenPadding = 16.dp
    val gutter = 12.dp
    val gap = 10.dp
    val tightGap = 6.dp
    val cardPadding = 14.dp
    val sectionSpacing = 14.dp

    /** Minimum heights, so a two-up or three-up row of tiles is always level. */
    val tileMinHeight = 98.dp
    val tileCompactMinHeight = 76.dp
    val rowMinHeight = 60.dp
    val buttonHeight = 50.dp
}

/** Colours that are not part of the Material scheme: charts, deltas, chrome. */
class AppAccents(
    val chart: List<Color>,
    val positive: Color,
    val negative: Color,
    val headerGradient: List<Color>,
    val heroGradient: List<Color>,
)

private fun lightAccentsFor(p: AppPalette) = AppAccents(
    chart = listOf(
        p.light,
        p.accentLight,
        p.tertiaryLight,
        mix(p.light, Color.Black, 0.30f),
        mix(p.accentLight, Color.White, 0.25f),
        mix(p.tertiaryLight, Color.Black, 0.25f),
    ),
    positive = Color(0xFF12795F),
    negative = Color(0xFFB3261E),
    headerGradient = listOf(p.deep, mix(p.deep, p.light, 0.55f), mix(p.light, Color.White, 0.10f)),
    heroGradient = listOf(mix(p.deep, p.light, 0.25f), mix(p.light, Color.White, 0.08f)),
)

private fun darkAccentsFor(p: AppPalette) = AppAccents(
    chart = listOf(
        p.dark,
        p.accentDark,
        p.tertiaryDark,
        mix(p.dark, Color.White, 0.25f),
        mix(p.accentDark, Color.Black, 0.20f),
        mix(p.tertiaryDark, Color.Black, 0.20f),
    ),
    positive = Color(0xFF5FD5AE),
    negative = Color(0xFFFFB4AB),
    headerGradient = listOf(
        mix(Color.Black, p.deep, 0.55f),
        p.deep,
        mix(p.deep, p.dark, 0.22f),
    ),
    heroGradient = listOf(mix(Color.Black, p.deep, 0.70f), mix(p.deep, p.dark, 0.18f)),
)

val LocalAppAccents = staticCompositionLocalOf { lightAccentsFor(AppPalette.PETROL) }

/** The palette currently in effect, so a screen can show its name or swatch. */
val LocalAppPalette = staticCompositionLocalOf { AppPalette.PETROL }

/** Convenience accessor so screens can write `appAccents.positive`. */
val appAccents: AppAccents
    @Composable @ReadOnlyComposable get() = LocalAppAccents.current

/** Top-app-area gradient, drawn behind the status bar so the notch area looks intentional. */
@Composable
@ReadOnlyComposable
fun headerBrush(): Brush = Brush.verticalGradient(LocalAppAccents.current.headerGradient)

@Composable
@ReadOnlyComposable
fun heroBrush(): Brush = Brush.linearGradient(LocalAppAccents.current.heroGradient)

@Composable
fun CarManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AppPalette = AppPalette.PETROL,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppAccents provides if (darkTheme) darkAccentsFor(palette) else lightAccentsFor(palette),
        LocalAppPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkSchemeFor(palette) else lightSchemeFor(palette),
            typography = PersianTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
