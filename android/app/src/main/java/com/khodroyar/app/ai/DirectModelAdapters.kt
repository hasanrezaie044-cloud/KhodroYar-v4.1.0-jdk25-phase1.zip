package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Groq (OpenAI-compatible) adapter: fixed official endpoint, user-selected provider only.
 * No proxies, redirects, SDK retry or paid fallback. OpenRouter has its own adapter.
 */
class DirectModelAdapter(
    private val mode: AiMode, private val configuration: () -> AiSettings, private val credentials: AiApiKeyReader,
    private val runtime: AIRequestRuntime, private val clock: () -> Long = System::currentTimeMillis,
) : AIModelAdapter {
    init { require(mode == AiMode.GROQ) { "Only Groq uses the direct adapter" } }
    override val providerId = AIProviderRegistry.get(mode).id
    private var scope = ""; private var cached = emptyList<AIModel>(); private var checked = 0L
    private class Rejected(val failure: AiResult.Failure) : Exception("Provider rejected")
    private fun account(): Pair<AiSettings, String> {
        val s = configuration()
        if (s.mode != mode || !s.enabled || !s.consented) throw Rejected(AiResult.Failure(AiErrorKind.DISABLED, "ارائه‌دهنده/رضایت تغییر کرده؛ ارسال نمی‌شود."))
        AIProviderRegistry.barrier(s, clock())?.let { throw Rejected(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, it)) }
        val key = credentials.read()?.let { AIProviderRegistry.normalizeKey(mode, it) }
            ?: throw Rejected(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "کلید این ارائه‌دهنده را فقط در تنظیمات امن وارد کنید."))
        return s to key
    }
    private fun unchanged(s: AiSettings) {
        if (configuration() != s) throw Rejected(AiResult.Failure(AiErrorKind.DISABLED, "حساب/مدل/تنظیمات تغییر کرد؛ پاسخ قدیمی کنار گذاشته شد."))
    }
    private suspend fun request(s: AiSettings, key: String, url: String, body: JSONObject? = null, onEvent: ((String) -> Unit)? = null): AiHttpResponse {
        unchanged(s)
        val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream", "Authorization" to "Bearer $key")
        val req = AiHttpRequest(url, if (body == null) "GET" else "POST", headers, body?.toString())
        require(AIProviderRegistry.permittedEndpoint(req))
        val response = runtime.send(providerId, req, onEvent)
        unchanged(s)
        if (response.status != 200) throw Rejected(ProviderFailurePolicy.response(response))
        return response
    }
    private fun failure(e: Exception): AiResult.Failure = when (e) {
        is Rejected -> e.failure
        is AIRequestRejected -> e.failure
        is OpenRouterReplyFailure -> e.failure
        is SocketTimeoutException -> AiResult.Failure(AiErrorKind.TIMEOUT, "مهلت سرویس تمام شد؛ دوباره خودکار ارسال نمی‌شود.")
        is IOException -> AiResult.Failure(AiErrorKind.OFFLINE, "اتصال امن برقرار نشد؛ امکانات آفلاین برقرارند.")
        else -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "فراداده یا پاسخ سرویس ناقص/ناسازگار بود؛ ابزاری از آن اجرا نمی‌شود.")
    }
    private val base = "https://api.groq.com/openai/v1"
    override suspend fun discover(force: Boolean): AIModelDiscovery = try {
        val (s, key) = account()
        if (scope != s.modelAccountScope) invalidate()
        if (!force && AIRequestPolicy.fresh(checked, clock(), AIRequestPolicy.METADATA_TTL_MS)) AIModelDiscovery.Found(cached, checked)
        else {
            val root = NativeJson.objectValue(request(s, key, "$base/models").body, 4 * 1024 * 1024)
            val data = root.getJSONArray("data")
            require(data.length() <= 2000)
            val allow = AIProviderRegistry.groqModels
            val now = clock()
            val models = (0 until data.length()).mapNotNull { i ->
                val row = data.getJSONObject(i)
                val id = row.getString("id")
                if (id !in allow || row.optBoolean("active", true).not()) return@mapNotNull null
                val context = row.optInt("context_window", 0)
                val fp = MessageDigest.getInstance("SHA-256").digest("$providerId|$id|$context|${AIProviderRegistry.VERIFIED_ON}".toByteArray()).joinToString("") { "%02x".format(it) }
                AIModel(AIModelKey(providerId, id), row.optString("displayName", id), AIProviderRegistry.get(mode).title, row.optString("owned_by", providerId),
                    AIModelPricing(null, null, false), AIModelCapability(true, context, imageInput = id in AIProviderRegistry.groqVisionModels), AIModelAuthorization.AUTHORIZED, now, fp,
                    costEvidence = AICostEvidence.FREE_QUOTA, freeQuotaEligible = true)
            }
            require(models.map { it.key }.distinct().size == models.size)
            unchanged(s); cached = models; checked = now; scope = s.modelAccountScope
            AIModelDiscovery.Found(models, now)
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { invalidate(); val f = failure(e); runtime.annotateFailure(providerId, f.kind); AIModelDiscovery.Failed(f) }
    override suspend fun availability(model: AIModel, force: Boolean): AIModelAvailability {
        val found = discover(false)
        return when (found) {
            is AIModelDiscovery.Failed -> AIModelAvailability(false, clock(), explanation = found.error.message, errorKind = found.error.kind)
            is AIModelDiscovery.Found -> AIModelAvailability(found.models.any { it.key == model.key && it.fingerprint == model.fingerprint }, found.checkedAt,
                providers = listOf(AIProviderRegistry.get(mode).title), explanation = "فهرست رسمی پاسخ داد؛ این نتیجه آزمون inference زنده یا تضمین سهمیه نیست.")
        }
    }
    override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult = try {
        val (s, key) = account()
        val found = discover(false)
        if (found is AIModelDiscovery.Failed) throw Rejected(found.error)
        val selected = cached.singleOrNull { it.key == model && AIRequestPolicy.eligibleMetadata(it, clock()) }
            ?: throw Rejected(AiResult.Failure(AiErrorKind.MODEL_INVALID, "مدل انتخابی در فهرست تازهٔ مجاز/سهمیهٔ Free تأیید نشد."))
        if (request.messages.any { it.images.isNotEmpty() } && !selected.capability.imageInput)
            throw Rejected(AiResult.Failure(AiErrorKind.MODEL_INVALID, "مدل انتخابی تصویر نمی‌پذیرد؛ یک مدل تصویری انتخاب کنید."))
        require(request.messages.none { key in it.content || AiTextSafety.containsCredential(it.content) || it.calls.any { c -> key in c.arguments || AiTextSafety.containsCredential(c.arguments) } })
        run {
            val body = JSONObject().put("model", model.id).put("messages", JSONArray().apply { request.messages.forEach { put(OpenRouterWire.messageJson(it)) } })
                .put("max_tokens", request.maxOutputTokens).put("stream", request.stream)
            if (request.tools.isNotEmpty()) body.put("tools", OpenRouterToolSchemas.tools(request.tools, compact = true)).put("tool_choice", "auto")
            body.remove("max_tokens"); body.put("max_completion_tokens", request.maxOutputTokens)
            // Documented instruct mode: no hidden reasoning tokens are billed or leaked into the Persian reply.
            if (model.id == "qwen/qwen3.8-27b") body.put("reasoning_effort", "none")
            if (request.stream) body.put("stream_options", JSONObject().put("include_usage", true))
            val decoder = OpenRouterStream(model.id, emptyList(), { text -> unchanged(s); onText(text) }, setOf(model.id))
            val url = "$base/chat/completions"
            val response = request(s, key, url, body, if (request.stream) ({ event -> unchanged(s); decoder.accept(event) }) else null)
            if (request.stream && response.body.isBlank()) decoder.result() else OpenRouterWire.completion(response.body, selected.key.id, emptyList(), setOf(model.id))
        }
    } catch (e: CancellationException) { onText(""); throw e }
    catch (e: Exception) { onText(""); val f = failure(e); runtime.annotateFailure(providerId, f.kind); FunctionResult.Failed(f) }
    override fun invalidate() { scope = ""; checked = 0; cached = emptyList() }
}

/** Exactly one explicitly selected adapter. A failure can never route to another provider. */
class SelectedAIModelAdapter(private val selected: () -> AiMode, private val adapters: Map<AiMode, AIModelAdapter>) : AIModelAdapter {
    override val providerId get() = AIProviderRegistry.get(selected()).id
    override suspend fun discover(force: Boolean): AIModelDiscovery = adapters[selected()]?.discover(force)
        ?: AIModelDiscovery.Failed(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, AIProviderRegistry.get(selected()).conditions))
    override suspend fun availability(model: AIModel, force: Boolean): AIModelAvailability =
        if (model.key.provider == providerId) adapters[selected()]?.availability(model, force) ?: AIModelAvailability(false, 0, explanation = "اتصال تأییدشده موجود نیست") else AIModelAvailability(false, 0, explanation = "ارائه‌دهنده عوض شده است")
    override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult =
        if (model.provider == providerId) adapters[selected()]?.infer(model, request, onText)
            ?: FunctionResult.Failed(AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "ارائه‌دهندهٔ انتخابی آماده نیست؛ fallback وجود ندارد."))
        else FunctionResult.Failed(AiResult.Failure(AiErrorKind.DISABLED, "ارائه‌دهنده تغییر کرد؛ درخواست قدیمی ارسال نمی‌شود."))
    override fun invalidate() = adapters.values.forEach { it.invalidate() }
}
