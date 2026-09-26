package com.khodroyar.app.core.analytics

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.DailyTotals
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.db.ServiceEntity

/**
 * Dashboard and report aggregations, ported from utils/analytics.ts.
 *
 * Where the JS version scanned in-memory arrays on every render, the heavy
 * aggregation now happens in SQL (see the DAO `observe*Totals` queries) and this
 * object only handles the comparisons and thresholds that need business rules.
 */
object Analytics {

    data class DaySummary(
        val date: String,
        val count: Int,
        val totalKm: Double,
        val totalHours: Double,
        val totalIncome: Long,
    )

    data class Average(
        val avgKm: Double,
        val avgHours: Double,
        val avgIncome: Double,
        val sampleSize: Int,
    )

    fun daySummary(services: List<ServiceEntity>, date: String): DaySummary {
        val todays = services.filter { it.date == date }
        return DaySummary(
            date = date,
            count = todays.size,
            totalKm = todays.sumOf { it.km },
            totalHours = todays.sumOf { it.hours },
            totalIncome = todays.sumOf { it.income },
        )
    }

    /**
     * Mean daily figures over the most recent [days] days that actually have records,
     * excluding [excludeDate]. Matches the JS behaviour of averaging over *populated*
     * days rather than calendar days.
     */
    fun recentAverage(services: List<ServiceEntity>, excludeDate: String, days: Int = 14): Average {
        val byDate = LinkedHashMap<String, Triple<Double, Double, Long>>()
        for (s in services) {
            if (s.date == excludeDate) continue
            val cur = byDate[s.date] ?: Triple(0.0, 0.0, 0L)
            byDate[s.date] = Triple(cur.first + s.km, cur.second + s.hours, cur.third + s.income)
        }
        val dates = byDate.keys.sorted().takeLast(days)
        if (dates.isEmpty()) return Average(0.0, 0.0, 0.0, 0)
        var km = 0.0; var hours = 0.0; var income = 0L
        for (d in dates) {
            val t = byDate[d]!!
            km += t.first; hours += t.second; income += t.third
        }
        val n = dates.size
        return Average(km / n, hours / n, income.toDouble() / n, n)
    }

    fun weekTotals(daily: List<DailyTotals>, anyDateInWeek: String): DaySummary {
        val (start, end) = Jalali.weekRange(anyDateInWeek)
        val inWeek = daily.filter { it.date >= start && it.date <= end }
        return DaySummary(
            date = "$start..$end",
            count = inWeek.sumOf { it.count },
            totalKm = inWeek.sumOf { it.totalKm },
            totalHours = inWeek.sumOf { it.totalHours },
            totalIncome = inWeek.sumOf { it.totalIncome },
        )
    }

    /** Maintenance due status, using the configurable km intervals from settings. */
    data class ServiceDue(
        val label: String,
        val lastKm: Double?,
        val intervalKm: Int,
        val currentKm: Double,
        val remainingKm: Double?,
        val isOverdue: Boolean,
        val isDueSoon: Boolean,
    )

    fun oilChangeDue(last: MaintenanceEntity?, currentKm: Double, rates: Rates, nextKmOverride: Double = 0.0): ServiceDue =
        due("تعویض روغن", last?.km, rates.oilChangeKmInterval, currentKm, nextKmOverride)

    fun timingBeltDue(last: MaintenanceEntity?, currentKm: Double, rates: Rates): ServiceDue =
        due("تسمه تایم", last?.km, rates.timingBeltKmInterval, currentKm)

    private fun due(label: String, lastKm: Double?, interval: Int, currentKm: Double, nextKmOverride: Double = 0.0): ServiceDue {
        if (lastKm == null || interval <= 0) {
            return ServiceDue(label, lastKm, interval, currentKm, null, false, false)
        }
        val nextAt = if (nextKmOverride > 0) nextKmOverride else lastKm + interval
        val remaining = nextAt - currentKm
        return ServiceDue(
            label = label,
            lastKm = lastKm,
            intervalKm = interval,
            currentKm = currentKm,
            remainingKm = remaining,
            isOverdue = remaining <= 0,
            isDueSoon = remaining in 0.0..(interval * 0.1),
        )
    }


    /* ------------------------------------------------- summary by service type */

    /**
     * One row of the "خلاصه بر اساس نوع سرویس" table. Deliberately a plain data class
     * with no formatting inside it, so the screen, the PDF writer and the XLSX writer
     * all render the exact same numbers from the exact same aggregation.
     */
    data class TypeSummary(
        val wire: String,
        val label: String,
        val count: Int,
        val totalKm: Double,
        val totalHours: Double,
        val tollCount: Int,
        val totalAmount: Long,
    )

