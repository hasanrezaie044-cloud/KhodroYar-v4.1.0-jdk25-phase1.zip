package com.khodroyar.app.automation

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AutomationHealthTest {
    private val now=Instant.parse("2026-11-11T19:00:00Z")
    private val schedule=RepeatSchedule(RepeatFrequency.DAILY,"1405/08/20",time="21:00",zoneId="Asia/Tehran")
    private fun rule()=AutomationRule("7e5c5ca9-07a4-472a-a66b-6eb5e3ea8d84",RuleKind.EXPENSE,"قهوه",schedule,50_000.0,"coffee")
    @Test fun dueButUnrecordedIsWaitingNotFailed() { assertEquals(AutomationHealth.WAITING,rule().health(Instant.parse("2026-11-11T17:35:00Z"),emptySet())) }
    @Test fun thirtyMinutesWithoutARecordedResultIsLateNotAClaimOfFailure() { assertEquals(AutomationHealth.LATE,rule().health(now,emptySet())) }
    @Test fun persistedFailureIsVisibleEvenWhenPausedAfterThreeFailures() { assertEquals(AutomationHealth.FAILED,rule().copy(enabled=false,failures=3).health(now,emptySet())) }
    @Test fun confirmedTodayIsCompleted() { assertEquals(AutomationHealth.COMPLETED,rule().copy(lastDate=schedule.dateAt(now)).health(now,emptySet())) }
    @Test fun disabledRuleIsPaused() { assertEquals(AutomationHealth.PAUSED,rule().copy(enabled=false).health(now,emptySet())) }
    @Test fun futureTimeIsScheduled() { assertEquals(AutomationHealth.SCHEDULED,rule().copy(schedule=schedule.copy(time="23:59")).health(now,emptySet())) }
    @Test fun expiredRangeDoesNotClaimAnUpcomingRun() { assertEquals(AutomationHealth.EXPIRED,rule().copy(schedule=schedule.copy(endDate="1405/08/20")).health(now.plusSeconds(86_400),emptySet())) }
    @Test fun calendarHolidayIsNotReportedAsMissedWork() { val r=rule().copy(schedule=schedule.copy(frequency=RepeatFrequency.WORKDAYS));assertEquals(AutomationHealth.SCHEDULED,r.health(now,setOf(schedule.dateAt(now)))) }
}
