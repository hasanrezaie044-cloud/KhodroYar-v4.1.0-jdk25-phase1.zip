package com.khodroyar.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiBackendContractTest {
    @Test fun rootVersionAndFullEndpointsDoNotDuplicateV1() {
        listOf("", "/", "/v1", "/v1/", "/v1/chat", "/v1/chat/", "/v1/status", "/v1/models", "/v1/chat/completions", "/health").forEach {
            assertEquals(it, "https://backend.example/v1/chat", AiBackendContract.endpoint("https://backend.example$it", "chat"))
        }
    }

    @Test fun reverseProxyPrefixesAndPortsSurviveNormalisation() {
        assertEquals("https://example.com:8443/assistant/v1/status", AiBackendContract.endpoint(" https://example.com:8443/assistant/v1/ ", "status"))
        assertEquals("https://example.com/api/v1/models", AiBackendContract.endpoint("https://example.com/api/v1/chat/completions", "models"))
        assertEquals("https://example.com/api", AiBackendContract.normalizeRoot("https://example.com/api"))
    }

    @Test fun normalisationIsIdempotent() {
        listOf("https://example.com", "https://example.com/api/v1/", "https://example.com/v1/chat").forEach {
            val root = AiBackendContract.normalizeRoot(it)!!
            assertEquals(root, AiBackendContract.normalizeRoot(root))
        }
    }

    @Test fun unsafeAndMalformedUrlsAreRejected() {
        listOf("", "https://", "https:///missing", "http://example.com", "https://example.com:0", "https://example.com:99999", "https://user:pass@example.com", "https://example.com?key=secret", "https://example.com#x", "https://example.com/a b", "https://example.com/../x", "https://example.com/%2e%2e/x", "https://example.com/a%2fb").forEach {
            assertNull(it, AiBackendContract.normalizeRoot(it))
        }
    }

    @Test fun direct404HtmlExplainsTheBackendAndLocalOption() {
        val error = AiBackendContract.error(404, "<html>not found</html>")
        assertEquals(AiErrorKind.NOT_FOUND, error.kind)
        assertTrue(error.message.contains("۴۰۴"))
        assertTrue(error.message.contains("داخلی"))
        assertFalse(error.message.contains("<html>"))
    }

    @Test fun provider404IsNotConfusedWithBackend404() {
        val error = AiBackendContract.error(502, """{"error":{"code":"upstream_not_found"}}""")
        assertEquals(AiErrorKind.NOT_FOUND, error.kind)
        assertTrue(error.message.contains("GAPGPT_BASE_URL"))
    }

    @Test fun unauthorizedStatusDoesNotPretendTheServerDisabledTheAssistant() {
        val status = AiBackendContract.statusResponse(401, """{"error":{"code":"unauthorized"}}""")
        assertTrue(status.reachable)
        assertEquals(AiErrorKind.UNAUTHORIZED, status.errorKind)
        assertTrue(status.message.contains("توکن"))
    }

    @Test fun unrelated200JsonOrHtmlIsNotAHealthyBackend() {
        listOf("<html>site</html>", "{}", """{"enabled":"yes","configured":true}""").forEach {
            assertEquals(AiErrorKind.SERVER, AiBackendContract.statusResponse(200, it).errorKind)
        }
        assertTrue(AiBackendContract.chatResponse(200, """{"choices":[]}""") is AiResult.Failure)
    }

    @Test fun successUsesOnlyTheDocumentedContract() {
        val response = AiBackendContract.chatResponse(200, """{"content":"سلام","model":"test"}""") as AiResult.Success
        assertEquals("سلام", response.content)
        val status = AiBackendContract.statusResponse(200, """{"enabled":true,"configured":true,"defaultModel":"test"}""")
        assertTrue(status.reachable && status.configured && status.enabled)
        assertNull(status.errorKind)
    }

    @Test fun retryTimeoutAndKillSwitchAreMappedPrecisely() {
        assertEquals(7, AiBackendContract.error(429, "{}", "7").retryAfterSec)
        assertEquals(AiErrorKind.TIMEOUT, AiBackendContract.error(504, "{}").kind)
        assertEquals(AiErrorKind.SERVER, AiBackendContract.error(503, "{}").kind)
        assertEquals(AiErrorKind.DISABLED, AiBackendContract.error(503, """{"error":{"code":"assistant_disabled"}}""").kind)
        assertEquals(AiErrorKind.NOT_CONFIGURED, AiBackendContract.error(503, """{"error":{"code":"not_configured"}}""").kind)
    }
}
