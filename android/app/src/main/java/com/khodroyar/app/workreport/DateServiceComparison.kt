package com.khodroyar.app.workreport

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.text.PersianInput

/** Advisory row counts only, NOT evidence that individual services match. */
data class DateServiceComparison(val date: String, val companyCount: Int, val appCount: Int) {
    val difference: Int get() = companyCount - appCount
}

/** Pure, read-only comparison. Missing km/hours never suppress a readable date. */
object DateServiceCountComparison {
    fun compare(rows: List<ReportRow>, appDates: List<String>, start: String, end: String): List<DateServiceComparison> {
        val a = requireNotNull(PersianInput.date(start))
        val b = requireNotNull(PersianInput.date(end))
        require(a <= b)
        // An ordinal is NOT a service count. Grouped descriptions and daily
        // totals are not silently reinterpreted as individual services either.
        val company = rows.asSequence().filter { it.included && it.kind == ReportRowKind.SERVICE }
            .mapNotNull { PersianInput.date(it.date) }.filter { it in a..b }.groupingBy { it }.eachCount()
        val app = appDates.asSequence().mapNotNull(PersianInput::date).filter { it in a..b }.groupingBy { it }.eachCount()
        return (company.keys + app.keys).sorted().map { DateServiceComparison(it, company[it] ?: 0, app[it] ?: 0) }
    }

    /** Include app-only days at BOTH ends of the monthly form, not just row dates. */
    fun suggestedRange(rows: List<ReportRow>): Pair<String, String>? {
        val dates = rows.filter { it.included }.mapNotNull { PersianInput.date(it.date) }
        if (dates.isEmpty()) return null
        val months = dates.map { it.take(7) }.distinct()
        return if (months.size == 1) {
            val month = months.single()
            Jalali.monthRange(month.take(4).toInt(), month.takeLast(2).toInt())
        } else dates.minOrNull()!! to dates.maxOrNull()!!
    }
}
