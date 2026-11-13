package com.khodroyar.app.workreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed-template contract (Sepahan statement + KhodroYar vehicle report):
 * a date anchors one service row; ordinal/km/hours are read by position and
 * domain ranges; serials, money and footers never leak; unknown stays blank.
 * Date + row count is the mandatory output — km/hours are bonus.
 */
class FastOcrReportTextTest {
    @Test fun sepahanRowInRtlLogicalOrder() {
        // ردیف، تاریخ، مبداء، مقصد، کیلومتر، ساعات، …، سری
        val rows = FastOcrReportText.parsePage("p1", 1, "1 1405/05/02 بهارستان فولاد و بالعکس 140 . . . . . 684")
        val row = rows.single()
        assertEquals("1405/05/02", row.date)
        assertEquals("1", row.ordinal)
        assertEquals("140", row.km)          // km wins by distance, NOT the 684 serial
        assertEquals("", row.hours)          // "." blank hour stays blank — never zero
        assertTrue(row.hoursBlank)
    }

    @Test fun sepahanRowInVisualLtrOrder() {
        // FastOCR may emit visually: سری … کیلومتر مقصد مبداء تاریخ ردیف
        val rows = FastOcrReportText.parsePage("p1", 1, "684 . . . . . 140 فولاد و بالعکس بهارستان 1405/05/02 1")
        val row = rows.single()
        assertEquals("1", row.ordinal)
        assertEquals("140", row.km)
        assertEquals("", row.hours)
    }

    @Test fun sepahanRowWithHoursAndKmReadsBoth() {
        val rows = FastOcrReportText.parsePage("p1", 1, "1640 . . . . 15 240 فولاد و بالعکس اصفهان 1405/05/12 10")
        val row = rows.single()
        assertEquals("10", row.ordinal)
        assertEquals("240", row.km)
        assertEquals("15", row.hours)
    }

    @Test fun sepahanLargeKmAndNoHours() {
        val rows = FastOcrReportText.parsePage("p1", 1, "1241 . . . . . 1120 کارتکس نیمه اول 1405/05/11 2")
        val row = rows.single()
        assertEquals("1120", row.km)
        assertEquals("", row.hours)
    }

    @Test fun footerTotalsAndSalaryNeverBecomeRows() {
        val text = """
            1 1405/05/02 بهارستان فولاد و بالعکس 140 . . . . . 684
            2 1405/05/03 اصفهان فولاد و بالعکس 240 15 . . . . . 1241
            جمع 4900 140
            حقوق: 999,318,596 پاداش: 0 معوقه: 0
            خالص دریافتی: 971,619,162 جمع کسور: 27,699,424
        """.trimIndent()
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(2, rows.size)
        assertEquals("1405/05/02", rows[0].date)
        assertEquals("1405/05/03", rows[1].date)
        assertEquals("140", rows[0].km)   // جمع's 4900 must NOT pollute row 2…
        assertEquals("240", rows[1].km)   // …nor its own window
    }

    @Test fun headerReportDateInOtherMonthIsNotARow() {
        val text = "شرکت آتیه تعاون سپاهان تاریخ گزارش: 1405/06/17 کد راننده: 245\n" +
            "1 1405/05/02 بهارستان فولاد و بالعکس 140 . 684\n" +
            "2 1405/05/03 اصفهان فولاد 240 15 1241\n" +
            "3 1405/05/03 فولاد اصفهان 80 . 682"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(3, rows.size) // 1405/06/17 header date dropped
        assertTrue(rows.all { it.date.startsWith("1405/05") })
        assertEquals(2, rows.count { it.date == "1405/05/03" }) // count-per-date input
    }

    @Test fun bareTitleDatesAreNotRows() {
        val text = "گزارش کارکرد خودرو\n1405/05/31 تا 1405/05/01"
        assertTrue(FastOcrReportText.parsePage("p1", 1, text).isEmpty())
    }

    @Test fun vehicleReportTimeRangeRow() {
        val rows = FastOcrReportText.parsePage("p1", 1, "1405/05/02تعطیل --:-- تا 07:00 120بهارستان فولاد 0 2,148,000")
        val row = rows.single()
        assertEquals("1405/05/02", row.date)
        assertEquals("120", row.km)
        assertEquals("", row.hours) // "--:--" start: duration UNKNOWN, never zero
        assertTrue(row.hoursBlank)
    }

    @Test fun vehicleRangeWrapsPastMidnight() {
        val rows = FastOcrReportText.parsePage("p1", 1, "1405/05/12ثابت 16:00 تا 06:00 120شیخ،فولاد شیخ 0 3,202,992")
        val row = rows.single()
        assertEquals("120", row.km)
        assertEquals("14:00", row.hours)
    }

