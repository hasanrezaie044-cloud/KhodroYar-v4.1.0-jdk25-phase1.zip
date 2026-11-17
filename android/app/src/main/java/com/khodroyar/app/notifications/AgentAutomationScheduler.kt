package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.khodroyar.app.automation.AutomationStore
import java.util.concurrent.TimeUnit

/** Inexact, persistent, no foreground LLM/service, no exact-alarm permission, no network constraint. */
object AgentAutomationScheduler {
    const val WORK_NAME = "khodroyar_agent_daily_rules"
    private const val IMMEDIATE = "khodroyar_agent_daily_check"
    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (AutomationStore.get(context).rules.value.none { it.enabled }) {
            manager.cancelUniqueWork(WORK_NAME)
            manager.cancelUniqueWork(IMMEDIATE)
            return
        }
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AgentAutomationWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
    }
    fun checkNow(context: Context) {
        sync(context)
        if (AutomationStore.get(context).rules.value.none { it.enabled }) return
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AgentAutomationWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
    }
}
