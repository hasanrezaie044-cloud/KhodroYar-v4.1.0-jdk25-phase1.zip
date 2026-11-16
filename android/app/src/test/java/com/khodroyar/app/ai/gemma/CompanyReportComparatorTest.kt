package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyReportComparatorTest {

    private fun report(vararg rows: CompanyReportParser.Row, totalKm: Double? = null, totalHours: Double? = null) =
        CompanyReportParser.ExtractedReport(rows.toList(), totalKm, totalHours, null, emptyList())

    private fun row(i: Int, date: String, km: Double, hours: Double = 8.0, amount: Double? = null) =
        CompanyReportParser.Row(i, date, "شرکت", "کارخانه", km, hours, "06:30", "14:30", "", amount, emptyList())

    private fun app(id: String, date: String, km: Double, hours: Double = 8.0, income: Long = 0) =
        CompanyReportComparator.AppServiceRow(id, date, km, hours, income)

    @Test fun perfectMatchProducesNoFindings() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0), row(2, "1405/08/11", 30.0)),
            listOf(app("aaaa0001", "1405/08/10", 24.0), app("aaaa0002", "1405/08/11", 30.0)),
        )
        assertEquals(2, r.matched)
        assertEquals(0, r.differs)
        assertEquals(0, r.missingInApp)
        assertEquals(0, r.extraInApp)
        assertTrue(CompanyReportComparator.formatPersian(r).contains("2 مطابق"))
    }

    @Test fun missingPaperRowIsReported() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0), row(2, "1405/08/12", 18.0)),
            listOf(app("aaaa0001", "1405/08/10", 24.0)),
        )
        assertEquals(1, r.matched)
        assertEquals(1, r.missingInApp)
        assertTrue(CompanyReportComparator.formatPersian(r).contains("ثبت نشده"))
    }

    @Test fun kmDifferenceIsQuantified() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0)),
            listOf(app("aaaa0001", "1405/08/10", 21.0)),
        )
        assertEquals(1, r.differs)
        assertEquals(0, r.matched)
        val rowCmp = r.rows.single()
        assertEquals(3.0, rowCmp.kmDiff!!, 0.001) // paper - app = +3
        assertTrue(CompanyReportComparator.formatPersian(r).contains("+3"))
    }

    @Test fun withinToleranceStillMatches() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.2, 8.1)),
            listOf(app("aaaa0001", "1405/08/10", 24.0, 8.0)),
        )
        assertEquals(1, r.matched)
    }

    @Test fun appOnlyRecordsInsideRangeAreExtraNotMissing() {
        // Paper covers 8/10 to 8/12; an app record on 8/11 sits INSIDE that range, so it is
        // an "extra in app" finding. (App records outside the paper's date range stay
        // untouched — a report only speaks for its own period.)
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0), row(2, "1405/08/12", 18.0)),
            listOf(
                app("aaaa0001", "1405/08/10", 24.0),
                app("aaaa0002", "1405/08/12", 18.0),
                app("aaaa0003", "1405/08/11", 40.0),
            ),
        )
        assertEquals(2, r.matched)
        assertEquals(1, r.extraInApp)
        assertTrue(CompanyReportComparator.formatPersian(r).contains("فقط در برنامه"))
    }

    @Test fun paperDuplicatesAreFlagged() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0), row(2, "1405/08/10", 24.0)),
            listOf(app("aaaa0001", "1405/08/10", 24.0)),
        )
        assertEquals(1, r.duplicatesPaper)
        assertEquals(1, r.matched) // first row matched, duplicate row flagged instead of double-matching
        assertTrue(r.rows.any { it.status == CompanyReportComparator.RowStatus.DUPLICATE_PAPER })
    }

    @Test fun appDuplicatesAreFlagged() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0)),
            listOf(app("aaaa0001", "1405/08/10", 24.0), app("aaaa0002", "1405/08/10", 24.0)),
        )
        assertEquals(1, r.duplicatesApp)
        assertTrue(r.rows.any { it.status == CompanyReportComparator.RowStatus.DUPLICATE_APP })
    }

    @Test fun printedTotalMismatchAddsNote() {
        val rep = report(row(1, "1405/08/10", 24.0), row(2, "1405/08/11", 12.0), totalKm = 100.0)
        val r = CompanyReportComparator.compare(rep, listOf(app("a1", "1405/08/10", 24.0), app("a2", "1405/08/11", 12.0)))
        assertEquals(false, r.printedTotalsOk)
        assertTrue(r.notes.any { it.contains("مجموع کیلومتر روی برگه") })
    }

    @Test fun printedTotalMatchIsPositive() {
        val rep = report(row(1, "1405/08/10", 24.0), totalKm = 24.0)
        val r = CompanyReportComparator.compare(rep, listOf(app("a1", "1405/08/10", 24.0)))
        assertEquals(true, r.printedTotalsOk)
    }

    @Test fun outOfRangeAppRecordsUntouched() {
        val r = CompanyReportComparator.compare(
            report(row(1, "1405/08/10", 24.0)),
            listOf(app("old", "1404/01/01", 500.0)),
        )
        // old record is outside the report's range: it is ignored (not even "extra")
        assertEquals(1, r.missingInApp)
        assertEquals(0, r.extraInApp)
    }
}
