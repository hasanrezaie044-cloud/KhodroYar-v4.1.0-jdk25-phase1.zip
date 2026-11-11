package com.khodroyar.app.automation

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.Duration

enum class AutomationHealth(val label: String) {
    FAILED("ناموفق"), PAUSED("متوقف"), COMPLETED("انجام‌شدهٔ امروز"), WAITING("منتظر فرصت اجرا"), LATE("دیرکرد؛ هنوز ثبت نشده"), SCHEDULED("زمان‌بندی‌شده"), EXPIRED("بازه تمام شده")
}

/** Status from persisted outcomes and the rule's captured timezone, never a guess about a running worker. */
fun AutomationRule.health(now: Instant, holidays: Set<String>): AutomationHealth {
    if (failures > 0) return AutomationHealth.FAILED
    if (!enabled) return AutomationHealth.PAUSED
    val date = schedule.dateAt(now)
    if (schedule.endDate.isNotBlank() && date > schedule.endDate) return AutomationHealth.EXPIRED
    if (lastDate == date) return AutomationHealth.COMPLETED
    if (!due(now, holidays)) return AutomationHealth.SCHEDULED
    val local = now.atZone(ZoneId.of(schedule.zoneId))
    val planned = local.toLocalDate().atTime(LocalTime.parse(schedule.time)).atZone(local.zone).toInstant()
    return if (Duration.between(planned, now).toMinutes() >= 30) AutomationHealth.LATE else AutomationHealth.WAITING
}
