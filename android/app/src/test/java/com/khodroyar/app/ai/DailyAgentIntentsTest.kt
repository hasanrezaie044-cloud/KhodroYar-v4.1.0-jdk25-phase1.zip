package com.khodroyar.app.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DailyAgentIntentsTest {
    private fun engine()=LocalDialogueEngine(today={"1405/08/20"})
    @Test fun everydayComparisonDoesNotFallIntoGeneralHelp() {
        for(text in listOf("این ماه نسبت به ماه قبل چطور بود؟","این ماه بهتر از ماه قبل بود؟","سود هر کیلومتر چقدره؟")) {
            val tool=engine().parse(text) as LocalIntent.Tool
            assertEquals("get_period_comparison",tool.name);assertEquals("month",tool.args.getString("period"))
        }
    }
    @Test fun weekComparisonIsNotChangedIntoAMonth() { val t=engine().parse("این هفته نسبت به هفته قبل چطور بود؟") as LocalIntent.Tool;assertEquals("week",t.args.getString("period")) }
    @Test fun unknownComparisonPeriodAsksInsteadOfGuessing() { assertTrue(engine().parse("مقایسه پارسال با امسال") is LocalIntent.Reply) }
    @Test fun repeatYesterdayUsesExplicitSourceAndTargetDates() { val t=engine().parse("همون هزینه دیروز رو برای امروز ثبت کن") as LocalIntent.Tool;assertEquals("repeat_expense",t.name);assertEquals("1405/08/19",t.args.getString("source_date"));assertEquals("1405/08/20",t.args.getString("date")) }
    @Test fun repeatCanDisambiguateCoffee() { val t=engine().parse("همون قهوه دیروز رو برای امروز تکرار کن") as LocalIntent.Tool;assertEquals("قهوه",t.args.getString("title")) }
    @Test fun negatedOrHypotheticalRepeatIsNotAWrite() { for(t in listOf("همون هزینه دیروز رو ثبت نکن","اگر شد همون هزینه دیروز رو ثبت کن","همون هزینه دیروز رو برای فردا ثبت کن")) assertTrue(engine().parse(t) is LocalIntent.Reply) }
    @Test fun recurringRepeatIsNotSilentlyMadeOneOff() { assertTrue(engine().parse("هر روز همون هزینه دیروز رو ثبت کن") is LocalIntent.Reply) }
    @Test fun incompletePreviousTaskMustNotLeakIntoARepeat() { val e=engine();e.parse("هر روز هزینه قهوه ثبت کن");assertTrue(e.parse("همون هزینه دیروز رو امروز ثبت کن") is LocalIntent.Reply) }
    @Test fun failedTaskQuestionIsARead() { val t=engine().parse("کدوم کارهای خودکارم اجرا نشده؟") as LocalIntent.Tool;assertEquals("get_automation_health",t.name);assertEquals(ToolKind.READ,AgentToolRegistry.find(t.name)!!.kind) }
    @Test fun repeatAmbiguityCanBeAnsweredWithOnlyTheTitle() {
        val e=engine();e.parse("همون هزینه دیروز رو برای امروز ثبت کن")
        val title=e.parse("قهوه") as LocalIntent.Tool
        assertEquals("repeat_expense",title.name);assertEquals("قهوه",title.args.getString("title"));assertEquals("1405/08/19",title.args.getString("source_date"))
    }
    @Test fun ambiguityFollowUpCannotSilentlyBecomeFutureOrMultipleExpenses() {
        val e=engine();e.parse("همون هزینه دیروز رو برای امروز ثبت کن")
        assertTrue(e.parse("قهوه برای فردا") is LocalIntent.Reply)
        assertTrue(e.parse("قهوه و ناهار") is LocalIntent.Reply)
    }
    @Test fun cancellingOrReadingAnUnrelatedToolDoesNotLeaveAnInvisibleRepeatDraft() {
        val e=engine();e.parse("همون هزینه دیروز رو برای امروز ثبت کن");e.parse("لغو")
        assertFalse((e.parse("قهوه") as? LocalIntent.Tool)?.name=="repeat_expense")
        e.parse("همون هزینه دیروز رو برای امروز ثبت کن");e.parse("کدوم کارهای خودکارم اجرا نشده؟")
        assertFalse((e.parse("قهوه") as? LocalIntent.Tool)?.name=="repeat_expense")
    }
    @Test fun repeatRequiresApprovalAndCannotInjectASourceSnapshot() {
        assertEquals(ToolKind.WRITE,AgentToolRegistry.find("repeat_expense")!!.kind)
        val c=AgentActionValidator.validate("repeat_expense",JSONObject().put("source_date","yesterday").put("date","today").put("source_id","evil").put("source_amount",1)) as ValidationResult.Ok
        assertFalse(c.call.has("source_id"));assertFalse(c.call.has("source_amount"))
    }
}
