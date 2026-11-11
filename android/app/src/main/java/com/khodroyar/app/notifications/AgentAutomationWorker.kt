package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khodroyar.app.automation.AutomationEngine
import com.khodroyar.app.automation.AutomationStore
import com.khodroyar.app.automation.RuleKind
import com.khodroyar.app.traffic.TrafficRepository
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant

class AgentAutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = AppRepository(applicationContext)
        val store = AutomationStore.get(applicationContext)
        var retry = false
        var messages: List<String> = emptyList()
        store.change { rules ->
            val batch = AutomationEngine { rule, date, id ->
                if (rule.kind == RuleKind.TRAFFIC) {
                    val snapshot = TrafficRepository().fetch(repo.settingsNow.neshanApiKey)
                    TrafficRepository.CORRIDORS.joinToString("؛ ") { c -> "${c.title.substringBefore('(')}: ${snapshot.statuses.getValue(c.id).labelFa}" }
                } else {
                    repo.recordAutomation(rule, date, id)
                    "ثبت $date در دیتابیس تأیید شد"
                }
            }.run(rules, Instant.now(),
                repo.manualHolidayDatesOnce(), repo.settingsNow.autoServiceEnabled)
            retry = batch.retry
            messages = batch.messages
            batch.rules
        }
        if (messages.isNotEmpty() && Notifications.canPost(applicationContext)) {
            Notifications.ensureChannels(applicationContext)
            Notifications.post(applicationContext, Notifications.ID_AGENT, Notifications.CHANNEL_SUMMARY,
                "دستیار · کارهای خودکار", messages.take(5).joinToString("\n"))
        }
        if (retry) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (runAttemptCount >= 2) Result.failure() else Result.retry()
    }
}