    @Test fun moneyWithThousandsSeparatorsNeverBecomesValues() {
        val rows = FastOcrReportText.parsePage("p1", 1, "ثابت3,080\n194\n20\n0\n80,235,986\n105,062,802\nجمع کل 4,640 194 39 0")
        assertTrue(rows.isEmpty())
    }

    @Test fun adjacentRowsKeepSeparateWindows() {
        val text = "1405/05/05شب --:-- تا --:--\n80فولاد\n1405/05/06ثابت--:-- تا 06:00\n160حکیم"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(2, rows.size)
        assertEquals("80", rows[0].km)
        assertEquals("160", rows[1].km)
        assertEquals("1405/05/06", rows[1].date)
    }

    @Test fun persianAndArabicDigitsBothRead() {
        val rows = FastOcrReportText.parsePage("p1", 1, "١٤٠٤/٠٩/١٠ ٥٠ ٧:٣٠ مسیر")
        val row = rows.single()
        assertEquals("1404/09/10", row.date)
        assertEquals("50", row.ordinal) // adjacent small number to the date is ردیف
        assertEquals("7:30", row.hours)
    }

    @Test fun ordinalGluedBeforeDateAlsoWorks() {
        val rows = FastOcrReportText.parsePage("p1", 1, "7 1405/05/04 اصفهان فولاد و بالعکس 80 8 . 682")
        val row = rows.single()
        assertEquals("7", row.ordinal)
        assertEquals("80", row.km)
        assertEquals("8", row.hours)
    }

    @Test fun incomeTailsNeverMasqueradeAsOrdinalsOrDropFirstVehicleRows() {
        val text = "1405/05/02تعطیل--:-- تا 07:00\n120شهر الف شهر ب 0\n2,148,000\n" +
            "1405/05/03ثابت--:-- تا --:--\n160شهر الف شهر ب 0\n4,154,530\n" +
            "1405/05/04ثابت--:-- تا --:--\n160شهر الف شهر ب 0\n4,154,530\n"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(listOf("1405/05/02", "1405/05/03", "1405/05/04"), rows.map { it.date })
        assertEquals(listOf("120", "160", "160"), rows.map { it.km })
        assertTrue(rows.all { it.ordinal.isBlank() && it.count == "1" && it.hours.isBlank() })
    }

    @Test fun repeatedTitleDatesAndSummaryAreNotExtraRowsOnContinuationPages() {
        val text = "گزارش کارکرد خودرو\n1405/05/31 تا 1405/05/01\n" +
            "خلاصه بر اساس نوع سرویس\nثابت3,080\n194\n20\n0\n80,235,986\n" +
            "جمع کل4,640 194 39 0 105,062,802\nجزئیات سرویس ها\nتاریخ نوع ساعت کیلومتر مبدا مقصد درآمد\n" +
            "1405/05/02تعطیل--:-- تا --:--\n120شهر الف شهر ب 0 2,148,000\n" +
            "1405/05/31ثابت--:-- تا --:--\n160شهر الف شهر ب 0 4,154,530\n" +
            "1405/05/31شب--:-- تا --:--\n80شهر ب شهر الف 0 1,206,320"
        val rows = FastOcrReportText.parsePage("p2", 2, text)
        assertEquals(3, rows.size)
        assertEquals(2, rows.count { it.date == "1405/05/31" })
        assertTrue(rows.none { it.date == "1405/05/01" })
        assertEquals(listOf("120", "160", "80"), rows.map { it.km })
    }

    @Test fun sameMonthHeaderAndWrappedVisualLtrRowsStaySeparate() {
        val text = "تاریخ گزارش: 1405/05/30 کد راننده: 245\n" +
            "684 . . . 140\nشهر ب شهر الف 1405/05/02 1\n" +
            "1241 . . 15 240\nشهر ب شهر الف 1405/05/03 2\n" +
            "682 . . . 80\nشهر الف شهر ب 1405/05/03 3\nجمع 4900 140"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(3, rows.size)
        assertEquals(listOf("1", "2", "3"), rows.map { it.ordinal })
        assertEquals(listOf("140", "240", "80"), rows.map { it.km })
        assertEquals(listOf("", "15", ""), rows.map { it.hours })
        assertFalse(rows[1].hoursBlank)
    }

