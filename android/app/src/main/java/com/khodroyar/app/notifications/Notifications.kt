package com.khodroyar.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.khodroyar.app.MainActivity
import com.khodroyar.app.R
import com.khodroyar.app.core.finance.MaintenanceType
import java.util.concurrent.TimeUnit

/**
 * Notification plumbing.
 *
 * Everything is local: WorkManager schedules, the app posts. No push service, no
 * network, and no permission beyond POST_NOTIFICATIONS, which was already declared.
 *
 * ## Why nothing arrived (root causes fixed here)
 *
 * 1. **The runtime permission was never asked for outside Settings.** On Android 13+
 *    POST_NOTIFICATIONS starts denied. [canPost] therefore returned false, so
 *    [post] returned early and `ReminderWorker` did its work and threw the result
 *    away. Silently. The permission is now requested on first launch (see
 *    `MainActivity`) and its state is reported in Settings.
 * 2. **`setOnlyAlertOnce(true)` on reminders with stable notification ids.** The
 *    first post alerted; every later post silently *updated* the same id, so a daily
 *    reminder never made a sound or peeked again after day one. Alert-once is now
 *    used only for the low-priority summaries.
 * 3. **The reminder channel was created at IMPORTANCE_DEFAULT.** Android freezes a
 *    channel's importance at creation, so raising it in code does nothing for an
 *    existing install. The reminder channel therefore has a new id
 *    ([CHANNEL_REMINDER]) at IMPORTANCE_HIGH and the old one is deleted, which is
 *    the only supported way to change it.
 * 4. **No content intent.** Tapping a reminder did nothing at all.
 */
object Notifications {

    const val CHANNEL_SUMMARY = "car_summary"

    /**
     * v2 of the reminder channel. The v1 id was created at IMPORTANCE_DEFAULT and its
     * importance can never be raised in place, so a new channel is the only way to get
     * reminders back to a visible, alerting importance. [ensureChannels] deletes v1.
     */
    const val CHANNEL_REMINDER = "car_reminder_v2"
    const val CHANNEL_TRAFFIC = "car_traffic"
    private const val CHANNEL_REMINDER_LEGACY = "car_reminder"

