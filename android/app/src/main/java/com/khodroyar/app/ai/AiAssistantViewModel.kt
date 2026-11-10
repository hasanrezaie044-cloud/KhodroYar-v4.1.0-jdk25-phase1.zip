package com.khodroyar.app.ai

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ConnectionState { LOCAL, READY, UNKNOWN, CHECKING, ONLINE, OFFLINE, NOT_CONFIGURED, NEEDS_CONSENT, DISABLED, ERROR }

class AiAssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = AiSettingsStore(app)
    private val conversations = ConversationRepository(app)
    private val tools = AgentToolExecutor(AppRepository(app))
    private val keyStore = AiApiKeyStore(app)
    private val backend = GapGPTProvider(app, settingsStore)
    private val direct = DirectGapGptProvider(settingsStore::load, keyStore)
    private val provider = AiProviderRouter({ settingsStore.load().mode }, backend, direct)
    private val orchestrator = AgentOrchestrator(provider, tools, settingsStore)
    private val local = LocalAssistant(tools)

    var settings by mutableStateOf(settingsStore.load())
        private set
    val messages = mutableStateListOf<ChatEntry>()
    var busy by mutableStateOf(false)
        private set
    var executingAction by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingAction?>(null)
        private set
    var failure by mutableStateOf<AiResult.Failure?>(null)
        private set
    var connection by mutableStateOf(ConnectionState.UNKNOWN)
        private set
    var serverModel by mutableStateOf("")
        private set
    var history by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var needsConsent by mutableStateOf(false)
        private set
    var testMessage by mutableStateOf<String?>(null)
        private set
    var hasGapGptKey by mutableStateOf(false)
        private set
    var savingSettings by mutableStateOf(false)
        private set
    var settingsError by mutableStateOf<String?>(null)
        private set

    private var conversationId = UUID.randomUUID().toString()
    private var createdAt = System.currentTimeMillis()
    private var queuedText: String? = null
    private var queuedTest = false
    private var job: Job? = null
    private var statusJob: Job? = null
    private var testJob: Job? = null
    private var generation = 0
    private var keyRevision = 0

    val activeModel: String get() = when (settings.mode) {
        AiMode.LOCAL -> "داخلی — بدون API"
        AiMode.ONLINE -> settings.model.ifBlank { serverModel }
        AiMode.GAPGPT_DIRECT -> "GapGPT مستقیم · ${GapGptDirectContract.selectedModel(settings)}"
    }

    init {
        viewModelScope.launch {
            val revision = keyRevision
            val storedKey = withContext(Dispatchers.IO) {
                runCatching { keyStore.read()?.let(GapGptDirectContract::normalizeApiKey) != null }.getOrDefault(false)
            }
            if (revision == keyRevision) hasGapGptKey = storedKey
            val last = conversations.load().filter { it.mode == settings.mode }.maxByOrNull { it.updatedAt }
            if (last != null && messages.isEmpty() && !busy) openConversation(last)
            refreshStatus()
        }
    }

    // Local status/testing are synchronous and NEVER call the online provider.
    fun refreshStatus() {
        statusJob?.cancel()
        connection = when {
            !settings.enabled -> ConnectionState.DISABLED
            settings.mode == AiMode.LOCAL -> ConnectionState.LOCAL
            settings.mode == AiMode.GAPGPT_DIRECT && (!hasGapGptKey || GapGptDirectContract.normalizeBaseUrl(settings.gapGptBaseUrl) == null) -> ConnectionState.NOT_CONFIGURED
            settings.mode == AiMode.ONLINE && !AiSettingsStore.isValidBackendUrl(settings.backendUrl) -> ConnectionState.NOT_CONFIGURED
            !settings.consented -> ConnectionState.NEEDS_CONSENT
            // A direct connection test consumes credit: NEVER probe automatically on screen open.
            settings.mode == AiMode.GAPGPT_DIRECT -> ConnectionState.READY
            else -> ConnectionState.CHECKING
        }
        if (connection != ConnectionState.CHECKING) return
        statusJob = viewModelScope.launch {
            applyStatus(provider.status())
        }
    }

    private fun applyStatus(s: AiProviderStatus) {
        serverModel = s.defaultModel
        connection = when {
            s.errorKind == AiErrorKind.NOT_CONFIGURED -> ConnectionState.NOT_CONFIGURED
            !s.reachable -> ConnectionState.OFFLINE
            s.errorKind != null -> ConnectionState.ERROR
            !s.enabled -> ConnectionState.DISABLED
            !s.configured -> ConnectionState.NOT_CONFIGURED
            else -> ConnectionState.ONLINE
        }
    }

    fun testConnection() {
        testJob?.cancel()
        if (settings.mode == AiMode.LOCAL) {
            testMessage = "حالت داخلی آماده است؛ بدون اینترنت، سرور، کلید API و هزینهٔ توکن کار می‌کند. برای آزمایش «گزارش این ماه» بفرستید. این حالت مدل زبانی عمومی نیست."
            refreshStatus()
            return
        }
        if (!settings.enabled) {
            testMessage = "ابتدا دستیار را فعال کنید."
            return
        }
        if (settings.mode == AiMode.GAPGPT_DIRECT) {
            if (GapGptDirectContract.normalizeBaseUrl(settings.gapGptBaseUrl) == null || !hasGapGptKey) {
                testMessage = "آدرس رسمی و کلید واقعی GapGPT را در قسمت اتصال مستقیم تنظیم کنید. جای‌نگهدار YOUR_GAPGPT_API_KEY کار نمی‌کند."
                return
            }
        } else if (!AiSettingsStore.isValidBackendUrl(settings.backendUrl)) {
            testMessage = "برای آدرس api.gapgpt.app یا api.gapapi.com حالت «اتصال مستقیم GapGPT» را انتخاب کنید؛ این آدرس‌ها بک‌اند خودرویار نیستند."
            return
        }
        if (!settings.consented) {
            queuedTest = true
            needsConsent = true
            return
        }
        testMessage = "در حال آزمایش…"
        testJob = viewModelScope.launch {
            val s = provider.status()
            applyStatus(s)
            testMessage = when {
                settings.mode == AiMode.GAPGPT_DIRECT -> "POST ${GapGptDirectContract.chatUrl(settings.gapGptBaseUrl)}\n${s.message}"
                s.errorKind != null || !s.reachable -> s.message
                !s.enabled -> "سرور در دسترس است ولی دستیار آنلاین غیرفعال است."
                !s.configured -> "سرور در دسترس است ولی کلید سرویس مدل روی سرور تنظیم نشده است. حالت داخلی به کلید نیاز ندارد."
                else -> when (backend.checkModel()) {
                    true -> "اتصال برقرار است و مدل «${settings.model.ifBlank { s.defaultModel }}» موجود است."
                    false -> "مدل «${settings.model.ifBlank { s.defaultModel }}» در حساب سرور پیدا نشد؛ نام مدل را بررسی کنید."
                    null -> "بک‌اند آماده است ولی بررسی فهرست مدل‌ها ممکن نبود."
                }
            }
        }
    }

    fun clearTestMessage() { testMessage = null }

    /** Encrypt a user-supplied key off the main thread; never put it in normal settings. */
    fun configure(new: AiSettings, apiKey: String, forgetKey: Boolean, afterSave: () -> Unit) {
        if (executingAction || savingSettings) return
        settingsError = null
        val typed = apiKey.trim()
        if (typed.isNotEmpty() && GapGptDirectContract.normalizeApiKey(typed) == null) {
            settingsError = "کلید معتبر نیست. کلید واقعی حساب را وارد کنید، نه YOUR_GAPGPT_API_KEY؛ کلید را در چت نفرستید."
            return
        }
        if (new.mode == AiMode.GAPGPT_DIRECT && GapGptDirectContract.normalizeBaseUrl(new.gapGptBaseUrl) == null) {
            settingsError = "یکی از دو آدرس رسمی GapGPT را انتخاب کنید."
            return
        }
        val credentialChanged = typed.isNotEmpty() || forgetKey
        statusJob?.cancel()
        testJob?.cancel()
        stop()
        if (credentialChanged) {
            keyRevision++
            // Revoke consent BEFORE writing a different account key, even if the app is stopped.
            settingsStore.save(settings.copy(consented = false))
            settings = settingsStore.load()
            newChat()
        }
        savingSettings = true
        viewModelScope.launch {
            try {
                if (credentialChanged || new.mode == AiMode.GAPGPT_DIRECT) {
                    hasGapGptKey = withContext(Dispatchers.IO) {
                        if (typed.isNotEmpty()) keyStore.save(typed)
                        else if (forgetKey) keyStore.clear()
                        keyStore.read()?.let(GapGptDirectContract::normalizeApiKey) != null
                    }
                }
                if (new.enabled && new.mode == AiMode.GAPGPT_DIRECT && !hasGapGptKey && !forgetKey) {
                    settingsError = "اتصال مستقیم به API Key واقعی حساب نیاز دارد. برای استفاده بدون کلید، حالت داخلی را انتخاب کنید."
                    return@launch
                }
                // Remove a legacy plaintext copy only when the user explicitly re-enters that
                // SAME value as their direct provider key. Never guess/migrate a backend token.
                val oldTokenIsKey = typed.isNotEmpty() && new.mode == AiMode.GAPGPT_DIRECT &&
                    GapGptDirectContract.normalizeApiKey(new.clientToken) == GapGptDirectContract.normalizeApiKey(typed)
                val cleaned = if (oldTokenIsKey) new.copy(clientToken = "") else new
                saveSettings(cleaned, forceNewConsent = credentialChanged)
                afterSave()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                settingsError = "ذخیره/خواندن امن کلید روی گوشی انجام نشد. هیچ کلیدی در متن ساده ذخیره نمی‌شود؛ دوباره تلاش کنید."
            } finally {
                savingSettings = false
            }
        }
    }

    fun clearSettingsError() { settingsError = null }

    fun saveSettings(new: AiSettings, forceNewConsent: Boolean = false) {
        if (executingAction) return
        val root = AiBackendContract.normalizeRoot(new.backendUrl) ?: new.backendUrl.trim()
        val directUrl = GapGptDirectContract.normalizeBaseUrl(new.gapGptBaseUrl) ?: new.gapGptBaseUrl.trim()
        val targetChanged = when (new.mode) {
            AiMode.LOCAL -> false
            AiMode.ONLINE -> root != settings.backendUrl
            AiMode.GAPGPT_DIRECT -> directUrl != settings.gapGptBaseUrl
        }
        val modeChanged = new.mode != settings.mode
        if (modeChanged || targetChanged || forceNewConsent) newChat()
        else if (!new.enabled) { stop(); pending = null }
        statusJob?.cancel()
        testJob?.cancel()
        settingsStore.save(new.copy(
            backendUrl = root, gapGptBaseUrl = directUrl,
            consented = if (targetChanged || modeChanged || forceNewConsent) false else new.consented,
        ))
        settings = settingsStore.load()
        needsConsent = false
        queuedText = null
        queuedTest = false
        testMessage = null
        failure = null
        refreshStatus()
    }

    fun chooseDirectGapGpt() {
        if (busy || savingSettings) return
        val url = GapGptDirectContract.normalizeBaseUrl(settings.backendUrl) ?: settings.gapGptBaseUrl
        saveSettings(settings.copy(mode = AiMode.GAPGPT_DIRECT, gapGptBaseUrl = url))
    }

    fun acceptConsent() {
        val text = queuedText
        val test = queuedTest
        settingsStore.save(settings.copy(consented = true))
        settings = settingsStore.load()
        needsConsent = false
        queuedText = null
        queuedTest = false
        if (test) testConnection() else refreshStatus()
        if (text != null) send(text)
    }

    fun declineConsent() {
        needsConsent = false
        queuedText = null
        queuedTest = false
    }

    /** Explicit user choice after an online error. The failed request is retried locally only. */
    fun useLocalMode() {
        if (busy) return
        val text = if (failure != null) messages.lastOrNull()?.takeIf { it.role == ChatRole.USER }?.text else null
        saveSettings(settings.copy(mode = AiMode.LOCAL))
        if (text != null) send(text)
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy || savingSettings || pending != null || !settings.enabled || text.length > AiLimits.MAX_USER_CHARS) return
        if (settings.mode != AiMode.LOCAL && !settings.consented) {
            queuedText = text
            needsConsent = true
            return
        }
        failure = null
        messages.add(ChatEntry(UUID.randomUUID().toString(), ChatRole.USER, text, System.currentTimeMillis()))
        persist()
        runAgent()
    }

    fun retry() {
        if (busy || savingSettings || pending != null || !settings.enabled || messages.lastOrNull()?.role != ChatRole.USER) return
        if (settings.mode != AiMode.LOCAL && !settings.consented) return
        failure = null
        runAgent()
    }

    private fun runAgent() {
        busy = true
        val run = ++generation
        val mode = settings.mode
        val snapshot = messages.toList()
        job = viewModelScope.launch {
            try {
                val step = if (mode == AiMode.LOCAL) local.run(snapshot.last().text) else orchestrator.run(snapshot)
                coroutineContext.ensureActive()
                if (run != generation) return@launch
                if (mode == AiMode.GAPGPT_DIRECT && step !is AgentStep.Failure) connection = ConnectionState.ONLINE
                when (step) {
                    is AgentStep.Reply -> add(ChatRole.ASSISTANT, step.text)
                    is AgentStep.Result -> add(ChatRole.RESULT, (if (step.outcome.ok) "" else "⚠️ ") + step.outcome.text)
                    is AgentStep.Confirm -> {
                        if (step.pending.lead.isNotBlank()) add(ChatRole.ASSISTANT, step.pending.lead)
                        pending = step.pending
                    }
                    is AgentStep.Failure -> {
                        failure = step.error
                        connection = when (step.error.kind) {
                            AiErrorKind.OFFLINE, AiErrorKind.TIMEOUT -> ConnectionState.OFFLINE
                            AiErrorKind.NOT_CONFIGURED -> ConnectionState.NOT_CONFIGURED
                            AiErrorKind.DISABLED -> ConnectionState.DISABLED
                            else -> ConnectionState.ERROR
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (run == generation) failure = AiResult.Failure(AiErrorKind.UNKNOWN, "دریافت پاسخ انجام نشد. دوباره تلاش کنید؛ هیچ عملیات تأییدنشده‌ای اجرا نمی‌شود.")
            } finally {
                if (run == generation) busy = false
            }
        }
    }

    fun confirmPending() {
        val p = pending ?: return
        if (busy || savingSettings || !settings.enabled) return
        pending = null
        busy = true
        executingAction = true
        job = viewModelScope.launch {
            try {
                val out = tools.execute(p.call)
                add(ChatRole.RESULT, (if (out.ok) "✅ " else "⚠️ ") + out.text)
            } finally {
                busy = false
                executingAction = false
            }
        }
    }

    fun cancelPending() {
        if (pending == null) return
        pending = null
        add(ChatRole.RESULT, "عملیات لغو شد. هیچ اطلاعاتی تغییر نکرد.")
    }

    fun stop() {
        // Once a database operation is approved, let its write + verification finish together.
        if (executingAction) return
        generation++
        job?.cancel()
        busy = false
    }

    private fun add(role: ChatRole, text: String) {
        messages.add(ChatEntry(UUID.randomUUID().toString(), role, text, System.currentTimeMillis()))
        persist()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == ChatRole.USER }?.text?.take(40) ?: "گفتگو"
        val convo = Conversation(conversationId, title, createdAt, System.currentTimeMillis(), messages.toList(), settings.mode)
        viewModelScope.launch { conversations.save(convo) }
    }

    fun newChat() {
        if (executingAction) return
        stop()
        pending = null
        failure = null
        declineConsent()
        messages.clear()
        conversationId = UUID.randomUUID().toString()
        createdAt = System.currentTimeMillis()
    }

    fun loadHistory() {
        viewModelScope.launch { history = conversations.load().filter { it.mode == settings.mode }.sortedByDescending { it.updatedAt } }
    }

    fun openConversation(c: Conversation) {
        if (executingAction || c.mode != settings.mode) return
        newChat()
        messages.addAll(c.messages)
        conversationId = c.id
        createdAt = c.createdAt
    }

    fun deleteConversation(c: Conversation) {
        if (executingAction) return
        viewModelScope.launch {
            conversations.delete(c.id)
            if (c.id == conversationId) newChat()
            history = conversations.load().filter { it.mode == settings.mode }.sortedByDescending { it.updatedAt }
        }
    }

    /** Wipes chat history only; never financial/vehicle records. */
    fun clearAllHistory() {
        if (executingAction) return
        viewModelScope.launch {
            conversations.clearAll()
            history = emptyList()
            newChat()
        }
    }
}
