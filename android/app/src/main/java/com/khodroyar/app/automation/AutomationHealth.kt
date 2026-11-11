package com.khodroyar.app.automation

import java.time.Instant

enum class AutomationHealth(val label: String) {
    FAILED("ناموفق"), PAUSED("متوقف"), COMPLETED("انجام‌شدهٔ امروز"), WAITING("منتظر فرصت اجرا"), SCHEDULED("زمان‌بندی‌شده"), EXPIRED("بازه تمام شده")
}

/** Status from persisted outcomes and the rule's captured timezone, never a guess about a running worker. */
fun AutomationRule.health(now: Instant, holidays: Set<String>): AutomationHealth {
    if (failures > 0) return AutomationHealth.FAILED
    if (!enabled) return AutomationHealth.PAUSED
    val date = schedule.dateAt(now)
    if (schedule.endDate.isNotBlank() && date > schedule.endDate) return AutomationHealth.EXPIRED
    if (lastDate == date) return AutomationHealth.COMPLETED
    return if (due(now, holidays)) AutomationHealth.WAITING else AutomationHealth.SCHEDULED
}
