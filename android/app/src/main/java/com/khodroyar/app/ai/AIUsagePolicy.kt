package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/** Owner-controlled local ceilings, NOT a provider quota or a monetary spending guarantee. */
data class AIUsageLimits(
    val inputTokens: Int = 12000, val outputTokens: Int = 512,
    val requestsPerTurn: Int = 5, val requestsPerDay: Int = 40, val tokensPerDay: Int = 80000,
    val historyMessages: Int = 4, val historyChars: Int = 2400,
    val toolsEnabled: Boolean = true, val saveHistory: Boolean = true,
) {
    fun bounded() = copy(inputTokens = inputTokens.coerceIn(1000, 20000), outputTokens = outputTokens.coerceIn(128, 1536),
        requestsPerTurn = requestsPerTurn.coerceIn(1, 5), requestsPerDay = requestsPerDay.coerceIn(1, 100),
        tokensPerDay = tokensPerDay.coerceIn(1000, 200000), historyMessages = historyMessages.coerceIn(0, 6), historyChars = historyChars.coerceIn(0, 4000))
}

data class AIUsage(val input: Int?, val output: Int?, val total: Int?) {
    companion object {
        fun from(root: JSONObject): AIUsage? {
            val u = root.optJSONObject("usage") ?: root.optJSONObject("usageMetadata") ?: root.optJSONObject("x_groq")?.optJSONObject("usage") ?: return null
            fun n(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
                (u.opt(key) as? Number)?.let { runCatching { java.math.BigDecimal(it.toString()).intValueExact() }.getOrNull() }?.takeIf { it in 0..10000000 }
            }
            val input = n("prompt_tokens", "promptTokenCount", "input_tokens")
            val output = n("completion_tokens", "candidatesTokenCount", "output_tokens")
            val total = n("total_tokens", "totalTokenCount") ?: if (input != null && output != null) input + output else null
            return if (input == null && output == null && total == null) null else AIUsage(input, output, total)
        }
    }
}

data class AIRequestDiagnostic(
    val provider: String, val model: String, val metadata: Boolean, val estimatedInput: Int,
    val outputLimit: Int, val actual: AIUsage?, val httpStatus: Int?, val error: AiErrorKind?,
    val elapsedMs: Long, val at: Long, val dispatched: Boolean,
    val remainingRequests: Int? = null, val remainingTokens: Int? = null,
)
data class AIUsageLedger(val day: Long = -1, val requests: Int = 0, val reservedTokens: Long = 0, val metadataRequests: Int = 0,
    val minute: Long = -1, val minuteRequests: Int = 0, val blockedScope: String = "", val blockedUntil: Long = 0, val blockedKind: String = "") {
    /** A timer expiring is not owner permission to retry a hard failure in periodic work. */
    fun permitsBackgroundMetadata(account: String): Boolean = blockedScope != account || ProviderFailurePolicy.NEVER_RETRY.none { it.name == blockedKind }
}
interface AIUsageStore { fun read(): AIUsageLedger; fun write(value: AIUsageLedger) }
class MemoryAIUsageStore : AIUsageStore { private var value = AIUsageLedger(); override fun read() = value; override fun write(value: AIUsageLedger) { this.value = value } }
class AIRequestRejected(val failure: AiResult.Failure) : Exception("AI request rejected locally")

