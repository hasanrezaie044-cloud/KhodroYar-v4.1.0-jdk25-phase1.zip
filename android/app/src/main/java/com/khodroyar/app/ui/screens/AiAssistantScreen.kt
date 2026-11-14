package com.khodroyar.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.khodroyar.app.ai.AIModelState
import com.khodroyar.app.ai.AISelectionMode
import com.khodroyar.app.ai.AIModelHealthMonitor
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.khodroyar.app.ai.OpenRouterFreePolicy
import com.khodroyar.app.ai.VerifiedFreeModel
import com.khodroyar.app.ai.PersianAgentLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.ai.AiAssistantViewModel
import com.khodroyar.app.ai.AiErrorKind
import com.khodroyar.app.ai.AiLimits
import com.khodroyar.app.ai.AiMode
import com.khodroyar.app.ai.AiSettings
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.ConnectionState
import com.khodroyar.app.ai.Conversation
import com.khodroyar.app.ai.PendingAction
import com.khodroyar.app.ai.AgentSection
import com.khodroyar.app.ai.LocalDialogueEngine
import com.khodroyar.app.ui.components.ConfirmDialog
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.LocalToast
import com.khodroyar.app.ui.components.fieldColors
import com.khodroyar.app.ui.components.IconBadge
import com.khodroyar.app.ui.components.SurfaceCard
import com.khodroyar.app.ui.components.StatusPill
import com.khodroyar.app.ui.components.tr
import com.khodroyar.app.ui.theme.appAccents

/**
 * «دستیار هوشمند»: chat with the KhodroYar AI agent. Layout direction and language come from
 * the app shell (RTL by default), so nothing here hard-codes a direction.
 */
