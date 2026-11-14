package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.khodroyar.app.ai.*
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.fieldColors

/** Same app/settings workflow. Secrets are ephemeral remember state, never saveable or shown from storage. */
@Composable
internal fun AIProviderSettingsDialog(
    current: AiSettings, models: AIModelState, hasKey: Boolean, saving: Boolean,
    message: String?, error: String?, diagnostics: List<AIRequestDiagnostic>, ledger: AIUsageLedger,
    onRefresh: () -> Unit, onTest: (AiSettings, String, Boolean) -> Unit,
    onEvaluate: (AiSettings, String, Boolean) -> Unit, onSave: (AiSettings, String, Boolean) -> Unit,
    onClearHistory: () -> Unit, onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    var key by remember { mutableStateOf("") }; var visible by remember { mutableStateOf(false) }; var forget by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }; var costDetails by remember { mutableStateOf(false) }
    var usageDetails by remember { mutableStateOf(false) }; var confirmEvaluation by remember { mutableStateOf(false) }
    val descriptor = AIProviderRegistry.get(draft.mode)
    val local = draft.mode == AiMode.LOCAL
    val same = draft.mode == current.mode
    val valid = key.isBlank() || AIProviderRegistry.normalizeKey(draft.mode, key) != null
    val providerModels = if (same) models else AIModelState()
    val effectiveKey = same && hasKey && !forget
    val uri = LocalUriHandler.current
    fun clearTyped() { key = ""; visible = false; forget = false }
    fun choose(mode: AiMode) { draft = draft.copy(mode = mode, modelSelectionMode = if (mode == AiMode.OPENROUTER) draft.modelSelectionMode else AISelectionMode.MANUAL); clearTyped(); providerMenu = false }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("تنظیمات دستیار", fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ai-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingSwitch("فعال بودن گفتگو", draft.enabled, !saving) { draft = draft.copy(enabled = it) }
            Text("اتصال و مدل", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { providerMenu = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("ai-provider-picker")) {
                    Text(if (local) "دستیار داخلی — بدون مدل/شبکه" else descriptor.title)
                }
                DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
                    DropdownMenuItem(text = { Text("دستیار داخلی — آفلاین") }, onClick = { choose(AiMode.LOCAL) }, modifier = Modifier.testTag("ai-provider-LOCAL"))
                    AIProviderRegistry.providers.forEach { provider ->
                        DropdownMenuItem(text = { Column { Text(provider.title); if (!provider.adapterImplemented) Text("غیرفعال: رایگانِ قابل اثبات نیست", style = MaterialTheme.typography.labelSmall) } },
                            onClick = { choose(provider.mode) }, modifier = Modifier.testTag("ai-provider-${provider.mode.name}"))
                    }
                }
            }
            if (!local) {
                Text("هیچ اتصال یا مدل پولی خودکار فعال نمی‌شود؛ سهمیه محدود است و رایگان‌بودن دائمی/نامحدود نیست.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { costDetails = !costDetails }) { Text(if (costDetails) "بستن شرایط هزینه و منطقه" else "شرایط هزینه، منطقه و حریم خصوصی") }
                if (costDetails || !descriptor.adapterImplemented) {
                    Text(descriptor.conditions, style = MaterialTheme.typography.bodySmall)
                    Text(descriptor.iran, style = MaterialTheme.typography.bodySmall)
                    Text("شواهد شرایط: ${AIProviderRegistry.VERIFIED_ON}؛ طرح واقعی حساب از کلید حدس زده نمی‌شود.", style = MaterialTheme.typography.labelSmall)
                    descriptor.sources.forEachIndexed { index, source -> TextButton(onClick = { uri.openUri(source) }) { Text("منبع رسمی ${index + 1}") } }
                }
                if (draft.mode != AiMode.OPENROUTER && descriptor.adapterImplemented) {
                    Text("محل استفاده — بدون تشخیص یا ارسال موقعیت", style = MaterialTheme.typography.labelMedium)
                    listOf(AIUseRegion.UNKNOWN to "هنوز بررسی نکرده‌ام", AIUseRegion.IRAN to "ایران — این اتصال تأیید/مجاز نیست", AIUseRegion.SUPPORTED_ELSEWHERE to "منطقهٔ دیگری که طبق شرایط سرویس مجاز است").forEach { (region, title) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(draft.useRegion == region, { draft = draft.copy(useRegion = region) }, enabled = !saving); Text(title, style = MaterialTheme.typography.bodySmall) }
                    }
                    SettingSwitch("حساب من Free، بدون روش پرداخت/billing است؛ شرایط فعلی را بررسی کرده‌ام", descriptor.id in draft.freeAccountConfirmed, !saving) {
                        draft = draft.copy(freeAccountConfirmed = if (it) draft.freeAccountConfirmed + descriptor.id else draft.freeAccountConfirmed - descriptor.id)
                    }
                    Text("این تأیید شماست، نه اثبات طرح حساب از API. حساب paid را متصل نکنید؛ سقف محلی، تضمین صورتحساب ثالث نیست.", style = MaterialTheme.typography.labelSmall)
                    if (draft.mode == AiMode.CLOUDFLARE) OutlinedTextField(draft.cloudflareAccountId, { draft = draft.copy(cloudflareAccountId = it.take(32)) }, label = { Text("Cloudflare Account ID") }, singleLine = true, enabled = !saving, textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                }
                if (descriptor.adapterImplemented) {
                    OutlinedTextField(value = key, onValueChange = { key = it.take(512); if (key.isNotBlank()) forget = false }, singleLine = true,
                        label = { Text("API Key · ${descriptor.title}") }, placeholder = { Text(if (effectiveKey) "خالی = حفظ کلید امن" else "کلید فقط همین‌جا") },
                        supportingText = { Text(if (forget) "با ذخیره کلید حذف می‌شود." else if (effectiveKey) "کلید امن موجود است؛ مقدار ذخیره‌شده بازنمایش نمی‌شود." else "رمزنگاری با Android Keystore؛ کلید را در چت ننویسید.") },
                        isError = !valid, enabled = !saving, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "نمایش/پنهان‌کردن کلید واردشده") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = FieldShape, colors = fieldColors(),
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), modifier = Modifier.fillMaxWidth().testTag("openrouter-key"))
                    if (effectiveKey) TextButton(onClick = { forget = true; key = "" }, enabled = !saving) { Text("حذف کلید با ذخیرهٔ تنظیمات") }
                    AIModelSettingsPanel(providerModels, draft.modelSelectionMode, draft.modelFor(), !saving && same,
                        onSelection = { draft = draft.copy(modelSelectionMode = it) }, onModel = { draft = draft.withModel(draft.mode, it) }, onRefresh = onRefresh,
                        providerTitle = descriptor.title)
                    Text("برای اتصال تازه ابتدا ذخیره و بررسی اتصال را بزنید، سپس مدل را از فهرست مجاز انتخاب کنید. آزمون مدل شرط گفتگو نیست.", style = MaterialTheme.typography.labelSmall)
                }
            } else Text("فرمان‌های داخلی و امکانات عادی برنامه آفلاین‌اند؛ مدل بزرگ دانلود نمی‌شود. کارهای خودکار قبلی مستقل‌اند.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("سقف مصرف و داده", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LimitChoice("حداکثر خروجی هر درخواست", draft.usage.outputTokens, listOf(128, 256, 384, 512, 768, 1024, 1536), !saving) { draft = draft.copy(usage = draft.usage.copy(outputTokens = it)) }
            LimitChoice("سقف برآورد محافظه‌کارانهٔ ورودی", draft.usage.inputTokens, listOf(1000, 2000, 4000, 8000, 12000, 20000), !saving) { draft = draft.copy(usage = draft.usage.copy(inputTokens = it)) }
            LimitChoice("درخواست مدل در هر نوبت", draft.usage.requestsPerTurn, (1..5).toList(), !saving) { draft = draft.copy(usage = draft.usage.copy(requestsPerTurn = it)) }
            LimitChoice("درخواست مدل در روز", draft.usage.requestsPerDay, listOf(5, 10, 20, 40, 60, 100), !saving) { draft = draft.copy(usage = draft.usage.copy(requestsPerDay = it)) }
            LimitChoice("بودجهٔ رزروشدهٔ توکن روزانه", draft.usage.tokensPerDay, listOf(5000, 10000, 20000, 40000, 80000, 120000, 200000), !saving) { draft = draft.copy(usage = draft.usage.copy(tokensPerDay = it)) }
            Text("ورودی با تعداد بایت UTF-8 و سربار، عمداً رو به بالا برآورد می‌شود؛ شمارش دقیق tokenizer نیست. درخواست ناموفق/لغوشده هم بودجه رزرو می‌کند. تغییر اتصال یا پاک‌کردن تاریخچه این بودجه را صفر نمی‌کند.", style = MaterialTheme.typography.labelSmall)
            LimitChoice("پیام‌های قبلی در زمینه", draft.usage.historyMessages, listOf(0, 2, 4, 6), !saving) { draft = draft.copy(usage = draft.usage.copy(historyMessages = it)) }
            SettingSwitch("ذخیرهٔ تاریخچه روی همین گوشی", draft.usage.saveHistory, !saving) { draft = draft.copy(usage = draft.usage.copy(saveHistory = it)) }
            TextButton(onClick = onClearHistory, enabled = !saving) { Text("پاک‌کردن تاریخچه با تأیید جداگانه") }
            SettingSwitch("استفاده از ابزارهای اطلاعات برنامه", draft.usage.toolsEnabled && draft.mode != AiMode.GEMINI, !saving && draft.mode != AiMode.GEMINI) { draft = draft.copy(usage = draft.usage.copy(toolsEnabled = it)) }
            Text(if (draft.mode == AiMode.GEMINI) "برای جلوگیری از ارسال سوابق به طرح Free آموزشی Gemini، ابزارهای دادهٔ برنامه در این اتصال مسدودند." else "هر ثبت/ویرایش/حذف همچنان تأیید صریح جداگانه می‌خواهد؛ خاموش‌کردن ابزارها حتی خواندن سوابق را متوقف می‌کند.", style = MaterialTheme.typography.labelSmall)
            if (draft.mode == AiMode.OPENROUTER) SettingSwitch("اجازهٔ یک جایگزینی داخل همین ارائه‌دهنده در خطای غیرسهمیه‌ای", draft.allowModelFallback, !saving) { draft = draft.copy(allowModelFallback = it) }
            Text("پیش‌فرض: بدون retry/fallback. ارائه‌دهنده هرگز خودکار عوض نمی‌شود؛ خطای کلید/اعتبار/سهمیه/مجوز هرگز retry نمی‌شود.", style = MaterialTheme.typography.labelSmall)

            if (!local && descriptor.adapterImplemented) {
                OutlinedButton(onClick = { onTest(draft, key, forget); clearTyped() }, enabled = valid && !saving && draft.enabled && !(forget && key.isBlank()), modifier = Modifier.fillMaxWidth().testTag("openrouter-test")) { Text("ذخیره و بررسی اتصال — بدون پیام AI") }
                OutlinedButton(onClick = { confirmEvaluation = true }, enabled = valid && !saving && draft.enabled && draft.usage.outputTokens >= 384, modifier = Modifier.fillMaxWidth().testTag("ai-manual-evaluation")) { Text("آزمون اختیاری یک مدل…") }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, modifier = Modifier.testTag("openrouter-test-result"), style = MaterialTheme.typography.bodySmall) }
            Text("وضعیت اتصال: ${if (same) models.message ?: if (models.active != null) "فهرست/مدل آماده؛ نه تضمین سهمیه" else "هنوز بررسی نشده" else "این اتصال هنوز ذخیره نشده"}", style = MaterialTheme.typography.bodySmall)
            val recent = diagnostics.lastOrNull { it.provider == descriptor.id && !it.metadata && it.dispatched }
            Text("باقی‌ماندهٔ اعلامی سرویس: " + if (recent?.remainingRequests != null || recent?.remainingTokens != null) "${recent?.remainingRequests ?: "نامعلوم"} درخواست / ${recent?.remainingTokens ?: "نامعلوم"} توکن${if (descriptor.mode == AiMode.GROQ) " (درخواست روزانه، توکن دقیقه‌ای)" else "؛ بازهٔ سهمیه نامعلوم"}؛ فقط آخرین پاسخ، نه مقدار زنده" else "نامعلوم؛ مقدار حدسی یا نامحدود نشان نمی‌دهیم", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { usageDetails = !usageDetails }, modifier = Modifier.testTag("ai-diagnostics-toggle")) { Text("مصرف و عیب‌یابی درخواست‌ها") }
            if (usageDetails) {
                Text("${if (ledger.day == System.currentTimeMillis() / 86400000L) "امروز UTC" else "آخرین شمارندهٔ ذخیره‌شده (نه مصرف امروز)"}: ${ledger.requests} درخواست مدل، ${ledger.metadataRequests} فراداده، ${ledger.reservedTokens} توکن رزروشده (نه مصرف واقعی).", style = MaterialTheme.typography.bodySmall)
                Text("جزئیات ۳۰ درخواست آخر فقط در حافظه است؛ بدون کلید، header، متن پیام یا سوابق.", style = MaterialTheme.typography.labelSmall)
                diagnostics.takeLast(10).asReversed().forEach { d ->
                    Text("${d.provider} · ${d.model.ifBlank { "فراداده" }}\n${if (d.metadata) "GET فراداده" else "POST مدل"} · ${if (d.dispatched) "ارسال شد" else "محلی رد شد"} · ${d.elapsedMs}ms · HTTP ${d.httpStatus ?: "—"}\nورودی تخمینی ${d.estimatedInput} / سقف خروجی ${d.outputLimit}؛ واقعی ورودی ${d.actual?.input ?: "؟"} / خروجی ${d.actual?.output ?: "؟"} / کل ${d.actual?.total ?: "اعلام نشد"}؛ خطا ${d.error?.name ?: "ندارد"}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("ai-request-diagnostic"))
                }
            }
        } },
        confirmButton = { Button(onClick = { onSave(draft, key, forget); clearTyped() }, enabled = valid && !saving) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("بستن") } })
    if (confirmEvaluation) AlertDialog(onDismissRequest = { confirmEvaluation = false }, title = { Text("مصرف آزمون اختیاری") },
        text = { Text("فقط یک مدلِ همین اتصال، با دادهٔ کاملاً ساختگی:\nحداکثر ۴ درخواست AI؛ سقف خروجی جمعاً ۱۵۳۶توکن (۳۸۴ در هر درخواست). سقف رزرو ورودی+خروجی حداکثر ${4L * (draft.usage.bounded().inputTokens + 384)} توکن است؛ مصرف واقعی ممکن است کمتر باشد و در عیب‌یابی نشان داده می‌شود. فراداده نیز GET جداگانه دارد.\nاین آزمون از سهمیهٔ سرویس کم می‌کند، اختیاری است و برای سلام/گفتگو اجرا نمی‌شود. هیچ ابزار یا رکورد واقعی استفاده نمی‌شود. در خطا متوقف می‌شود و خودکار تکرار ندارد.") },
        confirmButton = { Button(onClick = { confirmEvaluation = false; onEvaluate(draft, key, forget); clearTyped() }, modifier = Modifier.testTag("ai-evaluate-confirm")) { Text("با این سقف آزمون را اجرا کن") } },
        dismissButton = { TextButton(onClick = { confirmEvaluation = false }) { Text("انصراف — بدون درخواست") } })
}

@Composable private fun SettingSwitch(title: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(value, onChange, enabled = enabled) }
}
@Composable private fun LimitChoice(title: String, value: Int, values: List<Int>, enabled: Boolean, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Box { TextButton(onClick = { open = true }, enabled = enabled) { Text(value.toString()) }; DropdownMenu(open, { open = false }) { values.forEach { v -> DropdownMenuItem(text = { Text(v.toString()) }, onClick = { onChange(v); open = false }) } } }
    }
}
