package com.khodroyar.app.ui

import com.khodroyar.app.ui.components.ReportChartData
import org.junit.Assert.*
import org.junit.Test

class ReportChartDataTest {
    @Test fun costsAreNotCountedAgainAsASumSlice() {
        val parts = ReportChartData.parts(true, 10.0, 20.0, 30.0, 100.0, 50.0)
        assertEquals(listOf("fuel", "maint", "buy"), parts.map { it.id }); assertEquals(60.0, parts.sumOf { it.value }, 0.0)
    }
    @Test fun incomeAndCostsAreDifferentPartitions() {
        val parts = ReportChartData.parts(false, 10.0, 20.0, 30.0, 100.0, 50.0)
        assertEquals(listOf("income", "pincome"), parts.map { it.id }); assertEquals(150.0, parts.sumOf { it.value }, 0.0)
    }
    @Test fun emptyReportsNeverInventMoney() { assertEquals(0.0, ReportChartData.parts(true, 0.0, 0.0, 0.0, 0.0, 0.0).sumOf { it.value }, 0.0) }
    @Test fun originalAmountsAreNotRoundedByChartPreparation() {
        val parts = ReportChartData.parts(true, 0.25, 0.5, 1.25, 0.0, 0.0); assertEquals(2.0, parts.sumOf { it.value }, 0.0)
    }
}
