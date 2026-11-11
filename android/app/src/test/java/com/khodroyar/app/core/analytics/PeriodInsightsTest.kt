package com.khodroyar.app.core.analytics

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.DailyTotals
import com.khodroyar.app.data.db.ServiceEntity
import org.junit.Assert.*
import org.junit.Test

class PeriodInsightsTest {
    @Test fun inclusiveEqualWindowsDoNotOverlap() {
        val w = ReportWindow("1405/08/01", "1405/08/20")
        assertEquals(20, w.days); assertEquals(w.days, w.previous().days)
        assertEquals(Jalali.addDays(w.start, -1), w.previous().end)
    }
    @Test fun singleDayComparesWithOnePreviousDay() {
        val w = ReportWindow("1405/01/01", "1405/01/01")
        assertEquals(1, w.previous().days); assertEquals(Jalali.addDays(w.start, -1), w.previous().start)
    }
    @Test fun yearBoundaryUsesCalendarNotStringSubtraction() {
        val (now, before) = ReportWindow.monthToDate("1405/01/20")
        assertEquals("1404/12/01", before.start); assertEquals("1404/12/20", before.end); assertEquals(now.days, before.days)
    }
    @Test fun partialMonthDoesNotCompareWithAWholeMonth() {
        val (now, before) = ReportWindow.monthToDate("1405/08/10")
        assertEquals(10, now.days); assertEquals(10, before.days); assertEquals("1405/07/10", before.end)
    }
    @Test fun shorterPreviousMonthTrimsBothWindows() {
        val (now, before) = ReportWindow.monthToDate("1405/01/31")
        assertEquals(now.days, before.days); assertTrue(now.days in 29..30)
    }
    @Test fun reportPresetsAndAssistantUseIdenticalWindows() {
        val today="1405/08/20"; val month=Jalali.monthRange(1405,8);val week=Jalali.weekRange(today)
        assertEquals(ReportWindow.monthToDate(today),ReportWindow.forRange(month.first,month.second,today))
        assertEquals(ReportWindow.weekToDate(today),ReportWindow.forRange(week.first,week.second,today))
    }
    @Test fun weeklyComparisonUsesTheSameWeekdaysNotTheLastFewDays() {
        val (now,before)=ReportWindow.weekToDate("1405/08/20")
        assertEquals(now.days,before.days);assertEquals(Jalali.addDays(now.start,-7),before.start)
    }
    @Test fun invalidAndReversedRangesAreRejected() {
        for ((a,b) in listOf("1405/13/01" to "1405/13/02", "1405/08/20" to "1405/08/01", "1405/8/01" to "1405/08/03", "1300/01/01" to "1405/01/01")) {
            assertTrue(runCatching { ReportWindow(a,b) }.isFailure)
        }
    }
    @Test fun zeroOrNegativeBaselineNeverInventsPercentage() {
        assertNull(PeriodInsights.percentChange(100.0,0.0)); assertNull(PeriodInsights.percentChange(20.0,-10.0))
        assertNull(PeriodInsights.percentChange(Double.NaN,10.0)); assertEquals(25.0,PeriodInsights.percentChange(125.0,100.0)!!,0.0)
    }
    @Test fun vehicleRatiosExcludePersonalMoneyAndAllowRealLoss() {
        val value = FinancialSlice(serviceIncome=1_000.0, personalIncome=50_000.0, fuelCost=400.0, maintenanceCost=800.0, personalExpense=10_000.0, km=100.0, hours=2.0, recordCount=5)
        assertEquals(39_800.0,value.net,0.0); assertEquals(-2.0,value.vehicleNetPerKm!!,0.0); assertEquals(-100.0,value.vehicleNetPerHour!!,0.0)
    }
    @Test fun noDistanceOrHoursIsUnavailableNotZeroProfit() {
        assertNull(FinancialSlice(serviceIncome=100.0).vehicleNetPerKm); assertNull(FinancialSlice().vehicleNetPerHour)
        assertFalse(FinancialSlice().hasData)
    }
    @Test fun expenseChangesAreSortedByAmountAndLimitedToThree() {
        val now = listOf(ExpenseGroup("fuel",500.0),ExpenseGroup("maintenance",1_000.0),ExpenseGroup("personal:coffee",100.0),ExpenseGroup("personal:food",80.0))
        val before = listOf(ExpenseGroup("fuel",600.0),ExpenseGroup("maintenance",200.0))
        val changes = PeriodInsights.increases(now,before)
        assertEquals(listOf("maintenance","personal:coffee","personal:food"),changes.map { it.key })
        assertNull(changes[1].percent)
    }
    @Test fun sqlDailyTrendBucketsExactlyMatchLegacyRecordBuckets() {
        val today="1405/08/20"
        val rows=(0..200).map { i -> ServiceEntity("$i", Jalali.addDays(today,-i*3),"night","soren",10.0+i,2.5,2.5,"08:00","10:30","a","b","","",1,0.0,0.0,0.0,10_000L+i,"") }
        val daily=rows.groupBy { it.date }.map { (date,list) -> DailyTotals(date,list.size,list.sumOf { it.km },list.sumOf { it.hours },list.sumOf { it.income }) }
        Analytics.TrendScale.entries.forEach { scale ->
            assertEquals(Analytics.trend(rows,scale,if(scale==Analytics.TrendScale.YEAR) 4 else 6,today), DailyTrends.points(daily,scale,today))
        }
    }
}
