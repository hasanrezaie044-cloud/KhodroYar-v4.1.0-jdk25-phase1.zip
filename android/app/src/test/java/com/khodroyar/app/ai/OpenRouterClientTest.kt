package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenRouterClientTest {
    private val f = OpenRouterFixture
    private class Key(private val value: String?) : AiApiKeyReader {
        override fun read() = value
    }
    private class FakeHttp(var body: String, var status: Int = 200) : AiHttpTransport {
        val requests = mutableListOf<AiHttpRequest>()
        var handler: ((AiHttpRequest) -> AiHttpResponse)? = null
        override fun execute(request: AiHttpRequest): AiHttpResponse {
            requests += request
            return handler?.invoke(request) ?: if (request.method == "GET") AiHttpResponse(200, OpenRouterFixture.catalog()) else AiHttpResponse(status, body)
        }
    }
    private val messages = listOf(FunctionMessage("system", "فارسی"), FunctionMessage("user", "سلام"))
    private val schemas = OpenRouterToolSchemas.tools()
    @Test fun verifiedFreeCompletionUsesOnlyFixedHttpsNativeWireAndZeroCaps() = runBlocking {
        val http = FakeHttp(f.completion()); val result = OpenRouterClient(f::settings, Key(f.key), http).complete(messages) {}
        assertTrue(result is FunctionResult.Reply)
        assertEquals(listOf(OpenRouterFreePolicy.MODELS_URL, OpenRouterFreePolicy.CHAT_URL), http.requests.map { it.url })
        val post = http.requests.last(); val body = JSONObject(requireNotNull(post.body))
        assertEquals("openrouter/free", body.getString("model")); assertFalse(body.has("models")); assertFalse(body.has("plugins"))
        assertEquals("auto", body.getString("tool_choice")); assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals(0, body.getJSONObject("provider").getJSONObject("max_price").getInt("request"))
        assertEquals("Bearer ${f.key}", post.headers["Authorization"])
        assertFalse(http.requests.first().headers.containsKey("Authorization"))
        assertFalse(post.body.orEmpty().contains(f.key))
    }
    @Test fun noConsentNoKeyLocalDisabledAndPaidSelectionMakeZeroNetworkCalls() = runBlocking {
        val options = listOf(f.settings().copy(consented = false), f.settings().copy(mode = AiMode.LOCAL), f.settings().copy(enabled = false), f.settings().copy(openRouterModel = "vendor/paid"))
        for (settings in options) {
            val http = FakeHttp(f.completion())
            assertTrue(OpenRouterClient({ settings }, Key(f.key), http).complete(messages) {} is FunctionResult.Failed)
            assertTrue(http.requests.isEmpty())
        }
        val http = FakeHttp(f.completion())
        assertTrue(OpenRouterClient(f::settings, Key(null), http).complete(messages) {} is FunctionResult.Failed)
        assertTrue(http.requests.isEmpty())
    }
    @Test fun unavailableFreeModelsNeverReachACompletionOrAnyPaidFallback() = runBlocking {
        val http = FakeHttp(f.completion()); http.handler = { AiHttpResponse(200, f.catalog(f.row("vendor/paid", "1", "1"))) }
        val result = OpenRouterClient(f::settings, Key(f.key), http).complete(messages) {}
        assertTrue(result is FunctionResult.Failed); assertEquals(1, http.requests.size); assertEquals("GET", http.requests.single().method)
    }
    @Test fun catalogIsReverifiedEveryTurnAndAPriceChangeStopsTheNextPost() = runBlocking {
        val http = FakeHttp(f.completion()); val client = OpenRouterClient(f::settings, Key(f.key), http)
        assertTrue(client.complete(messages) {} is FunctionResult.Reply)
        http.handler = { AiHttpResponse(200, f.catalog(f.row("openrouter/free"), f.row(prompt = "1"))) }
        assertTrue(client.complete(messages) {} is FunctionResult.Failed)
        assertEquals(1, http.requests.count { it.method == "POST" })
    }
    @Test fun apiFailuresArePersianSanitisedAndNeverRetriedAutomatically() = runBlocking {
        for ((status, kind) in listOf(401 to AiErrorKind.UNAUTHORIZED, 402 to AiErrorKind.CREDIT_EXHAUSTED, 404 to AiErrorKind.NOT_FOUND, 429 to AiErrorKind.RATE_LIMITED, 503 to AiErrorKind.SERVER)) {
            val http = FakeHttp("private ${f.key}", status)
            val result = OpenRouterClient(f::settings, Key(f.key), http).complete(messages) {} as FunctionResult.Failed
            assertEquals(kind, result.error.kind); assertFalse(result.error.message.contains(f.key)); assertFalse(result.error.message.contains("private"))
            assertEquals(1, http.requests.count { it.method == "POST" })
        }
    }
    @Test fun rateLimitCooldownDoesNotTriggerMoreRequestsOrAnotherModel() = runBlocking {
        val http = FakeHttp("{}", 429); val client = OpenRouterClient(f::settings, Key(f.key), http)
        client.complete(messages) {}; val count = http.requests.size
        assertTrue(client.complete(messages) {} is FunctionResult.Failed); assertEquals(count, http.requests.size)
    }
    @Test fun offlineFailureIsNotRelabelledAsSuccessAndDoesNotLeakTransportException() = runBlocking {
        val http = FakeHttp(""); http.handler = { throw IOException("secret ${f.key}") }
        val result = OpenRouterClient(f::settings, Key(f.key), http).complete(messages) {} as FunctionResult.Failed
        assertEquals(AiErrorKind.OFFLINE, result.error.kind); assertFalse(result.error.message.contains(f.key)); assertEquals(1, http.requests.size)
    }
    @Test fun actualTestRequiresNativeNonceProbeAndSendsNoRealDatabaseData() = runBlocking {
        val http = FakeHttp(""); http.handler = { request ->
            if (request.method == "GET") AiHttpResponse(200, f.catalog()) else {
                val wire = JSONObject(requireNotNull(request.body)); val text = wire.getJSONArray("messages").getJSONObject(0).getString("content")
                val nonce = text.substringAfter("nonce برابر ").substringBefore(" صدا")
                assertEquals(1, wire.getJSONArray("tools").length()); assertEquals("connection_probe", wire.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
                assertFalse(request.body.orEmpty().contains("get_monthly"))
                AiHttpResponse(200, f.completion("", listOf(f.call("connection_probe", JSONObject().put("nonce", nonce).toString()))))
            }
        }
        val status = OpenRouterClient(f::settings, Key(f.key), http).testConnection()
        assertTrue(status.reachable); assertEquals(f.FREE, status.defaultModel)
    }
    @Test fun a200WithoutNativeProbeIsNotAConnectionSuccess() = runBlocking {
        val status = OpenRouterClient(f::settings, Key(f.key), FakeHttp(f.completion("ابزار کار می‌کند"))).testConnection()
        assertNotNull(status.errorKind); assertFalse(status.configured)
    }
    @Test fun rawKeyInUserTextIsBlockedBeforeEvenCatalogFetch() = runBlocking {
        val http = FakeHttp(f.completion())
        val result = OpenRouterClient(f::settings, Key(f.key), http).complete(listOf(FunctionMessage("user", "این ${f.key}"))) {}
        assertTrue(result is FunctionResult.Failed); assertTrue(http.requests.isEmpty())
    }
    @Test fun midStreamRateLimitIsSanitisedAndActivatesTheSameCooldown() = runBlocking {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val http = object : AiHttpTransport, AiStreamingTransport {
            override fun execute(request: AiHttpRequest): AiHttpResponse { count.incrementAndGet(); return AiHttpResponse(200, f.catalog()) }
            override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse {
                count.incrementAndGet()
                onEvent("""{"model":"${f.FREE}","error":{"code":429,"message":"${f.key} RAW PRIVATE"}}""")
                return AiHttpResponse(200, "")
            }
        }
        val client = OpenRouterClient(f::settings, Key(f.key), http)
        val failure = client.complete(messages) {} as FunctionResult.Failed
        assertEquals(AiErrorKind.RATE_LIMITED, failure.error.kind); assertFalse(failure.error.message.contains("RAW PRIVATE"))
        val before = count.get(); assertTrue(client.complete(messages) {} is FunctionResult.Failed); assertEquals(before, count.get())
    }

}
