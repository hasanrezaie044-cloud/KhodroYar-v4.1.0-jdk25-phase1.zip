package com.khodroyar.app.ai

import com.khodroyar.app.automation.*
import org.junit.Assert.*
import org.junit.Test

class LocalDialogueEngineTest {
    private val date = "1405/08/20"
    private fun engine(rows: List<AutomationRule> = emptyList()) = LocalDialogueEngine(today = { date }, zone = { "Asia/Tehran" }, rules = { rows })
    private fun tool(e: LocalDialogueEngine, text: String): LocalIntent.Tool = e.parse(text).let {
        assertTrue("$text -> $it", it is LocalIntent.Tool); it as LocalIntent.Tool
    }
    private fun question(e: LocalDialogueEngine, text: String): String = e.parse(text).let {
        assertTrue("$text -> $it", it is LocalIntent.Reply); (it as LocalIntent.Reply).text
    }
    private fun rule(title: String = "قهوه", id: String = "7e5c5ca9-07a4-472a-a66b-6eb5e3ea8d84") = AutomationRule(id,
        RuleKind.EXPENSE, title, RepeatSchedule(RepeatFrequency.WORKDAYS, date), 50_000.0, "coffee")

    @Test fun shortColloquialRequestAndBareScaledAmountFormOneRule() {
        val e = engine()
        assertTrue(question(e, "روزای کاری هزینه قهوه‌مو ثبت کن").contains("مبلغ"))
        val c = tool(e, "پنجاه هزار")
        assertEquals("create_expense_rule", c.name)
        assertEquals(50_000.0, c.args.getDouble("amount"), 0.0)
        assertEquals("coffee", c.args.getString("category"))
        assertEquals("workdays", c.args.getString("frequency"))
    }
    @Test fun correctionKeepsAmountTitleAndFrequency() {
        val e = engine(); tool(e, "هر روز هزینه قهوه ۵۰ هزار تومن ثبت کن")
        val c = tool(e, "نه، از فردا")
        assertEquals("1405/08/21", c.args.getString("start_date"))
        assertEquals(50_000.0, c.args.getDouble("amount"), 0.0)
        assertEquals("daily", c.args.getString("frequency"))
    }
    @Test fun workingDaysCorrectionIsNotDiscardedAsNegation() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان بزن")
        assertEquals("workdays", tool(e, "نه فقط روزای کاری").args.getString("frequency"))
    }
    @Test fun correctionUnderstandsEveningTime() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان ثبت کن")
        assertEquals("21:30", tool(e, "ساعت ۹ و نیم شب").args.getString("time"))
    }
    @Test fun selectedDaysAreNotMistakenForASingleSaturday() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان ثبت کن")
        val c = tool(e, "فقط شنبه و دوشنبه")
        assertEquals("weekdays", c.args.getString("frequency"))
        assertEquals("0,2", c.args.getString("weekdays"))
    }
    @Test fun customCostTitleCanBeAnsweredSeparately() {
        val e = engine(); question(e, "هر روز یه هزینه ثبت کن")
        question(e, "پارکینگ")
        val c = tool(e, "صد هزار")
        assertEquals("پارکینگ", c.args.getString("title"))
        assertEquals(100_000.0, c.args.getDouble("amount"), 0.0)
    }
    @Test fun aSmallBareAmountAsksAboutUnitsInsteadOfMultiplyingByThousand() {
        val e = engine(); question(e, "روزای کاری قهوه ثبت کن")
        assertTrue(question(e, "۵۰").contains("هزار تومان"))
        assertTrue(question(e, "پنجاه").contains("هزار تومان"))
        assertEquals(50_000.0, tool(e, "۵۰ هزار تومان").args.getDouble("amount"), 0.0)
    }
    @Test fun explicitRialsAreConvertedOnce() {
        assertEquals(50_000.0, tool(engine(), "روزانه هزینه قهوه ۵۰۰۰۰۰ ریال ثبت کن").args.getDouble("amount"), 0.0)
    }
    @Test fun unknownReplyCannotBecomeAnApprovalOrNewWrite() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان بزن")
        question(e, "سیستم عامل گوشی چیست")
    }
    @Test fun switchingTaskDoesNotReuseAnExpenseAmountForAService() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان بزن")
        assertTrue(question(e, "هر روز سرویس ثبت کن").contains("لغو"))
    }
    @Test fun cancelDropsDraftAndFollowUpCannotResurrectIt() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان بزن")
        question(e, "بیخیال"); question(e, "از فردا")
        assertEquals("", e.context.title)
    }
    @Test fun resetForNewConversationDropsContext() {
        val e = engine(); question(e, "روزای کاری قهوه ثبت کن"); e.reset()
        question(e, "پنجاه هزار")
    }
    @Test fun repeatFrequencyIsAskedWhenUserOnlySaysAutomatic() {
        val e = engine()
        assertTrue(question(e, "خودکار هزینه قهوه ۵۰ هزار تومان ثبت کن").contains("هر روز"))
        assertEquals("workdays", tool(e, "روزهای کاری").args.getString("frequency"))
    }
    @Test fun invalidDateDoesNotProduceATool() {
        question(engine(), "هر روز هزینه قهوه ۵۰ هزار تومان از ۱۴۰۵/۱۳/۰۱ ثبت کن")
    }
    @Test fun endBeforeStartNeedsClarification() {
        question(engine(), "از ۱۴۰۵/۰۸/۲۰ تا ۱۴۰۵/۰۸/۱۰ هر روز قهوه ۵۰ هزار تومان ثبت کن")
    }
    @Test fun explicitStartAndEndAreSeparateSlots() {
        val c = tool(engine(), "از ۱۴۰۵/۰۸/۲۰ تا ۱۴۰۵/۰۹/۲۰ هر روز قهوه ۵۰ هزار تومان ثبت کن")
        assertEquals("1405/08/20", c.args.getString("start_date"))
        assertEquals("1405/09/20", c.args.getString("end_date"))
    }
    @Test fun invalidClockIsNotClampedToAnotherTime() {
        question(engine(), "هر روز ساعت ۲۵:۰۰ قهوه ۵۰ هزار تومان ثبت کن")
    }
    @Test fun unsupportedRecurrenceIsNotSilentlyChangedToDaily() {
        question(engine(), "هر ماه هزینه قهوه ۵۰ هزار تومان ثبت کن")
        question(engine(), "یه روز در میون هزینه قهوه ۵۰ هزار تومان ثبت کن")
    }
    @Test fun noHypotheticalOrNegatedWrites() {
        for (s in listOf("فقط مثال بزن هر روز قهوه ۵۰ هزار تومان ثبت کن", "ثبت هزینه قهوه ۵۰ هزار تومان نکن", "اگر تعطیل بود قهوه ثبت کن")) question(engine(), s)
    }
    @Test fun multipleExpenseSubjectsNeedSeparateRequests() {
        question(engine(), "هر روز قهوه و ناهار ۵۰ هزار تومان ثبت کن")
        question(engine(), "ثبت سوخت و تعمیر ۵۰ هزار تومان")
    }
    @Test fun amountValidationRejectsNegativeAndAmbiguousNumbers() {
        question(engine(), "هر روز هزینه قهوه منفی ۵۰ هزار تومان ثبت کن")
        question(engine(), "هر روز هزینه قهوه ۵۰ تومان یا ۶۰ تومان ثبت کن")
    }
    @Test fun oneOffSpendingUsesTheSameSafeToolAsTheFinanceTab() {
        val c = tool(engine(), "امروز قهوه ۵۰ هزار تومان خریدم ثبتش کن")
        assertEquals("add_expense", c.name); assertEquals(date, c.args.getString("date"))
    }
    @Test fun fuelSlotsAreCollectedAcrossThreeShortTurns() {
        val e = engine(); question(e, "بنزین زدم ثبت کن"); question(e, "۱۲۰ هزار تومان")
        val c = tool(e, "۴۰ لیتر")
        assertEquals("add_fuel", c.name); assertEquals(40.0, c.args.getDouble("liters"), 0.0)
        assertEquals(120_000.0, c.args.getDouble("total_cost"), 0.0)
    }
    @Test fun maintenanceAndReminderStillWork() {
        assertEquals("add_car_service", tool(engine(), "ثبت تعویض روغن ۵۰۰ هزار تومان").name)
        val e = engine(); question(e, "یادم بنداز بیمه")
        assertEquals("create_reminder", tool(e, "فردا").name)
    }
    @Test fun serviceAndMaintenanceAreDifferentTools() {
        assertEquals("create_service_rule", tool(engine(), "هر روز برام سرویس ثبت کن").name)
        assertEquals("add_service_from_preset", tool(engine(), "سرویس همیشگی امروز رو ثبت کن").name)
    }
    @Test fun actualMaintenanceIsNotRepeatedAsCompletedWork() {
        question(engine(), "هر روز تعویض روغن ۵۰۰ هزار تومان ثبت کن")
    }
    @Test fun trafficReadsAndAlertsAreDifferentAndExplicit() {
        assertEquals("get_traffic_status", tool(engine(), "از تب ترافیک بگیر الان کجا ترافیکه").name)
        assertEquals("set_traffic_alerts", tool(engine(), "ترافیک شد بهم خبر بده").name)
        assertEquals("off", tool(engine(), "هشدار ترافیک را خاموش کن").args.getString("state"))
    }
    @Test fun scheduledTrafficDoesNotBecomeUnrequestedFifteenMinuteAlerts() {
        val c = tool(engine(), "هر روز ساعت ۸ صبح ترافیک رو بگو")
        assertEquals("create_traffic_rule", c.name); assertEquals("08:00", c.args.getString("time"))
    }
    @Test fun morningWithoutHourAsksRatherThanChoosingOne() {
        assertTrue(question(engine(), "هر روز صبح ترافیک رو بگو").contains("ساعت"))
    }
    @Test fun allTabNavigationUsesTypedDestinations() {
        val inputs = mapOf("سوخت" to "fuel", "مالی" to "finance", "تعمیرات" to "maintenance", "بیمه" to "insurance", "نرخ ها" to "rates", "تقویم" to "calendar", "گزارش" to "reports", "تنظیمات" to "settings", "ترافیک" to "traffic", "ثبت سرویس" to "services", "خانه" to "home", "جستجو" to "search")
        inputs.forEach { (fa, wire) -> assertEquals(wire, tool(engine(), "برو تب $fa").args.getString("section")) }
    }
    @Test fun financeQuestionsRemainReads() {
        assertEquals("get_monthly_report", tool(engine(), "این ماه چقد خرج کردم").name)
        assertEquals("get_monthly_report", tool(engine(), "گزارش این ماه").name)
    }
    @Test fun knownTypoAndArabicFormsNormalize() {
        val c = tool(engine(), "هر روز هزنیه قهوه ٥٠ هزار تومن ثبت كن")
        assertEquals(50_000.0, c.args.getDouble("amount"), 0.0)
    }
    @Test fun ambiguousRuleTargetAsksInsteadOfStoppingAllRules() {
        val e = engine(listOf(rule(), rule("ناهار", "cd78770d-2713-4f3b-b28a-d811ac46f154")))
        assertTrue(question(e, "قانون را متوقف کن").contains("کدام"))
        val c = tool(e, "قانون 7e5c5ca9")
        assertEquals("set_automation_enabled", c.name); assertEquals("off", c.args.getString("state"))
    }
    @Test fun namedRuleCanBePausedAndResumedOnlyViaWriteTool() {
        val rows = listOf(rule())
        assertEquals("off", tool(engine(rows), "قهوه را متوقف کن").args.getString("state"))
        assertEquals("on", tool(engine(rows), "قهوه را دوباره فعال کن").args.getString("state"))
    }
    @Test fun ruleAmountEditCollectsOneMissingSlotAndPreservesRecordHistory() {
        val e = engine(listOf(rule()))
        question(e, "مبلغ قانون 7e5c5ca9 را تغییر بده")
        val c = tool(e, "۶۰ هزار")
        assertEquals("update_expense_rule", c.name); assertEquals(60_000.0, c.args.getDouble("amount"), 0.0)
        assertFalse(c.args.has("date"))
    }
    @Test fun plainYesWithoutAnApprovalCardDoesNotExecuteAnything() {
        question(engine(), "آره")
    }
    @Test fun completedDraftIsNotReusedAsANewExpense() {
        val e = engine(); tool(e, "هر روز قهوه ۵۰ هزار تومان ثبت کن")
        val spec = AgentToolRegistry.find("create_expense_rule")!!
        e.completed(ValidatedCall(spec, mapOf("operation_id" to rule().id)), true)
        question(e, "از فردا")
    }
}