    @Test fun wrappedLogicalRtlRowsKeepTheirOwnKmAndNumericHours() {
        val text = "1 1405/05/02\nشهر الف شهر ب\n140\n. . . 684\n" +
            "2 1405/05/03\nشهر ب شهر الف\n80 8 . . 682\nحقوق: 999,318,596"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(listOf("140", "80"), rows.map { it.km })
        assertEquals(listOf("", "8"), rows.map { it.hours })
        assertFalse(rows[1].hoursBlank)
    }

    @Test fun dotHourDoesNotBecomeASmallSerialNumber() {
        val row = FastOcrReportText.parsePage("p1", 1, "1 1405/05/02 شهر الف شهر ب 140 . . . . 7").single()
        assertEquals("140", row.km)
        assertEquals("", row.hours)
        assertTrue(row.hoursBlank)
    }

    @Test fun dateAndOrdinalSurviveUnreadableOptionalValues() {
        val row = FastOcrReportText.parsePage("p1", 1, "7 1405/05/03 شهر الف شهر ب . . .").single()
        assertEquals("1405/05/03", row.date)
        assertEquals("1", row.count) // NOT the ordinal 7
        assertEquals("", row.km)
        assertEquals("", row.hours)
    }

    @Test fun explicitVehicleTypeSurvivesMissingKmAndHours() {
        val row = FastOcrReportText.parsePage("p1", 1, "1405/05/03ثابت --:-- تا --:-- شهر الف شهر ب").single()
        assertEquals("1", row.count)
        assertEquals("", row.km)
        assertEquals("", row.hours)
        assertTrue(row.hoursBlank)
    }

    @Test fun variableWidthAndCompactDateSpansDoNotShiftColumnCoordinates() {
        val rows = FastOcrReportText.parsePage("p1", 1,
            "1 1405/5/2 شهر الف شهر ب 140 . . 684\n2 14050503 شهر ب شهر الف 240 15 . 1241")
        assertEquals(listOf("1405/05/02", "1405/05/03"), rows.map { it.date })
        assertEquals(listOf("140", "240"), rows.map { it.km })
        assertEquals(listOf("", "15"), rows.map { it.hours })
    }

    @Test fun groupedDescriptionsStillRequireExplicitManualCoverage() {
        val row = FastOcrReportText.parsePage("p1", 1, "2 1405/05/11 کارتکس نیمه اول 1120 . . 1241").single()
        assertEquals(ReportRowKind.GROUPED, row.kind)
        assertEquals("1120", row.km)
        assertEquals("", row.hours)
    }

    @Test fun arabicThousandsSeparatorIsAlsoMoneyNotKmOrHours() {
        val row = FastOcrReportText.parsePage("p1", 1, "1 1405/05/03 شهر الف شهر ب . . 4٬154٬530").single()
        assertEquals("", row.km)
        assertEquals("", row.hours)
    }

    @Test fun presentationFormsAndCompleteRangeRetainKnownHours() {
        val row = FastOcrReportText.parsePage("p1", 1,
            "۱۴۰۵/۰۵/۱۲ﺛﺎﺑﺖ 16:00 ﺗﺎ 06:00 120شهر الف شهر ب 0 3,202,992").single()
        assertEquals("120", row.km)
        assertEquals("14:00", row.hours)
        assertFalse(row.hoursBlank)
    }

    @Test fun invalidClockRangeIsUnknownNotZero() {
        val row = FastOcrReportText.parsePage("p1", 1,
            "1405/05/12ثابت 16:90 تا 06:00 120شهر الف شهر ب 0 3,202,992").single()
        assertEquals("", row.hours)
        assertTrue(row.hoursBlank)
    }

    @Test fun footerDatesAndFinancialLabelsAreNeverServiceRows() {
        val text = "1 1405/05/02 شهر الف شهر ب 140 . . 684\n" +
            "جمع 1405/05/30 4900 140\nحقوق 1405/05/15 5000000\n" +
            "1405/05/30خالص دریافتی 971,619,162"
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(1, rows.size)
        assertEquals("1405/05/02", rows.single().date)
        assertEquals("140", rows.single().km)
    }

    @Test fun pageRowLimitIsBoundedAndIdsAreUnique() {
        val text = (1..FastOcrReportText.MAX_ROWS_PER_PAGE + 1).joinToString("\n") {
            "${(it - 1) % 99 + 1} 1405/05/03 شهر الف شهر ب 140 . . 684"
        }
        val rows = FastOcrReportText.parsePage("p1", 1, text)
        assertEquals(FastOcrReportText.MAX_ROWS_PER_PAGE, rows.size)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        assertTrue(rows.all { it.count == "1" })
    }

