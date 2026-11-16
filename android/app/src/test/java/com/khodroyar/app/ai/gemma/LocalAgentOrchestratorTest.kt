package com.khodroyar.app.ai.gemma

import com.khodroyar.app.ai.AgentStep
import com.khodroyar.app.ai.AgentToolExecutorApi
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.ToolOutcome
import com.khodroyar.app.ai.ValidatedCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The offline agent loop with a scripted engine and a scripted executor: proves that model
 * output alone can never write (WRITE/DELETE always end in a Confirm step), that READ tools
 * loop back into the model, and that invalid tool calls are pushed back for a retry.
 */
class LocalAgentOrchestratorTest {

    private class FakeEngine(vararg val scripted: String) : LocalLlmEngine {
        var calls = 0
        val requests = ArrayList<LocalLlmEngine.Request>()
        var failWith: LocalLlmEngine.EngineError? = null

        override suspend fun complete(request: LocalLlmEngine.Request): LocalLlmEngine.Reply {
            requests.add(request)
            failWith?.let { return LocalLlmEngine.Reply.Failure(it, "boom") }
            val idx = calls++
            val script = scripted.getOrNull(idx) ?: """{"action":"reply","text":"...fallback..."}"""
            return LocalLlmEngine.Reply.Text(script)
        }

        override fun isLoaded(): Boolean = true
    }

    private class FakeExecutor : AgentToolExecutorApi {
        val executed = ArrayList<String>()
        override suspend fun execute(call: ValidatedCall): ToolOutcome {
            executed.add(call.spec.name)
            return ToolOutcome(true, "خروجی آزمایشی ابزار ${call.spec.name}")
        }

        override suspend fun describe(call: ValidatedCall): String = "خلاصهٔ ${call.spec.name}"
        override suspend fun describeTarget(call: ValidatedCall): String? = "رکورد آزمایشی"
    }

    private fun userTurn(text: String) = listOf(
        ChatEntry(UUID.randomUUID().toString(), ChatRole.USER, text, System.currentTimeMillis()),
    )

    @Test fun plainReplyPassesThrough() = runBlocking {
        val engine = FakeEngine("""{"action":"reply","text":"سلام، در خدمتم."}""")
        val step = LocalAgentOrchestrator(engine, FakeExecutor()).run(userTurn("سلام"))
        assertTrue(step is AgentStep.Reply)
        assertEquals("سلام، در خدمتم.", (step as AgentStep.Reply).text)
        assertEquals(1, engine.calls)
    }

    @Test fun readToolLoopsResultBackIntoModel() = runBlocking {
        val engine = FakeEngine(
            """{"action":"tool","tool":"get_totals","args":{"period":"month"},"say":"نگاه می‌کنم"}""",
            """{"action":"reply","text":"جمع این ماه ۱۲۳ تومان است."}""",
        )
        val exec = FakeExecutor()
        val step = LocalAgentOrchestrator(engine, exec).run(userTurn("جمع هزینه‌های این ماه؟"))
        assertTrue(step is AgentStep.Reply)
        assertEquals("جمع این ماه ۱۲۳ تومان است.", (step as AgentStep.Reply).text)
        assertEquals(listOf("get_totals"), exec.executed)
        assertEquals(2, engine.calls)
        // The tool result must have been fed back as a user-side turn.
        assertTrue(engine.requests[1].userText.contains("نتیجه واقعی ابزار get_totals"))
    }

    @Test fun writeToolNeverExecutesWithoutConfirm() = runBlocking {
        val engine = FakeEngine(
            """{"action":"tool","tool":"add_fuel","args":{"liters":35,"total_cost":1500000},"say":"ثبت سوخت"}""",
        )
        val exec = FakeExecutor()
        val step = LocalAgentOrchestrator(engine, exec).run(userTurn("۳۵ لیتر بنزین یک و نیم میلیون تومان ثبت کن"))
        assertTrue(step is AgentStep.Confirm)
        assertFalse(exec.executed.contains("add_fuel")) // NOT executed — user must approve
        val p = (step as AgentStep.Confirm).pending
        assertEquals("add_fuel", p.call.spec.name)
        assertFalse(p.destructive)
        // …and after approval the SAME validated call executes.
        val out = LocalAgentOrchestrator(engine, exec).execute(p)
        assertTrue(out.ok)
        assertTrue(exec.executed.contains("add_fuel"))
    }

    @Test fun deleteIsAlwaysDestructiveConfirm() = runBlocking {
        val engine = FakeEngine("""{"action":"tool","tool":"delete_service","args":{"id":"abcd1234"}}""")
        val step = LocalAgentOrchestrator(engine, FakeExecutor()).run(userTurn("این سرویس را حذف کن"))
        assertTrue(step is AgentStep.Confirm)
        assertTrue((step as AgentStep.Confirm).pending.destructive)
    }

    @Test fun invalidToolCallIsGivenOneRetry() = runBlocking {
        val engine = FakeEngine(
            """{"action":"tool","tool":"add_expense","args":{}}""", // missing required amount
            """{"action":"reply","text":"مبلغ را بگویید لطفاً."}""",
        )
        val exec = FakeExecutor()
        val step = LocalAgentOrchestrator(engine, exec).run(userTurn("هزینه ثبت کن"))
        assertTrue(step is AgentStep.Reply)
        assertEquals(0, exec.executed.size) // invalid call never touched execution
        assertEquals(2, engine.calls)
        assertTrue(engine.requests[1].userText.contains("اعتبارسنجی ناموفق"))
    }

    @Test fun unknownToolIsRejectedByValidator() = runBlocking {
        val engine = FakeEngine(
            """{"action":"tool","tool":"wipe_database","args":{}}""",
            """{"action":"reply","text":"این کار مجاز نیست."}""",
        )
        val exec = FakeExecutor()
        val step = LocalAgentOrchestrator(engine, exec).run(userTurn("همه چیز را پاک کن"))
        assertTrue(exec.executed.isEmpty())
        assertEquals("این کار مجاز نیست.", (step as AgentStep.Reply).text)
    }

    @Test fun engineFailureBecomesFailureStep() = runBlocking {
        val engine = FakeEngine().apply { failWith = LocalLlmEngine.EngineError.OUT_OF_MEMORY }
        val step = LocalAgentOrchestrator(engine, FakeExecutor()).run(userTurn("سلام"))
        assertTrue(step is AgentStep.Failure)
        assertTrue((step as AgentStep.Failure).error.message.contains("حافظهٔ"))
    }

    @Test fun imageIsSentWithTheRequest() = runBlocking {
        val engine = FakeEngine("""{"action":"reply","text":"یک برگه گزارش است."}""")
        val img = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val step = LocalAgentOrchestrator(engine, FakeExecutor()).run(userTurn("این چیه؟"), imageBytes = img)
        assertTrue(step is AgentStep.Reply)
        assertTrue(engine.requests[0].imageBytes!!.contentEquals(img))
    }

    @Test fun historyIsBounded() = runBlocking {
        val engine = FakeEngine("""{"action":"reply","text":"ok"}""")
        val many = (1..40).map {
            ChatEntry(UUID.randomUUID().toString(), ChatRole.USER, "پیام شماره $it " + "طولانی ".repeat(50), it.toLong())
        }
        LocalAgentOrchestrator(engine, FakeExecutor()).run(many)
        val sent = engine.requests[0]
        assertTrue(sent.history.size <= LocalAgentOrchestrator.MAX_HISTORY_TURNS)
        assertTrue(sent.history.sumOf { it.text.length } <= LocalAgentOrchestrator.MAX_HISTORY_CHARS)
    }
}