@Composable
fun AiAssistantScreen(vm: AiAssistantViewModel = viewModel(), onNavigate: (AgentSection) -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings = vm.settings
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var showSections by remember { mutableStateOf(false) }
    LaunchedEffect(vm.navigation) { vm.navigation?.let { vm.consumeNavigation(); onNavigate(it) } }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        while (true) {
            delay(AIModelHealthMonitor.FOREGROUND_REFRESH_MS)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.refreshModels()
        }
    }
    LaunchedEffect(showSettings) { if (showSettings) vm.refreshModels() }
    LaunchedEffect(vm.modelState.notice) { vm.modelState.notice?.let { toast.show(it); vm.dismissModelNotice() } }

    val extraRows = (if (vm.streamingText.isNotBlank()) 1 else 0) + (if (vm.pending != null) 1 else 0) + (if (vm.busy) 1 else 0) + (if (vm.failure != null) 1 else 0)
    LaunchedEffect(vm.messages.size, extraRows, vm.streamingText.length / 120) {
        val total = vm.messages.size + extraRows
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("khodroyar-ai", text))
        toast.show("متن کپی شد")
    }

    Column(Modifier.fillMaxSize().imePadding().testTag("ai-agent-screen")) {
        CompactAssistantBar(onBack = onBack, onSettings = { showMenu = true })
        if (settings.mode == AiMode.LOCAL) AgentDraftBar(vm.localContext, onCancel = { if (vm.pending != null) vm.cancelPending() else vm.send("لغو") })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !settings.enabled && vm.pending == null -> DisabledCard(onEnable = { vm.saveSettings(settings.copy(enabled = true)) })
                vm.messages.isEmpty() && !vm.busy && vm.pending == null -> EmptyState(vm.connection, settings.mode, onSettings = { showSettings = true }, onExample = vm::send)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("ai-chat-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.messages, key = { it.id }) { entry -> Bubble(entry, onCopy = { copyText(it) }) }
                    vm.pending?.let { p ->
                        item(key = "pending") { ConfirmCard(p, onConfirm = vm::confirmPending, onCancel = vm::cancelPending) }
                    }
                    if (vm.streamingText.isNotBlank()) item(key = "stream") {
                        Column { StatusPill("در حال دریافت · متن مدل، نه تأیید ثبت", color = MaterialTheme.colorScheme.tertiary)
                            Bubble(ChatEntry("stream", ChatRole.ASSISTANT, vm.streamingText, 0L), onCopy = {}) }
                    }
                    if (vm.busy) item(key = "busy") { TypingBubble(canStop = !vm.executingAction, status = if (vm.modelState.checking) vm.modelState.message else null, onStop = vm::stop) }
                    vm.failure?.let { f ->
                        item(key = "failure") {
                            ErrorCard(
                                f.message,
                                retryable = f.kind !in setOf(AiErrorKind.NOT_CONFIGURED, AiErrorKind.NOT_FOUND),
                                onRetry = vm::retry, onSettings = { showSettings = true },
                                onLocal = null,
                                onDirect = null,
                            )
                        }
                    }
                }
            }
        }

        if (settings.mode == AiMode.LOCAL) AgentSuggestions(vm.localContext.suggestions,
            enabled = settings.enabled && !vm.busy && !vm.savingSettings, onPick = vm::send)
        InputBar(
            value = input,
            onChange = { input = it.take(if (settings.mode == AiMode.LOCAL) AiLimits.MAX_USER_CHARS else PersianAgentLimits.USER_CHARS) },
            maxChars = if (settings.mode == AiMode.LOCAL) AiLimits.MAX_USER_CHARS else PersianAgentLimits.USER_CHARS,
            enabled = settings.enabled && (vm.pending == null || settings.mode == AiMode.LOCAL) && !vm.savingSettings,
            busy = vm.busy,
            placeholder = if (settings.mode == AiMode.LOCAL) "پیام شما…" else if (vm.pending != null) "ابتدا عملیات بالا را تأیید یا لغو کنید" else "پیام خود را بنویسید…",
            onSend = {
                focusManager.clearFocus(); keyboard?.hide()
                vm.send(input)
                if (settings.mode == AiMode.LOCAL || settings.consented) input = ""
            },
        )
    }

    if (showMenu) {
        AlertDialog(onDismissRequest = { showMenu = false }, title = { Text("گزینه‌های گفتگو") },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ai-options-menu")) {
                TextButton(onClick = { showMenu = false; showSettings = true }, modifier = Modifier.fillMaxWidth()) { Text("تنظیمات هوش مصنوعی و مدل‌ها") }
                TextButton(onClick = { showMenu = false; if (vm.messages.isEmpty()) vm.newChat() else confirmNew = true }, enabled = !vm.executingAction, modifier = Modifier.fillMaxWidth()) { Text("گفتگوی نو") }
                TextButton(onClick = { showMenu = false; vm.loadHistory(); showHistory = true }, enabled = !vm.executingAction, modifier = Modifier.fillMaxWidth()) { Text("تاریخچه") }
                TextButton(onClick = { showMenu = false; confirmClear = true }, enabled = !vm.executingAction, modifier = Modifier.fillMaxWidth()) { Text("پاک‌سازی گفتگوها") }
                HorizontalDivider()
                TextButton(onClick = { showMenu = false; showTasks = true }, modifier = Modifier.fillMaxWidth()) { Text("کارهای خودکار · ${vm.automations.count { it.enabled }}") }
                TextButton(onClick = { showMenu = false; showSections = true }, modifier = Modifier.fillMaxWidth()) { Text("بخش‌های برنامه") }
                Text(vm.activeModel, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content))
            } }, confirmButton = { TextButton(onClick = { showMenu = false }) { Text("بستن") } })
    }

    if (showTasks) AutomationManagerSheet(vm.automations, vm.automationStorageError,
        onToggle = { showTasks = false; vm.setAutomation(it.id, !it.enabled) },
        onEdit = { showTasks = false; vm.editAutomation(it.id) },
        onRemove = { showTasks = false; vm.removeAutomation(it.id) },
        onCreate = { showTasks = false; if (settings.mode != AiMode.LOCAL) vm.useLocalMode(); vm.send("روزای کاری هزینه قهوه‌مو ثبت کن") },
        onDismiss = { showTasks = false })
    if (showSections) AgentSectionsSheet(onPick = { section ->
        showSections = false
        // Explicit user navigation, never a model-produced arbitrary Intent.
        onNavigate(section)
    }, onDismiss = { showSections = false })

    if (vm.needsConsent) ConsentDialog( onAccept = { vm.acceptConsent(); input = "" }, onDecline = vm::declineConsent)

    if (showSettings && !vm.needsConsent) {
        SettingsDialog(
            current = settings,
            testMessage = vm.testMessage,
            hasOpenRouterKey = vm.hasOpenRouterKey,
            models = vm.modelState,
            onRefresh = vm::refreshModels,
            saving = vm.savingSettings || vm.busy,
            settingsError = vm.settingsError,
            onTest = { new, key, forget -> vm.configure(new, key, forget) { vm.testConnection() } },
            onSave = { new, key, forget -> vm.configure(new, key, forget) {
                vm.clearTestMessage(); vm.clearSettingsError(); showSettings = false
            } },
            onDismiss = { vm.clearTestMessage(); vm.clearSettingsError(); showSettings = false },
        )
    }
    if (showHistory) {
        HistoryDialog(
            list = vm.history,
            onOpen = { vm.openConversation(it); showHistory = false },
            onDelete = vm::deleteConversation,
            onDismiss = { showHistory = false },
        )
    }
    if (confirmNew) {
        ConfirmDialog(
            title = "گفتگوی جدید",
            message = "گفتگوی فعلی در تاریخچه می‌ماند و یک گفتگوی تازه شروع می‌شود. ادامه می‌دهید؟",
            confirmLabel = "شروع گفتگوی جدید",
            onConfirm = { confirmNew = false; vm.newChat() },
            onDismiss = { confirmNew = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "پاک کردن تاریخچه",
            message = "همهٔ گفتگوهای دستیار از این دستگاه پاک می‌شود. اطلاعات مالی، سوخت و تعمیرات شما تغییری نمی‌کند.",
            confirmLabel = "پاک کردن",
            destructive = true,
            onConfirm = { confirmClear = false; vm.clearAllHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

/* -------------------------------------------------------------------- header */

@Composable
private fun CompactAssistantBar(onBack: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("ai-chat-toolbar")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("ai-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") }
            Text("دستیار هوشمند", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onSettings, modifier = Modifier.testTag("ai-options")) { Icon(Icons.Outlined.Settings, "تنظیمات دستیار") }
        }
    }
}

@Composable
private fun connectionLook(s: ConnectionState): Pair<String, Color> = when (s) {
    ConnectionState.READY -> "تنظیم شده — اتصال آزمایش نشده" to MaterialTheme.colorScheme.tertiary
    ConnectionState.LOCAL -> "داخلی · آماده، بدون API" to appAccents.positive
    ConnectionState.NEEDS_CONSENT -> "آنلاین — نیاز به رضایت ارسال داده" to MaterialTheme.colorScheme.tertiary
    ConnectionState.ONLINE -> "متصل" to appAccents.positive
    ConnectionState.CHECKING, ConnectionState.UNKNOWN -> "در حال بررسی اتصال…" to MaterialTheme.colorScheme.tertiary
    ConnectionState.OFFLINE -> "قطع (اینترنت یا سرور)" to MaterialTheme.colorScheme.error
    ConnectionState.NOT_CONFIGURED -> "اتصال یا کلید تنظیم نشده" to MaterialTheme.colorScheme.onSurfaceVariant
    ConnectionState.DISABLED -> "غیرفعال" to MaterialTheme.colorScheme.onSurfaceVariant
    ConnectionState.ERROR -> "هوش مصنوعی موقتاً در دسترس نیست" to MaterialTheme.colorScheme.error
}

/* ------------------------------------------------------------------- bubbles */

@Composable
private fun Bubble(entry: ChatEntry, onCopy: (String) -> Unit) {
    val isUser = entry.role == ChatRole.USER
    val (bg, fg) = when (entry.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerLowest to MaterialTheme.colorScheme.onSurface
        ChatRole.RESULT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val shape = if (isUser) RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.Absolute.Right else Arrangement.Absolute.Left,
    ) {
        Surface(shape = shape, color = bg, contentColor = fg, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp)) {
                if (entry.role == ChatRole.RESULT) {
                    Text("نتیجهٔ واقعی عملیات", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                }
                SelectionContainer {
                    Text(
                        entry.text,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    )
                }
                if (entry.role == ChatRole.ASSISTANT || entry.role == ChatRole.RESULT) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { onCopy(entry.text) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "کپی پاسخ", modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun TypingBubble(canStop: Boolean, status: String? = null, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Left) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(status ?: if (canStop) "دستیار در حال پاسخ دادن است…" else "در حال انجام عملیات تأییدشده…", modifier = Modifier.weight(1f), maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                if (canStop) TextButton(onClick = onStop) { Text("توقف") }
            }
        }
    }
}

@Composable
private fun ConfirmCard(p: PendingAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val danger = p.destructive
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, accent),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (danger) "تأیید حذف (غیرقابل بازگشت)" else if (p.call.spec.name.startsWith("bulk_")) "تأیید تغییرات گروهی" else "تأیید عملیات",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent,
            )
            SelectionContainer {
                Text(p.summary, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("agent-action-summary"),
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
            }
            p.target?.let {
                Text("رکورد: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (danger) "فقط پس از تأیید شما حذف می‌شود." else "تا تأیید نکنید چیزی ثبت یا تغییر نمی‌کند.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("agent-confirm-action"),
                    colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                ) { Text(if (danger) "بله، حذف شود" else "تأیید و انجام") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("agent-cancel-action")) { Text("لغو") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, retryable: Boolean, onRetry: () -> Unit, onSettings: () -> Unit, onLocal: (() -> Unit)?, onDirect: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onDirect != null) Button(onClick = onDirect) { Text("تنظیم اتصال مستقیم GapGPT") }
            if (onLocal != null) Button(onClick = onLocal) { Text("ادامه با دستیار داخلی — بدون API") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (retryable) OutlinedButton(onClick = onRetry) { Text("تلاش دوباره") }
                TextButton(onClick = onSettings) { Text("تنظیمات دستیار") }
            }
        }
    }
}

