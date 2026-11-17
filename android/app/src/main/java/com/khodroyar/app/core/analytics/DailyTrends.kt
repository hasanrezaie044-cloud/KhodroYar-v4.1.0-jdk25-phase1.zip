package com.khodroyar.app.core.analytics

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.DailyTotals

/** Same buckets as Analytics.trend, but consumes SQL daily aggregates instead of every lifetime record. */
object DailyTrends {
    fun points(rows: List<DailyTotals>, scale: Analytics.TrendScale, today: String): List<Analytics.TrendPoint> {
        val count = if (scale == Analytics.TrendScale.YEAR) 4 else 6
        return (count - 1 downTo 0).map { offset ->
            val label: String
            val slice: List<DailyTotals>
            when (scale) {
                Analytics.TrendScale.WEEK -> {
                    val range = Jalali.weekRange(Jalali.addDays(today, -7 * offset))
                    label = if (offset == 0) "این هفته" else "$offset هفته قبل"
                    slice = rows.filter { it.date in range.first..range.second }
                }
                Analytics.TrendScale.MONTH -> {
                    val date = requireNotNull(Jalali.parse(Jalali.addMonths(today, -offset)))
                    label = Jalali.monthName(date.month)
                    val prefix = "${date.year}/${date.month.toString().padStart(2, '0')}"
                    slice = rows.filter { it.date.startsWith(prefix) }
                }
                Analytics.TrendScale.YEAR -> {
                    val year = requireNotNull(Jalali.parse(today)).year - offset
                    label = year.toString(); slice = rows.filter { it.date.startsWith("$year/") }
                }
            }
            Analytics.TrendPoint(label, slice.sumOf { it.count }, slice.sumOf { it.totalKm }, slice.sumOf { it.totalHours }, slice.sumOf { it.totalIncome })
        }
    }
}
