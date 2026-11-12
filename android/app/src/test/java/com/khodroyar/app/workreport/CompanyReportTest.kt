package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import org.junit.Assert.*
import org.junit.Test

/** Entirely synthetic/anonymised fixtures; no employee/company statement is checked in. */
class CompanyReportTest {
    private fun word(text: String, x: Float, y: Float = .12f) = OcrWord(text, x - .018f, y, x + .018f, y + .018f, 96f)
    private fun headings(reversed: Boolean = false): List<OcrWord> = listOf("ردیف" to .94f, "تاریخ" to .86f, "مبدأ" to .75f,
        "مقصد" to .61f, "کیلومتر" to .505f, "ساعات" to .455f).map { (text, x) -> word(if (reversed) text.reversed() else text, x) }
    private fun cell(line: Int = 0, ordinal: String = "7", date: String = "1403/02/09", km: String = "64.5", hours: String = "0", total: Boolean = false) =
        CompanyOcrRow(line, "synthetic service", date, ordinal, km, hours, "شهر الف", "شهر ب", 96f, total)
    private fun page(id: String = "p1", number: Int = 1, cells: List<CompanyOcrRow> = listOf(cell()), words: List<OcrWord> = headings()) =
        OcrPage(id, number, words, "synthetic recurring template", cells)

