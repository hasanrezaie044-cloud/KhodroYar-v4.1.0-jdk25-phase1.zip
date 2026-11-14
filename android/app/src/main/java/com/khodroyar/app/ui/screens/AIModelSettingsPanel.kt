package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.khodroyar.app.ai.*
import com.khodroyar.app.core.jalali.Jalali
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

private fun checkedTime(value: Long): String {
    if (value <= 0) return "هنوز بررسی نشده"
    val date = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
    return "${Jalali.fromGregorian(date.year, date.monthValue, date.dayOfMonth)} · %02d:%02d".format(date.hour, date.minute)
}

/** Integrated, scrollable Persian settings. Only approved choices are actionable; discovered ones stay visible with reasons. */
@Composable
internal fun AIModelSettingsPanel(
    state: AIModelState, selection: AISelectionMode, selectedId: String, enabled: Boolean,
    onSelection: (AISelectionMode) -> Unit, onModel: (String) -> Unit, onRefresh: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000L); now = System.currentTimeMillis() } }
    var expanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val approved = state.models.filter { it.readyAt(now) }.sortedWith(compareByDescending<AIModelView> { it.evaluation?.score ?: 0 }.thenBy { it.model.key.id })
    Text("مدیریت مدل هوش مصنوعی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    listOf(AISelectionMode.AUTOMATIC to "خودکار — بهترین مدلِ مجاز و آزموده‌شده", AISelectionMode.MANUAL to "دستی — فقط از مدل‌های تأییدشده").forEach { (mode, title) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selection == mode, onClick = { onSelection(mode) }, enabled = enabled, modifier = Modifier.testTag("ai-selection-${mode.name}"))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text("مدل فعال: ${state.active?.id ?: "هنوز مدلِ آماده‌ای تأیید نشده"}", style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.testTag("openrouter-model"))
    Text("ارائه‌دهندهٔ اتصال: OpenRouter · بررسی قیمت/دسترسی: ${checkedTime(state.checkedAt)}", style = MaterialTheme.typography.labelMedium)
    if (selection == AISelectionMode.MANUAL) {
        Text("انتخاب دستی: $selectedId", style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && approved.isNotEmpty(), modifier = Modifier.testTag("ai-manual-model-picker")) { Text("انتخاب مدل تأییدشده") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                approved.forEach { item -> DropdownMenuItem(text = { Text(item.model.name, style = MaterialTheme.typography.bodySmall) },
                    onClick = { onModel(item.model.key.id); expanded = false }) }
            }
        }
        if (approved.none { it.model.key.id == selectedId }) Text("مدلی برای انتخاب تأیید نشده؛ حالت خودکار یا آزمون امن را انتخاب کنید.", color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = onRefresh, enabled = enabled && !state.checking, modifier = Modifier.testTag("ai-refresh-models")) { Text("تازه‌سازی فهرست مدل‌ها") }
    if (state.checking) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("در حال بررسی امن…", style = MaterialTheme.typography.bodySmall)
    }
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text("جایگزین‌های تأییدشده: ${approved.size}", fontWeight = FontWeight.SemiBold)
    if (state.models.isEmpty()) Text("پس از ذخیرهٔ کلید و رضایت، فهرست رسمی حساب بررسی می‌شود. قیمت یا مجوزِ نامعلوم مجاز نیست.", style = MaterialTheme.typography.bodySmall)
    val ordered = state.models.sortedWith(compareByDescending<AIModelView> { it.approved }.thenByDescending { it.evaluation?.score ?: 0 }.thenBy { it.model.key.id })
    ordered.take(if (showAll) 100 else 8).forEach { item ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("ai-model-${item.model.key.id}")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.model.name, fontWeight = FontWeight.SemiBold)
                Text(item.model.key.id, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                Text("سازنده: ${item.model.publisher} · اتصال: ${item.model.providerName}", style = MaterialTheme.typography.labelMedium)
                Text("قیمت: ${if (item.model.pricing.verifiedFree) "ورودی ۰ / خروجی ۰؛ همهٔ هزینه‌ها صفر" else "صفر تأیید نشده — غیرمجاز"}", style = MaterialTheme.typography.bodySmall)
                Text("ابزار: ${if (item.model.capability.toolCalling) "پشتیبانی دارد" else "تأیید نشد"} · زمینه: ${item.model.capability.contextLength} توکن", style = MaterialTheme.typography.labelMedium)
                Text("بررسی قیمت/Guardrail: ${checkedTime(item.model.checkedAt)}", style = MaterialTheme.typography.labelSmall)
                Text("دسترس‌پذیری: ${item.availability?.let { if (it.available) "فعال: ${it.providers.joinToString("، ")}" else it.explanation } ?: "هنوز endpoint بررسی نشده"}", style = MaterialTheme.typography.bodySmall)
                Text("بررسی ارائه‌دهنده: ${checkedTime(item.availability?.checkedAt ?: 0)}", style = MaterialTheme.typography.labelSmall)
                Text("آزمون فارسی/ابزار: ${item.evaluation?.let { if (it.passed) "قبول · امتیاز ${it.score}/100" else "قبول نشد" } ?: "انجام نشده"} · ${checkedTime(item.evaluation?.checkedAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
                item.evaluation?.cases?.forEach { test -> Text("${if (test.passed) "✓" else "×"} ${test.name}", style = MaterialTheme.typography.labelSmall) }
                Text(if (item.approved && !item.readyAt(now)) "اعتبار بررسی قیمت/دسترسی تمام شده؛ پیش از انتخاب، فهرست را تازه کنید." else item.reason, color = if (item.readyAt(now)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (!showAll && state.models.size > 8) TextButton(onClick = { showAll = true }) { Text("نمایش مدل‌های کشف‌شدهٔ بیشتر") }
    Text("خودکار فقط از مدل‌های مشخصِ رایگان، مجاز و قبول‌شده جایگزین می‌کند؛ در حالت دستی انتخاب شما بی‌اجازه عوض نمی‌شود. محدودیت Guardrail، سهمیه و ۴۲۹ دور زده نمی‌شوند. مسیریاب تصادفی برای ارزیابیِ مدل ثابت انتخاب نمی‌شود.", style = MaterialTheme.typography.labelMedium)
    Text("آزمون‌ها فقط ارقام ساختگی دارند؛ حداکثر دو مدل در نوبت، ۸ درخواست آزمون خودکار و ۱۶ درخواست کل آزمون در روز. تست پس‌زمینه نداریم؛ فقط فراداده هر ۶ ساعت و در استفادهٔ فعال تازه می‌شود. نتیجهٔ آزمون محدود، تضمین کیفیت همهٔ فارسی نیست.", style = MaterialTheme.typography.labelSmall)
}
