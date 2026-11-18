package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/** Cloud-agent upgrade: verifiable plan, on-device memory, image-aware and replay-safe model pool. */
class AssistantAgentUpgradeTest {
    private val f = OpenRouterFixture
    private class Script(vararg steps: FunctionResult) : FunctionCallingProvider {
        val replies = ArrayDeque(steps.toList()); val transcripts = mutableListOf<List<FunctionMessage>>()
        override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
            transcripts += messages.toList(); check(replies.isNotEmpty()) { "Unexpected automatic request" }; return replies.removeFirst()
        }
    }
    private class Tools : AgentToolRunner {
        val executed = mutableListOf<String>()
        override fun describe(call: ValidatedCall) = "پیش‌نمایش ${call.spec.name}"
        override suspend fun describeTarget(call: ValidatedCall): String? = null
        override suspend fun execute(call: ValidatedCall): ToolOutcome { executed += call.spec.name; return ToolOutcome(true, "نتیجهٔ واقعی ${call.spec.name}") }
    }
    private fun reply(vararg calls: FunctionCall, text: String = "") = FunctionResult.Reply(FunctionMessage("assistant", text, calls.toList()), f.FREE)
    private val user = listOf(ChatEntry("u", ChatRole.USER, "هزینه‌های امروز را بگو و وام‌هایم را هم بررسی کن", 1))
    private fun toolResults(t: List<FunctionMessage>) = t.filter { it.role == "tool" }.associate { it.toolCallId to JSONObject(it.content) }

    @Test fun planStepCannotBeMarkedDoneWithoutARealSuccessfulToolAndFinalReplyListsOpenSteps() = runBlocking {
        val script = Script(
            reply(f.call("plan_task", """{"goal":"بررسی هزینه و وام","step1":"هزینه امروز","step2":"وام‌ها"}""", "p1")),
            reply(f.call("mark_step", """{"step":1,"status":"done","evidence_tool":"get_today_expenses"}""", "m1")),
            reply(f.call("get_today_expenses", "{}", "r1")),
            reply(f.call("mark_step", """{"step":1,"status":"done","evidence_tool":"get_today_expenses"}""", "m2")),
            reply(text = "هزینهٔ امروز گزارش شد."))
        val tools = Tools(); val agent = PersianToolAgent(script, tools)
        // Plan-only batches read no data, so they continue without asking the owner to press continue.
        assertTrue(agent.start(user) is AgentStep.Continue)
        assertEquals(listOf("get_today_expenses"), tools.executed)
        val early = toolResults(script.transcripts[2])["m1"]!!
        assertFalse("model claim without evidence must be rejected", early.getBoolean("ok"))
        val final = agent.retry() as AgentStep.Reply
        assertTrue(toolResults(script.transcripts.last())["m2"]!!.getBoolean("ok"))
        assertTrue(final.text, final.text.contains("1 از 2 مرحله تأیید نشده"))
        assertTrue(final.text.contains("وام‌ها"))
        assertTrue(agent.scratchpad.active)
        agent.clearPlan(); assertFalse(agent.scratchpad.active)
    }

    @Test fun planContextAndMemoryCountReachThePromptButMemoryContentDoesNot() = runBlocking {
        val script = Script(reply(f.call("plan_task", """{"goal":"ثبت قسط","step1":"فهرست وام"}""", "p1")), reply(text = "باشه"),
            reply(text = "ادامه"))
        val agent = PersianToolAgent(script, Tools(), memoryCount = { 3 })
        agent.start(user)
        agent.start(user + ChatEntry("u2", ChatRole.USER, "ادامه بده", 2))
        val system = script.transcripts.last().first().content
        assertTrue(system.contains("3 نکتهٔ تأییدشده")); assertTrue(system.contains("فهرست وام — در انتظار"))
    }

    @Test fun anApprovedWriteIsExecutedOnceAndAReplayedProposalNeedsNoSecondExecution() = runBlocking {
        val args = """{"amount":500000,"category":"bonus"}"""
        val script = Script(reply(f.call("add_income", args, "w1")), reply(f.call("add_income", args, "w2")))
        val tools = Tools(); val agent = PersianToolAgent(script, tools)
        val confirm = agent.start(user) as AgentStep.Confirm
        assertTrue(tools.executed.isEmpty())
        val after = agent.confirm(confirm.pending)
        assertTrue(after is AgentStep.Reply); assertEquals(listOf("add_income"), tools.executed)
    }

    @Test fun memoryStoresVerifiedFactsRefusesSecretsAndRecallsByTopic() {
        val dir = java.nio.file.Files.createTempDirectory("memory").toFile(); try {
            var now = 1L
            val store = AssistantMemoryStore(File(dir, "m/facts.json")) { now++ }
            val car = requireNotNull(store.remember("خودروی من پژو ۴۰۵ دوگانه‌سوز است", "vehicle"))
            store.remember("گزارش‌ها را کوتاه می‌خواهم", "preference")
            assertNull(store.remember("رمز کارتم 1234 است"))
            assertNull(store.remember("6037 9912 3456 7890"))
            assertNull(store.remember("sk-or-" + "a".repeat(40)))
            assertEquals(car.id, store.remember("خودروی من پژو ۴۰۵ دوگانه‌سوز است")!!.id) // no duplicate
            assertEquals(2, store.count())
            assertEquals(car.id, store.recall("پژو").first().id)
            assertEquals(car.id, AssistantMemoryStore(File(dir, "m/facts.json")).recall("405").single().id) // persisted, digits folded
            assertTrue(store.forget(car.id)); assertNull(store.find(car.id)); assertEquals(1, store.count())
            assertTrue(store.clear()); assertEquals(0, store.count())
        } finally { dir.deleteRecursively() }
    }

    // ----- model pool -----
    private val now = 1_800_000_000_000L
    private val text = AIModelKey("future-provider", "test/text")
    private val vision = AIModelKey("future-provider", "test/vision")
    private fun model(key: AIModelKey, image: Boolean) = AIModel(key, key.id, "Future", "test", AIModelPricing(BigDecimal.ZERO, BigDecimal.ZERO, true),
        AIModelCapability(true, 65536, imageInput = image), AIModelAuthorization.AUTHORIZED, now, "fp")
    private class Store : AIModelEvaluationStore {
        override fun load(scope: String) = emptyList<AIModelEvaluation>()
        override fun save(scope: String, result: AIModelEvaluation) = Unit
        override fun reserve(scope: String, now: Long, automatic: Boolean) = true
    }
    private inner class Adapter(val handler: (AIModelKey) -> FunctionResult) : AIModelAdapter {
        override val providerId = "future-provider"
        val requests = mutableListOf<AIModelKey>()
        override fun invalidate() = Unit
        override suspend fun discover(force: Boolean) = AIModelDiscovery.Found(listOf(model(text, false), model(vision, true)), now)
        override suspend fun availability(model: AIModel, force: Boolean) = AIModelAvailability(true, now, listOf("Synthetic"), 99.0)
        override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult { requests += model; return handler(model) }
    }
    private fun managed(adapter: Adapter) = ManagedAIProvider(adapter, { AISelectionSettings(true, true, "acct", allowModelFallback = true) }, Store(),
        SafePersianModelEvaluator { now }, { now })
    private val fail = FunctionResult.Failed(AiResult.Failure(AiErrorKind.SERVER, "synthetic"))

    @Test fun imageTurnsOnlyEverReachVisionCapableModels() = runBlocking {
        val adapter = Adapter { key -> FunctionResult.Reply(FunctionMessage("assistant", "دیدم"), key.id) }
        val image = AIImage("image/jpeg", "AAAA")
        val result = managed(adapter).complete(listOf(FunctionMessage("user", "این فاکتور چیست؟", images = listOf(image), turnId = "t1")))
        assertTrue(result is FunctionResult.Reply); assertEquals(listOf(vision), adapter.requests)
    }

    @Test fun imageFallbackSkipsTextOnlyModelsAndNeverSendsTheImageToThem() = runBlocking {
        val adapter = Adapter { key -> if (key == vision) fail else error("text-only model must not receive an image") }
        val image = AIImage("image/jpeg", "AAAA")
        assertTrue(managed(adapter).complete(listOf(FunctionMessage("user", "رسید", images = listOf(image), turnId = "t2"))) is FunctionResult.Failed)
        assertEquals(listOf(vision), adapter.requests)
    }
}
