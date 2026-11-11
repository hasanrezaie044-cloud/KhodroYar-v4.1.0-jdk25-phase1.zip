package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalAgentWorkflowTest {
    private class Port : AgentToolRunner {
        val executed = mutableListOf<ValidatedCall>()
        var prepared = 0
        override suspend fun execute(call: ValidatedCall): ToolOutcome { executed += call; return ToolOutcome(true, "واقعی") }
        override suspend fun prepare(call: ValidatedCall): ValidatedCall {
            prepared++
            return ValidatedCall(call.spec, call.args + ("app_owned_snapshot" to "frozen-values"))
        }
        override fun describe(call: ValidatedCall) = "${call.spec.name}: ${call.args["app_owned_snapshot"]}"
        override suspend fun describeTarget(call: ValidatedCall): String? = null
    }
    @Test fun multiTurnRecurringTaskStopsAtApprovalWithoutWritingOrCallingAModel() = runBlocking {
        val port = Port(); val a = LocalAssistant(port)
        assertTrue(a.run("روزای کاری هزینه قهوه‌مو ثبت کن") is AgentStep.Reply)
        val review = a.run("۵۰ هزار") as AgentStep.Confirm
        assertEquals("create_expense_rule", review.pending.call.spec.name)
        assertEquals("frozen-values", review.pending.call.str("app_owned_snapshot"))
        assertTrue(port.executed.isEmpty())
    }
    @Test fun correctionProducesANewApprovalSummaryNotAnImmediateMutation() = runBlocking {
        val p = Port(); val a = LocalAssistant(p)
        val first = a.run("هر روز قهوه ۵۰ هزار تومان ثبت کن") as AgentStep.Confirm
        val second = a.run("فقط روزهای کاری") as AgentStep.Confirm
        assertEquals("daily", first.pending.call.str("frequency"))
        assertEquals("workdays", second.pending.call.str("frequency"))
        assertTrue(p.executed.isEmpty()); assertEquals(2, p.prepared)
    }
    @Test fun allAutomationAndAlertChangesAreWriteTools() {
        for (name in listOf("create_expense_rule", "create_service_rule", "create_traffic_rule", "update_expense_rule", "set_automation_enabled", "set_traffic_alerts", "add_service_from_preset")) {
            assertEquals(name, ToolKind.WRITE, AgentToolRegistry.find(name)!!.kind)
        }
    }
    @Test fun unknownToolOrDestinationCannotExpandAppAccess() {
        assertTrue(AgentActionValidator.validate("run_sql", JSONObject()) is ValidationResult.Invalid)
        assertTrue(AgentActionValidator.validate("open_app_section", JSONObject().put("section", "https://example.com")) is ValidationResult.Invalid)
        assertTrue(AgentActionValidator.validate("open_app_section", JSONObject().put("section", "contacts")) is ValidationResult.Invalid)
    }
    @Test fun modelCannotInjectTrustedSnapshotOrRevisionThroughExtraArguments() {
        val v = AgentActionValidator.validate("set_automation_enabled", JSONObject().put("id", "abcd1234").put("state", "on")
            .put("service_snapshot", "injected").put("expected_revision", 999).put("operation_id", "injected")) as ValidationResult.Ok
        assertFalse(v.call.has("service_snapshot")); assertFalse(v.call.has("expected_revision")); assertFalse(v.call.has("operation_id"))
    }
    @Test fun invalidExpenseStillCannotCrossValidation() = runBlocking {
        val p = Port(); val a = LocalAssistant(p)
        assertTrue(a.run("هر روز قهوه منفی ۵۰ هزار تومان ثبت کن") is AgentStep.Reply)
        assertTrue(p.executed.isEmpty()); assertEquals(0, p.prepared)
    }
    @Test fun navigationIsARealTypedResultButDoesNotMutateData() = runBlocking {
        val port = object : AgentToolRunner {
            override suspend fun execute(call: ValidatedCall) = ToolOutcome(true, "بازشدن سوخت", AgentSection.FUEL)
            override fun describe(call: ValidatedCall) = "unused"
            override suspend fun describeTarget(call: ValidatedCall): String? = null
        }
        val result = LocalAssistant(port).run("برو تب سوخت") as AgentStep.Result
        assertEquals(AgentSection.FUEL, result.outcome.section)
    }
}
