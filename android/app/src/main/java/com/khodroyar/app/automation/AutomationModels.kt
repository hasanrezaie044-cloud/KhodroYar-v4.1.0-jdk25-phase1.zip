package com.khodroyar.app.automation

import com.khodroyar.app.core.holidays.WorkCalendar
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.ServiceType
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class RuleKind { EXPENSE, SERVICE, TRAFFIC }
enum class RepeatFrequency(val wire: String, val label: String) {
    DAILY("daily", "هر روز، حتی تعطیلات"),
    WORKDAYS("workdays", "روزهای کاری؛ به‌جز پنجشنبه، جمعه و تعطیلات تقویم"),
    WEEKDAYS("weekdays", "روزهای انتخاب‌شدهٔ هفته"),
}

/** Snapshot, not a live pointer to settings: a later preset edit cannot change approved work. */
data class ServiceTemplate(
    val type: String,
    val carType: String,
    val origin: String,
    val destination: String,
    val km: Double,
    val hours: Double,
    val startTime: String,
    val endTime: String,
    val tollCount: Int,
    val passengers: String = "",
) {
    fun valid(): Boolean = ServiceType.fromWireOrNull(type) != null && CarType.entries.any { it.wire == carType } &&
        origin.isNotBlank() && destination.isNotBlank() && origin.length <= 80 && destination.length <= 80 &&
        passengers.length <= 80 && km.isFinite() && km in 0.0..3000.0 && hours.isFinite() && hours in 0.0..24.0 &&
        (km > 0 || hours > 0 || type in ServiceType.FIXED_AMOUNT_TYPES.map { it.wire }) &&
        tollCount in 0..100 && listOf(startTime, endTime).all { it.isEmpty() || validTime(it) }

    fun summary(includePassengers: Boolean = false): String = "$origin ← $destination · ${ServiceType.fromWire(type).label} · $km کیلومتر · $hours ساعت" +
        "\nزمان سفر: ${startTime.ifBlank { "تعیین نشده" }} تا ${endTime.ifBlank { "تعیین نشده" }} · عوارض: $tollCount · خودرو: ${CarType.fromWire(carType).label}" +
        if (includePassengers && passengers.isNotBlank()) "\nمسافر/مخاطب: $passengers" else ""
}

data class RepeatSchedule(
    val frequency: RepeatFrequency,
    val startDate: String,
    val time: String = "21:00",
    val zoneId: String = ZoneId.systemDefault().id,
    val endDate: String = "",
    /** Jalali convention: Saturday = 0 ... Friday = 6. */
    val weekdays: Set<Int> = emptySet(),
) {
    fun valid(): Boolean = validDate(startDate) && (endDate.isEmpty() || validDate(endDate) && endDate >= startDate) &&
        validTime(time) && runCatching { ZoneId.of(zoneId) }.isSuccess &&
        (if (frequency == RepeatFrequency.WEEKDAYS) weekdays.isNotEmpty() && weekdays.all { it in 0..6 } else weekdays.isEmpty())

    fun allows(date: String, manualHolidays: Set<String>): Boolean = validDate(date) && date >= startDate &&
        (endDate.isEmpty() || date <= endDate) && when (frequency) {
            RepeatFrequency.DAILY -> true
            RepeatFrequency.WORKDAYS -> !WorkCalendar.isNonWorkingDay(date, manualHolidays)
            RepeatFrequency.WEEKDAYS -> Jalali.dayOfWeek(date) in weekdays
        }

    fun dateAt(now: Instant): String {
        val d = now.atZone(ZoneId.of(zoneId)).toLocalDate()
        return Jalali.fromGregorian(d.year, d.monthValue, d.dayOfMonth).toString()
    }

    fun reachedTime(now: Instant): Boolean = now.atZone(ZoneId.of(zoneId)).toLocalTime() >= LocalTime.parse(time)

    fun summary(): String = buildString {
        append(if (frequency == RepeatFrequency.WEEKDAYS) weekdays.sorted().joinToString("، ") { Jalali.WEEKDAY_NAMES[it] } else frequency.label)
        append(" · حدود ساعت ").append(time).append("\nاز ").append(startDate)
        append(if (endDate.isBlank()) " · بدون تاریخ پایان" else " تا $endDate")
        append("\nمنطقهٔ زمانی: ").append(zoneId)
    }

    /** Human preview only. The worker always re-evaluates calendar/holidays before writing. */
    fun nextRun(now: Instant, manualHolidays: Set<String>, lastDate: String = ""): String? {
        if (!valid()) return null
        var date = dateAt(now).let { if (it < startDate) startDate else it }
        repeat(370) {
            if (allows(date, manualHolidays) && date != lastDate) return "$date · $time" + if (date == dateAt(now) && reachedTime(now)) " (در انتظار فرصت اجرا)" else ""
            date = Jalali.addDays(date, 1)
        }
        return null
    }
}

data class AutomationRule(
    val id: String,
    val kind: RuleKind,
    val title: String,
    val schedule: RepeatSchedule,
    val amount: Double = 0.0,
    val category: String = "other",
    val service: ServiceTemplate? = null,
    val enabled: Boolean = true,
    val lastDate: String = "",
    val lastMessage: String = "هنوز اجرا نشده",
    val failures: Int = 0,
    val revision: Int = 1,
) {
    fun valid(): Boolean = runCatching { UUID.fromString(id) }.isSuccess && title.isNotBlank() && title.length <= 80 &&
        title.none { it.isISOControl() } && schedule.valid() && revision > 0 && failures in 0..3 &&
        (lastDate.isBlank() || validDate(lastDate)) && when (kind) {
            RuleKind.EXPENSE -> amount.isFinite() && amount in 1.0..1_000_000_000.0 && category in EXPENSE_CATEGORIES && service == null
            RuleKind.SERVICE -> service?.valid() == true
            RuleKind.TRAFFIC -> service == null && amount == 0.0
        }

    /** Stable across retries, worker overlap and edits. Never overwrites a manually edited row. */
    fun occurrenceId(date: String): String = UUID.nameUUIDFromBytes("khodroyar:automation:$id:$date".toByteArray(Charsets.UTF_8)).toString()

    fun due(now: Instant, manualHolidays: Set<String>): Boolean = valid() && enabled &&
        schedule.dateAt(now).let { it != lastDate && schedule.allows(it, manualHolidays) } && schedule.reachedTime(now)

    companion object {
        val EXPENSE_CATEGORIES = setOf("cigarette", "coffee", "restaurant", "clothing", "food", "transport", "health", "home", "entertainment", "education", "bills", "other")
    }
}

fun validTime(value: String): Boolean = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(value)
fun validDate(value: String): Boolean = Regex("1[3-6]\\d{2}/\\d{2}/\\d{2}").matches(value) &&
    Jalali.parse(value)?.let { Jalali.isValid(it.year, it.month, it.day) } == true
