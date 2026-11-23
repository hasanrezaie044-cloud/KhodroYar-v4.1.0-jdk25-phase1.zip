package com.khodroyar.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.data.repo.QuickEntries
import com.khodroyar.app.ui.components.LocalLanguage
import com.khodroyar.app.ui.components.PersistedUiState
import com.khodroyar.app.ui.screens.*
import com.khodroyar.app.ui.theme.AppPalette
import com.khodroyar.app.ui.theme.CarManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * KhodroYar 5.9.1 emulator tests (synthetic data in a disposable emulator only):
 * finance header scrolling, collapsible periodic maintenance, whole-card insurance navigation,
 * the calendar's square service grid and the per-service delivery status (UI, Room, backup),
 * plus the API 36 / targetSdk 36 runtime check.
 */
class Release591UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private lateinit var repository: AppRepository
    private val today = Jalali.todayString()

    @Before fun fixture() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "A disposable emulator is required: these UI fixtures replace the test app's data."
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        QuickEntries.invalidate()
        SettingsStore.get(context).acknowledgeQuickMileageNotice()
        repository = AppRepository(context)
        repository.updateSettings { AppSettings(userFirstName = "آرمان", userLastName = "آزمایشی", remindInstallments = false,
            remindOilChange = false, remindBodyInsurance = false, remindVehicleInsurance = false, remindInspection = false,
            remindBackup = false, autoBackupEnabled = false) }
        context.getSharedPreferences("car_manager_onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PersistedUiState.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking {
            withContext(Dispatchers.IO) { db.clearAllTables() }
            // Two services on the SAME date: their delivery status must stay independent.
            db.services().insert(service("cal-a", "08:00", "10:00", "مبارکه", "اصفهان", 2_750_000L))
            db.services().insert(service("cal-b", "13:00", "15:30", "اصفهان", "فرودگاه", 3_100_000L))
            // A long month of purchases so the purchases booklet really scrolls.
            (1..28).forEach { i ->
                db.personalExpenses().insert(PersonalExpenseEntity("p-$i", today, "food", "خرید آزمایشی $i", 100_000.0 + i, "2026-01-01T00:00:00Z"))
            }
            db.insurance().insert(InsuranceEntity(id = "ins-valid", type = "بیمه شخص ثالث", expiryDate = Jalali.addDays(today, 200),
                cost = 9_500_000.0, company = "بیمه آزمایشی", policyNumber = "TP-1405", reminder = true, timestamp = "t"))
            db.insurance().insert(InsuranceEntity(id = "ins-expired", type = "معاینه فنی", expiryDate = Jalali.addDays(today, -12),
                cost = 0.0, company = "", policyNumber = "", reminder = true, timestamp = "t"))
        }
    }

    private fun service(id: String, start: String, end: String, origin: String, destination: String, income: Long) =
        ServiceEntity(id, today, "night", "soren", 80.0, 2.0, 2.0, start, end, origin, destination, "", "", 1,
            0.0, 0.0, 0.0, income, "2026-01-01T00:00:00Z")

    private fun render(widthDp: Int? = null, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides LayoutDirection.Rtl, LocalLanguage provides "fa") {
                CarManagerTheme(darkTheme = false, palette = AppPalette.ORBIT) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(if (widthDp == null) Modifier.fillMaxSize() else Modifier.width(widthDp.dp).fillMaxHeight()) { content() }
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String, unmerged: Boolean = false) =
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** True only if a node with [tag] is composed AND its bounds overlap the viewport of [container]. */
    private fun visibleInside(tag: String, container: String): Boolean {
        val viewport = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
            val b: Rect = node.boundsInRoot
            b.height > 0f && b.bottom > viewport.top + 1f && b.top < viewport.bottom - 1f
        }
    }

    private fun displayedTextCount(text: String, container: String): Int {
        val viewport = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodesWithText(text).fetchSemanticsNodes().count { node ->
            val b = node.boundsInRoot
            b.height > 0f && b.bottom > viewport.top + 1f && b.top < viewport.bottom - 1f
        }
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        bitmap.recycle()
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "$name.png", "image/png", bytes)
    }

    private fun pressSystemBack() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ finance scroll

    private fun purchasesHeaderScrollsWithTheList(shotName: String?) {
        compose.onNodeWithText("خرید شخصی").performClick()
        waitForTag("purchases-list")
        val month = Jalali.parse(today)!!.month
        waitForTag("purchases-month-$month")
        assertTrue("header starts on screen", visibleInside("purchases-header", "purchases-list"))
        assertEquals("exactly one purchases title", 1, displayedTextCount("خریدهای شخصی", "purchases-list"))
        compose.onNodeWithTag("purchases-month-$month").performClick()
        waitForTag("purchase-row-p-1")
        // Scroll DOWN with real swipes: the year row and the title must leave the screen.
        repeat(8) { compose.onNodeWithTag("purchases-list").performTouchInput { swipeUp() } }
        compose.waitForIdle()
        assertFalse("title must not stay frozen", visibleInside("purchases-header", "purchases-list"))
        assertFalse("year row must not stay frozen", visibleInside("purchases-year-row", "purchases-list"))
        assertEquals("no duplicated/overlaid title", 0, displayedTextCount("خریدهای شخصی", "purchases-list"))
        if (shotName != null) shot("$shotName-scrolled")
        // Scroll UP again: the header comes back exactly once, above the booklet.
        repeat(12) { compose.onNodeWithTag("purchases-list").performTouchInput { swipeDown() } }
        compose.waitForIdle()
        assertTrue("header returns at the top", visibleInside("purchases-header", "purchases-list"))
        assertEquals(1, displayedTextCount("خریدهای شخصی", "purchases-list"))
        val header = compose.onNodeWithTag("purchases-header").fetchSemanticsNode().boundsInRoot
        val summary = compose.onNodeWithTag("monthbook-summary").fetchSemanticsNode().boundsInRoot
        assertTrue("header sits above, not over, the month content", header.bottom <= summary.top + 1f)
        if (shotName != null) shot("$shotName-top")
        // Data and totals are untouched: all 28 rows of this month are still listed.
        assertEquals(28, runBlocking { db.personalExpenses().allOnce().size })
    }

    @Test fun financePurchasesHeaderScrollsAwayAndBackOnAPhone() {
        render { FinanceScreen(repository) }
        purchasesHeaderScrollsWithTheList("591-finance-purchases")
    }

    @Test fun financePurchasesHeaderScrollsAt320DpWithLargeText() {
        render(widthDp = 320, fontScale = 1.3f) { FinanceScreen(repository) }
        purchasesHeaderScrollsWithTheList(null)
    }

    // ------------------------------------------------------------ maintenance section

    @Test fun maintenancePeriodicSectionIsCollapsibleAndRemembersTheUsersChoice() {
        var shown by mutableStateOf(true)
        render { if (shown) MaintenanceScreen(repository) else Text("away") }
        waitForTag("maintenance-periodic-toggle")
        // Not auto-opened on entry.
        compose.onAllNodesWithTag("maintenance-periodic-content").assertCountEquals(0)
        compose.onNodeWithTag("maintenance-periodic-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "بسته"))
            .assertHasClickAction()
        val toggleBounds = compose.onNodeWithTag("maintenance-periodic-toggle").fetchSemanticsNode().boundsInRoot
        assertTrue("48dp tap target", toggleBounds.height >= with(compose.density) { 48.dp.toPx() } - 1f)
        shot("591-maintenance-periodic-collapsed")
        compose.onNodeWithTag("maintenance-periodic-toggle").performClick()
        waitForTag("maintenance-periodic-content")
        waitForTag("maintenance-oil-change")
        compose.onNodeWithTag("maintenance-periodic-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "باز"))
        assertTrue(PersistedUiState.read(context, PersistedUiState.MAINTENANCE_PERIODIC_EXPANDED, false))
        // Leave the screen and come back: the open state is remembered.
        compose.runOnIdle { shown = false }
        compose.onNodeWithText("away").assertExists()
        compose.runOnIdle { shown = true }
        waitForTag("maintenance-periodic-content")
        // Collapse, leave, return: stays collapsed.
        compose.onNodeWithTag("maintenance-periodic-toggle").performClick()
        compose.onAllNodesWithTag("maintenance-periodic-content").assertCountEquals(0)
        compose.runOnIdle { shown = false }
        compose.runOnIdle { shown = true }
        waitForTag("maintenance-periodic-toggle")
        compose.onAllNodesWithTag("maintenance-periodic-content").assertCountEquals(0)
        assertFalse(PersistedUiState.read(context, PersistedUiState.MAINTENANCE_PERIODIC_EXPANDED, true))
    }

    // ------------------------------------------------------------ green notebooks

    @Test fun fuelAndMaintenanceBooksUseTheGreenNotebookCover() {
        runBlocking { db.fuels().insert(FuelEntity("fuel-1", today, "gov", 40.0, 600_000.0, "soren", "t")) }
        var screen by mutableStateOf("fuel")
        render { if (screen == "fuel") FuelScreen(repository) else MaintenanceScreen(repository) }
        waitForTag("fuel-year-cover")
        val month = Jalali.parse(today)!!.month
        waitForTag("fuel-month-$month")
        compose.onNodeWithTag("fuel-month-$month").assertHasClickAction()
        compose.onNodeWithTag("fuel-year-cover").assertExists()
        shot("591-fuel-green-notebook")
        compose.runOnIdle { screen = "maintenance" }
        waitForTag("maintenance-list")
        compose.onNodeWithTag("maintenance-list").performScrollToNode(hasTestTag("maintenance-year-cover"))
        compose.onNodeWithTag("maintenance-year-cover").assertIsDisplayed()
        shot("591-maintenance-green-notebook")
    }

    // --------------------------------------------------------------- insurance cards

    @Test fun insuranceWholeCardOpensItsRealDetailsWithoutADetailsButton() {
        render { InsuranceScreen(repository) }
        waitForTag("insurance-booklet-ins-valid")
        waitForTag("insurance-booklet-ins-expired")
        // No separate "details" button or label on any card.
        assertTrue(compose.onAllNodes(hasText("جزئیات", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("insurance-status-ins-expired", useUnmergedTree = true).assertTextContains("منقضی", substring = true)
        shot("591-insurance-green-booklets")
        // Tap the card body (not a button): the existing details page opens with the real record.
        compose.onNodeWithTag("insurance-booklet-ins-expired").assertHasClickAction().performTouchInput { click(center) }
        waitForTag("record-details")
        compose.onNodeWithTag("record-details-title").assertTextEquals("معاینه فنی")
        compose.onNodeWithTag("record-details-status").assertTextContains("منقضی", substring = true)
        compose.onNodeWithTag("record-details-edit").assertIsEnabled()
        compose.onNodeWithTag("record-details-delete").assertExists()
        pressSystemBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("record-details").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("insurance-booklet-ins-valid").performTouchInput { click(center) }
        waitForTag("record-details")
        compose.onNodeWithTag("record-details-title").assertTextEquals("بیمه شخص ثالث")
    }

    // ------------------------------------------------- calendar grid + delivery status

    private fun card(id: String) = compose.onNodeWithTag("calendar-service-$id")
    private fun deliveredState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun openCalendarGrid() {
        waitForTag("calendar-list")
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-service-grid"))
        waitForTag("calendar-service-cal-a")
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-service-cal-b"))
    }

    @Test fun calendarSquareGridOpensTheExactServiceAndDeliveryTogglesPerRecord() {
        render { CalendarScreen(repository, {}, {}, {}, {}, {}) }
        openCalendarGrid()
        val a = card("cal-a").fetchSemanticsNode().boundsInRoot
        val b = card("cal-b").fetchSemanticsNode().boundsInRoot
        assertEquals("both shortcuts share one grid row", a.top, b.top, 1f)
        assertTrue("square-or-taller card (no clipping)", a.height >= a.width - 1f)
        assertTrue("compact: at least two columns", a.width * 2 <= compose.onNodeWithTag("calendar-service-grid").fetchSemanticsNode().boundsInRoot.width + 1f)
        card("cal-a").assert(deliveredState("تحویل نشده")).assertHasClickAction()
        card("cal-b").assert(deliveredState("تحویل نشده"))
        compose.onAllNodesWithTag("calendar-service-delivered-cal-a", useUnmergedTree = true).assertCountEquals(0)

        // Mark: details of exactly cal-a, button "تحویل" -> "تحویل شد".
        card("cal-a").performClick()
        waitForTag("record-details")
        compose.onNodeWithTag("record-details-amount").assertTextContains("2,750,000", substring = true)
        compose.onNodeWithTag("record-details-deliver").assertTextEquals("تحویل").performClick()
        compose.waitUntil(15_000) { runBlocking { db.services().byId("cal-a")!!.delivered } }
        waitForText("تحویل شد")
        compose.onNodeWithTag("record-details-deliver").assertTextEquals("تحویل شد")
            .assert(deliveredState("تحویل شده"))
        shot("591-service-delivery-details")
        assertFalse("same-date service untouched", runBlocking { db.services().byId("cal-b")!!.delivered })

        // Back in the calendar: persistent green accent on cal-a only.
        pressSystemBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("record-details").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-service-cal-b"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("calendar-service-delivered-cal-a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        card("cal-a").assert(deliveredState("تحویل شده"))
        card("cal-b").assert(deliveredState("تحویل نشده"))
        compose.onAllNodesWithTag("calendar-service-delivered-cal-b", useUnmergedTree = true).assertCountEquals(0)
        shot("591-calendar-service-grid-delivered")

        // Reopen: still delivered (read from Room by primary key); tap again reverses it.
        card("cal-a").performClick()
        waitForTag("record-details")
        compose.onNodeWithTag("record-details-deliver").assertTextEquals("تحویل شد").performClick()
        compose.waitUntil(15_000) { runBlocking { !db.services().byId("cal-a")!!.delivered } }
        compose.onNodeWithTag("record-details-deliver").assertTextEquals("تحویل")
        pressSystemBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("record-details").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-service-cal-b"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("calendar-service-delivered-cal-a", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        card("cal-a").assert(deliveredState("تحویل نشده"))
    }

    @Test fun deliveryIsImmediateAcrossScreensAndEditsNeverResetIt() {
        render { CalendarScreen(repository, {}, {}, {}, {}, {}) }
        openCalendarGrid()
        // A write from elsewhere (another screen/repository call) shows up without reopening.
        assertTrue(runBlocking { repository.setServiceDelivered("cal-b", true) })
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("calendar-service-delivered-cal-b", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        card("cal-b").assert(deliveredState("تحویل شده"))
        // The service edit form saves a row built without the field: delivery must survive.
        runBlocking {
            val row = db.services().byId("cal-b")!!
            repository.updateService(row.copy(destination = "ترمینال", delivered = false))
            assertTrue(db.services().byId("cal-b")!!.delivered)
            assertEquals("ترمینال", db.services().byId("cal-b")!!.destination)
            assertFalse(db.services().byId("cal-a")!!.delivered)
            assertFalse("unknown id changes nothing", repository.setServiceDelivered("missing-id", true))
        }
    }

    // ------------------------------------------------ persistence, DB reload and backup

    @Test fun deliveryPersistsInTheDatabaseFileAndThroughBackupRestore() = runBlocking {
        assertTrue(repository.setServiceDelivered("cal-a", true))
        // An independent Room connection on the same database file sees the stored value.
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, "car_management.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
        try {
            assertTrue(reopened.services().byId("cal-a")!!.delivered)
            assertFalse(reopened.services().byId("cal-b")!!.delivered)
        } finally { reopened.close() }

        // Current backup format: per-record status round-trips.
        val backup = repository.exportBackupJson()
        val services = JSONObject(backup).getJSONArray("services")
        val exported = (0 until services.length()).map { services.getJSONObject(it) }.associateBy { it.getString("id") }
        assertTrue(exported.getValue("cal-a").getBoolean("delivered"))
        assertFalse(exported.getValue("cal-b").getBoolean("delivered"))
        withContext(Dispatchers.IO) { db.clearAllTables() }
        val restored = repository.restoreBackup(backup)
        assertTrue(restored.exceptionOrNull()?.message ?: "restore failed", restored.isSuccess)
        assertTrue(db.services().byId("cal-a")!!.delivered)
        assertFalse(db.services().byId("cal-b")!!.delivered)
        assertEquals(28, db.personalExpenses().allOnce().size)

        // A backup written before 5.9.1 (no "delivered" key, legacy JSON without manifest) still restores.
        val old = JSONObject(backup).apply {
            remove("backupManifest"); remove("attachmentData")
            val rows = getJSONArray("services")
            for (i in 0 until rows.length()) rows.getJSONObject(i).remove("delivered")
        }.toString()
        assertFalse(old.contains("\"delivered\""))
        val preview = repository.previewBackup(old)
        assertTrue(preview.error ?: "", preview.valid)
        val legacy = repository.restoreBackup(old)
        assertTrue(legacy.exceptionOrNull()?.message ?: "legacy restore failed", legacy.isSuccess)
        assertEquals(setOf("cal-a", "cal-b"), db.services().allOnce().map { it.id }.toSet())
        assertTrue(db.services().allOnce().none { it.delivered })
    }

    // ---------------------------------------------------------------------- API 36

    @Test fun runsOnApi36WithTargetSdk36() {
        assertTrue("emulator must be API 36+, was ${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT >= 36)
        val target = context.applicationInfo.targetSdkVersion
        assertEquals(36, target)
        val evidence = JSONObject().put("sdk_int", Build.VERSION.SDK_INT).put("target_sdk", target)
            .put("release", Build.VERSION.RELEASE).put("passed", true)
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "api36.json", "application/json", evidence.toString().toByteArray())
    }
}
