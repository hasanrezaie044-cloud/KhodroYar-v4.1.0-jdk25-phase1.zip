package com.khodroyar.app.core.analytics

import com.khodroyar.app.core.jalali.Jalali
import kotlin.math.ceil

/** At most 56 aggregated rows are loaded, not the lifetime trip history. */
data class MileageDay(val date: String, val km: Double)
data class DrivingPace(val kmPerDay: Double, val calendarDays: Int, val activeDays: Int, val totalKm: Double)
data class MaintenanceEta(val days: Int, val date: String)

object MaintenanceForecast {
    const val LOOKBACK_DAYS = 56
    fun pace(rows: List<MileageDay>, today: String): DrivingPace? {
        val start = Jalali.addDays(today, -(LOOKBACK_DAYS - 1))
        val usable = rows.filter { it.date in start..today && it.km.isFinite() && it.km > 0 }
            .groupBy { it.date }.map { (date, trips) -> MileageDay(date, trips.sumOf { it.km }) }.sortedBy { it.date }
        if (usable.size < 4) return null
        val days = Jalali.daysBetween(usable.first().date, today) + 1
        if (days < 14 || Jalali.daysBetween(usable.last().date, today) > 14) return null
        val total = usable.sumOf { it.km }
        val average = total / days // Include non-driving days; active-day averages exaggerate the ETA.
        return if (average > 0 && average.isFinite()) DrivingPace(average, days, usable.size, total) else null
    }

    fun eta(remainingKm: Double?, pace: DrivingPace?, today: String, configured: Boolean): MaintenanceEta? {
        if (!configured || remainingKm == null || !remainingKm.isFinite() || remainingKm <= 0 || pace == null || pace.kmPerDay <= 0) return null
        val days = ceil(remainingKm / pace.kmPerDay)
        if (!days.isFinite() || days !in 1.0..1095.0) return null
        return MaintenanceEta(days.toInt(), Jalali.addDays(today, days.toInt()))
    }
}