/** One serialized dispatch path. Reserves durably BEFORE network, including cancelled/failed requests. No automatic retry. */
class AIRequestRuntime(
    private val transport: AiHttpTransport,
    private val limits: () -> AIUsageLimits = { AIUsageLimits() },
    private val scope: () -> String = { "default" },
    private val store: AIUsageStore = MemoryAIUsageStore(), private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<AIRequestDiagnostic>>(emptyList())
    val diagnostics = mutable.asStateFlow()
    fun ledger(): AIUsageLedger = store.read()
    /** Wire validation runs after HTTP; annotate, never retain the response text. */
    fun annotateFailure(provider: String, kind: AiErrorKind) {
        val list = mutable.value; val last = list.lastOrNull() ?: return
        if (last.provider == provider && last.error == null && clock() - last.at in 0..90000)
            mutable.value = list.dropLast(1) + last.copy(error = kind)
    }
    private fun block(account: String, kind: AiErrorKind, retryAfter: String?) {
        if (kind !in ProviderFailurePolicy.NEVER_RETRY) return
        val until = if (kind == AiErrorKind.RATE_LIMITED) clock() + (retryAfter?.toLongOrNull()?.coerceIn(1, 86400) ?: 60) * 1000 else Long.MAX_VALUE
        synchronized(reservationLock) { store.write(store.read().copy(blockedScope = account, blockedUntil = until, blockedKind = kind.name)) }
    }
    private fun record(value: AIRequestDiagnostic) { mutable.value = (mutable.value + value).takeLast(30) }
    suspend fun send(provider: String, request: AiHttpRequest, onEvent: ((String) -> Unit)? = null): AiHttpResponse = withContext(Dispatchers.IO) {
        mutex.withLock {
            val start = clock(); val metadata = request.method == "GET"; val l = limits().bounded(); val account = scope()
            val body = request.body?.let { NativeJson.objectValue(it, 128 * 1024) }
            val model = body?.optString("model")?.takeIf { it.length <= 160 && !AiTextSafety.containsCredential(it) }
                ?: request.url.substringAfter("/models/", "").substringBefore(':').take(100)
            // UTF-8 byte count is a deliberately conservative local upper estimate, not tokenizer telemetry.
            val input = if (metadata) 0 else (request.body.orEmpty().toByteArray(Charsets.UTF_8).size + 128)
            val out = if (metadata) 0 else body?.optJSONObject("generationConfig")?.optInt("maxOutputTokens")
                ?: body?.optInt("max_completion_tokens", 0)?.takeIf { it > 0 } ?: body?.optInt("max_tokens", l.outputTokens) ?: l.outputTokens
            val day = start / 86400000; val minute = start / 60000
            fun reject(kind: AiErrorKind, message: String): Nothing {
                record(AIRequestDiagnostic(provider, model, metadata, input, out, null, null, kind, 0, start, false))
                throw AIRequestRejected(AiResult.Failure(kind, message))
            }
            synchronized(reservationLock) {
                val previous = store.read()
                if (day < previous.day || minute < previous.minute) reject(AiErrorKind.RATE_LIMITED, "ساعت دستگاه عقب رفته؛ بودجهٔ قبلی برای جلوگیری از مصرف اضافه حفظ شد.")
                if (previous.blockedScope == account && start < previous.blockedUntil)
                    reject(runCatching { AiErrorKind.valueOf(previous.blockedKind) }.getOrDefault(AiErrorKind.RATE_LIMITED), "ارسال پس از خطای سهمیه/اعتبار/کلید متوقف است؛ تلاش یا جایگزینی خودکار نداریم.")
                val old = if (day == previous.day) previous else previous.copy(day = day, requests = 0, reservedTokens = 0, metadataRequests = 0)
                val inMinute = if (minute == old.minute) old.minuteRequests else 0
                if (inMinute >= 12) reject(AiErrorKind.RATE_LIMITED, "سقف محلی ۱۲ درخواست HTTP در دقیقه رسیده است؛ خودکار دوباره تلاش نمی‌شود.")
                if (!metadata && (out !in 1..l.outputTokens || input > l.inputTokens))
                    reject(AiErrorKind.BAD_REQUEST, "پیام، تاریخچه یا تعریف ابزار بیش از سقف ورودی/خروجی است؛ پیام را کوتاه یا سقف تنظیمات را بررسی کنید. چیزی ارسال نشد.")
                if (!metadata && (old.requests >= l.requestsPerDay || old.reservedTokens + input + out > l.tokensPerDay))
                    reject(AiErrorKind.RATE_LIMITED, "بودجهٔ روزانهٔ محلی درخواست/توکن تمام شد؛ ارسال، ارزیابی یا پرداخت خودکار انجام نمی‌شود.")
                store.write(old.copy(day = day, requests = old.requests + if (metadata) 0 else 1,
                    reservedTokens = old.reservedTokens + input + out, metadataRequests = old.metadataRequests + if (metadata) 1 else 0,
                    minute = minute, minuteRequests = inMinute + 1))
            }
            var usage: AIUsage? = null; var status: Int? = null; var error: AiErrorKind? = null; var response: AiHttpResponse? = null
            try {
                currentCoroutineContext().ensureActive()
                val callback = onEvent?.let { downstream -> { event: String ->
                    if (event != "[DONE]") {
                        val root = runCatching { NativeJson.objectValue(event, 65536) }.getOrNull()
                        root?.let { AIUsage.from(it) }?.let { usage = it }
                        root?.optJSONObject("error")?.let { throw OpenRouterReplyFailure(ProviderFailurePolicy.http(it.optInt("code", 500))) }
                    }
                    downstream(event)
                } }
                val reply = if (transport is OpenRouterHttp) transport.await(request, callback)
                    else if (callback != null && transport is AiStreamingTransport) transport.stream(request, callback) else transport.execute(request)
                response = reply; status = reply.status
                runCatching { AIUsage.from(NativeJson.objectValue(reply.body, 256 * 1024)) }.getOrNull()?.let { usage = it }
                if (reply.status !in 200..299) {
                    error = ProviderFailurePolicy.http(reply.status, reply.retryAfter).kind
                    block(account, requireNotNull(error), reply.retryAfter)
                }
                if (!metadata && reply.status in 200..299 && (usage?.output?.let { it > out } == true || usage?.total?.let { it > input + out } == true)) {
                    val extra = ((usage?.total ?: 0) - input - out).coerceAtLeast(0)
                    synchronized(reservationLock) { store.write(store.read().let { it.copy(reservedTokens = it.reservedTokens + extra) }) }
                    throw OpenRouterReplyFailure(AiResult.Failure(AiErrorKind.GUARDRAIL_REJECTED,
                        "سرویس مصرفی بیش از سقف رزروشده اعلام کرد؛ پاسخ/ابزار پذیرفته نمی‌شود و ارسال بعدی متوقف است. تنظیمات حساب را بررسی کنید."))
                }
                reply
            } catch (e: CancellationException) { error = AiErrorKind.DISABLED; throw e }
            catch (e: OpenRouterReplyFailure) { error = e.failure.kind; block(account, e.failure.kind, e.failure.retryAfterSec?.toString()); throw e }
            catch (e: SocketTimeoutException) { error = AiErrorKind.TIMEOUT; throw e }
            catch (e: IOException) { error = AiErrorKind.OFFLINE; throw e }
            catch (e: Exception) { error = AiErrorKind.BAD_REQUEST; throw e }
            finally {
                fun remaining(key: String): Int? = response?.headers?.entries?.firstOrNull { it.key.equals(key, true) }?.value?.toLongOrNull()?.takeIf { it in 0..10000000 }?.toInt()
                record(AIRequestDiagnostic(provider, model, metadata, input, out, usage, status, error, (clock() - start).coerceAtLeast(0), start, true,
                    remaining("x-ratelimit-remaining-requests"), remaining("x-ratelimit-remaining-tokens")))
                // Conservative reservations are never refunded: a timed-out provider may have consumed the full allowance.
            }
        }
    }
    companion object { private val reservationLock = Any() }
}

