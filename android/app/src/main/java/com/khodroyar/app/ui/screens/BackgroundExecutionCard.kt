package com.khodroyar.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.khodroyar.app.notifications.AgentAutomationScheduler
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Point-in-time diagnostics, refreshed on resume/tap. No polling, new permissions or battery exemption request. */
@Composable
internal fun BackgroundExecutionCard(onRefresh: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var batteryLimited by remember { mutableStateOf<Boolean?>(null) }
    var backgroundRestricted by remember { mutableStateOf(false) }
    var scheduler by remember { mutableStateOf<String?>(null) }
    var notifications by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        onRefresh()
        batteryLimited = !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        backgroundRestricted = Build.VERSION.SDK_INT >= 28 && (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        notifications = Notifications.canPost(context)
        scheduler = withContext(Dispatchers.IO) {
            runCatching {
                val rows = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AgentAutomationScheduler.WORK_NAME).get()
                when {
                    rows.any { it.state == WorkInfo.State.RUNNING } -> "در حال اجرا"
                    rows.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> "در صف اندروید"
                    else -> "کار فعالی در صف نیست"
                }
            }.getOrElse { "وضعیت در دسترس نیست" }
        }
    }
    SurfaceCard(modifier = Modifier.testTag("background-health"), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(Icons.Outlined.BatterySaver, size = 36.dp)
            Text(tr("اجرای پس‌زمینه", "Background execution"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, tr("بازبینی وضعیت", "Refresh status")) }
        }
        InfoRow(tr("زمان‌بند کارهای خودکار", "Automation scheduler"), scheduler ?: "…")
        InfoRow(tr("اعلان", "Notifications"), if (notifications) tr("مجاز", "Allowed") else tr("بسته", "Blocked"))
        InfoRow(tr("محدودیت پس‌زمینه", "Background restriction"), if (backgroundRestricted) tr("فعال", "Restricted") else tr("گزارش نشده", "Not reported"))
        if (batteryLimited == true || backgroundRestricted) {
            Text(tr("محدودیت باتری ممکن است اجرا را عقب بیندازد؛ اجرای دقیقه‌ای تضمین نمی‌شود.", "Battery restrictions may delay work; exact timing is not guaranteed."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr("تنظیمات باتری برنامه", "App battery settings")) }
        }
    }
}
