package com.khodroyar.app.update

import org.junit.Assert.*
import org.junit.Test

class BazaarUpdatePolicyTest {
    @Test fun onlyANewerPublishedCodeIsAnUpdate() {
        assertEquals(BazaarUpdateResult.Available(74), BazaarUpdatePolicy.result(73, 74))
        assertEquals(BazaarUpdateResult.Current, BazaarUpdatePolicy.result(73, 73))
        assertEquals(BazaarUpdateResult.Current, BazaarUpdatePolicy.result(73, 72))
    }
    @Test fun missingOrFailedVersionIsNotAnUpdateAndNotProofOfBeingCurrent() {
        for (remote in listOf(-1L, 0L)) assertEquals(BazaarUpdateResult.Unavailable, BazaarUpdatePolicy.result(73, remote))
        assertEquals(BazaarUpdateResult.Unavailable, BazaarUpdatePolicy.result(0, 74))
    }
    @Test fun usesLongVersionCodes() {
        assertEquals(BazaarUpdateResult.Available(Int.MAX_VALUE.toLong() + 4), BazaarUpdatePolicy.result(73, Int.MAX_VALUE.toLong() + 4))
    }
    @Test fun laterSnoozesJustThisVersionForOneDay() {
        assertFalse(BazaarUpdatePolicy.shouldPrompt(74, 73, 74, 10_000, 10_001))
        assertTrue(BazaarUpdatePolicy.shouldPrompt(74, 73, 74, 10_000, 10_000 + BazaarUpdatePolicy.REMIND_AFTER_MS))
        assertTrue(BazaarUpdatePolicy.shouldPrompt(75, 73, 74, 10_000, 10_001))
    }
    @Test fun clockRollbackDoesNotSnoozeForever() {
        assertTrue(BazaarUpdatePolicy.shouldPrompt(74, 73, 74, 10_000, 9_000))
    }
    @Test fun noPromptForEqualOlderOrUnknownVersions() {
        for (code in listOf(-1L, 0L, 72L, 73L)) assertFalse(BazaarUpdatePolicy.shouldPrompt(code, 73, 0, 0, 10_000))
    }
    @Test fun updateUrisAlwaysPointToThisAppsBazaarListing() {
        assertEquals("bazaar://details/modal?id=com.khodroyar.app", BazaarUpdatePolicy.modalUri("com.khodroyar.app"))
        assertEquals("bazaar://details?id=com.khodroyar.app", BazaarUpdatePolicy.detailsUri("com.khodroyar.app"))
        assertEquals("https://cafebazaar.ir/app/com.khodroyar.app", BazaarUpdatePolicy.webUri("com.khodroyar.app"))
    }
    @Test fun packageNamesCannotInjectAQueryOrAnotherDownload() {
        for (name in listOf("../app", "com.app&id=evil", "https://elsewhere.example/app", ""))
            assertTrue(runCatching { BazaarUpdatePolicy.modalUri(name) }.isFailure)
    }
    @Test fun officialBinderThresholdAndPackageAreStable() {
        assertEquals(2_400_700L, BazaarUpdatePolicy.REMOTE_VERSION_SUPPORTED)
        assertEquals("com.farsitel.bazaar", BazaarUpdatePolicy.PACKAGE)
        assertEquals("com.farsitel.bazaar.service.UpdateCheckService.BIND", BazaarUpdatePolicy.BIND_ACTION)
    }
}
