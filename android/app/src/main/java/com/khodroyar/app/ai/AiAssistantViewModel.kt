package com.khodroyar.app.ai

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.automation.AutomationRule
import com.khodroyar.app.automation.AutomationStore
import com.khodroyar.app.notifications.AgentAutomationScheduler
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ConnectionState { LOCAL, READY, UNKNOWN, CHECKING, ONLINE, OFFLINE, NOT_CONFIGURED, NEEDS_CONSENT, DISABLED, ERROR }

class AiAssistantViewModel @JvmOverloads constructor(
    app: Application, http: AiHttpTransport = OpenRouterHttp(), toolRunner: AgentToolRunner? = null,
) : AndroidViewModel(app) {
    private val settingsStore = AiSettingsStore(app)
    private val conversations = ConversationRepository(app)
    private val automationStore = AutomationStore.get(app)
    private val tools = toolRunner ?: AppAgentTools(app, AppRepository(app))
    private val keyStore = OpenRouterApiKeyStore(app)
    private val openRouter = OpenRouterClient(settingsStore::load, keyStore, http)
    private val managedModels = ManagedAIProvider(OpenRouterModelAdapter(openRouter), {
        settingsStore.load().let { s -> AISelectionSettings(s.enabled && s.mode == AiMode.OPENROUTER, s.consented,
            s.modelAccountScope, s.modelSelectionMode, AIModelKey(OpenRouterModelMetadata.PROVIDER, s.openRouterModel)) }
    }, AndroidModelEvaluationStore(app))
    private val agent = PersianToolAgent(managedModels, tools)
    private val local = LocalAssistant(tools, dialogue = LocalDialogueEngine(rules = { automationStore.rules.value }))

    var localContext by mutableStateOf(LocalDialogueContext())
        private set
    var automations by mutableStateOf<List<AutomationRule>>(automationStore.rules.value)
        private set
    val automationStorageError: Boolean get() = automationStore.storageError
    var navigation by mutableStateOf<AgentSection?>(null)
        private set
    fun consumeNavigation() { navigation = null }

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
    var hasOpenRouterKey by mutableStateOf(false)
        private set
    var savingSettings by mutableStateOf(false)
        private set
    var settingsError by mutableStateOf<String?>(null)
        private set

    var modelState by mutableStateOf(AIModelState())
        private set
    var streamingText by mutableStateOf("")
        private set

    private var conversationId = UUID.randomUUID().toString()
    private var createdAt = System.currentTimeMillis()
    private var queuedText: String? = null
    private var queuedTest = false
    private var job: Job? = null
    private var historySave: Job? = null
    private var historySaveConversationId: String? = null
    private var statusJob: Job? = null
    private var testJob: Job? = null
    private var generation = 0
    private var keyRevision = 0
    private var pendingFromAgent = false

    val activeModel: String get() = if (settings.mode == AiMode.LOCAL) "داخلی · بدون مدل/شبکه" else modelState.active?.let { "OpenRouter · ${it.id}" } ?: "در انتظار مدلِ مجاز و ارزیابی‌شده"

    init {
        viewModelScope.launch { automationStore.rules.collect { automations = it } }
        viewModelScope.launch { managedModels.state.collect { modelState = it; serverModel = it.active?.id.orEmpty() } }
        viewModelScope.launch {
            val revision = keyRevision
            val storedKey = withContext(Dispatchers.IO) {
                runCatching { keyStore.read()?.let(OpenRouterFreePolicy::normalizeKey) != null }.getOrDefault(false)
            }
            if (revision == keyRevision) hasOpenRouterKey = storedKey
            val last = conversations.load().filter { it.mode == settings.mode }.maxByOrNull { it.updatedAt }
            if (last != null && messages.isEmpty() && !busy) openConversation(last)
            refreshStatus(); AIModelCatalogScheduler.sync(app)
        }
    }

    /** No network probes on screen open; the first send/test always needs explicit account/data consent. */
    fun refreshStatus() {
        statusJob?.cancel()
        connection = when {
            !settings.enabled -> ConnectionState.DISABLED
            settings.mode == AiMode.LOCAL -> ConnectionState.LOCAL
            settings.mode != AiMode.OPENROUTER || !hasOpenRouterKey -> ConnectionState.NOT_CONFIGURED
            !settings.consented -> ConnectionState.NEEDS_CONSENT
            else -> ConnectionState.READY
        }
    }
    private fun applyStatus(s: AiProviderStatus) {
        serverModel = s.defaultModel
        connection = when {
            s.errorKind == AiErrorKind.NOT_CONFIGURED -> ConnectionState.NOT_CONFIGURED
            s.errorKind in setOf(AiErrorKind.OFFLINE, AiErrorKind.TIMEOUT) -> ConnectionState.OFFLINE
            s.errorKind != null -> ConnectionState.ERROR
            !s.reachable -> ConnectionState.OFFLINE
            !s.enabled || !s.configured -> ConnectionState.ERROR
            else -> ConnectionState.ONLINE
        }
    }
    fun testConnection() {
        testJob?.cancel()
        if (settings.mode == AiMode.LOCAL) {
            testMessage = "حالت داخلی و همهٔ ثبت‌های دستی بدون اینترنت یا مدل آفلاین کار می‌کنند. برای گفتگوی طبیعی و ایجنت چندمرحله‌ای OpenRouter رایگان را انتخاب کنید."
            refreshStatus(); return
        }
        if (!settings.enabled || !hasOpenRouterKey) {
            testMessage = "دستیار را فعال و کلید واقعی OpenRouter را در همین تنظیمات امن ذخیره کنید."; return
        }
        if (!settings.consented) { queuedTest = true; needsConsent = true; return }
        connection = ConnectionState.CHECKING
        testMessage = "در حال بررسی فهرست رسمی/Guardrail و آزمون‌های امن؛ فقط دادهٔ ساختگی و حداکثر دو مدل…"
        testJob = viewModelScope.launch {
            try {
                managedModels.refresh(runTests = true)
                val result = managedModels.state.value
                val ready = result.models.firstOrNull { it.model.key == result.active && it.approved }
                connection = if (ready != null) ConnectionState.ONLINE else ConnectionState.ERROR
                testMessage = if (ready != null) "مدل مجاز و رایگان با ۴ آزمون ساختگی قبول شد: ${ready.model.key.id}. این آزمون کیفیت همهٔ مکالمات را تضمین نمی‌کند."
                    else result.message ?: "مدلِ مجاز و قبول‌شده آماده نیست؛ جزئیات فهرست را بررسی کنید."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connection = ConnectionState.ERROR; testMessage = "بررسی کامل نشد؛ از مدل قدیمی/پولی استفاده نمی‌شود و کلید یا خطای خام نمایش داده نمی‌شود." }
        }
    }
    fun refreshModels() {
        if (busy || savingSettings || settings.mode != AiMode.OPENROUTER || !settings.consented || !hasOpenRouterKey) return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            try { managedModels.refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { testMessage = "فهرست تازه نشد؛ مدل/قیمتِ تأییدنشده استفاده نمی‌شود." }
        }
    }
    fun dismissModelNotice() = managedModels.dismissNotice()
    fun clearTestMessage() { testMessage = null }
    fun clearSettingsError() { settingsError = null }

    /** Key material never enters ordinary settings, history, SavedStateHandle or backups. */
    fun configure(new: AiSettings, apiKey: String, forgetKey: Boolean, afterSave: () -> Unit) {
        if (busy || executingAction || savingSettings) return
        settingsError = null
        if (new.mode !in setOf(AiMode.LOCAL, AiMode.OPENROUTER)) {
            settingsError = "فقط OpenRouter رایگانِ تأییدشده یا حالت داخلی مجاز است؛ اتصال پولی/قدیمی جایگزین نمی‌شود."; return
        }
        if (new.mode == AiMode.OPENROUTER && new.modelSelectionMode == AISelectionMode.MANUAL &&
            modelState.models.none { it.approved && it.model.key.id == new.openRouterModel }) {
            settingsError = "انتخاب دستی فقط از مدل‌های مجاز، رایگان و قبول‌شدهٔ همین حساب ممکن است؛ ابتدا آزمون امن را اجرا کنید."; return
        }
        val typed = apiKey.trim()
        if (typed.isNotEmpty() && OpenRouterFreePolicy.normalizeKey(typed) == null) {
            settingsError = "قالب کلید OpenRouter معتبر نیست؛ کلید واقعی حساب را فقط همین‌جا وارد کنید، نه در گفتگو."; return
        }
        val changed = typed.isNotEmpty() || forgetKey
        statusJob?.cancel(); testJob?.cancel(); stop()
        if (changed) {
            keyRevision++
            settingsStore.save(settings.copy(consented = false)); settings = settingsStore.load(); newChat()
        }
        savingSettings = true
        viewModelScope.launch {
            try {
                hasOpenRouterKey = withContext(Dispatchers.IO) {
                    if (changed) settingsStore.revokeOpenRouterConsent()
                    if (typed.isNotEmpty()) keyStore.save(typed) else if (forgetKey) keyStore.clear()
                    keyStore.read()?.let(OpenRouterFreePolicy::normalizeKey) != null
                }
                val target = if (changed) new.copy(modelAccountScope = UUID.randomUUID().toString(), modelSelectionMode = AISelectionMode.AUTOMATIC) else new
                saveSettings(target, forceNewConsent = changed)
                afterSave()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { settingsError = "ذخیره/خواندن امن کلید انجام نشد. هیچ متن ساده یا ذخیرهٔ جایگزین نداریم؛ دوباره تلاش کنید." }
            finally { savingSettings = false }
        }
    }
    fun saveSettings(new: AiSettings, forceNewConsent: Boolean = false) {
        if (executingAction) return
        if (new.mode !in setOf(AiMode.LOCAL, AiMode.OPENROUTER)) return
        val newAccount = new.mode != settings.mode || forceNewConsent
        val changed = newAccount || new.openRouterModel != settings.openRouterModel || new.modelSelectionMode != settings.modelSelectionMode
        if (changed) newChat() else if (!new.enabled) { stop(); pending = null; local.dialogue.reset(); localContext = local.dialogue.context }
        testJob?.cancel(); statusJob?.cancel()
        settingsStore.save(new.copy(consented = !newAccount && new.consented))
        settings = settingsStore.load(); managedModels.invalidate(); AIModelCatalogScheduler.sync(getApplication())
        needsConsent = false; queuedText = null; queuedTest = false; testMessage = null; failure = null
        refreshStatus()
    }
    /** Explicit opt-in to the new provider; no network/fallback is triggered by this action. */
    fun chooseOpenRouter() { if (!busy && !savingSettings) saveSettings(settings.copy(mode = AiMode.OPENROUTER)) }

    fun acceptConsent() {
        val text = queuedText
        val test = queuedTest
        settingsStore.save(settings.copy(consented = true))
        settings = settingsStore.load(); AIModelCatalogScheduler.sync(getApplication())
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
        if (text.isEmpty() || busy || savingSettings || !settings.enabled || text.length > (if (settings.mode == AiMode.LOCAL) AiLimits.MAX_USER_CHARS else PersianAgentLimits.USER_CHARS)) return
        if (OpenRouterFreePolicy.containsCredential(text)) {
            failure = AiResult.Failure(AiErrorKind.BAD_REQUEST, "این متن شبیه کلید API است؛ در تاریخچه ذخیره یا ارسال نشد. کلید را فقط در تنظیمات امن وارد کنید.")
            return
        }
        if (pending != null) {
            if (settings.mode != AiMode.LOCAL) return
            messages.add(ChatEntry(UUID.randomUUID().toString(), ChatRole.USER, text, System.currentTimeMillis()))
            persist()
            val grouped = pending!!.call.spec.name.startsWith("bulk_")
            val baseDecision = LocalApprovalInput.classify(text, false)
            val decision = if (grouped || pending!!.destructive) {
                if (baseDecision == LocalApprovalInput.Decision.CONFIRM) LocalApprovalInput.Decision.REQUIRE_BUTTON else baseDecision
            } else LocalApprovalInput.classify(text, pending!!.destructive)
            when (decision) {
                LocalApprovalInput.Decision.CONFIRM -> { confirmPending(); return }
                LocalApprovalInput.Decision.CANCEL -> { cancelPending(); return }
                LocalApprovalInput.Decision.REQUIRE_BUTTON -> {
                    add(ChatRole.ASSISTANT, "عملیات گروهی یا حذف فقط با دکمهٔ تأیید همین خلاصه انجام می‌شود؛ برای انصراف «لغو» بگو.")
                    return
                }
                LocalApprovalInput.Decision.REVISE -> Unit
            }
            // A correction invalidates the old approval card BEFORE it is parsed.
            pending = null
            runAgent()
            return
        }
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
        if (busy || savingSettings || pending != null || !settings.enabled) return
        if (settings.mode != AiMode.LOCAL && !settings.consented) return
        if (settings.mode == AiMode.LOCAL && messages.lastOrNull()?.role != ChatRole.USER) return
        failure = null
        runAgent(retrying = settings.mode == AiMode.OPENROUTER && agent.canResume)
    }
    private fun textCallback(run: Int): (String) -> Unit = { text ->
        viewModelScope.launch { if (run == generation && busy) streamingText = OpenRouterFreePolicy.redactCredentials(text) }
    }
    private suspend fun toolResult(name: String, out: ToolOutcome) {
        streamingText = ""
        add(ChatRole.RESULT, "ابزار $name · ${if (out.ok) "تأیید شد" else "ناموفق"}\n${out.text}")
        if (out.ok && out.section != null) navigation = out.section
    }
    private fun applyStep(step: AgentStep) {
        streamingText = ""
        when (step) {
            is AgentStep.Reply -> add(ChatRole.ASSISTANT, step.text)
            is AgentStep.Result -> { add(ChatRole.RESULT, (if (step.outcome.ok) "" else "⚠️ ") + step.outcome.text); if (step.outcome.ok) navigation = step.outcome.section }
            is AgentStep.Confirm -> { pendingFromAgent = settings.mode == AiMode.OPENROUTER; if (step.pending.lead.isNotBlank()) add(ChatRole.ASSISTANT, step.pending.lead); pending = step.pending }
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
    }
    private fun runAgent(retrying: Boolean = false) {
        busy = true; streamingText = ""
        val run = ++generation; val mode = settings.mode; val snapshot = messages.toList()
        job = viewModelScope.launch {
            try {
                val step = if (mode == AiMode.LOCAL) local.run(snapshot.last().text)
                    else if (retrying) agent.retry(textCallback(run), ::toolResult)
                    else agent.start(snapshot, textCallback(run), ::toolResult)
                coroutineContext.ensureActive()
                if (run != generation) return@launch
                if (mode == AiMode.LOCAL) localContext = local.dialogue.context
                if (mode == AiMode.OPENROUTER && step !is AgentStep.Failure) connection = ConnectionState.ONLINE
                applyStep(step)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (run == generation) failure = AiResult.Failure(AiErrorKind.UNKNOWN, "دریافت پاسخ کامل نشد؛ عملیات تأییدنشده اجرا نمی‌شود.") }
            finally { if (run == generation) { busy = false; streamingText = "" } }
        }
    }
    fun confirmPending() {
        val p = pending ?: return
        if (busy || savingSettings || (!settings.enabled && p.call.spec.name !in setOf("set_automation_enabled", "remove_automation_rule"))) return
        pending = null; busy = true; executingAction = true
        val run = ++generation
        job = viewModelScope.launch {
            try {
                if (settings.mode == AiMode.OPENROUTER && pendingFromAgent) {
                    val step = agent.confirm(p, textCallback(run)) { name, out ->
                        toolResult(name, out); executingAction = false // The verified DB phase is finished; streaming can now be stopped.
                    }
                    if (run == generation) applyStep(step)
                } else {
                    val out = tools.execute(p.call)
                    add(ChatRole.RESULT, (if (out.ok) "✅ " else "⚠️ ") + out.text)
                    local.dialogue.completed(p.call, out.ok); localContext = local.dialogue.context
                    if (out.ok) navigation = out.section else pending = p
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                add(ChatRole.ERROR, "نتیجهٔ عملیات تأیید نشد؛ سوابق را بررسی کنید. برای جلوگیری از ثبت تکراری، خودکار دوباره اجرا نمی‌شود.")
            } finally { if (run == generation) { busy = false; executingAction = false; streamingText = "" } }
        }
    }

    fun cancelPending() {
        if (pending == null) return
        pending = null
        agent.reset()
        local.dialogue.discardDraft()
        localContext = local.dialogue.context
        add(ChatRole.RESULT, "این پیش‌نمایش و مراحلِ تأییدنشده لغو شد. نتایجِ قبلاً تأییدشده تغییری نمی‌کنند.")
    }

    fun stop() {
        // Once a database operation is approved, let its write + verification finish together.
        if (executingAction) return
        generation++
        job?.cancel()
        agent.reset()
        streamingText = ""
        busy = false
    }

    private fun add(role: ChatRole, text: String) {
        messages.add(ChatEntry(UUID.randomUUID().toString(), role, OpenRouterFreePolicy.redactCredentials(text), System.currentTimeMillis()))
        persist()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == ChatRole.USER }?.text?.take(40) ?: "گفتگو"
        val convo = Conversation(conversationId, title, createdAt, System.currentTimeMillis(), messages.toList(), settings.mode)
        historySave?.cancel()
        historySaveConversationId = convo.id
        historySave = viewModelScope.launch { conversations.save(convo) }
    }

    fun newChat() {
        if (executingAction) return
        stop()
        local.dialogue.reset()
        localContext = local.dialogue.context
        navigation = null
        pending = null
        failure = null
        declineConsent()
        messages.clear()
        conversationId = UUID.randomUUID().toString()
        createdAt = System.currentTimeMillis()
    }

    /** Device-local management UI: no model/network round-trip, still uses the approval gate. */
    fun setAutomation(id: String, enabled: Boolean) = prepareAutomation("set_automation_enabled", JSONObject().put("id", id).put("state", if (enabled) "on" else "off"))
    fun removeAutomation(id: String) = prepareAutomation("remove_automation_rule", JSONObject().put("id", id))

    private fun prepareAutomation(name: String, args: JSONObject) {
        if (busy || savingSettings || pending != null) return
        busy = true
        val run = ++generation
        job = viewModelScope.launch {
            try {
                val validation = AgentActionValidator.validate(name, args)
                if (validation is ValidationResult.Ok) {
                    val step = AgentApprovalGate(tools).prepare(validation.call)
                    if (run == generation) when (step) {
                        is AgentStep.Confirm -> { pendingFromAgent = false; pending = step.pending }
                        is AgentStep.Reply -> add(ChatRole.ASSISTANT, step.text)
                        else -> Unit
                    }
                }
            } finally { if (run == generation) busy = false }
        }
    }

    fun editAutomation(id: String) {
        if (busy || pending != null) return
        if (settings.mode != AiMode.LOCAL) useLocalMode()
        send("مبلغ قانون ${id.take(8)} را تغییر بده")
    }

    fun loadHistory() {
        viewModelScope.launch { history = conversations.load().sortedByDescending { it.updatedAt } }
    }

    fun openConversation(c: Conversation) {
        if (executingAction) return
        newChat()
        messages.addAll(c.messages)
        if (c.mode != settings.mode) {
            settingsStore.save(settings.copy(consented = false)); settings = settingsStore.load(); refreshStatus()
            // Viewing old history never changes providers or authorises a request. New sends fork it.
        } else { conversationId = c.id; createdAt = c.createdAt }
    }

    fun deleteConversation(c: Conversation) {
        if (executingAction) return
        if (c.id == historySaveConversationId) historySave?.cancel()
        viewModelScope.launch {
            conversations.delete(c.id)
            if (c.id == conversationId) newChat()
            history = conversations.load().sortedByDescending { it.updatedAt }
        }
    }

    /** Wipes chat history only; never financial/vehicle records. */
    fun clearAllHistory() {
        if (executingAction) return
        historySave?.cancel()
        newChat()
        viewModelScope.launch {
            conversations.clearAll()
            history = emptyList()
            newChat()
        }
    }
}