object ProviderFailurePolicy {
    val NEVER_RETRY = setOf(AiErrorKind.UNAUTHORIZED, AiErrorKind.CREDIT_EXHAUSTED, AiErrorKind.RATE_LIMITED, AiErrorKind.GUARDRAIL_REJECTED)
    fun http(status: Int, retryAfter: String? = null): AiResult.Failure = when (status) {
        401 -> AiResult.Failure(AiErrorKind.UNAUTHORIZED, "کلید یا مجوز حساب پذیرفته نشد؛ تکرار یا تعویض ارائه‌دهنده انجام نمی‌شود.")
        402 -> AiResult.Failure(AiErrorKind.CREDIT_EXHAUSTED, "اعتبار/طرح حساب کافی نیست؛ خرید، ارتقا و تکرار درخواست انجام نمی‌شود.")
        403, 451 -> AiResult.Failure(AiErrorKind.GUARDRAIL_REJECTED, "محدودیت حساب، Guardrail یا منطقه؛ مجوز دور زده نمی‌شود و سرویس دیگری خودکار استفاده نمی‌شود.")
        429 -> AiResult.Failure(AiErrorKind.RATE_LIMITED, "سهمیهٔ سرویس تمام یا محدود شده؛ تلاش خودکار نداریم.", retryAfter?.toIntOrNull()?.coerceIn(1, 86400))
        404, 410 -> AiResult.Failure(AiErrorKind.NOT_FOUND, "مدل/مسیر انتخابی دیگر در دسترس نیست؛ مدل دیگری خودکار انتخاب نمی‌شود.")
        in 500..599 -> AiResult.Failure(AiErrorKind.SERVER, "سرویس فعلاً پاسخ کامل نداد؛ تلاش دوباره فقط با اقدام شما و در بودجهٔ محلی ممکن است.")
        else -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "درخواست یا پاسخ سرویس معتبر نبود؛ متن خام خطا و اطلاعات کلید نمایش داده نمی‌شود.")
    }
}
