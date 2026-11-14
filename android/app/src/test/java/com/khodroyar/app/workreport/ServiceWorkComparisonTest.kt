package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

class ServiceWorkComparisonTest {
    private val date = "1403/04/12"
    private fun company(id: String, km: String = "60", hours: String = "0", day: String = date, kind: ReportRowKind = ReportRowKind.SERVICE, count: String = "1") =
        ReportRow(id, "synthetic-page", 1, id.hashCode(), "synthetic service", day, count, km, hours, kind = kind, ordinal = id)
    private fun app(id: String, km: String = "60", hours: String = "0", day: String = date) =
        AppWorkService(id, day, km.toBigDecimal(), hours.toBigDecimal(), "شهر الف", "شهر ب", "07:00", "18:00", "آزمایشی")
    private fun compare(c: List<ReportRow>, a: List<AppWorkService>, links: List<ReportMatchLink> = emptyList()) =
        ServiceWorkComparison.compare(c, a, "1403/04/01", "1403/04/31", links)
    private fun link(c: List<ReportRow>, a: List<AppWorkService>) = ReportMatchLink(c.map { it.id }, a.map { it.id }, ServiceWorkComparison.fingerprint(a))

    @Test fun threeActualServicesAndTwoCompanyRowsIdentifyTheSpecificMissingService() {
        val actual = listOf(app("morning", "40", "0"), app("evening", "75", "0"), app("missing", "130", "4.5"))
        val result = compare(listOf(company("1", "40"), company("2", "75")), actual)
        val missing = result.services.single { it.status == ServiceMatchStatus.ONLY_IN_APP }
        assertEquals("missing", missing.app.single().id); assertEquals(date, missing.app.single().date)
        assertEquals("130", missing.app.single().km.toPlainString()); assertEquals("4.5", missing.app.single().hours.toPlainString())
        assertEquals(2, result.services.count { it.status == ServiceMatchStatus.MATCH })
    }
    @Test fun onlyCompanyRecordsAreDisplayedWithTheirSourceIdentity() {
        val result = compare(listOf(company("27", "90", "2.5")), emptyList())
        val match = result.services.single(); assertEquals(ServiceMatchStatus.ONLY_IN_COMPANY, match.status)
        assertEquals("27", match.company.single().ordinal); assertTrue(match.app.isEmpty())
    }
    @Test fun equalDayAndMonthTotalsDoNotConcealDifferentIndividualServices() {
        val result = compare(listOf(company("a", "50", "1"), company("b", "70", "2")), listOf(app("x", "40", "1"), app("y", "80", "2")))
        assertEquals(DayStatus.MATCH, result.days.single().status)
        assertTrue(result.services.none { it.status == ServiceMatchStatus.MATCH })
        assertEquals(ServiceMatchStatus.AMBIGUOUS, result.services.single().status)
    }
    @Test fun uniqueRemainingSameDayPairShowsBothKmAndHourDifferencesAsAProposal() {
        val result = compare(listOf(company("c", "62", "2.5")), listOf(app("a", "60", "3")))
        val match = result.services.single(); assertEquals(ServiceMatchStatus.DIFFERENT, match.status)
        assertTrue(match.note.contains("پیشنهاد")); assertFalse(match.manual)
        assertEquals("2", (match.companyTotals.km - match.appTotals.km).toPlainString())
        assertEquals("-0.5", (match.companyTotals.hours - match.appTotals.hours).toPlainString())
    }
    @Test fun hoursAreStoredWorkHoursNotElapsedStartEndDuration() {
        val actual = app("a", "60", "2.5").copy(startTime = "06:00", endTime = "22:00")
        assertEquals(ServiceMatchStatus.MATCH, compare(listOf(company("c", "60", "2.5")), listOf(actual)).services.single().status)
    }
    @Test fun indistinguishableMissingTripDoesNotInventWhichIdentityIsMissing() {
        val actual = listOf(app("route-a"), app("route-b").copy(origin = "شهر ج"))
        val match = compare(listOf(company("c")), actual).services.single()
        assertEquals(ServiceMatchStatus.AMBIGUOUS, match.status); assertEquals(2, match.app.size)
        assertTrue(match.note.contains("دقیقاً")); assertFalse(match.manual)
    }
    @Test fun equalMultiplicityOfIdenticalTripsIsComparedAsAMetricGroupNotArbitraryPairs() {
        val match = compare(listOf(company("1"), company("2")), listOf(app("a"), app("b"))).services.single()
        assertEquals(ServiceMatchStatus.MATCH, match.status); assertEquals(2, match.company.size); assertEquals(2, match.app.size)
        assertTrue(match.note.contains("دلخواه"))
    }
    @Test fun printedLegsVersusOneRoundTripAreAmbiguousUntilExplicitlyLinked() {
        val c = listOf(company("1", "55"), company("2", "55")); val a = listOf(app("return", "110"))
        assertEquals(ServiceMatchStatus.AMBIGUOUS, compare(c, a).services.single().status)
        val linked = compare(c, a, listOf(link(c, a))).services.single()
        assertEquals(ServiceMatchStatus.GROUPED, linked.status); assertTrue(linked.manual)
        assertTrue(linked.note.contains("نه هویت"))
    }
    @Test fun severalActualDaysCanBeExplicitlyCoveredByACompanySummaryWithoutDuplicatingThem() {
        val c = listOf(company("summary", "120", "6", kind = ReportRowKind.GROUPED))
        val a = listOf(app("first", "50", "2", "1403/04/02"), app("second", "70", "4", "1403/04/03"), app("not-covered", "25", "0", "1403/04/04"))
        val result = compare(c, a, listOf(link(c, a.take(2))))
        assertEquals(1, result.services.count { it.status == ServiceMatchStatus.GROUPED })
        assertEquals("not-covered", result.services.single { it.status == ServiceMatchStatus.ONLY_IN_APP }.app.single().id)
        assertFalse(result.companyServiceCountKnown)
        assertEquals(3, result.services.sumOf { it.app.size })
    }
    @Test fun unknownSummaryScopePreventsFalseDefinitiveMissingServiceClaims() {
        val result = compare(listOf(company("summary", "200", "0", kind = ReportRowKind.GROUPED)),
            listOf(app("first", "60", "0", "1403/04/02"), app("second", "140", "0", "1403/04/20")))
        assertEquals(ServiceMatchStatus.AMBIGUOUS, result.services.single().status)
        assertEquals(2, result.services.single().app.size); assertTrue(result.services.none { it.status == ServiceMatchStatus.ONLY_IN_APP })
    }
    @Test fun anExplicitGroupWithDifferentMetricsRemainsADiscrepancyNotAFakeMatch() {
        val c = listOf(company("summary", "125", "5", kind = ReportRowKind.GROUPED)); val a = listOf(app("a", "120", "4"))
        val match = compare(c, a, listOf(link(c, a))).services.single()
        assertEquals(ServiceMatchStatus.DIFFERENT, match.status); assertTrue(match.manual)
    }
    @Test fun explicitSameDayTieBreakRevealsTheOtherServiceWithoutGuessing() {
        val c = listOf(company("c")); val a = listOf(app("selected"), app("not-selected"))
        val result = compare(c, a, listOf(link(c, a.take(1))))
        assertEquals("not-selected", result.services.single { it.status == ServiceMatchStatus.ONLY_IN_APP }.app.single().id)
    }
    @Test fun manualDifferentDateLinkStillShowsADateDiscrepancy() {
        val c = listOf(company("c")); val a = listOf(app("a", day = "1403/04/13"))
        assertEquals(ServiceMatchStatus.DIFFERENT, compare(c, a, listOf(link(c, a))).services.single().status)
    }
    @Test fun firstAndLastOnlyAppDaysOfTheWholeMonthAreNotOmitted() {
        val result = compare(listOf(company("mid")), listOf(app("mid"), app("first", day = "1403/04/01"), app("last", day = "1403/04/31")))
        assertEquals(setOf("first", "last"), result.services.filter { it.status == ServiceMatchStatus.ONLY_IN_APP }.flatMap { it.app }.map { it.id }.toSet())
    }
    @Test fun sameMetricsOnDifferentDatesDoNotAutomaticallyMatch() {
        val result = compare(listOf(company("c")), listOf(app("a", day = "1403/04/13")))
        assertEquals(setOf(ServiceMatchStatus.ONLY_IN_APP, ServiceMatchStatus.ONLY_IN_COMPANY), result.services.map { it.status }.toSet())
    }
    @Test fun unreadableIsRejectedNotConvertedToZeroOrMissing() {
        assertTrue(runCatching { compare(listOf(company("c", hours = "")), listOf(app("a"))) }.isFailure)
        assertTrue(runCatching { compare(listOf(company("c", km = ".")), listOf(app("a"))) }.isFailure)
    }
    @Test fun excludedTotalAndRepeatedScanDoNotEnterServiceMatchingOrTotals() {
        val c = company("c"); val ignored = company("footer", "900").copy(included = false)
        val result = compare(listOf(c, ignored, c.copy(id = "duplicate", included = false, duplicateOf = c.id)), listOf(app("a")))
        assertEquals(1, result.company.count); assertEquals(1, result.services.size); assertEquals(1, result.companyRows.size)
    }
    @Test fun dailyAggregateIsNeverExpandedIntoInventedServices() {
        val c = company("daily", "120", "3", kind = ReportRowKind.DAILY_TOTAL, count = "2")
        val result = compare(listOf(c), listOf(app("a", "40", "1"), app("b", "80", "2")))
        assertEquals(ServiceMatchStatus.GROUPED, result.services.single().status)
        assertEquals(1, result.services.single().company.size); assertEquals(2, result.company.count)
    }
    @Test fun exactDecimalsAndHarmlessDatabaseFloatingPointNoiseAreHandled() {
        assertEquals(ServiceMatchStatus.MATCH, compare(listOf(company("c", "0.3", "1.5")), listOf(app("a", "0.30000000000000004", "1.5"))).services.single().status)
    }
    @Test fun staleManualLinkCannotSilentlyApproveChangedAppValues() {
        val c = listOf(company("c")); val a = listOf(app("a")); val approved = link(c, a)
        assertTrue(runCatching { compare(c, listOf(a.single().copy(hours = "2".toBigDecimal())), listOf(approved)) }.isFailure)
    }
    @Test fun overlappingOrMissingManualLinkIdsFailClosed() {
        val c = listOf(company("1"), company("2")); val a = listOf(app("a"), app("b"))
        assertTrue(runCatching { compare(c, a, listOf(link(c.take(1), a.take(1)), link(c.drop(1), a.take(1)))) }.isFailure)
        assertTrue(runCatching { compare(c, a, listOf(link(c, a).copy(companyIds = listOf("gone")))) }.isFailure)
    }
    @Test fun appIdentityAndReportRowsAreNotMutatedByComparisonOrManualLinks() {
        val c = listOf(company("c")); val a = listOf(app("a")); val beforeC = c.toList(); val beforeA = a.toList()
        compare(c, a, listOf(link(c, a))); assertEquals(beforeC, c); assertEquals(beforeA, a)
    }
    @Test fun manuallyEditedCompactPersianDatesUseTheSameCanonicalMatchingDay() {
        val result = compare(listOf(company("c", "62", "2", "۱۴۰۳۰۴۱۲")), listOf(app("a", "60", "3")))
        assertEquals(ServiceMatchStatus.DIFFERENT, result.services.single().status)
        assertEquals(date, result.services.single().company.single().date)
        val daily = company("daily", "120", "3", "۱۴۰۳/۴/۱۲", ReportRowKind.DAILY_TOTAL, "2")
        assertEquals(ServiceMatchStatus.GROUPED, compare(listOf(daily), listOf(app("a", "40", "1"), app("b", "80", "2"))).services.single().status)
    }
    @Test fun fingerprintsIgnoreListOrderButIncludeRecordIdentityAndReviewContext() {
        val a = listOf(app("a"), app("b"))
        assertEquals(ServiceWorkComparison.fingerprint(a), ServiceWorkComparison.fingerprint(a.reversed()))
        assertFalse(ServiceWorkComparison.fingerprint(a) == ServiceWorkComparison.fingerprint(a.map { it.copy(origin = "changed") }))
    }
}
