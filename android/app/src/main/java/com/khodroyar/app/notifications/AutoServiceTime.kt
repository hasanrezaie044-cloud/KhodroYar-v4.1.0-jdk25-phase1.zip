package com.khodroyar.app.notifications

import java.time.Instant
import java.time.ZoneId

/** Device-local 16:00; WorkManager execution can be deferred by Android battery policies. */
object AutoServiceTime {
    const val HOUR = 16
    fun delayMillis(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        var next = local.toLocalDate().atTime(HOUR, 0).atZone(zone)
        if (!next.isAfter(local)) next = local.toLocalDate().plusDays(1).atTime(HOUR, 0).atZone(zone)
        return next.toInstant().toEpochMilli() - now
    }
}
