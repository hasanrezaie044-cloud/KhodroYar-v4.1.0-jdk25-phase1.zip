package com.khodroyar.app.ui

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khodroyar.app.BuildConfig
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.update.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Missing-store/UI/fallback tests; the signed black-box job separately exercises real IPC with a simulated store. */
@RunWith(AndroidJUnit4::class)
class BazaarUpdateIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun emulatorOnly() { check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) }

    @Test fun absentBazaarDoesNotBlockStartupOrInventAnUpdate() {
        val result = runBlocking { withTimeout(3000) { BazaarUpdateService(context).check(BuildConfig.VERSION_CODE.toLong()) } }
        assertEquals(BazaarUpdateResult.NotInstalled, result)
    }

    @Test fun promptRequiresASeparateUpdateActionAndCanBeDismissed() {
        var later = 0; var updates = 0
        compose.setContent { CarManagerTheme { Surface {
            BazaarUpdateDialog(onLater = { later++ }, onUpdate = { updates++ })
        } } }
        compose.onNodeWithTag("bazaar-update-dialog").assertIsDisplayed()
        assertEquals(0, updates)
        compose.onNodeWithTag("bazaar-update-later").performClick()
        assertEquals(1, later); assertEquals(0, updates)
        compose.onNodeWithTag("bazaar-update-now").performClick()
        assertEquals(1, updates)
    }

    @Test fun nativeUpdateLinkIsExplicitlyRestrictedToBazaarAndTheCurrentPackage() {
        val intents = mutableListOf<Intent>()
        val capture = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { intents += Intent(intent) }
        }
        assertTrue(BazaarUpdateLinks.open(capture))
        assertEquals(1, intents.size)
        assertEquals("com.farsitel.bazaar", intents.single().`package`)
        assertEquals("bazaar://details/modal?id=com.khodroyar.app", intents.single().dataString)
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
    }

    @Test fun missingStoreUsesItsHttpsListingAndMissingBrowserFailsGracefully() {
        val intents = mutableListOf<Intent>()
        val capture = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                intents += Intent(intent)
                if (intent.`package` != null) throw ActivityNotFoundException("Synthetic missing store")
            }
        }
        assertTrue(BazaarUpdateLinks.open(capture))
        assertEquals(3, intents.size)
        assertEquals("bazaar://details?id=com.khodroyar.app", intents[1].dataString)
        assertNull(intents[2].`package`)
        assertEquals("https://cafebazaar.ir/app/com.khodroyar.app", intents[2].dataString)
        val noHandlers = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("Synthetic no browser") }
        }
        assertFalse(BazaarUpdateLinks.open(noHandlers))
    }
}
