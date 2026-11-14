package com.khodroyar.app.notifications

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class AutoServiceTimeTest {
    @Test fun schedulesTodayAtSixteenWithoutRoundingOneMinuteEarly() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = ZonedDateTime.of(2026, 11, 10, 15, 59, 30, 0, zone).toInstant().toEpochMilli()
        assertEquals(30000L, AutoServiceTime.delayMillis(now, zone))
        assertEquals(16, AutoServiceTime.HOUR)
    }
    @Test fun atAndAfterSixteenScheduleTomorrowWithoutAnExtraService() {
        val zone = ZoneId.of("Europe/Stockholm")
        for (hour in listOf(16, 18, 21)) {
            val now = ZonedDateTime.of(2026, 11, 10, hour, 0, 0, 0, zone)
            val target = now.toLocalDate().plusDays(1).atTime(16, 0).atZone(zone)
            assertEquals(target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli(), AutoServiceTime.delayMillis(now.toInstant().toEpochMilli(), zone))
        }
    }
    @Test fun daylightSavingChangeStillTargetsLocalSixteen() {
        val zone = ZoneId.of("Europe/Stockholm")
        val now = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, zone)
        val target = now.plusDays(1).withHour(16)
        assertEquals(target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli(), AutoServiceTime.delayMillis(now.toInstant().toEpochMilli(), zone))
    }
}
