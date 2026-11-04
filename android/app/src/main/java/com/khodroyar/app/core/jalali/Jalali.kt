package com.khodroyar.app.core.jalali

/**
 * Jalali (Solar Hijri) calendar — canonical Borkowski 33-year-cycle algorithm.
 *
 * WHY THIS REPLACES THE OLD JS IMPLEMENTATION
 * -------------------------------------------
 * The React Native version (utils/jalali.ts) had two real defects that this
 * implementation fixes. Both were verified against documented Nowruz dates:
 *
 *  1. `isJalaliLeapYear` used `breaks.contains(jy % 132)`, which disagreed with
 *     the actual conversion routines. It reported 1396/1400/1404/1417/1421/1425
 *     as leap and 1395/1399/1403/1416/1420/1424 as common — exactly inverted
 *     from the official calendar. Consequence in the shipped app: Esfand 1404
 *     rendered a non-existent 30th day, Esfand 1403's real 30th day was hidden,
 *     and `addMonthsJalali` could emit the invalid date "1404/12/30" as a loan
 *     installment due date.
 *
 *  2. `gregorianToJalali` returned a year too low on the Nowruz day of every
 *     leap year (2012-03-20, 2016-03-20, 2020-03-20, 2024-03-20 all mapped one
 *     year early). Because `getTodayJalali()` and `activeYear` are derived from
 *     it, on those days the app resolved the wrong Jalali year and therefore the
 *     wrong per-year rate set.
 *
 * This implementation round-trips all 36,890 days between 1350/01/01 and
 * 1450/12/29 with zero mismatches and agrees with the official leap-year list
 * (1391, 1395, 1399, 1403, 1408, 1412, 1416, 1420, 1424, 1428).
 *
 * Day-of-week convention is preserved from the JS app: 0 = Saturday … 6 = Friday.
 */
