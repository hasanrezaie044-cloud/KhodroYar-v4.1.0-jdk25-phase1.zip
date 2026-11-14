package com.khodroyar.app.automation

import com.khodroyar.app.core.jalali.Jalali
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AutomationScheduleTest {
    private fun instant(date: String, time: String = "21:01", zone: String = "Asia/Tehran"): Instant {
        val j = Jalali.parse(date)!!; val g = Jalali.toGregorian(j.year, j.month, j.day)
        return LocalDateTime.parse("%04d-%02d-%02dT%s:00".format(java.util.Locale.US, g.first, g.second, g.third, time)).atZone(ZoneId.of(zone)).toInstant()
    }
    private fun expense(s: RepeatSchedule = RepeatSchedule(RepeatFrequency.DAILY, "1405/08/20", zoneId = "Asia/Tehran")) = AutomationRule(
        "97cd8be4-b693-4af2-b411-bb507b51f4b5", RuleKind.EXPENSE, "قهوه", s, 50_000.0, "coffee")

    @Test fun notDueBeforeStartDate() { assertFalse(expense().due(instant("1405/08/19"), emptySet())) }
    @Test fun dueOnlyAfterConfiguredLocalTime() {
        val r = expense()
        assertFalse(r.due(instant("1405/08/20", "20:59"), emptySet()))
        assertTrue(r.due(instant("1405/08/20", "21:00"), emptySet()))
    }
    @Test fun sameDateIsNotDueTwiceEvenAfterAnAmountEdit() {
        val r = expense().copy(lastDate = "1405/08/20", amount = 60_000.0, revision = 2)
        assertFalse(r.due(instant("1405/08/20", "23:00"), emptySet()))
        assertTrue(r.due(instant("1405/08/21"), emptySet()))
    }
    @Test fun pausedRulesNeverRun() { assertFalse(expense().copy(enabled = false).due(instant("1405/08/20"), emptySet())) }
    @Test fun endDateIsInclusiveAndThenStops() {
        val r = expense().copy(schedule = expense().schedule.copy(endDate = "1405/08/21"))
        assertTrue(r.due(instant("1405/08/21"), emptySet()))
        assertFalse(r.due(instant("1405/08/22"), emptySet()))
    }
    @Test fun manualHolidayBlocksWorkdaysButNotDaily() {
        val s = expense().schedule
        assertFalse(s.copy(frequency = RepeatFrequency.WORKDAYS).allows("1405/08/20", setOf("1405/08/20")))
        assertTrue(s.allows("1405/08/20", setOf("1405/08/20")))
    }
    @Test fun bothThursdayAndFridayAreNonWorking() {
        val s = expense().schedule.copy(frequency = RepeatFrequency.WORKDAYS)
        val dates = (0..10).map { Jalali.addDays(s.startDate, it) }
        dates.filter { Jalali.dayOfWeek(it) >= 5 }.forEach { assertFalse(it, s.allows(it, emptySet())) }
    }
    @Test fun selectedWeekdaysRespectExactlyTheSelection() {
        val s = expense().schedule.copy(frequency = RepeatFrequency.WEEKDAYS, weekdays = setOf(0, 2))
        (0..13).map { Jalali.addDays(s.startDate, it) }.forEach { assertEquals(it, Jalali.dayOfWeek(it) in setOf(0, 2), s.allows(it, emptySet())) }
    }
    @Test fun selectedWeekdaysIncludeHolidaysUnlessUserChoosesWorkdays() {
        val s = expense().schedule.copy(frequency = RepeatFrequency.WEEKDAYS, weekdays = setOf(Jalali.dayOfWeek("1405/08/20")))
        assertTrue(s.allows("1405/08/20", setOf("1405/08/20")))
    }
    @Test fun zoneIsCapturedInsteadOfFollowingPhoneClockZoneChanges() {
        val now = Instant.parse("2026-11-11T18:00:00Z")
        val tehran = expense().schedule
        val stockholm = tehran.copy(zoneId = "Europe/Stockholm")
        assertTrue(tehran.reachedTime(now)); assertFalse(stockholm.reachedTime(now))
    }
    @Test fun dstOverlapDoesNotProduceTwoOccurrenceIds() {
        val r = expense().copy(schedule = RepeatSchedule(RepeatFrequency.DAILY, "1405/01/01", "02:00", "Europe/Stockholm"))
        val first = Instant.parse("2026-10-25T00:30:00Z"); val second = Instant.parse("2026-10-25T01:30:00Z")
        val date = r.schedule.dateAt(first)
        assertEquals(date, r.schedule.dateAt(second))
        assertTrue(r.due(first, emptySet())); assertFalse(r.copy(lastDate = date).due(second, emptySet()))
    }
    @Test fun invalidSchedulesFailClosed() {
        val s = expense().schedule
        listOf(s.copy(time = "25:00"), s.copy(startDate = "1405/13/01"), s.copy(zoneId = "Invalid/Nowhere"),
            s.copy(frequency = RepeatFrequency.WEEKDAYS), s.copy(weekdays = setOf(7)), s.copy(endDate = "1405/08/01")).forEach { assertFalse(it.toString(), it.valid()) }
    }
    @Test fun occurrenceIdIsStableAcrossRetriesAndEditsButDifferentAcrossDatesAndRules() {
        val r = expense()
        assertEquals(r.occurrenceId("1405/08/20"), r.copy(amount = 70_000.0, revision = 4).occurrenceId("1405/08/20"))
        assertNotEquals(r.occurrenceId("1405/08/20"), r.occurrenceId("1405/08/21"))
        assertNotEquals(r.occurrenceId("1405/08/20"), r.copy(id = "df511fb2-aed9-4e45-91c0-a396d6f146ea").occurrenceId("1405/08/20"))
    }
    @Test fun nextRunShowsDueTodayRatherThanHidingItUntilTomorrow() {
        assertTrue(expense().schedule.nextRun(instant("1405/08/20"), emptySet())!!.contains("در انتظار"))
    }
    @Test fun noNextRunAfterEndDate() {
        val s = expense().schedule.copy(endDate = "1405/08/20")
        assertNull(s.nextRun(instant("1405/08/21"), emptySet()))
    }
    @Test fun noCatchUpForPastDaysWhenPhoneWasOff() {
        val r = expense()
        assertEquals("1405/08/25", r.schedule.dateAt(instant("1405/08/25")))
        assertTrue(r.due(instant("1405/08/25"), emptySet()))
        // Engine only receives today's date, never the interval since last execution.
    }
}
