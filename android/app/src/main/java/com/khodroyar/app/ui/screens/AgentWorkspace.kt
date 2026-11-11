package com.khodroyar.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ai.AgentSection
import com.khodroyar.app.ai.AppAgentTools
import com.khodroyar.app.ai.LocalDialogueContext
import com.khodroyar.app.automation.AutomationRule
import com.khodroyar.app.automation.RuleKind
import com.khodroyar.app.automation.health
import com.khodroyar.app.automation.AutomationHealth
import com.khodroyar.app.ui.components.StatusPill
import com.khodroyar.app.ui.components.tr
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.Notifications
import java.time.Instant

@Composable
internal fun AgentWorkspaceBar(active: Int, onTasks: () -> Unit, onSections: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onTasks, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.EventRepeat, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("کارهای خودکار · $active", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onSections, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.AccountTree, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("بخش‌های برنامه", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun AgentDraftBar(context: LocalDialogueContext, onCancel: () -> Unit) {
    if (context.title.isBlank()) return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(context.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("در انتظار تکمیل یا تأیید شما؛ هنوز فعال نشده", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "لغو درخواست در حال تنظیم") }
        }
    }
}

@Composable
internal fun AgentSuggestions(values: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    if (values.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.take(4).forEach { value -> SuggestionChip(onClick = { onPick(value) }, label = { Text(value) }, enabled = enabled) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationManagerSheet(
    rules: List<AutomationRule>, storageError: Boolean,
    onToggle: (AutomationRule) -> Unit, onEdit: (AutomationRule) -> Unit, onRemove: (AutomationRule) -> Unit, onCreate: () -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var holidays by remember { mutableStateOf<Set<String>>(emptySet()) }
    var calendarLoaded by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(Notifications.canPost(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it && Notifications.canPost(context) }
    LaunchedEffect(Unit) {
        holidays = AppRepository(context).manualHolidayDatesOnce()
        calendarLoaded = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("کارهای خودکار من", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            BackgroundExecutionCard()
            if (storageError) Text("فایل قانون‌ها قابل خواندن نیست؛ برای ایمنی چیزی فعال نمی‌شود. اطلاعات مالی دست‌نخورده است.", color = MaterialTheme.colorScheme.error)
            if (!canNotify) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("اجازهٔ اعلان بسته است؛ نتیجه‌ها در این صفحه می‌مانند، اما هشدار نمی‌بینی.")
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !Notifications.permissionGranted(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else Notifications.openSystemSettings(context)
                        }) { Text("اجازهٔ اعلان") }
                    }
                }
            }
            if (rules.isEmpty() && !storageError) {
                Text("هنوز قانونی نساخته‌ای. مثلاً بگو: «روزای کاری هزینه قهوه‌مو ثبت کن».", style = MaterialTheme.typography.bodyLarge)
            }
            rules.forEach { rule ->
                Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val health = rule.health(Instant.now(), holidays)
                            StatusPill(if (calendarLoaded) health.label else "…", color = when (health) {
                                AutomationHealth.FAILED -> MaterialTheme.colorScheme.error
                                AutomationHealth.WAITING, AutomationHealth.LATE -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            })
                        }
                        Text(AppAgentTools.ruleSummary(rule), style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        Text("آخرین نتیجه: ${rule.lastMessage}", style = MaterialTheme.typography.bodySmall)
                        if (rule.enabled && calendarLoaded) Text("اجرای بعدی: ${rule.schedule.nextRun(Instant.now(), holidays, rule.lastDate) ?: "بازه تمام شده یا روزی در محدوده نیست"}", style = MaterialTheme.typography.bodySmall)
                        Text("شناسه: ${rule.id.take(8)} · ${if (rule.kind == RuleKind.TRAFFIC) "نیاز به اینترنت و نشان" else "بدون اینترنت و API هوش مصنوعی"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { onToggle(rule) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (rule.enabled) "توقف قانون" else "فعال‌سازی دوباره") }
                            if (rule.kind == RuleKind.EXPENSE) TextButton(onClick = { onEdit(rule) }) { Text("تغییر مبلغ") }
                            TextButton(onClick = { onRemove(rule) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("حذف قانون") }
                        }
                    }
                }
            }
            Button(onClick = onCreate, enabled = !storageError, modifier = Modifier.fillMaxWidth()) { Text("ساخت کار خودکار با گفتگو") }
            Text("قانون‌های این دستگاه در فایل پشتیبان منتقل نمی‌شوند؛ بازیابی پشتیبان آن‌ها را متوقف می‌کند. حذف گفتگو، قانون فعال را متوقف نمی‌کند.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSectionsSheet(onPick: (AgentSection) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("یک دستیار، متصل به بخش‌های خودرویار", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("بازکردن همهٔ بخش‌های زیر مجاز است؛ ثبت و تغییر فقط برای عملیات پشتیبانی‌شده و با تأیید انجام می‌شود. دسترسی آزاد به گوشی یا اپ‌های دیگر وجود ندارد.", style = MaterialTheme.typography.bodyMedium)
            AgentSection.entries.forEach { section ->
                OutlinedButton(onClick = { onPick(section) }, modifier = Modifier.fillMaxWidth()) { Text("بازکردن ${section.label}") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
