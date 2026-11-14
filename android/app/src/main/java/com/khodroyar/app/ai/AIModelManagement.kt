package com.khodroyar.app.ai

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provider-neutral model identity. Never synthesize a variant or substitute a display name. */
data class AIModelKey(val provider: String, val id: String)
enum class AISelectionMode { AUTOMATIC, MANUAL }
enum class AIModelAuthorization { AUTHORIZED, NOT_AUTHORIZED, UNKNOWN }
data class AIModelCapability(val toolCalling: Boolean, val contextLength: Int, val textOutput: Boolean = true)
data class AIModelPricing(val input: BigDecimal?, val output: BigDecimal?, val allFieldsZero: Boolean) {
    val verifiedFree: Boolean get() = input?.signum() == 0 && output?.signum() == 0 && allFieldsZero
}
data class AIModel(
    val key: AIModelKey, val name: String, val providerName: String, val publisher: String,
    val pricing: AIModelPricing, val capability: AIModelCapability,
    val authorization: AIModelAuthorization, val checkedAt: Long, val fingerprint: String,
    val pinnedIdentity: Boolean = true, val deprecated: Boolean = false,
)
data class AIModelAvailability(
    val available: Boolean, val checkedAt: Long, val providers: List<String> = emptyList(),
    val uptime: Double = 0.0, val explanation: String = "", val errorKind: AiErrorKind? = null,
)
sealed interface AIModelDiscovery {
    data class Found(val models: List<AIModel>, val checkedAt: Long) : AIModelDiscovery
    data class Failed(val error: AiResult.Failure) : AIModelDiscovery
}
interface AIModelCatalog {
    suspend fun discover(force: Boolean = false): AIModelDiscovery
    suspend fun availability(model: AIModel, force: Boolean = false): AIModelAvailability
}
/** No HTTP, JSON wire format, Android Context, database or real tool executor crosses this boundary. */
data class AIModelRequest(
    val messages: List<FunctionMessage>, val tools: List<ToolSpec>,
    val maxOutputTokens: Int = PersianAgentLimits.OUTPUT_TOKENS, val stream: Boolean = true,
)
interface AIModelAdapter : AIModelCatalog {
    val providerId: String
    suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit = {}): FunctionResult
    fun invalidate()
}
data class AISelectionSettings(
    val enabled: Boolean, val consented: Boolean, val scope: String,
    val mode: AISelectionMode = AISelectionMode.AUTOMATIC, val manual: AIModelKey? = null,
)
data class AIModelTestCase(val name: String, val passed: Boolean)
data class AIModelEvaluation(
    val key: AIModelKey, val fingerprint: String, val checkedAt: Long,
    val cases: List<AIModelTestCase>, val score: Int, val suiteVersion: Int = SafePersianModelEvaluator.VERSION,
    val explanation: String = "", val deferred: Boolean = false, val errorKind: AiErrorKind? = null,
) {
    val passed: Boolean get() = !deferred && errorKind == null && cases.size == 4 && cases.all { it.passed } && score in 80..100 && suiteVersion == SafePersianModelEvaluator.VERSION
}
/** Stores only model IDs, scores, booleans and quotas. NEVER prompts, replies, records or credentials. */
interface AIModelEvaluationStore {
    fun load(scope: String): List<AIModelEvaluation>
    fun save(scope: String, result: AIModelEvaluation)
    fun active(scope: String): AIModelKey? = null
    fun setActive(scope: String, key: AIModelKey?) {}
    /** Reserve BEFORE each dummy inference, including failures; restart cannot reset the budget. */
    fun reserve(scope: String, now: Long, automatic: Boolean): Boolean
}
interface AIModelEvaluator {
    suspend fun evaluate(model: AIModel, adapter: AIModelAdapter, reserve: () -> Boolean): AIModelEvaluation
}
object AIRequestPolicy {
    const val METADATA_TTL_MS = 60_000L
    const val EVALUATION_TTL_MS = 7 * 24 * 60 * 60_000L
    fun fresh(at: Long, now: Long, ttl: Long) = at > 0 && now >= at && now - at < ttl
    fun eligibleMetadata(model: AIModel, now: Long): Boolean = model.pricing.verifiedFree && model.capability.toolCalling &&
        model.capability.textOutput && model.capability.contextLength >= 32768 && model.pinnedIdentity && !model.deprecated &&
        model.authorization == AIModelAuthorization.AUTHORIZED && fresh(model.checkedAt, now, METADATA_TTL_MS)
    fun approved(model: AIModel, result: AIModelEvaluation?, now: Long): Boolean = eligibleMetadata(model, now) &&
        result != null && result.key == model.key && result.fingerprint == model.fingerprint && result.passed && fresh(result.checkedAt, now, EVALUATION_TTL_MS)
}
object AIModelSelector {
    /** Quality and actual tool/Persian tests first, health next, context last. Latency is not a quality proxy. */
    fun ranked(models: List<AIModel>, evaluations: List<AIModelEvaluation>, health: Map<AIModelKey, AIModelAvailability>, now: Long): List<AIModel> {
        val results = evaluations.associateBy { it.key }
        return models.filter { AIRequestPolicy.approved(it, results[it.key], now) }
            .sortedWith(compareByDescending<AIModel> { results[it.key]?.score ?: 0 }
                .thenByDescending { health[it.key]?.uptime ?: 0.0 }.thenByDescending { it.capability.contextLength }.thenBy { it.key.id })
    }
}
data class AIModelView(val model: AIModel, val evaluation: AIModelEvaluation?, val availability: AIModelAvailability?, val approved: Boolean, val reason: String) {
    /** UI and manual save must not turn an old approved snapshot into a new authorization. */
    fun readyAt(now: Long): Boolean = approved && AIRequestPolicy.approved(model, evaluation, now) && availability?.let {
        it.available && AIRequestPolicy.fresh(it.checkedAt, now, AIRequestPolicy.METADATA_TTL_MS)
    } == true
}
data class AIModelState(
    val models: List<AIModelView> = emptyList(), val active: AIModelKey? = null, val checking: Boolean = false,
    val checkedAt: Long = 0, val message: String? = null, val notice: String? = null,
)

