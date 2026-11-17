package com.khodroyar.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.ai.AiLimits
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.Conversation
import com.khodroyar.app.ai.PendingAction
import com.khodroyar.app.ai.gemma.DeviceProfile
import com.khodroyar.app.ai.gemma.GemmaAssistantViewModel
import com.khodroyar.app.ai.gemma.ModelUiState
import com.khodroyar.app.ui.components.ConfirmDialog
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.InfoRow
import com.khodroyar.app.ui.components.fieldColors

/**
 * «دستیار آفلاین (Gemma)» — a separate, clearly-labelled entry from the online assistant.
 * Everything here runs on the device; the ONLY network use of this screen is the explicit
 * model download, and the header chip says so.
 */
@Composable
fun GemmaAssistantScreen(vm: GemmaAssistantViewModel = viewModel()) {
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showModelManager by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteModel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Leaving this screen = the user is done with the offline assistant for now → free the RAM.
    DisposableEffect(Unit) { onDispose { vm.onLeaveScreen() } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.stageImage(uri)
    }
    fun launchPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val extraRows = (if (vm.pending != null) 1 else 0) + (if (vm.busy) 1 else 0) + (if (vm.failure != null) 1 else 0)
    LaunchedEffect(vm.messages.size, extraRows) {
        val total = vm.messages.size + extraRows
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Column(Modifier.fillMaxSize()) {
        GemmaHeader(
            modelState = vm.modelState,
            onNew = { if (vm.messages.isEmpty()) vm.newChat() else confirmNew = true },
            onHistory = { vm.loadHistory(); showHistory = true },
            onClear = { confirmClear = true },
            canClear = vm.messages.isNotEmpty(),
            onModel = { showModelManager = true },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val st = vm.modelState
            when {
                st is ModelUiState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                st !is ModelUiState.Ready && vm.messages.isEmpty() -> ModelGate(vm, st, onManage = { showModelManager = true })
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (st !is ModelUiState.Ready) {
                        item(key = "model-banner") { ModelBanner(st, onManage = { showModelManager = true }) }
                    }
                    items(vm.messages, key = { it.id }) { entry -> GemmaBubble(entry) }
                    vm.pending?.let { p -> item(key = "pending") { GemmaConfirmCard(p, onConfirm = vm::confirmPending, onCancel = vm::cancelPending) } }
                    if (vm.busy) item(key = "busy") { GemmaTyping(onStop = vm::stop) }
                    vm.failure?.let { f ->
                        item(key = "failure") { GemmaError(f, onRetry = vm::retry) }
                    }
                }
            }
        }

        GemmaInputBar(
            value = input,
            onChange = { input = it.take(AiLimits.MAX_USER_CHARS) },
            enabled = vm.modelState is ModelUiState.Ready && vm.pending == null,
            busy = vm.busy,
            stagedThumbnail = vm.stagedImage?.thumbnailBytes,
            staging = vm.stagingImage,
            onAttach = ::launchPicker,
            onRemoveImage = vm::clearStagedImage,
            onCompare = { vm.analyzeStagedReport() },
            onSend = { vm.send(input); input = "" },
        )
    }

    vm.notice?.let {
        AlertDialog(
            onDismissRequest = vm::clearNotice,
            title = { Text("دستیار آفلاین Gemma") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::clearNotice) { Text("فهمیدم") } },
        )
    }

    if (showHistory) {
        GemmaHistoryDialog(
            list = vm.history,
            onOpen = { vm.openConversation(it); showHistory = false },
            onDelete = vm::deleteConversation,
            onDismiss = { showHistory = false },
        )
    }

    if (showModelManager) {
        ModelManagerDialog(
            vm = vm,
            onClose = { showModelManager = false },
            onRequestDelete = { confirmDeleteModel = true },
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
            message = "همهٔ گفتگوهای دستیار آفلاین از این دستگاه پاک می‌شود. اطلاعات مالی و خودرویی شما تغییری نمی‌کند.",
            confirmLabel = "پاک کردن",
            destructive = true,
            onConfirm = { confirmClear = false; vm.clearAllHistory() },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmDeleteModel) {
        ConfirmDialog(
            title = "حذف مدل Gemma",
            message = "مدل دانلودشده و هر دانلود ناتمام پاک می‌شود تا حافظه آزاد شود. برای استفادهٔ دوبارهٔ دستیار باید دوباره دانلود کنید. مطمئنید؟",
            confirmLabel = "حذف مدل",
            destructive = true,
            onConfirm = { confirmDeleteModel = false; vm.deleteModel(); showModelManager = false },
            onDismiss = { confirmDeleteModel = false },
        )
    }
}

/* -------------------------------------------------------------------- header */

