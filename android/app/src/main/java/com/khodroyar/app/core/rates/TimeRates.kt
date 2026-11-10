package com.khodroyar.app.core.rates

import kotlin.math.floor
import kotlin.math.max

/**
 * Splits worked duration into normal / night / dawn ("sahar") brackets.
 *
 * Ported 1:1 from utils/timeRates.ts. The bracket rules, the midnight-crossing
 * behaviour and the proportional scaling against the user-entered `hours` field
 * are all preserved exactly; the golden-vector test suite asserts identical
 * output for 507 boundary/time combinations and 91 breakdown cases.
 *
 * Bracket rules (no overlap, no double counting at boundaries):
 *   before nightStart          -> normal
 *   nightStart .. nightEnd     -> night percent
 *   nightEnd .. saharEnd       -> dawn percent (may cross midnight)
 *   after saharEnd             -> normal
 */
object TimeRates {

    const val DAY_MINUTES = 24 * 60

    // Defaults identical to the JS app, for records/settings without custom ranges.
    const val DEFAULT_SAHAR_END_MIN = 6 * 60          // 06:00
    const val DEFAULT_NIGHT_START_MIN = 17 * 60 + 30  // 17:30
    const val DEFAULT_NIGHT_END_MIN = 20 * 60         // 20:00

    data class Boundaries(
        val nightStartMin: Int,
        val nightEndMin: Int,
        val saharEndMin: Int,
    )

    val DEFAULT_BOUNDARIES = Boundaries(
        nightStartMin = DEFAULT_NIGHT_START_MIN,
        nightEndMin = DEFAULT_NIGHT_END_MIN,
        saharEndMin = DEFAULT_SAHAR_END_MIN,
    )

    enum class Category { NORMAL, NIGHT, SAHAR }

    data class SliceHours(val normal: Double, val night: Double, val sahar: Double) {
        val total: Double get() = normal + night + sahar
    }

    data class HourlyBreakdown(
        val normalHours: Double,
        val nightHours: Double,
        val saharHours: Double,
        val normalAmount: Double,
        val nightAmount: Double,
        val saharAmount: Double,
        val totalAmount: Double,
    )

    /** JS `Math.round` semantics: floor(x + 0.5). Kotlin's `round` uses ties-to-even, which differs. */
    fun jsRound(x: Double): Long = if (x.isNaN() || x.isInfinite()) 0L else floor(x + 0.5).toLong()

    /** "HH:MM" -> minutes since midnight, or null when unparseable (matches JS). */
    fun timeToMinutes(t: String?): Int? {
        if (t.isNullOrEmpty()) return null
        val parts = t.split(':')
        if (parts.size < 2) return null
        val h = parts[0].trim().toDoubleOrNull() ?: return null
        val m = parts[1].trim().toDoubleOrNull() ?: return null
        if (!h.isFinite() || !m.isFinite()) return null
        return (h * 60 + m).toInt()
    }

