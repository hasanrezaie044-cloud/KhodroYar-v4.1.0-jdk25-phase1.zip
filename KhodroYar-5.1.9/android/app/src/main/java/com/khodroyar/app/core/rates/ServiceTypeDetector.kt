package com.khodroyar.app.core.rates

/**
 * Picks a [ServiceType] from what the user typed in the service form, so the type no
 * longer has to be chosen by hand:
 *
 *  * the service date is a holiday (Thursday, Friday, official or manual) -> HOLIDAY (تعطیل)
 *  * start 06:00–17:29, no request number  -> FIXED   (ثابت — never has a request number)
 *  * start 06:00–17:29, request number     -> REQUEST (درخواستی)
 *  * start 17:30–05:59 (wraps midnight)    -> NIGHT   (شب)
 *
 * A holiday date wins over the clock: whatever is driven on a holiday is a holiday
 * service (holiday km and hour rates). The brief lists night with a request number; with
 * no request number the clock is still in the night bracket, so it stays NIGHT.
 *
 * HOLIDAY, FIXED, REQUEST and NIGHT are the only types that come out of detection.
 * AVAILABLE, MISSION and the rental types remain manual choices — the form keeps a
 * "manual" override for exactly those. Rates for the chosen type are still looked up by
 * the service date's year, as before.
 */
object ServiceTypeDetector {

    private const val DAY_START = 6 * 60          // 06:00, inclusive
    private const val NIGHT_START = 17 * 60 + 30  // 17:30, inclusive

    /**
     * @param isHoliday whether the service date is a non-working day
     *   ([com.khodroyar.app.core.holidays.WorkCalendar.isNonWorkingDay])
     * @return null when it is not a holiday and [startTime] is blank or unreadable —
     *   nothing to decide from.
     */
    fun detect(startTime: String?, requestNumber: String?, isHoliday: Boolean = false): ServiceType? {
        if (isHoliday) return ServiceType.HOLIDAY
        val raw = TimeRates.timeToMinutes(startTime) ?: return null
        val minutes = ((raw % 1440) + 1440) % 1440
        val hasRequest = !requestNumber.isNullOrBlank()
        val isDay = minutes in DAY_START until NIGHT_START
        return when {
            isDay && hasRequest -> ServiceType.REQUEST
            isDay -> ServiceType.FIXED
            else -> ServiceType.NIGHT
        }
    }

    /**
     * For a record that already has a type: null when auto-detection would produce that
     * same type (so the form stays in automatic mode), otherwise the stored type — which
     * is then kept as the user's manual choice and is never silently rewritten.
     */
    fun manualOverride(
        stored: ServiceType,
        startTime: String?,
        requestNumber: String?,
        isHoliday: Boolean = false,
    ): ServiceType? =
        if (detect(startTime, requestNumber, isHoliday) == stored) null else stored
}
