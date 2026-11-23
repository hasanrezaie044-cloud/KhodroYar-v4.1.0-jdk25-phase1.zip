package com.khodroyar.app.core.finance

import com.khodroyar.app.core.jalali.Jalali

/**
 * Loan chart timeline (5.9.0). Chronology comes from the data order — index 0 is the
 * oldest month — and the chart draws it left→right even in RTL, so the Persian layout
 * can never reverse time. Axis captions are month names only; the year is shown in the
 * selection header and on Farvardin, where the year changes.
 */
object LoanChartLabels {
    /** "YYYY/MM" prefixes from [fromOffset] to [toOffset] months around today, oldest first. */
    fun window(today: String, fromOffset: Int = -3, toOffset: Int = 8): List<String> {
        val now = Jalali.parse(today) ?: return emptyList()
        val anchor = Jalali.format(now.year, now.month, 1)
        return (fromOffset..toOffset).map { Jalali.addMonths(anchor, it).substring(0, 7) }
    }

    fun axis(prefix: String): String {
        val year = prefix.substringBefore('/').toIntOrNull() ?: return prefix
        val month = prefix.substringAfter('/').take(2).toIntOrNull() ?: return prefix
        val name = Jalali.monthName(month)
        return if (month == 1) "$name ${year % 100}" else name
    }
}
