package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ai.offline.ModelTrust
import com.khodroyar.app.ai.offline.OfflineModelUiState
import com.khodroyar.app.ai.offline.OfflineModelUiState.Phase
import com.khodroyar.app.ui.components.ConfirmDialog

/**
 * «مدیریت هوش مصنوعی آفلاین»: status, progress and Download / Pause / Cancel / Delete / Re-download
 * for the on-device Gemma package. Persian, always RTL.
 */
@Composable
internal fun OfflineAiManagementSection(
    state: OfflineModelUiState,
    meteredNetwork: () -> Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRedownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmRedownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth().testTag("offline-ai-management")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مدیریت هوش مصنوعی آفلاین", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusChip(state.phase)
                }
                Text("${state.modelTitle} · حدود ${gigabytes(state.totalBytes)} گیگابایت · اجرا فقط روی همین گوشی؛ داخل برنامه (APK) قرار ندارد و جداگانه دانلود می‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                when (state.phase) {
                    Phase.DOWNLOADING, Phase.PAUSED -> {
                        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).testTag("offline-ai-progress"))
                        Text("${gigabytes(state.downloadedBytes)} از ${gigabytes(state.totalBytes)} گیگابایت (${persian((state.fraction * 100).toInt().toString())}٪)",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("offline-ai-downloaded"))
                        if (state.phase == Phase.DOWNLOADING && state.bytesPerSecond > 0) {
                            Text("سرعت ${megabytes(state.bytesPerSecond)} مگابایت/ثانیه${state.remainingSeconds?.let { " · حدود ${duration(it)} مانده" } ?: ""}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.phase == Phase.DOWNLOADING) Text("تا پایان دانلود برنامه را باز نگه دارید؛ اگر قطع شد، با «ادامه» از همان نقطه ادامه می‌یابد.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Phase.VERIFYING -> {
                        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).testTag("offline-ai-progress"))
                        Text("در حال بررسی SHA-256 فایل (${persian((state.fraction * 100).toInt().toString())}٪)…", style = MaterialTheme.typography.bodyMedium)
                    }
                    Phase.READY -> Text("مدل آماده است (${gigabytes(state.downloadedBytes)} گیگابایت). در حالت «دستیار آفلاین» پیام‌ها و تصویرها فقط روی گوشی پردازش می‌شوند و هیچ سرویس آنلاینی صدا زده نمی‌شود.",
                        style = MaterialTheme.typography.bodyMedium)
                    Phase.NOT_INSTALLED -> Text("مدل نصب نشده است. تا وقتی نصب نشود، دستیار آفلاین با فرمان‌های داخلی برنامه کار می‌کند.",
                        style = MaterialTheme.typography.bodyMedium)
                    Phase.ERROR -> Unit
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("offline-ai-error"))
                }
                if (state.phase == Phase.READY) TrustNote(state.trust)
                if (!state.checksumPinned && state.phase != Phase.READY) WarningNote(
                    "هشدار: اثر انگشت SHA-256 این مدل هنوز داخل برنامه ثابت (pin) نشده است. فایل با SHA-256 اعلام‌شده توسط Hugging Face بررسی می‌شود؛ اگر آن فهرست در دسترس نباشد، فقط اندازهٔ فایل کنترل می‌شود.")
                if (state.lowRam) WarningNote("حافظهٔ RAM این گوشی (${gigabytes(state.deviceRamBytes)} گیگابایت) از مقدار پیشنهادی کمتر است؛ ممکن است مدل اجرا نشود یا کند باشد.")
                if (state.freeBytes >= 0 && state.phase != Phase.READY) Text("فضای آزاد: ${gigabytes(state.freeBytes)} گیگابایت",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Actions
                when (state.phase) {
                    Phase.NOT_INSTALLED -> Button(onClick = { confirmDownload = true }, modifier = Modifier.fillMaxWidth().testTag("offline-ai-download")) {
                        ActionLabel(Icons.Outlined.CloudDownload, "دانلود و آماده‌سازی هوش مصنوعی آفلاین")
                    }
                    Phase.DOWNLOADING, Phase.VERIFYING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).testTag("offline-ai-pause")) { ActionLabel(Icons.Outlined.Pause, "توقف موقت") }
                        OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f).testTag("offline-ai-cancel"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { ActionLabel(Icons.Outlined.Close, "لغو دانلود") }
                    }
                    Phase.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload, modifier = Modifier.weight(1f).testTag("offline-ai-resume")) { ActionLabel(Icons.Outlined.CloudDownload, "ادامهٔ دانلود") }
                        OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f).testTag("offline-ai-cancel"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { ActionLabel(Icons.Outlined.Close, "لغو دانلود") }
                    }
                    Phase.READY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmRedownload = true }, modifier = Modifier.weight(1f).testTag("offline-ai-redownload")) { ActionLabel(Icons.Outlined.Refresh, "دانلود دوباره") }
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f).testTag("offline-ai-delete"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { ActionLabel(Icons.Outlined.Delete, "حذف مدل") }
                    }
                    Phase.ERROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (state.downloadedBytes > 0) onDownload() else confirmDownload = true }, modifier = Modifier.weight(1f).testTag("offline-ai-retry")) {
                            ActionLabel(Icons.Outlined.Refresh, if (state.downloadedBytes > 0) "ادامه / تلاش دوباره" else "تلاش دوباره")
                        }
                        OutlinedButton(onClick = { confirmRedownload = true }, modifier = Modifier.weight(1f).testTag("offline-ai-redownload")) { ActionLabel(Icons.Outlined.CloudDownload, "دانلود دوباره") }
                    }
                }
                if (state.phase == Phase.ERROR || state.phase == Phase.PAUSED) TextButton(onClick = { confirmDelete = true },
                    modifier = Modifier.testTag("offline-ai-delete")) { Text("حذف همهٔ فایل‌های مدل", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (confirmDownload) {
            val metered = remember { meteredNetwork() }
            ConfirmDialog(
                title = "دانلود هوش مصنوعی آفلاین",
                message = buildString {
                    append("حدود ${gigabytes(state.totalBytes)} گیگابایت از Hugging Face (litert-community/gemma-4-E2B-it-litert-lm) دانلود و در حافظهٔ داخلی خصوصی برنامه ذخیره می‌شود.")
                    if (metered) append("\n\n⚠️ اینترنت فعلی «حجمی» (مثلاً داده همراه) است؛ ممکن است هزینهٔ زیادی داشته باشد. Wi‑Fi پیشنهاد می‌شود.")
                    if (!state.checksumPinned) append("\n\n⚠️ اثر انگشت SHA-256 این نسخه هنوز در برنامه ثابت نشده است. سلامت فایل با SHA-256 اعلام‌شدهٔ Hugging Face بررسی می‌شود و اگر آن در دسترس نباشد فقط اندازهٔ فایل بررسی می‌شود. فقط در صورت اعتماد ادامه دهید.")
                    if (state.lowRam) append("\n\n⚠️ RAM گوشی کمتر از مقدار پیشنهادی است و ممکن است مدل اجرا نشود.")
                    append("\n\nپس از نصب، گفتگو در حالت آفلاین بدون اینترنت و بدون هیچ سرویس آنلاینی انجام می‌شود.")
                },
                confirmLabel = "متوجه شدم، دانلود کن",
                onConfirm = { confirmDownload = false; onDownload() },
                onDismiss = { confirmDownload = false },
            )
        }
        if (confirmRedownload) ConfirmDialog(
            title = "دانلود دوباره", message = "فایل فعلی مدل حذف و نسخهٔ تازه (حدود ${gigabytes(state.totalBytes)} گیگابایت) دانلود می‌شود. ادامه می‌دهید؟",
            confirmLabel = "حذف و دانلود دوباره", destructive = true,
            onConfirm = { confirmRedownload = false; onRedownload() }, onDismiss = { confirmRedownload = false })
        if (confirmDelete) ConfirmDialog(
            title = "حذف مدل آفلاین", message = "فایل مدل و هر دانلود نیمه‌کاره از گوشی حذف می‌شود و فضا آزاد می‌گردد. سوابق و گفتگوهای شما تغییری نمی‌کنند.",
            confirmLabel = "حذف مدل", destructive = true,
            onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false })
        if (confirmCancel) ConfirmDialog(
            title = "لغو دانلود", message = "دانلود متوقف و بخش دانلودشده حذف می‌شود. برای فقط متوقف‌کردن، «توقف موقت» را بزنید.",
            confirmLabel = "لغو و حذف", destructive = true,
            onConfirm = { confirmCancel = false; onCancel() }, onDismiss = { confirmCancel = false })
    }
}

