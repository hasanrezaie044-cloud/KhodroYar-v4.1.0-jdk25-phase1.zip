package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DirectGapGptProviderTest {
    private val key = "test-only-key-12345"
    private val messages = listOf(AiMessage("user", "سلام!"))
    private fun configured() = AiSettings(mode = AiMode.GAPGPT_DIRECT, consented = true)
    private val success = AiHttpResponse(200, """{"model":"gpt-4o","choices":[{"message":{"content":"سلام!"}}]}""")

    private class Reader(private val value: String?) : AiApiKeyReader {
        var reads = 0
        override fun read(): String? { reads++; return value }
    }

    private class Transport(val handler: (AiHttpRequest) -> AiHttpResponse) : AiHttpTransport {
        val requests = mutableListOf<AiHttpRequest>()
        override fun execute(request: AiHttpRequest): AiHttpResponse { requests.add(request); return handler(request) }
    }

    @Test fun reproducesThe404RegressionAndUsesTheCorrectEndpoint() = runBlocking {
        val transport = Transport { if (it.url.endsWith("/v1/chat/completions")) success else AiHttpResponse(404, "{}") }
        val provider = DirectGapGptProvider({ configured() }, Reader(key), transport)
        assertTrue(provider.chat(messages, "gpt-4o", 700) is AiResult.Success)
        assertEquals(1, transport.requests.size)
        assertEquals("https://api.gapgpt.app/v1/chat/completions", transport.requests.single().url)
    }

    @Test fun localBackendDisabledAndUnconsentedModesNeverEvenReadAKey() = runBlocking {
        listOf(configured().copy(mode = AiMode.LOCAL), configured().copy(mode = AiMode.ONLINE), configured().copy(enabled = false), configured().copy(consented = false)).forEach { settings ->
            val reader = Reader(key)
            val transport = Transport { error("Network must not be called") }
            val provider = DirectGapGptProvider({ settings }, reader, transport)
            assertEquals(AiErrorKind.DISABLED, (provider.chat(messages, null, 32) as AiResult.Failure).kind)
            assertEquals(0, reader.reads)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun missingSampleAndUnreadableKeysNeverMakeARequest() = runBlocking {
        listOf(null, "YOUR_GAPGPT_API_KEY").forEach { value ->
            val transport = Transport { error("Network must not be called") }
            val provider = DirectGapGptProvider({ configured() }, Reader(value), transport)
            assertEquals(AiErrorKind.NOT_CONFIGURED, (provider.chat(messages, null, 32) as AiResult.Failure).kind)
            assertTrue(transport.requests.isEmpty())
        }
        val reader = AiApiKeyReader { throw IllegalStateException(key) }
        val transport = Transport { error("Network must not be called") }
        val result = DirectGapGptProvider({ configured() }, reader, transport).chat(messages, null, 32) as AiResult.Failure
        assertEquals(AiErrorKind.NOT_CONFIGURED, result.kind)
        assertFalse(result.message.contains(key))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun unapprovedDestinationsDoNotReadOrSendTheProviderKey() = runBlocking {
        val reader = Reader(key)
        val transport = Transport { error("Network must not be called") }
        val provider = DirectGapGptProvider({ configured().copy(gapGptBaseUrl = "https://evil.example/v1") }, reader, transport)
        assertEquals(AiErrorKind.NOT_CONFIGURED, (provider.chat(messages, null, 32) as AiResult.Failure).kind)
        assertEquals(0, reader.reads)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun documentedCdnIsAnExplicitChoiceAndBackendTokensAreNeverForwarded() = runBlocking {
        val settings = configured().copy(gapGptBaseUrl = GapGptDirectContract.CDN_BASE_URL, clientToken = "private-backend-token")
        val transport = Transport { success }
        val provider = DirectGapGptProvider({ settings }, Reader(key), transport)
        provider.chat(messages, "gemini-2.5-pro", 700)
        val request = transport.requests.single()
        assertEquals("https://api.gapapi.com/v1/chat/completions", request.url)
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertFalse(request.headers.values.any { it.contains(settings.clientToken) })
        assertFalse(request.body!!.contains(settings.clientToken))
        assertEquals("gemini-2.5-pro", JSONObject(request.body!!).getString("model"))
    }

    @Test fun testConnectionSendsARealMinimalCompletionNotAnInventedStatusEndpoint() = runBlocking {
        val transport = Transport { success }
        val provider = DirectGapGptProvider({ configured() }, Reader(key), transport)
        val result = provider.status()
        assertTrue(result.reachable && result.enabled && result.configured)
        assertEquals(true, result.modelAvailable)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.gapgpt.app/v1/chat/completions", request.url)
        val body = JSONObject(request.body!!)
        assertEquals(32, body.getInt("max_tokens"))
        assertEquals(1, body.getJSONArray("messages").length())
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertFalse(body.toString().contains("system"))
        assertFalse(body.toString().contains("tools"))
    }

    @Test fun a404NeverTriggersAnAutomaticRequestToTheOtherHost() = runBlocking {
        val transport = Transport { AiHttpResponse(404, "{}") }
        val result = DirectGapGptProvider({ configured() }, Reader(key), transport).chat(messages, null, 32) as AiResult.Failure
        assertEquals(AiErrorKind.NOT_FOUND, result.kind)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.startsWith("https://api.gapgpt.app/"))
    }

    @Test fun html200IsNotSuccessfulConnectionAndProbeFailuresRemainFailures() = runBlocking {
        val transport = Transport { AiHttpResponse(200, "<html>site</html>") }
        val result = DirectGapGptProvider({ configured() }, Reader(key), transport).status()
        assertEquals(AiErrorKind.SERVER, result.errorKind)
        assertFalse(result.configured)
    }

    @Test fun networkTimeoutAndKeyErrorsDoNotLeakSecrets() = runBlocking {
        listOf(IOException(key) to AiErrorKind.OFFLINE, SocketTimeoutException(key) to AiErrorKind.TIMEOUT).forEach { (exception, expected) ->
            val transport = Transport { throw exception }
            val result = DirectGapGptProvider({ configured() }, Reader(key), transport).chat(messages, null, 32) as AiResult.Failure
            assertEquals(expected, result.kind)
            assertFalse(result.message.contains(key))
            assertEquals(1, transport.requests.size)
        }
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoAnErrorResponse() = runBlocking {
        val transport = Transport { throw CancellationException("stop") }
        DirectGapGptProvider({ configured() }, Reader(key), transport).chat(messages, null, 32)
        Unit
    }
}
