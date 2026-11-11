package com.khodroyar.app.updates

/** No guessed release number, remote JSON, private token or scraped store HTML. */
object BazaarUpdatePolicy {
    const val BAZAAR_PACKAGE = "com.farsitel.bazaar"
    const val APP_PACKAGE = "com.khodroyar.app"
    const val LISTING_URL = "https://cafebazaar.ir/app/$APP_PACKAGE"
    const val LISTING_URI = "bazaar://details?id=$APP_PACKAGE"
    const val BIND_ACTION = "com.farsitel.bazaar.service.UpdateCheckService.BIND"
    const val REMIND_AFTER_MS = 24L * 60 * 60 * 1000
    const val FOREGROUND_RECHECK_MS = 30L * 60 * 1000

    fun isNewer(installed: Long, reported: Long): Boolean =
        installed > 0 && reported > installed && reported <= Int.MAX_VALUE.toLong()

    fun shouldPrompt(installed: Long, reported: Long, dismissedVersion: Long, dismissedAt: Long,
                     now: Long, manual: Boolean = false): Boolean {
        if (!isNewer(installed, reported)) return false
        if (manual || reported > dismissedVersion || dismissedAt <= 0 || now < dismissedAt) return true
        return now - dismissedAt >= REMIND_AFTER_MS
    }
}

sealed interface BazaarVersionResult {
    /** Bazaar returns -1 when it does not report an update; cache delay is possible. */
    data class Reported(val code: Long) : BazaarVersionResult
    data class Unavailable(val reason: Reason) : BazaarVersionResult
    enum class Reason { NOT_INSTALLED, SERVICE_UNAVAILABLE, TIMEOUT }
}

fun interface BazaarVersionSource {
    suspend fun check(): BazaarVersionResult
}
