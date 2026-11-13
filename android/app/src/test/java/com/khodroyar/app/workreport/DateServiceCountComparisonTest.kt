package com.khodroyar.app.workreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic dates/counts only. No private company statement or DB writer. */
class DateServiceCountComparisonTest {
    private val start = "1405/05/01"
    private val end = "1405/05/31"
    private fun row(id: String, date: String = "1405/05/03", included: Boolean = true,
        kind: ReportRowKind = ReportRowKind.SERVICE) = ReportRow(
        id, "p1", 1, 0, "synthetic", date = date, count = "1", included = included,
        kind = kind, ordinal = "99", hoursBlank = true,
    )

    @Test fun twoCompanyRowsAndFiveActualServicesHaveDifferencePlusThree() {
        val rows = listOf(row("r1"), row("r2")) // km/hours intentionally unreadable
        val result = DateServiceCountComparison.compare(rows, List(5) { "1405/05/03" }, start, end).single()
        assertEquals("1405/05/03", result.date)
        assertEquals(2, result.companyCount)
        assertEquals(5, result.appCount)
        assertEquals(3, result.difference)
        assertTrue(rows.all { it.km.isBlank() && it.hours.isBlank() })
    }

    @Test fun equalCountsDoNotClaimIndividualIdentityOrRequireMetrics() {
        val result = DateServiceCountComparison.compare(listOf(row("r1"), row("r2")),
            listOf("1405/05/03", "1405/05/03"), start, end).single()
        assertEquals(0, result.difference)
        assertEquals(2, result.companyCount) // NOT ordinal 99, nor a km/hour total
    }

    @Test fun fullPeriodIncludesAppOnlyDatesAtBothEndsAndSortsTheUnion() {
        val result = DateServiceCountComparison.compare(listOf(row("r1")),
            listOf(end, start, "1405/05/02"), start, end)
        assertEquals(listOf(start, "1405/05/02", "1405/05/03", end), result.map { it.date })
        assertEquals(listOf(0, 0, 1, 0), result.map { it.companyCount })
        assertEquals(listOf(1, 1, 0, 1), result.map { it.appCount })
    }

    @Test fun exclusionsInvalidDatesAndGroupedOrDailyRowsAreNotIndividualServices() {
        val rows = listOf(row("r1"), row("excluded", included = false), row("invalid", "1405/13/03"),
            row("group", kind = ReportRowKind.GROUPED), row("daily", kind = ReportRowKind.DAILY_TOTAL))
        val result = DateServiceCountComparison.compare(rows, emptyList(), start, end).single()
        assertEquals(1, result.companyCount)
        assertEquals(-1, result.difference)
    }

    @Test fun persianArabicUnpaddedAndCompactDatesShareCanonicalDay() {
        val rows = listOf(row("r1", "۱۴۰۵/۵/۳"), row("r2", "14050503"))
        val result = DateServiceCountComparison.compare(rows, listOf("١٤٠٥/٠٥/٠٣"), "۱۴۰۵/۰۵/۰۱", "۱۴۰۵/۰۵/۳۱").single()
        assertEquals("1405/05/03", result.date)
        assertEquals(2, result.companyCount)
        assertEquals(1, result.appCount)
    }

    @Test fun neitherSideLeaksValuesOutsideTheSelectedRange() {
        val result = DateServiceCountComparison.compare(listOf(row("r1"), row("other-month", "1405/06/03")),
            listOf("1405/04/31", "1405/06/01", "bad", "1405/05/03"), start, end).single()
        assertEquals(0, result.difference)
        assertEquals(1, result.companyCount)
        assertEquals(1, result.appCount)
    }

    @Test fun editingOrExcludingADateRecomputesWithoutMutatingInputRows() {
        val original = listOf(row("r1"), row("r2"))
        val copy = original.toList()
        val edited = listOf(original[0].copy(date = "1405/05/04"), original[1].copy(included = false))
        val result = DateServiceCountComparison.compare(edited, listOf("1405/05/03"), start, end)
        assertEquals(listOf("1405/05/03", "1405/05/04"), result.map { it.date })
        assertEquals(listOf(1, -1), result.map { it.difference })
        assertEquals(copy, original)
    }

    @Test fun monthlyRangeUsesMonthNotFirstServiceDayEvenWhenDayExceedsTwelve() {
        assertEquals(start to end, DateServiceCountComparison.suggestedRange(listOf(row("r1", "1405/05/28"))))
        assertEquals(start to end, DateServiceCountComparison.suggestedRange(listOf(row("r1", "1405/05/02"))))
    }

