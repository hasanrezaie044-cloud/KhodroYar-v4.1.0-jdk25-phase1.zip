package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

class PrintedCellRasterTest {
    private fun paper(width: Int = 80, height: Int = 40) = IntArray(width * height) { 235 }
    @Test fun blankPaperAndAnEnlargedLookingTinyDotAreNotOriginalLegiblePrint() {
        val grey = paper()
        assertFalse(PrintedCellRaster.legibleHeight(80, 40, grey))
        for (y in 18..23) for (x in 35..39) grey[y * 80 + x] = 40
        assertFalse(PrintedCellRaster.legibleHeight(80, 40, grey))
    }
    @Test fun aRealTwelvePixelGlyphIsEligibleButAnAllBlackCellIsNot() {
        val grey = paper()
        for (y in 10..21) for (x in 32..38) grey[y * 80 + x] = 40
        assertTrue(PrintedCellRaster.legibleHeight(80, 40, grey))
        assertFalse(PrintedCellRaster.legibleHeight(80, 40, IntArray(3200)))
    }
    @Test fun headerRuleRemovalPreservesShortLetterStrokesWithoutChangingTheSource() {
        val grey = paper()
        for (y in 0 until 40) { grey[y * 80 + 10] = 20; grey[y * 80 + 11] = 20 }
        for (x in 0 until 80) grey[2 * 80 + x] = 20
        for (y in 10..25) for (x in 32..38) grey[y * 80 + x] = 40
        val before = grey.copyOf()
        val clean = PrintedCellRaster.withoutRules(80, 40, grey)
        assertArrayEquals(before, grey)
        assertTrue((0 until 40).all { clean[it * 80 + 10] == 255 })
        assertTrue((0 until 80).all { clean[2 * 80 + it] == 255 })
        assertTrue((10..25).all { clean[it * 80 + 35] == 40 })
    }
    @Test fun viewsDoNotMutateInputOrInventGeometryAndGlobalThresholdStaysBounded() {
        val grey = paper(); for (y in 8..28) for (x in 30..38) grey[y * 80 + x] = 70
        val copy = grey.copyOf()
        assertTrue(PrintedCellRaster.threshold(grey) in 40..210)
        for (view in PrintedCellRaster.View.entries) {
            val result = PrintedCellRaster.pixels(grey, view)
            assertEquals(grey.size, result.size); assertArrayEquals(copy, grey)
            assertTrue(result.all { (it ushr 24) == 255 })
            if (view == PrintedCellRaster.View.OTSU) assertTrue(result.all { (it and 255) in listOf(0, 255) })
        }
    }
    @Test fun colourConversionAndContrastAreMonotoneNotDigitSubstitutions() {
        val values = intArrayOf(0xff000000.toInt(), 0xff808080.toInt(), 0xffffffff.toInt())
        assertArrayEquals(intArrayOf(0, 128, 255), PrintedCellRaster.grey(3, 1, values))
        val grey = IntArray(1000) { 100 + it / 10 }
        for (view in PrintedCellRaster.View.entries) {
            val result = PrintedCellRaster.pixels(grey, view).map { it and 255 }
            assertTrue(result.zipWithNext().all { (a, b) -> a <= b })
        }
    }
    @Test fun pathologicalSizesAndNonGreyInputsAreRejectedBeforeAllocation() {
        assertTrue(runCatching { PrintedCellRaster.grey(2000, 2000, IntArray(4_000_000)) }.isFailure)
        assertTrue(runCatching { PrintedCellRaster.grey(2, 2, IntArray(3)) }.isFailure)
        assertTrue(runCatching { PrintedCellRaster.threshold(intArrayOf(-1, 256)) }.isFailure)
        assertTrue(runCatching { PrintedCellRaster.pixels(intArrayOf(), PrintedCellRaster.View.GREY) }.isFailure)
    }
}
