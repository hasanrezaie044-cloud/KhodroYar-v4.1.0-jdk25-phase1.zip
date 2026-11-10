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
import com.khodroyar.app.ai.PendingAction
import com.khodroyar.app.ui.components.ConfirmDialog
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.LocalToast
import com.khodroyar.app.ui.components.fieldColors

/**
 * «دستیار هوشمند»: chat with the KhodroYar AI agent. Layout direction and language come from
 * the app shell (RTL by default), so nothing here hard-codes a direction.
 */
@Composable
fun AiAssistantScreen(vm: AiAssistantViewModel = viewModel()) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val settings = vm.settings
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !settings.enabled -> DisabledCard(onEnable = { vm.saveSettings(settings.copy(enabled = true)) })
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
                                onLocal = if (settings.mode == AiMode.ONLINE) vm::useLocalMode else null,
                            )
                        }
                    }
                }
            }
        }

        InputBar(
            value = input,
            onChange = { input = it.take(AiLimits.MAX_USER_CHARS) },
            enabled = settings.enabled && vm.pending == null,
            busy = vm.busy,
            placeholder = if (vm.pending != null) "ابتدا عملیات بالا را تأیید یا لغو کنید" else "پیام خود را بنویسید…",
            onSend = { vm.send(input); if (settings.mode == AiMode.LOCAL || settings.consented) input = "" },
        )
    }

    if (vm.needsConsent) ConsentDialog(onAccept = { vm.acceptConsent(); input = "" }, onDecline = vm::declineConsent)

    if (showSettings && !vm.needsConsent) {
        SettingsDialog(
            current = settings,
            testMessage = vm.testMessage,
            onTest = { vm.saveSettings(it); vm.testConnection() },
            onSave = { vm.saveSettings(it); vm.clearTestMessage(); showSettings = false },
            onDismiss = { vm.clearTestMessage(); showSettings = false },
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = connectionLook(state)
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "دستیار: " + model.ifBlank { "تعیین‌نشده" },
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onNew) { Icon(Icons.Outlined.Add, contentDescription = "گفتگوی جدید") }
        IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, contentDescription = "تاریخچه") }
        IconButton(onClick = onClear, enabled = canClear) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "پاک کردن تاریخچه") }
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "تنظیمات دستیار") }
    }
}

private fun connectionLook(s: ConnectionState): Pair<String, Color> = when (s) {
    ConnectionState.LOCAL -> "داخلی — آماده، بدون API" to Color(0xFF2E7D32)
    ConnectionState.NEEDS_CONSENT -> "آنلاین — نیاز به رضایت ارسال داده" to Color(0xFFF9A825)
    ConnectionState.ONLINE -> "متصل" to Color(0xFF2E7D32)
    ConnectionState.CHECKING, ConnectionState.UNKNOWN -> "در حال بررسی اتصال…" to Color(0xFFF9A825)
    ConnectionState.OFFLINE -> "قطع (اینترنت یا سرور)" to Color(0xFFC62828)
    ConnectionState.NOT_CONFIGURED -> "سرور تنظیم نشده" to Color(0xFF757575)
    ConnectionState.DISABLED -> "غیرفعال" to Color(0xFF757575)
    ConnectionState.ERROR -> "سرور آماده نیست" to Color(0xFFC62828)
}

/* ------------------------------------------------------------------- bubbles */