    // stable ids so a re-fired reminder replaces the previous one instead of stacking
    const val ID_AGENT = 1013
    const val ID_DAILY = 1001
    const val ID_WEEKLY = 1002
    const val ID_MONTHLY = 1003
    const val ID_INSTALLMENT = 1004
    const val ID_OIL = 1005
    const val ID_BODY_INSURANCE = 1006
    const val ID_VEHICLE_INSURANCE = 1007
    const val ID_INSPECTION = 1008
    const val ID_BACKUP = 1009
    const val ID_TEST = 1010
    const val ID_AUTO_SERVICE = 1011
    const val ID_TRAFFIC = 1012

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // Retire the pinned-to-DEFAULT channel from earlier builds.
        runCatching { manager.deleteNotificationChannel(CHANNEL_REMINDER_LEGACY) }

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, "خلاصه عملکرد", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "خلاصهٔ روزانه، هفتگی و ماهانهٔ سرویس‌ها و درآمد"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "یادآورها", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "اقساط، نگهداری خودرو، بیمه، معاینه فنی و پشتیبان‌گیری"
                    enableVibration(true)
                    enableLights(true)
                    lightColor = BRAND_COLOR
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRAFFIC, "ترافیک", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "هشدار سنگین شدن مسیر مبارکه–اصفهان"
                    enableVibration(true)
                    enableLights(true)
                    lightColor = BRAND_COLOR
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
    }

    /** True when the POST_NOTIFICATIONS runtime permission is granted (always true < API 33). */
    fun permissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True when the user has not switched the app's notifications off in system settings. */
    fun systemEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True when a specific channel is not blocked or muted to IMPORTANCE_NONE. */
    fun channelEnabled(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** True when the app may actually post a notification right now. */
    fun canPost(context: Context): Boolean = permissionGranted(context) && systemEnabled(context)

    /** Everything Settings needs to explain the current state to the user. */
    data class Status(
        val permissionGranted: Boolean,
        val systemEnabled: Boolean,
        val reminderChannelEnabled: Boolean,
        val summaryChannelEnabled: Boolean,
    ) {
        val healthy: Boolean get() = permissionGranted && systemEnabled && reminderChannelEnabled
    }

    fun status(context: Context): Status = Status(
        permissionGranted = permissionGranted(context),
        systemEnabled = systemEnabled(context),
        reminderChannelEnabled = channelEnabled(context, CHANNEL_REMINDER),
        summaryChannelEnabled = channelEnabled(context, CHANNEL_SUMMARY),
    )

    /** Opens the OS notification settings for this app, for whatever we cannot fix in-app. */
    fun openSystemSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Extra read by MainActivity: which part of the app a tapped notification opens. */
    const val EXTRA_TARGET = "khodroyar.target"
    const val TARGET_MAINTENANCE = "maintenance"
    const val TARGET_FINANCE = "finance"
    const val TARGET_INSURANCE = "insurance"
    const val TARGET_SETTINGS = "settings"
    const val TARGET_CALENDAR = "calendar"
    const val TARGET_HOME = "home"
    const val TARGET_AGENT = "agent"
    const val TARGET_TRAFFIC = "traffic"
    const val TARGET_SERVICES = "services"
    const val TARGET_BACKUP = "backup"

    /** Closed, internal destination; notification extras never select arbitrary screens or execute writes. */
    fun maintenanceTarget(type: MaintenanceType): String = "$TARGET_MAINTENANCE:${type.wire}"
    fun maintenanceTypeFromTarget(target: String): MaintenanceType? = MaintenanceType.entries.firstOrNull {
        target == maintenanceTarget(it)
    }

    private const val GROUP_KEY = "khodroyar.notifications"
    private const val BRAND_COLOR = 0xFF5B4BDB.toInt()

    /**
     * How one notification looks: the emoji glyph on its coloured round large icon, the
     * sub-text next to the app name, its accent colour, and where a tap lands.
     */
    private data class Look(
        val glyph: String,
        val tint: Int,
        val subText: String,
        val target: String,
    )

    private fun lookFor(id: Int): Look = when (id) {
        ID_AGENT -> Look("✨", 0xFF5B4BDB.toInt(), "کارهای خودکار دستیار", TARGET_AGENT)
        ID_DAILY, ID_WEEKLY, ID_MONTHLY -> Look("📊", 0xFF2F9E8F.toInt(), "خلاصه عملکرد", TARGET_HOME)
        ID_INSTALLMENT -> Look("💳", 0xFFE8590C.toInt(), "اقساط", TARGET_FINANCE)
        ID_OIL -> Look("🛢️", 0xFFF08C00.toInt(), "نگهداری خودرو", TARGET_MAINTENANCE)
        ID_BODY_INSURANCE, ID_VEHICLE_INSURANCE -> Look("🛡️", 0xFF1C7ED6.toInt(), "بیمه", TARGET_INSURANCE)
        ID_INSPECTION -> Look("📋", 0xFF7048E8.toInt(), "معاینه فنی", TARGET_SETTINGS)
        ID_BACKUP -> Look("💾", 0xFF2B8A3E.toInt(), "پشتیبان‌گیری", TARGET_SETTINGS)
        ID_AUTO_SERVICE -> Look("⚡", 0xFF5B4BDB.toInt(), "ثبت خودکار", TARGET_CALENDAR)
        ID_TRAFFIC -> Look("🚦", 0xFFE03131.toInt(), "ترافیک", TARGET_TRAFFIC)
        else -> Look("🚗", BRAND_COLOR, "مدیریت خودرو", TARGET_HOME)
    }

    /** A round, coloured badge with the glyph centred — drawn, so no extra resources. */
    private fun largeIcon(look: Look): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.tint }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(look.glyph, size / 2f, baseline, text)
        return bitmap
    }

    /** Tapping a notification now opens the app instead of doing nothing. */
    private fun contentIntent(context: Context, requestCode: Int, target: String = TARGET_HOME): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, target)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    fun post(context: Context, id: Int, channel: String, title: String, body: String, maintenanceTypes: List<String> = emptyList()) {
        if (!canPost(context)) return
        ensureChannels(context)
        val isSummary = channel == CHANNEL_SUMMARY
        val look = lookFor(id)
        val parts = if (id == ID_OIL) maintenanceTypes.distinct().mapNotNull { wire -> MaintenanceType.TRACKED.firstOrNull { it.wire == wire } } else emptyList()
        val target = parts.firstOrNull()?.let(::maintenanceTarget) ?: look.target
        val lines = body.split('\n').filter { it.isNotBlank() }

        // What shows on a locked screen: no amounts, no names — the details appear
        // only once the phone is unlocked.
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(look.tint)
            .setContentTitle("مدیریت خودرو")
            .setContentText(look.subText)
            .build()

        val style: NotificationCompat.Style = if (lines.size > 1) {
            // Several items (instalments, parts due…) read as a tidy list, not a wall of text.
            NotificationCompat.InboxStyle().also { inbox ->
                lines.take(6).forEach { inbox.addLine(it) }
                inbox.setBigContentTitle(title)
                inbox.setSummaryText(look.subText)
            }
        } else {
            NotificationCompat.BigTextStyle().bigText(body).setBigContentTitle(title)
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setLargeIcon(largeIcon(look))
            .setColor(look.tint)
            .setSubText(look.subText)
            .setContentTitle(title)
            .setContentText(if (lines.size > 1) "${lines.size} مورد" else body)
            .setStyle(style)
            .setGroup(GROUP_KEY)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .apply { if (isSummary) setTimeoutAfter(TimeUnit.HOURS.toMillis(14)) }
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(contentIntent(context, id, target))
            .apply {
                if (parts.isEmpty()) addAction(0, "مشاهده", contentIntent(context, id + 5000, target))
                else parts.take(3).forEach { part ->
                    addAction(0, "ثبت ${part.label}", contentIntent(context, id + 5000 + part.ordinal, maintenanceTarget(part)))
                }
            }
            .setCategory(if (isSummary) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (isSummary) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setDefaults(if (isSummary) 0 else Notification.DEFAULT_ALL)
            // Alert-once is right for a rolling summary, and wrong for a reminder that
            // reuses a stable id every day — that was silencing every repeat.
            .setOnlyAlertOnce(isSummary)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // permission revoked between the check and the post — nothing to do
        }
    }

    /** Settings → "ارسال اعلان آزمایشی". Returns false when the app may not post. */
    fun sendTest(context: Context): Boolean {
        if (!canPost(context)) return false
        post(
            context, ID_TEST, CHANNEL_REMINDER,
            "اعلان آزمایشی",
            "اعلان‌ها به‌درستی کار می‌کنند.",
        )
        return true
    }
}
