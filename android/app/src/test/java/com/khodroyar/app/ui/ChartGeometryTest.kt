package com.khodroyar.app.ui

import com.khodroyar.app.ui.components.ChartGeometry
import org.junit.Assert.*
import org.junit.Test

class ChartGeometryTest {
    @Test fun emptyScaleHasRealZeroAndFiniteRange() {
        val s = ChartGeometry.scale(emptyList()); assertEquals(0.0, s.min, 0.0); assertTrue(s.max > 0); assertTrue(s.ticks.all { it.isFinite() })
    }
    @Test fun zeroSeriesNeverDividesByZero() {
        val s = ChartGeometry.scale(listOf(0.0, 0.0)); assertEquals(0f, s.fraction(0.0)); assertTrue(s.step > 0)
    }
    @Test fun positiveScaleDoesNotTruncateAndStartsAtZero() {
        val s = ChartGeometry.scale(listOf(125.0, 900.0)); assertEquals(0.0, s.min, 0.0); assertTrue(s.max >= 900); assertTrue(s.ticks.size in 2..13)
    }
    @Test fun negativeSeriesKeepsItsSign() {
        val s = ChartGeometry.scale(listOf(-9.0, -2.0)); assertTrue(s.min <= -9); assertEquals(0.0, s.max, 0.0); assertTrue(s.fraction(-2.0) > s.fraction(-9.0))
    }
    @Test fun mixedSignsHaveHonestZeroBaseline() {
        val s = ChartGeometry.scale(listOf(-100.0, 200.0)); assertTrue(s.fraction(0.0) in 0.01f..0.99f); assertTrue(s.min <= -100 && s.max >= 200)
    }
    @Test fun targetIsIncludedInScale() {
        val s = ChartGeometry.scale(listOf(100.0), 1_000_000.0); assertTrue(s.max >= 1_000_000); assertTrue(s.fraction(100.0) < s.fraction(1_000_000.0))
    }
    @Test fun invalidNumbersCannotPoisonScale() {
        val s = ChartGeometry.scale(listOf(Double.NaN, Double.POSITIVE_INFINITY), Double.NaN)
        assertTrue(s.min.isFinite() && s.max.isFinite()); assertTrue(s.fraction(Double.NaN).isFinite())
    }
    @Test fun extremeAndTinyDisplayValuesStayFinite() {
        listOf(listOf(Double.MAX_VALUE, -Double.MAX_VALUE), listOf(Double.MIN_VALUE)).forEach { data ->
            val s = ChartGeometry.scale(data); assertTrue(s.min.isFinite() && s.max.isFinite() && s.step.isFinite()); assertTrue(s.max > s.min)
        }
    }
    @Test fun fractionsAreClampedToPlot() {
        val s = ChartGeometry.scale(listOf(10.0)); assertEquals(0f, s.fraction(-100.0)); assertEquals(1f, s.fraction(1e10))
    }
    @Test fun rtlAndLtrUseTheSameChronologicalIndex() {
        assertEquals(0f, ChartGeometry.xFraction(0, 6, false)); assertEquals(1f, ChartGeometry.xFraction(0, 6, true))
        assertEquals(1f, ChartGeometry.xFraction(5, 6, false)); assertEquals(0f, ChartGeometry.xFraction(5, 6, true))
    }
    @Test fun drawingAndHitTestRoundTripInBothDirections() {
        listOf(false, true).forEach { rtl -> (1..20).forEach { count -> (0 until count).forEach { i ->
            assertEquals(i, ChartGeometry.nearestIndex(ChartGeometry.xFraction(i, count, rtl), count, rtl))
        } } }
    }
    @Test fun singlePointIsCentered() { assertEquals(0.5f, ChartGeometry.xFraction(0, 1, true)); assertEquals(0, ChartGeometry.nearestIndex(0.9f, 1, false)) }
    @Test fun invalidHitDoesNotSelectAnIndex() { assertNull(ChartGeometry.nearestIndex(0.4f, 0, false)); assertNull(ChartGeometry.nearestIndex(Float.NaN, 4, true)) }
    @Test fun tapsOutsidePlotClampToEndPoints() { assertEquals(0, ChartGeometry.nearestIndex(-0.2f, 6, false)); assertEquals(0, ChartGeometry.nearestIndex(1.2f, 6, true)) }
    @Test fun sliceSharesOnlyUseFinitePositiveValues() {
        val s = ChartGeometry.shares(listOf(20.0, 30.0, -5.0, Double.NaN)); assertEquals(0.4, s[0], 1e-9); assertEquals(0.6, s[1], 1e-9); assertEquals(0.0, s[2], 0.0); assertEquals(1.0, s.sum(), 1e-9)
    }
    @Test fun emptyAndZeroSlicesHaveNoFakeUnit() { assertTrue(ChartGeometry.shares(emptyList()).isEmpty()); assertEquals(listOf(0.0, 0.0), ChartGeometry.shares(listOf(0.0, -2.0))) }
    @Test fun largeSlicesCannotOverflowTotal() { val s = ChartGeometry.shares(listOf(Double.MAX_VALUE, Double.MAX_VALUE)); assertEquals(listOf(0.5, 0.5), s) }
    @Test fun clockwiseRingHitUsesSamePartitionAsDraw() {
        val s = listOf(0.5, 0.5); assertEquals(0, ChartGeometry.ringIndex(50f, 0f, 50f, 16f, s)); assertEquals(1, ChartGeometry.ringIndex(-50f, 0f, 50f, 16f, s))
    }
    @Test fun centreOutsideAndSegmentGapsDoNotSelect() {
        val s = listOf(0.5, 0.5); assertNull(ChartGeometry.ringIndex(0f, 0f, 50f, 16f, s)); assertNull(ChartGeometry.ringIndex(100f, 0f, 50f, 16f, s)); assertNull(ChartGeometry.ringIndex(0f, -50f, 50f, 16f, s))
    }
    @Test fun sparseLabelsAlwaysKeepFirstAndLast() { val s = ChartGeometry.visibleLabels(12); assertTrue(0 in s && 11 in s); assertEquals(5, s.size); assertTrue(ChartGeometry.visibleLabels(0).isEmpty()) }
}
