package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.khodroyar.app.data.prefs.SettingsStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily WorkManager job for [AutoServiceWorker] — same KEEP-policy shape as
 * [ReminderScheduler] (see that class for why: [ExistingPeriodicWorkPolicy.UPDATE] plus
 * a fresh `setInitialDelay` on every cold start pushes the run back indefinitely for
 * anyone who opens the app before the run time). Kept as its own unique work name and
 * its own switch ([com.khodroyar.app.data.prefs.AppSettings.autoServiceEnabled]) rather
 * than folded into [ReminderScheduler], so auto-registration's schedule is never
 * affected by turning notification reminders on or off, or vice versa.
 */
object AutoServiceScheduler {

    const val WORK_NAME = "car_manager_auto_service"
    private const val WORK_CATCH_UP = "car_manager_auto_service_catch_up"
    private const val RUN_HOUR = 21

    /** Aligns the schedule with the current switch. Safe to call on every start. */
    fun sync(context: Context) {
        if (!SettingsStore.get(context).current.autoServiceEnabled) {
            cancel(context)
            return
        }
        schedule(context)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoServiceWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNextRunMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** Runs once, soon — used right after the switch is turned on. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoServiceWorker>()
            .setConstraints(Constraints.Builder().build())
            .addTag(WORK_CATCH_UP)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_CATCH_UP, ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isScheduled(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)

    /** Human-readable run time, for the Settings/ServiceForm status line. */
    fun runTimeLabel(): String = "هر روز ساعت ${RUN_HOUR.toString().padStart(2, '0')}:00"

    private fun delayToNextRunMinutes(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, RUN_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_MONTH, 1)
        return ((target.timeInMillis - now.timeInMillis) / 60000L).coerceAtLeast(1L)
    }
}
