package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/** Direct user-key-only transport. Production inference requires account-bound, fresh all-zero metadata. */
class OpenRouterClient(
    private val configuration: () -> AiSettings,
    private val credentials: AiApiKeyReader,
    private val transport: AiHttpTransport = OpenRouterHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val runtime: AIRequestRuntime = AIRequestRuntime(transport, { configuration().usage }, { configuration().modelAccountScope }),
) {
    private val requestLock = Mutex()
    private val _models = MutableStateFlow<List<VerifiedFreeModel>>(emptyList())
    val models = _models.asStateFlow()
    private val attempts = ArrayDeque<Long>()
    private var blockedUntil = 0L

    private class SafeFailure(val failure: AiResult.Failure) : Exception("OpenRouter request rejected")
    private fun fail(kind: AiErrorKind, message: String): Nothing = throw SafeFailure(AiResult.Failure(kind, message))
    internal fun accountScope(): String? = configuration().let { s ->
        s.modelAccountScope.takeIf { it.isNotBlank() && s.enabled && s.mode == AiMode.OPENROUTER && s.consented }
    }
    private fun requireScope(expected: String) {
        if (accountScope() != expected) fail(AiErrorKind.DISABLED, "حساب یا رضایت عوض شد؛ بررسی قبلی و پاسخ آن معتبر نیست و ابزاری اجرا نمی‌شود.")
    }
    private fun account(): Pair<AiSettings, String> {
        val s = configuration()
        if (!s.enabled || s.mode != AiMode.OPENROUTER || !s.consented)
            fail(AiErrorKind.DISABLED, "ارسال به OpenRouter فعال نیست یا رضایت ارسال داده داده نشده است.")
        val key = try { credentials.read()?.let(OpenRouterFreePolicy::normalizeKey) } catch (_: Exception) {
            fail(AiErrorKind.NOT_CONFIGURED, "خواندن کلید رمزنگاری‌شده ممکن نیست؛ در تنظیمات دوباره ذخیره کنید. ذخیرهٔ متن ساده نداریم.")
        } ?: fail(AiErrorKind.NOT_CONFIGURED, "کلید OpenRouter را فقط در تنظیمات امن وارد کنید؛ کلید را در گفتگو نفرستید.")
        requireScope(s.modelAccountScope)
        return s to key
    }
    private fun checkHttp(response: AiHttpResponse) {
        if (response.status == 200) return
        val failure = OpenRouterFreePolicy.error(response.status, response.retryAfter)
        if (failure.kind == AiErrorKind.RATE_LIMITED) blockedUntil = clock() + (failure.retryAfterSec ?: 60) * 1000L
        throw SafeFailure(failure)
    }
    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json", "Accept" to "application/json, text/event-stream")
    private suspend fun fetchCatalog(): List<VerifiedFreeModel> {
        val response = request(AiHttpRequest(OpenRouterFreePolicy.MODELS_URL, "GET", mapOf("Accept" to "application/json")))
        checkHttp(response)
        val verified = OpenRouterFreePolicy.catalog(response.body)
        _models.value = verified
        if (verified.isEmpty()) fail(AiErrorKind.NOT_FOUND, "هیچ مدل رایگانِ دارای ابزار و ظرفیت کافی تأیید نشد. مدل پولی استفاده نمی‌شود؛ بعداً فهرست را تازه کنید.")
        return verified
    }
    private suspend fun request(request: AiHttpRequest, onEvent: ((String) -> Unit)? = null): AiHttpResponse {
        currentCoroutineContext().ensureActive()
        return runtime.send("openrouter", request, onEvent)
    }

    private fun checkLimit() {
        val now = clock()
        if (now < blockedUntil) throw SafeFailure(AiResult.Failure(AiErrorKind.RATE_LIMITED,
            "محدودیت قبلی OpenRouter هنوز تمام نشده؛ تلاش خودکار یا تغییر به مدل پولی انجام نمی‌شود.", ((blockedUntil - now) / 1000 + 1).toInt()))
        while (attempts.isNotEmpty() && attempts.first() <= now - 60000) attempts.removeFirst()
        if (attempts.size >= 20) fail(AiErrorKind.RATE_LIMITED, "حد ایمنی برنامه: حداکثر ۲۰ درخواست مدل در یک دقیقه. کمی صبر کنید؛ سهمیهٔ حساب را OpenRouter تعیین می‌کند.")
    }
    private suspend fun <T> guarded(work: suspend () -> T, failure: (AiResult.Failure) -> T): T = withContext(Dispatchers.IO) {
        requestLock.withLock {
            try { checkLimit(); work() }
            catch (e: CancellationException) { throw e }
            catch (e: AIRequestRejected) { failure(e.failure) }
            catch (e: SafeFailure) { runtime.annotateFailure("openrouter", e.failure.kind); failure(e.failure) }
            catch (e: OpenRouterReplyFailure) {
                if (e.failure.kind == AiErrorKind.RATE_LIMITED) blockedUntil = clock() + (e.failure.retryAfterSec ?: 60) * 1000L
                runtime.annotateFailure("openrouter", e.failure.kind); failure(e.failure)
            }
            catch (_: SocketTimeoutException) { failure(AiResult.Failure(AiErrorKind.TIMEOUT, "مهلت پاسخ OpenRouter تمام شد؛ عملیات تأییدنشده اجرا نمی‌شود.")) }
            catch (_: IOException) { failure(AiResult.Failure(AiErrorKind.OFFLINE, "اتصال امن به OpenRouter برقرار نشد. ثبت دستی و امکانات آفلاین همچنان کار می‌کنند.")) }
            catch (_: Exception) { runtime.annotateFailure("openrouter", AiErrorKind.BAD_REQUEST); failure(AiResult.Failure(AiErrorKind.BAD_REQUEST, "پاسخ مدل ناقص یا ناسازگار با پروتکل ابزار بود؛ چیزی از این پاسخ اجرا نمی‌شود. مدل را دوباره آزمایش کنید یا از حالت خودکار استفاده کنید.")) }
        }
    }

    suspend fun refreshModels(): AiResult.Failure? = guarded({ account(); fetchCatalog(); null }, { _models.value = emptyList(); it })

    /** Metadata requests never include conversation/record data. Only /models/user receives the key. */
    internal suspend fun metadata(url: String, authenticated: Boolean, expectedScope: String): OpenRouterMetadataResult = guarded({
        val (_, key) = account(); requireScope(expectedScope)
        require((url == OpenRouterModelMetadata.USER_MODELS_URL) == authenticated)
        require(url == OpenRouterFreePolicy.MODELS_URL || url == OpenRouterModelMetadata.USER_MODELS_URL ||
            (url.startsWith(OpenRouterFreePolicy.MODELS_URL + "/") && url.endsWith("/endpoints") &&
                OpenRouterModelMetadata.validId(url.removePrefix(OpenRouterFreePolicy.MODELS_URL + "/").removeSuffix("/endpoints"))))
        val response = request(AiHttpRequest(url, "GET", if (authenticated) headers(key) else mapOf("Accept" to "application/json")))
        checkHttp(response)
        currentCoroutineContext().ensureActive(); requireScope(expectedScope)
        OpenRouterMetadataResult.Body(response.body)
    }, { OpenRouterMetadataResult.Failed(it) })

    /** Called only through the managed adapter, with fresh independent price, authorization and endpoint evidence. */
    internal suspend fun inferApproved(model: AIModel, available: AIModelAvailability, responseIds: Set<String>, expectedScope: String,
        query: AIModelRequest, onText: (String) -> Unit): FunctionResult = guarded({
        val (_, key) = account(); requireScope(expectedScope)
        require(model.key.provider == OpenRouterModelMetadata.PROVIDER && AIRequestPolicy.eligibleMetadata(model, clock()))
        require(available.available && AIRequestPolicy.fresh(available.checkedAt, clock(), AIRequestPolicy.METADATA_TTL_MS))
        require(model.key.id in responseIds && responseIds.all(OpenRouterModelMetadata::validId))
        val messages = query.messages
        require(messages.isNotEmpty() && messages.size <= 80 && messages.sumOf { it.content.length + it.calls.sumOf { c -> c.arguments.length } } <= 160000)
        if (messages.any { key in it.content || AiTextSafety.containsCredential(it.content) || it.calls.any { c -> key in c.arguments || AiTextSafety.containsCredential(c.arguments) } })
            fail(AiErrorKind.BAD_REQUEST, "متن شبیه کلید API است؛ ارسال نمی‌شود. کلید فقط در تنظیمات امن وارد شود.")
        require(query.maxOutputTokens in 128..configuration().usage.bounded().outputTokens)
        val streaming = query.stream && transport is AiStreamingTransport
        val body = JSONObject().put("model", model.key.id).put("messages", JSONArray().apply { messages.forEach { put(OpenRouterWire.messageJson(it)) } })
            .put("stream", streaming).put("max_tokens", query.maxOutputTokens).put("provider", OpenRouterFreePolicy.routing())
        if (query.tools.isNotEmpty()) body.put("tools", OpenRouterToolSchemas.tools(query.tools, compact = true)).put("tool_choice", "auto")
        if (streaming) body.put("stream_options", JSONObject().put("include_usage", true))
        require(body.toString().toByteArray(Charsets.UTF_8).size + 128 + query.maxOutputTokens <= model.capability.contextLength)
        attempts.addLast(clock())
        val context = currentCoroutineContext()
        val decoder = OpenRouterStream(model.key.id, emptyList(), { text -> context.ensureActive(); requireScope(expectedScope); onText(text) }, responseIds)
        val response = request(AiHttpRequest(OpenRouterFreePolicy.CHAT_URL, "POST", headers(key), body.toString()),
            if (streaming) ({ event -> context.ensureActive(); decoder.accept(event) }) else null)
        checkHttp(response); context.ensureActive()
        requireScope(expectedScope)
        val reply = if (streaming && response.body.isBlank()) decoder.result()
            else OpenRouterWire.completion(response.body, model.key.id, emptyList(), responseIds)
        // Canonical response aliases are accepted ONLY when explicitly supplied by the official catalog.
        // Never strip ':free', invent an ID or request the paid/canonical alias.
        reply.copy(model = model.key.id)
    }, { FunctionResult.Failed(it) })

    /** Internal legacy wire regression hook; cannot be supplied to an agent. Production uses ManagedAIProvider. */
    internal suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult =
        perform(messages, OpenRouterToolSchemas.tools(AIToolExposure.tools(messages, configuration().usage.toolsEnabled), compact = true), null, onText)

    private suspend fun perform(messages: List<FunctionMessage>, schemas: JSONArray, forcedTool: String?, onText: (String) -> Unit): FunctionResult = guarded({
        val (s, key) = account()
        if (!OpenRouterFreePolicy.isFreeId(s.openRouterModel)) fail(AiErrorKind.BAD_REQUEST, "فقط openrouter/free یا مدل :free تأییدشده مجاز است؛ درخواست پولی ارسال نشد.")
        require(messages.isNotEmpty() && messages.size <= 80 && messages.sumOf { it.content.length + it.calls.sumOf { c -> c.arguments.length } } <= 160000)
        if (messages.any { OpenRouterFreePolicy.containsCredential(it.content) || it.calls.any { c -> OpenRouterFreePolicy.containsCredential(c.arguments) } }) fail(AiErrorKind.BAD_REQUEST, "متن شبیه کلید API است؛ ذخیره یا ارسال نمی‌شود. کلید را فقط در تنظیمات امن وارد کنید.")
        val catalog = fetchCatalog() // Never authorise from an old UI list, a suffix, or a remembered zero price.
        val selected = s.openRouterModel
        if (catalog.none { it.id == selected }) fail(AiErrorKind.NOT_FOUND, "مدل انتخابی در فهرست تازهٔ رایگان/ابزار تأیید نشد. خودکار عوض نمی‌شود؛ در تنظیمات مدل رایگان دیگری انتخاب کنید.")
        val streaming = forcedTool == null && transport is AiStreamingTransport
        val body = JSONObject().put("tool_choice", "auto").put("model", selected).put("messages", JSONArray().apply { messages.forEach { put(OpenRouterWire.messageJson(it)) } })
            .put("tools", schemas).put("provider", OpenRouterFreePolicy.routing()).put("stream", streaming)
            .put("max_tokens", if (forcedTool != null) 256 else s.usage.bounded().outputTokens)
        if (forcedTool != null) body.put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", forcedTool)))
        // No models array, plugins, web search, paid fallback, custom endpoint, or purchasing API exists here.
        attempts.addLast(clock())
        val request = AiHttpRequest(OpenRouterFreePolicy.CHAT_URL, "POST", headers(key), body.toString())
        val context = currentCoroutineContext()
        val decoder = OpenRouterStream(selected, catalog, onText = { text -> context.ensureActive(); onText(text) })
        val response = request(request, if (streaming) ({ event -> context.ensureActive(); decoder.accept(event) }) else null)
        checkHttp(response); context.ensureActive()
        if (streaming && response.body.isBlank()) decoder.result() else OpenRouterWire.completion(response.body, selected, catalog)
    }, { FunctionResult.Failed(it) })

    /** Metadata-only legacy API as well: connection checks NEVER dispatch a completion/probe. */
    suspend fun testConnection(): AiProviderStatus {
        val adapter = OpenRouterModelAdapter(this)
        return when (val found = adapter.discover(force = true)) {
            is AIModelDiscovery.Failed -> AiProviderStatus(false, message = found.error.message, errorKind = found.error.kind)
            is AIModelDiscovery.Found -> {
                val s = configuration()
                val eligible = found.models.filter { AIRequestPolicy.eligibleMetadata(it, clock()) }
                val model = if (s.modelSelectionMode == AISelectionMode.MANUAL) eligible.firstOrNull { it.key.id == s.openRouterModel } else eligible.firstOrNull()
                val health = model?.let { adapter.availability(it, force = true) }
                val ok = health?.available == true
                AiProviderStatus(true, ok, ok, model?.key?.id.orEmpty(), ok,
                    if (ok) "کلید/فهرست/endpoint پاسخ داد؛ بدون درخواست تولید متن یا آزمون مدل."
                    else health?.explanation ?: "مدل مجاز آماده نیست؛ هیچ پیام AI ارسال نشد.",
                    if (ok) null else health?.errorKind ?: AiErrorKind.NOT_FOUND)
            }
        }
    }

}

internal sealed interface OpenRouterMetadataResult {
    data class Body(val value: String) : OpenRouterMetadataResult
    data class Failed(val error: AiResult.Failure) : OpenRouterMetadataResult
}
