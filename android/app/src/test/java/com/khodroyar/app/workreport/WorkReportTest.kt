package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class WorkReportTest {
    @Test fun allDigitScriptsAndDateSeparatorsNormalizeStrictly() {
        listOf("۱۴۰۳۰۱۰۱", "١٤٠٣٠١٠١", "14030101", "۱۴۰۳/۱/۱", "1403-01-01", "1403.01.01").forEach { assertEquals(it, "1403/01/01", PersianInput.date(it)) }
        assertEquals("1403/12/30", PersianInput.date("۱۴۰۳۱۲۳۰"))
        listOf("14041230", "14031301", "14030001", "20240320", "140301010", "1/1", "bad", "").forEach { assertNull(it, PersianInput.date(it)) }
    }
    @Test fun unknownIsNeverAZeroAndDecimalsStayExact() {
        listOf("", "?", "O", "-1", "NaN", "Infinity", "1,20", "1e3").forEach { assertNull(it, PersianInput.decimal(it)) }
        assertEquals(BigDecimal("0"), PersianInput.decimal("۰"))
        assertEquals(BigDecimal("1234.5"), PersianInput.decimal("۱٬۲۳۴٫۵"))
        assertEquals(BigDecimal("2.500000"), PersianInput.hours("٢:٣٠"))
        assertNull(PersianInput.hours("2:60")); assertNull(PersianInput.count("1.5"))
    }
    @Test fun jalaliRangeIsInclusiveLeapAwareAndBounded() {
        assertEquals(366, PersianInput.days("14030101", "14031230")!!.size)
        assertEquals(365, PersianInput.days("14030101", "14031229")!!.size)
        assertNull(PersianInput.days("14040101", "14030101"))
        assertNull(PersianInput.days("14030101", "14050101"))
    }
    private fun work(date: String, count: Int, km: String, hours: String) = DailyWork(date, count, km.toBigDecimal(), hours.toBigDecimal())
    @Test fun aggregateMultipleEntriesPerDayWithoutFloatingPointDrift() {
        val a = WorkReportComparison.aggregate(listOf(work("1403/01/01", 1, "0.1", "0.5"), work("1403/01/01", 2, "0.2", "1.25")))
        assertEquals(1, a.size); assertEquals(3, a.single().count)
        assertEquals(BigDecimal("0.3"), a.single().km); assertEquals(BigDecimal("1.75"), a.single().hours)
    }
    @Test fun equalOverallTotalsMustNotHideWrongDays() {
        val company = listOf(work("1403/01/01", 1, "10", "1"), work("1403/01/02", 2, "20", "2"))
        val app = listOf(work("1403/01/01", 2, "20", "2"), work("1403/01/02", 1, "10", "1"))
        val result = WorkReportComparison.compare(company, app, "1403/01/01", "1403/01/02")
        assertEquals(result.company, result.app)
        assertEquals(listOf(DayStatus.DIFFERENT, DayStatus.DIFFERENT), result.days.map { it.status })
        assertEquals(-1, result.days[0].countDifference); assertEquals(1, result.days[1].countDifference)
    }
    @Test fun detectBothKindsOfMissingDaysIncludingOnlyAppDaysInRange() {
        val result = WorkReportComparison.compare(listOf(work("1403/01/01", 1, "10", "1")),
            listOf(work("1403/01/02", 1, "10", "1")), "1403/01/01", "1403/01/03")
        assertEquals(listOf(DayStatus.MISSING_IN_APP, DayStatus.MISSING_IN_REPORT), result.days.map { it.status })
    }
    private fun word(text: String, x: Float, y: Float, confidence: Float = 95f) = OcrWord(text, x - .035f, y, x + .035f, y + .025f, confidence)
    private fun page(id: String = "p1", number: Int = 1, date: String = "۱۴۰۳/۰۱/۰۱", count: String = "۲", header: Boolean = true, totals: Boolean = false): OcrPage {
        val words = mutableListOf<OcrWord>()
        if (header) words += listOf(word("تاریخ", .86f, .1f), word("تعداد", .62f, .1f), word("کیلومتر", .42f, .1f), word("ساعت", .22f, .1f))
        words += listOf(word(date, .86f, .2f), word(count, .62f, .2f), word("١٢٠٫٥", .42f, .2f), word("۳:۳۰", .22f, .2f))
        if (totals) words += listOf(word("جمع", .86f, .3f), word("۲", .62f, .3f), word("۱۲۰٫۵", .42f, .3f), word("۳٫۵", .22f, .3f))
        return OcrPage(id, number, words, "")
    }
    @Test fun extractColumnsByGeometryNotRtlReadingOrder() {
        val row = WorkReportParser.extract(listOf(page())).single()
        assertEquals("1403/01/01", row.date); assertEquals(2, row.validated()!!.count)
        assertEquals(BigDecimal("120.5"), row.validated()!!.km)
        assertEquals(BigDecimal("3.500000"), row.validated()!!.hours)
    }
    @Test fun totalsAreVisibleButExcludedNotDoubleCounted() {
        val rows = WorkReportParser.extract(listOf(page(totals = true)))
        assertEquals(2, rows.size); assertEquals(1, rows.count { it.included }); assertTrue(rows.last().note.contains("جمع"))
    }
    @Test fun sameRowAcrossPagesIsExcludedAndCanBeRestoredExplicitly() {
        val rows = WorkReportParser.extract(listOf(page(), page("p2", 2)))
        assertEquals(1, rows.count { it.included }); assertNotNull(rows[1].duplicateOf)
        assertNotNull(rows[1].copy(included = true).validated())
    }
    @Test fun identicalTripsOnSamePageAreNotAssumedDuplicates() {
        val p = page(); val second = p.words.filter { it.top > .15f }.map { it.copy(top = it.top + .1f, bottom = it.bottom + .1f) }
        assertEquals(2, WorkReportParser.extract(listOf(p.copy(words = p.words + second))).count { it.included })
    }
    @Test fun unreadableCountAndDateAreRetainedForCorrection() {
        val row = WorkReportParser.extract(listOf(page(date = "????", count = "?"))).single()
        assertTrue(row.included); assertNull(row.validated()); assertEquals("", row.count); assertEquals("", row.date)
    }
    @Test fun missingHeadersDoNotGuessValuesAndManualMappingWorks() {
        val p = page(header = false)
        assertNull(WorkReportParser.extract(listOf(p)).single().validated())
        assertNotNull(WorkReportParser.extract(listOf(p), ReportColumns(2, 3, 4)).single().validated())
    }
    @Test fun previousPageHeaderNeverAssumesNextPageLayout() {
        val result = WorkReportParser.extract(listOf(page(), page("p2", 2, date = "۱۴۰۳/۰۱/۰۲", header = false)))
        assertNotNull(result[0].validated()); assertNull(result[1].validated())
    }
    @Test fun zeroMustBeReadOrEnteredExplicitly() {
        val row = ReportRow("r", "p", 1, 1, "", "14030101", "۰", "۰", "۰")
        assertEquals(0, row.validated()!!.count)
        assertNull(row.copy(hours = "").validated()); assertNull(row.copy(km = "bad").validated())
    }
    @Test fun splitDateCellsAreReconstructedInEitherSpatialDirectionWithoutNumericLeakage() {
        for (reverse in listOf(false, true)) {
            val p = page()
            val without = p.words.filterNot { it.x > .8f && it.top > .15f }
            val values = listOf("1403", "/", "01", "/", "02").let { if (reverse) it.reversed() else it }
            val parts = values.mapIndexed { n, value -> OcrWord(value, .77f + n * .035f, .2f, .80f + n * .035f, .225f, 95f) }
            val row = WorkReportParser.extract(listOf(p.copy(words = without + parts))).single()
            assertEquals("1403/01/02", row.date); assertNotNull(row.validated())
            assertNotNull(WorkReportParser.extract(listOf(p.copy(words = without + parts)), ReportColumns(2, 3, 4)).single().validated())
        }
    }
    @Test fun decimalFormattingVariationAcrossPagesStillFlagsTheDuplicate() {
        val a = ReportRow("a", "p1", 1, 1, "۱۴۰۳/۰۱/۰۱ ۲ ۱۲۰٫۵ ۳٫۵", "14030101", "2", "120.5", "3.5")
        val b = a.copy(id = "b", pageId = "p2", page = 2, raw = "1403-01-01 2.0 120.50 3.500")
        assertNotNull(WorkReportParser.duplicates(listOf(a, b))[1].duplicateOf)
    }
    @Test fun duplicateMatchingIsOccurrenceAwareAndPreservesExtraIdenticalTrips() {
        val a = ReportRow("a", "p1", 1, 1, "14030101 1 10 1", "14030101", "1", "10", "1")
        val b = a.copy(id = "b", pageId = "p2", page = 2)
        val extra = b.copy(id = "c", line = 2)
        val result = WorkReportParser.duplicates(listOf(a, b, extra))
        assertEquals(2, result.count { it.included }); assertNotNull(result[1].duplicateOf); assertNull(result[2].duplicateOf)
    }
    @Test fun blankSourceAndExplicitZeroDoNotEraseMissingDayStatus() {
        val zero = work("1403/01/01", 0, "0", "0")
        assertEquals(DayStatus.MISSING_IN_APP, WorkDayComparison(zero.date, zero, null).status)
        assertEquals(DayStatus.MATCH, WorkDayComparison(zero.date, zero, zero.copy(km = BigDecimal("0.0000000001"))).status)
    }

}