    data class SummaryReport(
        val rows: List<TypeSummary>,
        val totals: TypeSummary,
    )

    /**
     * Groups services by type and computes the per-type and overall totals used by the
     * reports screen, the PDF export and the Excel export. Rows are ordered by amount,
     * highest first, which is the order the user reads them in.
     *
     * Unknown/legacy type values keep their own row rather than being silently folded
     * into another type, so an old record can never distort a total.
     */
    fun summarizeByType(services: List<ServiceEntity>): SummaryReport {
        val grouped = services.groupBy { it.type }
        val rows = grouped.map { (wire, list) ->
            TypeSummary(
                wire = wire,
                label = ServiceType.fromWireOrNull(wire)?.label ?: wire.ifBlank { "نامشخص" },
                count = list.size,
                totalKm = list.sumOf { it.km },
                totalHours = list.sumOf { it.hours },
                tollCount = list.sumOf { it.tollCount },
                totalAmount = list.sumOf { it.income },
            )
        }.sortedByDescending { it.totalAmount }
        val totals = TypeSummary(
            wire = "__total__",
            label = "جمع کل",
            count = services.size,
            totalKm = services.sumOf { it.km },
            totalHours = services.sumOf { it.hours },
            tollCount = services.sumOf { it.tollCount },
            totalAmount = services.sumOf { it.income },
        )
        return SummaryReport(rows, totals)
    }

    /* ------------------------------------------------------ comparison / trend */

    /** One bucket of the dashboard trend chart. */
    data class TrendPoint(
        val label: String,
        val count: Int,
        val totalKm: Double,
        val totalHours: Double,
        val totalIncome: Long,
    ) {
        val isEmpty: Boolean get() = count == 0
    }

    enum class TrendScale { WEEK, MONTH, YEAR }

    /**
     * Builds a real series from stored records only — never a synthetic or demo value.
     * A bucket with no records stays at zero and is reported as empty, so the screen can
     * show an insufficient-data state instead of drawing a fake line.
     */
    fun trend(
        services: List<ServiceEntity>,
        scale: TrendScale,
        buckets: Int,
        today: String = Jalali.todayString(),
    ): List<TrendPoint> {
        if (buckets <= 0) return emptyList()
        val out = ArrayList<TrendPoint>(buckets)
        for (offset in (buckets - 1) downTo 0) {
            when (scale) {
                TrendScale.WEEK -> {
                    val anchor = Jalali.addDays(today, -7 * offset)
                    val (start, end) = Jalali.weekRange(anchor)
                    val slice = services.filter { it.date >= start && it.date <= end }
                    out.add(bucket(if (offset == 0) "این هفته" else "$offset هفته قبل", slice))
                }
                TrendScale.MONTH -> {
                    val anchor = Jalali.addMonths(today, -offset)
                    val ymd = Jalali.parse(anchor) ?: continue
                    val prefix = "${ymd.year}/${ymd.month.toString().padStart(2, '0')}"
                    val slice = services.filter { it.date.startsWith(prefix) }
                    out.add(bucket(Jalali.monthName(ymd.month), slice))
                }
                TrendScale.YEAR -> {
                    val year = (Jalali.parse(today)?.year ?: Jalali.currentYear()) - offset
                    val slice = services.filter { it.date.startsWith("$year/") }
                    out.add(bucket(year.toString(), slice))
                }
            }
        }
        return out
    }

    private fun bucket(label: String, slice: List<ServiceEntity>) = TrendPoint(
        label = label,
        count = slice.size,
        totalKm = slice.sumOf { it.km },
        totalHours = slice.sumOf { it.hours },
        totalIncome = slice.sumOf { it.income },
    )

    /** Hours read better with one decimal than rounded to a whole number. */
    fun formatHours(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) formatNumber(rounded.toLong())
        else formatNumber(Math.floor(rounded).toLong()) + "." + ((Math.round(rounded * 10) % 10).toString())
    }

    /** Thousands separator, identical to `formatNumber` in AppContext.tsx. */
    fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = com.khodroyar.app.core.rates.TimeRates.jsRound(value)
        return formatNumber(rounded)
    }

    fun formatNumber(value: Long): String {
        val s = kotlin.math.abs(value).toString()
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return if (value < 0) "-$sb" else sb.toString()
    }
}
