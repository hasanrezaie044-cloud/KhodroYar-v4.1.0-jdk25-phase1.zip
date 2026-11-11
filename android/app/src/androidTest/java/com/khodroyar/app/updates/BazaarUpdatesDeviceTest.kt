package com.khodroyar.app.updates

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.khodroyar.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Simulated service outcomes; not evidence of a live Bazaar release or account. */
class BazaarUpdatesDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test fun minusOneSameOlderAndInvalidCodesNeverBecomeUpdateWarnings() = runBlocking {
        for (code in listOf(-1L, 0L, BuildConfig.VERSION_CODE.toLong() - 1, BuildConfig.VERSION_CODE.toLong(), Long.MAX_VALUE)) {
            val vm = BazaarUpdatesViewModel(app, BazaarVersionSource { BazaarVersionResult.Reported(code) })
            withContext(Dispatchers.Main) { vm.check() }
            val state = withTimeout(2_000) { vm.state.first { !it.checking && it.outcome != BazaarUpdatesViewModel.Outcome.NOT_CHECKED } }
            assertEquals(BazaarUpdatesViewModel.Outcome.NONE_REPORTED, state.outcome)
            assertFalse(state.showPrompt); assertNull(state.availableCode)
        }
    }

    @Test fun thrownServiceErrorDoesNotReportAFakeNewVersionOrLeaveCheckingStuck() = runBlocking {
        val vm = BazaarUpdatesViewModel(app, BazaarVersionSource { error("service disconnected") })
        withContext(Dispatchers.Main) { vm.check() }
        val state = withTimeout(2_000) { vm.state.first { !it.checking && it.outcome != BazaarUpdatesViewModel.Outcome.NOT_CHECKED } }
        assertEquals(BazaarUpdatesViewModel.Outcome.UNAVAILABLE, state.outcome)
        assertFalse(state.showPrompt)
    }

    @Test fun backgroundingCancelsAnInFlightCheckAndDoesNotLeaveAWarning() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val vm = BazaarUpdatesViewModel(app, BazaarVersionSource {
            try { started.complete(Unit); awaitCancellation() } finally { closed.complete(Unit) }
        })
        withContext(Dispatchers.Main) { vm.onForeground() }
        withTimeout(2_000) { started.await() }
        withContext(Dispatchers.Main) { vm.onBackground() }
        withTimeout(2_000) { closed.await() }
        assertFalse(vm.state.value.checking); assertFalse(vm.state.value.showPrompt)
    }
}