@Composable
private fun GemmaHeader(
    modelState: ModelUiState,
    onNew: () -> Unit,
    onHistory: () -> Unit,
    onClear: () -> Unit,
    canClear: Boolean,
    onModel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF2E7D32)))
                Spacer(Modifier.width(7.dp))
                Text("کاملاً آفلاین", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
            }
            Text(
                "مدل: Gemma 4 E2B (روی دستگاه)" + when (modelState) {
                    is ModelUiState.Ready -> " · آماده"
                    is ModelUiState.Downloading -> " · در حال دانلود"
                    is ModelUiState.Paused -> " · دانلود متوقف‌شده"
                    is ModelUiState.Interrupted -> " · دانلود ناتمام"
                    is ModelUiState.Corrupt -> " · فایل خراب"
                    is ModelUiState.Failed -> " · خطای دانلود"
                    else -> " · نصب نشده"
                },
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onNew) { Icon(Icons.Outlined.Add, contentDescription = "گفتگوی جدید") }
        IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, contentDescription = "تاریخچه") }
        IconButton(onClick = onClear, enabled = canClear) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "پاک کردن تاریخچه") }
        IconButton(onClick = onModel) { Icon(Icons.Outlined.Memory, contentDescription = "مدیریت مدل") }
    }
}

/* --------------------------------------------------------- model gate & banner */

@Composable
private fun ModelGate(vm: GemmaAssistantViewModel, st: ModelUiState, onManage: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text("دستیار آفلاین Gemma 4 E2B", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        when (st) {
            is ModelUiState.NotInstalled -> {
                Text(
                    "برای گفتگو و تحلیل تصویر بدون اینترنت، مدل باید یک‌بار روی همین دستگاه دانلود شود. هیچ‌وقت به‌صورت خودکار دانلود نمی‌شود.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.startDownload() }) {
                    Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("دانلود مدل (۲٫۴ گیگابایت)")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onManage) { Text("جزئیات و نیازمندی‌ها") }
            }
            is ModelUiState.Downloading -> DownloadCard(vm)
            is ModelUiState.Paused, is ModelUiState.Interrupted -> {
                val bytes = (st as? ModelUiState.Paused)?.downloadedBytes ?: (st as? ModelUiState.Interrupted)?.downloadedBytes ?: 0L
                Text(
                    "دانلود ناتمام است: ${DeviceProfile.mb(bytes)} از ${DeviceProfile.mb(vm.spec.expectedSizeBytes)} مگابایت دانلود شده.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.startDownload() }) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ادامهٔ دانلود")
                }
            }
            is ModelUiState.Corrupt -> {
                Text(
                    "فایل مدل خراب یا ناقص است و هرگز بارگذاری نمی‌شود. آن را حذف و دوباره دانلود کنید.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onManage) { Text("مدیریت مدل") }
            }
            is ModelUiState.Failed -> {
                Text(st.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.startDownload() }) { Text("تلاش دوباره") }
            }
            else -> Unit
        }
    }
}

@Composable
private fun ModelBanner(st: ModelUiState, onManage: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "مدل آفلاین نصب نیست؛ برای فعال شدن دستیار آن را دانلود کنید.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text("مدیریت") }
        }
    }
}

/* -------------------------------------------------------------- download card */

