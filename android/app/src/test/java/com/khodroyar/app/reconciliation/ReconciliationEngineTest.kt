package com.khodroyar.app.reconciliation

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.persian.PersianText
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ReconciliationEngineTest {
    private val day = "1405/01/12"
    private fun app(n: Int, km: Double = 160.0, hours: Double = 10.0, date: String = day) = (1..n).map { AppWorkRecord("$date-$it", date, km, hours) }
    private fun row(id: String, date: String = day, km: String = "160", hours: String = "10", page: String = "p1", confirmed: Boolean = true, count: String = "1") =
        StatementRow(id, page, date, km, hours, count, confidence = 95, confirmed = confirmed)
    private fun company(n: Int, km: String = "160", hours: String = "10", date: String = day) = (1..n).map { row("r$it", date, km, hours) }
    private fun compare(a: List<AppWorkRecord>, c: List<StatementRow>, pages: List<StatementPage> = listOf(StatementPage("p1", true))) = ReconciliationEngine.compare(a, c, pages, "1405/01/01", "1405/01/31")
    private fun number(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun fullyMatchingDays() {
        val r = compare(app(3), company(3))
        assertTrue(r.final); assertEquals(1, r.matchedDays); assertEquals(0, r.discrepancyDays)
        assertEquals(3, r.app.services); number("480", r.company.kilometers); number("30", r.company.hours)
    }
    @Test fun caseAMissingFromCompany() {
        val r = compare(app(3), company(2))
        assertEquals(1, r.difference.services); number("160", r.difference.kilometers); number("10", r.difference.hours)
        assertEquals(DayStatus.DISCREPANCY, r.days.single().status)
        assertTrue(r.days.single().explanations.any { it.contains("صورت‌وضعیت شرکت کم") })
    }
    @Test fun caseBMissingFromApplication() {
        val r = compare(app(2), company(3))
        assertEquals(-1, r.difference.services); number("-160", r.difference.kilometers); number("-10", r.difference.hours)
        assertTrue(r.days.single().explanations.any { it.contains("در برنامه ثبت نشده") })
    }
    @Test fun caseCEqualCountsDifferentKilometers() {
        val r = compare(app(2), company(2, "150"))
        assertEquals(0, r.difference.services); number("20", r.difference.kilometers); number("0", r.difference.hours)
        assertEquals(1, r.discrepancyDays)
    }
    @Test fun caseDEqualCountsAndDistanceDifferentHours() {
        val r = compare(app(2), company(2, hours = "9"))
        number("0", r.difference.kilometers); number("2", r.difference.hours); assertEquals(1, r.discrepancyDays)
    }
    @Test fun overallEqualityNeverHidesDailyDifferences() {
        val a = app(1) + app(1, date = "1405/01/13")
        val c = listOf(row("a", km = "0"), row("b", date = "1405/01/13", km = "320"))
        val r = compare(a, c)
        number("0", r.difference.kilometers); assertEquals(2, r.discrepancyDays); assertEquals(0, r.matchedDays)
    }
    @Test fun multipleRowsSameDateAreAllCountedIncludingEqualTrips() {
        val r = compare(app(4), company(4))
        assertEquals(4, r.company.services); number("640", r.days.single().company.kilometers)
    }
    @Test fun aConfirmedDailySummaryHasAnExplicitCount() {
        val r = compare(app(3), listOf(row("daily", km = "480", hours = "30", count = "۳")))
        assertEquals(1, r.matchedDays); assertEquals(3, r.company.services)
    }
    @Test fun persianArabicWesternNumeralsMatchAcrossAllFields() {
        val c = listOf(row("1", "۱۴۰۵/۰۱/۱۲", "۱۶۰", "۱۰"), row("2", "١٤٠٥/٠١/١٢", "١٦٠", "١٠"), row("3"))
        val a = app(1, date = "۱۴۰۵/۱/۱۲") + app(1, date = "١٤٠٥/١/١٢") + app(1)
        assertEquals(1, compare(a, c).matchedDays)
    }
    @Test fun jalaliDatesAreNormalizedWithoutGregorianConversion() {
        assertEquals("1405/01/12", PersianText.date("\u200f۱۴۰۵ - ۱ - ۱۲\u200e"))
        assertEquals("1403/12/30", PersianText.date("۱۴۰۳/۱۲/۳۰")); assertNull(PersianText.date("۱۴۰۴/۱۲/۳۰"))
        assertNull(PersianText.date("1405/07/31")); assertNull(PersianText.date("2026/04/01"))
        assertEquals(Triple(2026, 4, 1), Jalali.toGregorian(1405, 1, 12))
        assertEquals("1405/01/12", Jalali.fromGregorian(2026, 4, 1).toString())
    }
    @Test fun boundaryDatesAreIncludedAndOtherDatesExcluded() {
        val r = compare(app(1, date = "1405/01/01") + app(1, date = "1405/01/31") + app(1, date = "1405/02/01"), listOf(row("1", "1405/01/01"), row("2", "1405/01/31"), row("3", "1405/02/01")))
        assertEquals(2, r.matchedDays); assertEquals(2, r.app.services)
    }
    @Test fun absentSideDaysAreDiscrepanciesNotDropped() {
        val r = compare(app(1), listOf(row("company-only", "1405/01/13")))
        assertEquals(2, r.discrepancyDays); assertEquals(0, r.difference.services)
    }
    @Test fun unreadableValueIsNotZeroAndBlocksFinalResult() {
        val r = compare(app(1), listOf(row("?", km = "ناخوانا")))
        assertFalse(r.final); assertEquals(0, r.company.services); assertEquals(DayStatus.REVIEW, r.days.single().status)
        assertTrue(r.reviewIssues.isNotEmpty())
    }
    @Test fun missingKilometersAndHoursAreNotZero() {
        listOf(row("missing-km", km = ""), row("missing-hours", hours = ""), row("dash", hours = "—")).forEach {
            assertNull(ReconciliationEngine.values(it)); assertFalse(compare(app(1), listOf(it)).final)
        }
    }
    @Test fun explicitZeroIsValidButStillNeedsConfirmation() {
        assertEquals(1, compare(app(1, 0.0, 0.0), listOf(row("zero", km = "0", hours = "۰"))).matchedDays)
        assertFalse(compare(app(1), listOf(row("u", confirmed = false))).final)
    }
    @Test fun highConfidenceNeverBypassesHumanApproval() {
        val r = compare(app(1), listOf(row("unconfirmed").copy(confidence = 100, confirmed = false)))
        assertFalse(r.final); assertEquals(1, r.reviewDays)
    }
    @Test fun multiplePagesAndReorderingDoNotChangeTotals() {
        val c = listOf(row("a", km = "100"), row("b", km = "220", page = "p2"))
        val pages = listOf(StatementPage("p1", true), StatementPage("p2", true))
        val a = app(1, 100.0) + app(1, 220.0)
        assertEquals(compare(a, c, pages), compare(a, c.reversed(), pages.reversed()))
        assertEquals(1, compare(a, c, pages).matchedDays)
    }
    @Test fun totalHeaderAndExplanatoryRowsAreNeverCounted() {
        val c = company(2) + row("total", km = "320", hours = "20").copy(kind = StatementRowKind.TOTAL) + row("header").copy(kind = StatementRowKind.OTHER)
        assertEquals(2, compare(app(2), c).company.services)
    }
    @Test fun crossPageDuplicatesAreNotSilentlyCountedOrSilentlyDeleted() {
        val c = listOf(row("a"), row("b", page = "p2"))
        val pages = listOf(StatementPage("p1", true), StatementPage("p2", true))
        assertEquals(setOf("a", "b"), ReconciliationEngine.duplicateCandidates(c))
        val uncertain = compare(app(1), c, pages)
        assertFalse(uncertain.final); assertEquals(0, uncertain.company.services)
        val removed = compare(app(1), listOf(c[0], c[1].copy(excluded = true)), pages)
        assertTrue(removed.final); assertEquals(1, removed.matchedDays)
        val distinctTrips = compare(app(2), c.map { it.copy(keepDuplicate = true) }, pages)
        assertTrue(distinctTrips.final); assertEquals(1, distinctTrips.matchedDays)
    }
    @Test fun emptyOrUnreviewedPageBlocksFinalisationEvenIfKnownTotalsMatch() {
        assertFalse(compare(app(1), company(1), listOf(StatementPage("p1", true), StatementPage("p2", false))).final)
        assertEquals(1, compare(app(1), company(1), listOf(StatementPage("p1", false))).reviewDays)
        assertFalse(compare(emptyList(), emptyList(), emptyList()).final)
    }
    @Test fun unreadableDateCannotDisappearThroughRangeFiltering() {
        val r = compare(app(1), listOf(row("unknown-date", date = "14?5/01/12")))
        assertFalse(r.final); assertEquals(1, r.reviewDays)
    }
    @Test fun malformedApplicationValuesAreReadOnlyReviewIssues() {
        val a = listOf(AppWorkRecord("bad", day, Double.NaN, 10.0))
        val r = compare(a, company(1)); assertFalse(r.final); assertEquals(1, r.reviewDays)
        assertTrue(a.single().kilometers.isNaN())
    }
    @Test fun numericSeparatorsAndDurationAbove24HoursAreSupported() {
        number("1234.5", PersianText.decimal("۱٬۲۳۴٫۵")!!)
        number("1234.5", PersianText.decimal("١،٢٣٤٫٥")!!)
        number("1234.5", PersianText.decimal("1,234.5")!!)
        number("1234", PersianText.decimal("1 234")!!)
        number("30.5", PersianText.hours("۳۰:۳۰")!!)
        assertNull(PersianText.hours("۱۰:۷۵")); assertNull(PersianText.decimal("1,23"))
        assertNull(PersianText.decimal("1 2")); assertNull(PersianText.decimal("-1")); assertNull(PersianText.decimal("NaN"))
    }
    @Test fun decimalMathAvoidsFloatingPointFalseDiscrepancies() {
        val a = listOf(AppWorkRecord("a", day, 0.1, 0.1), AppWorkRecord("b", day, 0.2, 0.2))
        val c = listOf(row("daily", km = "۰٫۳", hours = "۰٫۳", count = "2"))
        assertEquals(1, compare(a, c).matchedDays)
    }
    @Test fun reportUsesPersianNumbersAndIncludesEveryDailyDiscrepancy() {
        val r = compare(app(3), company(2)); val text = ReconciliationTextReport.write(r)
        assertTrue(text.contains("۱۴۰۵/۰۱/۱۲")); assertTrue(text.contains("۱۶۰")); assertTrue(text.contains("هیچ رکورد سرویس تغییر نکرده"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRangeIsRejected() { ReconciliationEngine.compare(emptyList(), emptyList(), emptyList(), "1405/02/01", "1405/01/01") }
    @Test(expected = IllegalArgumentException::class) fun duplicateIdsAreRejected() { compare(app(1), listOf(row("same"), row("same"))) }
}
