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

/** Legacy preference fields remain readable. Usage ceilings are no longer enforced; history/tools preferences still apply. */
data class AIUsageLimits(
    val inputTokens: Int = 12000, val outputTokens: Int = 512,
    val requestsPerTurn: Int = 5, val requestsPerDay: Int = 40, val tokensPerDay: Int = 80000,
    val historyMessages: Int = 4, val historyChars: Int = 2400,
    val toolsEnabled: Boolean = true, val saveHistory: Boolean = true,
) {
    // Compatibility with old preferences/exports only. None of these numeric fields is an allowance.
    fun bounded() = this
}

data class AIUsage(val input: Int?, val output: Int?, val total: Int?) {
    companion object {
        fun from(root: JSONObject): AIUsage? {
            val u = root.optJSONObject("usage") ?: root.optJSONObject("message")?.optJSONObject("usage") ?: root.optJSONObject("usageMetadata") ?: root.optJSONObject("x_groq")?.optJSONObject("usage") ?: return null
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
    val minute: Long = -1, val minuteRequests: Int = 0, val blockedScope: String = "", val blockedUntil: Long = 0, val blockedKind: String = "", val actualTokens: Long = 0) {
    /** A timer expiring is not owner permission to retry a hard failure in periodic work. */
    fun permitsBackgroundMetadata(account: String): Boolean = blockedScope != account || ProviderFailurePolicy.NEVER_RETRY.none { it.name == blockedKind }
}
interface AIUsageStore { fun read(): AIUsageLedger; fun write(value: AIUsageLedger) }
class MemoryAIUsageStore : AIUsageStore { private var value = AIUsageLedger(); override fun read() = value; override fun write(value: AIUsageLedger) { this.value = value } }
class AIRequestRejected(val failure: AiResult.Failure) : Exception("AI request rejected locally")

/** Single-flight accounting only: no local request, token, daily or minute usage gate, and no automatic retry. */
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
    fun annotateFailure(provider: String, kind: AiErrorKind) {
        val list = mutable.value; val last = list.lastOrNull() ?: return
        if (last.provider == provider && last.error == null && clock() - last.at in 0..90000)
            mutable.value = list.dropLast(1) + last.copy(error = kind)
    }
    private fun rememberProviderFailure(account: String, kind: AiErrorKind, retryAfter: String?) {
        if (kind !in ProviderFailurePolicy.NEVER_RETRY) return
        // Only inhibits background refreshes. A new explicit owner action is not a hidden automatic retry.
        val until = retryAfter?.toLongOrNull()?.takeIf { it > 0 }?.let { clock() + it.coerceAtMost(86400) * 1000 } ?: 0
        synchronized(reservationLock) { store.write(store.read().copy(blockedScope = account, blockedUntil = until, blockedKind = kind.name)) }
    }
    suspend fun send(provider: String, request: AiHttpRequest, onEvent: ((String) -> Unit)? = null): AiHttpResponse = withContext(Dispatchers.IO) {
        mutex.withLock {
            val start = clock(); val metadata = request.method == "GET"; val account = scope()
            // Byte bounds protect parsing/memory, not user credits or provider token allowances.
            val body = request.body?.let { NativeJson.objectValue(it, 32 * 1024 * 1024) }
            val model = body?.optString("model")?.takeIf { it.length <= 240 && !AiTextSafety.containsCredential(it) }
                ?: request.url.substringAfter("/models/", "").substringBefore(':').take(160)
            val input = if (metadata) 0 else request.body.orEmpty().toByteArray(Charsets.UTF_8).size
            val out = if (metadata) 0 else body?.optJSONObject("generationConfig")?.optInt("maxOutputTokens")
                ?: body?.optInt("max_completion_tokens", 0)?.takeIf { it > 0 } ?: body?.optInt("max_tokens", 0) ?: 0
            synchronized(reservationLock) {
                val old = store.read(); val day = start / 86400000
                val sameDay = old.day == day
                store.write(old.copy(day = day, requests = (if (sameDay) old.requests else 0) + if (metadata) 0 else 1,
                    reservedTokens = 0, actualTokens = if (sameDay) old.actualTokens else 0L,
                    metadataRequests = (if (sameDay) old.metadataRequests else 0) + if (metadata) 1 else 0,
                    minute = start / 60000, minuteRequests = if (old.minute == start / 60000) old.minuteRequests + 1 else 1))
            }
            var usage: AIUsage? = null; var status: Int? = null; var error: AiErrorKind? = null; var response: AiHttpResponse? = null
            fun acceptUsage(value: AIUsage?) {
                if (value == null) return
                val i = value.input ?: usage?.input; val o = value.output ?: usage?.output
                usage = AIUsage(i, o, value.total ?: if (i != null && o != null) i + o else usage?.total)
            }
            try {
                currentCoroutineContext().ensureActive()
                val callback = onEvent?.let { downstream -> { event: String ->
                    if (event != "[DONE]") {
                        val root = runCatching { NativeJson.objectValue(event, 1024 * 1024) }.getOrNull()
                        root?.let { acceptUsage(AIUsage.from(it)) }
                        root?.optJSONObject("error")?.let { e ->
                            throw OpenRouterReplyFailure(ProviderFailurePolicy.fromError(root, e.optInt("code", 500)))
                        }
                    }
                    downstream(event)
                } }
                val reply = if (transport is OpenRouterHttp) transport.await(request, callback)
                    else if (callback != null && transport is AiStreamingTransport) transport.stream(request, callback) else transport.execute(request)
                response = reply; status = reply.status
                acceptUsage(runCatching { AIUsage.from(NativeJson.objectValue(reply.body, 16 * 1024 * 1024)) }.getOrNull())
                if (reply.status !in 200..299) {
                    error = ProviderFailurePolicy.response(reply).kind
                    rememberProviderFailure(account, requireNotNull(error), reply.retryAfter)
                }
                reply
            } catch (e: CancellationException) { error = AiErrorKind.DISABLED; throw e }
            catch (e: OpenRouterReplyFailure) { error = e.failure.kind; rememberProviderFailure(account, e.failure.kind, e.failure.retryAfterSec?.toString()); throw e }
            catch (e: SocketTimeoutException) { error = AiErrorKind.TIMEOUT; throw e }
            catch (e: IOException) { error = AiErrorKind.OFFLINE; throw e }
            catch (e: Exception) { error = AiErrorKind.BAD_REQUEST; throw e }
            finally {
                usage?.total?.let { total -> synchronized(reservationLock) {
                    val ledger = store.read(); store.write(ledger.copy(actualTokens = ledger.actualTokens + total))
                } }
                fun remaining(key: String): Int? = response?.headers?.entries?.firstOrNull { it.key.equals(key, true) }?.value?.toLongOrNull()?.takeIf { it in 0..10000000 }?.toInt()
                mutable.value = (mutable.value + AIRequestDiagnostic(provider, model, metadata, input, out, usage, status, error,
                    (clock() - start).coerceAtLeast(0), start, true, remaining("x-ratelimit-remaining-requests"), remaining("x-ratelimit-remaining-tokens"))).takeLast(30)
            }
        }
    }
    companion object { private val reservationLock = Any() }
}

object ProviderFailurePolicy {
    val NEVER_RETRY = setOf(AiErrorKind.UNAUTHORIZED, AiErrorKind.CREDIT_EXHAUSTED, AiErrorKind.RATE_LIMITED, AiErrorKind.GUARDRAIL_REJECTED)
    /** Inspect only recognised provider error codes; never surface/log the raw body or exception. */
    fun response(response: AiHttpResponse): AiResult.Failure = fromError(
        runCatching { NativeJson.objectValue(response.body, PersianAgentLimits.OUTPUT_CHARS) }.getOrNull(), response.status, response.retryAfter)
    fun fromError(root: JSONObject?, status: Int = 500, retryAfter: String? = null): AiResult.Failure {
        val error = root?.optJSONObject("error") ?: root
        val tags = listOf("code", "type", "status").map { error?.optString(it).orEmpty().lowercase(java.util.Locale.ROOT) }
        val message = error?.optString("message").orEmpty().lowercase(java.util.Locale.ROOT)
        val code = when {
            tags.any { it in setOf("insufficient_quota", "billing_hard_limit_reached", "credit_balance_too_low", "insufficient_credits") } ||
                message.contains("credit balance is too low") || message.contains("insufficient credits") -> 402
            tags.any { it in setOf("rate_limit_error", "rate_limit_exceeded", "resource_exhausted") } -> 429
            else -> status
        }
        return http(code, retryAfter)
    }
    fun http(status: Int, retryAfter: String? = null): AiResult.Failure = when (status) {
        401 -> AiResult.Failure(AiErrorKind.UNAUTHORIZED, "کلید یا مجوز حساب پذیرفته نشد.")
        402 -> AiResult.Failure(AiErrorKind.CREDIT_EXHAUSTED, "اعتبار حساب کافی نیست؛ خرید یا تکرار خودکار نداریم.")
        403, 451 -> AiResult.Failure(AiErrorKind.GUARDRAIL_REJECTED, "دسترسی حساب یا منطقه مجاز نیست.")
        429 -> AiResult.Failure(AiErrorKind.RATE_LIMITED, "سهمیه یا ظرفیت سرویس فعلاً کافی نیست؛ صبر کنید یا مدل دیگری انتخاب کنید.", retryAfter?.toIntOrNull()?.coerceIn(1, 86400))
        404, 410 -> AiResult.Failure(AiErrorKind.NOT_FOUND, "مدل در دسترس نیست؛ مدل دیگری انتخاب کنید.")
        in 500..599 -> AiResult.Failure(AiErrorKind.SERVER, "سرویس فعلاً پاسخ کامل نداد؛ می‌توانید دوباره پیام بفرستید.")
        else -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "مدل این درخواست را نپذیرفت؛ پیام یا مدل را بررسی کنید.")
    }
}
