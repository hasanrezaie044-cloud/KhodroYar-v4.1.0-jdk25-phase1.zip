package com.khodroyar.app.core.holidays

import com.khodroyar.app.core.jalali.Jalali

/**
 * Which days count as holidays / non-working days.
 *
 * Four sources: Thursday, Friday, the official table in [IranHolidays], and the holidays
 * the user added by hand in Settings. Thursday and Friday are the Iranian weekend the
 * calendar already shows in red; this is what makes them count as holidays for logic too
 * (automatic registration never runs on them, and the calendar names them as holidays).
 */
object WorkCalendar {

    /** [Jalali.dayOfWeek]: 0 = Saturday … 5 = Thursday, 6 = Friday. */
    private const val THURSDAY = 5

    /** Thursday or Friday. */
    fun isWeekend(date: String): Boolean =
        Jalali.parse(date) != null && Jalali.dayOfWeek(date) >= THURSDAY

    /** @param manualDates the user's own holiday dates, Jalali "YYYY/MM/DD" */
    fun isNonWorkingDay(date: String, manualDates: Set<String>): Boolean {
        if (Jalali.parse(date) == null) return false
        return isWeekend(date) || IranHolidays.isOfficialHoliday(date) || manualDates.contains(date)
    }

    /** Weekday name for a Thursday / Friday, else null (used when no named holiday applies). */
    fun weekendName(date: String): String? {
        if (!isWeekend(date)) return null
        return if (Jalali.dayOfWeek(date) == THURSDAY) "پنجشنبه" else "جمعه"
    }
}
