package com.khodroyar.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionValidatorTest {

    private fun ok(name: String, json: String): ValidatedCall {
        val r = AgentActionValidator.validate(name, JSONObject(json))
        assertTrue("expected Ok but was $r", r is ValidationResult.Ok)
        return (r as ValidationResult.Ok).call
    }

    private fun bad(name: String, json: String) {
        val r = AgentActionValidator.validate(name, JSONObject(json))
        assertTrue("expected Invalid but was $r", r is ValidationResult.Invalid)
    }

    @Test fun unknownToolIsRejected() = bad("drop_table", "{}")

    @Test fun rawSqlLikeToolIsRejected() = bad("run_sql", """{"query":"DELETE FROM fuels"}""")

    @Test fun persianDigitsAndSeparatorsAreParsed() {
        val c = ok("add_expense", """{"amount":"۲٬۰۰۰٬۰۰۰","category":"food"}""")
        assertEquals(2_000_000.0, c.dbl("amount")!!, 0.0)
    }

    @Test fun missingRequiredAmountIsRejected() = bad("add_expense", """{"title":"x"}""")

    @Test fun negativeAndHugeAmountsAreRejected() {
        bad("add_expense", """{"amount":-5}""")
        bad("add_expense", """{"amount":1e15}""")
    }

    @Test fun gregorianAndFutureDatesAreRejected() {
        bad("add_expense", """{"amount":10,"date":"2025/01/01"}""")
        bad("add_expense", """{"amount":10,"date":"1599/01/01"}""")
    }

    @Test fun impossibleJalaliDateIsRejected() = bad("add_expense", """{"amount":10,"date":"1404/13/01"}""")

    @Test fun todayKeywordIsNormalised() {
        val c = ok("add_expense", """{"amount":10,"date":"today"}""")
        assertTrue(Regex("""\d{4}/\d{2}/\d{2}""").matches(c.str("date")!!))
    }

    @Test fun unknownEnumIsRejected() = bad("add_car_service", """{"service_type":"format-disk"}""")

    @Test fun updateNeedsAtLeastOneChange() = bad("update_expense", """{"id":"abcd1234"}""")

    @Test fun updateWithChangeIsAccepted() {
        val c = ok("update_expense", """{"id":"abcd1234","amount":500}""")
        assertEquals(ToolKind.WRITE, c.spec.kind)
    }

    @Test fun deleteToolsAreMarkedDestructive() {
        assertEquals(ToolKind.DELETE, ok("delete_fuel", """{"id":"abcd1234"}""").spec.kind)
    }

    @Test fun badIdIsRejected() = bad("delete_expense", """{"id":"1; DROP TABLE x"}""")

    @Test fun unknownArgsAreDropped() {
        val c = ok("get_today_expenses", """{"sql":"SELECT 1"}""")
        assertNull(c.args["sql"])
    }

    @Test fun onlyReadToolsRunWithoutConfirmation() {
        AgentToolRegistry.tools.forEach { t ->
            val readName = t.name.startsWith("get_") || t.name.startsWith("list_") || t.name.startsWith("analyze_") || t.name == "create_financial_report"
            assertEquals(t.name, readName, t.kind == ToolKind.READ)
        }
    }
}
