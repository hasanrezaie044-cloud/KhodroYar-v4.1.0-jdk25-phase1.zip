package com.khodroyar.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.khodroyar.app.ai.*
import com.khodroyar.app.automation.*
import com.khodroyar.app.data.repo.*
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    @Volatile private var imeVisible = false

    @Before fun fixture() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic")) {
            "A disposable emulator is required: these UI fixtures replace the test app's data."
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        QuickEntries.invalidate()
        SettingsStore.get(context).acknowledgeQuickMileageNotice()
        check(SettingsStore.get(context).pendingQuickMileage.isNullOrBlank()) { "An earlier journal test did not finish recovery" }
        repository = AppRepository(context)
        repository.updateSettings { AppSettings(userFirstName = "آرمان", userLastName = "آزمایشی", monthlyIncomeGoal = 20_000_000.0,
            acceptedTermsVersion = TermsOfUse.VERSION, remindInstallments = false, remindOilChange = false, remindBodyInsurance = false,
            remindVehicleInsurance = false, remindInspection = false, remindBackup = false, autoBackupEnabled = false) }
        AiSettingsStore(context).save(AiSettings())
        // First-use navigation must stay free of automatic tutorial dialogs.
        context.getSharedPreferences("car_manager_onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking {
            // The real ALBERT chat test persists history by design. A different
            // first-use/navigation fixture must not depend on JUnit method order.
            ConversationRepository(context).clearAll()
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

    private fun render(dark: Boolean = false, rtl: Boolean = true, fontScale: Float = 1f, edgeToEdge: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            val activity = LocalContext.current.hostActivity()
            val imeBottom = WindowInsets.ime.getBottom(density)
            SideEffect { imeVisible = imeBottom > 0 }
            DisposableEffect(activity, edgeToEdge) {
                val previous = activity?.window?.attributes?.softInputMode
                // The IME regression opts into MainActivity's edge-to-edge setup
                // so fitting decor cannot consume IME insets before Compose. Other
                // isolated component fixtures retain their established viewport.
                if (edgeToEdge) (activity as? androidx.activity.ComponentActivity)?.enableEdgeToEdge()
                activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                onDispose { previous?.let { activity?.window?.setSoftInputMode(it) } }
            }
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
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("home-bar-toggle"))
        compose.onNodeWithTag("home-bar-toggle").performClick()
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
        compose.onNodeWithText("ظاهر برنامه").assertIsDisplayed()
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

    @Test fun homeTimelineMovesRightInPersianForBothChartTypes() {
        render { CarManagerRoot(repository) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2,750,000").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("dashboard-list")
        list.performScrollToNode(hasTestTag("home-line-chart"))
        val plot = compose.onNodeWithContentDescription("نمودار روند؛ برای جزئیات نقطه‌ای را لمس کنید")
        plot.performTouchInput { click(Offset(4f, height / 2f)) }
        compose.onNode(hasText("3,500,000 تومان") and hasAnyAncestor(hasTestTag("home-line-chart"))).assertIsDisplayed()
        plot.performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.onNode(hasText("2,750,000 تومان") and hasAnyAncestor(hasTestTag("home-line-chart"))).assertIsDisplayed()
        shot("20-home-line-timeline-right")
        list.performScrollToNode(hasTestTag("home-bar-toggle"))
        compose.onNodeWithTag("home-bar-toggle").performClick()
        list.performScrollToNode(hasTestTag("home-bar-chart"))
        plot.performTouchInput { click(Offset(4f, height / 2f)) }
        compose.onNode(hasText("3,500,000 تومان") and hasAnyAncestor(hasTestTag("home-bar-chart"))).assertIsDisplayed()
        plot.performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.onNode(hasText("2,750,000 تومان") and hasAnyAncestor(hasTestTag("home-bar-chart"))).assertIsDisplayed()
    }

    @Test fun yearlyTotalsAndRecentServicesMoveToReportsWithoutLosingRecords() {
        render { CarManagerRoot(repository) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2,750,000").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("dashboard-end"))
        compose.onAllNodesWithTag("report-annual-toggle").assertCountEquals(0)
        compose.onAllNodesWithText("آخرین سرویس‌ها").assertCountEquals(0)
        compose.onNodeWithTag("nav-REPORTS").performClick()
        val list = compose.onNodeWithTag("report-list")
        list.performScrollToNode(hasTestTag("report-annual-toggle"))
        compose.onNodeWithTag("report-annual-toggle").performClick()
        list.performScrollToNode(hasTestTag("report-annual-statistics"))
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("420,000") and hasAnyAncestor(hasTestTag("report-annual-statistics"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("42") and hasAnyAncestor(hasTestTag("report-annual-statistics"))).assertIsDisplayed()
        shot("21-reports-annual-statistics")
        list.performScrollToNode(hasTestTag("report-annual-toggle"))
        compose.onNodeWithTag("report-annual-toggle").performClick()
        list.performScrollToNode(hasTestTag("report-recent-toggle"))
        compose.onNodeWithTag("report-recent-toggle").performClick()
        list.performScrollToNode(hasTestTag("report-recent-ui-service-0"))
        compose.onNodeWithTag("report-recent-ui-service-0").assertIsDisplayed().assertHasClickAction()
        shot("22-reports-recent-services")
        compose.onNodeWithTag("report-recent-ui-service-0").performClick()
        compose.onAllNodesWithText("ویرایش سرویس").onFirst().assertIsDisplayed()
        val count = runBlocking { AppDatabase.get(context).services().allOnce().size }
        assertEquals(6, count)
    }

    @Test fun reportTableAndFiltersStayUsableAt320DpWithLargeText() {
        render(fontScale = 1.35f) {
            Box(Modifier.width(320.dp).testTag("compact-app").fillMaxHeight()) { CarManagerRoot(repository) }
        }
        compose.onNodeWithTag("compact-app").assertWidthIsEqualTo(320.dp)
        compose.onNodeWithTag("nav-REPORTS").performClick()
        val list = compose.onNodeWithTag("report-list")
        // Date fields deliberately expose one accessible button description,
        // not their decorative inner Text/OutlinedTextField semantics.
        list.performScrollToNode(hasContentDescription("از تاریخ", substring = true))
        val fromField = compose.onNodeWithContentDescription("از تاریخ", substring = true).assertIsDisplayed()
        val toField = compose.onNodeWithContentDescription("تا تاریخ", substring = true).assertIsDisplayed()
        assertTrue(fromField.fetchSemanticsNode().boundsInRoot.bottom <= toField.fetchSemanticsNode().boundsInRoot.top)
        list.performScrollToNode(hasTestTag("report-summary-table"))
        compose.onNodeWithTag("report-summary-table").assertIsDisplayed()
        compose.onNode(hasText("نوع سرویس") and hasAnyAncestor(hasTestTag("report-summary-table"))).assertIsDisplayed()
        shot("23-report-table-320-large-text")
        compose.onNodeWithTag("report-summary-table").performTouchInput { swipeRight() }
        compose.onNode(hasText("مبلغ (تومان)") and hasAnyAncestor(hasTestTag("report-summary-table"))).assertIsDisplayed()
        shot("24-report-table-scrolled-amount")
        compose.onNodeWithTag("nav-HOME").performClick()
        compose.onNodeWithTag("nav-HOME").assertIsSelected()
        compose.onNodeWithTag("nav-REPORTS").assertIsNotSelected()
        compose.onAllNodesWithText("آموزش", substring = true).assertCountEquals(0)
    }

    @Test fun quickExpenseRequiresReviewSupportsCorrectionAndConfirmedUndoAtLargeFont() {
        render(fontScale = 1.35f, edgeToEdge = true) {
            Box(Modifier.width(320.dp).testTag("compact-app").fillMaxHeight()) { CarManagerRoot(repository) }
        }
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("home-quick-add"))
        compose.onNodeWithTag("home-quick-add").performClick()
        compose.onNodeWithTag("quick-title").performScrollTo().performClick()
        compose.onNodeWithTag("quick-title").performTextReplacement("پارکینگ")
        // Focus through an actual tap BEFORE semantic text injection. The tag
        // includes supporting text; tap the editor, not its lower support area.
        compose.onNodeWithTag("quick-amount").performScrollTo().performTouchInput {
            click(Offset(width / 2f, height / 3f))
        }
        try {
            compose.waitUntil(15_000) { imeVisible }
        } catch (e: Throwable) {
            shot("debug-quick-edit-state")
            val focused = compose.onAllNodes(hasTestTag("quick-amount") and isFocused()).fetchSemanticsNodes().size
            throw AssertionError("Software IME did not open after tapping amount; focused fields=$focused; ${imeDiagnostics()}", e)
        }
        compose.onNodeWithTag("quick-amount").performTextReplacement("75000")
        compose.waitUntil(15_000) { imeVisible }
        pressSystemBack()
        try {
            compose.waitUntil(15_000) { !imeVisible }
        } catch (e: Throwable) {
            shot("debug-quick-edit-state")
            val forms = compose.onAllNodesWithTag("quick-entry-screen").fetchSemanticsNodes().size
            throw AssertionError("System Back left IME visible; quick forms=$forms", e)
        }
        compose.onNodeWithTag("quick-title").assertExists()
        compose.onNodeWithTag("quick-amount").assertTextContains("75,000")
        compose.onNodeWithTag("quick-title").performScrollTo()
        shot("25-quick-entry-320-large-text")
        compose.onNodeWithTag("quick-confirm").performClick()
        compose.onNodeWithTag("quick-review").assertExists()
        assertEquals(1, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
        shot("26-quick-entry-review")
        compose.onNodeWithContentDescription("بازگشت").performClick()
        compose.onNodeWithTag("quick-title").assertExists()
        compose.onAllNodesWithTag("quick-review").assertCountEquals(0)
        assertEquals(1,runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
        compose.onNodeWithTag("quick-confirm").performClick()
        compose.onNodeWithTag("quick-confirm").performClick()
        compose.waitUntil(15_000) { repository.quickEntries.receipt.value != null }
        assertEquals(2, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
        val id = repository.quickEntries.receipt.value!!.id
        // A native success snackbar temporarily overlays the bottom of the scrolled
        // card. Dismiss it as a person can, rather than tapping through the overlay.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("dismiss-snackbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dismiss-snackbar").performClick()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("edit-quick-entry"))
        compose.onNodeWithTag("edit-quick-entry").performClick()
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("quick-title").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            shot("debug-quick-edit-state")
            val root=compose.onAllNodes(isRoot()).onLast().printToString(maxDepth=3).take(1500)
            throw AssertionError("Quick edit did not open; receipt=${repository.quickEntries.receipt.value?.id}; $root",e)
        }
        compose.onNodeWithTag("quick-amount").performScrollTo().performTextReplacement("80000")
        compose.onNodeWithTag("quick-confirm").performClick()
        compose.onNodeWithTag("quick-confirm").performClick()
        compose.waitUntil(15_000) { repository.quickEntries.receipt.value?.draft?.amount == 80000.0 }
        assertEquals(2, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
        assertEquals(id, repository.quickEntries.receipt.value!!.id)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("dismiss-snackbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dismiss-snackbar").performClick()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("recent-quick-entry"))
        shot("27-recent-entry-edit-undo")
        compose.onNodeWithTag("undo-quick-entry").performClick()
        assertNotNull(runBlocking { AppDatabase.get(context).insights().expense(id) })
        compose.onNodeWithTag("confirm-quick-undo").performClick()
        compose.waitUntil(15_000) { repository.quickEntries.receipt.value == null }
        assertNull(runBlocking { AppDatabase.get(context).insights().expense(id) })
        assertEquals(1, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("dismiss-snackbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dismiss-snackbar").performClick()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("home-quick-add"))
        compose.onNodeWithTag("home-quick-add").performClick()
        compose.onNodeWithTag("quick-entry-screen").assertIsDisplayed()
        pressSystemBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("quick-entry-screen").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav-HOME").assertIsSelected()
        assertEquals(1, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
    }

    @Test fun repeatFuelFillsDraftOnlyAndKeepsActualPaidAmount() {
        var finished = false
        render(dark = true) { QuickEntryScreen(repository, initialKind = QuickKind.FUEL, onFullService = {}, onDone = { finished = true }) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("quick-suggestion-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick-suggestion-0").performClick()
        assertEquals(1, runBlocking { AppDatabase.get(context).fuels().allOnce().size })
        compose.onNodeWithTag("quick-liters").performScrollTo().performTextReplacement("35")
        compose.onNodeWithTag("quick-amount").performScrollTo().performTextReplacement("350000")
        compose.onNodeWithTag("quick-confirm").performClick()
        shot("28-quick-fuel-dark-review")
        compose.onNodeWithTag("quick-confirm").performClick()
        compose.waitUntil(15_000) { finished }
        val row = runBlocking { AppDatabase.get(context).fuels().allOnce().first { it.id != "ui-fuel" } }
        assertEquals(35.0, row.liters, 0.0); assertEquals(350000.0, row.total, 0.0)
        assertEquals(Jalali.todayString(), row.date)
    }

    @Test fun quickTripIsRepricedNotDuplicatedAndMileageUndoIsGuarded() = runBlocking {
        val source = AppDatabase.get(context).services().byId("ui-service-0")!!
        val draft = QuickDraft.from(source)
        val income = repository.quickEntries.previewIncome(draft)
        val operation = java.util.UUID.randomUUID().toString()
        val receipt = repository.quickEntries.save(draft, operation, income)
        assertEquals(receipt, repository.quickEntries.save(draft, operation, income))
        assertEquals(7, AppDatabase.get(context).services().allOnce().size)
        assertEquals(80.0, repository.settingsNow.vehicleCurrentKm, 0.0)
        assertEquals(income, AppDatabase.get(context).services().byId(receipt.id)!!.income)
        assertTrue(repository.quickEntries.undo(receipt))
        assertEquals(0.0, repository.settingsNow.vehicleCurrentKm, 0.0)
        assertEquals(6, AppDatabase.get(context).services().allOnce().size)
        assertTrue(runCatching { repository.quickEntries.save(draft, operation, income) }.isFailure)
        val next = repository.quickEntries.save(draft, java.util.UUID.randomUUID().toString(), income)
        repository.updateSettings { it.copy(vehicleCurrentKm = 90000.0) }
        assertFalse(repository.quickEntries.undo(next))
        assertNotNull(AppDatabase.get(context).services().byId(next.id))
        assertEquals(90000.0, repository.settingsNow.vehicleCurrentKm, 0.0)
    }

    @Test fun changedExpenseCannotBeDeletedByStaleQuickReceipt() = runBlocking {
        val receipt = repository.quickEntries.save(QuickDraft(title = "قهوه", amount = 50000.0), java.util.UUID.randomUUID().toString())
        val row = AppDatabase.get(context).insights().expense(receipt.id)!!
        repository.updatePersonalExpense(row.copy(amount = 70000.0))
        assertFalse(repository.quickEntries.undo(receipt))
        assertEquals(70000.0, AppDatabase.get(context).insights().expense(receipt.id)!!.amount, 0.0)
    }

    @Test fun frequentAmountsComeFromRepeatedRealRecordsAndOnlyFillTheDraft() {
        runBlocking {
            val db=AppDatabase.get(context)
            repeat(3) { i -> db.personalExpenses().insert(PersonalExpenseEntity("hint-$i",Jalali.todayString(),"coffee","قهوه",50000.0,"2026-01-01T00:00:00Z")) }
            val hints=repository.insights.frequentAmounts(QuickKind.EXPENSE.name).first()
            assertEquals(1,hints.size);assertEquals(50000.0,hints.single().value,0.0);assertEquals(3,hints.single().uses)
        }
        render { QuickEntryScreen(repository,onFullService={},onDone={}) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("frequent-amount-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("frequent-amount-0").performScrollTo().performClick()
        compose.onNodeWithTag("quick-amount").assertTextContains("50,000")
        assertEquals(4,runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
    }

    @Test fun quickAndFullTripWritersDoNotLoseEachOthersMileage() = runBlocking {
        val db=AppDatabase.get(context);val draft=QuickDraft.from(db.services().byId("ui-service-0")!!)
        val income=repository.quickEntries.previewIncome(draft)
        val receipt=coroutineScope {
            val quick=async(Dispatchers.Default) { repository.quickEntries.save(draft,java.util.UUID.randomUUID().toString(),income) }
            val full=async(Dispatchers.Default) { repository.addService(Jalali.todayString(),com.khodroyar.app.core.rates.ServiceType.NIGHT,"soren",40.0,1.0,1.0,"08:00","09:00","a","b","","",0,0.0,0.0,0.0) }
            full.await();quick.await()
        }
        assertEquals(120.0,repository.settingsNow.vehicleCurrentKm,0.0)
        assertEquals(8,db.services().allOnce().size)
        val undone=repository.quickEntries.undo(receipt)
        assertEquals(if(undone) 40.0 else 120.0,repository.settingsNow.vehicleCurrentKm,0.0)
        assertEquals(if(undone) 7 else 8,db.services().allOnce().size)
    }

    @Test fun maintenanceForecastUsesHistoryAndOpensTheCorrectReplacementForm() {
        runBlocking {
            val unknown=repository.maintenanceDue("brake-pad","لنت ترمز",30000,currentOdometer=0.0,nextKmOverride=150000.0)
            assertNull(unknown.remainingKm);assertEquals(150000.0,unknown.nextKm,0.0)
            val db = AppDatabase.get(context); val base = db.services().byId("ui-service-0")!!
            listOf(7,14,27).forEach { days -> db.services().insert(base.copy(id = "pace-$days", date = Jalali.addDays(Jalali.todayString(),-days), km = 140.0)) }
        }
        repository.updateSettings { it.copy(vehicleCurrentKm = 100000.0, oilCurrentKm = 95000.0, oilNextKm = 105000.0) }
        render { MaintenanceScreen(repository) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("maintenance-eta-oil-change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("maintenance-eta-oil-change").assertIsDisplayed()
        shot("29-maintenance-forecast")
        compose.onNodeWithTag("maintenance-record-oil-change").performClick()
        compose.onNodeWithText("نوع تعمیر").assertIsDisplayed()
        compose.onNodeWithText("کیلومتر تعویض روغن").assertIsDisplayed()
        shot("30-maintenance-direct-form")
    }

    @Test fun maintenanceNotificationTargetOpensOnlyTheSelectedUnsavedType() {
        val type = com.khodroyar.app.core.finance.MaintenanceType.BRAKE_PAD
        assertEquals(type, com.khodroyar.app.notifications.Notifications.maintenanceTypeFromTarget(com.khodroyar.app.notifications.Notifications.maintenanceTarget(type)))
        assertNull(com.khodroyar.app.notifications.Notifications.maintenanceTypeFromTarget("maintenance:delete-all"))
        var handled=false
        render { CarManagerRoot(repository, launchTarget=com.khodroyar.app.notifications.Notifications.maintenanceTarget(type), onLaunchHandled={handled=true}) }
        compose.onNodeWithText("نوع تعمیر: لنت ترمز").assertIsDisplayed()
        assertTrue(handled)
        assertEquals(1,runBlocking { AppDatabase.get(context).maintenances().allOnce().size })
    }

    @Test fun trackedQuickMaintenanceUndoRestoresOnlyItsTrackingAndRefusesLaterChanges() = runBlocking {
        repository.updateSettings { it.copy(vehicleCurrentKm = 100000.0, oilCurrentKm = 90000.0, oilNextKm = 101000.0) }
        val draft = QuickDraft(QuickKind.MAINTENANCE, amount = 650000.0, maintenanceType = "oil-change", km = 100000.0)
        val receipt = repository.quickEntries.save(draft, java.util.UUID.randomUUID().toString())
        assertEquals(100000.0, repository.settingsNow.oilCurrentKm, 0.0)
        assertEquals(110000.0, repository.settingsNow.oilNextKm, 0.0)
        assertTrue(repository.quickEntries.undo(receipt))
        assertEquals(90000.0, repository.settingsNow.oilCurrentKm, 0.0)
        assertEquals(101000.0, repository.settingsNow.oilNextKm, 0.0)
        assertEquals(100000.0, repository.settingsNow.vehicleCurrentKm, 0.0)
        val second = repository.quickEntries.save(draft, java.util.UUID.randomUUID().toString())
        repository.updateSettings { it.copy(oilNextKm = 112000.0) }
        assertFalse(repository.quickEntries.undo(second))
        assertNotNull(AppDatabase.get(context).insights().maintenance(second.id))
        assertEquals(112000.0, repository.settingsNow.oilNextKm, 0.0)
    }

    @Test fun financialInsightsUseSameRecordedTotalsAndSeparateVehicleRatios() {
        render { CarManagerRoot(repository) }
        compose.onNodeWithTag("nav-REPORTS").performClick()
        compose.onNodeWithTag("report-list").performScrollToNode(hasTestTag("financial-comparison"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("comparison-details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("comparison-details").performClick()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("خالص خودرو / کیلومتر"))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("21,000 تومان").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("21,000 تومان").assertIsDisplayed()
        shot("31-financial-comparison")
        val today = Jalali.todayString(); val date = Jalali.parse(today)!!
        val current = runBlocking { repository.insights.financial(Jalali.format(date.year,date.month,1), today).first() }
        assertEquals(1_645_000.0, current.net, 0.0)
        assertEquals(1_680_000.0, current.vehicleNet, 0.0)
    }

    @Test fun automationManagerShowsPersistedFailureAndBatteryDiagnostics() {
        val rule = AutomationRule(java.util.UUID.randomUUID().toString(), RuleKind.EXPENSE, "قهوه", RepeatSchedule(RepeatFrequency.DAILY,Jalali.todayString()), amount = 50000.0, failures = 1, lastMessage = "ثبت تأیید نشد؛ در انتظار تلاش مجدد")
        render { AutomationManagerSheet(listOf(rule), false, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("background-health").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("ناموفق").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ناموفق").performScrollTo().assertIsDisplayed()
        shot("32-automation-health")
    }

    @Test fun localRepeatUsesActualSourceRequiresApprovalAndRejectsChangedSnapshot() = runBlocking {
        val db = AppDatabase.get(context); val yesterday = Jalali.addDays(Jalali.todayString(),-1)
        val source = PersonalExpenseEntity("repeat-source",yesterday,"coffee","قهوه",50000.0,"2026-01-02T00:00:00Z")
        db.personalExpenses().insert(source)
        val tools = AppAgentTools(context, repository); val assistant = LocalAssistant(tools)
        val pending = (assistant.run("همون هزینه دیروز رو برای امروز ثبت کن") as AgentStep.Confirm).pending
        assertEquals(2,db.personalExpenses().allOnce().size)
        db.personalExpenses().insert(source.copy(amount = 60000.0))
        assertFalse(tools.execute(pending.call).ok)
        assertEquals(2,db.personalExpenses().allOnce().size)
        val fresh = (assistant.run("همون قهوه دیروز رو برای امروز تکرار کن") as AgentStep.Confirm).pending
        assertTrue(fresh.summary.contains("60,000"))
        assertTrue(tools.execute(fresh.call).ok);assertTrue(tools.execute(fresh.call).ok)
        assertEquals(3,db.personalExpenses().allOnce().size)
    }

    @Test fun ambiguousRepeatAsksThenAcceptsAShortTitleWithoutWritingBeforeApproval() = runBlocking {
        val db=AppDatabase.get(context);val yesterday=Jalali.addDays(Jalali.todayString(),-1)
        db.personalExpenses().insert(PersonalExpenseEntity("y-coffee",yesterday,"coffee","قهوه",50000.0,""))
        db.personalExpenses().insert(PersonalExpenseEntity("y-food",yesterday,"food","خوراک",90000.0,""))
        val tools=AppAgentTools(context,repository);val assistant=LocalAssistant(tools)
        assertTrue(assistant.run("همون هزینه دیروز رو برای امروز ثبت کن") is AgentStep.Reply)
        assertEquals(3,db.personalExpenses().allOnce().size)
        val action=(assistant.run("قهوه") as AgentStep.Confirm).pending
        assertTrue(action.summary.contains("50,000"));assertEquals(3,db.personalExpenses().allOnce().size)
        assertTrue(tools.execute(action.call).ok);assertEquals(4,db.personalExpenses().allOnce().size)
    }

    @Test fun fixedSalaryRepeatNeverAdvancesVehicleMileage() = runBlocking {
        val source=AppDatabase.get(context).services().byId("ui-service-0")!!.copy(type="fixed-salary",km=0.0,hours=0.0,workHours=0.0,startTime="",endTime="",origin="",destination="")
        repository.saveRatesForYear(Jalali.currentYear(),repository.ratesFor(Jalali.currentYear()).copy(dailySalary=2_000_000.0))
        val draft=QuickDraft.from(source);val income=repository.quickEntries.previewIncome(draft)
        assertEquals(2_000_000L,income)
        val receipt=repository.quickEntries.save(draft,java.util.UUID.randomUUID().toString(),income)
        assertEquals(0.0,repository.settingsNow.vehicleCurrentKm,0.0)
        assertTrue(repository.quickEntries.undo(receipt));assertEquals(0.0,repository.settingsNow.vehicleCurrentKm,0.0)
    }

    @Test fun interruptedQuickTripRecoversCommittedMileageOnlyOnceAndAbortedWriteDoesNothing() = runBlocking {
        val db=AppDatabase.get(context);val settings=SettingsStore.get(context)
        val row=db.services().byId("ui-service-0")!!.copy(id=java.util.UUID.randomUUID().toString())
        val before=settings.current;val after=before.copy(vehicleCurrentKm=80.0)
        val journal=QuickMileageJournal.plan(QuickKind.SERVICE,row.id,null,row,before,after,true,null)!!
        settings.stageQuickMileage(journal.encode(),journal::beforeMatches)
        // Simulate process death after a Room commit but before the preferences handoff.
        db.services().insert(row);QuickEntries.invalidate()
        repository.quickEntries.recover();repository.quickEntries.recover()
        assertEquals(80.0,repository.settingsNow.vehicleCurrentKm,0.0)
        assertNull(settings.pendingQuickMileage)
        val abortedRow=row.copy(id=java.util.UUID.randomUUID().toString())
        val aborted=QuickMileageJournal.plan(QuickKind.SERVICE,abortedRow.id,null,abortedRow,settings.current,settings.current.copy(vehicleCurrentKm=160.0),true,null)!!
        settings.stageQuickMileage(aborted.encode(),aborted::beforeMatches)
        // No Room commit: recovery removes the journal without adding mileage.
        repository.quickEntries.recover()
        assertEquals(80.0,repository.settingsNow.vehicleCurrentKm,0.0)
        assertNull(settings.pendingQuickMileage)
    }

    @Test fun interruptedUndoRecoversAndManualMileageCorrectionWinsOverAJournal() = runBlocking {
        val db=AppDatabase.get(context);val settings=SettingsStore.get(context)
        val row=db.services().byId("ui-service-0")!!
        repository.updateSettings { it.copy(vehicleCurrentKm=80.0) }
        val undo=QuickMileageJournal.plan(QuickKind.SERVICE,row.id,row,null,settings.current,settings.current.copy(vehicleCurrentKm=0.0),true,null)!!
        settings.stageQuickMileage(undo.encode(),undo::beforeMatches)
        db.services().deleteById(row.id)
        repository.quickEntries.recover()
        assertEquals(0.0,repository.settingsNow.vehicleCurrentKm,0.0)
        val saved=row.copy(id=java.util.UUID.randomUUID().toString())
        val insert=QuickMileageJournal.plan(QuickKind.SERVICE,saved.id,null,saved,settings.current,settings.current.copy(vehicleCurrentKm=80.0),true,null)!!
        settings.stageQuickMileage(insert.encode(),insert::beforeMatches)
        db.services().insert(saved)
        repository.updateSettings { it.copy(vehicleCurrentKm=90000.0) }
        repository.quickEntries.recover()
        assertEquals(90000.0,repository.settingsNow.vehicleCurrentKm,0.0)
        assertNotNull(db.services().byId(saved.id));assertNull(settings.pendingQuickMileage)
        assertNotNull(repository.quickEntries.recoveryNotice.value)
        repository.quickEntries.acknowledgeRecoveryNotice()
    }

    @Test fun laterMaintenanceWriterInvalidatesUndoEvenIfReplacementMileageIsTheSame() = runBlocking {
        repository.updateSettings { it.copy(vehicleCurrentKm=100000.0) }
        val draft=QuickDraft(QuickKind.MAINTENANCE,amount=650000.0,maintenanceType="oil-change",km=100000.0)
        val receipt=repository.quickEntries.save(draft,java.util.UUID.randomUUID().toString())
        repository.addMaintenance(Jalali.todayString(),"oil-change","تعویض روغن",700000.0,100000.0,"soren","")
        assertFalse(repository.quickEntries.undo(receipt))
        assertEquals(110000.0,repository.settingsNow.oilNextKm,0.0)
        assertNotNull(AppDatabase.get(context).insights().maintenance(receipt.id))
    }

    @Test fun twelveThousandRecordsUseBoundedSqlAndRenderHomeWithMeasuredEvidence() {
        val db = AppDatabase.get(context); val today = Jalali.todayString()
        runBlocking {
            val base = db.services().byId("ui-service-0")!!
            db.services().clear()
            db.services().insertAll((0 until 12000).map { i -> base.copy(id="volume-$i",date=Jalali.addDays(today,-(i%365)),km=10.0,hours=1.0,workHours=1.0,income=1000L) })
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val totals = runBlocking { repository.insights.financial(Jalali.addDays(today,-364),today).first() }
        val queryMs = android.os.SystemClock.elapsedRealtime()-started
        assertEquals(12000,totals.serviceCount);assertEquals(12000000.0,totals.serviceIncome,0.0)
        val daily = runBlocking { repository.insights.mileageDays(Jalali.addDays(today,-55),today).first() }
        assertEquals(56,daily.size)
        assertEquals(3,runBlocking { repository.insights.recentServices().first().size })
        val renderStarted = android.os.SystemClock.elapsedRealtime()
        render { CarManagerRoot(repository) }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("33,000").fetchSemanticsNodes().isNotEmpty() }
        val renderMs = android.os.SystemClock.elapsedRealtime()-renderStarted
        shot("33-home-12000-records")
        writeTextArtifact("performance-12000.json", """{"environment":"API35 x86_64 debug emulator; not a phone benchmark","records":12000,"aggregateQueryMs":$queryMs,"homeFirstDataMs":$renderMs,"mileageRows":${daily.size}}""")
    }

    @Test fun offlineModelNoticeAndRuntimeLicenceOpenWithoutNetworkOrModelLoading() {
        render { Column(Modifier.padding(16.dp)) { OfflineModelLicenseLink() } }
        compose.onNodeWithText("مدل آفلاین و مجوزها").performClick()
        compose.onNodeWithText("Model & modifications").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("KhodroYar 5.5 · Offline Persian task interpretation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("KhodroYar 5.5 · Offline Persian task interpretation").assertIsDisplayed()
        compose.onNodeWithText("بازگشت").performClick()
        compose.onNodeWithText("ONNX Runtime · MIT").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("MIT License").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("MIT License").assertIsDisplayed()
    }

    @Test fun realOfflineAlbertChatShowsReviewWithoutWritingAnyExpense() {
        lateinit var vm: AiAssistantViewModel
        render(fontScale = 1.2f) {
            val current: AiAssistantViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            SideEffect { vm = current }
            Box(Modifier.width(320.dp).fillMaxHeight().testTag("offline-agent-workspace")) { AiAssistantScreen(current) }
        }
        compose.waitUntil(20_000) { vm.connection == ConnectionState.LOCAL }
        val before = runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size }
        compose.runOnIdle { vm.newChat(); vm.send("پول قهوه امروز پنجاه هزار تومان رو یادداشت کن") }
        compose.waitUntil(60_000) { !vm.busy && vm.pending != null }
        assertEquals(com.khodroyar.app.ai.albert.PersianAlbertEngine.Status.READY, vm.localModelStatus)
        assertEquals("add_expense", vm.pending!!.call.spec.name)
        assertEquals(50_000.0, vm.pending!!.call.dbl("amount")!!, 0.0)
        compose.onNodeWithTag("agent-confirm").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        // assertIsDisplayed alone accepts a partly clipped button. Check the
        // WHOLE action, without test-assisted scrolling, at 320 dp / 1.2x text.
        val viewport = compose.onNodeWithTag("agent-message-list").getUnclippedBoundsInRoot()
        for (tag in listOf("agent-confirm", "agent-cancel")) {
            val action = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag must be fully above the input bar", action.top >= viewport.top && action.bottom <= viewport.bottom + 1.dp)
        }
        assertEquals(before, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
        shot("37-offline-albert-approval")
        compose.onNodeWithTag("agent-cancel").performClick()
        compose.waitUntil(10_000) { vm.pending == null }
        compose.runOnIdle { vm.onWorkspaceHidden() }
        compose.waitUntil(10_000) { vm.localModelStatus == com.khodroyar.app.ai.albert.PersianAlbertEngine.Status.NOT_LOADED }
        assertEquals(before, runBlocking { AppDatabase.get(context).personalExpenses().allOnce().size })
    }

    @Test fun reportedNightServiceCorrectionRebuildsTheCardAndOnlyApprovalWritesTheReviewedType() {
        saveAgentServicePreset()
        lateinit var vm: AiAssistantViewModel
        render {
            val current: AiAssistantViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            SideEffect { vm = current }
            Box(Modifier.width(320.dp).fillMaxHeight().testTag("offline-agent-workspace")) { AiAssistantScreen(current) }
        }
        compose.waitUntil(20_000) { vm.connection == ConnectionState.LOCAL }
        assertEquals(com.khodroyar.app.ai.albert.PersianAlbertEngine.Status.NOT_LOADED, vm.localModelStatus)
        assertTrue(vm.activeModel.contains("هنوز بارگذاری نشده"))
        val before = runBlocking { AppDatabase.get(context).services().allOnce() }
        compose.runOnIdle { vm.send("امشب ی سرویس رفتم ثبت کن") }
        compose.waitUntil(60_000) { !vm.busy && vm.pending != null }
        val initial = vm.pending!!.call
        assertEquals("request", AutomationCodec.templateFromJson(org.json.JSONObject(initial.str("service_snapshot")!!)).type)
        compose.runOnIdle { vm.send("ن سرویس شب بود") }
        compose.waitUntil(20_000) { !vm.busy && vm.pending?.call?.str("service_type") == "night" }
        val revised = vm.pending!!.call
        assertEquals(com.khodroyar.app.ai.albert.PersianAlbertEngine.Status.READY, vm.localModelStatus)
        assertTrue(vm.activeModel.contains("بارگذاری‌شده"))
        assertNotEquals(initial.str("operation_id"), revised.str("operation_id"))
        assertEquals(Jalali.todayString(), revised.str("date"))
        val snapshot = AutomationCodec.templateFromJson(org.json.JSONObject(revised.str("service_snapshot")!!))
        assertEquals("night", snapshot.type); assertEquals(80.0, snapshot.km, 0.0)
        assertEquals("request", repository.settingsNow.autoServiceType)
        assertEquals(before.size, runBlocking { AppDatabase.get(context).services().allOnce().size })
        compose.onNodeWithTag("agent-confirm").performScrollTo().assertIsDisplayed()
        shot("38-night-service-correction")
        compose.onNodeWithTag("agent-confirm").performClick()
        compose.waitUntil(20_000) { !vm.busy && vm.pending == null }
        val after = runBlocking { AppDatabase.get(context).services().allOnce() }
        assertEquals(before.size + 1, after.size)
        val saved = after.single { row -> before.none { it.id == row.id } }
        assertEquals("night", saved.type); assertEquals(revised.str("date"), saved.date)
        assertEquals(snapshot.km, saved.km, 0.0); assertEquals(snapshot.hours, saved.hours, 0.0)
        assertEquals(snapshot.origin, saved.origin); assertEquals(snapshot.destination, saved.destination)
        assertEquals(80.0, repository.settingsNow.vehicleCurrentKm, 0.0)
        assertEquals("request", repository.settingsNow.autoServiceType)
    }

    @Test fun missingServicePresetKeepsTheNightCorrectionAndAsksForRealFieldsWithoutWriting() = runBlocking {
        val assistant = LocalAssistant(AppAgentTools(context, repository))
        val before = AppDatabase.get(context).services().allOnce().size
        assertTrue(assistant.run("امشب ی سرویس رفتم ثبت کن") is AgentStep.Reply)
        val correction = assistant.run("ن سرویس شب بود") as AgentStep.Reply
        assertTrue(correction.text.contains("سرویس شب")); assertTrue(correction.text.contains("کیلومتر"))
        assertFalse(correction.text.contains("ربط ندادم"))
        assertEquals(before, AppDatabase.get(context).services().allOnce().size)
        saveAgentServicePreset()
        val review = assistant.run("الگو را ذخیره کردم") as AgentStep.Confirm
        assertEquals("night", AutomationCodec.templateFromJson(org.json.JSONObject(review.pending.call.str("service_snapshot")!!)).type)
        assertEquals(before, AppDatabase.get(context).services().allOnce().size)
    }

    private fun saveAgentServicePreset() {
        repository.updateSettings { it.copy(autoServicePresetSaved = true, autoServiceType = "request", autoServiceCarType = "soren",
            autoServiceOrigin = "مبارکه", autoServiceDestination = "اصفهان", autoServiceKm = 80.0, autoServiceHours = 2.0,
            autoServiceStartTime = "20:00", autoServiceEndTime = "22:00", autoServiceTollCount = 1) }
    }

    @Test fun newerBazaarVersionHasDismissibleCompactRtlWarningAndManualRecheck() {
        context.getSharedPreferences("bazaar_update_notice", 0).edit().clear().commit()
        var checks = 0
        val updates = com.khodroyar.app.updates.BazaarUpdatesViewModel(context.applicationContext as android.app.Application,
            com.khodroyar.app.updates.BazaarVersionSource { checks++; com.khodroyar.app.updates.BazaarVersionResult.Reported(com.khodroyar.app.BuildConfig.VERSION_CODE + 1L) })
        render(fontScale = 1.35f) {
            Column(Modifier.width(320.dp).padding(12.dp)) {
                BazaarUpdateHost(updates)
                BazaarUpdateSettingsCard(updates)
            }
        }
        compose.waitUntil(10_000) { updates.state.value.showPrompt }
        compose.onNodeWithTag("bazaar-update-open").assertIsDisplayed()
        compose.onNodeWithTag("bazaar-update-later").assertIsDisplayed()
        shot("34-bazaar-update-rtl-320")
        compose.onNodeWithTag("bazaar-update-later").performClick()
        compose.onNodeWithTag("bazaar-update-dialog").assertDoesNotExist()
        compose.runOnIdle { updates.onForeground() }
        assertEquals(1, checks)
        shot("35-bazaar-update-settings")
        compose.onNodeWithTag("bazaar-update-check").performClick()
        compose.waitUntil(10_000) { updates.state.value.showPrompt }
        assertEquals(2, checks)
        compose.onNodeWithTag("bazaar-update-later").performClick()
        context.getSharedPreferences("bazaar_update_notice", 0).edit().clear().commit()
    }

    @Test fun missingBazaarOrUnavailableVersionNeverDisplaysAFakeUpdateWarning() {
        val updates = com.khodroyar.app.updates.BazaarUpdatesViewModel(context.applicationContext as android.app.Application,
            com.khodroyar.app.updates.BazaarVersionSource { com.khodroyar.app.updates.BazaarVersionResult.Unavailable(com.khodroyar.app.updates.BazaarVersionResult.Reason.NOT_INSTALLED) })
        render { Column(Modifier.padding(16.dp)) { BazaarUpdateHost(updates); BazaarUpdateSettingsCard(updates) } }
        compose.waitUntil(10_000) { updates.state.value.outcome == com.khodroyar.app.updates.BazaarUpdatesViewModel.Outcome.BAZAAR_MISSING }
        compose.onNodeWithTag("bazaar-update-dialog").assertDoesNotExist()
        compose.onNodeWithText("برای بررسی خودکار، برنامهٔ کافه‌بازار باید نصب باشد.").assertIsDisplayed()
        shot("36-bazaar-not-installed")
        val actual = runBlocking { com.khodroyar.app.updates.BazaarVersionService(context).check() }
        assertEquals(com.khodroyar.app.updates.BazaarVersionResult.Unavailable(com.khodroyar.app.updates.BazaarVersionResult.Reason.NOT_INSTALLED), actual)
    }

    @Test fun bazaarBrowserFallbackTargetsOnlyTheExistingAppPage() {
        val attempts = mutableListOf<android.content.Intent>()
        assertTrue(com.khodroyar.app.updates.BazaarListing.open { intent ->
            attempts += intent
            if (attempts.size == 1) throw android.content.ActivityNotFoundException("Bazaar absent")
        })
        assertEquals("com.farsitel.bazaar", attempts[0].`package`)
        assertEquals("bazaar://details?id=com.khodroyar.app", attempts[0].data.toString())
        assertNull(attempts[1].`package`)
        assertEquals("https://cafebazaar.ir/app/com.khodroyar.app", attempts[1].data.toString())
        assertFalse(com.khodroyar.app.updates.BazaarListing.open { throw android.content.ActivityNotFoundException("No browser") })
    }

    private fun imeDiagnostics(): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            .lineSequence().filter { line -> listOf("mInputShown", "mShowRequested", "mImeWindowVis", "mCurFocusedWindow", "mCurMethodId").any { key -> line.contains(key) } }
            .take(12).joinToString("; ") { it.trim().take(240) }
    }.getOrDefault("IME diagnostics unavailable")

    private fun pressSystemBack() {
        // Use instrumentation's complete system-key event, not a dispatcher shortcut
        // (which would bypass the input method) or an un-sourced synthetic key pair.
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private tailrec fun Context.hostActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.hostActivity()
        else -> null
    }

    private fun writeTextArtifact(name: String, text: String) {
        val resolver=context.contentResolver
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/json")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/KhodroYar-UI-Smoke");put(MediaStore.Downloads.IS_PENDING,1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(text.toByteArray()) }
        resolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
    }

    private fun shot(name: String) {
        // Synchronize the rendered Compose frame, not just updated semantics. A raw
        // UiAutomation screenshot can still contain the frame before a chart tap.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // ModalBottomSheet/Dialog adds another root; capture the topmost visible
        // surface rather than asking onRoot() to match exactly one window.
        val node = when {
            name == "32-automation-health" -> compose.onNodeWithTag("automation-sheet-content")
            name == "34-bazaar-update-rtl-320" -> compose.onNodeWithTag("bazaar-update-dialog")
            name in setOf("37-offline-albert-approval", "38-night-service-correction") -> compose.onNodeWithTag("offline-agent-workspace")
            name in setOf("19-home-dark-320-large-text", "23-report-table-320-large-text", "24-report-table-scrolled-amount", "25-quick-entry-320-large-text", "26-quick-entry-review", "27-recent-entry-edit-undo") -> compose.onNodeWithTag("compact-app")
            else -> compose.onAllNodes(isRoot()).onLast()
        }
        // On IME failure include the real system keyboard/window, which a
        // Compose-only capture deliberately excludes. Normal previews stay Compose.
        val bitmap = if (name == "debug-quick-edit-state") {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: node.captureToImage().asAndroidBitmap()
        } else node.captureToImage().asAndroidBitmap()
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
