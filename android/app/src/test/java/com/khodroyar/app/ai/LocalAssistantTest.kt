package com.khodroyar.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalAssistantTest {
    private class FakeTools : AgentToolRunner {
        val calls = mutableListOf<ValidatedCall>()
        var outcome = ToolOutcome(true, "از داده‌های واقعی: ۱۲۳ تومان")
        var target: String? = "رکورد واقعی"
        override suspend fun execute(call: ValidatedCall): ToolOutcome { calls.add(call); return outcome }
        override fun describe(call: ValidatedCall): String = "خلاصهٔ معتبر: ${call.spec.name} ${call.dbl("amount")}"
        override suspend fun describeTarget(call: ValidatedCall): String? = target
    }

    @Test fun readsReturnTheActualToolResultNotAnInventedAnswer() = runBlocking {
        val tools = FakeTools()
        val step = LocalAssistant(tools).run("گزارش این ماه") as AgentStep.Result
        assertEquals(tools.outcome, step.outcome)
        assertEquals("get_monthly_report", tools.calls.single().spec.name)
    }

    @Test fun aWriteOnlyPreparesApprovalAndDoesNotTouchTheDatabase() = runBlocking {
        val tools = FakeTools()
        val step = LocalAssistant(tools).run("ثبت هزینه قهوه ۵۰ هزار تومان") as AgentStep.Confirm
        assertTrue(tools.calls.isEmpty())
        assertEquals(50_000.0, step.pending.call.dbl("amount")!!, 0.0)
        assertTrue(step.pending.summary.startsWith("خلاصهٔ معتبر:"))
        assertFalse(step.pending.destructive)
    }

    @Test fun everyLocalWriteTypeRequiresApproval() = runBlocking {
        listOf("ثبت هزینه قهوه ۵۰۰ تومان", "ثبت تعمیر ۲۰۰ هزار تومان", "ثبت تعویض روغن ۵۰۰ هزار تومان", "ثبت سوخت ۴۰ لیتر ۱۲۰ هزار تومان", "ثبت یادآور بیمه تاریخ ۱۵۰۰/۰۱/۰۱").forEach {
            val tools = FakeTools()
            assertTrue(it, LocalAssistant(tools).run(it) is AgentStep.Confirm)
            assertTrue(tools.calls.isEmpty())
        }
    }

    @Test fun invalidOrAmbiguousInputNeverReachesTheExecutor() = runBlocking {
        val tools = FakeTools()
        val assistant = LocalAssistant(tools)
        listOf("ثبت هزینه", "ثبت هزینه -۵۰۰ تومان", "ثبت سوخت ۶۰۰ لیتر ۱۰۰۰ تومان", "حذف همه هزینه ها", "drop_table").forEach {
            assertTrue(it, assistant.run(it) is AgentStep.Reply)
        }
        assertTrue(tools.calls.isEmpty())
    }

    @Test fun readFailuresStayFailuresInsteadOfBecomingFakeSuccess() = runBlocking {
        val tools = FakeTools().apply { outcome = ToolOutcome(false, "اطلاعات در دسترس نیست") }
        val step = LocalAssistant(tools).run("وضعیت خودرو") as AgentStep.Result
        assertFalse(step.outcome.ok)
        assertEquals(tools.outcome.text, step.outcome.text)
    }

    @Test fun sharedDeletionGateShowsTheRealTargetAndNeverExecutesIt() = runBlocking {
        val tools = FakeTools()
        val call = (AgentActionValidator.validate("delete_fuel", JSONObject().put("id", "abcd1234")) as ValidationResult.Ok).call
        val step = AgentApprovalGate(tools).prepare(call) as AgentStep.Confirm
        assertTrue(step.pending.destructive)
        assertEquals("رکورد واقعی", step.pending.target)
        assertTrue(tools.calls.isEmpty())
        tools.target = null
        assertTrue(AgentApprovalGate(tools).prepare(call) is AgentStep.Reply)
        assertTrue(tools.calls.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun cancellingAReadDoesNotTurnItIntoAnAnswer() = runBlocking {
        val tools = object : AgentToolRunner {
            override suspend fun execute(call: ValidatedCall): ToolOutcome = throw CancellationException("stopped")
            override fun describe(call: ValidatedCall) = "unused"
            override suspend fun describeTarget(call: ValidatedCall): String? = null
        }
        LocalAssistant(tools).run("گزارش این ماه")
        Unit
    }
}
