package com.khodroyar.app.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AutomationEngineTest {
    private val now = Instant.parse("2026-11-11T19:00:00Z")
    private fun expense() = AutomationRule("4d42fe28-35d8-43f4-9b97-a58d53e03774", RuleKind.EXPENSE, "قهوه",
        RepeatSchedule(RepeatFrequency.DAILY, "1405/08/01", "21:00", "Asia/Tehran"), 50_000.0, "coffee")
    @Test fun realWriteReceivesOnlyTodayAndStableOccurrenceId() = runBlocking {
        val r = expense(); var calls = 0
        val batch = AutomationEngine { rule, date, id ->
            calls++; assertEquals("1405/08/20", date); assertEquals(rule.occurrenceId(date), id); "ثبت تأیید شد"
        }.run(listOf(r), now, emptySet(), false)
        assertEquals(1, calls); assertEquals("1405/08/20", batch.rules.single().lastDate); assertFalse(batch.retry)
    }
    @Test fun repeatedTickDoesNotWriteAgain() = runBlocking {
        var calls = 0; val engine = AutomationEngine { _, _, _ -> calls++; "تأیید شد" }
        val first = engine.run(listOf(expense()), now, emptySet(), false)
        engine.run(first.rules, now.plusSeconds(3600), emptySet(), false)
        assertEquals(1, calls)
    }
    @Test fun processDeathAfterDatabaseCommitCanSafelyReplaySameId() = runBlocking {
        val db = mutableSetOf<String>(); var attempts = 0
        val engine = AutomationEngine { _, _, id -> attempts++; db.add(id); if (attempts == 1) throw IllegalStateException("simulated crash after commit"); "بازخوانی شد" }
        val first = engine.run(listOf(expense()), now, emptySet(), false)
        assertTrue(first.retry); assertEquals("", first.rules.single().lastDate)
        val second = engine.run(first.rules, now, emptySet(), false)
        assertEquals(2, attempts); assertEquals(1, db.size); assertEquals("1405/08/20", second.rules.single().lastDate)
    }
    @Test fun failedWriteIsNotReportedAsSaved() = runBlocking {
        val batch = AutomationEngine { _, _, _ -> error("do not expose provider body or secret") }.run(listOf(expense()), now, emptySet(), false)
        assertEquals("", batch.rules.single().lastDate); assertTrue(batch.retry)
        assertFalse(batch.messages.single().contains("secret")); assertFalse(batch.messages.single().contains("تأیید شد"))
    }
    @Test fun threeFailuresPauseRuleInsteadOfLoopingForever() = runBlocking {
        val engine = AutomationEngine { _, _, _ -> error("fail") }
        var rows = listOf(expense())
        repeat(3) { rows = engine.run(rows, now, emptySet(), false).rules }
        assertFalse(rows.single().enabled); assertEquals(3, rows.single().failures)
    }
    @Test fun oneFailureDoesNotBlockAnotherRule() = runBlocking {
        val a = expense(); val b = a.copy(id = "c22d82ee-244c-443b-91c6-18447f5dd3a4", title = "ناهار")
        val batch = AutomationEngine { r, _, _ -> if (r.id == a.id) error("fail") else "تأیید" }.run(listOf(a, b), now, emptySet(), false)
        assertEquals("", batch.rules[0].lastDate); assertEquals("1405/08/20", batch.rules[1].lastDate)
    }
    @Test fun legacyAutoServicePreventsSecondIndependentServiceWriter() = runBlocking {
        var calls = 0
        val s = expense().copy(kind = RuleKind.SERVICE, amount = 0.0, service = ServiceTemplate("night", "soren", "مبارکه", "اصفهان", 50.0, 1.0, "08:00", "09:00", 0))
        val batch = AutomationEngine { _, _, _ -> calls++; "تأیید" }.run(listOf(s), now, emptySet(), true)
        assertEquals(0, calls); assertFalse(batch.rules.single().enabled)
    }
    @Test fun aManualHolidaySkipsWorkdayExpense() = runBlocking {
        var calls = 0
        AutomationEngine { _, _, _ -> calls++; "تأیید" }.run(listOf(expense().copy(schedule = expense().schedule.copy(frequency = RepeatFrequency.WORKDAYS))), now, setOf("1405/08/20"), false)
        assertEquals(0, calls)
    }
    @Test fun pausedAndFutureRulesNeverCallExecutor() = runBlocking {
        var calls = 0
        AutomationEngine { _, _, _ -> calls++; "تأیید" }.run(listOf(expense().copy(enabled = false), expense().copy(schedule = expense().schedule.copy(startDate = "1406/01/01"))), now, emptySet(), false)
        assertEquals(0, calls)
    }
    @Test fun trafficReportIsReturnedNotClaimedToBeAFinancialRegistration() = runBlocking {
        val r = expense().copy(kind = RuleKind.TRAFFIC, amount = 0.0, title = "ترافیک")
        val b = AutomationEngine { _, _, _ -> "ذوب‌آهن: سنگین؛ زمان دریافت ۸:۰۰" }.run(listOf(r), now, emptySet(), false)
        assertTrue(b.messages.single().contains("ذوب‌آهن")); assertFalse(b.messages.single().contains("ثبت 1405"))
    }
    @Test(expected = CancellationException::class) fun cancellationIsNotConvertedToAnOrdinaryFailure() = runBlocking {
        AutomationEngine { _, _, _ -> throw CancellationException("stopped") }.run(listOf(expense()), now, emptySet(), false)
        Unit
    }
}
