package com.khodroyar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The previous scheme was structurally fine but visually washed out: an almost-white
 * background against pure-white cards with no visible outline meant nothing read as a
 * card. The palette below keeps the same petrol identity and the same semantic slots
 * (so no screen needs a colour rewrite) while adding real separation:
 *
 *  * background is a deeper tinted neutral, surfaces sit clearly above it;
 *  * the surfaceContainer* slots are populated so cards, headers and rows can differ;
 *  * outlines are strong enough to be visible, which is what makes a card a card;
 *  * a warm amber secondary and an indigo tertiary give charts and states real colour
 *    without turning the app into a rainbow.
 *
 * Type sizes came down a step across the board. Oversized text was the root cause of
 * the "one card is taller than its neighbour" problem: a 32sp number inside a 14dp
 * padded card cannot wrap, so the card grew. See also [AppDimens].
 */

/* ------------------------------------------------------------------ palette */

private val Petrol = Color(0xFF056B5C)
private val PetrolDark = Color(0xFF4FDCC4)
private val Amber = Color(0xFFA1560E)
private val AmberDark = Color(0xFFFFB77C)
private val Indigo = Color(0xFF3B4F9E)
private val IndigoDark = Color(0xFF9FB2FF)

private val Ink = Color(0xFF0E1A1D)
private val InkMuted = Color(0xFF46595B)

private val LightColors = lightColorScheme(
    primary = Petrol,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEDE2),
    onPrimaryContainer = Color(0xFF00352D),
    inversePrimary = PetrolDark,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDFC2),
    onSecondaryContainer = Color(0xFF4A2400),
    tertiary = Indigo,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE1FF),
    onTertiaryContainer = Color(0xFF16215C),
    background = Color(0xFFE7EEEC),
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFDCE6E3),
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FAF9),
    surfaceContainer = Color(0xFFF1F6F4),
    surfaceContainerHigh = Color(0xFFE9F0EE),
    surfaceContainerHighest = Color(0xFFE1EAE7),
    surfaceTint = Petrol,
    outline = Color(0xFF9FB2AE),
    outlineVariant = Color(0xFFC6D4D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410E0A),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = PetrolDark,
    onPrimary = Color(0xFF00352D),
    primaryContainer = Color(0xFF00564A),
    onPrimaryContainer = Color(0xFFB4F5E6),
    inversePrimary = Petrol,
    secondary = AmberDark,
    onSecondary = Color(0xFF4A2400),
    secondaryContainer = Color(0xFF6A3908),
    onSecondaryContainer = Color(0xFFFFDFC2),
    tertiary = IndigoDark,
    onTertiary = Color(0xFF16215C),
    tertiaryContainer = Color(0xFF2C3B85),
    onTertiaryContainer = Color(0xFFDCE1FF),
    background = Color(0xFF070F11),
    onBackground = Color(0xFFE3EDEA),
    surface = Color(0xFF111C1E),
    onSurface = Color(0xFFE3EDEA),
    surfaceVariant = Color(0xFF203033),
    onSurfaceVariant = Color(0xFFB2C4C1),
    surfaceContainerLowest = Color(0xFF060D0F),
    surfaceContainerLow = Color(0xFF0E191B),
    surfaceContainer = Color(0xFF131F22),
    surfaceContainerHigh = Color(0xFF1B292C),
    surfaceContainerHighest = Color(0xFF243437),
    surfaceTint = PetrolDark,
    outline = Color(0xFF415356),
    outlineVariant = Color(0xFF2C3D40),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690004),
    errorContainer = Color(0xFF930009),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color(0xFF000000),
)

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

private val LightAccents = AppAccents(
    chart = listOf(Petrol, Amber, Indigo, Color(0xFF7A1FA2), Color(0xFF0F7490), Color(0xFF9A6700)),
    positive = Color(0xFF12795F),
    negative = Color(0xFFB3261E),
    headerGradient = listOf(Color(0xFF04463C), Color(0xFF066A5C), Color(0xFF0A8B77)),
    heroGradient = listOf(Color(0xFF05594C), Color(0xFF0A8674)),
)

private val DarkAccents = AppAccents(
    chart = listOf(PetrolDark, AmberDark, IndigoDark, Color(0xFFE2A8FF), Color(0xFF6FD3F0), Color(0xFFEBC46A)),
    positive = Color(0xFF5FD5AE),
    negative = Color(0xFFFFB4AB),
    headerGradient = listOf(Color(0xFF04262A), Color(0xFF06403C), Color(0xFF075B50)),
    heroGradient = listOf(Color(0xFF063E38), Color(0xFF0A6355)),
)

val LocalAppAccents = staticCompositionLocalOf { LightAccents }

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
fun CarManagerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppAccents provides if (darkTheme) DarkAccents else LightAccents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PersianTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
