package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Fixed official REST/SSE fixtures only: these tests spend no credits and prove no live account/region. */
class DirectModelAdapterTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
    private val groqModel = "openai/gpt-oss-20b"
    private val geminiModel = "gemini-2.5-flash-lite"
    private val cfModel = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    private val account = "a".repeat(32)
    private fun key(mode: AiMode) = when (mode) { AiMode.GEMINI -> "AIza" + "x".repeat(35); AiMode.GROQ -> "gsk_" + "y".repeat(48); else -> "z".repeat(40) }
    private fun settings(mode: AiMode) = AiSettings(mode = mode, consented = true, modelSelectionMode = AISelectionMode.MANUAL,
        useRegion = AIUseRegion.SUPPORTED_ELSEWHERE, freeAccountConfirmed = setOf(AIProviderRegistry.get(mode).id), cloudflareAccountId = account)
        .withModel(mode, when (mode) { AiMode.GROQ -> groqModel; AiMode.GEMINI -> geminiModel; else -> cfModel })
    private class Http(val handler: (AiHttpRequest) -> AiHttpResponse) : AiHttpTransport {
        val requests = mutableListOf<AiHttpRequest>()
        override fun execute(request: AiHttpRequest): AiHttpResponse { requests += request; return handler(request) }
    }
    private fun metadata(mode: AiMode): String = when (mode) {
        AiMode.GROQ -> JSONObject().put("data", JSONArray().put(JSONObject().put("id", groqModel).put("active", true).put("context_window", 131072))).toString()
        AiMode.GEMINI -> JSONObject().put("models", JSONArray().put(JSONObject().put("name", "models/$geminiModel").put("version", "001")
            .put("inputTokenLimit", 1048576).put("supportedGenerationMethods", JSONArray(listOf("generateContent"))))).toString()
        else -> JSONObject().put("success", true).put("result", JSONArray().put(JSONObject().put("id", "7aa2a0a0-0000-4444-8888-aaaaaaaaaaaa").put("name", cfModel))).toString()
    }
    private fun subscriptions(plan: String = "Workers Free") = JSONObject().put("success", true).put("result", JSONArray().put(JSONObject()
        .put("rate_plan", JSONObject().put("public_name", plan)))).toString()
    private fun gemini(text: String = "سلام", version: String = geminiModel, finish: String = "STOP") = JSONObject().put("modelVersion", version)
        .put("candidates", JSONArray().put(JSONObject().put("index", 0).put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))))
            .put("finishReason", finish))).put("usageMetadata", JSONObject().put("promptTokenCount", 10).put("candidatesTokenCount", 2).put("totalTokenCount", 12)).toString()
    private fun adapter(mode: AiMode, http: AiHttpTransport, s: () -> AiSettings = { settings(mode) }, runtime: AIRequestRuntime = AIRequestRuntime(http, { s().usage }, { s().modelAccountScope }, clock = { now })) =
        DirectModelAdapter(mode, s, object : AiApiKeyReader { override fun read() = key(mode) }, runtime, { now })
    private val messages = listOf(FunctionMessage("system", "فارسی و کوتاه"), FunctionMessage("user", "سلام"))

    @Test fun groqUsesOnlySelectedOfficialModelNativeToolsAndOneInferenceNoSdkRetries() = runBlocking {
        val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, metadata(AiMode.GROQ))
            else AiHttpResponse(200, OpenRouterFixture.completion(model = groqModel)) }
        val a = adapter(AiMode.GROQ, http)
        val found = a.discover(true) as AIModelDiscovery.Found
        assertEquals(listOf(groqModel), found.models.map { it.key.id }); assertTrue(found.models.single().freeQuotaEligible)
        val reply = a.infer(found.models.single().key, AIModelRequest(messages, emptyList(), 256, false)) as FunctionResult.Reply
        assertEquals(groqModel, reply.model); assertEquals(listOf("GET", "POST"), http.requests.map { it.method })
        val post = http.requests.last(); val body = JSONObject(requireNotNull(post.body))
        assertEquals("https://api.groq.com/openai/v1/chat/completions", post.url)
        assertEquals(256, body.getInt("max_completion_tokens")); assertFalse(body.has("max_tokens")); assertFalse(body.has("tools")); assertFalse(body.has("models"))
        assertEquals("Bearer ${key(AiMode.GROQ)}", post.headers["Authorization"]); assertFalse(post.body.orEmpty().contains(key(AiMode.GROQ)))
    }
    @Test fun geminiHasNativeSystemContentsAndUsageAndNeverReceivesRealAppTools() = runBlocking {
        val http = Http { q -> AiHttpResponse(200, if (q.method == "GET") metadata(AiMode.GEMINI) else gemini()) }
        val rt = AIRequestRuntime(http, clock = { now }); val a = adapter(AiMode.GEMINI, http, runtime = rt)
        val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
        val result = a.infer(m.key, AIModelRequest(messages, emptyList(), 256, false)) as FunctionResult.Reply
        assertEquals("سلام", result.message.content); assertEquals(geminiModel, result.model)
        val post = http.requests.last(); val body = JSONObject(requireNotNull(post.body))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent", post.url)
        assertEquals(key(AiMode.GEMINI), post.headers["x-goog-api-key"]); assertFalse(post.url.contains(key(AiMode.GEMINI)))
        assertTrue(body.has("systemInstruction")); assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertEquals(256, body.getJSONObject("generationConfig").getInt("maxOutputTokens")); assertFalse(body.has("tools"))
        assertEquals(AIUsage(10, 2, 12), rt.diagnostics.value.last().actual)
        assertTrue(a.infer(m.key, AIModelRequest(messages, AgentToolRegistry.tools, 256, false)) is FunctionResult.Failed)
        val withData = messages + FunctionMessage("tool", "PRIVATE-REAL-RECORD", toolCallId = "record")
        assertTrue(a.infer(m.key, AIModelRequest(withData, emptyList(), 256, false)) is FunctionResult.Failed)
        assertEquals(1, http.requests.count { it.method == "POST" }); assertFalse(http.requests.any { it.body.orEmpty().contains("PRIVATE-REAL") })
    }
    @Test fun cloudflareRequiresPositiveFreePlanEvidenceAndUsesModelNameNotOpaqueModelUuid() = runBlocking {
        val http = Http { q -> AiHttpResponse(200, when { q.url.endsWith("subscriptions") -> subscriptions(); q.method == "GET" -> metadata(AiMode.CLOUDFLARE)
            else -> OpenRouterFixture.completion(model = cfModel) }) }
        val a = adapter(AiMode.CLOUDFLARE, http); val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
        assertEquals(cfModel, m.key.id); assertEquals(24000, m.capability.contextLength)
        assertTrue(a.infer(m.key, AIModelRequest(messages, emptyList(), 128, false)) is FunctionResult.Reply)
        assertEquals("https://api.cloudflare.com/client/v4/accounts/$account/ai/v1/chat/completions", http.requests.last().url)
        assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun cloudflarePaidUnknownMissingOrUnreadableSubscriptionsNeverDispatchInference() = runBlocking {
        for (plans in listOf(subscriptions("Workers Paid"), "{\"success\":true,\"result\":[]}", "{\"success\":false}", subscriptions("unrecognised"))) {
            val http = Http { AiHttpResponse(200, plans) }; val a = adapter(AiMode.CLOUDFLARE, http)
            assertTrue(a.discover(true) is AIModelDiscovery.Failed)
            assertTrue(a.infer(AIModelKey("cloudflare", cfModel), AIModelRequest(messages, emptyList())) is FunctionResult.Failed)
            assertTrue(http.requests.all { it.method == "GET" })
        }
    }
    @Test fun noConsentUnconfirmedFreePlanUnknownRegionIranAndExpiredPolicySendNothing() = runBlocking {
        for (mode in listOf(AiMode.GROQ, AiMode.GEMINI, AiMode.CLOUDFLARE)) {
            val valid = settings(mode)
            for (s in listOf(valid.copy(consented = false), valid.copy(enabled = false), valid.copy(useRegion = AIUseRegion.UNKNOWN),
                valid.copy(useRegion = AIUseRegion.IRAN), valid.copy(freeAccountConfirmed = emptySet()), valid.copy(mode = AiMode.LOCAL))) {
                val http = Http { error("No HTTP allowed") }
                assertTrue(adapter(mode, http, { s }).discover(true) is AIModelDiscovery.Failed); assertTrue(http.requests.isEmpty())
            }
            assertNotNull(AIProviderRegistry.barrier(valid, AIProviderRegistry.POLICY_EXPIRES_AT))
        }
    }
    @Test fun newOrPaidUnlistedModelIdsCannotBeInferredEvenIfReturnedByModelsApi() = runBlocking {
        val unknown = metadata(AiMode.GROQ).replace(groqModel, "unknown/expensive-model")
        val http = Http { AiHttpResponse(200, unknown) }; val a = adapter(AiMode.GROQ, http)
        assertTrue((a.discover(true) as AIModelDiscovery.Found).models.isEmpty())
        assertTrue(a.infer(AIModelKey("groq", "unknown/expensive-model"), AIModelRequest(messages, emptyList())) is FunctionResult.Failed)
        assertTrue(http.requests.all { it.method == "GET" })
    }
    @Test fun directQuotaBillingAuthAndRegionErrorsMakeOnePostAndNoOtherProviderAttempt() = runBlocking {
        for (code in listOf(401, 402, 403, 429, 451)) {
            val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, metadata(AiMode.GROQ)) else AiHttpResponse(code, "PRIVATE-RAW-ERROR") }
            val a = adapter(AiMode.GROQ, http); val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
            val failed = a.infer(m.key, AIModelRequest(messages, emptyList(), 256, false)) as FunctionResult.Failed
            assertEquals(ProviderFailurePolicy.http(code).kind, failed.error.kind); assertFalse(failed.error.message.contains("PRIVATE"))
            assertTrue(a.infer(m.key, AIModelRequest(messages, emptyList(), 256, false)) is FunctionResult.Failed)
            assertEquals(1, http.requests.count { it.method == "POST" }); assertTrue(http.requests.all { it.url.startsWith("https://api.groq.com/") })
        }
    }
    @Test fun selectedDispatcherNeverRoutesFailureToAnotherProviderOrAcceptsOldProviderIdentity() = runBlocking {
        val otherCalls = java.util.concurrent.atomic.AtomicInteger()
        val http = Http { AiHttpResponse(401, "private") }; val groq = adapter(AiMode.GROQ, http)
        val gemini = adapter(AiMode.GEMINI, Http { otherCalls.incrementAndGet(); error("Must not route here") })
        val selected = SelectedAIModelAdapter({ AiMode.GROQ }, mapOf(AiMode.GROQ to groq, AiMode.GEMINI to gemini))
        assertTrue(selected.discover(true) is AIModelDiscovery.Failed)
        assertTrue(selected.infer(AIModelKey("gemini", geminiModel), AIModelRequest(messages, emptyList())) is FunctionResult.Failed)
        assertEquals(0, otherCalls.get()); assertEquals(1, http.requests.size)
    }
    @Test fun providerChangeDuringHttpDropsOldResponseAndClearsPartialText() = runBlocking {
        var s = settings(AiMode.GROQ)
        val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, metadata(AiMode.GROQ)) else {
            s = s.copy(mode = AiMode.GEMINI); AiHttpResponse(200, OpenRouterFixture.completion(model = groqModel))
        } }
        val a = adapter(AiMode.GROQ, http, { s }); val m = (a.discover(true) as AIModelDiscovery.Found).models.single(); val text = mutableListOf<String>()
        assertTrue(a.infer(m.key, AIModelRequest(messages, emptyList(), 128, false)) { text += it } is FunctionResult.Failed)
        assertEquals("", text.last()); assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun geminiStreamingRejectsSubstitutedTruncatedBlockedOrUnsupportedPartsAndRequiresFinish() {
        for (bad in listOf(gemini(version = "paid/unselected"), gemini(finish = "MAX_TOKENS"),
            "{\"modelVersion\":\"$geminiModel\",\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
            gemini().replace("\"text\":\"سلام\"", "\"executableCode\":{\"code\":\"private\"}"))) {
            assertTrue(runCatching { GeminiWire.completion(bad, geminiModel, setOf(geminiModel)) }.isFailure)
        }
        val partial = GeminiTextStream(geminiModel, setOf(geminiModel), {})
        val unfinished = JSONObject(gemini()); unfinished.getJSONArray("candidates").getJSONObject(0).remove("finishReason")
        partial.accept(unfinished.toString())
        assertTrue(runCatching { partial.result() }.isFailure)
        val good = GeminiTextStream(geminiModel, setOf(geminiModel), {})
        good.accept(gemini()); good.accept("{\"usageMetadata\":{\"totalTokenCount\":12},\"candidates\":[]}")
        assertEquals("سلام", good.result().message.content)
    }
    @Test fun nativeGeminiDummyFunctionCallPreservesSignatureAndNeverAutomaticallyExecutesIt() {
        val body = """{"modelVersion":"$geminiModel","candidates":[{"content":{"parts":[{"functionCall":{"name":"get_jalali_calendar","args":{"year":1404}},"thoughtSignature":"synthetic-signature"}]},"finishReason":"STOP"}]}"""
        val reply = GeminiWire.completion(body, geminiModel, setOf(geminiModel))
        val call = reply.message.calls.single(); assertEquals("synthetic-signature", call.providerSignature)
        val wire = GeminiWire.request(AIModelRequest(messages + reply.message + FunctionMessage("tool", "{\"days\":365}", toolCallId = call.id), listOf(AgentToolRegistry.tools.first()), 384, false))
        val replayed = wire.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals("synthetic-signature", replayed.getString("thoughtSignature"))
        assertTrue(wire.getJSONArray("contents").getJSONObject(2).getJSONArray("parts").getJSONObject(0).has("functionResponse"))
    }
    @Test fun officialEndpointAllowlistRejectsCustomHostsRedirectTargetsAccountPathsAndPaidProviderEndpoints() {
        for (url in listOf("https://api.groq.com.evil.example/openai/v1/chat/completions", "http://api.groq.com/openai/v1/models", "https://api.groq.com/openai/v1/chat/completions?key=secret",
            "https://api.cloudflare.com/client/v4/accounts/../subscriptions", "https://generativelanguage.googleapis.com/v1beta/models/unknown:generateContent", "https://api.avalai.ir/v1/chat/completions")) {
            assertFalse(AIProviderRegistry.permittedEndpoint(AiHttpRequest(url, "POST", body = "{}")))
        }
        assertFalse(AIProviderRegistry.permittedEndpoint(AiHttpRequest("https://api.groq.com/openai/v1/models", "POST", body = "{}")))
    }
    @Test fun paymentRequiredAndUnverifiedProvidersStayVisibleButHaveNoActiveAdapter() {
        for (mode in listOf(AiMode.CEREBRAS, AiMode.HUGGING_FACE, AiMode.AVALAI)) {
            val descriptor = AIProviderRegistry.get(mode); assertFalse(descriptor.adapterImplemented)
            assertNotNull(AIProviderRegistry.barrier(settings(mode), now)); assertTrue(descriptor.sources.isNotEmpty())
        }
    }
}
