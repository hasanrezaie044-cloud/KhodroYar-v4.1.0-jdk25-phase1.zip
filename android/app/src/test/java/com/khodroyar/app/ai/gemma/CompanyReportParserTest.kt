package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyReportParserTest {

    private fun ok(raw: String): CompanyReportParser.ExtractedReport {
        val r = CompanyReportParser.parse(raw)
        assertTrue("expected Ok but was $r", r is CompanyReportParser.ParseResult.Ok)
        return (r as CompanyReportParser.ParseResult.Ok).report
    }

    private fun empty(raw: String) {
        val r = CompanyReportParser.parse(raw)
        assertTrue("expected Empty but was $r", r is CompanyReportParser.ParseResult.Empty)
    }

    @Test fun cleanTableParses() {
        val r = ok(
            """
            {"rows":[
              {"date":"1405/08/10","origin":"شرکت","destination":"کارخانه","km":24.5,"hours":8,"start":"06:30","end":"14:30","amount":410000},
              {"date":"1405/08/11","origin":"کارخانه","destination":"شرکت","km":22,"hours":7.5,"amount":390000}
            ],"total_km":46.5,"total_hours":15.5,"total_amount":800000}
            """.trimIndent(),
        )
        assertEquals(2, r.rows.size)
        assertEquals("1405/08/10", r.rows[0].date)
        assertEquals(24.5, r.rows[0].km!!, 0.0)
        assertEquals(8.0, r.rows[0].hours!!, 0.0)
        assertEquals("06:30", r.rows[0].startTime)
        assertEquals(410000.0, r.rows[0].amount!!, 0.0)
        assertEquals(46.5, r.printedTotalKm!!, 0.0)
        assertEquals(46.5, r.sumKm, 0.0)
        assertEquals(800000.0, r.printedTotalAmount!!, 0.0)
    }

    @Test fun persianDigitsEverywhereAreRead() {
        val r = ok("""{"rows":[{"date":"۱۴۰۵/۰۸/۱۰","km":"۲۴٫۵","hours":"۸","amount":"۴۱۰٬۰۰۰"}],"total_km":"۲۴٫۵"}""")
        assertEquals("1405/08/10", r.rows[0].date)
        assertEquals(24.5, r.rows[0].km!!, 0.001)
        assertEquals(24.5, r.printedTotalKm!!, 0.001)
        assertEquals(410000.0, r.rows[0].amount!!, 0.0)
    }

    @Test fun impossibleDatesAreNulledAndWarned() {
        val r = ok("""{"rows":[{"date":"1405/13/40","km":10},{"date":"1405/08/12","km":5}]}""")
        assertNull(r.rows[0].date)
        assertEquals("1405/08/12", r.rows[1].date)
        assertTrue(r.warnings.any { it.contains("ردیف 1") })
    }

    @Test fun twoDigitAndSwappedYearsAreNormalised() {
        assertEquals("1405/08/12", CompanyReportParser.normalizeJalaliDate("05/08/12"))
        assertEquals("1405/08/12", CompanyReportParser.normalizeJalaliDate("1405-08-12"))
        assertEquals("1405/08/12", CompanyReportParser.normalizeJalaliDate("12/08/1405"))
        assertNull(CompanyReportParser.normalizeJalaliDate("2025/08/12"))
    }

    @Test fun nullAmountStaysNullNotZero() {
        val r = ok("""{"rows":[{"date":"1405/08/10","km":10,"amount":null}]}""")
        assertNull(r.rows[0].amount)
    }

    @Test fun missingTableIsEmptyResult() {
        empty("""{"rows":[],"notes":["جدول دیده نمی‌شود"]}""")
        empty("این یک متن ساده است بدون جدول")
        empty("""{"action":"reply"}""")
    }

    @Test fun corruptJsonIsEmptyResult() = empty("""{"rows":[{"date":"1405/08/10""""")

    @Test fun wrappedProseIsIgnored() {
        val r = ok("""البته، این استخراج من است: {"rows":[{"date":"1405/08/10","km":12}]} امیدوارم درست باشد""")
        assertEquals(1, r.rows.size)
        assertEquals(12.0, r.rows[0].km!!, 0.0)
    }
}
