package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Level-2 agent: persistent plans, operation ids, tool budget, cancellation, partial success and memory provenance. */
class AgentTaskPersistenceTest {
    private val f = OpenRouterFixture
    private class Script(vararg steps: FunctionResult) : FunctionCallingProvider {
        val replies = ArrayDeque(steps.toList()); val transcripts = mutableListOf<List<FunctionMessage>>()
        override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
            transcripts += messages.toList(); check(replies.isNotEmpty()) { "Unexpected automatic request" }; return replies.removeFirst()
        }
    }
    private class Tools(val writeOk: Boolean = true) : AgentToolRunner {
        val executed = mutableListOf<String>()
        override fun describe(call: ValidatedCall) = "پیش‌نمایش ${call.spec.name}"
        override suspend fun describeTarget(call: ValidatedCall): String? = null
        override suspend fun execute(call: ValidatedCall): ToolOutcome {
            executed += call.spec.name
            return if (call.spec.kind == ToolKind.READ || writeOk) ToolOutcome(true, "نتیجهٔ واقعی ${call.spec.name}") else ToolOutcome(false, "بازخوانی تأیید نشد")
        }
    }
    private fun reply(vararg calls: FunctionCall, text: String = "") = FunctionResult.Reply(FunctionMessage("assistant", text, calls.toList()), f.FREE)
    private fun user(text: String = "درآمد امروز را ثبت کن و بعد وام‌ها را بررسی کن") = listOf(ChatEntry("u", ChatRole.USER, text, 1))
    private fun <T> temp(block: (File) -> T): T { val dir = Files.createTempDirectory("agent").toFile(); return try { block(dir) } finally { dir.deleteRecursively() } }
    private val income = """{"amount":500000,"category":"bonus"}"""

    @Test fun anInterruptedPlanSurvivesARestartAndResumesOnlyInItsOwnConversation() = temp { dir ->
        runBlocking {
            val store = AgentTaskStore(File(dir, "agent/tasks.json"))
            val first = PersianToolAgent(Script(reply(f.call("plan_task", """{"goal":"ثبت درآمد و بررسی وام","step1":"ثبت درآمد","step2":"بررسی وام"}""", "p1")),
                reply(text = "برنامه آماده است")), Tools(), tasks = store, conversationId = { "conv-a" })
            first.start(user())
            val saved = store.latestFor("conv-a")!!
            assertEquals(AgentTaskStatus.ACTIVE, saved.status); assertEquals(2, saved.steps.size)

            // A fresh agent (process restart) restores the open task only for the same conversation.
            val script = Script(reply(text = "ادامه"))
            val resumed = PersianToolAgent(script, Tools(), tasks = store, conversationId = { "conv-a" })
            resumed.start(user("ادامه بده"))
            assertEquals(saved.id, resumed.scratchpad.taskId)
            assertTrue(script.transcripts.single().first().content.contains("ثبت درآمد — در انتظار"))

            val other = Script(reply(text = "سلام"))
            val elsewhere = PersianToolAgent(other, Tools(), tasks = store, conversationId = { "conv-b" })
            elsewhere.start(user("وضعیت خودرو"))
            assertFalse(elsewhere.scratchpad.active)
            assertFalse(other.transcripts.single().first().content.contains("ثبت درآمد"))
        }
    }

    @Test fun everyApprovedWriteGetsAnOperationIdThatIsPersistedAndSentToTheModel() = temp { dir ->
        runBlocking {
            val store = AgentTaskStore(File(dir, "tasks.json"))
            val script = Script(reply(f.call("plan_task", """{"goal":"ثبت درآمد","step1":"ثبت"}""", "p1"), f.call("add_income", income, "w1")),
                reply(text = "ثبت شد"))
            val tools = Tools(); val agent = PersianToolAgent(script, tools, tasks = store, conversationId = { "c1" })
            val shown = mutableListOf<ToolOutcome>()
            val confirm = agent.start(user()) as AgentStep.Confirm
            assertTrue("nothing is written before approval", tools.executed.isEmpty())
            agent.confirm(confirm.pending) { _, out -> shown += out }
            val opId = requireNotNull(shown.single().operationId)
            assertEquals(listOf("add_income"), tools.executed)
            val toolMessage = script.transcripts.last().last { it.role == "tool" && it.toolCallId == "w1" }
            assertEquals(opId, JSONObject(toolMessage.content).getString("operation_id"))
            val op = store.latestFor("c1")!!.operations.single()
            assertEquals(opId, op.id); assertTrue(op.ok); assertEquals("add_income", op.tool)
        }
    }

    @Test fun aFailedWriteStopsTheTaskAndReportsEarlierVerifiedWritesAsPartialSuccess() = temp { dir ->
        runBlocking {
            val store = AgentTaskStore(File(dir, "tasks.json"))
            var calls = 0
            val tools = object : AgentToolRunner {
                override fun describe(call: ValidatedCall) = "پیش‌نمایش"
                override suspend fun describeTarget(call: ValidatedCall): String? = null
                override suspend fun execute(call: ValidatedCall) = if (++calls == 1) ToolOutcome(true, "ثبت و بازخوانی شد") else ToolOutcome(false, "بازخوانی تأیید نشد")
            }
            val script = Script(reply(f.call("plan_task", """{"goal":"دو درآمد","step1":"اول","step2":"دوم"}""", "p1"), f.call("add_income", income, "w1")),
                reply(f.call("add_income", """{"amount":700000,"category":"bonus"}""", "w2")))
            val agent = PersianToolAgent(script, tools, tasks = store, conversationId = { "c2" })
            val first = agent.start(user()) as AgentStep.Confirm
            val second = agent.confirm(first.pending) as AgentStep.Confirm
            val final = agent.confirm(second.pending) as AgentStep.Reply
            assertTrue(final.text, final.text.contains("موفقیت جزئی"))
            assertTrue(final.text.contains("1 عملیات"))
            val task = store.latestFor("c2")!!
            assertEquals(AgentTaskStatus.FAILED, task.status)
            assertEquals(listOf(true, false), task.operations.map { it.ok })
            assertEquals(2, calls) // the failed write is never retried automatically
        }
    }

    @Test fun moreThanTwelveToolCallsInOneRequestStopWithoutLosingThePlan() = runBlocking {
        val reads = (1..13).map { f.call("search_records", """{"query":"مورد$it"}""", "r$it") }.toTypedArray()
        val script = Script(reply(f.call("plan_task", """{"goal":"جستجوی گسترده","step1":"جستجو"}""", "p1"), *reads))
        val tools = Tools(); val agent = PersianToolAgent(script, tools)
        val step = agent.start(user("همه چیز را بگرد")) as AgentStep.Reply
        assertEquals(AgentScratchpad.MAX_TOOL_CALLS, tools.executed.size)
        assertTrue(step.text, step.text.contains("سقف 12"))
        assertTrue(agent.scratchpad.active)
    }

    @Test fun planAcceptsEightStepsAndANewGoalClosesTheOldTaskVisibly() {
        val saved = mutableListOf<AgentTask>()
        val pad = AgentScratchpad(persist = { saved += it })
        fun plan(json: String) = pad.plan((FunctionToolSchemas.validate(f.call("plan_task", json)) as ValidationResult.Ok).call)
        val eight = (1..8).joinToString(",") { "\"step$it\":\"مرحله $it\"" }
        assertTrue(plan("""{"goal":"هدف اول",$eight}""").ok)
        assertEquals(8, pad.steps.size)
        val firstId = pad.taskId
        val next = plan("""{"goal":"هدف دوم","step1":"یک"}""")
        assertTrue(next.text, next.text.contains("کار قبلی «هدف اول»"))
        assertNotEquals(firstId, pad.taskId)
        assertEquals(AgentTaskStatus.PARTIAL, saved.last { it.id == firstId }.status)
        assertEquals(AgentTaskStatus.CANCELLED, pad.cancel()!!.status)
        assertFalse(pad.active)
    }

    @Test fun storeIsBoundedAndTolerantOfCorruptFiles() = temp { dir ->
        val file = File(dir, "tasks.json"); file.writeText("{not json")
        val store = AgentTaskStore(file)
        assertTrue(store.all().isEmpty())
        repeat(AgentTaskStore.MAX_TASKS + 5) { i ->
            store.upsert(AgentTask("task-$i", "c", "هدف $i", listOf(AgentScratchpad.Step("گام")), createdAt = i.toLong(), updatedAt = i.toLong()))
        }
        assertEquals(AgentTaskStore.MAX_TASKS, store.all().size)
        assertEquals("task-5", store.all().first().id)
        assertTrue(store.clear()); assertTrue(store.all().isEmpty())
    }

    @Test fun codecRoundTripsFailsClosedOnNewerFormatsAndUpsertReportsTheNormalisedCopy() = temp { dir ->
        val task = AgentTask("task-codec", "c", "هدف", listOf(AgentScratchpad.Step("گام", AgentScratchpad.Status.DONE, "add_expense")),
            listOf(AgentOperation("op-codec-1", "add_expense", true, "ثبت شد", 5)), AgentTaskStatus.PARTIAL, 1, 5)
        assertEquals(listOf(task), AgentTaskCodec.decode(AgentTaskCodec.encode(listOf(task))))
        assertTrue(runCatching { AgentTaskCodec.decode(JSONObject("""{"version":7,"tasks":[]}""")) }.isFailure)
        assertTrue(AgentTaskCodec.decode(JSONObject("""{"version":1,"tasks":[{"id":"x"},{"id":"task-ok","goal":"","steps":[]},42]}""")).isEmpty())

        val store = AgentTaskStore(File(dir, "tasks.json"))
        val long = task.copy(id = "task-long", goal = "ا".repeat(400) + " sk-or-" + "z".repeat(40))
        assertTrue("a bounded/redacted copy is still a successful save", store.upsert(long))
        val saved = store.all().single()
        assertTrue(saved.goal.length <= 160); assertFalse(saved.goal.contains("sk-or-"))
        assertFalse("invalid ids are refused, not guessed", store.upsert(task.copy(id = "x")))
    }

    @Test fun memoryEditsKeepProvenanceAndBackupRestoreReFiltersSecrets() = temp { dir ->
        var now = 10L
        val store = AssistantMemoryStore(File(dir, "facts.json")) { now++ }
        val fact = requireNotNull(store.remember("سوخت را از جایگاه نزدیک خانه می‌زنم", "vehicle"))
        assertEquals(MemoryFact.SOURCE_CHAT, fact.source); assertEquals(1.0, fact.confidence, 0.0)
        val edited = requireNotNull(store.update(fact.id, "سوخت را از جایگاه سر کوچه می‌زنم", "vehicle"))
        assertEquals(MemoryFact.SOURCE_MANUAL, edited.source); assertTrue(edited.updatedAt > fact.createdAt)
        assertNull("secrets are refused on edit too", store.update(fact.id, "رمز کارت 1234", "other"))
        val restored = listOf(edited, MemoryFact("x1", "6037 9912 3456 7890", "finance", 1), MemoryFact("x2", "sk-or-" + "a".repeat(40), "other", 2))
        assertTrue(store.replaceAll(restored))
        assertEquals(listOf(edited.text), AssistantMemoryStore(File(dir, "facts.json")).all().map { it.text })
    }
}
