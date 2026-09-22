package com.khodroyar.app

import android.app.Application
import androidx.work.Configuration
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.notifications.ReminderScheduler

/**
 * Application entry point.
 *
 * The manifest strips WorkManager's startup initializer for a faster cold start, so
 * WorkManager is configured here on demand instead — that pairing (removed initializer
 * + `Configuration.Provider` on the Application) is what makes `WorkManager.getInstance`
 * valid, and it must stay in place.
 *
 * Startup also makes sure the notification channels exist and that the reminder
 * schedule matches the current switches. `ReminderScheduler.sync` is idempotent and
 * uses KEEP, so calling it on every launch can no longer postpone a pending run — see
 * the root-cause note in [ReminderScheduler].
 */
class CarManagerApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        runCatching { ReminderScheduler.sync(this) }
    }
}
