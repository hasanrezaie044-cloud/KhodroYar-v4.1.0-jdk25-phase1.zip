package com.khodroyar.app.ai

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Documented wire fixtures only. No real account, inference, purchase or provider credit is used. */
class AccountModelAdapterTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
    private val modes = listOf(AiMode.CLAUDE, AiMode.FIREWORKS, AiMode.GPT)
    private fun id(mode: AiMode) = when (mode) {
        AiMode.CLAUDE -> "claude-synthetic"
        AiMode.FIREWORKS -> "accounts/fireworks/models/synthetic-vision"
        else -> "gpt-4.1"
    }
    private fun key(mode: AiMode) = if (mode == AiMode.CLAUDE) "sk-ant-" + "x".repeat(40) else if (mode == AiMode.GPT) "sk-proj-" + "z".repeat(40) else "y".repeat(40)
    private fun settings(mode: AiMode) = AiSettings(mode = mode, consented = true, useRegion = AIUseRegion.SUPPORTED_ELSEWHERE,
        accountTermsConfirmed = setOf(AIProviderRegistry.get(mode).id), modelSelectionMode = AISelectionMode.MANUAL).withModel(mode, id(mode))
    private fun row(mode: AiMode): JSONObject = when (mode) {
        AiMode.CLAUDE -> JSONObject().put("id", id(mode)).put("display_name", "Synthetic Claude").put("lifecycle", "active")
            .put("max_input_tokens", 200000).put("max_tokens", 32768)
            .put("capabilities", JSONObject().put("image_input", JSONObject().put("supported", true)))
        AiMode.FIREWORKS -> JSONObject().put("name", id(mode)).put("displayName", "Synthetic Vision").put("contextLength", 131072)
            .put("supportsServerless", true).put("supportsImageInput", true).put("supportsTools", true)
            .put("state", "READY").put("conversationConfig", JSONObject()).put("huggingFaceUrl", "https://huggingface.co/meta-llama/synthetic")
        else -> JSONObject().put("id", id(mode)).put("owned_by", "openai")
    }
    private fun catalog(mode: AiMode, vararg rows: JSONObject = arrayOf(row(mode))) = JSONObject().put(if (mode == AiMode.FIREWORKS) "models" else "data", JSONArray(rows.toList())).toString()
    private fun completion(mode: AiMode): String = if (mode == AiMode.CLAUDE) JSONObject().put("model", id(mode)).put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "پاسخ تصویر ساختگی"))).put("stop_reason", "end_turn").toString()
        else OpenRouterFixture.completion("پاسخ تصویر ساختگی", model = id(mode)).replace("\"cost\":0", "\"cost\":0.002")
    private class Http(val handler: (AiHttpRequest) -> AiHttpResponse) : AiHttpTransport {
        val requests = mutableListOf<AiHttpRequest>()
        override fun execute(request: AiHttpRequest): AiHttpResponse { requests += request; return handler(request) }
    }
    private fun adapter(mode: AiMode, http: AiHttpTransport, s: () -> AiSettings = { settings(mode) }) =
        AccountModelAdapter(mode, s, object : AiApiKeyReader { override fun read() = key(mode) }, AIRequestRuntime(http, clock = { now }), { now })
    private val image = AIImage("image/jpeg", "aGVsbG8=")
    private val query = AIModelRequest(listOf(FunctionMessage("system", "فارسی پاسخ بده"), FunctionMessage("user", "این تصویر چیست؟", images = listOf(image))), emptyList(), stream = false)

    @Test fun supportedSurfaceIsExactlySevenConnectionsAndNeverIncludesRetiredProviders() {
        assertEquals(listOf(AiMode.LOCAL, AiMode.OPENROUTER, AiMode.GEMINI, AiMode.GROQ, AiMode.CLAUDE, AiMode.FIREWORKS, AiMode.GPT), AIProviderRegistry.supportedModes)
        assertEquals(AIProviderRegistry.supportedModes.toSet(), AIProviderRegistry.providers.map { it.mode }.toSet())
        assertEquals(7, AIProviderRegistry.providers.size)
    }
    @Test fun allAccountAdaptersDiscoverRealMetadataThenSendTheActualSelectedImageInTheirNativeProtocol() = runBlocking {
        for (mode in modes) {
            val http = Http { q -> AiHttpResponse(200, if (q.method == "GET") catalog(mode) else completion(mode)) }
            val a = adapter(mode, http); val model = (a.discover(true) as AIModelDiscovery.Found).models.single()
            assertEquals(AICostEvidence.ACCOUNT_QUOTA, model.costEvidence); assertFalse(model.pricing.verifiedFree); assertTrue(model.capability.imageInput)
            assertTrue(a.infer(model.key, query) is FunctionResult.Reply)
            assertEquals(listOf("GET", "POST"), http.requests.map { it.method })
            val post = http.requests.last(); val body = JSONObject(requireNotNull(post.body))
            assertEquals(id(mode), body.getString("model")); assertFalse(post.body.orEmpty().contains(key(mode)))
            assertTrue(AIProviderRegistry.permittedEndpoint(post)); assertFalse(body.has("models")); assertFalse(body.has("plugins"))
            if (mode == AiMode.CLAUDE) {
                assertEquals(key(mode), post.headers["x-api-key"]); assertFalse(post.headers.containsKey("Authorization"))
                assertEquals(32768, body.getInt("max_tokens")) // required provider metadata, not the retired 512 local allowance.
                val source = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("source")
                assertEquals("image/jpeg", source.getString("media_type")); assertEquals(image.base64, source.getString("data"))
            } else {
                assertEquals("Bearer ${key(mode)}", post.headers["Authorization"])
                assertFalse(body.has("max_tokens")); assertFalse(body.has("max_completion_tokens"))
                val url = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
                assertEquals("data:image/jpeg;base64,${image.base64}", url)
            }
        }
    }
    @Test fun noConsentUnknownRegionIranAndUnacceptedAccountCostsSendZeroRequests() = runBlocking {
        for (mode in modes) {
            val valid = settings(mode)
            for (s in listOf(valid.copy(consented = false), valid.copy(enabled = false), valid.copy(useRegion = AIUseRegion.UNKNOWN),
                valid.copy(useRegion = AIUseRegion.IRAN), valid.copy(accountTermsConfirmed = emptySet()), valid.copy(mode = AiMode.LOCAL))) {
                val http = Http { error("Not authorised") }; val a = adapter(mode, http) { s }
                assertTrue(a.discover(true) is AIModelDiscovery.Failed); assertTrue(a.infer(AIModelKey(AIProviderRegistry.get(mode).id, id(mode)), query) is FunctionResult.Failed)
                assertTrue(http.requests.isEmpty())
            }
        }
    }
    @Test fun accountBackedModelsAreNotMislabelledPermanentlyFreeAndDoNotActivateBilling() = runBlocking {
        for (mode in modes) {
            val http = Http { q -> assertEquals("GET", q.method); AiHttpResponse(200, catalog(mode)) }
            val model = (adapter(mode, http).discover(true) as AIModelDiscovery.Found).models.single()
            assertTrue(AIRequestPolicy.eligibleMetadata(model, now)); assertFalse(model.freeQuotaEligible)
            assertTrue(http.requests.none { it.url.contains("billing") || it.url.contains("deployments") })
        }
    }
    @Test fun unknownOpenAiContextIsDisplayedAsUnknownNotInventedAsAQuota() = runBlocking {
        val http = Http { AiHttpResponse(200, catalog(AiMode.GPT)) }
        val model = (adapter(AiMode.GPT, http).discover(true) as AIModelDiscovery.Found).models.single()
        assertEquals(0, model.capability.contextLength); assertEquals("—", ChatModelPresentation.capacity(model.capability.contextLength))
    }
    @Test fun unsupportedImagesAndMissingRequiredClaudeOutputMetadataFailBeforePostWithoutChangingModels() = runBlocking {
        for (mode in listOf(AiMode.CLAUDE, AiMode.FIREWORKS)) {
            val changed = row(mode)
            if (mode == AiMode.CLAUDE) changed.getJSONObject("capabilities").getJSONObject("image_input").put("supported", false)
            else changed.put("supportsImageInput", false)
            val http = Http { AiHttpResponse(200, catalog(mode, changed)) }; val a = adapter(mode, http)
            val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
            assertTrue(a.infer(m.key, query) is FunctionResult.Failed); assertEquals(0, http.requests.count { it.method == "POST" })
        }
        val missing = row(AiMode.CLAUDE).apply { remove("max_tokens") }
        val http = Http { AiHttpResponse(200, catalog(AiMode.CLAUDE, missing)) }; val a = adapter(AiMode.CLAUDE, http)
        val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
        assertTrue(a.infer(m.key, query) is FunctionResult.Failed); assertTrue(http.requests.all { it.method == "GET" })
    }
    @Test fun providerQuotaAuthCreditRegionErrorsStopAfterOnePostAndNeverSpendOnAnotherConnection() = runBlocking {
        for (mode in modes) for (status in listOf(401, 402, 403, 429, 451)) {
            val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, catalog(mode)) else AiHttpResponse(status, "RAW PRIVATE ${key(mode)}") }
            val a = adapter(mode, http); val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
            val failed = a.infer(m.key, query) as FunctionResult.Failed
            assertEquals(ProviderFailurePolicy.http(status).kind, failed.error.kind); assertEquals(1, http.requests.count { it.method == "POST" })
            assertFalse(failed.error.message.contains("PRIVATE")); assertFalse(failed.error.message.contains(key(mode)))
        }
    }
    @Test fun discoveryPaginatesUsingOnlyOfficialOpaqueCursorsAndDetectsCycles() = runBlocking {
        for (mode in listOf(AiMode.CLAUDE, AiMode.FIREWORKS)) {
            var calls = 0
            val http = Http {
                calls++
                val root = JSONObject(catalog(mode, row(mode).apply { if (calls == 2) put(if (mode == AiMode.CLAUDE) "id" else "name", id(mode) + "-second") }))
                if (mode == AiMode.CLAUDE) root.put("has_more", calls == 1).put("last_id", id(mode))
                else root.put("nextPageToken", if (calls == 1) "opaque+/cursor=" else "")
                AiHttpResponse(200, root.toString())
            }
            val found = adapter(mode, http).discover(true) as AIModelDiscovery.Found
            assertEquals(2, found.models.size); assertEquals(2, calls); assertTrue(http.requests.all(AIProviderRegistry::permittedEndpoint))
        }
    }
    @Test fun firestoreCatalogueDoesNotDeployPrivateUnavailableEmbeddingOrNonChatModels() = runBlocking {
        val mode = AiMode.FIREWORKS
        val http = Http { AiHttpResponse(200, catalog(mode,
            row(mode).put("supportsServerless", false), row(mode).put("state", "UPLOADING"), row(mode).apply { remove("conversationConfig") },
            row(mode).put("name", "accounts/other/models/private"))) }
        assertTrue((adapter(mode, http).discover(true) as AIModelDiscovery.Found).models.isEmpty())
        assertTrue(http.requests.all { it.method == "GET" })
    }
    @Test fun sameProviderOfficialResponseAliasNeverBecomesAGuessedRequestFallback() {
        val live = setOf("gpt-4.1", "gpt-4.1-2025-04-14", "gpt-4.1-2099-01-01", "unrelated-paid")
        assertEquals(setOf("gpt-4.1", "gpt-4.1-2025-04-14"), OpenAIResponseIdentities.forModel("gpt-4.1", live))
        assertEquals(setOf("gpt-4.1"), OpenAIResponseIdentities.forModel("gpt-4.1", setOf("gpt-4.1")))
    }
    @Test fun modelOrAccountChangeWhileHttpIsRunningDropsTheOldReply() = runBlocking {
        var s = settings(AiMode.GPT)
        val http = Http { q -> if (q.method == "GET") AiHttpResponse(200, catalog(AiMode.GPT)) else {
            s = s.copy(modelAccountScope = "different"); AiHttpResponse(200, completion(AiMode.GPT))
        } }
        val a = adapter(AiMode.GPT, http) { s }; val m = (a.discover(true) as AIModelDiscovery.Found).models.single()
        val result = a.infer(m.key, query) as FunctionResult.Failed
        assertEquals(AiErrorKind.DISABLED, result.error.kind); assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun credentialPatternsCoverNewKeysAndNeverPersistTheirPlainText() {
        for (mode in listOf(AiMode.CLAUDE, AiMode.GPT)) {
            assertTrue(AiTextSafety.containsCredential("پیام ${key(mode)}")); assertFalse(AiTextSafety.redact(key(mode)).contains(key(mode)))
        }
    }
}
