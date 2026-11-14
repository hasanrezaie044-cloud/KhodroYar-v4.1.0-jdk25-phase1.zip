package com.khodroyar.app.workreport

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

/**
 * Blank/unreadable hours are UNKNOWN, never invented zero. Sparse results still
 * show full-period real totals, while strict service matching runs only on the
 * unaffected subset and everything else is explicitly held for review.
 */
class SparseHoursComparisonTest {
    private val d1 = "1403/04/12"
    private val d2 = "1403/04/18"
    private val d3 = "1403/04/23"

    private fun row(id: String, date: String = d1, count: String = "1", km: String = "40", hours: String = "",
        blank: Boolean = false, kind: ReportRowKind = ReportRowKind.SERVICE) =
        ReportRow(id, "p1", 1, 0, "raw", date = date, count = count, km = km, hours = hours, kind = kind, hoursBlank = blank)

    private fun service(id: String, date: String, km: String, hours: String) =
        AppWorkService(id, date, BigDecimal(km), BigDecimal(hours))

    private fun equal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual); assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun blankHoursAreUnknownNeverZero() {
        val blank = row("r1", hours = "", blank = true)
        val reviewed = blank.reviewed()
        assertNotNull(reviewed)
        assertNull(reviewed!!.hours)
        // The strict engine must not receive a manufactured zero: it still
        // refuses this row, and it is routed to the sparse path instead.
        assertNull(blank.validated())
        assertNull(row("r2", hours = ".", blank = true).reviewed()!!.hours)
        assertNull(row("r3", hours = "-", blank = true).reviewed()!!.hours)
        // An explicitly recorded zero remains evidence, not an erased blank.
        equal("0", row("r4", hours = "0").reviewed()!!.hours)
        assertTrue(ReportHoursText.isEmpty("  ")); assertTrue(ReportHoursText.isEmpty(" . ")); assertTrue(ReportHoursText.isEmpty("-"))
        assertFalse(ReportHoursText.isEmpty("۰")); assertFalse(ReportHoursText.isEmpty("٠"))
    }

    @Test
    fun fullPeriodTotalsAreSeparateFromCompleteDaysSubset() {
        val rows = listOf(
            row("r1", d1, km = "40", hours = "", blank = true),
            row("r2", d1, km = "75", hours = "", blank = true),
            row("r3", d2, km = "65", hours = "2.5"),
            row("r4", d3, km = "55", hours = "1.25"),
        )
        val app = listOf(service("a1", d1, "40", "1"), service("a2", d1, "75", "2"), service("a3", d2, "65", "2.5"))
        val result = SparseHoursComparison.compare(rows, app, "1403/04/10", "1403/04/30")
        assertTrue(result.serviceCountKnown)
        assertEquals(4, result.company.count)
        equal("235", result.company.km)
        assertNull("two blank hours must not become a zero company total", result.company.hours)
        assertEquals(3, result.app.count)
        equal("5.5", result.app.hours)
        equal("55", result.company.km - result.app.km) // the missing-in-app day counts toward full-period km
        val days = result.days.associateBy { it.date }
        assertEquals(setOf(d1, d2, d3), days.keys)
        assertEquals(ReviewedDayStatus.UNKNOWN_HOURS, days.getValue(d1).status)
        assertEquals(0, days.getValue(d1).countDifference)
        assertNull(days.getValue(d1).hoursDifference)
        equal("0", days.getValue(d1).kmDifference)
        assertEquals(ReviewedDayStatus.EQUAL_TOTALS, days.getValue(d2).status)
        assertEquals(ReviewedDayStatus.MISSING_IN_APP, days.getValue(d3).status)
        assertNull(days.getValue(d3).app)
        assertEquals(setOf(d1), result.heldDates)
        // completeDays is ONLY the unaffected subset, never full-period totals.
        assertEquals(2, result.completeDays.company.count)
        assertFalse(result.completeDays.days.any { it.date == d1 })
        assertEquals(setOf(d2, d3), result.completeDays.days.map { it.date }.toSet())
        assertEquals(0, result.deferredLinkCount)
    }

    @Test
    fun groupedScopeSuspendsServiceCountsWithoutGuessingPairing() {
        val grouped = row("g1", d1, count = "1", km = "200", hours = "", blank = true, kind = ReportRowKind.GROUPED)
        val app = listOf(service("a1", d1, "200", "8"), service("a2", d2, "10", "0.5"))
        val result = SparseHoursComparison.compare(listOf(grouped), app, "1403/04/10", "1403/04/30")
        assertFalse(result.serviceCountKnown)
        assertEquals(setOf(d1, d2), result.heldDates)
        assertTrue(result.completeDays.days.isEmpty())
        assertEquals(0, result.completeDays.company.count)
        val days = result.days.associateBy { it.date }
        assertEquals(ReviewedDayStatus.GROUPED, days.getValue(d1).status)
        assertNull(days.getValue(d1).countDifference)
        assertEquals(ReviewedDayStatus.MISSING_IN_REPORT, days.getValue(d2).status)
        assertNull(result.company.hours)
    }

    @Test
    fun blankHoursNeverHideKmOrCountDifferences() {
        val rows = listOf(row("r1", d1, km = "40", hours = "", blank = true))
        val app = listOf(service("a1", d1, "41", "1"))
        val day = SparseHoursComparison.compare(rows, app, "1403/04/10", "1403/04/30").days.single()
        assertEquals(ReviewedDayStatus.DIFFERENT, day.status)
        assertNull(day.hoursDifference)
        equal("-1", day.kmDifference)
    }

    @Test
    fun crossDayManualLinkIsDeferredTransitivelyNotPartiallyMatched() {
        val rows = listOf(row("r1", d1, km = "40", hours = "", blank = true), row("r2", d2, km = "65", hours = "2.5"))
        val app = listOf(service("a1", d1, "40", "1"), service("a2", d2, "65", "2.5"))
        val link = ReportMatchLink(listOf("r1"), listOf("a2"), ServiceWorkComparison.fingerprint(listOf(app[1])))
        val result = SparseHoursComparison.compare(rows, app, "1403/04/10", "1403/04/30", listOf(link))
        assertEquals(1, result.deferredLinkCount)
        assertEquals(setOf(d1, d2), result.heldDates)
        assertTrue("the linked complete day must not leak into automatic strict matching", result.completeDays.days.isEmpty())
        val days = result.days.associateBy { it.date }
        assertEquals(ReviewedDayStatus.UNKNOWN_HOURS, days.getValue(d1).status)
        assertEquals(ReviewedDayStatus.EQUAL_TOTALS, days.getValue(d2).status)
    }

    @Test
    fun specificFieldMessagesDistinguishBlankFromInvalid() {
        val blank = row("r1", hours = "", blank = true)
        assertTrue(blank.fieldMessages().any { it.contains("صفر فرض نشده") })
        assertTrue(row("r2", hours = "الف").fieldMessages().any { it.contains("ساعت معتبر نیست") })
        assertTrue(row("r3", date = "").fieldMessages().any { it.contains("تاریخ") })
        assertTrue(row("r4", km = "").fieldMessages().any { it.contains("کیلومتر") })
        assertTrue(row("r5", count = "2").fieldMessages().any { it.contains("شمارهٔ ردیف") })
        assertNull(row("r6", hours = "الف").reviewed())
    }
}