    @Test fun monthLengthAndLeapYearAreRespected() {
        assertEquals("1403/12/01" to "1403/12/30",
            DateServiceCountComparison.suggestedRange(listOf(row("r1", "1403/12/28"))))
        assertEquals("1404/12/01" to "1404/12/29",
            DateServiceCountComparison.suggestedRange(listOf(row("r1", "1404/12/28"))))
    }

    @Test fun mixedMonthRangeDoesNotInventOneMonth() {
        assertEquals("1405/04/31" to "1405/05/03", DateServiceCountComparison.suggestedRange(
            listOf(row("r1", "1405/05/03"), row("r2", "1405/04/31"))))
    }

    @Test fun noIncludedValidDateMeansNoSuggestedRange() {
        assertNull(DateServiceCountComparison.suggestedRange(emptyList()))
        assertNull(DateServiceCountComparison.suggestedRange(listOf(row("r1", included = false), row("r2", "bad"))))
    }

    @Test(expected = IllegalArgumentException::class) fun reversedRangeFailsClosed() {
        DateServiceCountComparison.compare(listOf(row("r1")), emptyList(), end, start)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidRangeFailsClosed() {
        DateServiceCountComparison.compare(listOf(row("r1")), emptyList(), "bad", end)
    }
    @Test fun sameDayGroupedCardexDoesNotReplaceTheTwoExplicitServiceRowsOrInventAThird() {
        val text = "1 1405/05/11 کارتکس نیمه اول 300 .\n" +
            "6 1405/05/10 شهر الف شهر ب 40 .\n7 1405/05/10 شهر ب شهر الف 80 .\n" +
            "8 1405/05/11 شهر الف شهر ب 40 .\n9 1405/05/11 شهر ب شهر الف 80 ."
        val rows = FastOcrReportText.parsePage("p", 1, text)
        val compared = DateServiceCountComparison.compare(rows, List(3) { "1405/05/10" } + List(3) { "1405/05/11" }, start, end)
        assertEquals(5, rows.size)
        assertEquals(1, rows.count { it.kind == ReportRowKind.GROUPED })
        assertEquals(listOf(2, 2), compared.map { it.companyCount })
        assertEquals(listOf(3, 3), compared.map { it.appCount })
        assertEquals(listOf(1, 1), compared.map { it.difference })
    }
    @Test fun explicitDailyCorrectionIsLabelledAndNeverManufacturesRowsOrChangesAppDates() {
        val rows = listOf(row("r1"), row("r2"))
        val app = List(3) { "1405/05/03" }
        val corrected = DateServiceCountComparison.compare(rows, app, start, end, mapOf("1405/05/03" to 4)).single()
        assertEquals(4, corrected.companyCount)
        assertEquals(2, corrected.extractedCount)
        assertTrue(corrected.manuallyCorrected)
        assertEquals(-1, corrected.difference)
        assertEquals(2, rows.size)
        assertEquals(3, app.size)
        val original = DateServiceCountComparison.compare(rows, app, start, end).single()
        assertEquals(2, original.companyCount)
        assertEquals(1, original.difference)
    }
    @Test fun noIncludedCompanyServiceStillShowsAppOnlyDaysInsteadOfHidingComparison() {
        val result = DateServiceCountComparison.compare(listOf(row("r1", included = false)), listOf(start, end), start, end)
        assertEquals(listOf(start, end), result.map { it.date })
        assertEquals(listOf(0, 0), result.map { it.companyCount })
        assertEquals(listOf(1, 1), result.map { it.difference })
    }
    @Test fun explicitZeroAndAManualOnlyDateArePreservedWithoutPretendingTheyWereRead() {
        val result = DateServiceCountComparison.compare(emptyList(), listOf(start), start, end, mapOf(start to 0, end to 2))
        assertEquals(listOf(start, end), result.map { it.date })
        assertEquals(listOf(0, 2), result.map { it.companyCount })
        assertTrue(result.all { it.manuallyCorrected && it.extractedCount == 0 })
    }
    @Test(expected = IllegalArgumentException::class) fun negativeManualCountIsRejectedNotCoerced() {
        DateServiceCountComparison.compare(emptyList(), emptyList(), start, end, mapOf(start to -1))
    }

}
