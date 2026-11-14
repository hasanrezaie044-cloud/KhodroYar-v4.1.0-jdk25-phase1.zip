package com.khodroyar.app.ui

import androidx.compose.ui.graphics.Color
import com.khodroyar.app.ui.theme.AppPalette
import com.khodroyar.app.ui.theme.orbitColorScheme
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class OrbitThemeTest {
    @Test fun allLegacyPaletteWiresAreStillRecognised() {
        listOf("indigo", "petrol", "purple", "blue", "orange", "brown", "black").forEach { assertEquals(it, AppPalette.fromWire(it).wire) }
    }
    @Test fun newDefaultAndUnknownWireAreSafe() { assertSame(AppPalette.ORBIT, AppPalette.fromWire("orbit")); assertSame(AppPalette.ORBIT, AppPalette.fromWire(null)); assertSame(AppPalette.ORBIT, AppPalette.fromWire("unknown")) }
    @Test fun pickerHasUniqueStableKeys() { assertEquals(8, AppPalette.all.size); assertEquals(8, AppPalette.all.map { it.wire }.distinct().size) }
    @Test fun bodyTextAndPrimaryControlsHaveReadableContrastInEveryTheme() {
        AppPalette.all.forEach { palette -> listOf(false, true).forEach { dark ->
            val s = orbitColorScheme(palette, dark)
            listOf(s.onSurface to s.surface, s.onSurfaceVariant to s.surfaceContainerLowest, s.onPrimary to s.primary,
                s.onPrimaryContainer to s.primaryContainer, s.onErrorContainer to s.errorContainer).forEach { (text, background) ->
                assertTrue("${palette.wire} dark=$dark contrast=${contrast(text, background)}", contrast(text, background) >= 4.5)
            }
        } }
    }
    private fun luminance(c: Color): Double {
        fun linear(v: Float) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a); val y = luminance(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
