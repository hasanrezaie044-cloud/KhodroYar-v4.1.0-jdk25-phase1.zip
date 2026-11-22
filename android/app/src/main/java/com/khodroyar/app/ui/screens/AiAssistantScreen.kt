package com.khodroyar.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.ai.*
import com.khodroyar.app.ui.components.ConfirmDialog
import com.khodroyar.app.ui.components.LocalToast
import kotlinx.coroutines.delay

/** The visual changes are deliberately local to chat and its options panel, not the app shell. */
@Composable
fun AiAssistantScreen(vm: AiAssistantViewModel = viewModel(), onNavigate: (AgentSection) -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings = vm.settings
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteConversation by remember { mutableStateOf<Conversation?>(null) }
    var cameraCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attachImage(it) } }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.attachImage(it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraCapture?.let { saved ->
            val uri = Uri.parse(saved)
            if (success) vm.attachImage(uri, fromCamera = true) else vm.imageStore.discardCamera(uri)
        }
        cameraCapture = null
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text ->
            vm.editDraft(listOf(vm.draftText, text).filter { it.isNotBlank() }.joinToString(" "))
        }
    }
    LaunchedEffect(vm.navigation) { vm.navigation?.let { vm.consumeNavigation(); onNavigate(it) } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AIModelHealthMonitor.FOREGROUND_REFRESH_MS)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.refreshModels(explicit = false)
        }
    }
    LaunchedEffect(showSettings) { if (showSettings) vm.refreshModels(explicit = false) }
    LaunchedEffect(vm.modelState.notice) { vm.modelState.notice?.let { toast.show(it); vm.dismissModelNotice() } }
    val extraRows = (if (vm.streamingText.isNotBlank()) 1 else 0) + (if (vm.pending != null) 1 else 0) + (if (vm.busy) 1 else 0) + (if (vm.canContinue) 1 else 0)
    LaunchedEffect(vm.messages.size, extraRows, vm.streamingText.length / 120) {
        val total = vm.messages.size + extraRows
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
    fun copyText(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("khodroyar-ai", text))
        toast.show("متن کپی شد")
    }
    fun settingsFromPanel() { showModels = false; showMenu = false; showSettings = true }

    Column(Modifier.fillMaxSize().imePadding().testTag("ai-agent-screen")) {
        CompactAssistantBar(vm, onBack, onModels = { showModels = true; vm.refreshModels(explicit = false) }, onOptions = { showMenu = true })
        if (settings.mode == AiMode.LOCAL) AgentDraftBar(vm.localContext, onCancel = { if (vm.pending != null) vm.cancelPending() else vm.send("لغو") })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !settings.enabled && vm.pending == null -> DisabledCard(onEnable = { vm.saveSettings(settings.copy(enabled = true)) })
                vm.messages.isEmpty() && !vm.busy && vm.pending == null -> ChatWelcome(vm.connection, { showSettings = true }, vm::send)
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("ai-chat-list"),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(vm.messages, key = { it.id }) { entry ->
                        Bubble(entry, vm.imageStore, onImage = vm::reuseImage, onCopy = ::copyText)
                    }
                    vm.pending?.let { p -> item(key = "pending") { ConfirmCard(p, vm::confirmPending, vm::cancelPending) } }
                    if (vm.streamingText.isNotBlank()) item(key = "stream") {
                        Bubble(ChatEntry("stream", ChatRole.ASSISTANT, vm.streamingText, 0L), vm.imageStore, {}, {})
                    }
                    if (vm.busy) item(key = "busy") { TypingBubble(!vm.executingAction, vm::stop) }
                    if (vm.canContinue) item(key = "continue") {
                        OutlinedButton(onClick = vm::continueTurn, enabled = !vm.networkBusy, modifier = Modifier.testTag("ai-continue")) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("ادامهٔ بررسی")
                        }
                    }
                }
            }
        }
        vm.failure?.let { f ->
            ChatFailureNotice(settings.mode, f, !vm.networkBusy, vm::retry,
                onModel = { showModels = true; vm.refreshModels(explicit = false) }, onDismiss = vm::dismissFailure)
        }
        if (settings.mode == AiMode.LOCAL) AgentSuggestions(vm.localContext.suggestions,
            enabled = settings.enabled && !vm.busy && !vm.savingSettings, onPick = vm::send)
        ChatComposer(value = vm.draftText, onChange = vm::editDraft,
            image = vm.draftImage?.let(vm.imageStore::file), imageLoading = vm.imageLoading,
            enabled = settings.enabled && !vm.networkBusy && (vm.pending == null || settings.mode == AiMode.LOCAL), busy = vm.busy,
            placeholder = if (vm.pending != null && settings.mode != AiMode.LOCAL) "ابتدا تأیید یا لغو کنید" else "پیام شما…",
            onAdd = { showAttachments = true }, onRemoveImage = vm::removeDraftImage,
            onSend = { focusManager.clearFocus(); keyboard?.hide(); vm.sendDraft() },
            onVoice = {
                try { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR").putExtra(RecognizerIntent.EXTRA_PROMPT, "پیام شما")) }
                catch (_: Exception) { toast.show("تایپ صوتی در دسترس نیست") }
            })
    }
    if (showModels) ChatModelSheet(settings.mode, vm.modelState, vm.modelState.active?.id ?: settings.modelFor(),
        busy = vm.networkBusy || vm.pending != null, onProvider = { vm.selectChatProvider(it) },
        onPick = { id -> vm.selectChatModel(settings.mode, id) { showModels = false } }, onRefresh = { vm.refreshModels() },
        onSettings = ::settingsFromPanel, onDismiss = { showModels = false })
    if (showMenu) ChatOptionsSheet(taskCount = vm.automations.size, enabled = !vm.executingAction,
        onNew = { showMenu = false; if (vm.messages.isEmpty()) vm.newChat() else confirmNew = true },
        onHistory = { showMenu = false; vm.loadHistory(); showHistory = true },
        onClear = { showMenu = false; confirmClear = true }, onTasks = { showMenu = false; showTasks = true },
        onMemory = { showMenu = false; vm.loadMemory(); showMemory = true },
        onSettings = ::settingsFromPanel, onDismiss = { showMenu = false })
    if (showMemory) AssistantMemorySheet(vm.memoryFacts, vm.agentTasks, vm.memoryMessage,
        onSave = vm::saveMemory, onDelete = { vm.deleteMemory(it.id) }, onCancelTask = { vm.cancelTask(it.id) },
        onDeleteTask = { vm.deleteTask(it.id) }, onDismissMessage = vm::dismissMemoryMessage,
        onDismiss = { showMemory = false; vm.dismissMemoryMessage() })
    if (showAttachments) ChatAttachmentSheet(
        onCamera = {
            showAttachments = false
            try { val uri = vm.imageStore.cameraUri(); cameraCapture = uri.toString(); camera.launch(uri) }
            catch (_: Exception) { cameraCapture?.let { vm.imageStore.discardCamera(Uri.parse(it)) }; cameraCapture = null; toast.show("دوربین در دسترس نیست") }
        }, onGallery = {
            showAttachments = false
            try { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: Exception) { toast.show("گالری باز نشد") }
        }, onFile = {
            showAttachments = false
            try { file.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
            catch (_: Exception) { toast.show("انتخاب فایل باز نشد") }
        }, onDismiss = { showAttachments = false })
    if (showTasks) AutomationManagerSheet(vm.automations, vm.automationStorageError,
        onToggle = { showTasks = false; vm.setAutomation(it.id, !it.enabled) }, onEdit = { showTasks = false; vm.editAutomation(it.id) },
        onRemove = { showTasks = false; vm.removeAutomation(it.id) },
        onCreate = { showTasks = false; if (settings.mode != AiMode.LOCAL) vm.useLocalMode(); vm.send("روزای کاری هزینه قهوه‌مو ثبت کن") }, onDismiss = { showTasks = false })
    if (vm.needsConsent) ConsentDialog(settings, vm::acceptConsent, vm::declineConsent)
    if (showSettings && !vm.needsConsent) AIProviderSettingsDialog(current = settings, message = vm.testMessage,
        hasKey = vm.hasOpenRouterKey, models = vm.modelState, onRefresh = { vm.refreshModels() }, saving = vm.networkBusy,
        error = vm.settingsError, diagnostics = vm.diagnostics, ledger = vm.usageLedger,
        onClearHistory = { showSettings = false; confirmClear = true },
        onEvaluate = { new, key, forget -> vm.configure(new, key, forget) { vm.testModels() } },
        onTest = { new, key, forget -> vm.configure(new, key, forget) { vm.testConnection() } },
        onSave = { new, key, forget -> vm.configure(new, key, forget) { vm.clearTestMessage(); vm.clearSettingsError(); showSettings = false } },
        onDismiss = { vm.clearTestMessage(); vm.clearSettingsError(); showSettings = false })
    if (showHistory) HistoryDialog(vm.history, onOpen = { vm.openConversation(it); showHistory = false },
        onDelete = { deleteConversation = it }, onDismiss = { showHistory = false })
    deleteConversation?.let { c -> ConfirmDialog(title = "حذف گفتگو", message = "این گفتگو و تصویرهای بدون استفادهٔ آن از این دستگاه حذف شوند؟ سوابق برنامه تغییری نمی‌کند.",
        confirmLabel = "حذف گفتگو", destructive = true, onConfirm = { deleteConversation = null; vm.deleteConversation(c) }, onDismiss = { deleteConversation = null }) }
    if (confirmNew) ConfirmDialog(title = "گفتگوی جدید", message = "گفتگوی فعلی در تاریخچه می‌ماند و گفتگوی تازه‌ای شروع می‌شود. ادامه می‌دهید؟",
        confirmLabel = "شروع گفتگوی جدید", onConfirm = { confirmNew = false; vm.newChat() }, onDismiss = { confirmNew = false })
    if (confirmClear) ConfirmDialog(title = "پاک کردن تاریخچه", message = "همهٔ گفتگوها، حافظهٔ دستیار و تصویرهای بدون استفاده از این دستگاه پاک می‌شوند. اطلاعات مالی، سوخت و تعمیرات تغییری نمی‌کند.",
        confirmLabel = "پاک کردن", destructive = true, onConfirm = { confirmClear = false; vm.clearAllHistory() }, onDismiss = { confirmClear = false })
}

