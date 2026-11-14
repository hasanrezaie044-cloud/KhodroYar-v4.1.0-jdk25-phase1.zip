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
    private val usageStore = AndroidAIUsageStore(app)
    private val requestRuntime = AIRequestRuntime(http, { settingsStore.load().usage }, { settingsStore.load().modelAccountScope }, usageStore)
    private fun keyStore(mode: AiMode = settingsStore.load().mode) = ProviderApiKeyStore(getApplication(), mode)
    private val openRouter = OpenRouterClient(settingsStore::load, OpenRouterApiKeyStore(app), http, runtime = requestRuntime)
    private val selectedAdapter = SelectedAIModelAdapter({ settingsStore.load().mode }, mapOf(
        AiMode.OPENROUTER to OpenRouterModelAdapter(openRouter),
        *listOf(AiMode.GEMINI, AiMode.GROQ, AiMode.CLOUDFLARE).map { mode ->
            mode to DirectModelAdapter(mode, settingsStore::load, ProviderApiKeyStore(app, mode), requestRuntime)
        }.toTypedArray()))
    private val managedModels = ManagedAIProvider(selectedAdapter, {
        settingsStore.load().let { s -> AISelectionSettings(s.enabled && s.mode != AiMode.LOCAL, s.consented,
            s.modelAccountScope, s.modelSelectionMode, AIModelKey(AIProviderRegistry.get(s.mode).id, s.modelFor()),
            s.allowModelFallback && s.mode == AiMode.OPENROUTER, s.effectiveUsage()) }
    }, AndroidModelEvaluationStore(app))
    private val agent = PersianToolAgent(managedModels, tools) { settingsStore.load().effectiveUsage() }
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
    var diagnostics by mutableStateOf<List<AIRequestDiagnostic>>(emptyList())
        private set
    var usageLedger by mutableStateOf(usageStore.read())
        private set
    val networkBusy: Boolean get() = busy || savingSettings || testJob?.isActive == true || statusJob?.isActive == true || modelState.checking
    var streamingText by mutableStateOf("")
        private set

    private var conversationId = UUID.randomUUID().toString()
    private var createdAt = System.currentTimeMillis()
    private var queuedText: String? = null
    private var queuedTest = false
    private var queuedManualTest = false
    private var foreignHistory = false
    private var job: Job? = null
    private var historySave: Job? = null
    private var historySaveConversationId: String? = null
    private var statusJob: Job? = null
    private var testJob: Job? = null
    private var generation = 0
    private var keyRevision = 0
    /** Ephemeral only, like the HTTPS key; never saveable state/history/diagnostics. Covers prefixless provider tokens. */
    private var selectedSecret: String? = null
    private fun safeText(value: String): String = AiTextSafety.redact(selectedSecret?.let { value.replace(it, "[کلید حذف شد]") } ?: value)
    private var pendingFromAgent = false

    val activeModel: String get() = if (settings.mode == AiMode.LOCAL) "داخلی · بدون مدل/شبکه" else modelState.active?.let { "${AIProviderRegistry.get(settings.mode).title} · ${it.id}" } ?: "مدل را در تنظیمات انتخاب کنید"

    init {
        viewModelScope.launch { automationStore.rules.collect { automations = it } }
        viewModelScope.launch { managedModels.state.collect { modelState = it; serverModel = it.active?.id.orEmpty() } }
        viewModelScope.launch { requestRuntime.diagnostics.collect { diagnostics = it; usageLedger = usageStore.read() } }
        viewModelScope.launch {
            val revision = keyRevision
            val storedKey = withContext(Dispatchers.IO) {
                runCatching { keyStore().read()?.let { AIProviderRegistry.normalizeKey(settingsStore.load().mode, it) } }.getOrNull()
            }
            if (revision == keyRevision) { selectedSecret = storedKey; hasOpenRouterKey = storedKey != null }
            val last = conversations.load().filter { it.mode == settings.mode && it.accountScope == settings.modelAccountScope }.maxByOrNull { it.updatedAt }
            if (settings.usage.saveHistory && last != null && messages.isEmpty() && !busy) openConversation(last)
            refreshStatus(); AIModelCatalogScheduler.sync(app)
        }
    }

    /** No network probes on screen open; the first send/test always needs explicit account/data consent. */
    fun refreshStatus() {
        statusJob?.cancel()
        connection = when {
            !settings.enabled -> ConnectionState.DISABLED
            settings.mode == AiMode.LOCAL -> ConnectionState.LOCAL
            !hasOpenRouterKey || AIProviderRegistry.barrier(settings, System.currentTimeMillis()) != null -> ConnectionState.NOT_CONFIGURED
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
    /** Connection test is metadata-only: zero model/evaluation POSTs. */
    fun testConnection() {
        if (networkBusy) return
        if (settings.mode == AiMode.LOCAL) { testMessage = "حالت داخلی بدون شبکه است."; refreshStatus(); return }
        if (!settings.enabled || !hasOpenRouterKey) { testMessage = "دستیار و کلید همین ارائه‌دهنده را در تنظیمات فعال کنید."; return }
        if (!settings.consented) { queuedText = null; queuedManualTest = false; queuedTest = true; needsConsent = true; return }
        testMessage = "بررسی کلید و فهرست رسمی؛ بدون تولید متن یا ارزیابی…"
        connection = ConnectionState.CHECKING
        testJob = viewModelScope.launch {
            try {
                managedModels.refresh()
                val ready = managedModels.state.value.models.any { it.approved }
                connection = if (ready) ConnectionState.ONLINE else ConnectionState.ERROR
                testMessage = if (ready) "کلید/فهرست رسمی پاسخ داد؛ ۰ درخواست تولید متن و ۰ آزمون مدل. این بررسی کیفیت یا inference زنده را اثبات نمی‌کند."
                    else managedModels.state.value.message ?: "مدل/مجوز/طرح رایگان تأیید نشد؛ fallback انجام نمی‌شود."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connection = ConnectionState.ERROR; testMessage = "بررسی اتصال کامل نشد؛ خطای خام/کلید نمایش داده نمی‌شود." }
        }
    }
    /** Called only after the settings' separate usage-warning confirmation. One model, <=4 dummy POSTs. */
    fun testModels() {
        if (networkBusy || settings.mode == AiMode.LOCAL) return
        if (settings.modelSelectionMode == AISelectionMode.MANUAL && settings.modelFor().isBlank()) { testMessage = "ابتدا یک مدل مشخص را پس از بررسی فراداده انتخاب و ذخیره کنید."; return }
        if (settings.usage.bounded().outputTokens < 384) { testMessage = "آزمون اختیاری به سقف خروجی حداقل۳۸۴ نیاز دارد؛ سقف انتخابی شما خودکار بالا نمی‌رود."; return }
        if (!settings.enabled || !hasOpenRouterKey) { testMessage = "ابتدا کلید و مدل همین اتصال را ذخیره کنید."; return }
        if (!settings.consented) { queuedText = null; queuedTest = false; queuedManualTest = true; needsConsent = true; return }
        connection = ConnectionState.CHECKING
        testMessage = "آزمون اختیاری یک مدل؛ حداکثر۴ درخواست ساختگی، خروجی هر درخواست حداکثر۳۸۴توکن؛ بدون ابزار/رکورد واقعی…"
        testJob = viewModelScope.launch {
            try {
                managedModels.refresh(runTests = true)
                val result = managedModels.state.value
                val checked = result.models.firstOrNull { it.model.key == result.active }?.evaluation
                connection = if (result.active != null) ConnectionState.ONLINE else ConnectionState.ERROR
                testMessage = checked?.let { "نتیجهٔ آزمون اختیاری: ${if (it.passed) "قبول" else "قبول نشد"}؛ ${it.explanation}" }
                    ?: result.message ?: "آزمون کامل نشد؛ جزئیات مصرف و خطا را بررسی کنید."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { connection = ConnectionState.ERROR; testMessage = "آزمون کامل نشد؛ تکرار خودکار نداریم." }
        }
    }
    fun refreshModels() {
        if (networkBusy || settings.mode == AiMode.LOCAL || !settings.consented || !hasOpenRouterKey) return
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
        if (networkBusy || executingAction) return
        settingsError = null
        if (new.mode != AiMode.LOCAL && new.mode !in AIProviderRegistry.providers.map { it.mode }) return
        val typed = apiKey.trim()
        if (typed.isNotEmpty() && AIProviderRegistry.normalizeKey(new.mode, typed) == null) {
            settingsError = "قالب کلید این ارائه‌دهنده معتبر نیست؛ فقط در همین تنظیمات امن وارد کنید."; return
        }
        val changed = typed.isNotEmpty() || forgetKey
        keyRevision++
        statusJob?.cancel(); testJob?.cancel(); stop()
        if (changed) {
            settingsStore.save(settings.copy(consented = false)); settings = settingsStore.load(); newChat()
        }
        savingSettings = true
        viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) {
                    if (changed) settingsStore.revokeOpenRouterConsent()
                    if (typed.isNotEmpty()) keyStore(new.mode).save(typed) else if (forgetKey) keyStore(new.mode).clear()
                    keyStore(new.mode).read()?.let { AIProviderRegistry.normalizeKey(new.mode, it) }
                }
                selectedSecret = stored; hasOpenRouterKey = stored != null
                val target = if (changed) new.copy(modelAccountScope = UUID.randomUUID().toString()) else new
                saveSettings(target, forceNewConsent = changed)
                savingSettings = false
                afterSave()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { settingsError = "ذخیره/خواندن امن کلید انجام نشد. هیچ متن ساده یا ذخیرهٔ جایگزین نداریم؛ دوباره تلاش کنید." }
            finally { savingSettings = false }
        }
    }
    fun saveSettings(new: AiSettings, forceNewConsent: Boolean = false) {
        if (executingAction) return
        if (new.mode != AiMode.LOCAL && new.mode !in AIProviderRegistry.providers.map { it.mode }) return
        val newAccount = new.mode != settings.mode || forceNewConsent || new.cloudflareAccountId != settings.cloudflareAccountId
        val changed = newAccount || new.modelFor() != settings.modelFor() || new.modelSelectionMode != settings.modelSelectionMode || new.usage != settings.usage
        if (!new.usage.saveHistory) historySave?.cancel()
        if (changed) newChat() else if (!new.enabled) { stop(); pending = null; local.dialogue.reset(); localContext = local.dialogue.context }
        testJob?.cancel(); statusJob?.cancel()
        settingsStore.save(new.copy(consented = !newAccount && new.consented, modelAccountScope = if (newAccount && !forceNewConsent) UUID.randomUUID().toString() else new.modelAccountScope))
        settings = settingsStore.load(); managedModels.invalidate(); AIModelCatalogScheduler.sync(getApplication())
        needsConsent = false; queuedText = null; queuedTest = false; queuedManualTest = false; testMessage = null; failure = null
        refreshStatus()
    }
    /** Explicit opt-in to the new provider; no network/fallback is triggered by this action. */
    fun chooseOpenRouter() { if (!busy && !savingSettings) saveSettings(settings.copy(mode = AiMode.OPENROUTER)) }

    fun acceptConsent() {
        val text = queuedText
        val test = queuedTest
        val manual = queuedManualTest
        settingsStore.save(settings.copy(consented = true))
        settings = settingsStore.load(); AIModelCatalogScheduler.sync(getApplication())
        needsConsent = false
        queuedText = null
        queuedTest = false
        queuedManualTest = false
        if (test) testConnection() else if (manual) testModels() else { refreshStatus(); if (text != null) send(text) }
    }

    fun declineConsent() {
        needsConsent = false
        queuedText = null
        queuedTest = false
        queuedManualTest = false
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
        if (text.isEmpty() || networkBusy || !settings.enabled || text.length > (if (settings.mode == AiMode.LOCAL) AiLimits.MAX_USER_CHARS else PersianAgentLimits.USER_CHARS)) return
        if (OpenRouterFreePolicy.containsCredential(text) || selectedSecret?.let { it in text } == true) {
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
        if (foreignHistory) { newChat(); foreignHistory = false }
        if (settings.mode != AiMode.LOCAL && !settings.consented) {
            queuedTest = false; queuedManualTest = false
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
        if (networkBusy || pending != null || !settings.enabled || failure?.kind in ProviderFailurePolicy.NEVER_RETRY) return
        if (settings.mode != AiMode.LOCAL && !settings.consented) return
        if (settings.mode == AiMode.LOCAL && messages.lastOrNull()?.role != ChatRole.USER) return
        failure = null
        runAgent(retrying = settings.mode != AiMode.LOCAL && agent.canResume)
    }
    private fun textCallback(run: Int): (String) -> Unit = { text ->
        viewModelScope.launch { if (run == generation && busy) streamingText = safeText(text) }
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
            is AgentStep.Confirm -> { pendingFromAgent = settings.mode != AiMode.LOCAL; if (step.pending.lead.isNotBlank()) add(ChatRole.ASSISTANT, step.pending.lead); pending = step.pending }
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
                if (mode != AiMode.LOCAL && step !is AgentStep.Failure) connection = ConnectionState.ONLINE
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
                if (settings.mode != AiMode.LOCAL && pendingFromAgent) {
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
        messages.add(ChatEntry(UUID.randomUUID().toString(), role, safeText(text), System.currentTimeMillis()))
        persist()
    }

    private fun persist() {
        if (messages.isEmpty() || !settings.usage.saveHistory) return
        val title = messages.firstOrNull { it.role == ChatRole.USER }?.text?.take(40) ?: "گفتگو"
        val convo = Conversation(conversationId, title, createdAt, System.currentTimeMillis(), messages.toList(), settings.mode, settings.modelAccountScope)
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
        messages.clear(); foreignHistory = false
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
        messages.addAll(c.messages.map { it.copy(text = safeText(it.text)) })
        if (c.mode != settings.mode || c.accountScope != settings.modelAccountScope) {
            foreignHistory = true
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