    fun minutesToTime(totalMinutes: Int): String {
        val m = ((totalMinutes % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    private fun classifyMinute(minuteOfDay: Double, b: Boundaries): Category {
        val m = ((minuteOfDay % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        if (m >= b.nightStartMin && m < b.nightEndMin) return Category.NIGHT
        if (m >= b.nightEndMin || m < b.saharEndMin) return Category.SAHAR
        return Category.NORMAL
    }

    /**
     * Percent tied to the bracket the service STARTED in.
     *
     * Business rule preserved from the JS app: for the per-kilometre amount the
     * whole service is priced at the start bracket's percent — unlike the hourly
     * part, there is no time-slicing here.
     */
    fun startBracketPercent(
        startTime: String?,
        nightPercent: Double,
        saharPercent: Double,
        b: Boundaries = DEFAULT_BOUNDARIES,
    ): Double {
        val startMin = timeToMinutes(startTime) ?: return 0.0
        return when (classifyMinute(startMin.toDouble(), b)) {
            Category.NIGHT -> if (nightPercent.isFinite()) nightPercent else 0.0
            Category.SAHAR -> if (saharPercent.isFinite()) saharPercent else 0.0
            Category.NORMAL -> 0.0
        }
    }

    /**
     * Splits [startTime]..[endTime] into the three brackets, in decimal hours.
     * Midnight crossing is handled (e.g. 22:00 -> 02:00). end == start yields zero.
     */
    fun splitDuration(
        startTime: String?,
        endTime: String?,
        b: Boundaries = DEFAULT_BOUNDARIES,
    ): SliceHours {
        val startRaw = timeToMinutes(startTime) ?: return SliceHours(0.0, 0.0, 0.0)
        val endRaw = timeToMinutes(endTime) ?: return SliceHours(0.0, 0.0, 0.0)

        val start = startRaw
        var end = endRaw
        if (end <= start) end += DAY_MINUTES // crosses midnight, or end == start -> zero
        if (end == start) return SliceHours(0.0, 0.0, 0.0)

        // Collect bracket boundaries for every day-period covering [start, end)
        // so the span splits into non-overlapping pieces.
        val boundaryPoints = intArrayOf(b.saharEndMin, b.nightStartMin, b.nightEndMin)
        val cuts = LinkedHashSet<Int>()
        cuts.add(start); cuts.add(end)
        val kMin = floor(start.toDouble() / DAY_MINUTES).toInt() - 1
        val kMax = kotlin.math.ceil(end.toDouble() / DAY_MINUTES).toInt() + 1
        for (k in kMin..kMax) {
            for (p in boundaryPoints) {
                val point = p + k * DAY_MINUTES
                if (point > start && point < end) cuts.add(point)
            }
        }

        val sorted = cuts.sorted()
        var normal = 0.0; var night = 0.0; var sahar = 0.0
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]; val c = sorted[i + 1]
            if (c <= a) continue
            val mid = (a + c) / 2.0
            val hours = (c - a) / 60.0
            when (classifyMinute(mid, b)) {
                Category.NORMAL -> normal += hours
                Category.NIGHT -> night += hours
                Category.SAHAR -> sahar += hours
            }
        }
        return SliceHours(normal, night, sahar)
    }

    /**
     * Hourly income with night/dawn percents applied only to each bracket's real share.
     *
     * If start/end are missing (older records), the whole duration counts as normal,
     * preserving the previous behaviour. The bracket hours are scaled to the
     * user-entered [hours] field — that field stays the source of truth and only the
     * *ratio* is derived from start/end.
     */
    fun computeHourlyBreakdown(
        hours: Double,
        startTime: String?,
        endTime: String?,
        hourlyBaseRate: Double,
        nightPercent: Double,
        saharPercent: Double,
        b: Boundaries = DEFAULT_BOUNDARIES,
    ): HourlyBreakdown {
        val safeHours = if (hours.isFinite()) max(0.0, hours) else 0.0
        val nightMultiplier = 1 + (if (nightPercent.isFinite()) nightPercent else 0.0) / 100
        val saharMultiplier = 1 + (if (saharPercent.isFinite()) saharPercent else 0.0) / 100

        var normalHours = safeHours
        var nightHours = 0.0
        var saharHours = 0.0

        if (safeHours > 0 && !startTime.isNullOrEmpty() && !endTime.isNullOrEmpty()) {
            val slice = splitDuration(startTime, endTime, b)
            val sliceTotal = slice.total
            if (sliceTotal > 0) {
                normalHours = safeHours * (slice.normal / sliceTotal)
                nightHours = safeHours * (slice.night / sliceTotal)
                saharHours = safeHours * (slice.sahar / sliceTotal)
            }
        }

        val rate = if (hourlyBaseRate.isFinite()) hourlyBaseRate else 0.0
        val normalAmount = normalHours * rate
        val nightAmount = nightHours * rate * nightMultiplier
        val saharAmount = saharHours * rate * saharMultiplier

        return HourlyBreakdown(
            normalHours = normalHours,
            nightHours = nightHours,
            saharHours = saharHours,
            normalAmount = normalAmount,
            nightAmount = nightAmount,
            saharAmount = saharAmount,
            totalAmount = normalAmount + nightAmount + saharAmount,
        )
    }
}
