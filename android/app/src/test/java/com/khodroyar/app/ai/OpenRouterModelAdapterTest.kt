package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterModelAdapterTest {
    private val f = OpenRouterFixture
    private var now = 1_800_000_000_000L
    private val query = AIModelRequest(listOf(FunctionMessage("user", "سلام")), AgentToolRegistry.tools, stream = false)
    private fun catalog(vararg rows: JSONObject) = JSONObject().put("data", JSONArray(rows.toList())).toString()
    private fun row(id: String = f.FREE) = f.row(id).put("canonical_slug", id)
    private fun endpoint(model: String = f.FREE, price: String = "0", status: Any = 0) = JSONObject().put("data", JSONObject().put("id", model)
        .put("endpoints", JSONArray().put(f.row(model, prompt = price).put("status", status).put("provider_name", "Synthetic provider").put("uptime_last_30m", 99)))).toString()
    private inner class Http : AiHttpTransport {
        var public = catalog(row()); var user = public; var endpoints = endpoint(); var completion = f.completion()
        var userCode = 200; var postCode = 200; var endpointCode = 200
        var handler: ((AiHttpRequest) -> AiHttpResponse)? = null
        val requests = mutableListOf<AiHttpRequest>()
        override fun execute(request: AiHttpRequest): AiHttpResponse {
            requests += request
            handler?.let { return it(request) }
            return when {
                request.url == OpenRouterModelMetadata.USER_MODELS_URL -> AiHttpResponse(userCode, user)
                request.url.endsWith("/endpoints") -> AiHttpResponse(endpointCode, endpoints)
                request.method == "GET" -> AiHttpResponse(200, public)
                else -> AiHttpResponse(postCode, completion)
            }
        }
    }
    private fun adapter(http: Http, settings: () -> AiSettings = f::settings) = OpenRouterModelAdapter(OpenRouterClient(settings, object : AiApiKeyReader { override fun read() = f.key }, http, { now }), { now })

    @Test fun exactOfficialIdsPublicAndKeyScopedGuardrailCatalogMustIntersect() {
        val public = catalog(row(), row("new/discovered:free"))
        val (models, _) = OpenRouterModelMetadata.parse(public, catalog(row()), now)
        assertEquals(AIModelAuthorization.AUTHORIZED, models.single { it.key.id == f.FREE }.authorization)
        assertEquals(AIModelAuthorization.NOT_AUTHORIZED, models.single { it.key.id == "new/discovered:free" }.authorization)
        assertFalse(models.any { it.key.id == "new/invented:free" })
        for (bad in listOf("vendor/model?key=secret", "vendor/../paid", " vendor/model:free", "https://other/evil", "vendor/model%3Afree")) assertFalse(OpenRouterModelMetadata.validId(bad))
        assertTrue(runCatching { OpenRouterModelMetadata.parse(catalog(row(), row()), catalog(row()), now) }.isFailure)
    }
    @Test fun metadataVerificationIsNotBoundToAnOutdatedFreeSuffixList() {
        val fresh = row("new-provider/newly-free-model")
        val model = OpenRouterModelMetadata.parse(catalog(fresh), catalog(fresh), now).first.single()
        assertTrue(AIRequestPolicy.eligibleMetadata(model, now))
        assertEquals("new-provider/newly-free-model", model.key.id)
        val router = row("openrouter/free")
        assertFalse(OpenRouterModelMetadata.parse(catalog(router), catalog(router), now).first.single().pinnedIdentity)
    }
    @Test fun missingPositiveNegativeNullBooleanAndFuturePricesFailClosedWithoutUnderflow() {
        for (value in listOf<Any>("0.0000001", "-1", "NaN", JSONObject.NULL, true, "1e-1000")) {
            val r = row(); r.getJSONObject("pricing").put("prompt", value)
            val model = OpenRouterModelMetadata.parse(catalog(r), catalog(r), now).first.single()
            assertFalse(value.toString(), model.pricing.verifiedFree)
        }
        val missing = row(); missing.getJSONObject("pricing").remove("completion")
        assertFalse(OpenRouterModelMetadata.parse(catalog(missing), catalog(missing), now).first.single().pricing.verifiedFree)
        val r = row(); r.getJSONObject("pricing").put("future_charge", "0.00001")
        assertFalse(OpenRouterModelMetadata.parse(catalog(r), catalog(r), now).first.single().pricing.verifiedFree)
        val tiny = catalog(row()).replace("\"prompt\":\"0\"", "\"prompt\":1e-1000")
        assertFalse(OpenRouterModelMetadata.parse(tiny, tiny, now).first.single().pricing.verifiedFree)
    }
    @Test fun toolsContextTextOutputDeprecationAndProviderStatusAreIndependentGates() {
        for (changed in listOf(row().put("supported_parameters", JSONArray(listOf("max_tokens"))), row().put("context_length", 8192),
            row().put("deprecated", true), row().put("expiration_date", "2000-01-01"),
            row().put("architecture", JSONObject().put("output_modalities", JSONArray(listOf("image")))))) {
            assertFalse(AIRequestPolicy.eligibleMetadata(OpenRouterModelMetadata.parse(catalog(changed), catalog(changed), now).first.single(), now))
        }
        val model = OpenRouterModelMetadata.parse(catalog(row()), catalog(row()), now).first.single()
        assertTrue(OpenRouterModelMetadata.availability(endpoint(), model, setOf(f.FREE), now).available)
        for (body in listOf(endpoint(price = "0.001"), endpoint(status = 1), endpoint(status = "0"), endpoint(model = "unverified/other"))) {
            assertFalse(runCatching { OpenRouterModelMetadata.availability(body, model, setOf(f.FREE), now).available }.getOrDefault(false))
        }
    }
    @Test fun adapterUsesOnlyFixedHttpsAndAddsCredentialsOnlyToKeyScopedMetadataAndInference() = runBlocking {
        val http = Http(); val adapter = adapter(http)
        val models = (adapter.discover() as AIModelDiscovery.Found).models
        assertTrue(adapter.infer(models.single().key, query) is FunctionResult.Reply)
        assertEquals(listOf(OpenRouterFreePolicy.MODELS_URL, OpenRouterModelMetadata.USER_MODELS_URL, OpenRouterModelMetadata.endpointUrl(f.FREE), OpenRouterFreePolicy.CHAT_URL), http.requests.map { it.url })
        assertFalse(http.requests[0].headers.containsKey("Authorization")); assertFalse(http.requests[2].headers.containsKey("Authorization"))
        assertEquals("Bearer ${f.key}", http.requests[1].headers["Authorization"])
        val body = JSONObject(requireNotNull(http.requests.last().body))
        assertEquals(f.FREE, body.getString("model")); assertFalse(body.has("models")); assertFalse(body.has("plugins"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals(0, body.getJSONObject("provider").getJSONObject("max_price").getInt("prompt"))
    }
    @Test fun minuteCacheExpiresAndAPriceChangeStopsAllFurtherPostsIncludingFormerlyApprovedModel() = runBlocking {
        val http = Http(); val adapter = adapter(http); val key = AIModelKey("openrouter", f.FREE)
        assertTrue(adapter.infer(key, query) is FunctionResult.Reply)
        assertTrue(adapter.infer(key, query) is FunctionResult.Reply)
        assertEquals(2, http.requests.count { it.url == OpenRouterFreePolicy.CHAT_URL })
        assertEquals(1, http.requests.count { it.url == OpenRouterFreePolicy.MODELS_URL })
        val r = row(); r.getJSONObject("pricing").put("completion", "0.001"); http.public = catalog(r)
        now += AIRequestPolicy.METADATA_TTL_MS
        assertTrue(adapter.infer(key, query) is FunctionResult.Failed)
        assertEquals(2, http.requests.count { it.method == "POST" })
    }
    @Test fun guardrailExcludedModelHasNoProbeNoUserPostAndNoBypassEndpoint() = runBlocking {
        val http = Http(); http.user = catalog()
        val result = adapter(http).infer(AIModelKey("openrouter", f.FREE), query)
        assertTrue(result is FunctionResult.Failed); assertTrue(http.requests.all { it.method == "GET" })
        assertTrue(http.requests.none { it.url.contains("guardrails") || it.url.endsWith("/endpoints") })
    }
    @Test fun accountForbiddenAndUnavailableMetadataNeverReuseThePreviousCatalog() = runBlocking {
        for (status in listOf(401, 403, 429, 503)) {
            val http = Http(); val a = adapter(http); assertTrue(a.discover() is AIModelDiscovery.Found)
            http.userCode = status
            assertTrue(a.discover(true) is AIModelDiscovery.Failed)
            assertTrue(a.infer(AIModelKey("openrouter", f.FREE), query) is FunctionResult.Failed)
            assertTrue(http.requests.none { it.method == "POST" })
        }
    }
    @Test fun a403IsAUsefulSanitisedGuardrailErrorNotAKeyLeakOrImplicitAuthorization() = runBlocking {
        val http = Http(); http.postCode = 403; http.completion = "secret ${f.key} raw upstream body"
        val failure = adapter(http).infer(AIModelKey("openrouter", f.FREE), query) as FunctionResult.Failed
        assertEquals(AiErrorKind.GUARDRAIL_REJECTED, failure.error.kind)
        assertTrue(failure.error.message.contains("Guardrail")); assertFalse(failure.error.message.contains(f.key)); assertFalse(failure.error.message.contains("raw upstream"))
    }
    @Test fun canonicalResponseAliasMustBeExplicitlyProvenByTheSameOfficialEntryNeverGuessedByStrippingFree() = runBlocking {
        val canonical = "synthetic/persian-checkpoint"
        val http = Http(); http.public = catalog(row().put("canonical_slug", canonical)); http.user = http.public
        http.completion = JSONObject(f.completion()).put("model", canonical).toString()
        val reply = adapter(http).infer(AIModelKey("openrouter", f.FREE), query) as FunctionResult.Reply
        assertEquals(f.FREE, reply.model)
        assertEquals(f.FREE, JSONObject(requireNotNull(http.requests.last().body)).getString("model"))
        val unproven = Http(); unproven.completion = JSONObject(f.completion()).put("model", f.FREE.removeSuffix(":free")).toString()
        assertTrue(adapter(unproven).infer(AIModelKey("openrouter", f.FREE), query) is FunctionResult.Failed)
    }
    @Test fun backgroundMetadataEpochInvalidatesEvenAnUnexpiredInProcessPriceCache() = runBlocking {
        val http = Http(); val a = adapter(http); val key = AIModelKey("openrouter", f.FREE)
        assertTrue(a.infer(key, query) is FunctionResult.Reply)
        val changed = row(); changed.getJSONObject("pricing").put("prompt", "1"); http.public = catalog(changed)
        AIModelCatalogEpoch.value.incrementAndGet()
        assertTrue(a.infer(key, query) is FunctionResult.Failed)
        assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun userScopedCapabilitiesAndContextCannotBeInferredFromThePublicCatalogAlone() {
        for (user in listOf(row().put("context_length", 8192), row().put("supported_parameters", JSONArray(listOf("tools"))),
            row().put("architecture", JSONObject().put("output_modalities", JSONArray(listOf("image")))))) {
            val model = OpenRouterModelMetadata.parse(catalog(row()), catalog(user), now).first.single()
            assertEquals(AIModelAuthorization.NOT_AUTHORIZED, model.authorization)
        }
    }
    @Test fun cachedPermissionsNeverTransferToANewAccountEvenInsideTheMinuteTtl() = runBlocking {
        var settings = f.settings(); val http = Http(); val a = adapter(http) { settings }
        val key = AIModelKey("openrouter", f.FREE)
        assertTrue(a.infer(key, query) is FunctionResult.Reply)
        settings = settings.copy(modelAccountScope = "new-account"); http.user = catalog()
        assertTrue(a.infer(key, query) is FunctionResult.Failed)
        assertEquals(1, http.requests.count { it.method == "POST" })
        assertEquals(2, http.requests.count { it.url == OpenRouterModelMetadata.USER_MODELS_URL })
    }
    @Test fun anAccountChangeWhileAuthorizedMetadataIsInFlightDiscardsItsEvidence() = runBlocking {
        var settings = f.settings(); val http = Http(); val a = adapter(http) { settings }
        http.handler = { request ->
            if (request.url == OpenRouterModelMetadata.USER_MODELS_URL) settings = settings.copy(modelAccountScope = "new-account")
            AiHttpResponse(200, catalog(row()))
        }
        val result = a.discover() as AIModelDiscovery.Failed
        assertEquals(AiErrorKind.DISABLED, result.error.kind)
        assertTrue(http.requests.none { it.method == "POST" })
        http.handler = null; http.user = catalog()
        assertTrue(a.infer(AIModelKey("openrouter", f.FREE), query) is FunctionResult.Failed)
        assertTrue(http.requests.none { it.method == "POST" })
    }
    @Test fun endpointErrorsKeepTheirAccountLimitKindAndNeverSendInference() = runBlocking {
        for ((code, kind) in listOf(401 to AiErrorKind.UNAUTHORIZED, 429 to AiErrorKind.RATE_LIMITED, 403 to AiErrorKind.GUARDRAIL_REJECTED)) {
            val http = Http(); http.endpointCode = code
            val result = adapter(http).infer(AIModelKey("openrouter", f.FREE), query) as FunctionResult.Failed
            assertEquals(kind, result.error.kind); assertTrue(http.requests.none { it.method == "POST" })
        }
    }
    @Test fun invalidationWhileEndpointMetadataIsInFlightCannotRestoreItsOldApproval() = runBlocking {
        val http = Http(); val a = adapter(http)
        assertTrue(a.discover() is AIModelDiscovery.Found)
        http.handler = { a.invalidate(); AiHttpResponse(200, endpoint()) }
        val result = a.infer(AIModelKey("openrouter", f.FREE), query) as FunctionResult.Failed
        assertEquals(AiErrorKind.DISABLED, result.error.kind)
        assertTrue(http.requests.none { it.method == "POST" })
    }
    @Test fun changingConsentDuringCompletionDropsAllProseAndToolCalls() = runBlocking {
        var settings = f.settings(); val http = Http(); val a = adapter(http) { settings }
        assertTrue(a.discover() is AIModelDiscovery.Found)
        http.handler = { request ->
            if (request.method == "POST") { settings = settings.copy(consented = false); AiHttpResponse(200, f.completion("", listOf(f.call("add_expense", "{\"amount\":100}")))) }
            else AiHttpResponse(200, endpoint())
        }
        val result = a.infer(AIModelKey("openrouter", f.FREE), query) as FunctionResult.Failed
        assertEquals(AiErrorKind.DISABLED, result.error.kind)
        assertEquals(1, http.requests.count { it.method == "POST" })
    }

}
