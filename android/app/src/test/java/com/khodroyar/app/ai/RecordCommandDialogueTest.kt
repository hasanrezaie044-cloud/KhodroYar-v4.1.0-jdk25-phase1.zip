package com.khodroyar.app.ai

import org.junit.Assert.*
import org.junit.Test

class RecordCommandDialogueTest {
    private fun engine() = LocalDialogueEngine(today = { "1405/08/20" })
    @Test fun expenseEditingAsksForExplicitIdAndKeepsMoneyFollowUp() {
        val e = engine()
        assertTrue(e.parse("هزینه را ویرایش کن") is LocalIntent.Reply)
        assertEquals("list_expenses", (e.parse("فهرست هزینه‌ها") as LocalIntent.Tool).name)
        assertTrue(e.parse("abcd1234") is LocalIntent.Reply)
        val tool = e.parse("مبلغش ۶۰ هزار تومان بشه") as LocalIntent.Tool
        assertEquals("update_expense", tool.name); assertEquals("abcd1234", tool.args.getString("id")); assertEquals(60000.0, tool.args.getDouble("amount"), 0.0)
    }
    @Test fun deletionIsNotAReadAndCannotUseAnAmbiguousTitleAsAnId() {
        val e = engine()
        assertTrue(e.parse("قهوه را حذف کن") is LocalIntent.Reply)
        assertTrue(e.parse("هزینه قهوه را حذف کن") is LocalIntent.Reply)
        assertTrue(e.parse("قهوه") is LocalIntent.Reply)
        val tool = e.parse("شناسه abcd1234") as LocalIntent.Tool
        val call = (AgentActionValidator.validate(tool.name, tool.args) as ValidationResult.Ok).call
        assertEquals("delete_expense", tool.name); assertEquals(ToolKind.DELETE, call.spec.kind)
    }
    @Test fun fuelEditHandlesArabicDigitsLitersMoneyAndCompactDate() {
        val tool = engine().parse("سوخت شناسه abcd1234 را ویرایش کن ٤٠ لیتر ١٢٠ هزار تومان تاریخ ١٤٠٣٠١٠١") as LocalIntent.Tool
        val call = (AgentActionValidator.validate(tool.name, tool.args) as ValidationResult.Ok).call
        assertEquals("update_fuel", tool.name); assertEquals(40.0, call.dbl("liters")!!, 0.0)
        assertEquals(120000.0, call.dbl("total_cost")!!, 0.0); assertEquals("1403/01/01", call.str("date"))
    }
    @Test fun multiStepRangeRegistrationThenReportSharesOneWriteApproval() {
        val e = engine()
        val tool = e.parse("سرویس شب از ۱۴۰۳۰۱۰۱ تا ۱۴۰۳۰۱۰۳ روزی ۱ سرویس ثبت کن و بعد گزارشش رو بده") as LocalIntent.Tool
        assertEquals("bulk_register_services", tool.name); assertEquals("on", tool.args.getString("report_after"))
        assertTrue(AgentActionValidator.validate(tool.name, tool.args) is ValidationResult.Ok)
    }
}
