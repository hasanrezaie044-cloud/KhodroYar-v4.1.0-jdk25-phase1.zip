package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiProviderRouterTest {
    private class Fake(override val name: String) : AIProvider {
        var calls = 0
        var checks = 0
        var result: AiResult = AiResult.Success(name, name)
        override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult { calls++; return result }
        override suspend fun status(): AiProviderStatus { checks++; return AiProviderStatus(true, true, true, name) }
    }

    @Test fun localModeNeverCallsEitherOnlineProtocol() = runBlocking {
        val backend = Fake("backend")
        val direct = Fake("direct")
        val router = AiProviderRouter({ AiMode.LOCAL }, backend, direct)
        assertTrue(router.chat(listOf(AiMessage("user", "hi")), null, 32) is AiResult.Failure)
        router.status()
        assertEquals(0, backend.calls + direct.calls + backend.checks + direct.checks)
    }

    @Test fun modeSelectionRoutesToOnlyTheChosenProtocol() = runBlocking {
        var mode = AiMode.ONLINE
        val backend = Fake("backend")
        val direct = Fake("direct")
        val router = AiProviderRouter({ mode }, backend, direct)
        assertEquals("backend", (router.chat(listOf(AiMessage("user", "hi")), null, 32) as AiResult.Success).content)
        mode = AiMode.GAPGPT_DIRECT
        assertEquals("direct", (router.chat(listOf(AiMessage("user", "hi")), null, 32) as AiResult.Success).content)
        router.status()
        assertEquals(1, backend.calls)
        assertEquals(1, direct.calls)
        assertEquals(0, backend.checks)
        assertEquals(1, direct.checks)
    }

    @Test fun nativeOpenRouterCanNeverFallBackIntoEitherLegacyPaidProtocol() = runBlocking {
        val backend = Fake("backend"); val direct = Fake("direct")
        val router = AiProviderRouter({ AiMode.OPENROUTER }, backend, direct)
        assertTrue(router.chat(listOf(AiMessage("user", "private")), null, 32) is AiResult.Failure)
        router.status()
        assertEquals(0, backend.calls + direct.calls + backend.checks + direct.checks)
    }

    @Test fun errorsNeverSilentlyFallBackAndTransmitToAnotherService() = runBlocking {
        val backend = Fake("backend").apply { result = AiResult.Failure(AiErrorKind.NOT_FOUND, "404") }
        val direct = Fake("direct")
        val router = AiProviderRouter({ AiMode.ONLINE }, backend, direct)
        assertTrue(router.chat(listOf(AiMessage("user", "private")), null, 32) is AiResult.Failure)
        assertEquals(0, direct.calls)
    }
}
