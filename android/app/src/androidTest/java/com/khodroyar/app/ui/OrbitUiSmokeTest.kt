package com.khodroyar.app.ui

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.khodroyar.app.ai.AiSettings
import com.khodroyar.app.ai.AiSettingsStore
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.screens.*
import com.khodroyar.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic fixtures in a disposable emulator only. Never run against a personal app installation. */
class OrbitUiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: AppRepository

    @Before fun fixture() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic")) {
            "A disposable emulator is required: these UI fixtures replace the test app's data."
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        repository = AppRepository(context)
        repository.updateSettings { AppSettings(userFirstName = "آرمان", userLastName = "آزمایشی", monthlyIncomeGoal = 20_000_000.0,
            acceptedTermsVersion = TermsOfUse.VERSION, remindInstallments = false, remindOilChange = false, remindBodyInsurance = false,
            remindVehicleInsurance = false, remindInspection = false, remindBackup = false, autoBackupEnabled = false) }
        AiSettingsStore(context).save(AiSettings())
        context.getSharedPreferences("car_manager_onboarding", Context.MODE_PRIVATE).edit().apply {
            listOf("home", "services", "calendar", "reports", "settings").forEach { putBoolean("tab_$it", true) }
            listOf("fuel", "finance", "maintenance", "rates", "insurance", "traffic", "ai_assistant").forEach { putBoolean("tool_$it", true) }
        }.commit()
        runBlocking {
            withContext(Dispatchers.IO) { AppDatabase.get(context).clearAllTables() }
            val db = AppDatabase.get(context)
            val today = Jalali.todayString()
            (0..5).forEach { month ->
                db.services().insert(ServiceEntity("ui-service-$month", Jalali.addMonths(today, -month), "night", "soren",
                    80.0 + month * 15, 3.5, 3.5, "08:00", "11:30", "مبارکه", "اصفهان", "", "", 1,
                    0.0, 0.0, 0.0, 2_750_000L + month * 150_000L, "2026-01-01T00:00:00Z"))
            }
            db.fuels().insert(FuelEntity("ui-fuel", today, "gov", 42.0, 420_000.0, "soren", "2026-01-01T00:00:00Z"))
            db.maintenances().insert(MaintenanceEntity("ui-maint", today, "oil-change", "تعویض روغن", 650_000.0, 50_000.0,
                "soren", "رکورد آزمایشی", "2026-01-01T00:00:00Z"))
            db.personalExpenses().insert(PersonalExpenseEntity("ui-expense", today, "food", "ناهار", 160_000.0, "2026-01-01T00:00:00Z"))
            db.personalIncomes().insert(PersonalIncomeEntity("ui-income", today, "other", "درآمد آزمایشی", 125_000.0, "2026-01-01T00:00:00Z"))
            db.loans().insert(LoanEntity("ui-loan", "وام خودرو", 12_000_000.0, 18.0, Jalali.addMonths(today, -2), 12, "[]", "2026-01-01T00:00:00Z"))
        }
    }

    private fun render(dark: Boolean = false, rtl: Boolean = true, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalLanguage provides if (rtl) "fa" else "en") {
                CarManagerTheme(darkTheme = dark, palette = AppPalette.ORBIT) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    @Test fun applicationNavigationPrivacyAndToolScreens() {
        render { CarManagerRoot(repository) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2,750,000").fetchSemanticsNodes().isNotEmpty() }
        shot("01-home-light-fa")
        compose.onNodeWithContentDescription("پنهان کردن مبالغ").performClick()
        compose.onAllNodesWithText("2,750,000").assertCountEquals(0)
        shot("02-home-privacy")
        compose.onNodeWithContentDescription("نمایش مبالغ").performClick()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasText("ستونی"))
        compose.onNodeWithText("ستونی").performClick()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(
            hasContentDescription("نمودار روند؛ برای جزئیات نقطه‌ای را لمس کنید"))
        shot("18-dashboard-bars")
        compose.onNodeWithTag("nav-SERVICES").performClick()
        compose.onNodeWithText("سرویس جدید").assertIsDisplayed()
        shot("03-service-form")
        compose.onNodeWithTag("nav-CALENDAR").performClick()
        shot("04-calendar")
        compose.onNodeWithTag("nav-REPORTS").performClick()
        compose.onNodeWithText("گزارش سرویس‌های شما").assertIsDisplayed()
        shot("05-reports-summary")
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("ترکیب مالی بازه"))
        compose.onNodeWithText("ترکیب مالی بازه").assertIsDisplayed()
        shot("06-report-donut")
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithText("فضای شما، به سلیقهٔ شما").assertIsDisplayed()
        shot("07-settings")
        compose.onNodeWithContentDescription("ابزارها").performClick()
        compose.onNodeWithText("ابزارهای شما").assertIsDisplayed()
        shot("08-toolkit")
        compose.onNodeWithText("مالی، وام و اقساط").performClick()
        compose.onNodeWithText("تراز این ماه").assertIsDisplayed()
        shot("09-finance")
        compose.onNodeWithTag("nav-HOME").performClick()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasText("دستیار داخلی"))
        compose.onNodeWithText("دستیار داخلی").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("چی برات انجام بدم؟").fetchSemanticsNodes().isNotEmpty() }
        shot("10-assistant")
        compose.onNodeWithText("کارهای خودکار · 0").performClick()
        compose.onNodeWithText("کارهای خودکار من").assertIsDisplayed()
        shot("11-automation-manager")
    }

    @Test fun rtlChartTapAndLargePersianTextUseRealSelectedValue() {
        render(fontScale = 1.35f) {
            Column(Modifier.width(320.dp).testTag("compact-chart").padding(12.dp)) {
                SurfaceCard {
                    Text("روند درآمد", style = MaterialTheme.typography.titleMedium)
                    SmoothLineChart(listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور"),
                        listOf(125_000.0, 250_000.0, 150_000.0, 500_000.0, 400_000.0, 750_000.0),
                        valueText = { money(it) + " تومان" })
                }
            }
        }
        compose.onNodeWithTag("compact-chart").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithText("750,000 تومان").assertIsDisplayed()
        compose.onNodeWithContentDescription("نمودار روند؛ برای جزئیات نقطه‌ای را لمس کنید")
            .performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.onNodeWithText("125,000 تومان").assertIsDisplayed()
        shot("12-chart-rtl-320-large-text")
    }

    @Test fun ltrDarkChartTapAndSignedZeroAxis() {
        render(dark = true, rtl = false) {
            Column(Modifier.padding(20.dp)) {
                SurfaceCard {
                    Text("Monthly balance", style = MaterialTheme.typography.titleMedium)
                    SmoothLineChart(listOf("Jan", "Feb", "Mar", "Apr"), listOf(-125_000.0, 0.0, 300_000.0, 750_000.0),
                        valueText = { money(it) + " Toman" })
                }
            }
        }
        compose.onNodeWithContentDescription("Trend chart; tap a point for details").performTouchInput { click(Offset(4f, height / 2f)) }
        compose.onNodeWithText("-125,000 Toman").assertIsDisplayed()
        shot("13-chart-dark-en")
    }

    @Test fun donutLegendAndCentreHaveTheSameSelection() {
        var selected by mutableStateOf<String?>(null)
        render {
            SurfaceCard(modifier = Modifier.padding(20.dp)) {
                val colors = appAccents.chart
                InteractiveDonutChart(listOf(DonutSlice("fuel", "سوخت", 100_000.0, colors[0]),
                    DonutSlice("repair", "تعمیرات", 200_000.0, colors[1]), DonutSlice("buy", "خرید", 300_000.0, colors[2])),
                    selected, { selected = it.ifBlank { null } }, centerTitle = "جمع هزینه‌ها")
            }
        }
        compose.onNodeWithText("سوخت").performClick()
        compose.runOnIdle { assertEquals("fuel", selected) }
        compose.onAllNodesWithText("سوخت").assertCountEquals(2)
        shot("14-donut-selected")
        compose.onNodeWithContentDescription("نمودار سهم دسته‌ها؛ جزئیات در فهرست زیر").performTouchInput { click(center) }
        compose.runOnIdle { assertNull(selected) }
    }

    @Test fun privacyAndEmptyChartsExposeNoInventedOrPrivateAmount() {
        render {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SmoothLineChart(listOf("امروز"), listOf(987_654.0), masked = true)
                InteractiveDonutChart(emptyList(), null, {}, centerTitle = "بازهٔ خالی")
            }
        }
        compose.onNodeWithText("نمودار مالی در حالت حریم خصوصی پنهان است").assertIsDisplayed()
        compose.onNodeWithText("در این بازه رکوردی ثبت نشده است").assertIsDisplayed()
        compose.onAllNodesWithText("987,654", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("1").assertCountEquals(0)
        shot("15-privacy-empty")
    }

    @Test fun loginUsesNativeSecureInputAndFingerprintCallback() {
        var pin by mutableStateOf("")
        var fingerprint = false
        render {
            AnimatedLoginScreen(pin, { pin = it }, "خوش آمدید", "رمز عبور خود را وارد کنید", "", 0, false, true, { fingerprint = true })
        }
        compose.onNodeWithText("ورود با اثر انگشت").performClick()
        compose.runOnIdle { assertTrue(fingerprint) }
        compose.onNode(hasSetTextAction()).performTextInput("12345")
        compose.runOnIdle { assertEquals("12345", pin) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
        shot("16-login-native-password")
    }

    @Test fun agreementKeepsExplicitConsentAtEnlargedFont() {
        var accepted = false
        render(fontScale = 1.35f) { AgreementScreen({ accepted = true }, {}) }
        compose.onNodeWithText("می‌پذیرم و ادامه").assertIsNotEnabled()
        compose.onNodeWithText("متن بالا را خوانده‌ام و می‌پذیرم").performClick()
        compose.onNodeWithText("می‌پذیرم و ادامه").assertIsEnabled()
        shot("17-agreement-large-text")
        compose.onNodeWithText("می‌پذیرم و ادامه").performClick()
        compose.runOnIdle { assertTrue(accepted) }
    }

    @Test fun darkNavigationStaysUsableAt320DpAndEnlargedFont() {
        render(dark = true, fontScale = 1.35f) {
            Box(Modifier.width(320.dp).testTag("compact-app").fillMaxHeight()) { CarManagerRoot(repository) }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2,750,000").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("compact-app").assertWidthIsEqualTo(320.dp)
        listOf("HOME", "SERVICES", "CALENDAR", "REPORTS", "SETTINGS").forEach {
            compose.onNodeWithTag("nav-$it").assertIsDisplayed().assertHasClickAction()
        }
        shot("19-home-dark-320-large-text")
        compose.onNodeWithTag("nav-SERVICES").performClick()
        compose.onNodeWithText("سرویس جدید").assertIsDisplayed()
    }

    private fun shot(name: String) {
        // Synchronize the rendered Compose frame, not just updated semantics. A raw
        // UiAutomation screenshot can still contain the frame before a chart tap.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // ModalBottomSheet/Dialog adds another root; capture the topmost visible
        // surface rather than asking onRoot() to match exactly one window.
        val node = if (name == "19-home-dark-320-large-text") compose.onNodeWithTag("compact-app") else compose.onAllNodes(isRoot()).onLast()
        val bitmap = node.captureToImage().asAndroidBitmap()
        // UTP uninstalls the tested APK after a run, removing its app-specific external files.
        // Publish synthetic screenshots to shared Downloads so CI can collect them afterwards.
        check(Build.VERSION.SDK_INT >= 29)
        val resolver = context.contentResolver
        val metadata = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/KhodroYar-UI-Smoke")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        bitmap.recycle()
    }
}
