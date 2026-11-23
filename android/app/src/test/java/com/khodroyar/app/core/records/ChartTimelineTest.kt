package com.khodroyar.app.core.records

import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.analytics.HomeGoalLine
import com.khodroyar.app.core.finance.LoanChartLabels
import com.khodroyar.app.ui.components.ChartGeometry
import org.junit.Assert.*
import org.junit.Test

/** 5.9.0 loan chart and Home goal line. */
class ChartTimelineTest {
    @Test fun loanWindowIsChronologicalAcrossTheJalaliNewYear() {
        val w = LoanChartLabels.window("1404/11/15")
        assertEquals(12, w.size)
        assertEquals("1404/08", w.first()); assertEquals("1405/07", w.last())
        assertEquals(w.sorted(), w) // oldest first: drawn left→right, never mirrored
        assertTrue("1404/12" in w && "1405/01" in w)
        assertTrue(LoanChartLabels.window("garbage").isEmpty())
    }

    @Test fun axisCaptionsAreShortMonthNamesWithYearOnlyAtFarvardin() {
        assertEquals("اردیبهشت", LoanChartLabels.axis("1405/02"))
        assertEquals("فروردین 5", LoanChartLabels.axis("1405/01"))
        assertEquals("اسفند", LoanChartLabels.axis("1404/12"))
    }

    @Test fun fittedLabelsNeverOverlapEvenWithEdgeClamping() {
        for (count in listOf(2, 6, 12, 24)) for (plot in listOf(220f, 300f, 600f)) for (w in listOf(24f, 48f, 90f)) {
            val shown = ChartGeometry.fitLabels(count, w, plot, 6f).sorted()
            assertTrue("newest month always labelled", count - 1 in shown)
            val step = plot / (count - 1)
            shown.zipWithNext().forEach { (a, b) ->
                assertTrue("gap $count/$plot/$w", (b - a) * step >= w * 1.5f + 6f - 0.01f)
            }
        }
        assertEquals(setOf(0), ChartGeometry.fitLabels(1, 50f, 300f, 6f))
        assertTrue(ChartGeometry.fitLabels(0, 50f, 300f, 6f).isEmpty())
        assertEquals((0 until 6).toSet(), ChartGeometry.fitLabels(6, 20f, 600f, 6f))
    }

    @Test fun goalLineOnlyWhereTheMonthlyTargetIsComparable() {
        assertEquals(20_000_000.0, HomeGoalLine.of(20_000_000.0, true, Analytics.TrendScale.MONTH)!!, 0.0)
        assertEquals(240_000_000.0, HomeGoalLine.of(20_000_000.0, true, Analytics.TrendScale.YEAR)!!, 0.0)
        assertNull(HomeGoalLine.of(20_000_000.0, true, Analytics.TrendScale.WEEK))
        assertNull(HomeGoalLine.of(20_000_000.0, false, Analytics.TrendScale.MONTH))
        assertNull(HomeGoalLine.of(0.0, true, Analytics.TrendScale.MONTH))
        assertNull(HomeGoalLine.of(Double.NaN, true, Analytics.TrendScale.MONTH))
    }
}
