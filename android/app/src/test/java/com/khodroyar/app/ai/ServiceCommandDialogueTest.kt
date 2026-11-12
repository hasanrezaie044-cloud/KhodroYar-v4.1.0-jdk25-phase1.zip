package com.khodroyar.app.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceCommandDialogueTest {
    private fun engine() = LocalDialogueEngine(today = { "1405/08/20" })
    private fun tool(value: LocalIntent) = (value as LocalIntent.Tool)
    @Test fun compactPersianAnnualRangeAsksCountAndPreservesAllSlots() {
        val e = engine()
        val question = e.parse("سرویس‌های شب از ۱۴۰۳۰۱۰۱ تا ۱۴۰۳۱۲۲۹ ثبت کن") as LocalIntent.Reply
        assertTrue(question.text.contains("چند"))
        val intent = tool(e.parse("۱"))
        assertEquals("bulk_register_services", intent.name)
        assertEquals("1403/01/01", intent.args.getString("start_date")); assertEquals("1403/12/29", intent.args.getString("end_date"))
        assertEquals("night", intent.args.getString("service_type")); assertEquals(1, intent.args.getInt("per_day"))
    }
    @Test fun mixedArabicDigitsSeparatorsAndCountAreAccepted() {
        val e = engine()
        val intent = tool(e.parse("از ١٤٠٣/١/١ تا 1403-01-05 سرویس شب روزی ٢ سرویس ثبت کن"))
        assertEquals("1403/01/05", intent.args.getString("end_date")); assertEquals(2, intent.args.getInt("per_day"))
        assertTrue(AgentActionValidator.validate(intent.name, intent.args) is ValidationResult.Ok)
    }
    @Test fun invalidJalaliDateReversedRangeFutureAndExcessiveMassAreQuestionsNotTools() {
        listOf("14041230 تا 14050101", "14030110 تا 14030101", "14060101 تا 14060110", "14020101 تا 14050101").forEach { range ->
            assertTrue(range, engine().parse("سرویس شب از $range روزی 1 سرویس ثبت کن") is LocalIntent.Reply)
        }
        assertTrue(engine().parse("سرویس شب از 14030101 تا 14031229 روزی 20 سرویس ثبت کن") is LocalIntent.Reply)
    }
    @Test fun correctionsKeepContextAndMakeAFreshProposal() {
        val e = engine()
        tool(e.parse("سرویس شب از 14030101 تا 14030103 روزی 1 سرویس ثبت کن"))
        val revised = tool(e.parse("نه، تا ۱۴۰۳۰۱۰۵"))
        assertEquals("1403/01/05", revised.args.getString("end_date")); assertEquals("night", revised.args.getString("service_type"))
        val workdays = tool(e.parse("فقط روزهای کاری"))
        assertEquals("workdays", workdays.args.getString("days")); assertEquals(1, workdays.args.getInt("per_day"))
    }
    @Test fun editAsksForChangesThenValidatesKmAndHours() {
        val e = engine()
        assertTrue(e.parse("سرویس شب از 14030101 تا 14030103 را ویرایش کن") is LocalIntent.Reply)
        val c = tool(e.parse("کیلومترش ۸۰ و ساعتش ۳٫۵ بشه"))
        assertEquals("bulk_edit_services", c.name); assertEquals(80.0, c.args.getDouble("km"), 0.0); assertEquals(3.5, c.args.getDouble("hours"), 0.0)
    }
    @Test fun deleteHasSeparateDestructiveToolAndFollowUpCanReuseScope() {
        val e = engine()
        val read = tool(e.parse("جستجوی سرویس شب از 14030101 تا 14030103")); assertEquals("search_services_range", read.name)
        val del = tool(e.parse("همونا رو حذف کن")); assertEquals("bulk_delete_services", del.name)
        val valid = AgentActionValidator.validate(del.name, del.args) as ValidationResult.Ok
        assertEquals(ToolKind.DELETE, valid.call.spec.kind)
    }
    @Test fun cancellationAndNegationNeverMakeABulkTool() {
        val e = engine(); e.parse("سرویس شب از 14030101 تا 14030105 ثبت کن"); e.parse("لغو")
        assertFalse(e.parse("۱") is LocalIntent.Tool)
        assertTrue(engine().parse("سرویس شب از 14030101 تا 14030105 را ثبت نکن") is LocalIntent.Reply)
    }
    @Test fun negativeKilometersAreNotConvertedToPositive() {
        assertTrue(engine().parse("سرویس شب از 14030101 تا 14030103 را ویرایش کن کیلومتر -80") is LocalIntent.Reply)
    }
    @Test fun noProviderCanInjectAnAppOwnedPlan() {
        val raw = JSONObject().put("start_date", "14030101").put("end_date", "14030102").put("service_type", "night")
            .put("per_day", 1).put("days", "all").put("service_plan", "invented")
        val v = AgentActionValidator.validate("bulk_register_services", raw) as ValidationResult.Ok
        assertFalse(v.call.has("service_plan"))
    }
    @Test fun compactDatesAlsoWorkInExistingExpenseTools() {
        val v = AgentActionValidator.validate("add_expense", JSONObject().put("amount", 50000).put("date", "١٤٠٣٠١٠١")) as ValidationResult.Ok
        assertEquals("1403/01/01", v.call.str("date"))
    }
    @Test fun oldRecurringAndSingleServiceFeaturesKeepTheirTools() {
        assertEquals("create_service_rule", tool(engine().parse("هر روز برام سرویس ثبت کن")).name)
        val e = engine(); assertEquals("add_service_from_preset", tool(e.parse("امشب ی سرویس رفتم ثبت کن")).name)
        val correction = tool(e.parse("ن سرویس شب بود"))
        assertEquals("night", correction.args.getString("service_type"))
    }
    @Test fun longestServiceTypeDoesNotAlsoMatchTheShorterType() {
        val v = tool(engine().parse("سرویس حقوق ثابت از 14030101 تا 14030103 روزی 1 سرویس ثبت کن"))
        assertEquals("fixed-salary", v.args.getString("service_type"))
    }
    @Test fun malformedDateCorrectionCannotReuseAnOlderApprovedRange() {
        val e = engine()
        tool(e.parse("سرویس شب از 14030101 تا 14030103 روزی 1 سرویس ثبت کن"))
        assertTrue(e.parse("نه، تا 1403010") is LocalIntent.Reply)
        assertTrue(e.parse("نه، تا 20240320") is LocalIntent.Reply)
    }
    @Test fun malformedOrUnicodeNegativeValuesAreNeverPartiallyParsed() {
        listOf("−80 کیلومتر", "کیلومتر 1,234.5.6", "ساعت 3:77", "2٫5٫6 ساعت").forEach { change ->
            assertTrue(change, engine().parse("سرویس شب از 14030101 تا 14030103 را ویرایش کن $change") is LocalIntent.Reply)
        }
        val ok = tool(engine().parse("سرویس شب از 14030101 تا 14030103 را ویرایش کن کیلومتر 1,234.5"))
        assertEquals(1234.5, ok.args.getDouble("km"), 0.0)
    }

}
