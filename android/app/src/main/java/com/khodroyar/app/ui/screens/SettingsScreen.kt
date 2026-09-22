package com.khodroyar.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.backup.BackupFiles
import com.khodroyar.app.data.legacy.LegacyBackup
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.notifications.ReminderScheduler
import com.khodroyar.app.security.PinStore
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
import kotlinx.coroutines.launch

/**
 * Settings — and settings only.
 *
 * The tab this replaces ("بیشتر") was a launcher for the fuel / finance / maintenance /
 * rates screens as well as a settings page. Those tools moved to the right-edge panel,
 * and privacy mode moved back to the dashboard where it is one tap away. What is left
 * here is genuinely configuration: appearance, income goal, backup & restore, the
 * reminder set with a real health check, holidays, personnel and PIN security, plus an
 * About section.
 */
@Composable
fun SettingsScreen(repository: AppRepository, settings: AppSettings, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val sectionOpen = remember { mutableStateOf(setOf<String>()) }
    fun toggleSection(key: String) {
        sectionOpen.value = if (key in sectionOpen.value) sectionOpen.value - key else sectionOpen.value + key
    }
    val pinStore = remember { PinStore(context) }

    var backupJson by remember { mutableStateOf<String?>(null) }
    var restoreJson by remember { mutableStateOf<String?>(null) }
    var restorePreview by remember { mutableStateOf<LegacyBackup.Preview?>(null) }
    var goalText by remember { mutableStateOf(settings.monthlyIncomeGoal.trimNumber().digitsOnly()) }
    var showPinDialog by remember { mutableStateOf(false) }
    var lockEnabled by remember { mutableStateOf(pinStore.lockEnabled) }

    // Bumped whenever something might have changed the notification state, so the health
    // card re-reads it instead of showing a stale answer.
    var healthToken by remember { mutableStateOf(0) }
    val notificationStatus = remember(healthToken) { Notifications.status(context) }
    val scheduled = remember(healthToken, settings.anyReminderEnabled) { ReminderScheduler.isScheduled(context) }

    /* --- real device file storage: pick a folder/name, then actually write the file --- */
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFiles.MIME_JSON)
    ) { uri ->
        val json = backupJson
        if (uri == null || json == null) { toast.show("ذخیره پشتیبان لغو شد"); return@rememberLauncherForActivityResult }
        val ok = BackupFiles.writeToUri(context, uri, json)
        if (ok) {
            repository.markBackupDone()
            toast.show("فایل پشتیبان روی دستگاه ذخیره شد")
        } else {
            toast.show("ذخیره فایل پشتیبان انجام نشد")
        }
        backupJson = null
    }

    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = BackupFiles.readFromUri(context, uri)
        if (content == null) { toast.show("خواندن فایل انجام نشد"); return@rememberLauncherForActivityResult }
        restoreJson = content
        restorePreview = repository.previewBackup(content)
    }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        healthToken++
        toast.show(if (granted) "اجازه اعلان داده شد" else "اجازه اعلان داده نشد")
        if (granted) {
            ReminderScheduler.sync(context)
            ReminderScheduler.runNow(context)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
    ) {
        /* ------------------------------------------------------------- language */
        item { CollapsibleSectionTitle(
            "زبان / Language", icon = Icons.Outlined.Language,
            expanded = "language" in sectionOpen.value,
            onToggle = { toggleSection("language") },
            englishTitle = "Language"
        ) }
        if ("language" in sectionOpen.value) {
            item {
                SurfaceCard {
                    Text(
                        if (settings.language == AppSettings.LANGUAGE_EN) "Choose app language" else "زبان برنامه را انتخاب کنید",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentedSelector(
                        options = listOf(AppSettings.LANGUAGE_FA, AppSettings.LANGUAGE_EN),
                        selected = settings.language,
                        label = { if (it == AppSettings.LANGUAGE_EN) "English" else "فارسی" },
                        onSelect = { language -> repository.updateSettings { it.copy(language = language) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (settings.language == AppSettings.LANGUAGE_EN) "The app switches layout direction immediately." else "زبان و جهت راست‌به‌چپ / چپ‌به‌راست برنامه بلافاصله تغییر می‌کند.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        /* ---------------------------------------------------------- appearance */
        item { CollapsibleSectionTitle("ظاهر برنامه", icon = Icons.Outlined.Palette, expanded = "appearance" in sectionOpen.value, onToggle = { toggleSection("appearance") }, englishTitle = "Appearance") }
        if ("appearance" in sectionOpen.value) {
        item {
            SurfaceCard {
                Text("حالت نمایش", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                SegmentedSelector(
                    options = listOf(AppSettings.THEME_SYSTEM, AppSettings.THEME_LIGHT, AppSettings.THEME_DARK),
                    selected = settings.themeMode,
                    label = {
                        when (it) {
                            AppSettings.THEME_LIGHT -> "روشن"
                            AppSettings.THEME_DARK -> "تاریک"
                            else -> "سیستم"
                        }
                    },
                    onSelect = { mode -> repository.updateSettings { it.copy(themeMode = mode) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "حالت فعلی: ${themeLabel(settings)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            // Privacy mode is controlled from the dashboard now, on purpose.
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (settings.privacyMode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("حالت حریم خصوصی", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "از داشبورد با یک ضربه روی آیکن چشم روشن و خاموش می‌شود. " +
                                "وضعیت فعلی: ${if (settings.privacyMode) "روشن" else "خاموش"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        /* --------------------------------------------------------------- goal */
                }

        item { CollapsibleSectionTitle("هدف درآمد ماهانه", icon = Icons.Outlined.Flag, expanded = "income_goal" in sectionOpen.value, onToggle = { toggleSection("income_goal") }, englishTitle = "Monthly Income Goal") }
        if ("income_goal" in sectionOpen.value) {
        item {
            SurfaceCard {
                MoneyField("هدف ماهانه", goalText, { goalText = it }, imeAction = ImeAction.Done)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        repository.updateSettings { it.copy(monthlyIncomeGoal = goalText.asDouble()) }
                        toast.show("هدف درآمد ماهانه ذخیره شد")
                    },
                    modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text("ذخیره هدف") }
            }
        }

        /* ------------------------------------------------------ notifications */
                }

        item { CollapsibleSectionTitle("یادآورها و اعلان‌ها", icon = Icons.Outlined.NotificationsActive, expanded = "reminders" in sectionOpen.value, onToggle = { toggleSection("reminders") }, englishTitle = "Reminders & Notifications") }
        if ("reminders" in sectionOpen.value) {
        item {
            NotificationHealthCard(
                status = notificationStatus,
                scheduled = scheduled,
                anyReminderEnabled = settings.anyReminderEnabled,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Notifications.openSystemSettings(context)
                    }
                },
                onOpenSystemSettings = { Notifications.openSystemSettings(context) },
                onRecheck = {
                    ReminderScheduler.sync(context)
                    healthToken++
                    toast.show("وضعیت اعلان‌ها بازبینی شد")
                },
                onSendTest = {
                    val sent = Notifications.sendTest(context)
                    healthToken++
                    toast.show(if (sent) "اعلان آزمایشی ارسال شد" else "اجازه ارسال اعلان وجود ندارد")
                },
            )
        }
        item {
            SwitchRow("خلاصه روزانه", "پایان هر روز", settings.remindDailySummary) { v ->
                repository.updateSettings { it.copy(remindDailySummary = v) }
                ReminderScheduler.sync(context)
                if (v) ReminderScheduler.runNow(context)
                healthToken++
            }
        }
        item {
            SwitchRow("خلاصه هفتگی", "جمعه‌ها", settings.remindWeeklySummary) { v ->
                repository.updateSettings { it.copy(remindWeeklySummary = v) }
                ReminderScheduler.sync(context)
                healthToken++
            }
        }
        item {
            SwitchRow("خلاصه ماهانه", "اول هر ماه", settings.remindMonthlySummary) { v ->
                repository.updateSettings { it.copy(remindMonthlySummary = v) }
                ReminderScheduler.sync(context)
                healthToken++
            }
        }
        item {
            SwitchRow("یادآور اقساط", "۳ روز قبل و پس از سررسید", settings.remindInstallments) { v ->
                repository.updateSettings { it.copy(remindInstallments = v) }
                ReminderScheduler.sync(context)
                if (v) ReminderScheduler.runNow(context)
                healthToken++
            }
        }
        item {
            SwitchRow("یادآور تعویض روغن", "بر مبنای کیلومتر", settings.remindOilChange) { v ->
                repository.updateSettings { it.copy(remindOilChange = v) }
                ReminderScheduler.sync(context)
                if (v) ReminderScheduler.runNow(context)
                healthToken++
            }
        }
        item {
            SurfaceCard {
                Text(tr("تاریخ‌های انقضا", "Expiry Dates"), style = MaterialTheme.typography.titleMedium)
                Text(
                    "یادآور ۱۴ روز و ۳ روز قبل ارسال می‌شود.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(AppDimens.gutter))
                JalaliDateField("انقضای بیمه بدنه", settings.bodyInsuranceDate.ifBlank { Jalali.todayString() }, { d ->
                    repository.updateSettings { it.copy(bodyInsuranceDate = d) }
                })
                Spacer(Modifier.height(8.dp))
                SwitchRow("یادآور بیمه بدنه", "", settings.remindBodyInsurance) { v ->
                    repository.updateSettings { it.copy(remindBodyInsurance = v) }
                    ReminderScheduler.sync(context)
                    healthToken++
                }
                Spacer(Modifier.height(AppDimens.gutter))
                JalaliDateField("انقضای بیمه شخص ثالث", settings.vehicleInsuranceDate.ifBlank { Jalali.todayString() }, { d ->
                    repository.updateSettings { it.copy(vehicleInsuranceDate = d) }
                })
                Spacer(Modifier.height(8.dp))
                SwitchRow("یادآور بیمه شخص ثالث", "", settings.remindVehicleInsurance) { v ->
                    repository.updateSettings { it.copy(remindVehicleInsurance = v) }
                    ReminderScheduler.sync(context)
                    healthToken++
                }
                Spacer(Modifier.height(AppDimens.gutter))
                JalaliDateField("انقضای معاینه فنی", settings.inspectionDate.ifBlank { Jalali.todayString() }, { d ->
                    repository.updateSettings { it.copy(inspectionDate = d) }
                })
                Spacer(Modifier.height(8.dp))
                SwitchRow("یادآور معاینه فنی", "", settings.remindInspection) { v ->
                    repository.updateSettings { it.copy(remindInspection = v) }
                    ReminderScheduler.sync(context)
                    healthToken++
                }
            }
        }
        item {
            SwitchRow(
                "یادآور پشتیبان‌گیری دوره‌ای",
                "هر ${settings.backupIntervalDays} روز",
                settings.remindBackup,
            ) { v ->
                repository.updateSettings { it.copy(remindBackup = v) }
                ReminderScheduler.sync(context)
                healthToken++
            }
        }

        /* ------------------------------------------------------------- backup */
                }

        item { CollapsibleSectionTitle("پشتیبان‌گیری و بازگردانی", icon = Icons.Outlined.Backup, expanded = "backup" in sectionOpen.value, onToggle = { toggleSection("backup") }, englishTitle = "Backup & Restore") }
        if ("backup" in sectionOpen.value) {
        item {
            ActionRow(
                "ذخیره پشتیبان روی دستگاه",
                "انتخاب مسیر و ساخت فایل JSON واقعی",
                Icons.Outlined.SaveAlt,
            ) {
                scope.launch {
                    val json = repository.exportBackupJson()
                    backupJson = json
                    BackupFiles.writeInternalCopy(context, json, BackupFiles.suggestedFileName())
                    saveBackup.launch(BackupFiles.suggestedFileName())
                }
            }
        }
        item {
            ActionRow("اشتراک‌گذاری فایل پشتیبان", "ارسال با پیام‌رسان یا ایمیل", Icons.Outlined.Share) {
                scope.launch {
                    val json = repository.exportBackupJson()
                    val ok = BackupFiles.shareText(context, json, BackupFiles.suggestedFileName(), "اشتراک فایل پشتیبان")
                    toast.show(if (ok) "فایل پشتیبان آماده اشتراک شد" else "ساخت فایل پشتیبان انجام نشد")
                }
            }
        }
        item {
            ActionRow("بازگردانی از فایل", "پیش‌نمایش و تأیید قبل از جایگزینی", Icons.Outlined.Restore) {
                pickBackup.launch(arrayOf(BackupFiles.MIME_JSON, "text/plain", "*/*"))
            }
        }
        item {
            Text(
                if (settings.lastBackupAt > 0)
                    "آخرین پشتیبان‌گیری: ${daysAgoLabel(settings.lastBackupAt)}"
                else "تا کنون پشتیبانی ذخیره نشده است",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        /* ----------------------------------------------------------- holidays */
                }

        item { CollapsibleSectionTitle("تعطیلات", icon = Icons.Outlined.EventBusy, expanded = "holidays" in sectionOpen.value, onToggle = { toggleSection("holidays") }, englishTitle = "Holidays") }
        if ("holidays" in sectionOpen.value) {
        item { ManualHolidaysCard(repository) }

        /* ---------------------------------------------------------- personnel */
                }

        item { CollapsibleSectionTitle("پرسنل", icon = Icons.Outlined.Group, expanded = "personnel" in sectionOpen.value, onToggle = { toggleSection("personnel") }, englishTitle = "Personnel") }
        if ("personnel" in sectionOpen.value) {
        item { PersonnelCard(repository) }

        /* ------------------------------------------------------------ security */
                }

        item { CollapsibleSectionTitle("امنیت", icon = Icons.Outlined.Lock, expanded = "security" in sectionOpen.value, onToggle = { toggleSection("security") }, englishTitle = "Security") }
        if ("security" in sectionOpen.value) {
        item {
            SwitchRow(
                "قفل ورود با PIN",
                if (pinStore.isPinSet) "PIN تنظیم شده است" else "PIN تنظیم نشده است",
                lockEnabled,
            ) { value ->
                if (value && !pinStore.isPinSet) {
                    showPinDialog = true
                } else {
                    pinStore.lockEnabled = value
                    lockEnabled = value
                    toast.show(if (value) "قفل ورود فعال شد" else "قفل ورود غیرفعال شد")
                }
            }
        }
        item {
            ActionRow("تغییر PIN", "PBKDF2 با نمک اختصاصی دستگاه", Icons.Outlined.Password) { showPinDialog = true }
        }

        /* --------------------------------------------------------------- about */
                }

        item { CollapsibleSectionTitle("درباره برنامه", icon = Icons.Outlined.Info, expanded = "about" in sectionOpen.value, onToggle = { toggleSection("about") }, englishTitle = "About") }
        if ("about" in sectionOpen.value) {
        item { AboutCard() }
    }

        }
    /* ------------------------------------------------------- restore preview */
    val preview = restorePreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { restorePreview = null; restoreJson = null },
            title = { Text(if (preview.valid) "تأیید بازگردانی" else "فایل نامعتبر") },
            shape = MaterialTheme.shapes.extraLarge,
            text = {
                if (!preview.valid) {
                    Text(preview.error ?: "ساختار فایل پشتیبان قابل خواندن نیست.")
                } else {
                    Column {
                        InfoRow("نسخه فایل", preview.version ?: "نامشخص")
                        InfoRow("تاریخ پشتیبان", preview.exportDate ?: "نامشخص")
                        InfoRow("سرویس‌ها", preview.counts.services.toString())
                        InfoRow("سوخت", preview.counts.fuels.toString())
                        InfoRow("تعمیرات", preview.counts.maintenances.toString())
                        InfoRow("وام‌ها", preview.counts.loans.toString())
                        InfoRow("خریدهای شخصی", preview.counts.personalExpenses.toString())
                        InfoRow("درآمد شخصی", preview.counts.personalIncomes.toString())
                        InfoRow("پرسنل", preview.counts.personnel.toString())
                        InfoRow("تعطیلات دستی", preview.counts.holidays.toString())
                        InfoRow("سال‌های نرخ", preview.counts.rateYears.toString())
                        preview.versionWarning?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "داده‌های فعلی با محتوای این فایل جایگزین می‌شوند. این عملیات بازگشت‌پذیر نیست.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = preview.valid,
                    onClick = {
                        val json = restoreJson
                        restorePreview = null
                        if (json != null) {
                            scope.launch {
                                val result = repository.restoreBackup(json)
                                // Restored settings can change which reminders are on,
                                // so the schedule is rebuilt rather than left stale.
                                ReminderScheduler.reschedule(context)
                                healthToken++
                                toast.show(
                                    if (result.isSuccess) "بازگردانی با موفقیت انجام شد"
                                    else "بازگردانی انجام نشد؛ داده‌های فعلی دست‌نخورده است"
                                )
                                restoreJson = null
                            }
                        }
                    },
                ) { Text(tr("بازگردانی", "Restore")) }
            },
            dismissButton = {
                TextButton(onClick = { restorePreview = null; restoreJson = null }) { Text(tr("انصراف", "Cancel")) }
            },
        )
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                pinStore.setPin(pin)
                pinStore.lockEnabled = true
                lockEnabled = true
                showPinDialog = false
                toast.show("PIN ذخیره شد")
            },
        )
    }
}

/* --------------------------------------------------------- notification health */

/**
 * The health card. This is the "clear setting/status" the notification system was
 * missing: it names exactly which of the three gates is closed — the runtime
 * permission, the app-level system switch, or the reminder channel — and offers the
 * one action that fixes it.
 */
@Composable
private fun NotificationHealthCard(
    status: Notifications.Status,
    scheduled: Boolean,
    anyReminderEnabled: Boolean,
    onGrant: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onRecheck: () -> Unit,
    onSendTest: () -> Unit,
) {
    val healthy = status.healthy && (scheduled || !anyReminderEnabled)
    SurfaceCard(
        color = if (healthy) MaterialTheme.colorScheme.surfaceContainerLowest
        else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (healthy) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                null,
                tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    if (healthy) "اعلان‌ها فعال است" else "اعلان‌ها ارسال نمی‌شود",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (healthy) ReminderScheduler.runTimeLabel()
                    else "یکی از موارد زیر مانع ارسال اعلان است.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HealthLine("اجازه ارسال اعلان (اندروید ۱۳ به بالا)", status.permissionGranted)
        HealthLine("اعلان‌های برنامه در تنظیمات اندروید", status.systemEnabled)
        HealthLine("کانال «یادآورها»", status.reminderChannelEnabled)
        HealthLine("کانال «خلاصه عملکرد»", status.summaryChannelEnabled)
        HealthLine(
            if (anyReminderEnabled) "زمان‌بندی روزانه ثبت شده" else "هیچ یادآوری روشن نیست",
            if (anyReminderEnabled) scheduled else true,
        )
        Spacer(Modifier.height(AppDimens.gutter))
        if (!status.permissionGranted) {
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.NotificationsActive, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("اجازه ارسال اعلان را بده", "Allow notifications"))
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSendTest,
                modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                shape = FieldShape,
            ) { Text(tr("اعلان آزمایشی", "Test notification")) }
            OutlinedButton(
                onClick = onRecheck,
                modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                shape = FieldShape,
            ) { Text(tr("بررسی مجدد", "Check again")) }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSystemSettings, modifier = Modifier.fillMaxWidth()) {
            Text(tr("باز کردن تنظیمات اعلان اندروید", "Open Android notification settings"))
        }
        Text(
            "اگر اعلان‌ها با تأخیر می‌رسند، در تنظیمات اندروید بهینه‌سازی باتری این برنامه را " +
                "روی حالت بدون محدودیت بگذارید. برنامه از هیچ مجوز اضافه‌ای استفاده نمی‌کند.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HealthLine(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Outlined.Check else Icons.Outlined.Close,
            null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (ok) "درست" else "مشکل",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/* ---------------------------------------------------------------------- about */

@Composable
private fun AboutCard() {
    GradientCard(brush = heroBrush(), contentPadding = 20.dp) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = Color.White.copy(alpha = 0.18f)) {
                Icon(
                    Icons.Outlined.DirectionsCar, null,
                    tint = Color.White,
                    modifier = Modifier.padding(12.dp).size(26.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(tr("مدیریت خودرو", "KhodroYar"), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(
                "نسخه ۴ · کاملاً آفلاین · داده‌ها روی همین دستگاه",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.28f))
            Spacer(Modifier.height(16.dp))
            Text(
                "طراحی و توسعه توسط حسن رضائی خولنجانی",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* ------------------------------------------------------------------- pin */

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("تنظیم PIN", "Set PIN")) },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    first, { if (it.length <= 8) first = it.toLatinDigits() },
                    label = { Text(tr("PIN جدید", "New PIN")) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true, shape = FieldShape,
                )
                OutlinedTextField(
                    second, { if (it.length <= 8) second = it.toLatinDigits() },
                    label = { Text(tr("تکرار PIN", "Repeat PIN")) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true, shape = FieldShape,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    first.length < 4 -> error = "PIN باید حداقل ۴ رقم باشد"
                    first != second -> error = "دو مقدار یکسان نیستند"
                    else -> onConfirm(first)
                }
            }) { Text(tr("ذخیره", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("انصراف", "Cancel")) } },
    )
}

/* ------------------------------------------------------------------ holidays */

@Composable
private fun ManualHolidaysCard(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val manual by repository.manualHolidays.collectAsState(initial = emptyList())
    var date by remember { mutableStateOf(Jalali.todayString()) }
    var title by remember { mutableStateOf("") }
    val currentYear = remember { Jalali.currentYear() }
    val officialCount = remember(currentYear) { IranHolidays.forYear(currentYear).size }

    SurfaceCard {
        Text(tr("تعطیلات رسمی به‌صورت آفلاین در برنامه موجود است", "Official holidays are available offline"), style = MaterialTheme.typography.bodyMedium)
        Text(
            "سال $currentYear: $officialCount تعطیل رسمی ثبت‌شده در منبع داخلی",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(AppDimens.gutter))
        JalaliDateField("تاریخ تعطیل دستی", date, { date = it })
        Spacer(Modifier.height(8.dp))
        TextFieldR("عنوان (اختیاری)", title, { title = it }, imeAction = ImeAction.Done)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    repository.addHoliday(date, title.trim())
                    title = ""
                    toast.show("تعطیل دستی افزوده شد")
                }
            },
            modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Text(tr("افزودن تعطیل", "Add holiday")) }

        if (manual.isNotEmpty()) {
            Spacer(Modifier.height(AppDimens.gutter))
            manual.groupBy { it.date.substringBefore('/') }.toSortedMap().forEach { (year, list) ->
                Text("سال $year", style = MaterialTheme.typography.titleSmall)
                list.sortedBy { it.date }.forEach { holiday ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(holiday.date, style = MaterialTheme.typography.bodyMedium)
                            if (holiday.title.isNotBlank()) {
                                Text(
                                    holiday.title,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                repository.removeHoliday(holiday.id)
                                toast.show("تعطیل حذف شد")
                            }
                        }) { Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

/* ----------------------------------------------------------------- personnel */

@Composable
private fun PersonnelCard(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val people by repository.personnel().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var costCenter by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    SurfaceCard {
        Text("${people.size} نفر ثبت شده", style = MaterialTheme.typography.titleMedium)
        Text(
            "برای دیدن اطلاعات کامل، از داشبورد روی کارت پرسنل بزنید.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(AppDimens.gutter))
        TextFieldR("نام", name, { name = it })
        Spacer(Modifier.height(8.dp))
        TextFieldR("کد پرسنلی", code, { code = it })
        Spacer(Modifier.height(8.dp))
        DigitsField("مرکز هزینه", costCenter, { costCenter = it }, maxLength = 12, showCounter = false)
        Spacer(Modifier.height(8.dp))
        DigitsField("تلفن", phone, { phone = it }, maxLength = 11, showCounter = false, imeAction = ImeAction.Done)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (name.isBlank()) { toast.show("نام را وارد کنید"); return@Button }
                scope.launch {
                    repository.addPersonnel(name.trim(), code.trim(), costCenter.trim(), phone.trim())
                    name = ""; code = ""; costCenter = ""; phone = ""
                    toast.show("پرسنل افزوده شد")
                }
            },
            modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Text(tr("افزودن پرسنل", "Add personnel")) }

        people.forEach { person ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(person.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(person.personnelCode, person.costCenter, person.phone)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        repository.deletePersonnel(person.id)
                        toast.show("پرسنل حذف شد")
                    }
                }) { Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun daysAgoLabel(timestamp: Long): String {
    val days = ((System.currentTimeMillis() - timestamp) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "امروز"
        days == 1 -> "دیروز"
        else -> "$days روز پیش"
    }
}
