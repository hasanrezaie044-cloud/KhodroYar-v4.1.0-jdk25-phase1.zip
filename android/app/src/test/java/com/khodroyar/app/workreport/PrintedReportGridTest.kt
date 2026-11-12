package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.tan

class PrintedReportGridTest {
    private val width = 1000
    private val height = 1400
    private val columns = listOf(50, 120, 200, 290, 360, 430, 480, 530, 690, 810, 915, 960)
    private fun header() = CompanyReportTemplate.Header(mapOf(CompanyReportTemplate.Column.ORDINAL to .94f,
        CompanyReportTemplate.Column.DATE to .86f, CompanyReportTemplate.Column.ORIGIN to .75f,
        CompanyReportTemplate.Column.DESTINATION to .61f, CompanyReportTemplate.Column.KM to .505f,
        CompanyReportTemplate.Column.HOURS to .455f), .15f, .17f)
    private fun grid(shadow: Boolean = false, missingLine: Boolean = false, mergedFooter: Boolean = false): ReportInk {
        val grey = ByteArray(width * height) { index -> (if (shadow) 130 + 115 * (index % width) / width else 255).toByte() }
        fun black(x: Int, y: Int) { if (x in 0 until width && y in 0 until height) grey[y * width + x] = 20 }
        for (y in listOf(200, 250, 300, 350, 400, 450)) {
            if (missingLine && y == 300) continue
            for (x in 50..960) { black(x, y); black(x, y + 1) }
        }
        for (x in columns) for (y in 200..450) {
            if (mergedFooter && y > 400 && x in listOf(690, 810)) continue
            black(x, y); black(x + 1, y)
        }
        return ReportInk.fromGrey(width, height, grey)
    }
    @Test fun actualRulesEstablishEveryRowEvenWhenNoDigitsWereRecognised() {
        val rows = grid().companyRows(header())
        assertEquals(4, rows.size); assertTrue(rows.all { it.reliable })
        assertTrue(rows.all { it.cells.keys.containsAll(CompanyReportTemplate.Column.entries) })
    }
    @Test fun croppedOuterDateCellRetainsVisibleRowsWithoutInventingAnOrdinalColumn() {
        val original = grid(); val croppedWidth = 900
        val cropped = ReportInk(croppedWidth, height, BooleanArray(croppedWidth * height) { i -> original.ink[i / croppedWidth * width + i % croppedWidth] })
        val h = header().copy(columns = header().columns.filterKeys { it != CompanyReportTemplate.Column.ORDINAL }.mapValues { (_, x) -> x * width / croppedWidth })
        val rows = cropped.companyRows(h)
        assertEquals(4, rows.size); assertTrue(rows.all { it.reliable })
        assertTrue(rows.all { CompanyReportTemplate.Column.ORDINAL !in it.cells })
        assertTrue(rows.all { it.cells.getValue(CompanyReportTemplate.Column.DATE).right > .99f })
    }
    @Test fun absentOrdinalHeadingDoesNotCreateAnExtraServiceOrCountCell() {
        val rows = grid().companyRows(header().copy(columns = header().columns - CompanyReportTemplate.Column.ORDINAL))
        assertEquals(4, rows.size); assertTrue(rows.all { it.reliable })
        assertTrue(rows.all { CompanyReportTemplate.Column.ORDINAL !in it.cells })
    }
    @Test fun cellBoundariesFollowRulesRatherThanHeaderMidpoints() {
        val row = grid().companyRows(header()).first()
        val date = row.cells.getValue(CompanyReportTemplate.Column.DATE)
        assertTrue(date.left in .81f.. .82f && date.right in .91f.. .92f)
        val km = row.cells.getValue(CompanyReportTemplate.Column.KM)
        assertTrue(km.left >= .48f && km.right <= .53f)
    }
    @Test fun localContrastWorksUnderASyntheticShadow() {
        val rows = grid(shadow = true).companyRows(header())
        assertEquals(4, rows.size); assertTrue(rows.all { it.reliable })
    }
    @Test fun aMissingSeparatorIsNotTreatedAsAConfidentSingleService() {
        val rows = grid(missingLine = true).companyRows(header())
        assertTrue(rows.isNotEmpty()); assertTrue(rows.any { !it.reliable })
    }
    @Test fun mergedFooterCanBeReadAsALabelButHasNoInventedDateOrOrdinal() {
        val last = grid(mergedFooter = true).companyRows(header()).last()
        assertFalse(last.reliable); assertTrue(last.description.right > last.description.left)
    }
    @Test fun noPrintedGridNeverInventsDefaultCoordinatesOrRowCounts() {
        val blank = ReportInk.fromGrey(width, height, ByteArray(width * height) { 240.toByte() })
        assertTrue(blank.companyRows(header()).isEmpty()); assertEquals(0f, blank.skewDegrees())
    }
    @Test fun headerAndFixedColumnOrderMustAgreeWithTheActualRules() {
        val h = header(); val bad = h.copy(columns = h.columns + (CompanyReportTemplate.Column.KM to .61f))
        assertTrue(grid().companyRows(bad).isEmpty())
    }
    @Test fun smallSkewComesFromSeveralLongLinesNotOneTextBaseline() {
        val w = 600; val h = 800; val angle = -1.25f; val slope = tan(Math.toRadians(angle.toDouble()))
        val ink = BooleanArray(w * h)
        for (base in 100..650 step 50) for (x in 25..570) {
            val y = (base + slope * x).roundToInt(); ink[y * w + x] = true
        }
        assertEquals(angle.toDouble(), ReportInk(w, h, ink).skewDegrees().toDouble(), .22)
    }
    @Test fun lineAnalysisIsBoundedAndCancellationIsObserved() {
        assertTrue(runCatching { ReportInk.fromGrey(2000, 2000, ByteArray(4000000)) }.isFailure)
        var called = false
        assertTrue(runCatching { ReportInk.fromGrey(100, 100, ByteArray(10000)) { called = true; error("cancel") } }.isFailure)
        assertTrue(called)
    }
    @Test fun slightlyOversizedPhotoIsNotNeedlesslyHalvedAndPrintIsRetained() {
        val plan = ReportRasterPlan.of(2200, 2900, 6000000, 3200)
        assertTrue(plan.width > 2000 && plan.height > 2700); assertEquals(1, plan.sample)
        assertTrue(plan.width.toLong() * plan.height <= 6000000 && maxOf(plan.width, plan.height) <= 3200)
    }
    @Test fun hugeCameraImageStillDecodesWithABoundedSampleAndDensity() {
        val plan = ReportRasterPlan.of(12000, 9000, 6000000, 3200)
        assertTrue(plan.sample >= 4); assertTrue(plan.density in 1..1000000)
        assertTrue(plan.width.toLong() * plan.height <= 6000000 && maxOf(plan.width, plan.height) <= 3200)
    }
    @Test fun thumbnailsAndAlreadySmallImagesKeepTheirBounds() {
        val small = ReportRasterPlan.of(320, 240, 6000000, 3200)
        assertEquals(320, small.width); assertEquals(240, small.height); assertEquals(1000000, small.density)
        val thumb = ReportRasterPlan.of(4000, 3000, 120000, 420)
        assertTrue(thumb.width <= 420 && thumb.height <= 420 && thumb.width.toLong() * thumb.height <= 120000)
    }
}
