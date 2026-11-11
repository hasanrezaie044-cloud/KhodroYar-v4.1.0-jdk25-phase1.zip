package com.khodroyar.app.updates

import org.junit.Assert.*
import org.junit.Test

class BazaarUpdatePolicyTest {
    private val now = 1_800_000_000_000L
    @Test fun onlyAnActuallyGreaterVersionCodeCanPrompt() {
        assertTrue(BazaarUpdatePolicy.isNewer(61, 62))
        for (code in listOf(-1L, 0L, 1L, 60L, 61L, Long.MAX_VALUE)) assertFalse(BazaarUpdatePolicy.isNewer(61, code))
        assertFalse(BazaarUpdatePolicy.isNewer(0, 62))
    }
    @Test fun dismissalSnoozesTheSameVersionNotAStillNewerRelease() {
        assertFalse(BazaarUpdatePolicy.shouldPrompt(61, 62, 62, now - 60_000, now))
        assertTrue(BazaarUpdatePolicy.shouldPrompt(61, 63, 62, now - 60_000, now))
        assertTrue(BazaarUpdatePolicy.shouldPrompt(61, 62, 62, now - BazaarUpdatePolicy.REMIND_AFTER_MS, now))
    }
    @Test fun manualRecheckCanRedisplayAnAvailableButDismissedUpdate() {
        assertTrue(BazaarUpdatePolicy.shouldPrompt(61, 62, 62, now, now, manual = true))
        assertFalse(BazaarUpdatePolicy.shouldPrompt(62, 62, 62, now, now, manual = true))
        assertFalse(BazaarUpdatePolicy.shouldPrompt(62, -1, 62, now, now, manual = true))
    }
    @Test fun backwardsClockDoesNotHideAllFutureUpdates() {
        assertTrue(BazaarUpdatePolicy.shouldPrompt(61, 62, 62, now + 86_400_000, now))
    }
    @Test fun storeDestinationAndServiceAreForThisExactApp() {
        assertEquals("https://cafebazaar.ir/app/com.khodroyar.app", BazaarUpdatePolicy.LISTING_URL)
        assertEquals("bazaar://details?id=com.khodroyar.app", BazaarUpdatePolicy.LISTING_URI)
        assertEquals("com.farsitel.bazaar", BazaarUpdatePolicy.BAZAAR_PACKAGE)
        assertEquals("com.farsitel.bazaar.service.UpdateCheckService.BIND", BazaarUpdatePolicy.BIND_ACTION)
    }
}
