package com.khodroyar.app.core.records

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.MonthAggregate

/**
 * Month-by-month booklet for history modules (fuel, maintenance, personal purchases).
 *
 * The overview is built from one SQL aggregate per month ([MonthAggregate]); a month's
 * records are loaded only when that page is opened (`observeByDatePrefix("YYYY/MM")`).
 * Nothing here loads a whole history into memory.
 */
object MonthBook {
    data class Page(
        val year: Int,
        val month: Int,
        val count: Int,
        val total: Double,
        val liters: Double,
    ) {
        val name: String get() = Jalali.monthName(month)
        val prefix: String get() = prefix(year, month)
        val isEmpty: Boolean get() = count == 0
    }

    fun prefix(year: Int, month: Int): String = "$year/${month.toString().padStart(2, '0')}"

    /** All twelve pages of a Jalali year, in calendar order; months without rows are empty pages. */
    fun pages(year: Int, rows: List<MonthAggregate>): List<Page> {
        val byMonth = rows.mapNotNull { row ->
            val parts = row.month.split('/')
            val y = parts.getOrNull(0)?.toIntOrNull()
            val m = parts.getOrNull(1)?.toIntOrNull()
            if (y == year && m != null && m in 1..12) m to row else null
        }.groupBy({ it.first }, { it.second })
        return (1..12).map { m ->
            val list = byMonth[m].orEmpty()
            Page(year, m, list.sumOf { it.count }, list.sumOf { it.total }, list.sumOf { it.liters })
        }
    }

    data class YearTotals(val count: Int, val total: Double, val liters: Double, val activeMonths: Int) {
        val monthlyAverage: Double get() = if (activeMonths == 0) 0.0 else total / activeMonths
    }

    fun totals(pages: List<Page>) = YearTotals(
        pages.sumOf { it.count }, pages.sumOf { it.total }, pages.sumOf { it.liters }, pages.count { !it.isEmpty },
    )

    /** Previous/next month across year boundaries (Esfand 1403 + 1 = Farvardin 1404). */
    fun shift(year: Int, month: Int, delta: Int): Pair<Int, Int> {
        val index = year * 12 + (month - 1) + delta
        return Math.floorDiv(index, 12) to Math.floorMod(index, 12) + 1
    }

    /** The page opened by default: the current month of the current year, otherwise the latest month with records. */
    fun defaultMonth(year: Int, pages: List<Page>, today: String): Int? {
        val now = Jalali.parse(today)
        if (now != null && now.year == year) return now.month
        return pages.lastOrNull { !it.isEmpty }?.month
    }
}
