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

/** Direct, user-key-only provider. Every completion re-verifies live metadata; no cache authorises billing. */
class OpenRouterClient(
    private val configuration: () -> AiSettings,
    private val credentials: AiApiKeyReader,
    private val transport: AiHttpTransport = OpenRouterHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
) : FunctionCallingProvider {
    private val requestLock = Mutex()
    private val _models = MutableStateFlow<List<VerifiedFreeModel>>(emptyList())
    val models = _models.asStateFlow()
    private val attempts = ArrayDeque<Long>()
    private var blockedUntil = 0L

    private class SafeFailure(val failure: AiResult.Failure) : Exception("OpenRouter request rejected")
    private fun fail(kind: AiErrorKind, message: String): Nothing = throw SafeFailure(AiResult.Failure(kind, message))
    private fun account(): Pair<AiSettings, String> {
        val s = configuration()
        if (!s.enabled || s.mode != AiMode.OPENROUTER || !s.consented)
            fail(AiErrorKind.DISABLED, "ارسال به OpenRouter فعال نیست یا رضایت ارسال داده داده نشده است.")
        val key = try { credentials.read()?.let(OpenRouterFreePolicy::normalizeKey) } catch (_: Exception) {
            fail(AiErrorKind.NOT_CONFIGURED, "خواندن کلید رمزنگاری‌شده ممکن نیست؛ در تنظیمات دوباره ذخیره کنید. ذخیرهٔ متن ساده نداریم.")
        } ?: fail(AiErrorKind.NOT_CONFIGURED, "کلید OpenRouter را فقط در تنظیمات امن وارد کنید؛ کلید را در گفتگو نفرستید.")
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
    private suspend fun request(request: AiHttpRequest, onEvent: ((String) -> Unit)? = null): AiHttpResponse =
        if (transport is OpenRouterHttp) transport.await(request, onEvent)
        else if (onEvent != null && transport is AiStreamingTransport) transport.stream(request, onEvent)
        else transport.execute(request)

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
            catch (e: SafeFailure) { failure(e.failure) }
            catch (_: SocketTimeoutException) { failure(AiResult.Failure(AiErrorKind.TIMEOUT, "مهلت پاسخ OpenRouter تمام شد؛ عملیات تأییدنشده اجرا نمی‌شود.")) }
            catch (_: IOException) { failure(AiResult.Failure(AiErrorKind.OFFLINE, "اتصال امن به OpenRouter برقرار نشد. ثبت دستی و امکانات آفلاین همچنان کار می‌کنند.")) }
            catch (_: Exception) { failure(AiResult.Failure(AiErrorKind.BAD_REQUEST, "پاسخ کامل، رایگان و سازگار با ابزار تأیید نشد؛ ابزار جدیدی اجرا نمی‌شود. فهرست مدل رایگان یا اتصال را بررسی کنید.")) }
        }
    }

    suspend fun refreshModels(): AiResult.Failure? = guarded({ account(); fetchCatalog(); null }, { _models.value = emptyList(); it })

    override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult =
        perform(messages, FunctionToolSchemas.tools(), null, onText)

    private suspend fun perform(messages: List<FunctionMessage>, schemas: JSONArray, forcedTool: String?, onText: (String) -> Unit): FunctionResult = guarded({
        val (s, key) = account()
        if (!OpenRouterFreePolicy.isFreeId(s.openRouterModel)) fail(AiErrorKind.BAD_REQUEST, "فقط openrouter/free یا مدل :free تأییدشده مجاز است؛ درخواست پولی ارسال نشد.")
        require(messages.isNotEmpty() && messages.size <= 80 && messages.sumOf { it.content.length + it.calls.sumOf { c -> c.arguments.length } } <= 160000)
        if (messages.any { OpenRouterFreePolicy.containsCredential(it.content) || it.calls.any { c -> OpenRouterFreePolicy.containsCredential(c.arguments) } }) fail(AiErrorKind.BAD_REQUEST, "متن شبیه کلید API است؛ ذخیره یا ارسال نمی‌شود. کلید را فقط در تنظیمات امن وارد کنید.")
        val catalog = fetchCatalog() // Never authorise from an old UI list, a suffix, or a remembered zero price.
        val selected = s.openRouterModel
        if (catalog.none { it.id == selected }) fail(AiErrorKind.NOT_FOUND, "مدل انتخابی در فهرست تازهٔ رایگان/ابزار تأیید نشد. خودکار عوض نمی‌شود؛ در تنظیمات مدل رایگان دیگری انتخاب کنید.")
        val streaming = forcedTool == null && transport is AiStreamingTransport
        val body = JSONObject().put("tool_choice", "auto").put("model", selected).put("messages", JSONArray().apply { messages.forEach { put(it.json()) } })
            .put("tools", schemas).put("provider", OpenRouterFreePolicy.routing()).put("stream", streaming)
            .put("max_tokens", if (forcedTool != null) 256 else PersianAgentLimits.OUTPUT_TOKENS)
        if (forcedTool != null) body.put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", forcedTool)))
        // No models array, plugins, web search, paid fallback, custom endpoint, or purchasing API exists here.
        attempts.addLast(clock())
        val request = AiHttpRequest(OpenRouterFreePolicy.CHAT_URL, "POST", headers(key), body.toString())
        val context = currentCoroutineContext()
        val decoder = OpenRouterStream(selected, catalog) { text -> context.ensureActive(); onText(text) }
        val response = request(request, if (streaming) ({ event -> context.ensureActive(); decoder.accept(event) }) else null)
        checkHttp(response); context.ensureActive()
        if (streaming && response.body.isBlank()) decoder.result() else OpenRouterWire.completion(response.body, selected, catalog)
    }, { FunctionResult.Failed(it) })

    /** Explicit user test: real free tool call, a synthetic nonce only; NEVER executes app tools. */
    suspend fun testConnection(): AiProviderStatus {
        val nonce = java.util.UUID.randomUUID().toString()
        val probe = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "connection_probe")
            .put("description", "Return the supplied nonce; no database or application operation")
            .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("required", JSONArray(listOf("nonce"))).put("properties", JSONObject().put("nonce", JSONObject().put("type", "string").put("enum", JSONArray(listOf(nonce))))))))
        return when (val result = perform(listOf(FunctionMessage("user", "برای آزمایش اتصال، فقط connection_probe را با nonce برابر $nonce صدا بزن.")), probe, "connection_probe", {})) {
            is FunctionResult.Reply -> {
                val c = result.message.calls.singleOrNull()
                val ok = c != null && c.name == "connection_probe" && runCatching { JSONObject(c.arguments).getString("nonce") == nonce }.getOrDefault(false)
                AiProviderStatus(true, ok, ok, result.model, ok,
                    if (ok) "اتصال و function calling واقعی مدل رایگان تأیید شد: ${result.model}. هیچ ابزار برنامه یا دادهٔ مالی در تست استفاده نشد."
                    else "پاسخ رسید ولی ابزار آزمایشی درست نبود؛ این مدل فعلاً برای ایجنت تأیید عملی نشد.", if (ok) null else AiErrorKind.BAD_REQUEST)
            }
            is FunctionResult.Failed -> AiProviderStatus(false, message = result.error.message, errorKind = result.error.kind)
        }
    }
}
