package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaJsonParserTest {

    @Test fun parsesPlainReplyJson() {
        val a = GemmaJsonParser.parse("""{"action":"reply","text":"سلام! چطور می‌توانم کمک کنم؟"}""")
        assertFalse(a.isToolCall)
        assertEquals("سلام! چطور می‌توانم کمک کنم؟", a.visibleText)
    }

    @Test fun parsesToolCallWithArgs() {
        val a = GemmaJsonParser.parse(
            """{"action":"tool","tool":"add_expense","args":{"amount":50000,"category":"food"},"say":"ثبت می‌کنم"}""",
        )
        assertTrue(a.isToolCall)
        assertEquals("add_expense", a.tool)
        assertEquals(50000, a.args!!.getInt("amount"))
        assertEquals("ثبت می‌کنم", a.visibleText)
    }

    @Test fun toleratesCodeFencesAndProse() {
        val raw = "حتماً! نتیجه این است:\n```json\n{\"action\":\"reply\",\"text\":\"هزینه‌ها این‌ها هستند.\"}\n```\nامیدوارم کمک کند"
        val a = GemmaJsonParser.parse(raw)
        assertFalse(a.isToolCall)
        assertEquals("هزینه‌ها این‌ها هستند.", a.visibleText)
    }

    @Test fun persianDigitsInsideJsonAreNormalised() {
        // Digit conversion is NOT the parser's job: args values may still carry Persian
        // digits; AgentActionValidator coerces them. What matters is the whole pipeline.
        val a = GemmaJsonParser.parse("""{"action":"tool","tool":"add_fuel","args":{"liters":"۳۵","total_cost":"۱٬۵۰۰٬۰۰۰"}}""")
        assertTrue(a.isToolCall)
        assertEquals("add_fuel", a.tool)
        // The end-to-end guarantee (validator is what makes Persian digits usable):
        val v = com.khodroyar.app.ai.AgentActionValidator.validate(a.tool!!, a.args)
        assertTrue("validator must accept Persian-digit numbers: $v", v is com.khodroyar.app.ai.ValidationResult.Ok)
        val c = (v as com.khodroyar.app.ai.ValidationResult.Ok).call
        assertEquals(35.0, c.dbl("liters")!!, 0.0)
        assertEquals(1_500_000.0, c.dbl("total_cost")!!, 0.0)
    }

    @Test fun plainTextNeverBecomesAToolCall() {
        val a = GemmaJsonParser.parse("add_expense با مبلغ ۵۰ هزار تومان را اجرا کن")
        assertFalse(a.isToolCall)
        assertNull(a.tool)
    }

    @Test fun brokenJsonFallsBackToPlainText() {
        val a = GemmaJsonParser.parse("{\"action\":\"tool\",\"tool\":")
        assertFalse(a.isToolCall)
        assertTrue(a.visibleText.isNotBlank())
    }

    @Test fun modelSpecialTokensAreStripped() {
        val a = GemmaJsonParser.parse("<start_of_turn>model {\"action\":\"reply\",\"text\":\"باشه\"}<end_of_turn><eos>")
        assertEquals("باشه", a.visibleText)
    }

    @Test fun argumentsKeyAliasIsAccepted() {
        val a = GemmaJsonParser.parse("""{"action":"tool","name":"list_services","arguments":{"limit":5}}""")
        assertTrue(a.isToolCall)
        assertEquals("list_services", a.tool)
    }
}