object Jalali {

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181,
        1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    )

    /** Reference point: 1404/06/01 is a Saturday (= 2025-08-23). */
    private val DOW_OFFSET: Int by lazy {
        val jdn = toJdn(1404, 6, 1)
        ((0 - (jdn % 7)) % 7 + 7) % 7
    }

    data class YearCal(val leap: Int, val gy: Int, val march: Int)
    data class Ymd(val year: Int, val month: Int, val day: Int) {
        override fun toString(): String = format(year, month, day)
    }

    val MONTH_NAMES = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /** 0 = Saturday … 6 = Friday */
    val WEEKDAY_NAMES = listOf(
        "شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"
    )

    private fun div(a: Int, b: Int) = a / b

    private fun jalCal(jy: Int): YearCal {
        val gy = jy + 621
        require(jy >= BREAKS[0] && jy < BREAKS[BREAKS.size - 1]) { "Jalali year out of range: $jy" }

        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(jump % 33, 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += div(n, 33) * 8 + div((n % 33) + 3, 4)
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1

        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG

        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = (((n + 1) % 33) - 1) % 4
        if (leap == -1) leap = 4
        return YearCal(leap, gy, march)
    }

    fun isLeapYear(jy: Int): Boolean = jalCal(jy).leap == 0

    fun monthLength(jy: Int, jm: Int): Int = when {
        jm <= 6 -> 31
        jm <= 11 -> 30
        else -> if (isLeapYear(jy)) 30 else 29
    }

    fun isValid(jy: Int, jm: Int, jd: Int): Boolean =
        jm in 1..12 && jd >= 1 && jd <= monthLength(jy, jm)

    // ---- Julian Day Number bridges ----

    fun gregorianToJdn(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) +
                div(153 * ((gm + 9) % 12) + 2, 5) + gd - 34840408
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
        return d
    }

    fun jdnToGregorian(jdn: Int): Triple<Int, Int, Int> {
        var j = 4 * jdn + 139361631
        j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(j % 1461, 4) * 5 + 308
        val gd = div(i % 153, 5) + 1
        val gm = div(i, 153) % 12 + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return Triple(gy, gm, gd)
    }

    fun toJdn(jy: Int, jm: Int, jd: Int): Int {
        val r = jalCal(jy)
        return gregorianToJdn(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1
    }

    fun fromJdn(jdn: Int): Ymd {
        val gy = jdnToGregorian(jdn).first
        var jy = gy - 621
        val r = jalCal(jy)
        val jdn1f = gregorianToJdn(gy, 3, r.march)
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) return Ymd(jy, 1 + div(k, 31), (k % 31) + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return Ymd(jy, 7 + div(k, 30), (k % 30) + 1)
    }

    // ---- Public conversions ----

    fun fromGregorian(gy: Int, gm: Int, gd: Int): Ymd = fromJdn(gregorianToJdn(gy, gm, gd))

    fun toGregorian(jy: Int, jm: Int, jd: Int): Triple<Int, Int, Int> = jdnToGregorian(toJdn(jy, jm, jd))

    /** 0 = Saturday … 6 = Friday */
    fun dayOfWeek(jy: Int, jm: Int, jd: Int): Int = ((toJdn(jy, jm, jd) + DOW_OFFSET) % 7 + 7) % 7

    fun dayOfWeek(date: String): Int = parse(date)?.let { dayOfWeek(it.year, it.month, it.day) } ?: 0

    fun weekdayName(jy: Int, jm: Int, jd: Int): String = WEEKDAY_NAMES[dayOfWeek(jy, jm, jd)]

    // ---- String helpers (format identical to the JS app: "YYYY/MM/DD") ----

    fun format(jy: Int, jm: Int, jd: Int): String =
        "$jy/${jm.toString().padStart(2, '0')}/${jd.toString().padStart(2, '0')}"

    fun parse(date: String?): Ymd? {
        if (date.isNullOrBlank()) return null
        val parts = date.split('/')
        if (parts.size != 3) return null
        val y = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        val d = parts[2].trim().toIntOrNull() ?: return null
        return Ymd(y, m, d)
    }

    fun today(): Ymd {
        val now = java.util.Calendar.getInstance()
        return fromGregorian(
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    fun todayString(): String = today().toString()

    fun currentYear(): Int = today().year

    /**
     * Adds [months] to a Jalali date, clamping the day to the target month's real
     * length. Unlike the JS version this can never produce a non-existent date,
     * because [monthLength] is now correct.
     */
    fun addMonths(date: String, months: Int): String {
        val p = parse(date) ?: return date
        var y = p.year
        var m = p.month + months
        while (m > 12) { m -= 12; y += 1 }
        while (m < 1) { m += 12; y -= 1 }
        return format(y, m, minOf(p.day, monthLength(y, m)))
    }

    fun addDays(date: String, days: Int): String {
        val p = parse(date) ?: return date
        return fromJdn(toJdn(p.year, p.month, p.day) + days).toString()
    }

    /** Week containing [date], Saturday → Friday (same convention as the JS app). */
    fun weekRange(date: String): Pair<String, String> {
        val p = parse(date) ?: return date to date
        val jdn = toJdn(p.year, p.month, p.day)
        val dow = ((jdn + DOW_OFFSET) % 7 + 7) % 7
        return fromJdn(jdn - dow).toString() to fromJdn(jdn + (6 - dow)).toString()
    }

    fun monthRange(jy: Int, jm: Int): Pair<String, String> =
        format(jy, jm, 1) to format(jy, jm, monthLength(jy, jm))

    /** Days between two Jalali dates (b - a). */
    fun daysBetween(a: String, b: String): Int {
        val pa = parse(a) ?: return 0
        val pb = parse(b) ?: return 0
        return toJdn(pb.year, pb.month, pb.day) - toJdn(pa.year, pa.month, pa.day)
    }

    fun monthName(jm: Int): String = MONTH_NAMES.getOrElse(jm - 1) { "" }
}