@Composable
private fun CompactAssistantBar(vm: AiAssistantViewModel, onBack: () -> Unit, onModels: () -> Unit, onOptions: () -> Unit) {
    val model = vm.modelState.models.firstOrNull { it.model.key.id == (vm.modelState.active?.id ?: vm.settings.modelFor()) }?.model
    val local = vm.settings.mode == AiMode.LOCAL
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("ai-chat-toolbar")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.testTag("ai-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") }
            Column(Modifier.weight(1f)) {
                Text("دستیار هوشمند", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(onClick = onModels, enabled = !vm.networkBusy && !vm.executingAction, shape = CircleShape, color = Color.Transparent,
                    modifier = Modifier.testTag("ai-chat-model-picker")) {
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChatBrandIcon(if (local) "local" else model?.let { ChatModelPresentation.brand(it).first } ?: AIProviderRegistry.get(vm.settings.mode).id, Modifier.size(16.dp))
                        Text(if (local) "دستیار آفلاین" else model?.name?.substringAfter(": ") ?: "انتخاب مدل", modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (model != null) Text(ChatModelPresentation.capacity(model.capability.contextLength), style = MaterialTheme.typography.labelSmall)
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp))
                    }
                }
            }
            IconButton(onOptions, Modifier.testTag("ai-options")) { Icon(Icons.Outlined.MoreHoriz, "گزینه‌های گفتگو") }
        }
    }
}

