package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIToolExposureTest {
    @Test fun greetingOmitsAllToolsAndOrdinarySchemasAreFarSmallerThanTheWholeRegistry() {
        val greeting = listOf(FunctionMessage("user", "سلام!"))
        assertTrue(AIToolExposure.tools(greeting, true).isEmpty())
        val tools = AIToolExposure.tools(listOf(FunctionMessage("user", "وضعیت سرویس را بگو")), true)
        assertEquals(6, tools.size); assertTrue(tools.any { it.name == AIToolExposure.DISCOVER })
        val compact = OpenRouterToolSchemas.tools(tools, compact = true).toString().toByteArray().size
        val full = OpenRouterToolSchemas.tools().toString().toByteArray().size
        assertTrue("compact=$compact full=$full", compact < full / 3)
        assertTrue(AIToolExposure.tools(greeting, false).isEmpty())
    }
    @Test fun everyExistingToolRemainsDiscoverableWithBoundedSchemaPagesWithoutReadingData() {
        val discovered = mutableSetOf<String>()
        for (group in listOf("services", "finance", "vehicle", "reports", "automation", "other")) for (page in 1..10) {
            val call = FunctionCall("discover_${group}_$page", AIToolExposure.DISCOVER, """{"group":"$group","page":$page}""")
            val messages = listOf(FunctionMessage("user", "ابزار لازم را پیدا کن"), FunctionMessage("assistant", calls = listOf(call)))
            val schemas = AIToolExposure.tools(messages, true)
            assertTrue(schemas.size <= 14); discovered += schemas.map { it.name }
            assertTrue(AIToolExposure.discover(call).ok)
        }
        assertTrue(discovered.containsAll(AgentToolRegistry.tools.map { it.name }))
        assertEquals(AgentToolRegistry.tools.map { it.name }.toSet(), discovered - AIToolExposure.DISCOVER)
    }
    @Test fun invalidDiscoveryNeverAccessesApplicationRecordsOrProducesDefinitionsForUnknownTools() {
        for (args in listOf("{}", """{"group":"private","page":1}""", """{"group":"finance","page":1.5}""", """{"group":"finance","page":0}""", """{"group":"finance","page":1,"sql":"secret"}""")) {
            assertFalse(AIToolExposure.discover(FunctionCall("bad", AIToolExposure.DISCOVER, args)).ok)
        }
    }
    private val runner = object : AgentToolRunner {
        override suspend fun execute(call: ValidatedCall): ToolOutcome = error("No tool may run")
        override fun describe(call: ValidatedCall) = error("No approval may be prepared")
        override suspend fun describeTarget(call: ValidatedCall): String? = null
    }
    @Test fun toolsDisabledAlsoRejectsUnsolicitedCallsWithoutAnotherInference() = runBlocking {
        var requests = 0
        val provider = object : FunctionCallingProvider {
            override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
                requests++; return FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(FunctionCall("no", "get_today_expenses", "{}"))), "mock")
            }
        }
        val step = PersianToolAgent(provider, runner, { AIUsageLimits(toolsEnabled = false) }).start(listOf(ChatEntry("u", ChatRole.USER, "گزارش بده", 1)))
        assertTrue(step is AgentStep.Reply); assertEquals(1, requests)
    }
    @Test fun manualRetryAfterAuthenticationBillingQuotaOrPermissionDoesNotSendAnotherInference() = runBlocking {
        for (kind in ProviderFailurePolicy.NEVER_RETRY) {
            var requests = 0
            val provider = object : FunctionCallingProvider {
                override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
                    requests++; return FunctionResult.Failed(AiResult.Failure(kind, "mock"))
                }
            }
            val agent = PersianToolAgent(provider, runner)
            assertTrue(agent.start(listOf(ChatEntry("u", ChatRole.USER, "متن", 1))) is AgentStep.Failure)
            agent.retry(); assertEquals(1, requests)
        }
    }
    @Test fun zeroHistoryAndGreetingNeverSendOldPrivateMessagesAndTurnIdentityIsNotSerialized() = runBlocking {
        val transcripts = mutableListOf<List<FunctionMessage>>()
        val provider = object : FunctionCallingProvider {
            override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
                transcripts += messages; return FunctionResult.Reply(FunctionMessage("assistant", "سلام"), "mock")
            }
        }
        val old = listOf(ChatEntry("old", ChatRole.USER, "PRIVATE-OLD-MESSAGE", 1), ChatEntry("a", ChatRole.ASSISTANT, "PRIVATE-OLD-REPLY", 2))
        val agent = PersianToolAgent(provider, runner, { AIUsageLimits(historyMessages = 0) })
        agent.start(old + ChatEntry("new", ChatRole.USER, "پیام جدید", 3))
        agent.start(old + ChatEntry("greet", ChatRole.USER, "سلام", 4))
        assertTrue(transcripts.all { it.size == 2 && it.none { m -> "PRIVATE-OLD" in m.content } })
        val id = requireNotNull(transcripts.last().first().turnId)
        assertFalse(OpenRouterWire.messageJson(transcripts.last().first()).toString().contains(id))
        assertTrue(transcripts.last().first().content.toByteArray().size < 500)
    }
}