    @Test fun missingDayTenAndElevenSurviveGluedOrdinalBeforeTheYear() {
        val text = """
            71405/05/10 شهر الف شهر ب 40 0
            81405/05/10 شهر ب شهر الف 95 .
            91405/05/11 شهر الف شهر ب 80 8
            101405/05/11 شهر ب شهر الف 120 .
            111405/05/12 شهر ب شهر الف 60 3
        """.trimIndent()
        val rows = FastOcrReportText.parsePage("p", 1, text)
        assertEquals(listOf("7", "8", "9", "10", "11"), rows.map { it.ordinal })
        assertEquals(listOf("1405/05/10", "1405/05/10", "1405/05/11", "1405/05/11", "1405/05/12"), rows.map { it.date })
        assertEquals(listOf("40", "95", "80", "120", "60"), rows.map { it.km })
        assertTrue(rows.all { it.count == "1" })
        assertEquals("", rows[1].hours) // Never fabricate a zero or an app-derived row.
    }

    @Test fun visualOrderSuffixOrdinalDoesNotMakeTheDayInvalid() {
        val rows = FastOcrReportText.parsePage("p", 1,
            "700 . . 140 شهر ب شهر الف 1405/05/107\n800 . . 8 80 شهر الف شهر ب 1405/05/118")
        assertEquals(listOf("1405/05/10", "1405/05/11"), rows.map { it.date })
        assertEquals(listOf("7", "8"), rows.map { it.ordinal })
        assertEquals(listOf("140", "80"), rows.map { it.km })
    }

    @Test fun markdownPipesUnicodeSlashAndSplitYearKeepTheDateAnchor() {
        val rows = FastOcrReportText.parsePage("p", 1,
            "| 7 | 1 4 0 5 / 05 / 10 | شهر الف شهر ب | 140 | . |\n" +
                "| 8 | ۱۴۰۵∕۰۵∕۱۱ | شهر ب شهر الف | 80 | 8 |")
        assertEquals(listOf("1405/05/10", "1405/05/11"), rows.map { it.date })
        assertEquals(listOf("7", "8"), rows.map { it.ordinal })
        assertEquals(listOf("140", "80"), rows.map { it.km })
    }

    @Test fun bidiMarksAndWideCellSpacingDoNotHideDatesOrOrdinals() {
        val rows = FastOcrReportText.parsePage("p", 1,
            "7        14\u200b05/\u200f05/10 شهر الف شهر ب 140 .\n8        1405/05/11 شهر ب شهر الف 80 8")
        assertEquals(listOf("1405/05/10", "1405/05/11"), rows.map { it.date })
        assertEquals(listOf("7", "8"), rows.map { it.ordinal })
    }

    @Test fun ordinalOnItsOwnCellLineWorksInBothReadingDirectionsWithoutIncomeTailBleed() {
        val rtl = "7\n1405/05/10\nشهر الف شهر ب 140 . 684\n8\n1405/05/11\nشهر ب شهر الف 80 8 682"
        val ltr = "684 . . 140 شهر ب شهر الف\n1405/05/10\n7\n682 . 8 80 شهر الف شهر ب\n1405/05/11\n8"
        for (text in listOf(rtl, ltr)) {
            val rows = FastOcrReportText.parsePage("p", 1, text)
            assertEquals(listOf("1405/05/10", "1405/05/11"), rows.map { it.date })
            assertEquals(listOf("7", "8"), rows.map { it.ordinal })
            assertEquals(listOf("140", "80"), rows.map { it.km })
        }
    }

    @Test fun layoutRecoveryDoesNotJoinADayAndAnOrdinalIntoAnotherDate() {
        val normalized = FastOcrReportText.normalizeLayout("1405 / 05 / 1 1")
        assertEquals("1405/05/1 1", normalized)
        assertEquals("14050510", FastOcrReportText.normalizeLayout("14050510"))
        assertEquals("1405/15/10", FastOcrReportText.normalizeLayout("1405/15/10"))
    }

    @Test fun skippedOrdinalsAreWarningsNeverGeneratedServiceRows() {
        val rows = FastOcrReportText.parsePage("p", 1,
            "7 1405/05/10 شهر الف شهر ب 140 .\n10 1405/05/12 شهر ب شهر الف 80 8")
        assertEquals(listOf(8, 9), FastOcrReportText.missingOrdinals(rows))
        assertEquals(2, rows.size)
        assertFalse(rows.any { it.date == "1405/05/11" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun absurdlyLongInputRejected() {
        FastOcrReportText.parsePage("p1", 1, "x".repeat(FastOcrClient.MAX_TEXT_BYTES + 1))
    }
}
