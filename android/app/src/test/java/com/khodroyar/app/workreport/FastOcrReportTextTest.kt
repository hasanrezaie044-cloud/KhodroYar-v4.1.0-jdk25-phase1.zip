package com.khodroyar.app.workreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FastOCR text parser must NEVER invent a value: only an unambiguous
 * "date + exactly two numbers" line assigns km/hours; skip-labeled totals lines
 * never become rows; everything else leaves cells blank for manual review.
 */
class FastOcrReportTextTest {
    @Test fun cleanTemplateRowBecomesFullyAssigned() {
        val rows = FastOcrReportText.parsePage("p1", 1, "گزارش عملکرد ماهانه\n۱ ۱۴۰۴/۰۹/۱۰ ۱۲۳٫۵ ۱۲:۱۵")
        val row = rows.single()
        assertEquals("1404/09/10", row.date)
        assertEquals("123.5", row.km)
        assertEquals("12:15", row.hours)
        assertEquals("1", row.ordinal)
        assertFalse(row.note.contains("مجهول"))
        assertTrue(row.included)
    }

    @Test fun datePartsNeverLeakIntoKmOrHours() {
        // A line whose ONLY numbers belong to the date: km/hours must stay empty.
        val rows = FastOcrReportText.parsePage("p1", 1, "۱۴۰۴/۰۹/۱۰")
        val row = rows.single()
        assertEquals("1404/09/10", row.date)
        assertEquals("", row.km)
        assertEquals("", row.hours)
    }

    @Test fun ambiguousExtraTokensLeaveNumericFieldsBlank() {
        val rows = FastOcrReportText.parsePage("p1", 1, "۳ ۱۴۰۴/۰۹/۱۲ ۴۵ ۸:۳۰ ۲۵۰۰")
        val row = rows.single()
        assertEquals("1404/09/12", row.date)
        assertEquals("", row.km)   // three numbers after ordinal: NOT guessable
        assertEquals("", row.hours)
        assertEquals("", row.ordinal) // ambiguous line: even the ordinal is not trusted
        assertTrue(row.note.contains("آشکار نیست"))
    }

    @Test fun totalsAndSalaryLinesNeverBecomeRows() {
        val text = "۱۴۰۴/۰۹/۱۰ ۱۰۰ ۵:۰۰\nجمع ۱۴۰۴/۰۹/۳۰ ۹۹۹۹ ۸:۰۰\nحقوق ۱۴۰۴/۰۹/۱۵ ۵۰۰۰۰۰۰\nمجموع ماه ۱۴۰۴/۰۹/۳۰"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(1, rows.size)
        assertEquals("1404/09/10", rows[0].date)
    }

    @Test fun missingHoursStaysBlankNeverZero() {
        val rows = FastOcrReportText.parsePage("p1", 1, "۲ ۱۴۰۴/۰۹/۱۱ ۲۰۰")
        val row = rows.single()
        assertEquals("", row.km)     // one token after ordinal cannot be trusted as km+hours pair
        assertEquals("", row.hours)
        assertFalse(row.hours == "0")
        assertTrue(row.note.isNotBlank())
    }

    @Test fun blankDashMarkersAreRememberedAsUnknownHours() {
        val rows = FastOcrReportText.parsePage("p1", 1, "۱۴۰۴/۰۹/۱۰ ۱۲۳ -")
        val row = rows.single()
        assertTrue(row.hoursBlank)
        assertEquals("", row.hours)
    }

    @Test fun headerLinesWithoutAnyNumberAreDropped() {
        val rows = FastOcrReportText.parsePage("p1", 1, "شرکت نفت و گاز\nگزارش عملکرد ماهانه راننده\nنام راننده")
        assertTrue(rows.isEmpty())
    }

    @Test fun idsAreStableAndPerPage() {
        val a = FastOcrReportText.parsePage("p1", 1, "۱۴۰۴/۰۹/۱۰ ۱ ۲")
        val b = FastOcrReportText.parsePage("p2", 2, "۱۴۰۴/۰۹/۱۰ ۱ ۲")
        assertTrue(a[0].id.startsWith("p1:fastocr:"))
        assertTrue(b[0].id.startsWith("p2:fastocr:"))
        assertEquals(1, a[0].page)
        assertEquals(2, b[0].page)
    }

    @Test fun persianAndArabicDigitsBothRead() {
        val rows = FastOcrReportText.parsePage("p1", 1, "١٤٠٤/٠٩/١٠ ٥٠ ٧:٣٠")
        assertEquals("1404/09/10", rows[0].date)
        assertEquals("50", rows[0].km)
        assertEquals("7:30", rows[0].hours)
    }

    @Test(expected = IllegalArgumentException::class)
    fun absurdlyLongInputRejected() {
        FastOcrReportText.parsePage("p1", 1, "x".repeat(FastOcrClient.MAX_TEXT_BYTES + 1))
    }
}
