package com.khodroyar.app.ai

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnectionState { LOCAL, NEEDS_CONSENT, UNKNOWN, CHECKING, ONLINE, OFFLINE, NOT_CONFIGURED, DISABLED, ERROR }

class AiAssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = AiSettingsStore(app)
    private val conversations = ConversationRepository(app)
    private val provider = GapGPTProvider(app, settingsStore)
    private val tools = AgentToolExecutor(AppRepository(app))
    private val orchestrator = AgentOrchestrator(provider, tools, settingsStore)
    private val local = LocalAssistant(tools)
    private var executingAction = false
    private var generation = 0
    private var statusJob: Job? = null

    var settings by mutableStateOf(settingsStore.load())
        private set
    val messages = mutableStateListOf<ChatEntry>()
    var busy by mutableStateOf(false)
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
    /** Set when the user tried to send before accepting the data-sharing notice. */
    var needsConsent by mutableStateOf(false)
        private set
    var testMessage by mutableStateOf<String?>(null)
        private set

    private var conversationId: String = UUID.randomUUID().toString()
    private var createdAt: Long = System.currentTimeMillis()
    private var queuedText: String? = null
    private var job: Job? = null

    val activeModel: String get() = if (settings.mode == AiMode.LOCAL) "دستیار داخلی · بدون مدل سنگین" else settings.model.ifBlank { serverModel }

    init {
        viewModelScope.launch {
            val last = conversations.load().filter { it.mode == settings.mode }.maxByOrNull { it.updatedAt }
            if (last != null) openConversation(last)
            refreshStatus()
        }
    }

    // --------------------------------------------------------------- status

    fun refreshStatus() {
        statusJob?.cancel()
        if (!settings.enabled) { connection = ConnectionState.DISABLED; return }
        if (settings.mode == AiMode.LOCAL) { connection = ConnectionState.LOCAL; return }
        if (!settings.consented) { connection = ConnectionState.NEEDS_CONSENT; return }
        connection = ConnectionState.CHECKING
        statusJob = viewModelScope.launch {
            val s = provider.status()
            if (s.defaultModel.isNotBlank()) serverModel = s.defaultModel
            connection = when {
                !AiSettingsStore.isValidBackendUrl(settings.backendUrl) -> ConnectionState.NOT_CONFIGURED
                !s.reachable -> ConnectionState.OFFLINE
                !s.enabled -> ConnectionState.DISABLED
                !s.configured -> ConnectionState.ERROR
                else -> ConnectionState.ONLINE
            }
        }
    }

    /** Real connection test: server reachable, key configured, and the chosen model really exists. */
    fun testConnection() {
        if (settings.mode == AiMode.LOCAL) {
            testMessage = "حالت داخلی آماده است؛ بدون اینترنت و API. مدل زبانی عمومی نیست؛ «راهنما» را بفرستید."
            return
        }
        if (!settings.enabled || !settings.consented) {
            testMessage = "پیش از اتصال آنلاین، پیام بفرستید و رضایت ارسال داده را تأیید کنید."
            return
        }
        testMessage = "در حال آزمایش…"
        viewModelScope.launch {
            val s = provider.status()
            testMessage = when {
                !s.reachable -> "اتصال برقرار نشد: ${s.message}"
                !s.enabled -> "سرور در دسترس است ولی دستیار از سمت سرور غیرفعال است."
                !s.configured -> "سرور در دسترس است ولی کلید GapGPT در سرور تنظیم نشده است."
                else -> when (provider.checkModel()) {
                    true -> "اتصال برقرار است و مدل «${settings.model.ifBlank { s.defaultModel }}» در حساب شما موجود است."
                    false -> "سرور وصل است ولی مدل «${settings.model.ifBlank { s.defaultModel }}» در حساب شما پیدا نشد."
                    null -> "سرور وصل است ولی بررسی فهرست مدل‌ها ممکن نبود."
                }
            }
            refreshStatus()
        }
    }

    fun clearTestMessage() { testMessage = null }

    // ------------------------------------------------------------- settings

    fun saveSettings(new: AiSettings) {
        if (executingAction) return
        if (new.mode != settings.mode || new.backendUrl != settings.backendUrl) newChat()
        val safe = if (new.backendUrl != settings.backendUrl) new.copy(consented = false) else new
        settingsStore.save(safe)
        settings = settingsStore.load()
        refreshStatus()
    }

    fun acceptConsent() {
        saveSettings(settings.copy(consented = true))
        needsConsent = false
        val q = queuedText
        queuedText = null
        if (q != null) send(q)
    }

    fun declineConsent() {
        needsConsent = false
        queuedText = null
    }

    // -------------------------------------------------------------- sending

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy || pending != null || !settings.enabled) return
        if (text.length > AiLimits.MAX_USER_CHARS) return
        if (settings.mode == AiMode.ONLINE && !settings.consented) {
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
        if (busy || pending != null || messages.lastOrNull()?.role != ChatRole.USER) return
        failure = null
        runAgent()
    }

    private fun runAgent() {
        busy = true
        val run = ++generation
        job = viewModelScope.launch {
            try {
                val step = if (settings.mode == AiMode.LOCAL) local.run(messages.last { it.role == ChatRole.USER }.text)
                    else orchestrator.run(messages.toList())
                if (run != generation) return@launch
                when (step) {
                    is AgentStep.Result -> add(ChatRole.RESULT, (if (step.outcome.ok) "✅ " else "⚠️ ") + step.outcome.text)
                    is AgentStep.Reply -> add(ChatRole.ASSISTANT, step.text)
                    is AgentStep.Confirm -> {
                        if (step.pending.lead.isNotBlank()) add(ChatRole.ASSISTANT, step.pending.lead)
                        pending = step.pending
                    }
                    is AgentStep.Failure -> {
                        failure = step.error
                        connection = when (step.error.kind) {
                            AiErrorKind.OFFLINE -> ConnectionState.OFFLINE
                            AiErrorKind.NOT_CONFIGURED -> ConnectionState.NOT_CONFIGURED
                            AiErrorKind.DISABLED -> ConnectionState.DISABLED
                            else -> connection
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (run == generation) add(ChatRole.ERROR, "دریافت پاسخ انجام نشد. هیچ عملیات تأییدنشده‌ای اجرا نشد.")
            } finally {
                if (run == generation) busy = false
            }
        }
    }

    fun confirmPending() {
        val p = pending ?: return
        if (busy || !settings.enabled) return
        pending = null
        busy = true
        executingAction = true
        viewModelScope.launch {
            try {
                val out = tools.execute(p.call)
                add(ChatRole.RESULT, (if (out.ok) "✅ " else "⚠️ ") + out.text)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                add(ChatRole.ERROR, "نتیجهٔ عملیات تأیید نشد؛ قبل از تکرار، سوابق را بررسی کنید.")
            } finally {
                executingAction = false
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
        if (executingAction) return
        generation++
        job?.cancel()
        busy = false
    }

    private fun add(role: ChatRole, text: String) {
        messages.add(ChatEntry(UUID.randomUUID().toString(), role, text, System.currentTimeMillis()))
        persist()
    }

    // ------------------------------------------------------------- history

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == ChatRole.USER }?.text?.take(40) ?: "گفتگو"
        val convo = Conversation(conversationId, title, createdAt, System.currentTimeMillis(), messages.toList(), settings.mode)
        viewModelScope.launch { conversations.save(convo) }
    }

    fun newChat() {
        if (executingAction) return
        stop()
        declineConsent()
        busy = false
        pending = null
        failure = null
        messages.clear()
        conversationId = UUID.randomUUID().toString()
        createdAt = System.currentTimeMillis()
    }

    fun loadHistory() {
        viewModelScope.launch { history = conversations.load().filter { it.mode == settings.mode }.sortedByDescending { it.updatedAt } }
    }

    fun openConversation(c: Conversation) {
        if (executingAction || c.mode != settings.mode) return
        stop()
        busy = false
        pending = null
        failure = null
        messages.clear()
        messages.addAll(c.messages)
        conversationId = c.id
        createdAt = c.createdAt
    }

    fun deleteConversation(c: Conversation) {
        viewModelScope.launch {
            conversations.delete(c.id)
            if (c.id == conversationId) newChat()
            history = conversations.load().filter { it.mode == settings.mode }.sortedByDescending { it.updatedAt }
        }
    }

    /** Wipes ALL chat history on this device. Financial and vehicle records are not touched. */
    fun clearAllHistory() {
        viewModelScope.launch {
            conversations.clearAll()
            history = emptyList()
            newChat()
        }
    }
}
