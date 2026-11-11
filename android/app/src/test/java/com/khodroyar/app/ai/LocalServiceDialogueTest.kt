package com.khodroyar.app.ai

import com.khodroyar.app.ai.albert.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regression for a real two-turn conversation; no model is needed to retain an explicit correction. */
class LocalServiceDialogueTest {
    private val today = "1405/08/20"
    private fun engine() = LocalDialogueEngine(today = { today }, zone = { "Asia/Tehran" })
    private fun tool(e: LocalDialogueEngine, text: String): LocalIntent.Tool {
        val result = e.parse(text)
        assertTrue("$text -> $result", result is LocalIntent.Tool)
        return result as LocalIntent.Tool
    }
    private fun reply(e: LocalDialogueEngine, text: String): String {
        val result = e.parse(text)
        assertTrue("$text -> $result", result is LocalIntent.Reply)
        return (result as LocalIntent.Reply).text
    }

    @Test fun exactReportedConversationRevisesTheSameUnwrittenTrip() {
        val e = engine()
        val first = tool(e, "امشب ی سرویس رفتم ثبت کن")
        assertEquals("add_service_from_preset", first.name)
        assertEquals(today, first.args.getString("date"))
        assertFalse("Tonight is a date, not proof of the app's NIGHT tariff category", first.args.has("service_type"))
        val corrected = tool(e, "ن سرویس شب بود")
        assertEquals(first.name, corrected.name)
        assertEquals(today, corrected.args.getString("date"))
        assertEquals("night", corrected.args.getString("service_type"))
    }

    @Test fun shortCorrectionsAndRepeatedSameValueAreRelatedNotNewTasks() {
        for (correction in listOf("نه، سرویس شب بود", "ن سرویس شب بود", "نه شب بود", "شب", "نوعش شب بود")) {
            val e = engine()
            tool(e, "سرویس درخواستی دیروز را ثبت کن")
            for (i in 1..2) {
                val c = tool(e, correction)
                assertEquals("night", c.args.getString("service_type"))
                assertEquals("1405/08/19", c.args.getString("date"))
            }
        }
    }

    @Test fun tonightAndLastNightCorrectTheDateWithoutInventingTheTariffCategory() {
        val e = engine()
        tool(e, "سرویس دیروز را ثبت کن")
        assertEquals(today, tool(e, "نه امشب بود").args.getString("date"))
        assertEquals("1405/08/19", tool(e, "نه دیشب بود").args.getString("date"))
        assertEquals("1405/08/21", tool(e, "نه فرداشب بود").args.getString("date"))
        // Future one-off dates are still rejected by the authoritative validator.
        assertTrue(AgentActionValidator.validate("add_service_from_preset", JSONObject().put("date", "1599/01/01")) is ValidationResult.Invalid)
    }

    @Test fun nightCategoryDoesNotChangeAnApprovedAutomationScheduleIntoNinePm() {
        val e = engine()
        val first = tool(e, "هر روز ساعت ۸ صبح سرویس درخواستی ثبت کن")
        assertEquals("request", first.args.getString("service_type"))
        val corrected = tool(e, "نه سرویس شب بود")
        assertEquals("create_service_rule", corrected.name)
        assertEquals("night", corrected.args.getString("service_type"))
        assertEquals("08:00", corrected.args.getString("time"))
        assertEquals("21:00", tool(e, "ساعت ۹ شب").args.getString("time"))
    }

    @Test fun clockOnlyCorrectionIsNotAChangeOfServiceCategory() {
        val e = engine()
        tool(e, "هر روز ساعت ۸ صبح سرویس درخواستی ثبت کن")
        val clock = tool(e, "نه ساعت ۹ شب")
        assertEquals("21:00", clock.args.getString("time"))
        assertEquals("request", clock.args.getString("service_type"))
    }

