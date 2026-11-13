package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

/**
 * Preprocessing determinism and safety nets: bounded gain never darkens real
 * strokes, the conservative ruled-ROI keeps the title strip and refuses to crop
 * on unreliable geometry, and blank detection only fires on genuinely empty cell
 * pixels (never substituting zero for a dotted/dashed or noisy cell).
 */
class ReportPageRasterTest {
    private fun channel(argb: Int) = argb and 0xff

    @Test
    fun uniformWhitePageIsUntouched() {
        val pixels = IntArray(64 * 64) { -1 }
        val out = ReportPageRaster.illuminated(64, 64, pixels)
        assertArrayEquals(pixels, out)
        assertFalse(out === pixels)
    }

    @Test
    fun shadedRegionBrightensButDarkStrokesArePreserved() {
        val w = 64; val h = 64
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x < 3) 0xff000000.toInt() // thin printed stroke, like a small slash
            else if (y < h / 2) 0xffc8c8c8.toInt() // shaded paper
            else -1
        }
        val out = ReportPageRaster.illuminated(w, h, pixels)
        assertEquals("a zero-black stroke gains nothing and stays dark", 0, channel(out[0]))
        assertEquals(0, channel(out[(h - 1) * w + 0]))
        // Top grey paper (~200) receives mild gain; it never exceeds white.
        val lifted = channel(out[10 * w + 10])
        assertTrue(lifted in 235..255)
        assertTrue(channel(out[(h - 10) * w + 40]) >= 245)
        assertArrayEquals("input pixels are not mutated by default", pixels, pixels.copyOf())
    }

    @Test
    fun inPlaceAndDefaultModesAgree() {
        val w = 64; val h = 64
        val pixels = IntArray(w * h) { i -> if (i % w < 3) 0xff000000.toInt() else if (i / w < h / 2) 0xffc8c8c8.toInt() else -1 }
        val copy = pixels.copyOf()
        val fresh = ReportPageRaster.illuminated(w, h, pixels.copyOf())
        ReportPageRaster.illuminated(w, h, copy, inPlace = true)
        assertArrayEquals(fresh, copy)
    }

    @Test
    fun missingOrUnreliableTableKeepsTheFullPage() {
        val w = 40; val h = 40
        val pixels = IntArray(w * h) { -1 }; pixels[0] = 0xff000000.toInt()
        assertArrayEquals(pixels, ReportPageRaster.tableOnly(w, h, pixels, null))
        assertArrayEquals(pixels, ReportPageRaster.tableOnly(w, h, pixels, GridRect(.8f, .2f, .7f, .9f)))
        assertArrayEquals(pixels, ReportPageRaster.tableOnly(w, h, pixels, GridRect(.2f, .2f, .2f, .9f)))
        assertArrayEquals(pixels, ReportPageRaster.tableOnly(w, h, pixels, GridRect(Float.NaN, .2f, .7f, .9f)))
    }

    @Test
    fun tableOnlyPadsBelowBottomAndOutsideSidesButKeepsTitleStrip() {
        val w = 40; val h = 40
        val pixels = IntArray(w * h) { -1 }
        val black = 0xff000000.toInt()
        pixels[5 * w + 20] = black  // title strip above the table
        pixels[15 * w + 20] = black // inside the ruled body
        pixels[15 * w + 35] = black // outside the right rule
        pixels[30 * w + 20] = black // below the (possibly faint) last rule
        val out = ReportPageRaster.tableOnly(w, h, pixels, GridRect(.2f, .3f, .8f, .6f))
        assertEquals(black, out[5 * w + 20])
        assertEquals(black, out[15 * w + 20])
        assertEquals(-1, out[15 * w + 35])
        assertEquals("any faint-rule tail noise is padded white, never fed to OCR", -1, out[30 * w + 20])
    }

    @Test
    fun blankDetectionOnlyFiresOnTrulyEmptyCells() {
        assertTrue(PrintedCellRaster.blank(IntArray(400) { 255 }))
        assertTrue(PrintedCellRaster.blank(IntArray(400) { 200 }))
        assertFalse(PrintedCellRaster.blank(IntArray(400) { if (it == 199) 0 else 255 }))
    }
}
