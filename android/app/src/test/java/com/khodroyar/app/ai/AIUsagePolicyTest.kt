package com.khodroyar.app.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Mock transport only: accounting must never turn back into an app quota or implicit retry engine. */
class AIUsagePolicyTest {
    private var now = 1_794_268_800_000L
    private fun request(text: String = "سلام", output: Int? = null) = AiHttpRequest("https://api.groq.com/openai/v1/chat/completions", "POST", emptyMap(), JSONObject()
        .put("model", "openai/gpt-oss-20b").put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        .put("max_completion_tokens", output).toString())
    private class Http : AiHttpTransport {
        var calls = 0; var status = 200; var body = "{}"; var problem: Exception? = null
        override fun execute(request: AiHttpRequest): AiHttpResponse {
            calls++; problem?.let { throw it }
            return AiHttpResponse(status, body, "120", mapOf("X-RateLimit-Remaining-Requests" to "19", "x-ratelimit-remaining-tokens" to "7000"))
        }
    }
    private fun runtime(http: AiHttpTransport, store: AIUsageStore = MemoryAIUsageStore(), limits: AIUsageLimits = AIUsageLimits(), scope: () -> String = { "account" }) =
        AIRequestRuntime(http, { limits }, scope, store, { now })

    @Test fun recordsBytesActualTokensLatencyAndOnlyAllowlistedQuotaHeadersWithoutReservingTokens() = runBlocking {
        val http = Http().apply { body = """{"usage":{"prompt_tokens":25,"completion_tokens":7,"total_tokens":32}}""" }
        val store = MemoryAIUsageStore(); val rt = runtime(http, store); val q = request(output = 128)
        rt.send("groq", q)
        assertEquals(1, store.read().requests); assertEquals(0L, store.read().reservedTokens); assertEquals(32L, store.read().actualTokens)
        val d = rt.diagnostics.value.single()
        assertEquals(requireNotNull(q.body).toByteArray().size, d.estimatedInput); assertEquals(128, d.outputLimit); assertEquals(AIUsage(25, 7, 32), d.actual)
        assertEquals(19, d.remainingRequests); assertEquals(7000, d.remainingTokens); assertTrue(d.dispatched); assertEquals(200, d.httpStatus)
        assertEquals("openai/gpt-oss-20b", d.model); assertFalse(d.metadata); assertEquals(0L, d.elapsedMs)
    }
    @Test fun retiredInputAndOutputPreferencesCannotRejectOrTruncateARequest() = runBlocking {
        val http = Http(); val rt = runtime(http, limits = AIUsageLimits(inputTokens = 1, outputTokens = 1))
        rt.send("groq", request("الف".repeat(20_000), 65536))
        rt.send("groq", request("پیام بعدی"))
        assertEquals(2, http.calls); assertTrue(rt.diagnostics.value.all { it.dispatched })
        assertEquals(0, rt.diagnostics.value.last().outputLimit)
    }
    @Test fun dailyRequestAndMinuteGatesDoNotReappearAcrossRestartsOrAccounts() = runBlocking {
        val store = MemoryAIUsageStore(); val http = Http(); val limits = AIUsageLimits(requestsPerDay = 1, requestsPerTurn = 1)
        repeat(45) { runtime(http, store, limits, { "owner-$it" }).send("groq", request()) }
        assertEquals(45, http.calls); assertEquals(45, store.read().requests)
    }
    @Test fun failedRequestsAreCountedButNeverCreateAnArtificialTokenBalance() = runBlocking {
        val store = MemoryAIUsageStore(); val http = Http().apply { status = 503 }
        val rt = runtime(http, store, AIUsageLimits(tokensPerDay = 1))
        repeat(3) { assertEquals(503, rt.send("groq", request("a".repeat(5000))).status) }
        assertEquals(3, http.calls); assertEquals(0L, store.read().reservedTokens); assertEquals(0L, store.read().actualTokens)
    }
    @Test fun hardProviderFailuresMakeExactlyOneAttemptAndDoNotCreateAnAppRateLimitForNewExplicitActions() = runBlocking {
        for ((code, kind) in listOf(401 to AiErrorKind.UNAUTHORIZED, 402 to AiErrorKind.CREDIT_EXHAUSTED,
            403 to AiErrorKind.GUARDRAIL_REJECTED, 451 to AiErrorKind.GUARDRAIL_REJECTED, 429 to AiErrorKind.RATE_LIMITED)) {
            val store = MemoryAIUsageStore(); val http = Http().apply { status = code }; val rt = runtime(http, store)
            rt.send("groq", request()); assertEquals(1, http.calls); assertEquals(kind, rt.diagnostics.value.single().error)
            assertFalse(rt.ledger().permitsBackgroundMetadata("account"))
            http.status = 200
            runtime(http, store).send("groq", request("درخواست صریح تازه"))
            assertEquals(2, http.calls)
        }
    }
    @Test fun periodicMetadataCannotRetryAHardFailureAfterClockRolloverOrRestart() = runBlocking {
        for (code in listOf(401, 402, 403, 451, 429)) {
            val store = MemoryAIUsageStore(); val http = Http().apply { status = code }
            runtime(http, store).send("groq", request()); now += 86400000
            assertFalse(runtime(http, store).ledger().permitsBackgroundMetadata("account"))
            assertTrue(store.read().permitsBackgroundMetadata("explicit-new-account")); assertEquals(1, http.calls)
        }
    }
    @Test fun retryAfterIsProviderEvidenceNotAnAppTimerOrAutomaticRetry() = runBlocking {
        val http = Http().apply { status = 429 }; val rt = runtime(http)
        rt.send("groq", request()); assertEquals(1, http.calls)
        assertEquals(120, ProviderFailurePolicy.http(429, "120").retryAfterSec)
        now += 86400000; assertEquals(1, http.calls)
        http.status = 200; rt.send("groq", request()); assertEquals(2, http.calls)
    }
    @Test fun transportFailuresNeverRetryOrLeakRawExceptions() = runBlocking {
        for (error in listOf(IOException("RAW PRIVATE DATA"), SocketTimeoutException("RAW PRIVATE DATA"))) {
            val http = Http().apply { problem = error }; val rt = runtime(http)
            try { rt.send("groq", request()); fail("Must fail") } catch (_: IOException) { }
            assertEquals(1, http.calls); assertEquals(1, rt.ledger().requests)
            assertEquals(if (error is SocketTimeoutException) AiErrorKind.TIMEOUT else AiErrorKind.OFFLINE, rt.diagnostics.value.single().error)
            assertFalse(rt.diagnostics.value.toString().contains("PRIVATE"))
        }
    }
    @Test fun metadataAndInferenceHaveNoLocalHttpRateCap() = runBlocking {
        val http = Http(); val rt = runtime(http)
        repeat(40) { rt.send("groq", AiHttpRequest("https://api.groq.com/openai/v1/models", "GET", emptyMap())) }
        rt.send("groq", request())
        assertEquals(41, http.calls); assertEquals(40, rt.ledger().metadataRequests); assertEquals(1, rt.ledger().requests)
    }
    @Test fun backwardsClockDoesNotBlockNewMessagesAndCountersAreLabelledByUtcDay() = runBlocking {
        val http = Http(); val rt = runtime(http, limits = AIUsageLimits(requestsPerDay = 1))
        rt.send("groq", request()); now -= 86400000; rt.send("groq", request())
        now += 2 * 86400000; rt.send("groq", request())
        assertEquals(3, http.calls); assertEquals(1, rt.ledger().requests)
    }
    @Test fun concurrentExplicitRuntimesAreAccountedWithoutAHiddenSharedAllowance() = runBlocking {
        val store = MemoryAIUsageStore(); val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = AiHttpTransport { calls.incrementAndGet(); AiHttpResponse(200, "{}") }
        coroutineScope { (1..20).map { async { runtime(http, store, AIUsageLimits(requestsPerDay = 1)).send("groq", request()) } }.awaitAll() }
        assertEquals(20, calls.get()); assertEquals(20, store.read().requests)
    }
    @Test fun streamQuotaErrorStopsThatFlightSuppressesBackgroundAndKeepsNoRawData() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = object : AiHttpTransport, AiStreamingTransport {
            override fun execute(request: AiHttpRequest) = error("Only stream expected")
            override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse {
                calls.incrementAndGet(); onEvent("""{"error":{"code":429,"message":"RAW PRIVATE DATA"}}""")
                return AiHttpResponse(200, "")
            }
        }
        val rt = runtime(http)
        try { rt.send("groq", request()) { }; fail("Must stop") } catch (e: OpenRouterReplyFailure) { assertEquals(AiErrorKind.RATE_LIMITED, e.failure.kind) }
        assertEquals(1, calls.get()); assertFalse(rt.ledger().permitsBackgroundMetadata("account"))
        assertFalse(rt.diagnostics.value.toString().contains("PRIVATE"))
    }
    @Test fun usageSupportsGeminiGroqAndAnthropicWithoutInventingMissingOrMalformedNumbers() {
        assertEquals(AIUsage(20, 3, 24), AIUsage.from(JSONObject("""{"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":3,"totalTokenCount":24}}""")))
        assertEquals(AIUsage(2, 5, 7), AIUsage.from(JSONObject("""{"x_groq":{"usage":{"prompt_tokens":2,"completion_tokens":5}}}""")))
        assertEquals(AIUsage(20, 0, 20), AIUsage.from(JSONObject("""{"message":{"usage":{"input_tokens":20,"output_tokens":0}}}""")))
        assertNull(AIUsage.from(JSONObject("{}")))
        assertNull(AIUsage.from(JSONObject("""{"usage":{"prompt_tokens":-1,"completion_tokens":0.5,"total_tokens":1000000000}}""")))
    }
    @Test fun actualProviderUsageAboveRetiredOutputPreferencesIsRecordedNotRejected() = runBlocking {
        val http = Http().apply { body = """{"usage":{"prompt_tokens":300,"completion_tokens":1000,"total_tokens":1300}}""" }
        val rt = runtime(http, limits = AIUsageLimits(outputTokens = 128, tokensPerDay = 1))
        repeat(2) { assertEquals(200, rt.send("groq", request()).status) }
        assertEquals(2600L, rt.ledger().actualTokens); assertTrue(rt.diagnostics.value.all { it.error == null && it.actual?.total == 1300 })
    }
    @Test fun diagnosticsAreMemoryBoundedAndContainNoMessagesImagesOrCredentials() = runBlocking {
        val rt = runtime(Http())
        repeat(35) { rt.send("groq", request("PRIVATE USER MESSAGE")) }
        assertEquals(30, rt.diagnostics.value.size)
        rt.annotateFailure("groq", AiErrorKind.MODEL_INVALID)
        assertEquals(AiErrorKind.MODEL_INVALID, rt.diagnostics.value.last().error)
        assertFalse(rt.diagnostics.value.toString().contains("PRIVATE"))
    }
    @Test fun nativeProviderCreditCodesAreHardFailuresEvenWhenHttpUses400Or429() {
        for ((code, body) in listOf(400 to """{"error":{"type":"invalid_request_error","message":"Your credit balance is too low. RAW PRIVATE"}}""",
            429 to """{"error":{"code":"insufficient_quota","message":"RAW PRIVATE"}}""")) {
            val error = ProviderFailurePolicy.response(AiHttpResponse(code, body))
            assertEquals(AiErrorKind.CREDIT_EXHAUSTED, error.kind); assertTrue(error.kind in ProviderFailurePolicy.NEVER_RETRY)
            assertFalse(error.message.contains("PRIVATE"))
        }
    }
}
