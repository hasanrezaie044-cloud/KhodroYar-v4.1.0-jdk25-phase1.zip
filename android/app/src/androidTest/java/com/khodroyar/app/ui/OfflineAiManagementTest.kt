package com.khodroyar.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khodroyar.app.ai.offline.ModelTrust
import com.khodroyar.app.ai.offline.OfflineModelUiState
import com.khodroyar.app.ai.offline.OfflineModelUiState.Phase
import com.khodroyar.app.ui.screens.OfflineAiManagementSection
import com.khodroyar.app.ui.theme.CarManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** «مدیریت هوش مصنوعی آفلاین»: states, warning-before-download and every action button. No network. */
@RunWith(AndroidJUnit4::class)
class OfflineAiManagementTest {
    @get:Rule val compose = createComposeRule()
    @Before fun emulatorOnly() { check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) }

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(OfflineModelUiState())

    private fun render() {
        compose.setContent { CarManagerTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            OfflineAiManagementSection(state, meteredNetwork = { true },
                onDownload = { calls += "download" }, onPause = { calls += "pause" }, onCancel = { calls += "cancel" },
                onDelete = { calls += "delete" }, onRedownload = { calls += "redownload" })
        } } } }
    }

    @Test fun downloadShowsTheUnpinnedChecksumWarningBeforeStarting() {
        render()
        compose.onNodeWithText("مدیریت هوش مصنوعی آفلاین").assertIsDisplayed()
        compose.onNodeWithText("نصب نشده").assertIsDisplayed()
        compose.onNodeWithTag("offline-ai-download").performScrollTo().performClick()
        assertEquals(emptyList<String>(), calls) // a warning dialog comes first
        compose.onNodeWithText("دانلود هوش مصنوعی آفلاین").assertIsDisplayed()
        compose.onNodeWithText("متوجه شدم، دانلود کن").performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test fun downloadingShowsProgressSizeAndPauseCancel() {
        state = OfflineModelUiState(phase = Phase.DOWNLOADING, downloadedBytes = 1_294_073_856L, totalBytes = 2_588_147_712L,
            bytesPerSecond = 5_000_000L, remainingSeconds = 259L)
        render()
        compose.onNodeWithText("در حال دانلود").assertIsDisplayed()
        compose.onNodeWithTag("offline-ai-progress").assertIsDisplayed()
        compose.onNodeWithText("۱٫۲۹ از ۲٫۵۹ گیگابایت (۵۰٪)").assertIsDisplayed()
        compose.onNodeWithTag("offline-ai-pause").performScrollTo().performClick()
        compose.onNodeWithTag("offline-ai-cancel").performScrollTo().performClick()
        compose.onNodeWithText("لغو و حذف").performClick()
        assertEquals(listOf("pause", "cancel"), calls)
    }

    @Test fun readyModelCanBeDeletedOrRedownloadedAfterConfirmation() {
        state = OfflineModelUiState(phase = Phase.READY, downloadedBytes = 2_588_147_712L, totalBytes = 2_588_147_712L, trust = ModelTrust.PUBLISHED)
        render()
        compose.onNodeWithText("آماده").assertIsDisplayed()
        compose.onNodeWithTag("offline-ai-delete").performScrollTo().performClick()
        compose.onNodeWithText("حذف مدل آفلاین").assertIsDisplayed()
        compose.onNodeWithText("انصراف").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("offline-ai-redownload").performScrollTo().performClick()
        compose.onNodeWithText("حذف و دانلود دوباره").performClick()
        assertEquals(listOf("redownload"), calls)
    }

    @Test fun errorAndUnsupportedDeviceAreExplicit() {
        state = OfflineModelUiState(phase = Phase.ERROR, error = "سرور دانلود خطای HTTP 503 داد؛ کمی بعد دوباره تلاش کنید.")
        render()
        compose.onNodeWithText("خطا").assertIsDisplayed()
        compose.onNodeWithTag("offline-ai-error").assertIsDisplayed()
        compose.onNodeWithTag("offline-ai-retry").performScrollTo().assertIsDisplayed()

        state = OfflineModelUiState(deviceSupported = false)
        compose.onNodeWithTag("offline-ai-download").performScrollTo().assertIsNotEnabled()
    }
}