@Composable
private fun ChatWelcome(state: ConnectionState, onSettings: () -> Unit, onExample: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text("چی برات انجام بدم؟", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        listOf("گزارش این ماه", "وضعیت خودرو", "راهنما").forEach { title ->
            SuggestionChip(onClick = { onExample(title) }, label = { Text(title) })
        }
        if (state == ConnectionState.NOT_CONFIGURED || state == ConnectionState.NEEDS_CONSENT) TextButton(onSettings) { Text("تنظیم اتصال") }
    }
}

@Composable
private fun DisabledCard(onEnable: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("دستیار غیرفعال است", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp)); Button(onEnable) { Text("فعال کردن دستیار") }
    }
}

@Composable
private fun Bubble(entry: ChatEntry, imageStore: ChatImageStore, onImage: (ChatImageRef) -> Unit, onCopy: (String) -> Unit) {
    val user = entry.role == ChatRole.USER
    val background = when (entry.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.RESULT -> MaterialTheme.colorScheme.tertiaryContainer
        ChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.Absolute.Right else Arrangement.Absolute.Left) {
        Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (user) 22.dp else 6.dp, bottomEnd = if (user) 6.dp else 22.dp),
            color = background, modifier = Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (entry.role == ChatRole.RESULT) Text("نتیجهٔ واقعی عملیات", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                entry.image?.let { ref -> ChatImageThumbnail(imageStore.file(ref), Modifier.size(192.dp, 144.dp), onClick = { onImage(ref) }) }
                if (entry.text.isNotEmpty()) SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)) }
                if (entry.role == ChatRole.ASSISTANT || entry.role == ChatRole.RESULT) {
                    IconButton(onClick = { onCopy(entry.text) }, modifier = Modifier.align(Alignment.End).size(30.dp)) {
                        Icon(Icons.Outlined.ContentCopy, "کپی پاسخ", Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble(canStop: Boolean, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(if (canStop) "در حال پاسخ…" else "در حال انجام عملیات…", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (canStop) TextButton(onStop) { Text("توقف") }
    }
}

@Composable
private fun ChatFailureNotice(mode: AiMode, failure: AiResult.Failure, enabled: Boolean, onRetry: () -> Unit, onModel: () -> Unit, onDismiss: () -> Unit) {
    val title = AIProviderRegistry.get(mode).title
    val message = when (failure.kind) {
        AiErrorKind.RATE_LIMITED -> "$title: سهمیه یا ظرفیت سرویس فعلاً کافی نیست؛ صبر کنید یا مدل دیگری انتخاب کنید."
        AiErrorKind.CREDIT_EXHAUSTED -> "$title: اعتبار حساب کافی نیست؛ مدل را دستی انتخاب کنید."
        AiErrorKind.UNAUTHORIZED -> "$title: کلید یا مجوز حساب پذیرفته نشد."
        AiErrorKind.GUARDRAIL_REJECTED -> "$title: دسترسی حساب یا منطقه مجاز نیست."
        else -> "$title: ${failure.message}"
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(horizontal = 16.dp).testTag("ai-provider-notice")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (enabled && failure.kind !in ProviderFailurePolicy.NEVER_RETRY && failure.kind !in setOf(AiErrorKind.NOT_CONFIGURED, AiErrorKind.NOT_FOUND, AiErrorKind.MODEL_INVALID))
                IconButton(onRetry, Modifier.size(40.dp)) { Icon(Icons.Outlined.Refresh, "تلاش دوباره", Modifier.size(20.dp)) }
            IconButton(onModel, enabled = enabled, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Tune, "انتخاب مدل", Modifier.size(20.dp)) }
            IconButton(onDismiss, Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "بستن پیام", Modifier.size(17.dp)) }
        }
    }
}

