package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.prefs.SettingsStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * One daily WorkManager job evaluates every enabled reminder. Cheaper and far more
 * reliable on modern Android than the legacy per-reminder JS timers, which died with
 * the JS runtime.
 *
 * ## Scheduling bug fixed here
 *
 * [sync] runs on every cold start (from `CarManagerApp` and from the UI shell). It used
 * to enqueue the periodic work with [ExistingPeriodicWorkPolicy.UPDATE] and a freshly
 * computed `setInitialDelay`, which **replaced the pending work's trigger time on every
 * launch**. Opening the app before the daily run time therefore pushed the run to the
 * next day, indefinitely, for anyone who opens the app regularly. The periodic work is
 * now enqueued with [ExistingPeriodicWorkPolicy.KEEP], so an existing schedule is never
 * disturbed, and a separate one-time [WORK_CATCH_UP] request handles the "evaluate now"
 * cases (reminders just switched on, permission just granted) explicitly.
 *
 * No exact-alarm permission is used or needed: these are day-granularity reminders, so
 * WorkManager's inexact windows are appropriate and survive reboots on their own.
 */
object ReminderScheduler {

    const val WORK_NAME = "car_manager_daily_reminders"
    private const val WORK_CATCH_UP = "car_manager_reminders_catch_up"
    private const val RUN_HOUR = 9

    /** Aligns the schedule with the current switches. Safe to call on every start. */
    fun sync(context: Context) {
        val settings = SettingsStore.get(context).current
        if (!settings.anyReminderEnabled) {
            cancel(context)
            return
        }
        schedule(context)
    }

    /**
     * Ensures the daily job exists. Uses KEEP so repeated calls cannot postpone a run
     * that is already pending.
     */
    fun schedule(context: Context) {
        Notifications.ensureChannels(context)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNextRunMinutes(), TimeUnit.MINUTES)
            // No network constraint: the app is fully offline.
            .setConstraints(Constraints.Builder().build())
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /**
     * Evaluates the reminders once, soon, without touching the periodic schedule.
     * Used when the user switches a reminder on or grants the notification permission,
     * so they see the effect immediately instead of waiting for tomorrow morning.
     */
    fun runNow(context: Context) {
        Notifications.ensureChannels(context)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setConstraints(Constraints.Builder().build())
            .addTag(WORK_CATCH_UP)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_CATCH_UP, ExistingWorkPolicy.REPLACE, request,
        )
    }

    /** Rebuilds the schedule from scratch — used after a backup restore. */
    fun reschedule(context: Context) {
        cancel(context)
        sync(context)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * True when the daily job is actually enqueued/running. Surfaced in Settings so a
     * broken schedule is visible instead of being a silent no-op like before.
     */
    fun isScheduled(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)

    /** Human-readable run time, for the Settings status card. */
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

    /** Days until a Jalali expiry date, or null when the date is empty/invalid. */
    fun daysUntil(date: String): Int? {
        if (Jalali.parse(date) == null) return null
        return Jalali.daysBetween(Jalali.todayString(), date)
    }
}
