package com.khodroyar.app.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GapGptDirectContractTest {
    private val key = "test-only-key-12345"

    @Test fun bothDocumentedHostsUseExactlyTheChatCompletionsEndpoint() {
        listOf("api.gapgpt.app", "api.gapapi.com").forEach { host ->
            listOf("", "/", "/v1", "/v1/", "/v1/chat/completions", "/v1/chat/completions/", "/v1/models").forEach { path ->
                assertEquals("$host$path", "https://$host/v1/chat/completions", GapGptDirectContract.chatUrl("https://$host$path"))
            }
        }
    }

    @Test fun providerUrlCannotBeUsedAsTheCustomBackend() {
        listOf(GapGptDirectContract.PRIMARY_BASE_URL, GapGptDirectContract.CDN_BASE_URL).forEach {
            assertTrue(GapGptDirectContract.isProviderHost(it))
            assertNull(AiBackendContract.endpoint(it, "chat"))
            assertNull(AiBackendContract.endpoint(it, "status"))
            assertFalse(AiSettingsStore.isValidBackendUrl(it))
        }
        assertEquals("https://owner.example/v1/chat", AiBackendContract.endpoint("https://owner.example", "chat"))
    }

    @Test fun onlyTheTwoOfficialHttpsDestinationsCanReceiveTheKey() {
        listOf(
            "http://api.gapgpt.app/v1", "https://api.gapgpt.app.evil.test/v1", "https://api.gapgpt.app@evil.test/v1",
            "https://user:pass@api.gapgpt.app/v1", "https://api.gapgpt.app/v1?key=secret", "https://api.gapgpt.app/v1#secret",
            "https://api.gapgpt.app:8443/v1", "https://api.gapgpt.app/v1/chat", "https://api.gapgpt.app/%76%31",
            "https://api.gapgpt.app/v1/../v1", "https://api.gapgpt.app/a b", "https://other.example/v1", "",
        ).forEach { assertNull(it, GapGptDirectContract.chatUrl(it)) }
    }

    @Test fun payloadMatchesTheOpenAiPythonExampleRatherThanTheProxyFormat() {
        val request = GapGptDirectContract.chatRequest(GapGptDirectContract.PRIMARY_BASE_URL, key, listOf(AiMessage("user", "سلام!")), "gpt-4o", 700)
        assertEquals("POST", request.method)
        assertEquals("https://api.gapgpt.app/v1/chat/completions", request.url)
        assertEquals("Bearer $key", request.headers["Authorization"])
        val body = JSONObject(request.body!!)
        assertEquals("gpt-4o", body.getString("model"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("سلام!", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.has("json"))
        assertFalse(body.has("response_format"))
        assertFalse(request.headers.containsKey("X-Client-Id"))
        assertFalse(request.body!!.contains(key))
        assertFalse(request.url.contains(key))
    }

    @Test fun modelNamesAreNotHardcodedToOneModelAndOutputCostIsCapped() {
        val request = GapGptDirectContract.chatRequest(GapGptDirectContract.CDN_BASE_URL, key, listOf(AiMessage("user", "سلام!")), "gemini-2.5-pro", 50_000)
        val body = JSONObject(request.body!!)
        assertEquals("gemini-2.5-pro", body.getString("model"))
        assertEquals(AiLimits.MAX_OUTPUT_TOKENS, body.getInt("max_tokens"))
        assertEquals("https://api.gapapi.com/v1/chat/completions", request.url)
    }

    @Test fun readsChoicesMessageContentExactlyLikeThePythonSdk() {
        val result = GapGptDirectContract.chatResponse(AiHttpResponse(200, """{"model":"gpt-4o","choices":[{"message":{"role":"assistant","content":"سلام!"}}]}"""), "gpt-4o") as AiResult.Success
        assertEquals("سلام!", result.content)
        assertEquals("gpt-4o", result.model)
    }

    @Test fun customBackendOrHtml200CannotMasqueradeAsAChatCompletion() {
        listOf("""{"content":"proxy response"}""", "<html>website</html>", "{}", """{"choices":[{"message":{"content":null}}]}""").forEach {
            assertTrue(GapGptDirectContract.chatResponse(AiHttpResponse(200, it), "gpt-4o") is AiResult.Failure)
        }
    }

    @Test fun samplePlaceholdersAndHeaderInjectionAreNotRealKeys() {
        listOf("YOUR_GAPGPT_API_KEY", "YOUR_API_KEY", "Bearer YOUR_GAPGPT_API_KEY", "", "test-only-key\r\nX-Header:value", "test only key with spaces").forEach {
            assertNull(it, GapGptDirectContract.normalizeApiKey(it))
        }
        assertEquals(key, GapGptDirectContract.normalizeApiKey(" Bearer $key "))
        assertEquals(key, GapGptDirectContract.normalizeApiKey(key))
    }

    @Test fun requestAndResponseToStringNeverExposeKeysOrConversations() {
        val request = GapGptDirectContract.chatRequest(GapGptDirectContract.PRIMARY_BASE_URL, key, listOf(AiMessage("user", "private-conversation")), "gpt-4o", 32)
        assertFalse(request.toString().contains(key))
        assertFalse(request.toString().contains("private-conversation"))
        assertFalse(AiHttpResponse(401, key).toString().contains(key))
    }

    @Test fun errorsAreActionableButNeverEchoTheProviderBody() {
        val result = GapGptDirectContract.chatResponse(AiHttpResponse(404, """{"error":{"message":"$key private-message"}}"""), "gpt-4o") as AiResult.Failure
        assertEquals(AiErrorKind.NOT_FOUND, result.kind)
        assertTrue(result.message.contains("/v1/chat/completions"))
        assertFalse(result.message.contains(key))
        assertFalse(result.message.contains("private-message"))
        val modelError = GapGptDirectContract.chatResponse(AiHttpResponse(404, """{"error":{"code":"model_not_found","message":"$key"}}"""), "gemini-2.5-pro") as AiResult.Failure
        assertEquals(AiErrorKind.BAD_REQUEST, modelError.kind)
        assertTrue(modelError.message.contains("gemini-2.5-pro"))
        assertFalse(modelError.message.contains(key))
    }

    @Test fun authCreditRateLimitsAndRedirectsAreDistinguished() {
        mapOf(401 to AiErrorKind.UNAUTHORIZED, 403 to AiErrorKind.UNAUTHORIZED, 402 to AiErrorKind.CREDIT_EXHAUSTED, 429 to AiErrorKind.RATE_LIMITED, 302 to AiErrorKind.NOT_CONFIGURED, 500 to AiErrorKind.SERVER, 504 to AiErrorKind.TIMEOUT).forEach { (code, expected) ->
            val result = GapGptDirectContract.chatResponse(AiHttpResponse(code, "{}", "7"), "gpt-4o") as AiResult.Failure
            assertEquals(code.toString(), expected, result.kind)
            if (code == 429) assertEquals(7, result.retryAfterSec!!)
        }
    }

    @Test fun modelsAndSettingsAreSeparateForBackendAndDirectModes() {
        val settings = AiSettings(mode = AiMode.GAPGPT_DIRECT, model = "backend-only", gapGptModel = "gemini-2.5-pro")
        assertEquals("gemini-2.5-pro", settings.requestedModel)
        assertEquals("backend-only", settings.copy(mode = AiMode.ONLINE).requestedModel)
        assertEquals("gpt-4o", settings.copy(gapGptModel = "").requestedModel)
        assertEquals(AiMode.OPENROUTER, AiSettings().mode)
        assertEquals("openrouter/free", AiSettings().openRouterModel)
        assertFalse(AiSettings().consented)
    }
}