@Composable
private fun DownloadCard(vm: GemmaAssistantViewModel) {
    val p = vm.progress
    val downloaded = p?.downloaded ?: 0L
    val total = p?.total ?: vm.spec.expectedSizeBytes
    val percent = p?.percent ?: 0
    val speed = p?.bytesPerSecond ?: 0L
    val remainingSeconds = if (speed > 0) (total - downloaded) / speed else -1L

    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("در حال دانلود مدل Gemma 4 E2B", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "$percent٪ — ${DeviceProfile.mb(downloaded)} از ${DeviceProfile.mb(total)} مگابایت" +
                    (if (speed > 0) " · سرعت ${"%.1f".format(speed / 1048576.0)} مگابایت/ثانیه" else "") +
                    (if (remainingSeconds > 0) " · حدود ${remainingSeconds / 60 + 1} دقیقه مانده" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::pauseDownload, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Pause, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("توقف موقت")
                }
            }
            Text(
                "در صورت قطع شدن اینترنت، دانلود از همان نقطه ادامه می‌یابد. پس از نصب، مدل همیشه آفلاین کار می‌کند.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------- bubbles */

@Composable
private fun rememberBitmap(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    return remember(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
}

@Composable
private fun GemmaBubble(entry: ChatEntry) {
    val isUser = entry.role == ChatRole.USER
    val (bg, fg) = when (entry.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        ChatRole.RESULT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.Absolute.Right else Arrangement.Absolute.Left) {
        Surface(shape = RoundedCornerShape(18.dp), color = bg, contentColor = fg, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
                if (entry.imagePath.isNotBlank()) {
                    rememberBitmap(entry.imagePath)?.let { bmp ->
                        Image(
                            bmp, contentDescription = "تصویر پیوست‌شده",
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .heightIn(max = 160.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (entry.role == ChatRole.RESULT) {
                    Text("نتیجهٔ واقعی برنامه", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                }
                SelectionContainer {
                    Text(entry.text, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                }
            }
        }
    }
}

@Composable
private fun GemmaTyping(onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Left) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("مدل روی دستگاه در حال پردازش است…", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onStop) { Text("توقف") }
            }
        }
    }
}

@Composable
private fun GemmaConfirmCard(p: PendingAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
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
                "تا تأیید نکنید چیزی ثبت یا تغییر نمی‌کند.",
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
private fun GemmaError(message: String, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text("تلاش دوباره") }
            }
        }
    }
}

/* ----------------------------------------------------------------- input bar */

@Composable
private fun GemmaInputBar(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    stagedThumbnail: ByteArray?,
    staging: Boolean,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onCompare: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (stagedThumbnail != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val bmp = remember(stagedThumbnail) {
                        runCatching { BitmapFactory.decodeByteArray(stagedThumbnail, 0, stagedThumbnail.size)?.asImageBitmap() }.getOrNull()
                    }
                    bmp?.let {
                        Image(it, "پیش‌نمایش تصویر", modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("تصویر پیوست شد", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "پیام خود را بنویسید یا بدون متن ارسال کنید.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCompare, enabled = !busy) {
                        Icon(Icons.Outlined.Compare, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("مقایسه با سوابق")
                    }
                    IconButton(onClick = onRemoveImage) { Icon(Icons.Outlined.Close, "حذف تصویر") }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onAttach, enabled = enabled && !busy && !staging, modifier = Modifier.padding(bottom = 4.dp)) {
                    if (staging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "افزودن عکس", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text(if (enabled) "پیام فارسی بنویسید یا عکس ضمیمه کنید…" else "ابتدا مدل را دانلود کنید", maxLines = 1) },
                    maxLines = 5,
                    shape = FieldShape,
                    colors = fieldColors(),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                )
                val canSend = enabled && !busy && (value.isNotBlank() || stagedThumbnail != null)
                Surface(
                    shape = CircleShape,
                    color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.size(48.dp),
                    ) {
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
}

/* ------------------------------------------------------------ model manager */

@Composable
private fun ModelManagerDialog(vm: GemmaAssistantViewModel, onClose: () -> Unit, onRequestDelete: () -> Unit) {
    val st = vm.modelState
    val ram = vm.runtimeRamText()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("مدیریت مدل آفلاین") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("مدل", "${vm.spec.displayName} · LiteRT-LM")
                InfoRow("نسخهٔ مدل (commit)", vm.spec.pinnedCommit.take(8))
                InfoRow("حجم مدل", "${DeviceProfile.mb(vm.spec.expectedSizeBytes)} مگابایت")
                InfoRow(
                    "وضعیت", when (st) {
                        is ModelUiState.Ready -> "نصب‌شده و آماده"
                        is ModelUiState.Downloading -> "در حال دانلود"
                        is ModelUiState.Paused -> "متوقف‌شده (قابل ادامه)"
                        is ModelUiState.Interrupted -> "ناتمام (قابل ادامه)"
                        is ModelUiState.Corrupt -> "خراب/ناقص — حذف کنید"
                        is ModelUiState.Failed -> "خطا در دانلود"
                        else -> "دانلود نشده"
                    },
                )
                InfoRow("رم دستگاه", ram)
                InfoRow("حداقل رسمی", "۸ گیگابایت رم — دقیقاً سازگار با Galaxy S23 FE شما")
                InfoRow("منبع دانلود", "Hugging Face · litert-community (رسمی)", emphasis = false)
                InfoRow("اجازه‌نامه", "Apache 2.0")
                if (st is ModelUiState.Downloading) {
                    Spacer(Modifier.height(8.dp))
                    DownloadCard(vm)
                }
            }
        },
        confirmButton = {
            when (st) {
                is ModelUiState.Ready -> TextButton(onClick = onRequestDelete) { Text("حذف مدل", color = MaterialTheme.colorScheme.error) }
                is ModelUiState.Corrupt, is ModelUiState.Failed -> TextButton(onClick = onRequestDelete) { Text("حذف فایل‌ها", color = MaterialTheme.colorScheme.error) }
                is ModelUiState.Paused, is ModelUiState.Interrupted, is ModelUiState.NotInstalled ->
                    TextButton(onClick = { vm.startDownload() }) { Text(if (st is ModelUiState.NotInstalled) "دانلود" else "ادامهٔ دانلود") }
                is ModelUiState.Downloading -> TextButton(onClick = { vm.pauseDownload() }) { Text("توقف موقت") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("بستن") } },
    )
}

@Composable
private fun GemmaHistoryDialog(
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