/* ----------------------------------------------------------------- empty/off */

@Composable
private fun EmptyState(state: ConnectionState, mode: AiMode, onSettings: () -> Unit, onExample: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(Icons.Outlined.AutoAwesome, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text("چی برات انجام بدم؟", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            when (mode) {
                AiMode.LOCAL -> "دستیار تخصصی خودرو · بدون API"
                AiMode.ONLINE -> "بک‌اند خودرویار · آنلاین"
                AiMode.GAPGPT_DIRECT -> "تاریخچهٔ اتصال قدیمی"
                AiMode.OPENROUTER -> "ایجنت فارسی · OpenRouter رایگان"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        (if (mode == AiMode.LOCAL) LocalDialogueEngine.STARTERS + "گزارش این ماه" else listOf("گزارش این ماه", "وضعیت خودرو", "راهنما")).forEach { example ->
            OutlinedButton(onClick = { onExample(example) }, modifier = Modifier.fillMaxWidth()) { Text(example) }
        }
        if (state == ConnectionState.NOT_CONFIGURED) {
            Button(onClick = onSettings) { Text("تنظیم کلید OpenRouter یا حالت داخلی") }
        }
    }
}

@Composable
private fun DisabledCard(onEnable: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("دستیار هوشمند غیرفعال است", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("گفتگو خاموش است؛ قوانین قبلاً تأییدشده مستقل‌اند. برای توقفشان از «کارهای خودکار» استفاده کنید. محاسبات داخلی API هوش مصنوعی نمی‌خواهد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onEnable) { Text("فعال کردن دستیار") }
    }
}

/* ------------------------------------------------------------------- input */

@Composable
private fun InputBar(
    value: String,
    onChange: (String) -> Unit,
    maxChars: Int,
    enabled: Boolean,
    busy: Boolean,
    placeholder: String,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f).testTag("ai-message-input"),
                enabled = enabled,
                placeholder = { Text(placeholder, maxLines = 1) },
                maxLines = 5,
                shape = FieldShape,
                colors = fieldColors(),
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                supportingText = if (value.length > maxChars * 8 / 10) {
                    { Text("${value.length} / ${maxChars}") }
                } else null,
            )
            val canSend = enabled && !busy && value.isNotBlank()
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(bottom = if (value.length > maxChars * 8 / 10) 22.dp else 4.dp),
            ) {
                IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "ارسال",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ----------------------------------------------------------------- dialogs */

@Composable
private fun ConsentDialog( onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("اجازهٔ ارسال به OpenRouter") },
        text = { Text(
            "در حالت ایجنت آنلاین، پیام شما، حداکثر ۱۰ پیام اخیر و فقط خلاصهٔ لازمِ نتایج ابزار (مثل تاریخ، تعداد و مبلغ مرتبط) به OpenRouter و ارائه‌دهندهٔ مدل رایگان ارسال می‌شود. کل دیتابیس، اصل سند، عکس/PDF، شماره بیمه‌نامه، اطلاعات پرسنل، موقعیت، مخاطبین و فایل‌های دیگر ارسال نمی‌شوند. اطلاعات حساس را در پیام ننویسید.\n\n" +
            "فقط قیمت صفر و پشتیبانی ابزار از فهرست تازهٔ سرویس پذیرفته می‌شود؛ سقف قیمت صفر و منع fallback پولی اعمال می‌شود. دسترسی/محدودیت روزانه و سیاست پردازش داده را OpenRouter و ارائه‌دهنده کنترل می‌کنند؛ برنامه سهمیه یا کیفیت پاسخ را تضمین نمی‌کند. مسیرهایی که طبق فرادادهٔ سرویس جمع‌آوری داده دارند مجاز نیستند، اما این جایگزین مطالعهٔ سیاست سرویس نیست.\n\n" +
            "مدیریت خودکار می‌تواند میان مدل‌های مشخصِ مجاز، قیمت‌صفر و قبول‌شده جابه‌جا شود و این تغییر را اعلام می‌کند. فهرست عمومی و فهرست مجازِ کلید/Guardrail تازه می‌شوند؛ تنظیم Guardrail تغییر نمی‌کند. آزمون‌های فارسی/شمسی/مالی/ابزار فقط دادهٔ ساختگی دارند و هرگز ابزار واقعی را اجرا نمی‌کنند. سقف خودکار ۸ و سقف کل آزمون ۱۶ درخواست در روز است؛ از سهمیهٔ رایگان استفاده می‌کند. در پس‌زمینه فقط فراداده هر ۶ ساعت بررسی می‌شود، نه inference. کلید فقط در هدر HTTPS همان سرویس است. پرداخت، خرید اعتبار و مدل پولی خودکار وجود ندارد.\n\n" +
            "تمام ثبت/ویرایش/حذف‌ها پیش‌نمایش و دکمهٔ تأیید جدا دارند. گفتگو روی گوشی ذخیره می‌شود و قابل پاک‌کردن است. تعویض کلید/ارائه‌دهنده رضایت تازه می‌خواهد؛ جایگزینی خودکار فقط داخل فهرست مجاز و آزموده‌شده است. امکانات دستی و حالت داخلی آفلاین مستقل می‌مانند.",
            modifier = Modifier.verticalScroll(rememberScrollState()).testTag("openrouter-consent")) },
        confirmButton = { Button(onClick = onAccept) { Text("موافقم و ادامه") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("انصراف") } },
    )
}

