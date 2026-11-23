package com.khodroyar.app.core.records

import com.khodroyar.app.data.db.MonthAggregate
import org.junit.Assert.*
import org.junit.Test

class MonthBookTest {
    @Test fun twelveJalaliPagesInCalendarOrderFromAggregates() {
        val pages = MonthBook.pages(1405, listOf(
            MonthAggregate("1405/12", 1, 300.0, 0.0),
            MonthAggregate("1405/01", 2, 100.0, 40.0),
            MonthAggregate("1404/01", 9, 999.0, 0.0), // other year never leaks in
            MonthAggregate("bad", 1, 1.0, 0.0),
        ))
        assertEquals((1..12).toList(), pages.map { it.month })
        assertEquals("فروردین", pages[0].name)
        assertEquals("اسفند", pages[11].name)
        assertEquals(2, pages[0].count); assertEquals(40.0, pages[0].liters, 0.0)
        assertTrue(pages[1].isEmpty)
        assertEquals("1405/01", pages[0].prefix)
        val totals = MonthBook.totals(pages)
        assertEquals(3, totals.count); assertEquals(400.0, totals.total, 0.0); assertEquals(2, totals.activeMonths)
        assertEquals(200.0, totals.monthlyAverage, 0.0)
    }

    @Test fun navigationCrossesJalaliYearBoundaries() {
        assertEquals(1405 to 1, MonthBook.shift(1404, 12, 1))
        assertEquals(1404 to 12, MonthBook.shift(1405, 1, -1))
        assertEquals(1405 to 6, MonthBook.shift(1405, 6, 0))
    }

    @Test fun prefixesArePaddedSoLikeQueriesMatchOneMonthOnly() {
        assertEquals("1405/02", MonthBook.prefix(1405, 2))
        assertEquals("1405/11", MonthBook.prefix(1405, 11))
    }

    @Test fun defaultMonthIsTodayInTheCurrentYearOtherwiseLatestActive() {
        val pages = MonthBook.pages(1404, listOf(MonthAggregate("1404/03", 1, 1.0, 0.0), MonthAggregate("1404/07", 1, 1.0, 0.0)))
        assertEquals(7, MonthBook.defaultMonth(1404, pages, "1405/02/10"))
        assertEquals(2, MonthBook.defaultMonth(1405, MonthBook.pages(1405, emptyList()), "1405/02/10"))
        assertNull(MonthBook.defaultMonth(1403, MonthBook.pages(1403, emptyList()), "1405/02/10"))
    }
}