@Composable
private fun ConsentDialog(settings: AiSettings, onAccept: () -> Unit, onDecline: () -> Unit) {
    val provider = AIProviderRegistry.get(settings.mode)
    AlertDialog(onDismissRequest = onDecline, title = { Text("اجازهٔ ارسال به ${provider.title}") },
        text = { Text("پیام و زمینهٔ همین گفتگو، تصویر انتخابی شما و نتیجهٔ لازم ابزارهای فعال به همین اتصال ارسال می‌شوند. کل دیتابیس یا فایل نامرتبط ارسال نمی‌شود. تاریخچه روی دستگاه است؛ کلید با Android Keystore رمزنگاری می‌شود.\n\n" +
            provider.conditions + "\n" + provider.iran + "\n\nآزمون مدل فقط با تأیید جداگانه است؛ در سلام، گفتگو یا پس‌زمینه اجرا نمی‌شود. برنامه محدودیت مصرف، خرید یا ارتقای حساب ندارد؛ هزینه و سهمیهٔ واقعی تابع حساب سرویس است. اتصال یا مدل پولی خودکار جایگزین نمی‌شود.\n\n" +
            "هر ثبت، ویرایش یا حذف اطلاعات برنامه تأیید جداگانه می‌خواهد. پس از تغییر اتصال یا کلید، تاریخچهٔ اتصال دیگر خودکار ارسال نمی‌شود.",
            modifier = Modifier.verticalScroll(rememberScrollState()).testTag("openrouter-consent")) },
        confirmButton = { Button(onAccept) { Text("موافقم و ادامه") } }, dismissButton = { TextButton(onDecline) { Text("انصراف") } })
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