    @Test fun fixedTemplateDoesNotNeedCountAndNeverReadsOrdinalAsCount() {
        val row = WorkReportParser.extract(listOf(page(cells = listOf(cell(ordinal = "۳۷"))))).single()
        assertEquals(ReportRowKind.SERVICE, row.kind); assertEquals("۳۷", row.ordinal)
        assertEquals("1", row.count); assertEquals(1, row.validated()!!.count)
        assertEquals("64.5", row.km); assertEquals("0", row.hours)
    }
    @Test fun rightToLeftVisualHeaderLabelsAreRecognisedWithoutReversingDigits() {
        assertNotNull(CompanyReportTemplate.header(headings(true)))
        val row = WorkReportParser.extract(listOf(page(words = headings(true), cells = listOf(cell(date = "۱۴۰۳/۰۲/۰۹"))))).single()
        assertEquals("1403/02/09", row.date)
        assertEquals(CompanyReportTemplate.Column.KM, CompanyReportTemplate.column("ةموليك"))
    }
    @Test fun numericRegionsAreEstablishedWithoutAServiceCountColumn() {
        val words = headings() + listOf(word("1403/02/09", .86f, .24f), word("64", .505f, .24f), word("0", .455f, .24f))
        val fields = ReportTableGeometry.regions(words).map { it.field }.toSet()
        assertTrue(NumericField.DATE in fields && NumericField.KM in fields && NumericField.HOURS in fields)
        assertFalse(NumericField.COUNT in fields); assertTrue(NumericField.ORDINAL in fields)
    }
    @Test fun genericThreeColumnCountFormIsNotMisclassifiedAsCompanyTemplate() {
        assertNull(CompanyReportTemplate.header(headings() + word("تعداد", .3f)))
        assertNull(CompanyReportTemplate.header(headings().filterNot { it.text in listOf("ردیف", "مبدأ", "مقصد") }))
    }
    @Test fun missingEntireCellOrDateRetainsTheServiceForExplicitReview() {
        val rows = WorkReportParser.extract(listOf(page(cells = listOf(cell(date = "", km = "", hours = ""), cell(1, "8")))))
        assertEquals(2, rows.size); assertTrue(rows.first().included); assertNull(rows.first().validated())
        assertEquals("", rows.first().date); assertEquals("", rows.first().hours)
    }
    @Test fun zeroMustBeAnActualReadValueNotAnEmptyOrDotCell() {
        val row = WorkReportParser.extract(listOf(page())).single()
        assertNotNull(row.validated()); assertNull(row.copy(hours = "").validated()); assertNull(row.copy(hours = ".").validated())
        assertNull(row.copy(count = "7").validated())
    }
    @Test fun totalIsVisibleAndExcludedBeforeAnyAggregation() {
        val rows = WorkReportParser.extract(listOf(page(cells = listOf(cell(), cell(1, "", "", "64.5", "0", true)))))
        assertEquals(1, rows.count { it.included }); assertFalse(rows.last().included); assertTrue(rows.last().note.contains("جمع"))
    }
    @Test fun continuationPagesPreserveSeparateSameDaySameMetricTripsWithDifferentOrdinals() {
        val rows = WorkReportParser.extract(listOf(page(), page("p2", 2, listOf(cell(ordinal = "8"))), page("p3", 3, listOf(cell(ordinal = "9")))))
        assertEquals(3, rows.count { it.included }); assertTrue(rows.all { it.duplicateOf == null })
    }
    @Test fun repeatedScanIsFlaggedByOrdinalDateAndMetricsEvenWhenRouteOcrDiffers() {
        val rows = WorkReportParser.extract(listOf(page(), page("p2", 2, listOf(cell().copy(origin = "low-quality route OCR")))))
        assertEquals(1, rows.count { it.included }); assertNotNull(rows.last().duplicateOf)
        assertNotNull(rows.last().copy(included = true).validated())
    }
    @Test fun sameOrdinalWithDifferentDataIsNotSilentlyDiscarded() {
        val rows = WorkReportParser.extract(listOf(page(), page("p2", 2, listOf(cell(km = "81")))))
        assertEquals(2, rows.count { it.included })
    }
    @Test fun identicalTripsWithinOnePageRemainSeparate() {
        val rows = WorkReportParser.extract(listOf(page(cells = listOf(cell(), cell(1, "8")))))
        assertEquals(2, rows.count { it.included })
    }
    @Test fun aGroupedDescriptionIsNotAssumedToBeOneService() {
        val row = WorkReportParser.extract(listOf(page(cells = listOf(cell().copy(destination = "کارکرد نیمه اول"))))).single()
        assertEquals(ReportRowKind.GROUPED, row.kind); assertNotNull(row.validated())
        assertTrue(CompanyReportTemplate.isGrouped("لوا همین درکراک"))
    }
    @Test fun fullMonthIncludesUnlistedFirstAndLastDaysWithoutHardCodingTheMonth() {
        val rows = WorkReportParser.extract(listOf(page(cells = listOf(cell(date = "1404/08/11")))))
        val period = CompanyReportTemplate.period(listOf(page()), rows)
        assertEquals("1404/08/01", period.start); assertEquals("1404/08/30", period.end)
    }
    @Test fun monthEndIsJalaliLeapAware() {
        for ((date, end) in listOf("1403/12/14" to "1403/12/30", "1404/12/14" to "1404/12/29", "1404/01/14" to "1404/01/31")) {
            val p = page(cells = listOf(cell(date = date)))
            assertEquals(end, CompanyReportTemplate.period(listOf(p), WorkReportParser.extract(listOf(p))).end)
        }
    }
    @Test fun declaredHeaderMonthCanProposeTheRangeEvenWhenAllDatesAreUnreadable() {
        val p = page(words = headings() + word("بهمن", .55f, .04f) + word("۱۴۰۴", .42f, .04f), cells = listOf(cell(date = "")))
        val proposal = CompanyReportTemplate.period(listOf(p), WorkReportParser.extract(listOf(p)))
        assertEquals("1404/11/01", proposal.start); assertEquals("1404/11/30", proposal.end)
    }
    @Test fun conflictingHeaderAndDataNeverSilentlyChoosesAConvenientRange() {
        val p = page(words = headings() + word("بهمن", .55f, .04f) + word("۱۴۰۴", .42f, .04f))
        assertEquals("", CompanyReportTemplate.period(listOf(p), WorkReportParser.extract(listOf(p))).start)
    }
    @Test fun mixedMonthsAcrossPagesRequireAnExplicitCompleteRange() {
        val p = page(); val q = page("p2", 2, listOf(cell(date = "1403/03/02")))
        assertEquals("", CompanyReportTemplate.period(listOf(p, q), WorkReportParser.extract(listOf(p, q))).end)
    }
    @Test fun eachContinuationPageMustEstablishItsOwnGeometry() {
        val p = page(); val q = OcrPage("p2", 2, listOf(word("1403/02/10", .86f, .24f), word("85", .5f, .24f)), "")
        val rows = WorkReportParser.extract(listOf(p, q))
        assertNotNull(rows.first().validated()); assertNull(rows.last().validated())
    }
    @Test fun manualServiceMappingNeedsOnlyKmAndHoursAndDoesNotMapOrdinalToCount() {
        val p = OcrPage("p", 1, listOf(word("37", .95f, .24f), word("1403/02/09", .8f, .24f), word("64.5", .5f, .24f), word("0", .3f, .24f)), "")
        val mapping = ReportColumns(km = 3, hours = 4, singleService = true)
        assertTrue(mapping.valid()); assertFalse(mapping.copy(count = 1).valid())
        val row = WorkReportParser.extract(listOf(p), mapping).single()
        assertEquals(ReportRowKind.SERVICE, row.kind); assertEquals(1, row.validated()!!.count)
        assertEquals("64.5", row.km)
    }
    @Test fun ordinalGrammarDoesNotAcceptZeroFractionOrUnreadableInk() {
        fun glyphs(s: String) = s.map { listOf(it) }
        assertEquals("27", NumericReadingGrammar.uniqueValue(NumericField.ORDINAL, glyphs("27")))
        assertNull(NumericReadingGrammar.uniqueValue(NumericField.ORDINAL, glyphs("0")))
        assertNull(NumericReadingGrammar.uniqueValue(NumericField.ORDINAL, glyphs("1.5")))
        assertNull(PersianInput.hours("."))
    }
}