@Composable
private fun Bubble(entry: ChatEntry, onCopy: (String) -> Unit) {
    val isUser = entry.role == ChatRole.USER
    val (bg, fg) = when (entry.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        ChatRole.RESULT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val shape = RoundedCornerShape(18.dp)
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
private fun ErrorCard(message: String, retryable: Boolean, onRetry: () -> Unit, onSettings: () -> Unit, onLocal: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
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
        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text("دستیار خودرویار", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (mode == AiMode.LOCAL)
                "بدون اینترنت، API یا سرور کار می‌کند. گزارش، هزینه، تعمیر، سوخت و یادآور را با دستورهای مشخص مدیریت کنید. این حالت مدل زبانی عمومی نیست و چیزی به اینترنت نمی‌فرستد."
            else "دستیار آنلاین به بک‌اند شخصی خودرویار نیاز دارد؛ آدرس مستقیم GapGPT یا صفحهٔ سایت کافی نیست. ارسال اطلاعات فقط پس از رضایت شماست.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        listOf("گزارش این ماه", "وضعیت خودرو", "راهنما").forEach { example ->
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
        Text("حالت داخلی بدون اینترنت و API کار می‌کند؛ فعال‌کردن آن باعث ارسال داده به سرور نمی‌شود.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
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
                shape = CircleShape,
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
private fun ConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("قبل از شروع") },
        text = {
            Text(
                "فقط در حالت آنلاین، متن پیام و حداکثر ۱۰ پیام اخیر همین گفتگو و در صورت نیاز خلاصه‌ای از سوابق مرتبط (مثل مبلغ‌ها، تاریخ‌ها و عنوان هزینه‌ها) " +
                    "به سرور دستیار و از آنجا به سرویس هوش مصنوعی (GapGPT) ارسال می‌شود. بدون موافقت شما چیزی ارسال نمی‌شود.\n\n" +
                    "گفتگوها فقط روی همین دستگاه ذخیره می‌شوند و هر زمان می‌توانید آن‌ها را پاک یا دستیار را غیرفعال کنید. " +
                    "موقعیت مکانی، مخاطبین، پیامک، فایل‌ها و اطلاعات پرسنل ارسال نمی‌شود. ثبت، ویرایش و حذف فقط با تأیید شما انجام می‌شود.\n\n" +
                    "سرویس آنلاین ممکن است هزینهٔ توکن داشته باشد. حالت داخلی بدون API و اینترنت کار می‌کند و به این رضایت نیاز ندارد.",
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
    onTest: (AiSettings) -> Unit,
    onSave: (AiSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var mode by remember { mutableStateOf(current.mode) }
    var url by remember { mutableStateOf(current.backendUrl) }
    var model by remember { mutableStateOf(current.model) }
    var token by remember { mutableStateOf(current.clientToken) }
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    val valid = mode == AiMode.LOCAL || !enabled || AiSettingsStore.isValidBackendUrl(url)
    fun candidate() = current.copy(enabled = enabled, mode = mode, backendUrl = url, model = model, clientToken = token)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات دستیار") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فعال بودن دستیار", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == AiMode.LOCAL, onClick = { mode = AiMode.LOCAL })
                    TextButton(onClick = { mode = AiMode.LOCAL }) { Text("داخلی — بدون API (پیش‌فرض)") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == AiMode.ONLINE, onClick = { mode = AiMode.ONLINE })
                    TextButton(onClick = { mode = AiMode.ONLINE }) { Text("آنلاین — سرور شخصی") }
                }
                if (mode == AiMode.LOCAL) {
                    Text(
                        "به اینترنت، کلید، اعتبار یا دانلود مدل نیاز ندارد. گزارش‌ها از داده‌های واقعی همین دستگاه خوانده می‌شوند. ثبت با تأیید شماست. این دستیار دستورمحور است، نه مدل زبانی عمومی؛ برای نمونه‌ها «راهنما» بفرستید.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true, shape = FieldShape, colors = fieldColors(),
                        label = { Text("آدرس HTTPS بک‌اند خودرویار") }, textStyle = ltr,
                        supportingText = { Text("آدرس ریشه یا /v1 پذیرفته می‌شود؛ آدرس مستقیم GapGPT یا سایت نیست.") },
                        isError = url.isNotBlank() && !AiSettingsStore.isValidBackendUrl(url),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it }, singleLine = true, shape = FieldShape, colors = fieldColors(),
                        label = { Text("نام مدل (خالی = پیش‌فرض سرور)") }, textStyle = ltr,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it }, singleLine = true, shape = FieldShape, colors = fieldColors(),
                        label = { Text("توکن برنامه، نه کلید API (اختیاری)") }, textStyle = ltr,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("کلید سرویس مدل فقط روی سرور نگهداری می‌شود. سرویس آنلاین لزوماً رایگان نیست.", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { onTest(candidate()) }, enabled = valid) {
                    Text(if (mode == AiMode.LOCAL) "ذخیره و بررسی حالت داخلی" else "ذخیره و آزمایش اتصال")
                }
                testMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { onSave(candidate()) }, enabled = valid) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
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
