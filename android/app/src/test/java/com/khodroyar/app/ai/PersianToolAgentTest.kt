package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scripted native LLM responses test the app, not a keyword parser and not live model quality. */
class PersianToolAgentTest {
    private val f = OpenRouterFixture
    private class Script(vararg steps: FunctionResult) : FunctionCallingProvider {
        val replies = ArrayDeque(steps.toList())
        val transcripts = mutableListOf<List<FunctionMessage>>()
        override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
            transcripts += messages.toList()
            check(replies.isNotEmpty()) { "Unexpected automatic request" }
            return replies.removeFirst()
        }
    }
    private class Tools : AgentToolRunner {
        val executed = mutableListOf<ValidatedCall>(); val prepared = mutableListOf<ValidatedCall>()
        var fail = false
        override suspend fun prepare(call: ValidatedCall): ValidatedCall {
            prepared += call
            return ValidatedCall(call.spec, call.args + ("owned_snapshot" to "same exact approved operation"))
        }
        override fun describe(call: ValidatedCall) = "پیش‌نمایش دقیق ${call.spec.name}: ${call.args["owned_snapshot"]}"
        override suspend fun describeTarget(call: ValidatedCall) = "رکورد با شناسهٔ ثابت ${call.str("id")}"
        override suspend fun execute(call: ValidatedCall): ToolOutcome {
            executed += call
            return ToolOutcome(!fail, if (fail) "بازخوانی تأیید نشد" else "نتیجهٔ واقعی ${call.spec.name}: 365 سرویس، 21900 کیلومتر، 1095 ساعت")
        }
    }
    private fun reply(vararg calls: FunctionCall, text: String = "") = FunctionResult.Reply(FunctionMessage("assistant", text, calls.toList()), f.FREE)
    private fun history(text: String = "سرویس شب رو از اول فروردین ۱۴۰۴ تا آخر اسفند ۱۴۰۴ ثبت کن و بعد جمع درآمدشو بده؛ اگه چیزی کمه ازم بپرس.") =
        listOf(ChatEntry("user-1", ChatRole.USER, text, 1))
    private val bulkArgs = """{"start_date":"1404/01/01","end_date":"1404/12/29","service_type":"night","per_day":1,"days":"all","template_source":"explicit","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3}"""

    @Test fun colloquialMultipartPersianUsesNativeReadPlanApprovalWriteAndVerifiedContinuation() = runBlocking {
        val script = Script(reply(f.call("get_jalali_calendar", """{"year":1404}""", "c1")),
            reply(f.call("get_service_catalog", """{"year":1404}""", "c2")),
            reply(f.call("bulk_register_services", bulkArgs, "c3")),
            reply(f.call("report_services_range", """{"start_date":"1404/01/01","end_date":"1404/12/29","service_type":"night"}""", "c4")),
            reply(text = "گزارش طبق نتیجهٔ ابزار آماده است."))
        val tools = Tools(); val agent = PersianToolAgent(script, tools); val seen = mutableListOf<String>()
        val preview = agent.start(history(), onResult = { name, _ -> seen += name }) as AgentStep.Confirm
        assertEquals(listOf("get_jalali_calendar", "get_service_catalog"), seen)
        assertEquals(0, tools.executed.count { it.spec.kind != ToolKind.READ })
        assertTrue(preview.pending.summary.contains("bulk_register_services"))
        val final = agent.confirm(preview.pending, onResult = { name, _ -> seen += name }) as AgentStep.Reply
        assertEquals(1, tools.executed.count { it.spec.kind != ToolKind.READ })
        assertEquals(listOf("get_jalali_calendar", "get_service_catalog", "bulk_register_services", "report_services_range"), seen)
        assertTrue(final.text.contains("1 عملیات"))
        val transcript = script.transcripts.last()
        assertEquals(setOf("c1", "c2", "c3", "c4"), transcript.filter { it.role == "tool" }.map { it.toolCallId }.toSet())
        assertTrue(transcript.filter { it.role == "tool" }.all { JSONObject(it.content).getBoolean("ok") })
        assertEquals(history().single().text, script.transcripts.first().last().content)
    }
    @Test fun neitherJsonInProseNorAClaimOfSuccessCanWriteAnything() = runBlocking {
        val tools = Tools(); val agent = PersianToolAgent(Script(reply(text = "ثبت کردم! {\"action\":\"add_expense\",\"args\":{\"amount\":100}}")), tools)
        val result = agent.start(history("همین هزینه رو بزن")) as AgentStep.Reply
        assertTrue(tools.executed.isEmpty()); assertTrue(tools.prepared.isEmpty())
        assertTrue(result.text.contains("داده‌ای تغییر نکرده"))
    }
    @Test fun modelConfirmationWordsDoNotReplaceTheActualSingleUseAppTicket() = runBlocking {
        val tools = Tools(); val agent = PersianToolAgent(Script(reply(f.call("add_expense", """{"amount":100}""")), reply(text = "تمام شد")), tools)
        val preview = (agent.start(history()) as AgentStep.Confirm).pending
        val fake = PendingAction(preview.call, preview.summary, preview.target, preview.lead)
        assertTrue((agent.confirm(fake) as AgentStep.Reply).text.contains("منقضی")); assertTrue(tools.executed.isEmpty())
        agent.confirm(preview); agent.confirm(preview)
        assertEquals(1, tools.executed.size)
    }
    @Test fun everyWriteInOneMultiCallNeedsItsOwnApprovalAndQueueOrderIsPreserved() = runBlocking {
        val tools = Tools(); val script = Script(reply(f.call("add_expense", """{"amount":100,"title":"قهوه"}""", "a"),
            f.call("add_fuel", """{"liters":20,"total_cost":60000}""", "b")), reply(text = "دو نتیجه تأیید شد"))
        val agent = PersianToolAgent(script, tools)
        val first = agent.start(history()) as AgentStep.Confirm
        assertTrue(tools.executed.isEmpty())
        val second = agent.confirm(first.pending) as AgentStep.Confirm
        assertEquals(listOf("add_expense"), tools.executed.map { it.spec.name })
        agent.confirm(second.pending)
        assertEquals(listOf("add_expense", "add_fuel"), tools.executed.map { it.spec.name })
    }
    @Test fun deleteIsAPreviewAndRequiresASeparateExplicitButtonTicket() = runBlocking {
        val tools = Tools(); val agent = PersianToolAgent(Script(reply(f.call("delete_service", """{"id":"abcd1234"}""")), reply(text = "حذف تأیید شد")), tools)
        val preview = agent.start(history("اون سرویس تکراری رو پاک کن")) as AgentStep.Confirm
        assertTrue(preview.pending.destructive); assertNotNull(preview.pending.target); assertTrue(tools.executed.isEmpty())
        agent.confirm(preview.pending); assertEquals("delete_service", tools.executed.single().spec.name)
    }
    @Test fun retryAfterCommittedWriteContinuesTheTranscriptWithoutReplayingTheWrite() = runBlocking {
        val tools = Tools(); val script = Script(reply(f.call("add_expense", """{"amount":100}""")),
            FunctionResult.Failed(AiResult.Failure(AiErrorKind.OFFLINE, "اینترنت قطع است")), reply(text = "نتیجهٔ قبلی خوانده شد"))
        val agent = PersianToolAgent(script, tools)
        val preview = agent.start(history()) as AgentStep.Confirm
        assertTrue(agent.confirm(preview.pending) is AgentStep.Failure); assertTrue(agent.canResume)
        agent.retry(); assertEquals(1, tools.executed.size)
        assertTrue(script.transcripts.last().any { it.role == "tool" && JSONObject(it.content).getBoolean("ok") })
    }
    @Test fun duplicateCallsAndRepeatedSemanticWritesCannotWriteTwice() = runBlocking {
        val tools = Tools(); val script = Script(reply(f.call("add_expense", """{"amount":100}""", "a")),
            reply(f.call("add_expense", """{"amount":100}""", "b")))
        val agent = PersianToolAgent(script, tools); val preview = agent.start(history()) as AgentStep.Confirm
        val final = agent.confirm(preview.pending) as AgentStep.Reply
        assertTrue(final.text.contains("تکراری")); assertEquals(1, tools.executed.size)
    }
    @Test fun cancellationInvalidatesPreviewAndAllUnapprovedQueuedCalls() = runBlocking {
        val tools = Tools(); val agent = PersianToolAgent(Script(reply(f.call("add_expense", """{"amount":100}"""))), tools)
        val preview = agent.start(history()) as AgentStep.Confirm
        agent.reset(); agent.confirm(preview.pending)
        assertTrue(tools.executed.isEmpty()); assertFalse(agent.canResume)
    }
    @Test fun invalidUnknownInternalAndWrongTypedArgumentsNeverReachPreparationOrExecution() = runBlocking {
        for (call in listOf(f.call("run_sql", """{"query":"DELETE FROM services"}"""),
            f.call("add_expense", """{"amount":100,"created_record":{}}"""), f.call("add_expense", """{"amount":"100"}"""),
            f.call("add_expense", """{"amount":100,"date":"1404/12/30"}"""))) {
            val tools = Tools(); val script = Script(reply(call), reply(text = "اطلاعات را روشن کنید"))
            PersianToolAgent(script, tools).start(history())
            assertTrue(tools.executed.isEmpty()); assertTrue(tools.prepared.isEmpty())
            assertFalse(JSONObject(script.transcripts.last().last().content).getBoolean("ok"))
        }
    }
    @Test fun failedReadbackStopsSubsequentWritesAndNeverClaimsVerifiedSuccess() = runBlocking {
        val tools = Tools(); val agent = PersianToolAgent(Script(reply(f.call("add_expense", """{"amount":100}""", "a"),
            f.call("add_fuel", """{"liters":10,"total_cost":30000}""", "b"))), tools)
        val preview = agent.start(history()) as AgentStep.Confirm
        tools.fail = true
        val final = agent.confirm(preview.pending) as AgentStep.Reply
        assertTrue(final.text.contains("متوقف")); assertEquals(1, tools.executed.size); assertFalse(agent.canResume)
    }
    @Test fun loopsDuplicateIdsAndExcessRoundsAreBounded() = runBlocking {
        val tools = Tools(); val looping = (0..3).map { reply(f.call("get_today_expenses", id = "read$it")) }.toTypedArray()
        val final = PersianToolAgent(Script(*looping), tools).start(history()) as AgentStep.Reply
        assertTrue(final.text.contains("حلقه")); assertEquals(2, tools.executed.size)
        val bounded = Tools(); val unique = (0..8).map { reply(f.call("get_yearly_report", """{"year":${1400 + it}}""", "r$it")) }.toTypedArray()
        val end = PersianToolAgent(Script(*unique), bounded).start(history()) as AgentStep.Reply
        assertTrue(end.text.contains("5 مرحله")); assertEquals(5, bounded.executed.size)
    }
    @Test fun incompletePersianCanBeClarifiedWithNoToolAndPriorContextIsIncluded() = runBlocking {
        val script = Script(reply(text = "هر روز چند سرویس و چه مسیر و مقدار کیلومتر/ساعت؟")); val tools = Tools()
        val context = history("همهٔ شب‌ها رو بزن") + ChatEntry("a", ChatRole.ASSISTANT, "کدام سال؟", 2) + ChatEntry("b", ChatRole.USER, "۱۴۰۴، حتی تعطیلا", 3)
        val result = PersianToolAgent(script, tools).start(context) as AgentStep.Reply
        assertTrue(result.text.contains("چند سرویس")); assertEquals(4, script.transcripts.single().size); assertTrue(tools.executed.isEmpty())
    }
    @Test fun longInputAndCredentialsNeverGetSilentlyTruncatedIntoADifferentOperation() = runBlocking {
        val script = Script(); val tools = Tools(); val agent = PersianToolAgent(script, tools)
        agent.start(history("الف".repeat(6001))); agent.start(history("کلید: ${f.key}"))
        assertTrue(script.transcripts.isEmpty()); assertTrue(tools.executed.isEmpty())
    }
}
