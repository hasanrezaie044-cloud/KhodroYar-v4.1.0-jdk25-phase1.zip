package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixed-form layouts only; not live OCR accuracy or the user's private scan. */
class FastOcrDatedRowCoverageTest {
    private fun parse(text: String) = FastOcrReportText.parsePage("synthetic", 1, text)

    @Test fun routeAndValidDateAreEnoughWhenOrdinalKmAndHoursWereNotRead() {
        for (text in listOf(
            "1405/07/05 اصفهان فولاد . .\n1405/07/05 فولاد اصفهان . .",
            ". . فولاد اصفهان 1405/07/05\n. . اصفهان فولاد 1405/07/05",
        )) {
            val rows = parse(text)
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.date == "1405/07/05" && it.count == "1" && it.km.isBlank() && it.hours.isBlank() })
        }
    }

    @Test fun dateOnlyCellsInsideTheActualTableAreNotDroppedOrDeduplicatedByDay() {
        val rows = parse("""
            تاریخ گزارش: 1405/08/01 کد راننده: 245
            ردیف تاریخ مبدأ مقصد کیلومتر ساعت سری
            1405/07/05
            1405/07/05
            1405/07/06
            جمع 4900 140
            حقوق 1405/07/30 50000000
        """.trimIndent())
        assertEquals(listOf("1405/07/05", "1405/07/05", "1405/07/06"), rows.map { it.date })
        assertEquals(3, rows.map { it.id }.distinct().size)
        assertTrue(rows.all { it.km.isBlank() && it.hours.isBlank() })
    }

    @Test fun anIsolatedTitleDateWithoutServiceOrTableContextIsNotInventedAsAService() {
        assertTrue(parse("گزارش شرکت\n1405/07/05").isEmpty())
        assertTrue(parse("تاریخ گزارش: 1405/07/05 کد راننده: 245").isEmpty())
    }

    @Test fun validServiceDatesInAMinorityMonthAreNotSuppressedByAMonthVote() {
        val rows = parse("7 1405/06/31 اصفهان فولاد 40 .\n8 1405/07/01 فولاد اصفهان 80 .\n9 1405/07/02 اصفهان فولاد 40 .")
        assertEquals(listOf("1405/06/31", "1405/07/01", "1405/07/02"), rows.map { it.date })
    }

    @Test fun legitimatePlaceWordsDoNotBecomeFinancialFooterCutsThatEraseTheDatedService() {
        val rows = parse("1405/07/05 هتل عباسی فولاد . .\n1405/07/05 عوارضی اصفهان فولاد . .\n1405/07/06شرکت فولاد اصفهان . .")
        assertEquals(listOf("1405/07/05", "1405/07/05", "1405/07/06"), rows.map { it.date })
    }

    @Test fun threeDigitAndGluedOrdinalsAreStillOneServiceEach() {
        val rows = parse("1001405/07/05 اصفهان فولاد . .\n101 1405/07/05 فولاد اصفهان . .")
        assertEquals(2, rows.size)
        assertEquals(listOf("100", "101"), rows.map { it.ordinal })
        assertTrue(rows.all { it.count == "1" })
    }

    @Test fun labelledMultilineRecordsDoNotConfuseDateCellsWithThePrintingDate() {
        val rows = parse("""
            تاریخ چاپ: 1405/08/01
            ردیف: 7
            تاریخ: 1405/07/05
            مبدأ: اصفهان
            مقصد: فولاد
            کیلومتر: .
            ساعت: .
            ردیف: 8
            تاریخ: 1405/07/05
            مبدأ: فولاد
            مقصد: اصفهان
            کیلومتر: .
            ساعت: .
        """.trimIndent())
        assertEquals(2, rows.size)
        assertEquals(listOf("7", "8"), rows.map { it.ordinal })
        assertTrue(rows.all { it.date == "1405/07/05" })
    }

    @Test fun htmlTableCellsAndEmptyMetricsKeepEveryDatedService() {
        val rows = parse("""
            <table><thead><tr><th>ردیف</th><th>تاریخ</th><th>مبدأ</th><th>مقصد</th><th>کیلومتر</th><th>ساعت</th></tr></thead><tbody>
            <tr><td>7</td><td>۱۴۰۵/۰۷/۰۵</td><td>اصفهان</td><td>فولاد</td><td>&nbsp;</td><td>.</td></tr>
            <tr><td>8</td><td>۱۴۰۵/۰۷/۰۵</td><td>فولاد</td><td>اصفهان</td><td></td><td></td></tr>
            </tbody></table>
        """.trimIndent())
        assertEquals(2, rows.size)
        assertEquals(listOf("7", "8"), rows.map { it.ordinal })
        assertTrue(rows.all { it.date == "1405/07/05" && it.km.isBlank() && it.hours.isBlank() })
    }

    @Test fun unambiguousSplitDateDigitsAreJoinedWithoutChangingAnyDigit() {
        val rows = parse("7 1 4 0 5 / 0 7 / 0 5 اصفهان فولاد . .\n8 1405/07/06 فولاد اصفهان . .")
        assertEquals(listOf("1405/07/05", "1405/07/06"), rows.map { it.date })
        assertEquals("1405/07/1 1", FastOcrReportText.normalizeLayout("1405 / 07 / 1 1"))
    }

    @Test fun invalidJalaliDatesNeverBecomeRowsOrGetRepairedFromOtherDays() {
        val rows = parse("7 1405/07/31 اصفهان فولاد 80 .\n8 1405/13/01 فولاد اصفهان 40 .\n9 1405/07/30 اصفهان فولاد . .")
        assertEquals(listOf("1405/07/30"), rows.map { it.date })
    }

    @Test fun exactUserExampleComparesCountsWithoutAnyMetricEntryOrAppDerivedCompanyRows() {
        val rows = parse("1405/07/05 اصفهان فولاد . .\n1405/07/05 فولاد اصفهان . .")
        val result = DateServiceCountComparison.compare(rows, List(3) { "1405/07/05" }, "1405/07/01", "1405/07/30").single()
        assertEquals("1405/07/05", result.date)
        assertEquals(2, result.companyCount)
        assertEquals(3, result.appCount)
        assertEquals(1, result.difference)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.km.isBlank() && it.hours.isBlank() })
    }
    @Test fun labelledDateWithoutOrdinalAndShortCityNamesStillRetainDatedServices() {
        assertEquals(2, parse("1405/07/05 ری قم . .\n1405/07/05 قم ری . .").size)
        val rows = parse("تاریخ: 1405/08/01\nتاریخ: 1405/07/05\nمبدأ: ری\nمقصد: قم\nتاریخ: 1405/07/05\nمبدأ: قم\nمقصد: ری")
        assertEquals(listOf("1405/07/05", "1405/07/05"), rows.map { it.date })
    }

}
