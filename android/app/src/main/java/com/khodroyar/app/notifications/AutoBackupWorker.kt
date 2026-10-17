package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khodroyar.app.data.backup.AutoBackup
import com.khodroyar.app.data.prefs.SettingsStore

/** Runs the daily automatic backup (see [AutoBackup] for where and how it is written). */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SettingsStore.get(applicationContext).current.autoBackupEnabled) return Result.success()
        return try {
            if (AutoBackup.run(applicationContext)) Result.success()
            else if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        } catch (_: Exception) {
            // Retry with WorkManager's backoff, but give up for today after a few tries
            // (e.g. a full disk) so a broken run can't loop; tomorrow's run starts fresh.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