/**
 * The agent sees only FunctionCallingProvider. This manager selects independently of adapters and
 * NEVER executes tools. Failover retries inference only, from the same transcript, at most once.
 */
class ManagedAIProvider(
    private val adapter: AIModelAdapter, private val preferences: () -> AISelectionSettings,
    private val store: AIModelEvaluationStore, private val evaluator: AIModelEvaluator = SafePersianModelEvaluator(),
    private val clock: () -> Long = System::currentTimeMillis,
) : FunctionCallingProvider {
    private val lock = Mutex()
    private val invalidations = java.util.concurrent.atomic.AtomicLong()
    private var operationRevision = 0L
    private val accountErrors = setOf(AiErrorKind.RATE_LIMITED, AiErrorKind.UNAUTHORIZED, AiErrorKind.CREDIT_EXHAUSTED,
        AiErrorKind.OFFLINE, AiErrorKind.DISABLED, AiErrorKind.NOT_CONFIGURED)
    private val mutable = MutableStateFlow(AIModelState())
    val state = mutable.asStateFlow()
    private var scope = ""
    private var models = emptyList<AIModel>()
    private var evaluations = emptyList<AIModelEvaluation>()
    private val health = mutableMapOf<AIModelKey, AIModelAvailability>()
    private val healthMonitor = AIModelHealthMonitor()
    private val blocked = mutableMapOf<AIModelKey, Long>()
    private var lastSelection: AIModelKey? = null
    private var catalogFailure: AiResult.Failure? = null
    private var evaluationStop: AiResult.Failure? = null
    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }
    fun invalidate() { invalidations.incrementAndGet(); adapter.invalidate(); mutable.value = AIModelState(); scope = "" }
    private fun account(): AISelectionSettings? {
        operationRevision = invalidations.get()
        val p = preferences()
        if (!p.enabled || !p.consented) {
            models = emptyList(); adapter.invalidate(); scope = ""; mutable.value = AIModelState()
            return null
        }
        if (scope != p.scope) {
            scope = p.scope; models = emptyList(); health.clear(); healthMonitor.reset(); blocked.clear(); lastSelection = store.active(scope)
            evaluations = store.load(scope); catalogFailure = null; evaluationStop = null; adapter.invalidate(); mutable.value = AIModelState()
        }
        return p
    }
    private fun stillAuthorized(p: AISelectionSettings) = operationRevision == invalidations.get() && preferences() == p
    private fun publish(message: String? = null) {
        if (operationRevision != invalidations.get()) return
        val now = clock()
        val results = evaluations.associateBy { it.key }
        mutable.value = mutable.value.copy(models = models.map { model ->
            val result = results[model.key]; val h = health[model.key]
            val reason = when {
                !model.pricing.verifiedFree -> "قیمت صفر تأیید نشده؛ هرگز درخواست نمی‌شود"
                model.authorization != AIModelAuthorization.AUTHORIZED -> "کشف شده، اما برای این کلید/Guardrail مجاز نیست؛ ابتدا در OpenRouter اجازه بدهید"
                !model.pinnedIdentity -> "مسیریاب/شناسهٔ پویا؛ برای ارزیابی قابل اتکا یک مدل مشخص لازم است"
                model.deprecated -> "مدل حذف یا منسوخ شده است"
                !model.capability.toolCalling -> "پشتیبانی ابزار کافی نیست"
                model.capability.contextLength < 32768 || !model.capability.textOutput -> "ظرفیت/خروجی مناسب ایجنت نیست"
                !AIRequestPolicy.fresh(model.checkedAt, now, AIRequestPolicy.METADATA_TTL_MS) -> "بررسی قیمت و دسترسی باید تازه شود"
                (blocked[model.key] ?: 0) > now -> "موقتاً نامعتبر/در دسترس نیست؛ بدون دورزدن محدودیت"
                h?.available == false -> h.explanation.ifBlank { "ارائه‌دهندهٔ رایگانِ فعال تأیید نشد" }
                result == null -> "در انتظار آزمون امن با دادهٔ ساختگی"
                !result.passed -> result.explanation.ifBlank { "آزمون‌های لازم قبول نشد" }
                result.fingerprint != model.fingerprint || !AIRequestPolicy.fresh(result.checkedAt, now, AIRequestPolicy.EVALUATION_TTL_MS) -> "ارزیابی منقضی است؛ آزمون دوباره لازم است"
                else -> "قبول؛ قیمت/ارائه‌دهنده پیش از استفاده دوباره بررسی می‌شود"
            }
            val approved = AIRequestPolicy.approved(model, result, now) && h?.available == true &&
                AIRequestPolicy.fresh(h.checkedAt, now, AIRequestPolicy.METADATA_TTL_MS) && (blocked[model.key] ?: 0) <= now
            AIModelView(model, result, h, approved, reason)
        }, message = message)
    }
    private fun ranked(): List<AIModel> = AIModelSelector.ranked(models, evaluations, health, clock())
        .filter { (blocked[it.key] ?: 0) <= clock() }
    private fun candidates(p: AISelectionSettings): List<AIModel> = ranked().let { list ->
        if (p.mode == AISelectionMode.MANUAL) list.filter { it.key == p.manual } else list
    }
    private fun select(key: AIModelKey?, reason: String) {
        if (operationRevision != invalidations.get()) return
        if (lastSelection != key) {
            val previous = lastSelection
            val notice = when {
                key == null -> "هوش مصنوعی موقتاً در دسترس نیست؛ ثبت دستی و امکانات آفلاین برقرارند."
                previous == null -> "مدل تأییدشده فعال شد: ${key.id}"
                else -> "مدل از ${previous.id} به ${key.id} تغییر کرد؛ $reason"
            }
            mutable.value = mutable.value.copy(active = key, notice = notice)
            lastSelection = key; store.setActive(scope, key)
        }
        mutable.value = mutable.value.copy(active = key)
    }
    private suspend fun discover(force: Boolean): Boolean {
        val result = adapter.discover(force)
        if (operationRevision != invalidations.get()) return false
        return when (result) {
            is AIModelDiscovery.Found -> {
                catalogFailure = null; models = result.models; mutable.value = mutable.value.copy(checkedAt = result.checkedAt)
                if (lastSelection != null && models.none { it.key == lastSelection && AIRequestPolicy.eligibleMetadata(it, clock()) }) select(null, "قیمت/دسترسی معتبر نیست")
                true
            }
            is AIModelDiscovery.Failed -> { catalogFailure = result.error; models = emptyList(); select(null, "بررسی ممکن نشد"); publish(result.error.message); false }
        }
    }
    private suspend fun inspect(model: AIModel, force: Boolean = false): Boolean {
        val availability = adapter.availability(model, force)
        if (operationRevision != invalidations.get()) return false
        health[model.key] = availability
        if (availability.errorKind in accountErrors) evaluationStop = AiResult.Failure(requireNotNull(availability.errorKind), availability.explanation)
        return availability.available && AIRequestPolicy.fresh(availability.checkedAt, clock(), AIRequestPolicy.METADATA_TTL_MS)
    }
    private suspend fun tests(p: AISelectionSettings, automatic: Boolean, retest: Boolean) {
        var count = 0
        evaluationStop = null
        val now = clock()
        val todo = models.filter { AIRequestPolicy.eligibleMetadata(it, now) && (blocked[it.key] ?: 0) <= now }
            .filter { retest || evaluations.none { e -> e.key == it.key && e.fingerprint == it.fingerprint && e.suiteVersion == SafePersianModelEvaluator.VERSION && AIRequestPolicy.fresh(e.checkedAt, now, if (e.passed) AIRequestPolicy.EVALUATION_TTL_MS else 24 * 60 * 60_000L) } }
            .sortedWith(compareByDescending<AIModel> { it.capability.contextLength }.thenBy { it.key.id })
        // At most six metadata checks and two actual model evaluations per pass.
        for (model in todo.take(6)) {
            if (count >= 2 || !stillAuthorized(p)) break
            if (!inspect(model, force = true)) {
                if (evaluationStop != null) break
                blocked[model.key] = clock() + 5 * 60_000L
                continue
            }
            count++
            publish("در حال آزمون امن فارسی و ابزار: ${model.name}؛ فقط دادهٔ ساختگی")
            val evaluation = evaluator.evaluate(model, adapter) { stillAuthorized(p) && store.reserve(p.scope, clock(), automatic) }
            if (!stillAuthorized(p)) return
            if (evaluation.deferred) {
                mutable.value = mutable.value.copy(notice = "بودجهٔ روزانهٔ آزمون تمام شد؛ نتیجهٔ قبلی حفظ شد. آزمون خودکار بیشتر یا خرید اعتبار انجام نمی‌شود.")
                break
            }
            if (evaluation.errorKind in accountErrors) {
                evaluationStop = AiResult.Failure(requireNotNull(evaluation.errorKind), evaluation.explanation)
                publish(evaluation.explanation); break
            }
            store.save(scope, evaluation); evaluations = store.load(scope)
            if (evaluation.passed) healthMonitor.reset(model.key)
            publish()
        }
    }
    /** Metadata-only refreshes do not consume inference quota. Explicit tests are capped to two models. */
    suspend fun refresh(runTests: Boolean = false) = lock.withLock {
        val p = account() ?: return@withLock
        mutable.value = mutable.value.copy(checking = true); evaluationStop = null
        try {
            if (!discover(true)) return@withLock
            if (runTests) {
                tests(p, automatic = false, retest = true)
                evaluationStop?.let { stopAccount(it); return@withLock }
                if (!stillAuthorized(p) || !discover(true)) return@withLock
            }
            if (!stillAuthorized(p)) return@withLock
            for (model in candidates(p).take(6)) { inspect(model); if (evaluationStop != null || !stillAuthorized(p)) break }
            if (stillAuthorized(p)) {
                val next = if (evaluationStop == null) candidates(p).firstOrNull { model -> health[model.key]?.let {
                    it.available && AIRequestPolicy.fresh(it.checkedAt, clock(), AIRequestPolicy.METADATA_TTL_MS)
                } == true } else null
                select(next?.key, "فهرست/قیمت/دسترسی تازه شد"); publish(evaluationStop?.message ?: if (next == null) "مدلِ مجاز و قبول‌شده آماده نیست؛ آزمون امن را اجرا کنید یا محدودیت حساب/Guardrail را بررسی کنید." else null)
            }
        } finally { mutable.value = mutable.value.copy(checking = false) }
    }
    override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult = lock.withLock {
        val p = account() ?: return@withLock FunctionResult.Failed(AiResult.Failure(AiErrorKind.DISABLED, "ارسال مدل/ارزیابی فعال نیست یا رضایت داده نشده است."))
        if (!discover(false)) return@withLock catalogFailure?.let { FunctionResult.Failed(it) } ?: unavailable(mutable.value.message)
        mutable.value = mutable.value.copy(checking = true)
        try {
            // At most eight automatic dummy requests/day, only while the user is using AI. No background inference.
            tests(p, automatic = true, retest = false)
            evaluationStop?.let { return@withLock stopAccount(it) }
            if (!discover(false)) return@withLock catalogFailure?.let { FunctionResult.Failed(it) } ?: unavailable(mutable.value.message)
            val attempted = mutableSetOf<AIModelKey>()
            var last: AiResult.Failure? = null
            while (attempted.size < 2 && stillAuthorized(p)) {
                val model = candidates(p).firstOrNull { it.key !in attempted } ?: break
                attempted += model.key
                if (!inspect(model, force = lastSelection != model.key)) {
                    evaluationStop?.let { return@withLock stopAccount(it) }
                    blocked[model.key] = clock() + 5 * 60_000L; continue
                }
                if (!stillAuthorized(p)) break
                select(model.key, "مدل قبلی معتبر/در دسترس نبود؛ جایگزین فقط از فهرست قبول‌شده و مجاز است")
                publish()
                when (val result = adapter.infer(model.key, AIModelRequest(messages, AgentToolRegistry.tools), onText)) {
                    is FunctionResult.Reply -> {
                        if (!stillAuthorized(p)) return@withLock unavailable("حساب/رضایت تغییر کرد؛ پاسخ کنار گذاشته شد و ابزاری اجرا نمی‌شود.")
                        // No adapter may silently change the selected identity, even to another free model.
                        if (result.model == model.key.id) { publish(); return@withLock result }
                        last = AiResult.Failure(AiErrorKind.MODEL_INVALID, "شناسهٔ پاسخ با مدلِ آزموده‌شده یکی نبود؛ پاسخ و ابزارها کنار گذاشته شدند.")
                    }
                    is FunctionResult.Failed -> last = result.error
                }
                if (!stillAuthorized(p)) return@withLock unavailable("حساب یا انتخاب مدل تغییر کرد؛ پاسخ قدیمی کنار گذاشته شد.")
                onText("") // Discard partial prose before a different model is asked. No action has executed here.
                val error = requireNotNull(last)
                if (error.kind in accountErrors) {
                    return@withLock stopAccount(error) // Never evade account limits with model switching.
                }
                blocked[model.key] = clock() + 30 * 60_000L
                health[model.key] = AIModelAvailability(false, clock(), explanation = error.message)
                if (error.kind in setOf(AiErrorKind.MODEL_INVALID, AiErrorKind.BAD_REQUEST, AiErrorKind.GUARDRAIL_REJECTED)) {
                    val failed = AIModelEvaluation(model.key, model.fingerprint, clock(), emptyList(), 0, explanation = error.message)
                    store.save(scope, failed); evaluations = store.load(scope)
                }
                if (p.mode == AISelectionMode.MANUAL) { select(null, "مدل دستی معتبر نیست"); publish(error.message); return@withLock FunctionResult.Failed(error) }
                adapter.invalidate()
                if (!discover(true)) break
            }
            select(null, "هیچ مدل جایگزینِ قبول‌شده آماده نیست"); publish(last?.message)
            unavailable(last?.message)
        } catch (e: CancellationException) { throw e }
        finally { mutable.value = mutable.value.copy(checking = false) }
    }
    override fun reportToolProtocol(model: String, issue: NativeToolProtocolIssue): Boolean {
        val current = preferences()
        if (operationRevision != invalidations.get() || current.scope != scope || !current.enabled || !current.consented) return false
        val key = lastSelection?.takeIf { it.id == model } ?: return false
        val source = models.singleOrNull { it.key == key } ?: return false
        if (!healthMonitor.reject(key, issue)) return false
        val message = "پروتکل ابزار این مدل نامعتبر بود؛ برای ایمنی تا آزمون دوباره فعال نمی‌شود. نتایجِ قبلاً تأییدشده حفظ می‌شوند."
        blocked[key] = clock() + 30 * 60_000L
        health[key] = AIModelAvailability(false, clock(), explanation = message, errorKind = AiErrorKind.MODEL_INVALID)
        store.save(scope, AIModelEvaluation(key, source.fingerprint, clock(), emptyList(), 0, explanation = message, errorKind = AiErrorKind.MODEL_INVALID))
        evaluations = store.load(scope)
        mutable.value = mutable.value.copy(active = null)
        publish(message)
        return true
    }
    private fun stopAccount(error: AiResult.Failure): FunctionResult.Failed {
        // Preserve quality receipts, not a misleading currently-ready indication for a blocked account.
        health.keys.toList().forEach { health[it] = AIModelAvailability(false, clock(), explanation = error.message, errorKind = error.kind) }
        select(null, "ارسال به علت محدودیت/رضایت حساب متوقف شد"); publish(error.message)
        return FunctionResult.Failed(error)
    }
    private fun unavailable(detail: String?) = FunctionResult.Failed(AiResult.Failure(AiErrorKind.NOT_FOUND,
        "هوش مصنوعی موقتاً آماده نیست. " + detail.orEmpty().take(240) + "\nمدل‌های مجاز/آزمون امن در چرخ‌دنده هستند. ثبت دستی آفلاین برقرار است؛ پرداخت یا تغییر Guardrail انجام نمی‌شود."))
}

/** Model-health diagnostics contain no user/tool payload and never execute or replay any tool. */
class AIModelHealthMonitor {
    private val invalidTools = mutableMapOf<AIModelKey, Int>()
    fun reject(key: AIModelKey, issue: NativeToolProtocolIssue): Boolean {
        val count = (invalidTools.getOrDefault(key, 0) + if (issue == NativeToolProtocolIssue.INVALID_IDENTITIES) 2 else 1).coerceAtMost(2)
        invalidTools[key] = count
        return count >= 2
    }
    fun reset(key: AIModelKey? = null) { if (key == null) invalidTools.clear() else invalidTools.remove(key) }
    companion object {
        const val FOREGROUND_REFRESH_MS = 10 * 60_000L
        const val BACKGROUND_INTERVAL_HOURS = 6L
    }
}
