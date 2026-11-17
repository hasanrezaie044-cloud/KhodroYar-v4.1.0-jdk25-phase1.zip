package com.khodroyar.app.ai.gemma

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.ai.AgentStep
import com.khodroyar.app.ai.AppAgentTools
import com.khodroyar.app.ai.AiLimits
import com.khodroyar.app.ai.AiMode
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatImageRef
import com.khodroyar.app.ai.ChatImageStore
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.Conversation
import com.khodroyar.app.ai.ConversationRepository
import com.khodroyar.app.ai.PendingAction
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the screen shows about the on-device model. */
sealed interface ModelUiState {
    data object Checking : ModelUiState
    data object NotInstalled : ModelUiState
    /** A resumable partial is on disk from an earlier attempt. */
    data class Interrupted(val downloadedBytes: Long) : ModelUiState
    /** Actively downloading; see [GemmaAssistantViewModel.progress]. */
    data object Downloading : ModelUiState
    data class Paused(val downloadedBytes: Long) : ModelUiState
    data class Ready(val sizeBytes: Long) : ModelUiState
    /** A file with the wrong size exists — never loaded, must be deleted. */
    data class Corrupt(val actualBytes: Long) : ModelUiState
    data class Failed(val message: String) : ModelUiState
}

/**
 * Drives the offline Gemma assistant: model download/management, the chat loop with the
 * shared tool/confirmation stack, and the deterministic company-report comparison.
 */
class GemmaAssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = GemmaRuntime.get(app)
    private val repo = AppRepository(app)
    /** Same production tool runner as the 5.6.3 online assistant; inference stays local. */
    private val executor = AppAgentTools(app, repo)
    private val orchestrator = LocalAgentOrchestrator(runtime.engine(), executor)
    private val conversations = ConversationRepository(app, ConversationRepository.FILE_GEMMA)
    private val imageStore = ChatImageStore(app, "gemma-chat-images")

    /** UI-only thumbnail path; the persisted reference itself is a safe app-private filename. */
    fun thumbnailPath(ref: ChatImageRef): String = imageStore.file(ref).absolutePath

    val spec: GemmaModelSpec = runtime.spec

    var modelState by mutableStateOf<ModelUiState>(ModelUiState.Checking)
        private set
    var progress by mutableStateOf<ModelDownloadManager.Progress?>(null)
        private set

    val messages = mutableStateListOf<ChatEntry>()
    var busy by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingAction?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** Image the user attached but has not sent yet. */
    var stagedImage by mutableStateOf<ReportImagePrep.Prepared?>(null)
        private set
    var stagingImage by mutableStateOf(false)
        private set
    var history by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var conversationId = UUID.randomUUID().toString()
    private var createdAt = System.currentTimeMillis()
    private var chatJob: Job? = null
    private var downloadJob: Job? = null
    private var downloader: ModelDownloadManager? = null

    init {
        viewModelScope.launch {
            refreshModelState()
            val last = conversations.load().maxByOrNull { it.updatedAt }
            if (last != null) openConversation(last)
        }
    }

    // --------------------------------------------------------- model management

    fun refreshModelState() {
        val st = runtime.modelStore.state()
        if (modelState !is ModelUiState.Downloading) {
            modelState = when (st) {
                is ModelStore.InstallState.NotInstalled -> ModelUiState.NotInstalled
                is ModelStore.InstallState.Interrupted -> ModelUiState.Interrupted(st.downloadedBytes)
                is ModelStore.InstallState.Ready -> ModelUiState.Ready(st.sizeBytes)
                is ModelStore.InstallState.Corrupt -> ModelUiState.Corrupt(st.actualBytes)
            }
        }
    }

    private fun online(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The ONLY entry point that ever downloads the model. Explicit user action required. */
    fun startDownload() {
        if (modelState is ModelUiState.Downloading) return
        if (!online()) {
            modelState = ModelUiState.Failed("برای دانلود مدل اینترنت لازم است. پس از دانلود، دستیار کاملاً آفلاین کار می‌کند.")
            return
        }
        val partialBytes = runtime.modelStore.partialFile.let { if (it.isFile) it.length() else 0L }
        val verdict = runtime.deviceProfile.storageVerdict(spec, runtime.modelStore.rootDir, alreadyDownloaded = partialBytes)
        if (!verdict.ok) {
            modelState = ModelUiState.Failed(verdict.reasons.joinToString("\n"))
            return
        }
        // Soft RAM advisory: a weaker device may still try, but it is told honestly first.
        val ramVerdict = runtime.deviceProfile.ramVerdict(spec)
        if (!ramVerdict.ok) notice = ramVerdict.reasons.joinToString("\n")
        modelState = ModelUiState.Downloading
        progress = progress ?: ModelDownloadManager.Progress(partialBytes, spec.expectedSizeBytes, 0, 0, spec.expectedSizeBytes - partialBytes)
        val manager = runtime.makeDownloader()
        downloader = manager
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val outcome = manager.download { p ->
                launch(Dispatchers.Main) { progress = p }
            }
            withContext(Dispatchers.Main) {
                downloadJob = null
                when (val o = outcome) {
                    is ModelDownloadManager.Outcome.Installed -> {
                        progress = null
                        modelState = ModelUiState.Ready(o.bytes)
                        notice = if (o.digestTrusted) {
                            "مدل دانلود و دقیقاً با checksum رسمی تأیید شد. از این لحظه همه‌چیز آفلاین است."
                        } else {
                            "مدل دانلود شد و اندازهٔ آن دقیقاً مطابق نسخهٔ رسمی است (امکان خواندن checksum از سرور نبود). حالا آفلاین کار می‌کند."
                        }
                    }
                    is ModelDownloadManager.Outcome.Paused -> {
                        modelState = ModelUiState.Paused(o.downloaded)
                    }
                    is ModelDownloadManager.Outcome.Failed -> {
                        val resumed = runtime.modelStore.state()
                        modelState = when (val r = o.reason) {
                            is ModelDownloadManager.Reason.NotEnoughStorage -> ModelUiState.Failed(r.message)
                            is ModelDownloadManager.Reason.Network ->
                                if (resumed is ModelStore.InstallState.Interrupted) ModelUiState.Interrupted(resumed.downloadedBytes)
                                else ModelUiState.Failed(r.message + " — پس از برقراری اینترنت «ادامهٔ دانلود» را بزنید.")
                            is ModelDownloadManager.Reason.Integrity -> ModelUiState.Failed(r.message)
                            is ModelDownloadManager.Reason.Unexpected ->
                                if (resumed is ModelStore.InstallState.Interrupted) ModelUiState.Interrupted(resumed.downloadedBytes)
                                else ModelUiState.Failed(r.message)
                        }
                    }
                }
            }
        }
    }

    fun pauseDownload() {
        downloader?.pause()
    }

    /** Deletes the model, any partial download and meta — gives the storage back. */
    fun deleteModel() {
        downloadJob?.cancel()
        downloader?.pause()
        runtime.releaseEngine()
        val freed = runtime.modelStore.deleteAll()
        progress = null
        modelState = ModelUiState.NotInstalled
        notice = "مدل و فایل‌های دانلود حذف شدند (${DeviceProfile.mb(freed)} مگابایت آزاد شد)."
    }

    fun clearNotice() { notice = null }

    /** Real device RAM for the model-manager sheet. */
    fun runtimeRamText(): String {
        val info = runtime.deviceProfile.ramInfo()
        if (info.totalBytes <= 0) return "نامشخص"
        return "${DeviceProfile.gb(info.totalBytes)} گیگابایت (آزاد: ${DeviceProfile.mb(info.availableBytes)} مگابایت)"
    }

    // -------------------------------------------------------------- image staging

    fun stageImage(uri: Uri) {
        if (stagingImage) return
        stagingImage = true
        viewModelScope.launch {
            val prepared = ReportImagePrep.load(getApplication(), uri)
            stagingImage = false
            if (prepared == null) notice = "خواندن تصویر ممکن نشد؛ عکس دیگری انتخاب کنید."
            stagedImage = prepared
        }
    }

    /** Persists a staged thumbnail in this assistant's private, no-backup image namespace. */
    private suspend fun storeAttached(prepared: ReportImagePrep.Prepared): ChatImageRef =
        imageStore.storePreparedJpeg(prepared.thumbnailBytes)

    fun clearStagedImage() { stagedImage = null }

    // ------------------------------------------------------------------- chat

    fun send(raw: String) {
        val text = raw.trim()
        if (busy || pending != null) return
        // An attached image alone is a valid message; an image-only send runs the
        // deterministic report-extraction pipeline (the "compare with my records" flow).
        if (text.isEmpty() && stagedImage == null) return
        if (text.isEmpty()) { analyzeStagedReport(); return }
        if (text.length > AiLimits.MAX_USER_CHARS) return
        if (modelState !is ModelUiState.Ready) {
            failure = "مدل آفلاین هنوز نصب نیست. ابتدا آن را دانلود کنید."
            return
        }
        failure = null
        val image = stagedImage
        stagedImage = null
        viewModelScope.launch {
            val imageRef = image?.let { storeAttached(it) }
            messages += ChatEntry(UUID.randomUUID().toString(), ChatRole.USER, text, System.currentTimeMillis(), image = imageRef)
            persist()
            runAgent(image?.jpegBytes)
        }
    }

    fun retry() {
        if (busy || pending != null || messages.lastOrNull()?.role != ChatRole.USER) return
        failure = null
        // Full image bytes live only for the in-flight send; a retried message is text-only
        // (the bubble still shows the photo). We never pretend the model re-saw the image.
        runAgent(null)
    }

    private fun runAgent(imageBytes: ByteArray?) {
        busy = true
        chatJob = viewModelScope.launch {
            try {
                when (val step = orchestrator.run(messages.toList(), imageBytes)) {
                    is AgentStep.Reply -> add(ChatRole.ASSISTANT, step.text)
                    is AgentStep.Confirm -> {
                        if (step.pending.lead.isNotBlank()) add(ChatRole.ASSISTANT, step.pending.lead)
                        pending = step.pending
                    }
                    is AgentStep.Result -> add(ChatRole.RESULT, (if (step.outcome.ok) "✅ " else "⚠️ ") + step.outcome.text)
                    is AgentStep.Failure -> failure = step.error.message
                    AgentStep.Continue -> add(ChatRole.ASSISTANT, "برای ادامه، پیام بعدی را جداگانه بفرستید.")
                }
            } finally {
                busy = false
            }
        }
    }

    fun confirmPending() {
        val p = pending ?: return
        if (busy) return
        pending = null
        busy = true
        viewModelScope.launch {
            try {
                val out = orchestrator.execute(p)
                add(ChatRole.RESULT, (if (out.ok) "✅ " else "⚠️ ") + out.text)
            } finally {
                busy = false
            }
        }
    }

    fun cancelPending() {
        if (pending == null) return
        pending = null
        add(ChatRole.RESULT, "عملیات لغو شد. هیچ اطلاعاتی تغییر نکرد.")
    }

    fun stop() {
        chatJob?.cancel()
        busy = false
    }

    // ------------------------------------------------- company report comparison

    /**
     * The deterministic path: model only READS the image (one vision call); parsing,
     * validation and the comparison maths all happen in app code. Nothing is written to
     * the database. Unreadable values are surfaced, never guessed.
     */
    fun analyzeStagedReport(extraQuestion: String = "") {
        val image = stagedImage ?: return
        if (busy || modelState !is ModelUiState.Ready) {
            if (modelState !is ModelUiState.Ready) failure = "مدل آفلاین هنوز نصب نیست."
            return
        }
        stagedImage = null
        viewModelScope.launch {
            val imageRef = storeAttached(image)
            messages += ChatEntry(
                UUID.randomUUID().toString(), ChatRole.USER,
                "تصویر گزارش شرکت — خواندن و مقایسه با سوابق من" + if (extraQuestion.isNotBlank()) "\n$extraQuestion" else "",
                System.currentTimeMillis(), image = imageRef,
            )
            persist()
            busy = true
            try {
                val reply = runtime.engine().complete(
                    LocalLlmEngine.Request(
                        systemPrompt = GemmaPrompts.reportExtractionPrompt(),
                        history = emptyList(),
                        userText = "اطلاعات جدول این گزارش را دقیق استخراج کن." +
                            if (extraQuestion.isNotBlank()) "\nتوجه کاربر: ${extraQuestion.take(300)}" else "",
                        imageBytes = image.jpegBytes,
                        maxOutputTokens = GemmaPrompts.MAX_EXTRACTION_TOKENS,
                        disableThinking = true,
                    ),
                )
                val raw = when (reply) {
                    is LocalLlmEngine.Reply.Text -> reply.text
                    is LocalLlmEngine.Reply.Failure -> {
                        failure = when (reply.error) {
                            LocalLlmEngine.EngineError.OUT_OF_MEMORY -> "حافظهٔ کافی برای تحلیل تصویر در دسترس نیست. چند برنامهٔ باز را ببندید و دوباره تلاش کنید."
                            else -> "تحلیل تصویر ناموفق بود: ${reply.detail}"
                        }
                        return@launch
                    }
                }
                when (val parsed = CompanyReportParser.parse(raw)) {
                    is CompanyReportParser.ParseResult.Empty ->
                        add(ChatRole.RESULT, "⚠️ نتوانستم جدول معتبری از تصویر بخوانم. ${parsed.reason}\nچیزی ثبت یا تغییر نکرد.")
                    is CompanyReportParser.ParseResult.Ok -> {
                        val dates = parsed.report.rows.mapNotNull { it.date }
                        val snapshot = if (dates.isEmpty()) emptyList() else {
                            val minYear = dates.min().substringBefore('/')
                            val maxYear = dates.max().substringBefore('/')
                            // A company report never spans far; fetch involved years only.
                            val years = (minYear.toIntOrNull() ?: 0)..(maxYear.toIntOrNull() ?: 0)
                            repo.servicesByPrefixOnce("")
                                .filter { svc -> svc.date.substringBefore('/').toIntOrNull()?.let { it in years } == true }
                                .map {
                                    CompanyReportComparator.AppServiceRow(
                                        id = it.id.take(8), date = it.date, km = it.km,
                                        hours = it.hours, income = it.income,
                                    )
                                }
                        }
                        val comparison = CompanyReportComparator.compare(parsed.report, snapshot)
                        add(ChatRole.RESULT, CompanyReportComparator.formatPersian(comparison))
                        // Short natural-language wrap-up from the model over VERIFIED numbers.
                        val wrap = runtime.engine().complete(
                            LocalLlmEngine.Request(
                                systemPrompt = GemmaPrompts.comparisonExplainPrompt(CompanyReportComparator.formatPersian(comparison)),
                                history = emptyList(),
                                userText = "خلاصهٔ کوتاه فارسی برای کاربر.",
                                imageBytes = null,
                                maxOutputTokens = 400,
                                disableThinking = true,
                            ),
                        )
                        if (wrap is LocalLlmEngine.Reply.Text) {
                            val text = GemmaJsonParser.parse(wrap.text).visibleText
                            if (text.isNotBlank()) add(ChatRole.ASSISTANT, text)
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    // ----------------------------------------------------------------- history

    private fun add(role: ChatRole, text: String) {
        messages += ChatEntry(UUID.randomUUID().toString(), role, text, System.currentTimeMillis())
        persist()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == ChatRole.USER }?.text?.take(40) ?: "گفتگو"
        val convo = Conversation(
            conversationId, title, createdAt, System.currentTimeMillis(), messages.toList(),
            mode = AiMode.LOCAL, accountScope = "gemma-offline", contextStart = 0,
        )
        viewModelScope.launch {
            conversations.save(convo)
            pruneImages()
        }
    }

    private suspend fun pruneImages() {
        val retained = conversations.load().flatMap { it.messages }.mapNotNull { it.image }.toSet()
        withContext(Dispatchers.IO) { imageStore.prune(retained) }
    }

    fun newChat() {
        chatJob?.cancel()
        busy = false
        pending = null
        failure = null
        stagedImage = null
        messages.clear()
        conversationId = UUID.randomUUID().toString()
        createdAt = System.currentTimeMillis()
    }

    fun loadHistory() {
        viewModelScope.launch { history = conversations.load().sortedByDescending { it.updatedAt } }
    }

    fun openConversation(c: Conversation) {
        chatJob?.cancel()
        busy = false
        pending = null
        failure = null
        stagedImage = null
        messages.clear()
        messages.addAll(c.messages)
        conversationId = c.id
        createdAt = c.createdAt
    }

    fun deleteConversation(c: Conversation) {
        viewModelScope.launch {
            conversations.delete(c.id)
            if (c.id == conversationId) newChat()
            history = conversations.load().sortedByDescending { it.updatedAt }
            pruneImages()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            conversations.clearAll()
            history = emptyList()
            newChat()
            pruneImages()
        }
    }

    // ------------------------------------------------------------- lifecycle

    /** Called by the screen when the assistant page is left; frees the mapped model RAM. */
    fun onLeaveScreen() {
        runtime.releaseEngine()
    }

    override fun onCleared() {
        runtime.releaseEngine()
        super.onCleared()
    }
}
