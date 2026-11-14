package com.khodroyar.app.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** No account/key/network: deterministic request reservations and failure/usage fixtures only. */
class AIUsagePolicyTest {
    private var now = 1_794_268_800_000L
    private val post = "https://api.groq.com/openai/v1/chat/completions"
    private fun request(text: String = "سلام", output: Int = 128) = AiHttpRequest(post, "POST", emptyMap(), JSONObject()
        .put("model", "openai/gpt-oss-20b").put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", text)))
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
    private suspend fun rejected(runtime: AIRequestRuntime, request: AiHttpRequest = request()): AiResult.Failure {
        try { runtime.send("groq", request); fail("Dispatch must be rejected") } catch (e: AIRequestRejected) { return e.failure }
        error("Unreachable")
    }

    @Test fun reservesBeforeDispatchAndRecordsEstimateActualUsageLatencyAndBoundedHeaders() = runBlocking {
        val http = Http().apply { body = """{"usage":{"prompt_tokens":25,"completion_tokens":7,"total_tokens":32}}""" }
        val store = MemoryAIUsageStore(); val rt = runtime(http, store); val q = request()
        rt.send("groq", q)
        val expected = requireNotNull(q.body).toByteArray(Charsets.UTF_8).size + 128
        assertEquals(1, store.read().requests); assertEquals((expected + 128).toLong(), store.read().reservedTokens)
        val d = rt.diagnostics.value.single()
        assertEquals(expected, d.estimatedInput); assertEquals(128, d.outputLimit); assertEquals(AIUsage(25, 7, 32), d.actual)
        assertEquals(19, d.remainingRequests); assertEquals(7000, d.remainingTokens); assertTrue(d.dispatched); assertEquals(200, d.httpStatus)
        assertEquals("openai/gpt-oss-20b", d.model); assertFalse(d.metadata); assertEquals(0L, d.elapsedMs)
    }
    @Test fun inputAndOutputCeilingsRejectBeforeDispatchOrReservation() = runBlocking {
        val http = Http(); val rt = runtime(http, limits = AIUsageLimits(inputTokens = 1000, outputTokens = 128))
        assertEquals(AiErrorKind.BAD_REQUEST, rejected(rt, request("الف".repeat(600))).kind)
        assertEquals(AiErrorKind.BAD_REQUEST, rejected(rt, request(output = 129)).kind)
        assertEquals(0, http.calls); assertEquals(0, rt.ledger().requests)
        assertTrue(rt.diagnostics.value.all { !it.dispatched })
    }
    @Test fun dailyRequestBudgetSurvivesRuntimeRestartAndProviderAccountChange() = runBlocking {
        val store = MemoryAIUsageStore(); val http = Http(); val limits = AIUsageLimits(requestsPerDay = 1)
        runtime(http, store, limits).send("groq", request())
        val restarted = runtime(http, store, limits, { "a-different-owner-selected-account" })
        assertEquals(AiErrorKind.RATE_LIMITED, rejected(restarted).kind)
        assertEquals(1, http.calls); assertEquals(1, store.read().requests)
    }
    @Test fun conservativeDailyTokensAreChargedForFailuresAndNeverRefunded() = runBlocking {
        val store = MemoryAIUsageStore(); val http = Http().apply { status = 503 }
        val rt = runtime(http, store, AIUsageLimits(tokensPerDay = 1000))
        rt.send("groq", request("a".repeat(500)))
        assertTrue(store.read().reservedTokens > 700)
        assertEquals(AiErrorKind.RATE_LIMITED, rejected(rt).kind); assertEquals(1, http.calls)
    }
    @Test fun httpAuthenticationBillingQuotaAndRegionFailuresBlockMoreHttpWithoutRetries() = runBlocking {
        for ((code, kind) in listOf(401 to AiErrorKind.UNAUTHORIZED, 402 to AiErrorKind.CREDIT_EXHAUSTED,
            403 to AiErrorKind.GUARDRAIL_REJECTED, 451 to AiErrorKind.GUARDRAIL_REJECTED, 429 to AiErrorKind.RATE_LIMITED)) {
            val store = MemoryAIUsageStore(); val http = Http().apply { status = code }; val rt = runtime(http, store)
            rt.send("groq", request()); assertEquals(kind, rt.diagnostics.value.single().error)
            assertEquals(kind, rejected(runtime(http, store)).kind)
            val metadata = AiHttpRequest("https://api.groq.com/openai/v1/models", "GET", emptyMap())
            assertEquals(kind, rejected(rt, metadata).kind); assertEquals(1, http.calls)
        }
    }
    @Test fun retryAfterDoesNotScheduleARetryButAllowsLaterExplicitUserRequest() = runBlocking {
        val store = MemoryAIUsageStore(); val http = Http().apply { status = 429 }; val rt = runtime(http, store)
        rt.send("groq", request()); now += 119000
        rejected(rt); assertEquals(1, http.calls)
        now += 1000; http.status = 200; rt.send("groq", request()); assertEquals(2, http.calls)
    }
    @Test fun transportFailuresConsumeOneReservationAndAreNeverRetried() = runBlocking {
        for (error in listOf(IOException("do not expose raw private content"), SocketTimeoutException("private"))) {
            val http = Http().apply { problem = error }; val rt = runtime(http)
            try { rt.send("groq", request()); fail("Must fail") } catch (_: IOException) { }
            assertEquals(1, http.calls); assertEquals(1, rt.ledger().requests)
            assertEquals(if (error is SocketTimeoutException) AiErrorKind.TIMEOUT else AiErrorKind.OFFLINE, rt.diagnostics.value.single().error)
            assertFalse(rt.diagnostics.value.toString().contains("private"))
        }
    }
    @Test fun metadataNeverConsumesInferenceTokenBudgetButHasItsOwnHttpCountAndRateCap() = runBlocking {
        val http = Http(); val rt = runtime(http)
        repeat(12) { rt.send("groq", AiHttpRequest("https://api.groq.com/openai/v1/models", "GET", emptyMap())) }
        assertEquals(0, rt.ledger().requests); assertEquals(0L, rt.ledger().reservedTokens); assertEquals(12, rt.ledger().metadataRequests)
        assertEquals(AiErrorKind.RATE_LIMITED, rejected(rt).kind); assertEquals(12, http.calls)
        now += 60000; rt.send("groq", request()); assertEquals(13, http.calls)
    }
    @Test fun backwardsClockCannotResetBudgetsAndNextUtcDayDoesResetDailyCounts() = runBlocking {
        val store = MemoryAIUsageStore(); val http = Http(); val rt = runtime(http, store, AIUsageLimits(requestsPerDay = 1))
        rt.send("groq", request()); now -= 86400000
        assertEquals(AiErrorKind.RATE_LIMITED, rejected(rt).kind); assertEquals(1, http.calls)
        now += 2 * 86400000; rt.send("groq", request()); assertEquals(1, store.read().requests); assertEquals(2, http.calls)
    }
    @Test fun concurrentRuntimesCannotRaceInstallWideReservations() = runBlocking {
        val store = MemoryAIUsageStore(); val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = AiHttpTransport { calls.incrementAndGet(); AiHttpResponse(200, "{}") }
        val limit = AIUsageLimits(requestsPerDay = 1)
        coroutineScope { (1..12).map { async { try { runtime(http, store, limit).send("groq", request()); true } catch (_: AIRequestRejected) { false } } }.awaitAll() }
        assertEquals(1, calls.get()); assertEquals(1, store.read().requests)
    }
    @Test fun streamQuotaErrorIsDurablyBlockedAndFinalUsageCanBeReadWithoutTextRetention() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = object : AiHttpTransport, AiStreamingTransport {
            override fun execute(request: AiHttpRequest) = error("Only stream expected")
            override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse {
                calls.incrementAndGet(); onEvent("""{"error":{"code":429,"message":"RAW-PRIVATE-ERROR"}}""")
                return AiHttpResponse(200, "")
            }
        }
        val store = MemoryAIUsageStore(); val rt = runtime(http, store)
        try { rt.send("groq", request()) { }; fail("Stream must stop") } catch (e: OpenRouterReplyFailure) { assertEquals(AiErrorKind.RATE_LIMITED, e.failure.kind) }
        assertEquals(AiErrorKind.RATE_LIMITED, rt.diagnostics.value.single().error)
        rejected(runtime(http, store)); assertEquals(1, calls.get()); assertFalse(rt.diagnostics.value.toString().contains("PRIVATE"))
    }
    @Test fun actualUsageSupportsGeminiAndGroqButNeverInventsFractionalNegativeOrMissingUsage() {
        assertEquals(AIUsage(20, 3, 24), AIUsage.from(JSONObject("""{"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":3,"totalTokenCount":24}}""")))
        assertEquals(AIUsage(2, 5, 7), AIUsage.from(JSONObject("""{"x_groq":{"usage":{"prompt_tokens":2,"completion_tokens":5}}}""")))
        assertNull(AIUsage.from(JSONObject("{}")))
        assertNull(AIUsage.from(JSONObject("""{"usage":{"prompt_tokens":-1,"completion_tokens":0.5,"total_tokens":1000000000}}""")))
    }
    @Test fun providerReportedOverrunStopsFurtherRequestsAndIsNeverRefusedFromDiagnostics() = runBlocking {
        val http = Http().apply { body = """{"usage":{"prompt_tokens":300,"completion_tokens":1000,"total_tokens":1300}}""" }
        val rt = runtime(http)
        try { rt.send("groq", request()); fail("Overrun cannot produce an executable reply") }
        catch (e: OpenRouterReplyFailure) { assertEquals(AiErrorKind.GUARDRAIL_REJECTED, e.failure.kind) }
        assertTrue(rt.ledger().reservedTokens >= 1300)
        assertEquals(AiErrorKind.GUARDRAIL_REJECTED, rt.diagnostics.value.single().error)
        assertEquals(1300, rt.diagnostics.value.single().actual?.total)
        assertEquals(AiErrorKind.GUARDRAIL_REJECTED, rejected(rt).kind); assertEquals(1, http.calls)
    }
    @Test fun diagnosticsAreBoundedAndWireErrorsCanBeAnnotatedWithoutRecordingBodies() = runBlocking {
        val rt = runtime(Http(), limits = AIUsageLimits(requestsPerDay = 100, tokensPerDay = 200000))
        repeat(35) { now += 60000; rt.send("groq", request()) }
        assertEquals(30, rt.diagnostics.value.size)
        rt.annotateFailure("groq", AiErrorKind.MODEL_INVALID)
        assertEquals(AiErrorKind.MODEL_INVALID, rt.diagnostics.value.last().error)
        assertFalse(rt.diagnostics.value.toString().contains("سلام"))
    }
}
