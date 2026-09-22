package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.finance.LoanCalculator
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository

/**
 * Evaluates every enabled reminder once a day. Restores the legacy reminder set:
 * daily / weekly / monthly summaries, installment reminders, oil change, body
 * insurance, vehicle insurance, technical inspection and periodic backup.
 *
 * Expiry reminders fire at 14 days and 3 days before the date, and once it has
 * passed — the legacy thresholds.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Notifications.ensureChannels(ctx)
        // Nothing can be delivered without the runtime permission. Returning success
        // (rather than retry) is right: retrying cannot grant a permission. The Settings
        // health card is what tells the user this is why they saw nothing.
        if (!Notifications.canPost(ctx)) return Result.success()

        val settings = SettingsStore.get(ctx).current
        val repo = AppRepository(ctx)
        val today = Jalali.todayString()
        val ymd = Jalali.parse(today) ?: return Result.success()

        try {
            if (settings.remindDailySummary) dailySummary(ctx, repo, today)
            if (settings.remindWeeklySummary && Jalali.dayOfWeek(ymd.year, ymd.month, ymd.day) == 6) {
                weeklySummary(ctx, repo, today)
            }
            if (settings.remindMonthlySummary && ymd.day == 1) monthlySummary(ctx, repo, today)
            if (settings.remindInstallments) installments(ctx, repo, today)
            if (settings.remindOilChange) oilChange(ctx, repo)
            if (settings.remindBodyInsurance) {
                expiry(ctx, Notifications.ID_BODY_INSURANCE, "بیمه بدنه", settings.bodyInsuranceDate)
            }
            if (settings.remindVehicleInsurance) {
                expiry(ctx, Notifications.ID_VEHICLE_INSURANCE, "بیمه شخص ثالث", settings.vehicleInsuranceDate)
            }
            if (settings.remindInspection) {
                expiry(ctx, Notifications.ID_INSPECTION, "معاینه فنی", settings.inspectionDate)
            }
            if (settings.remindBackup) backupReminder(ctx, settings.lastBackupAt, settings.backupIntervalDays)
        } catch (_: Exception) {
            // A reminder must never crash the app or lose the schedule. Unlike before,
            // an unexpected failure now asks WorkManager to retry with backoff instead
            // of silently reporting success and dropping the day's reminders.
            return Result.retry()
        }
        return Result.success()
    }

    private suspend fun dailySummary(ctx: Context, repo: AppRepository, today: String) {
        val services = repo.servicesForDateOnce(today)
        if (services.isEmpty()) return
        val income = services.sumOf { it.income }
        val km = services.sumOf { it.km }
        Notifications.post(
            ctx, Notifications.ID_DAILY, Notifications.CHANNEL_SUMMARY,
            "خلاصه امروز · $today",
            "${services.size} سرویس · ${Analytics.formatNumber(km)} کیلومتر · ${Analytics.formatNumber(income)} تومان درآمد",
        )
    }

    private suspend fun weeklySummary(ctx: Context, repo: AppRepository, today: String) {
        val (start, end) = Jalali.weekRange(today)
        val services = repo.servicesInRangeOnce(start, end)
        if (services.isEmpty()) return
        Notifications.post(
            ctx, Notifications.ID_WEEKLY, Notifications.CHANNEL_SUMMARY,
            "خلاصه این هفته",
            "$start تا $end — ${services.size} سرویس · ${Analytics.formatNumber(services.sumOf { it.income })} تومان",
        )
    }

    private suspend fun monthlySummary(ctx: Context, repo: AppRepository, today: String) {
        val prev = Jalali.addMonths(today, -1)
        val p = Jalali.parse(prev) ?: return
        val prefix = "${p.year}/${p.month.toString().padStart(2, '0')}"
        val services = repo.servicesByPrefixOnce(prefix)
        if (services.isEmpty()) return
        val fuel = repo.fuelsByPrefixOnce(prefix).sumOf { it.total }
        val maint = repo.maintenancesByPrefixOnce(prefix).sumOf { it.cost }
        val income = services.sumOf { it.income }
        Notifications.post(
            ctx, Notifications.ID_MONTHLY, Notifications.CHANNEL_SUMMARY,
            "خلاصه ماه ${Jalali.monthName(p.month)} ${p.year}",
            "${services.size} سرویس · درآمد ${Analytics.formatNumber(income)} · هزینه ${Analytics.formatNumber(fuel + maint)} تومان",
        )
    }

    private suspend fun installments(ctx: Context, repo: AppRepository, today: String) {
        val loans = repo.loansOnce()
        if (loans.isEmpty()) return
        val lines = ArrayList<String>()
        for (loan in loans) {
            val summary = repo.loanSummary(loan)
            val next = summary.nextInstallment ?: continue
            val days = Jalali.daysBetween(today, next.dueDate)
            if (days in 0..3 || days < 0) {
                val label = if (days < 0) "سررسید گذشته" else if (days == 0) "امروز" else "$days روز دیگر"
                lines.add("${loan.title}: قسط ${next.index} — ${Analytics.formatNumber(next.amount)} تومان ($label)")
            }
        }
        if (lines.isEmpty()) return
        Notifications.post(
            ctx, Notifications.ID_INSTALLMENT, Notifications.CHANNEL_REMINDER,
            "یادآور اقساط", lines.joinToString("\n"),
        )
    }

    private suspend fun oilChange(ctx: Context, repo: AppRepository) {
        val rates = repo.ratesFor(Jalali.currentYear())
        val last = repo.latestMaintenance("oil-change")
        val currentKm = repo.highestKm()
        val due = Analytics.oilChangeDue(last, currentKm, rates)
        if (!due.isOverdue && !due.isDueSoon) return
        val remaining = due.remainingKm ?: return
        val body = if (due.isOverdue) {
            "از موعد تعویض روغن ${Analytics.formatNumber(-remaining)} کیلومتر گذشته است."
        } else {
            "تا تعویض روغن بعدی ${Analytics.formatNumber(remaining)} کیلومتر باقی مانده است."
        }
        Notifications.post(ctx, Notifications.ID_OIL, Notifications.CHANNEL_REMINDER, "تعویض روغن", body)
    }

    /** 14 days before, 3 days before, and after expiry — the legacy thresholds. */
    private fun expiry(ctx: Context, id: Int, label: String, date: String) {
        val days = ReminderScheduler.daysUntil(date) ?: return
        val body = when {
            days < 0 -> "$label منقضی شده است (تاریخ: $date)."
            days == 0 -> "$label امروز به پایان می‌رسد."
            days <= 3 -> "$label تا $days روز دیگر منقضی می‌شود (تاریخ: $date)."
            days <= 14 -> "$label تا $days روز دیگر منقضی می‌شود (تاریخ: $date)."
            else -> return
        }
        if (days in 4..13) return // only the 14-day and 3-day windows fire
        Notifications.post(ctx, id, Notifications.CHANNEL_REMINDER, "یادآور $label", body)
    }

    private fun backupReminder(ctx: Context, lastBackupAt: Long, intervalDays: Int) {
        val interval = if (intervalDays <= 0) 14 else intervalDays
        val elapsedDays = if (lastBackupAt <= 0L) Int.MAX_VALUE
        else ((System.currentTimeMillis() - lastBackupAt) / 86_400_000L).toInt()
        if (elapsedDays < interval) return
        val body = if (lastBackupAt <= 0L) {
            "هنوز پشتیبانی تهیه نشده است. از تنظیمات یک فایل پشتیبان روی دستگاه ذخیره کنید."
        } else {
            "$elapsedDays روز از آخرین پشتیبان‌گیری گذشته است. یک نسخه پشتیبان جدید ذخیره کنید."
        }
        Notifications.post(ctx, Notifications.ID_BACKUP, Notifications.CHANNEL_REMINDER, "پشتیبان‌گیری", body)
    }
}
