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
    private fun key(mode: AiMode) = when (mode) { AiMode.GROQ -> "gsk_" + "y".repeat(48); else -> "z".repeat(40) }
    private fun settings(mode: AiMode) = AiSettings(mode = mode, consented = true, modelSelectionMode = AISelectionMode.MANUAL,
        useRegion = AIUseRegion.SUPPORTED_ELSEWHERE, freeAccountConfirmed = setOf(AIProviderRegistry.get(mode).id))
        .withModel(mode, groqModel)
    private class Http(val handler: (AiHttpRequest) -> AiHttpResponse) : AiHttpTransport {
        val requests = mutableListOf<AiHttpRequest>()
        override fun execute(request: AiHttpRequest): AiHttpResponse { requests += request; return handler(request) }
    }
    @Suppress("UNUSED_PARAMETER")
    private fun metadata(mode: AiMode): String =
        JSONObject().put("data", JSONArray().put(JSONObject().put("id", groqModel).put("active", true).put("context_window", 131072))).toString()
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
    @Test fun groqVisionModelReceivesRealImagePixelsInInstructModeWhileTextModelsStayTextOnly() = runBlocking {
        val vision = "qwen/qwen3.8-27b"
        val list = JSONObject().put("data", JSONArray().put(JSONObject().put("id", groqModel).put("context_window", 131072))
            .put(JSONObject().put("id", vision).put("context_window", 131072))).toString()
        val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, list) else AiHttpResponse(200, OpenRouterFixture.completion(model = vision)) }
        val a = adapter(AiMode.GROQ, http)
        val models = (a.discover(true) as AIModelDiscovery.Found).models.associateBy { it.key.id }
        assertFalse(models.getValue(groqModel).capability.imageInput); assertTrue(models.getValue(vision).capability.imageInput)
        val image = AIImage("image/jpeg", "/9j/4AAQSkZJRg==")
        val withImage = listOf(messages[0], messages[1].copy(images = listOf(image)))
        assertTrue(a.infer(models.getValue(groqModel).key, AIModelRequest(withImage, emptyList(), 256, false)) is FunctionResult.Failed)
        assertEquals(1, http.requests.size) // the text-only model never receives the image
        assertTrue(a.infer(models.getValue(vision).key, AIModelRequest(withImage, emptyList(), 256, false)) is FunctionResult.Reply)
        val body = JSONObject(requireNotNull(http.requests.last().body))
        assertEquals("none", body.getString("reasoning_effort"))
        assertTrue(body.toString().contains("data:image/jpeg;base64,/9j/4AAQSkZJRg=="))
    }
    @Test fun groqReceivesNativeToolSchemasAndReportsUsageWithoutLeakingTheKey() = runBlocking {
        val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, metadata(AiMode.GROQ)) else AiHttpResponse(200, OpenRouterFixture.completion(model = groqModel)) }
        val rt = AIRequestRuntime(http, clock = { now }); val a = adapter(AiMode.GROQ, http, runtime = rt)
        val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
        assertTrue(a.infer(m.key, AIModelRequest(messages, AgentToolRegistry.tools, 256, false)) is FunctionResult.Reply)
        val body = JSONObject(requireNotNull(http.requests.last().body))
        assertTrue(body.has("tools")); assertEquals("auto", body.getString("tool_choice"))
        assertFalse(body.toString().contains(key(AiMode.GROQ)))
        assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun retiredConnectionsAreReadBackAsTheOfflineAssistantAndOnlyThreeSystemsRemain() {
        assertEquals(listOf(AiMode.LOCAL, AiMode.OPENROUTER, AiMode.GROQ), AIProviderRegistry.supportedModes)
        assertEquals(listOf(AiMode.LOCAL, AiMode.OPENROUTER, AiMode.GROQ), AiMode.entries.toList())
        for (old in listOf("ONLINE", "GAPGPT_DIRECT", "GEMINI", "CLOUDFLARE", "CEREBRAS", "HUGGING_FACE", "AVALAI", "CLAUDE", "FIREWORKS", "GPT", "SOMETHING_NEW")) {
            assertEquals(old, AiMode.LOCAL, AiMode.fromStored(old, absent = AiMode.OPENROUTER))
        }
        assertEquals(AiMode.OPENROUTER, AiMode.fromStored(null, absent = AiMode.OPENROUTER))
        assertEquals(AiMode.GROQ, AiMode.fromStored("GROQ", absent = AiMode.OPENROUTER))
        assertEquals(AiMode.OPENROUTER, AiMode.fromStored("OPENROUTER", absent = AiMode.LOCAL))
        assertTrue(runCatching { DirectModelAdapter(AiMode.OPENROUTER, { settings(AiMode.GROQ) }, { key(AiMode.GROQ) }, AIRequestRuntime(Http { error("never") })) }.isFailure)
        assertNull(AIProviderRegistry.normalizeKey(AiMode.LOCAL, "gsk_" + "y".repeat(48)))
    }
    @Test fun noConsentUnconfirmedFreePlanUnknownRegionIranAndExpiredPolicySendNothing() = runBlocking {
        for (mode in listOf(AiMode.GROQ)) {
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
            assertEquals(1, http.requests.count { it.method == "POST" }); assertTrue(http.requests.all { it.url.startsWith("https://api.groq.com/") })
        }
    }
    @Test fun selectedDispatcherNeverRoutesFailureToAnotherProviderOrAcceptsOldProviderIdentity() = runBlocking {
        val otherCalls = java.util.concurrent.atomic.AtomicInteger()
        val http = Http { AiHttpResponse(401, "private") }; val groq = adapter(AiMode.GROQ, http)
        val openRouter = object : AIModelAdapter {
            override val providerId = "openrouter"
            override suspend fun discover(force: Boolean): AIModelDiscovery { otherCalls.incrementAndGet(); error("Must not route here") }
            override suspend fun availability(model: AIModel, force: Boolean): AIModelAvailability { otherCalls.incrementAndGet(); error("Must not route here") }
            override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult { otherCalls.incrementAndGet(); error("Must not route here") }
            override fun invalidate() = Unit
        }
        val selected = SelectedAIModelAdapter({ AiMode.GROQ }, mapOf(AiMode.GROQ to groq, AiMode.OPENROUTER to openRouter))
        assertTrue(selected.discover(true) is AIModelDiscovery.Failed)
        assertTrue(selected.infer(AIModelKey("openrouter", "openai/gpt-oss-20b:free"), AIModelRequest(messages, emptyList())) is FunctionResult.Failed)
        assertEquals(0, otherCalls.get()); assertEquals(1, http.requests.size)
    }
    @Test fun providerChangeDuringHttpDropsOldResponseAndClearsPartialText() = runBlocking {
        var s = settings(AiMode.GROQ)
        val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, metadata(AiMode.GROQ)) else {
            s = s.copy(mode = AiMode.OPENROUTER); AiHttpResponse(200, OpenRouterFixture.completion(model = groqModel))
        } }
        val a = adapter(AiMode.GROQ, http, { s }); val m = (a.discover(true) as AIModelDiscovery.Found).models.single(); val text = mutableListOf<String>()
        assertTrue(a.infer(m.key, AIModelRequest(messages, emptyList(), 128, false)) { text += it } is FunctionResult.Failed)
        assertEquals("", text.last()); assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun officialEndpointAllowlistRejectsCustomHostsRedirectTargetsAccountPathsAndPaidProviderEndpoints() {
        for (url in listOf("https://api.groq.com.evil.example/openai/v1/chat/completions", "http://api.groq.com/openai/v1/models", "https://api.groq.com/openai/v1/chat/completions?key=secret",
            "https://api.cloudflare.com/client/v4/accounts/../subscriptions", "https://generativelanguage.googleapis.com/v1beta/models/unknown:generateContent", "https://api.avalai.ir/v1/chat/completions",
            "https://api.openai.com/v1/chat/completions", "https://api.anthropic.com/v1/messages", "https://api.fireworks.ai/inference/v1/chat/completions",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent")) {
            assertFalse(AIProviderRegistry.permittedEndpoint(AiHttpRequest(url, "POST", emptyMap(), body = "{}")))
        }
        assertFalse(AIProviderRegistry.permittedEndpoint(AiHttpRequest("https://api.groq.com/openai/v1/models", "POST", emptyMap(), body = "{}")))
    }
    @Test fun allImplementedDirectProvidersParseUsageAndTextFromTheirDocumentedStreamFormats() = runBlocking {
        for (mode in listOf(AiMode.GROQ)) {
            val id = settings(mode).modelFor(); val posts = java.util.concurrent.atomic.AtomicInteger()
            val http = object : AiHttpTransport, AiStreamingTransport {
                override fun execute(q: AiHttpRequest) = AiHttpResponse(200, metadata(mode))
                override fun stream(q: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse {
                    posts.incrementAndGet()
                    run {
                        fun chunk(text: String, finish: Any) = JSONObject().put("model", id).put("choices", JSONArray().put(JSONObject().put("index", 0)
                            .put("delta", JSONObject().put("content", text)).put("finish_reason", finish))).toString()
                        onEvent(chunk("سلا", JSONObject.NULL)); onEvent(chunk("م", "stop"))
                        onEvent(JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 2).put("total_tokens", 12)).toString())
                        onEvent("[DONE]")
                    }
                    return AiHttpResponse(200, "")
                }
            }
            val rt = AIRequestRuntime(http, clock = { now }); val a = adapter(mode, http, runtime = rt)
            val m = (a.discover(true) as AIModelDiscovery.Found).models.single(); val visible = mutableListOf<String>()
            val result = a.infer(m.key, AIModelRequest(messages, emptyList(), 256, true)) { visible += it } as FunctionResult.Reply
            assertEquals("سلام", result.message.content); assertEquals("سلام", visible.last()); assertEquals(1, posts.get())
            assertEquals(12, rt.diagnostics.value.last().actual?.total)
        }
    }
    @Test fun groqNativeChangesStillRequireAppOwnedApprovalAndContinueFromToolResults() = runBlocking {
        for (mode in listOf(AiMode.GROQ)) {
            val s = settings(mode); var posts = 0; var writes = 0; val bodies = mutableListOf<String>()
            val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, metadata(mode)) else {
                posts++; bodies += requireNotNull(q.body)
                AiHttpResponse(200, if (posts == 1) OpenRouterFixture.completion("پیشنهاد", listOf(FunctionCall("expense_once", "add_expense", """{"date":"1404/01/01","amount":1000,"title":"ساختگی"}""")), s.modelFor())
                    else OpenRouterFixture.completion("نتیجهٔ ابزار دریافت شد", model = s.modelFor()))
            } }
            val a = adapter(mode, http); val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
            val provider = object : FunctionCallingProvider {
                override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit) =
                    a.infer(m.key, AIModelRequest(messages, AIToolExposure.tools(messages, true), 512, false), onText)
            }
            val runner = object : AgentToolRunner {
                override suspend fun execute(call: ValidatedCall): ToolOutcome { writes++; return ToolOutcome(true, "ثبت واقعیِ mock تأیید شد") }
                override fun describe(call: ValidatedCall) = "پیش‌نمایش ساختگی"
                override suspend fun describeTarget(call: ValidatedCall): String? = null
            }
            val agent = PersianToolAgent(provider, runner)
            val pending = agent.start(listOf(ChatEntry("u", ChatRole.USER, "یک هزینهٔ آزمایشی ثبت کن", 1))) as AgentStep.Confirm
            assertEquals(0, writes); assertEquals(1, posts)
            assertTrue(agent.confirm(pending.pending) is AgentStep.Reply)
            assertEquals(1, writes); assertEquals(2, posts)
            assertTrue(bodies.last().contains("\"tool_call_id\":\"expense_once\""))
            agent.confirm(pending.pending); assertEquals(1, writes); assertEquals(2, posts)
        }
    }
}
