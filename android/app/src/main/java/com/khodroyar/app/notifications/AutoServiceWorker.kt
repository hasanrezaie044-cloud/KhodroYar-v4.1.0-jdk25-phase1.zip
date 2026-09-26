package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository

/**
 * "ثبت خودکار" — once a day, if the user has turned the switch on, registers a
 * service from the saved preset (route, km, hours, start/end time, tolls) with no
 * interaction. Runs from the same daily WorkManager job family as [ReminderWorker]
 * but is scheduled separately ([AutoServiceScheduler]) so it is never tied to the
 * notification-reminder switches — a user can want daily auto-registration with every
 * reminder off, or reminders on with auto-registration off.
 *
 * [AppSettings.autoServiceLastRunDate] is the dedup guard: WorkManager's periodic job
 * and the one-time catch-up request (fired right after the switch is turned on) can
 * both land on the same day, and this must never create two service records for one
 * day. The guard is a plain settings field, not a DB query, so it works identically
 * whether the day already has zero, one, or several manually-entered services.
 */
class AutoServiceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SettingsStore.get(ctx)
        val settings = store.current
        if (!settings.autoServiceEnabled || !settings.autoServicePresetSaved) return Result.success()

        val today = Jalali.todayString()
        if (settings.autoServiceLastRunDate == today) return Result.success()
        if (Jalali.parse(today) == null) return Result.success()

        return try {
            val repo = AppRepository(ctx)
            repo.addService(
                date = today,
                type = ServiceType.fromWire(settings.autoServiceType),
                carType = CarType.fromWire(settings.autoServiceCarType).wire,
                km = settings.autoServiceKm,
                hours = settings.autoServiceHours,
                workHours = settings.autoServiceHours,
                startTime = settings.autoServiceStartTime,
                endTime = settings.autoServiceEndTime,
                origin = settings.autoServiceOrigin,
                destination = settings.autoServiceDestination,
                passengers = "",
                requestNumber = "",
                tollCount = settings.autoServiceTollCount,
                missionFood = 0.0,
                missionToll = 0.0,
                missionFine = 0.0,
            )
            store.update { it.copy(autoServiceLastRunDate = today) }

            Notifications.ensureChannels(ctx)
            if (Notifications.canPost(ctx)) {
                val route = listOf(settings.autoServiceOrigin, settings.autoServiceDestination)
                    .filter { it.isNotBlank() }.joinToString(" ← ")
                Notifications.post(
                    ctx, Notifications.ID_AUTO_SERVICE, Notifications.CHANNEL_SUMMARY,
                    "ثبت خودکار سرویس",
                    if (route.isNotBlank()) "سرویس امروز ($route) به‌صورت خودکار ثبت شد." else "سرویس امروز به‌صورت خودکار ثبت شد.",
                )
            }
            Result.success()
        } catch (_: Exception) {
            // Same contract as ReminderWorker: never lose the schedule, let WorkManager
            // retry with backoff instead of silently skipping the day.
            Result.retry()
        }
    }
}
