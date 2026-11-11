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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.khodroyar.app.ai.AiSettingsStore
import com.khodroyar.app.ai.AiSettings
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.ConnectionState
import com.khodroyar.app.ai.Conversation
import com.khodroyar.app.ai.GapGptDirectContract
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
fun AiAssistantScreen(vm: AiAssistantViewModel = viewModel(), onNavigate: (AgentSection) -> Unit = {}) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val settings = vm.settings
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.onWorkspaceHidden() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.onWorkspaceHidden() }
    }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var showSections by remember { mutableStateOf(false) }
    LaunchedEffect(vm.navigation) { vm.navigation?.let { vm.consumeNavigation(); onNavigate(it) } }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val extraRows = (if (vm.pending != null) 1 else 0) + (if (vm.busy) 1 else 0) + (if (vm.failure != null) 1 else 0)
    LaunchedEffect(vm.messages.size, extraRows) {
        val total = vm.messages.size + extraRows
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("khodroyar-ai", text))
        toast.show("متن کپی شد")
    }

    Column(Modifier.fillMaxSize()) {
        AssistantHeader(
            model = vm.activeModel,
            state = vm.connection,
            canClear = vm.messages.isNotEmpty(),
            onNew = { if (vm.messages.isEmpty()) vm.newChat() else confirmNew = true },
            onHistory = { vm.loadHistory(); showHistory = true },
            onClear = { confirmClear = true },
            onSettings = { showSettings = true },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AgentWorkspaceBar(vm.automations.count { it.enabled }, onTasks = { showTasks = true }, onSections = { showSections = true })
        if (settings.mode == AiMode.LOCAL) AgentDraftBar(vm.localContext, onCancel = { if (vm.pending != null) vm.cancelPending() else vm.send("لغو") })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !settings.enabled && vm.pending == null -> DisabledCard(onEnable = { vm.saveSettings(settings.copy(enabled = true)) })
                vm.messages.isEmpty() && !vm.busy -> EmptyState(vm.connection, settings.mode, onSettings = { showSettings = true }, onExample = vm::send)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.messages, key = { it.id }) { entry -> Bubble(entry, onCopy = { copyText(it) }) }
                    vm.pending?.let { p ->
                        item(key = "pending") { ConfirmCard(p, onConfirm = vm::confirmPending, onCancel = vm::cancelPending) }
                    }
                    if (vm.busy) item(key = "busy") { TypingBubble(canStop = !vm.executingAction, onStop = vm::stop) }
                    vm.failure?.let { f ->
                        item(key = "failure") {
                            ErrorCard(
                                f.message,
                                retryable = f.kind !in setOf(AiErrorKind.NOT_CONFIGURED, AiErrorKind.NOT_FOUND),
                                onRetry = vm::retry, onSettings = { showSettings = true },
                                onLocal = if (settings.mode != AiMode.LOCAL) vm::useLocalMode else null,
                                onDirect = if (settings.mode == AiMode.ONLINE && GapGptDirectContract.isProviderHost(settings.backendUrl)) {
                                    { vm.chooseDirectGapGpt(); showSettings = true }
                                } else null,
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
            onChange = { input = it.take(AiLimits.MAX_USER_CHARS) },
            enabled = settings.enabled && (vm.pending == null || settings.mode == AiMode.LOCAL) && !vm.savingSettings,
            busy = vm.busy,
            placeholder = if (settings.mode == AiMode.LOCAL) "پیام شما…" else if (vm.pending != null) "ابتدا عملیات بالا را تأیید یا لغو کنید" else "پیام خود را بنویسید…",
            onSend = { vm.send(input); if (settings.mode == AiMode.LOCAL || settings.consented) input = "" },
        )
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

    if (vm.needsConsent) ConsentDialog(mode = settings.mode, onAccept = { vm.acceptConsent(); input = "" }, onDecline = vm::declineConsent)

    if (showSettings && !vm.needsConsent) {
        SettingsDialog(
            current = settings,
            testMessage = vm.testMessage,
            hasDirectKey = vm.hasGapGptKey,
            saving = vm.savingSettings,
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
private fun AssistantHeader(
    model: String,
    state: ConnectionState,
    canClear: Boolean,
    onNew: () -> Unit,
    onHistory: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(Icons.Outlined.AutoAwesome)
            Column(Modifier.weight(1f)) {
                val (label, color) = connectionLook(state)
                Text(label, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                Text(model.ifBlank { "دستیار خودرویار" }, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "تنظیمات دستیار") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("گفتگوی نو", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.History, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("تاریخچه", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onClear, enabled = canClear, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("پاک‌سازی", style = MaterialTheme.typography.labelSmall)
            }
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
    ConnectionState.ERROR -> "سرور آماده نیست" to MaterialTheme.colorScheme.error
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
private fun TypingBubble(canStop: Boolean, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Left) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (canStop) "دستیار در حال پاسخ دادن است…" else "در حال انجام عملیات تأییدشده…", style = MaterialTheme.typography.bodyMedium)
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
                if (danger) "تأیید حذف (غیرقابل بازگشت)" else "تأیید عملیات",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent,
            )
            Text(p.summary, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
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
                    modifier = Modifier.weight(1f),
                    colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                ) { Text(if (danger) "بله، حذف شود" else "تأیید و انجام") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("لغو") }
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
                AiMode.GAPGPT_DIRECT -> "GapGPT · اتصال مستقیم"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        (if (mode == AiMode.LOCAL) LocalDialogueEngine.STARTERS + "گزارش این ماه" else listOf("گزارش این ماه", "وضعیت خودرو", "راهنما")).forEach { example ->
            OutlinedButton(onClick = { onExample(example) }, modifier = Modifier.fillMaxWidth()) { Text(example) }
        }
        if (state == ConnectionState.NOT_CONFIGURED) {
            Button(onClick = onSettings) { Text("انتخاب حالت داخلی یا تنظیم سرور") }
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
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(placeholder, maxLines = 1) },
                maxLines = 5,
                shape = FieldShape,
                colors = fieldColors(),
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                supportingText = if (value.length > AiLimits.MAX_USER_CHARS * 8 / 10) {
                    { Text("${value.length} / ${AiLimits.MAX_USER_CHARS}") }
                } else null,
            )
            val canSend = enabled && !busy && value.isNotBlank()
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(bottom = if (value.length > AiLimits.MAX_USER_CHARS * 8 / 10) 22.dp else 4.dp),
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
private fun ConsentDialog(mode: AiMode, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("قبل از شروع") },
        text = {
            Text(
                "فقط در حالت آنلاین، متن پیام و حداکثر ۱۰ پیام اخیر همین گفتگو و در صورت نیاز خلاصه‌ای از سوابق مرتبط (مثل مبلغ‌ها، تاریخ‌ها و عنوان هزینه‌ها) " +
                    (if (mode == AiMode.GAPGPT_DIRECT) "مستقیماً به آدرس رسمی GapGPT یا CDN انتخابی شما ارسال می‌شود. کلید حساب فقط در هدر Authorization همان مقصد استفاده می‌شود. "
                     else "به بک‌اند خودرویار و از آنجا به سرویس مدل ارسال می‌شود. ") +
                    "بدون موافقت شما چیزی ارسال نمی‌شود. تغییر حساب/آدرس/حالت نیازمند رضایت دوباره است.\n\n" +
                    "گفتگوها فقط روی همین دستگاه ذخیره می‌شوند و هر زمان می‌توانید آن‌ها را پاک یا دستیار را غیرفعال کنید. " +
                    "موقعیت مکانی، مخاطبین، پیامک، فایل‌ها و اطلاعات پرسنل ارسال نمی‌شود. ثبت، ویرایش و حذف فقط با تأیید شما انجام می‌شود.\n\n" +
                    "سرویس آنلاین به اعتبار حساب نیاز دارد. در اتصال مستقیم، «آزمایش اتصال» یک پیام سلام با حداکثر خروجی ۳۲ توکن می‌فرستد و ممکن است هزینه داشته باشد؛ تاریخچه یا اطلاعات مالی را در تست نمی‌فرستد. حالت داخلی بدون API است.",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { Button(onClick = onAccept) { Text("موافقم و ادامه") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("انصراف") } },
    )
}

@Composable
private fun SettingsDialog(
    current: AiSettings,
    testMessage: String?,
    hasDirectKey: Boolean,
    saving: Boolean,
    settingsError: String?,
    onTest: (AiSettings, String, Boolean) -> Unit,
    onSave: (AiSettings, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var mode by remember { mutableStateOf(current.mode) }
    var url by remember { mutableStateOf(current.backendUrl) }
    var model by remember { mutableStateOf(current.model) }
    var token by remember { mutableStateOf(current.clientToken) }
    var directUrl by remember { mutableStateOf(current.gapGptBaseUrl) }
    var directModel by remember { mutableStateOf(current.gapGptModel) }
    // Never rememberSaveable: a provider key must not enter saved-instance state or backups.
    var apiKey by remember { mutableStateOf("") }
    var forgetKey by remember { mutableStateOf(false) }
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    val keyValid = apiKey.isBlank() || GapGptDirectContract.normalizeApiKey(apiKey) != null
    val valid = when (mode) {
        AiMode.LOCAL -> true
        AiMode.ONLINE -> !enabled || AiSettingsStore.isValidBackendUrl(url)
        AiMode.GAPGPT_DIRECT -> GapGptDirectContract.normalizeBaseUrl(directUrl) != null && keyValid &&
            (!enabled || hasDirectKey || apiKey.isNotBlank() || forgetKey)
    }
    fun candidate() = current.copy(
        enabled = enabled, mode = mode, backendUrl = url, model = model, clientToken = token,
        gapGptBaseUrl = directUrl, gapGptModel = directModel,
    )

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("تنظیمات دستیار") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فعال بودن گفتگوی دستیار", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving)
                }
                Text("قوانین تأییدشده مستقل از گفتگو اجرا می‌شوند؛ برای توقف یا حذفشان از کارهای خودکار استفاده کنید.", style = MaterialTheme.typography.labelMedium)
                listOf(
                    AiMode.LOCAL to "داخلی — بدون API",
                    AiMode.GAPGPT_DIRECT to "اتصال مستقیم GapGPT",
                    AiMode.ONLINE to "بک‌اند شخصی خودرویار",
                ).forEach { (choice, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == choice, onClick = { mode = choice }, enabled = !saving)
                        TextButton(onClick = { mode = choice }, enabled = !saving) { Text(title) }
                    }
                }
                when (mode) {
                    AiMode.LOCAL -> Text(
                        "محاسبات، هزینه و سرویس بدون API هوش مصنوعی یا دانلود مدل انجام می‌شوند. گفتگوی مرحله‌ای دارد، ولی مدل زبانی عمومی نیست. فقط دریافت ترافیک تازه به اینترنت و کلید نشان نیاز دارد. فعال‌سازی هر قانون تأیید می‌خواهد.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AiMode.GAPGPT_DIRECT -> {
                        Text("آدرس سرویس (مطابق راهنمای GapGPT):", fontWeight = FontWeight.SemiBold)
                        listOf(
                            GapGptDirectContract.PRIMARY_BASE_URL to "اصلی — api.gapgpt.app",
                            GapGptDirectContract.CDN_BASE_URL to "CDN خارجی — api.gapapi.com",
                        ).forEach { (base, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = GapGptDirectContract.normalizeBaseUrl(directUrl) == base, onClick = { directUrl = base }, enabled = !saving)
                                TextButton(onClick = { directUrl = base }, enabled = !saving) { Text(label) }
                            }
                        }
                        Text("POST " + GapGptDirectContract.chatUrl(directUrl).orEmpty(), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                        OutlinedTextField(
                            value = directModel, onValueChange = { directModel = it.take(100) }, singleLine = true,
                            label = { Text("مدل GapGPT (خالی = gpt-4o)") },
                            supportingText = { Text("مثلاً gpt-4o یا gemini-2.5-pro؛ باید در حساب شما قابل استفاده باشد.") },
                            enabled = !saving, shape = FieldShape, colors = fieldColors(), textStyle = ltr,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it.take(4096); if (it.isNotBlank()) forgetKey = false }, singleLine = true,
                            label = { Text("API Key واقعی حساب GapGPT") },
                            placeholder = { Text(if (hasDirectKey && !forgetKey) "خالی = حفظ کلید رمزنگاری‌شده" else "کلید را فقط اینجا وارد کنید") },
                            supportingText = { Text(if (forgetKey) "کلید ذخیره‌شده با ذخیرهٔ تنظیمات پاک می‌شود." else if (hasDirectKey) "کلید رمزنگاری‌شده روی گوشی موجود است؛ نمایش داده نمی‌شود." else "YOUR_GAPGPT_API_KEY کلید واقعی نیست. کلید را در چت نفرستید.") },
                            isError = !keyValid,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            enabled = !saving, shape = FieldShape, colors = fieldColors(), textStyle = ltr,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (hasDirectKey && !forgetKey) TextButton(onClick = { forgetKey = true; apiKey = "" }, enabled = !saving) { Text("حذف کلید از گوشی هنگام ذخیره") }
                        Text(
                            "کلید شخصی شما با Android Keystore رمزنگاری می‌شود؛ داخل APK، بکاپ یا تاریخچه نیست. Redirect و تغییر خودکار CDN انجام نمی‌شود. اتصال و تست واقعی ممکن است اعتبار مصرف کنند.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    AiMode.ONLINE -> {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it }, singleLine = true, shape = FieldShape, colors = fieldColors(),
                            label = { Text("آدرس HTTPS بک‌اند خودرویار") }, textStyle = ltr, enabled = !saving,
                            supportingText = { Text("این فیلد برای سرور واسطِ مستقرشده است، نه آدرس خود GapGPT.") },
                            isError = url.isNotBlank() && !AiSettingsStore.isValidBackendUrl(url),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (GapGptDirectContract.isProviderHost(url)) {
                            Text("این آدرس متعلق به خود GapGPT است و /v1/chat بک‌اند را ندارد. اتصال مستقیم را انتخاب کنید.", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { directUrl = GapGptDirectContract.normalizeBaseUrl(url) ?: GapGptDirectContract.PRIMARY_BASE_URL; mode = AiMode.GAPGPT_DIRECT }, enabled = !saving) { Text("استفاده از اتصال مستقیم GapGPT") }
                        }
                        OutlinedTextField(
                            value = model, onValueChange = { model = it.take(100) }, singleLine = true, shape = FieldShape, colors = fieldColors(),
                            label = { Text("مدل (خالی = پیش‌فرض بک‌اند)") }, textStyle = ltr, enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = token, onValueChange = { token = it }, singleLine = true, shape = FieldShape, colors = fieldColors(),
                            label = { Text("توکن بک‌اند، نه API Key") }, textStyle = ltr, enabled = !saving,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("در این حالت کلید سرویس مدل فقط روی سرور شماست. برای API مستقیم، گزینهٔ جداگانهٔ بالا را انتخاب کنید.", style = MaterialTheme.typography.labelMedium)
                    }
                }
                val canTest = valid && !saving && enabled && !(mode == AiMode.GAPGPT_DIRECT && forgetKey && apiKey.isBlank())
                OutlinedButton(onClick = {
                    onTest(candidate(), apiKey, forgetKey)
                    apiKey = ""
                    forgetKey = false
                }, enabled = canTest) {
                    Text(if (mode == AiMode.LOCAL) "ذخیره و بررسی حالت داخلی" else if (mode == AiMode.GAPGPT_DIRECT) "ذخیره و ارسال تست واقعی (مصرف اعتبار)" else "ذخیره و آزمایش بک‌اند")
                }
                if (saving) Text("در حال ذخیرهٔ امن تنظیمات…", style = MaterialTheme.typography.bodySmall)
                settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                testMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)) }
            }
        },
        confirmButton = { Button(onClick = { onSave(candidate(), apiKey, forgetKey) }, enabled = valid && !saving) { Text("ذخیره") } },
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