/** One-line status used in the model picker sheet. */
internal fun offlineModelSummary(state: OfflineModelUiState): String = when (state.phase) {
    Phase.READY -> "${state.modelTitle} نصب است · آمادهٔ اجرا روی گوشی"
    Phase.DOWNLOADING -> "در حال دانلود ${persian((state.fraction * 100).toInt().toString())}٪"
    Phase.VERIFYING -> "در حال بررسی فایل مدل…"
    Phase.PAUSED -> "دانلود متوقف شده (${gigabytes(state.downloadedBytes)} از ${gigabytes(state.totalBytes)} گیگابایت)"
    Phase.ERROR -> state.error ?: "خطا در مدل آفلاین"
    Phase.NOT_INSTALLED -> "مدل نصب نشده · فرمان‌های داخلی بدون مدل زبانی"
}

@Composable
private fun StatusChip(phase: Phase) {
    val (label, color) = when (phase) {
        Phase.NOT_INSTALLED -> "نصب نشده" to MaterialTheme.colorScheme.outline
        Phase.DOWNLOADING -> "در حال دانلود" to MaterialTheme.colorScheme.primary
        Phase.VERIFYING -> "در حال بررسی" to MaterialTheme.colorScheme.primary
        Phase.PAUSED -> "متوقف" to MaterialTheme.colorScheme.tertiary
        Phase.READY -> "آماده" to Color(0xFF2E7D32)
        Phase.ERROR -> "خطا" to MaterialTheme.colorScheme.error
    }
    val icon = when (phase) {
        Phase.READY -> Icons.Outlined.CheckCircle
        Phase.ERROR -> Icons.Outlined.ErrorOutline
        Phase.DOWNLOADING, Phase.VERIFYING, Phase.PAUSED -> Icons.Outlined.CloudDownload
        Phase.NOT_INSTALLED -> Icons.Outlined.CloudDownload
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f), modifier = Modifier.testTag("offline-ai-status")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TrustNote(trust: ModelTrust?) {
    when (trust) {
        ModelTrust.PINNED -> Text("فایل با SHA-256 ثابت داخل برنامه تطبیق داده شد.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelTrust.PUBLISHED -> Text("فایل با SHA-256 اعلام‌شدهٔ Hugging Face تطبیق داده شد (هنوز در برنامه pin نشده است).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelTrust.UNVERIFIED, null -> WarningNote("فهرست SHA-256 هنگام دانلود در دسترس نبود؛ فقط اندازهٔ فایل بررسی شد. اگر مدل خطا داد، «دانلود دوباره» را بزنید.")
    }
}

@Composable
private fun WarningNote(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text)
}

private val FA_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
private fun persian(text: String): String = buildString(text.length) {
    text.forEach { c -> append(if (c in '0'..'9') FA_DIGITS[c - '0'] else if (c == '.') '٫' else c) }
}
private fun gigabytes(bytes: Long): String = persian(String.format(java.util.Locale.US, "%.2f", bytes.coerceAtLeast(0) / 1_000_000_000.0))
private fun megabytes(bytes: Long): String = persian(String.format(java.util.Locale.US, "%.1f", bytes / 1_000_000.0))
private fun duration(seconds: Long): String = when {
    seconds >= 3600 -> persian("${seconds / 3600}") + " ساعت و " + persian("${(seconds % 3600) / 60}") + " دقیقه"
    seconds >= 60 -> persian("${seconds / 60}") + " دقیقه"
    else -> persian("$seconds") + " ثانیه"
}
