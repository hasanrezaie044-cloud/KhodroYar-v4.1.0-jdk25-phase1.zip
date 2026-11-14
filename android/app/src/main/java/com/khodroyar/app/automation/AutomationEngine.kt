package com.khodroyar.app.automation

import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Pure execution coordinator, independently testable without Android/WorkManager/network. */
class AutomationEngine(private val record: suspend (AutomationRule, String, String) -> String) {
    data class Batch(val rules: List<AutomationRule>, val messages: List<String>, val retry: Boolean)

    suspend fun run(rules: List<AutomationRule>, now: Instant, manualHolidays: Set<String>, legacyServiceEnabled: Boolean): Batch {
        val messages = mutableListOf<String>()
        var retry = false
        val updated = rules.map { rule ->
            if (!rule.due(now, manualHolidays)) return@map rule
            if (rule.kind == RuleKind.SERVICE && legacyServiceEnabled) {
                messages += "«${rule.title}» متوقف شد: ثبت خودکار قدیمی سرویس فعال است؛ برای جلوگیری از ثبت دوگانه یکی را انتخاب کنید."
                return@map rule.copy(enabled = false, revision = rule.revision + 1, lastMessage = "توقف برای جلوگیری از ثبت دوگانهٔ سرویس")
            }
            val date = rule.schedule.dateAt(now)
            try {
                val detail = record(rule, date, rule.occurrenceId(date)).take(200)
                messages += "${rule.title}: $detail"
                rule.copy(lastDate = date, lastMessage = detail, failures = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val action = if (rule.kind == RuleKind.TRAFFIC) "دریافت اطلاعات" else "ثبت"
                val failures = (rule.failures + 1).coerceAtMost(3)
                val paused = failures == 3
                retry = retry || !paused
                messages += "${rule.title}: " + if (paused) "پس از سه خطا متوقف شد؛ کارهای خودکار را بررسی کنید." else "$action تأیید نشد؛ دوباره تلاش می‌شود."
                rule.copy(enabled = !paused, failures = failures,
                    lastMessage = if (paused) "توقف پس از سه خطا؛ نیاز به بررسی" else "$action تأیید نشد؛ در انتظار تلاش مجدد")
            }
        }
        return Batch(updated, messages, retry)
    }
}
