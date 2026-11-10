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
 * Daily WorkManager job for [AutoBackupWorker], aimed at 00:00. Same KEEP-policy shape
 * as [ReminderScheduler] (re-enqueueing with a fresh delay on every start would keep
 * pushing the run back), on its own work name and its own switch.
 *
 * WorkManager is inexact by design and no exact-alarm permission is used, so the run
 * lands shortly after midnight — later if the phone is dozing or switched off, in which
 * case it runs once the phone is back. The per-day guard in [AutoBackup] makes any
 * extra run harmless.
 */
object AutoBackupScheduler {

    const val WORK_NAME = "car_manager_auto_backup"
    private const val WORK_CATCH_UP = "car_manager_auto_backup_now"

    /** Aligns the schedule with the switch. Safe to call on every start. */
    fun sync(context: Context) {
        if (!SettingsStore.get(context).current.autoBackupEnabled) {
            cancel(context)
            return
        }
        schedule(context)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNextMidnightMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** One backup soon, without touching the nightly schedule (used right after switching on). */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
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

    const val RUN_TIME_LABEL = "هر شب ساعت ۲۴:۰۰ (۰۰:۰۰)"

    private fun delayToNextMidnightMinutes(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)   // the coming midnight, never "today 00:00" (already past)
        }
        return ((target.timeInMillis - now.timeInMillis) / 60000L).coerceAtLeast(1L)
    }
}