    @Test fun negatedUnsupportedAndConflictingCategoriesAskInsteadOfChoosingNight() {
        for (correction in listOf("نه سرویس شب نبود", "سرویس روز بود", "نوعش روز بود", "سرویس شب یا تعطیل بود", "سرویس شب و درخواستی بود")) {
            val e = engine()
            tool(e, "سرویس درخواستی امروز را ثبت کن")
            val answer = reply(e, correction)
            assertFalse(answer.contains("ربط ندادم"))
            assertFalse(answer.contains("ثبت شد"))
            assertTrue(reply(e, "بله").contains("نوع"))
            assertEquals("request", tool(e, "سرویس درخواستی").args.getString("service_type"))
        }
    }

    @Test fun aNewConversationOrCancelledDraftCannotBeResurrectedByNightWasTheType() {
        val e = engine()
        reply(e, "ن سرویس شب بود")
        tool(e, "امشب ی سرویس رفتم ثبت کن")
        reply(e, "لغو")
        reply(e, "ن سرویس شب بود")
        assertEquals("", e.context.title)
    }

    @Test fun explicitServiceHistoryQuestionsRemainReadsNotCorrectionDrafts() {
        for (text in listOf("لیست سرویس شب", "گزارش سرویس شب", "سوابق سرویس درخواستی")) {
            val e = engine()
            val result = tool(e, text)
            assertEquals(ToolKind.READ, AgentToolRegistry.find(result.name)!!.kind)
            assertEquals("", e.context.title)
        }
    }

    @Test fun fullNegationAndHypotheticalRemainNonWrites() {
        for (text in listOf("سرویس شب ثبت نکن", "اگر رفتم سرویس شب ثبت کن", "فرض کن سرویس شب رفتم")) reply(engine(), text)
        val e = engine()
        tool(e, "امشب ی سرویس رفتم ثبت کن")
        reply(e, "نه ثبتش نکن")
        reply(e, "ن سرویس شب بود")
    }

    @Test fun onlyAllowListedTripCategoriesCrossTheToolBoundaryAndSnapshotsCannotBeInjected() {
        val c = AgentActionValidator.validate("add_service_from_preset", JSONObject().put("service_type", "night")
            .put("date", "today").put("service_snapshot", "injected").put("amount", 1)) as ValidationResult.Ok
        assertEquals("night", c.call.str("service_type"))
        assertFalse(c.call.has("service_snapshot")); assertFalse(c.call.has("amount"))
        for (type in listOf("day", "unknown", "fixed-salary", "leave")) {
            assertTrue(AgentActionValidator.validate("add_service_from_preset", JSONObject().put("service_type", type)) is ValidationResult.Invalid)
        }
    }

    @Test fun aCorrectionNeedsFreshPreparationAndNeverCallsTheWriteOrModelAgain() = runBlocking {
        val prepared = mutableListOf<ValidatedCall>()
        val executed = mutableListOf<ValidatedCall>()
        var predictions = 0
        val tools = object : AgentToolRunner {
            override suspend fun prepare(call: ValidatedCall): ValidatedCall {
                prepared += call
                return ValidatedCall(call.spec, call.args + ("operation_id" to "revision-${prepared.size}"))
            }
            override suspend fun execute(call: ValidatedCall): ToolOutcome { executed += call; return ToolOutcome(true, "saved") }
            override fun describe(call: ValidatedCall) = "${call.str("service_type")} ${call.str("date")}"
            override suspend fun describeTarget(call: ValidatedCall): String? = null
        }
        val a = LocalAssistant(tools, dialogue = engine(), model = PersianIntentModel {
            predictions++
            PersianIntentPrediction(PersianIntent.SERVICE, .99, .98, true)
        })
        val first = a.run("امشب ی سرویس رفتم ثبت کن") as AgentStep.Confirm
        val revised = a.run("ن سرویس شب بود") as AgentStep.Confirm
        assertEquals(1, predictions)
        assertEquals(2, prepared.size)
        assertEquals("night", revised.pending.call.str("service_type"))
        assertNotEquals(first.pending.call.str("operation_id"), revised.pending.call.str("operation_id"))
        assertTrue(executed.isEmpty())
    }
}
