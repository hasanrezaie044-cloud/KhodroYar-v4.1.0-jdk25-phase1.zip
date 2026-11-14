package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.khodroyar.app.data.prefs.SettingsStore
import java.util.concurrent.TimeUnit

/** Inexact device-local 16:00. KEEP on cold start cannot keep pushing today's pending run into tomorrow. */
object AutoServiceScheduler {
    const val WORK_NAME = "car_manager_auto_service_16"
    const val DAILY_INPUT = "scheduled_daily_service"
    private const val LEGACY_WORK_NAME = "car_manager_auto_service"
    private const val WORK_CATCH_UP = "car_manager_auto_service_catch_up"

    fun sync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        if (!SettingsStore.get(context).current.autoServiceEnabled) cancel(context) else schedule(context)
    }
    private fun request(): androidx.work.PeriodicWorkRequest {
        val now = System.currentTimeMillis()
        val delay = AutoServiceTime.delayMillis(now)
        return PeriodicWorkRequestBuilder<AutoServiceWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        // The next wall-clock target is recalculated after each successful scheduled run. A fixed
        // 24-hour interval alone would move to 15:00/17:00 across DST. No exact alarm is requested.
        .setNextScheduleTimeOverride(now + delay)
        .setInputData(Data.Builder().putBoolean(DAILY_INPUT, true).build())
        .setConstraints(Constraints.Builder().build()).addTag(WORK_NAME).build()
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
    }
    /** Only after a run or a system time/zone change, never on ordinary app opens. UPDATE does not cancel a running DB phase. */
    fun realign(context: Context) {
        if (!SettingsStore.get(context).current.autoServiceEnabled) { cancel(context); return }
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request())
    }
    /** Existing opt-in catch-up behaviour; the worker's date guard still prevents duplicate records. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_CATCH_UP, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoServiceWorker>().setConstraints(Constraints.Builder().build()).addTag(WORK_CATCH_UP).build())
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_CATCH_UP)
    }
    fun isScheduled(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)
    fun runTimeLabel(): String = "هر روز حدود ساعت 16:00"
}