@Composable
private fun SettingsDialog(
    current: AiSettings,
    testMessage: String?,
    hasOpenRouterKey: Boolean,
    models: AIModelState,
    onRefresh: () -> Unit,
    saving: Boolean,
    settingsError: String?,
    onTest: (AiSettings, String, Boolean) -> Unit,
    onSave: (AiSettings, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var mode by remember { mutableStateOf(if (current.mode == AiMode.LOCAL) AiMode.LOCAL else AiMode.OPENROUTER) }
    var model by remember { mutableStateOf(current.openRouterModel) }
    var selection by remember { mutableStateOf(current.modelSelectionMode) }
    // Never rememberSaveable: credentials must not enter saved-instance state.
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var forgetKey by remember { mutableStateOf(false) }
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    val valid = apiKey.isBlank() || OpenRouterFreePolicy.normalizeKey(apiKey) != null
    fun candidate() = current.copy(enabled = enabled, mode = mode, openRouterModel = model, modelSelectionMode = selection)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("تنظیمات ایجنت فارسی") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("ai-settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فعال بودن گفتگو", Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving)
                }
                listOf(AiMode.OPENROUTER to "OpenRouter — فقط رایگان", AiMode.LOCAL to "دستیار داخلی — بدون مدل/شبکه").forEach { (choice, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == choice, onClick = { mode = choice }, enabled = !saving)
                        TextButton(onClick = { mode = choice }, enabled = !saving) { Text(label) }
                    }
                }
                Text("کارهای خودکارِ قبلاً تأییدشده مستقل‌اند؛ توقف/حذفشان از همان بخش انجام می‌شود.", style = MaterialTheme.typography.labelSmall)
                if (mode == AiMode.OPENROUTER) {
                    Text("ارائه‌دهنده: OpenRouter · اتصال مستقیم با کلید خودتان؛ بدون سرور واسط", fontWeight = FontWeight.SemiBold)
                    Text(OpenRouterFreePolicy.BASE_URL, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it.take(512); if (it.isNotBlank()) forgetKey = false }, singleLine = true,
                        label = { Text("API Key حساب OpenRouter") },
                        placeholder = { Text(if (hasOpenRouterKey && !forgetKey) "خالی = حفظ کلید رمزنگاری‌شده" else "کلید را فقط اینجا وارد کنید") },
                        supportingText = { Text(if (forgetKey) "با ذخیره، کلید حذف می‌شود." else if (hasOpenRouterKey) "کلید امن موجود است؛ مقدار ذخیره‌شده بازنمایش نمی‌شود. برای تعویض، کلید جدید وارد کنید." else "کلید با Android Keystore رمزنگاری می‌شود؛ در گفتگو ننویسید.") },
                        isError = !valid, enabled = !saving,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "پنهان کردن کلید" else "نمایش کلید واردشده") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = FieldShape, colors = fieldColors(), textStyle = ltr, modifier = Modifier.fillMaxWidth().testTag("openrouter-key"),
                    )
                    if (hasOpenRouterKey && !forgetKey) TextButton(onClick = { forgetKey = true; apiKey = "" }, enabled = !saving) { Text("حذف کلید با ذخیرهٔ تنظیمات") }
                    AIModelSettingsPanel(models, selection, model, enabled = !saving,
                        onSelection = { selection = it }, onModel = { model = it }, onRefresh = onRefresh)
                    Text("پیام و خلاصهٔ لازم به OpenRouter/ارائه‌دهندهٔ مدل می‌رود؛ اصل دیتابیس/سند ارسال نمی‌شود. کلید در APK، بکاپ، تاریخچه و متن ساده ذخیره نمی‌شود. محافظت اسکرین‌شات برای همین پنجره فعال است.", style = MaterialTheme.typography.labelSmall)
                } else Text("قابلیت‌های داخلی و ثبت دستی آفلاین حفظ‌اند. این حالت مدل زبانی عمومی نیست و چیزی دانلود نمی‌کند. برای درک آزاد فارسی از ایجنت OpenRouter استفاده کنید.")
                OutlinedButton(onClick = {
                    onTest(candidate(), apiKey, forgetKey); apiKey = ""; showKey = false; forgetKey = false
                }, enabled = valid && !saving && !models.checking && enabled && !(forgetKey && apiKey.isBlank()), modifier = Modifier.testTag("openrouter-test")) {
                    Text(if (mode == AiMode.LOCAL) "ذخیره و بررسی داخلی" else "ذخیره و اجرای آزمون امن مدل‌ها")
                }
                if (saving) Text("در حال ذخیرهٔ امن…")
                settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                testMessage?.let { Text(it, modifier = Modifier.testTag("openrouter-test-result"), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)) }
            }
        },
        confirmButton = { Button(onClick = { onSave(candidate(), apiKey, forgetKey); apiKey = ""; showKey = false }, enabled = valid && !saving) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("بستن") } },
    )
}

@Composable
private fun HistoryDialog(
    list: List<Conversation>,
    onOpen: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تاریخچهٔ گفتگوها") },
        text = {
            if (list.isEmpty()) {
                Text("هنوز گفتگویی ذخیره نشده است.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onOpen(c) }, modifier = Modifier.weight(1f)) {
                                Text(
                                    c.title.ifBlank { "گفتگو" },
                                    maxLines = 1,
                                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            IconButton(onClick = { onDelete(c) }) { Icon(Icons.Outlined.Delete, contentDescription = "حذف گفتگو") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}
