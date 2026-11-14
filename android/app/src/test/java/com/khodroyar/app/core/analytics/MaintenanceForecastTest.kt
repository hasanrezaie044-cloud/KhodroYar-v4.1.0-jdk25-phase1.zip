package com.khodroyar.app.core.analytics

import com.khodroyar.app.core.jalali.Jalali
import org.junit.Assert.*
import org.junit.Test

class MaintenanceForecastTest {
    private val today="1405/08/20"
    private fun history() = listOf(0,7,14,27).map { MileageDay(Jalali.addDays(today,-it),140.0) }
    @Test fun averageIncludesRestDays() { val p=MaintenanceForecast.pace(history(),today)!!; assertEquals(28,p.calendarDays);assertEquals(4,p.activeDays);assertEquals(20.0,p.kmPerDay,0.0) }
    @Test fun lessThanFourActiveDaysHasNoPrediction() { assertNull(MaintenanceForecast.pace(history().take(3),today)) }
    @Test fun shortHistoryHasNoPrediction() { assertNull(MaintenanceForecast.pace((0..3).map { MileageDay(Jalali.addDays(today,-it),100.0) },today)) }
    @Test fun staleHistoryHasNoPrediction() { assertNull(MaintenanceForecast.pace(listOf(15,20,30,40).map { MileageDay(Jalali.addDays(today,-it),100.0) },today)) }
    @Test fun invalidDistancesAndFutureRecordsAreIgnored() { assertEquals(MaintenanceForecast.pace(history(),today),MaintenanceForecast.pace(history()+listOf(MileageDay(today,Double.NaN),MileageDay(today,-50.0),MileageDay(Jalali.addDays(today,1),5000.0)),today)) }
    @Test fun duplicateDatesAreOneActiveDay() { assertEquals(4,MaintenanceForecast.pace(history()+history(),today)!!.activeDays) }
    @Test fun etaRoundsUpAndIsExplicitlyUnavailableWithoutConfiguredTarget() {
        val p=MaintenanceForecast.pace(history(),today)
        assertEquals(6,MaintenanceForecast.eta(101.0,p,today,true)!!.days)
        assertEquals(Jalali.addDays(today,6),MaintenanceForecast.eta(101.0,p,today,true)!!.date)
        assertNull(MaintenanceForecast.eta(100.0,p,today,false))
    }
    @Test fun overdueAndMissingPaceHaveNoInventedFutureDate() { assertNull(MaintenanceForecast.eta(-1.0,MaintenanceForecast.pace(history(),today),today,true));assertNull(MaintenanceForecast.eta(100.0,null,today,true)) }
    @Test fun predictionsBeyondThreeYearsAreNotPresentedAsPreciseDates() { assertNull(MaintenanceForecast.eta(1_000_000.0,MaintenanceForecast.pace(history(),today),today,true)) }
}
