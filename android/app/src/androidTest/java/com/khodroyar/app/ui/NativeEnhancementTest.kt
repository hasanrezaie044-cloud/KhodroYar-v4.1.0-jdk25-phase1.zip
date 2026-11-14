package com.khodroyar.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.khodroyar.app.R
import com.khodroyar.app.ai.*
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.*
import com.khodroyar.app.ui.screens.WorkReportScreen
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.workreport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Disposable-emulator tests only. Never run these fixture resets on a user's phone. */
@RunWith(AndroidJUnit4::class)
class NativeEnhancementTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private lateinit var repo: AppRepository
    private val settings get() = SettingsStore.get(context)

    @Before fun reset() = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        WorkManager.getInstance(context).cancelAllWork().result.get()
        repo = AppRepository(context)
        repo.quickEntries.recover()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        settings.update { AppSettings(vehicleCurrentKm = 1000.0, autoServicePresetSaved = true, autoServiceType = "night", autoServiceCarType = "soren",
            autoServiceOrigin = "مبارکه", autoServiceDestination = "اصفهان", autoServiceKm = 60.0, autoServiceHours = 3.0,
            remindOilChange = false, remindBackup = false, remindInstallments = false, remindInspection = false, remindBodyInsurance = false, remindVehicleInsurance = false) }
        QuickEntries.invalidate()
    }
    private fun request(name: String = "bulk_register_services", km: Double? = null, hours: Double? = null): ValidatedCall {
        val args = JSONObject().put("start_date", "14030101").put("end_date", "14030103").put("service_type", "night")
        if (name == "bulk_register_services") args.put("per_day", 1).put("days", "all")
        km?.let { args.put("km", it) }; hours?.let { args.put("hours", it) }
        return (AgentActionValidator.validate(name, args) as ValidationResult.Ok).call
    }
    @Test fun rangeCommandNeedsApprovalIsAtomicAndDoesNotDuplicateOnRetry() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val local = LocalAssistant(tools, dialogue = LocalDialogueEngine(today = { "1405/08/20" }))
        assertTrue(local.run("سرویس شب از ۱۴۰۳۰۱۰۱ تا ۱۴۰۳۰۱۰۳ ثبت کن") is AgentStep.Reply)
        val step = local.run("۱") as AgentStep.Confirm
        assertEquals(0, db.services().allOnce().size)
        assertTrue(step.pending.summary.contains("1403/01/02"))
        assertTrue(tools.execute(step.pending.call).ok)
        assertEquals(3, db.services().allOnce().size); assertEquals(1180.0, settings.current.vehicleCurrentKm, .001)
        assertTrue(tools.execute(step.pending.call).ok)
        assertEquals(3, db.services().allOnce().size); assertEquals(1180.0, settings.current.vehicleCurrentKm, .001)
        val again = AgentApprovalGate(tools).prepare(request())
        assertTrue(again is AgentStep.Reply)
        assertEquals(3, db.services().dailyInRangeOnce("1403/01/01", "1403/01/03").sumOf { it.count })
    }
    @Test fun staleRecordOrChangedPresetRefusesUnapprovedReplacement() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val pending = (AgentApprovalGate(tools).prepare(request()) as AgentStep.Confirm).pending
        db.services().insert(service("other", "1403/01/02", 25.0, 1.0))
        val before = db.services().allOnce()
        assertFalse(tools.execute(pending.call).ok); assertEquals(before, db.services().allOnce())
        val next = (AgentApprovalGate(tools).prepare(request()) as AgentStep.Confirm).pending
        settings.update { it.copy(autoServiceKm = 90.0) }
        assertFalse(tools.execute(next.call).ok); assertEquals(before, db.services().allOnce())
    }
    @Test fun editAndDeleteKeepPreviewSeparateRepriceAndVerifyEveryRow() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val add = (AgentApprovalGate(tools).prepare(request()) as AgentStep.Confirm).pending
        assertTrue(tools.execute(add.call).ok)
        val edit = (AgentApprovalGate(tools).prepare(request("bulk_edit_services", 80.0, 4.0)) as AgentStep.Confirm).pending
        assertTrue(db.services().allOnce().all { it.km == 60.0 })
        assertTrue(tools.execute(edit.call).ok)
        assertTrue(db.services().allOnce().all { it.km == 80.0 && it.hours == 4.0 && it.workHours == 4.0 })
        assertEquals(1240.0, settings.current.vehicleCurrentKm, .001)
        val delete = (AgentApprovalGate(tools).prepare(request("bulk_delete_services")) as AgentStep.Confirm).pending
        assertTrue(delete.destructive); assertNotNull(delete.target); assertEquals(3, db.services().allOnce().size)
        assertTrue(tools.execute(delete.call).ok); assertTrue(db.services().allOnce().isEmpty())
        assertEquals(1000.0, settings.current.vehicleCurrentKm, .001)
    }
    @Test fun financialRecordEditingNeedsAnOwnedSnapshotAndRejectsConcurrentChanges() = runBlocking {
        val original = PersonalExpenseEntity("guarded-expense", "1403/01/01", "coffee", "قهوه", 50000.0, "2026-01-01T00:00:00Z")
        db.personalExpenses().insert(original)
        val tools = AppAgentTools(context, repo)
        val requested = (AgentActionValidator.validate("update_expense", JSONObject().put("id", "guarded").put("amount", 60000)) as ValidationResult.Ok).call
        val pending = (AgentApprovalGate(tools).prepare(requested) as AgentStep.Confirm).pending
        assertEquals(50000.0, db.insights().expense(original.id)!!.amount, 0.0)
        db.personalExpenses().insert(original.copy(amount = 55000.0))
        assertFalse(tools.execute(pending.call).ok)
        assertEquals(55000.0, db.insights().expense(original.id)!!.amount, 0.0)
        val fresh = (AgentApprovalGate(tools).prepare(requested) as AgentStep.Confirm).pending
        assertTrue(tools.execute(fresh.call).ok)
        assertEquals(60000.0, db.insights().expense(original.id)!!.amount, 0.0)
        assertEquals(1, db.personalExpenses().allOnce().size)
    }

    @Test fun committedBatchMileageJournalRecoversOnceAfterSimulatedProcessDeath() = runBlocking {
        val plan = repo.serviceBatches.prepare("bulk_register_services", "1403/01/01", "1403/01/03", "night", 1, false, null, null, null)
        val journal = requireNotNull(ServiceBatchJournal.from(plan))
        settings.stageQuickMileage(journal.encode()) { true }
        db.services().insertAll(plan.created)
        assertEquals(1000.0, settings.current.vehicleCurrentKm, .001)
        repo.quickEntries.recover(); repo.quickEntries.recover()
        assertEquals(1180.0, settings.current.vehicleCurrentKm, .001); assertTrue(settings.pendingQuickMileage.isNullOrBlank())
    }
    @Test fun rolledBackBatchAndManualOdometerChangesAreNotOverwritten() = runBlocking {
        val plan = repo.serviceBatches.prepare("bulk_register_services", "1403/01/01", "1403/01/03", "night", 1, false, null, null, null)
        val journal = requireNotNull(ServiceBatchJournal.from(plan))
        settings.stageQuickMileage(journal.encode()) { true }; repo.quickEntries.recover()
        assertEquals(1000.0, settings.current.vehicleCurrentKm, .001)
        settings.stageQuickMileage(journal.encode()) { true }; db.services().insertAll(plan.created)
        settings.update { it.copy(vehicleCurrentKm = 5000.0) }; repo.quickEntries.recover()
        assertEquals(5000.0, settings.current.vehicleCurrentKm, .001); assertNotNull(settings.quickMileageNotice.value)
        settings.acknowledgeQuickMileageNotice()
    }
    @Test fun batchUsesOwnYearPricesAndRejectsAStaleRateSnapshot() = runBlocking {
        val old = com.khodroyar.app.core.rates.Rates(nightKm = 1000.0, hour = 2000.0)
        val next = old.copy(nightKm = 3000.0, hour = 4000.0)
        repo.saveRatesForYear(1402, old); repo.saveRatesForYear(1403, next)
        val plan = repo.serviceBatches.prepare("bulk_register_services", "1402/12/29", "1403/01/01", "night", 1, false, null, null, null)
        assertEquals(listOf(66000L, 192000L), plan.created.map { it.income })
        repo.saveRatesForYear(1403, next.copy(hour = 5000.0))
        assertTrue(runCatching { repo.serviceBatches.execute(plan) }.isFailure)
        assertTrue(db.services().allOnce().isEmpty()); assertTrue(settings.pendingQuickMileage.isNullOrBlank())
        assertEquals(1000.0, settings.current.vehicleCurrentKm, .001)
    }
    @Test fun workdaysSkipOfficialManualAndWeekendDatesAndReadsNeverPrepareWrites() = runBlocking {
        val all = requireNotNull(com.khodroyar.app.core.text.PersianInput.days("1403/02/01", "1403/02/10"))
        val manual = all.first { !com.khodroyar.app.core.holidays.WorkCalendar.isNonWorkingDay(it, emptySet()) }
        repo.addHoliday(manual, "تعطیل آزمایشی")
        val plan = repo.serviceBatches.prepare("bulk_register_services", all.first(), all.last(), "night", 1, true, null, null, null)
        val expected = all.filterNot { com.khodroyar.app.core.holidays.WorkCalendar.isNonWorkingDay(it, setOf(manual)) }
        assertEquals(expected, plan.created.map { it.date }); assertEquals(all.size - expected.size, plan.skipped)
        val tools = AppAgentTools(context, repo)
        val read = request("search_services_range")
        assertSame(read, tools.prepare(read))
        assertTrue(tools.execute(read).ok); assertTrue(db.services().allOnce().isEmpty())
    }
    @Test fun rentalServiceRequiresRentalSettingsEvenAfterAnApprovalWasPrepared() = runBlocking {
        settings.update { it.copy(isRental = true) }
        val plan = repo.serviceBatches.prepare("bulk_register_services", "1403/01/01", "1403/01/02", "fixed-salary", 1, false, null, null, null)
        assertTrue(plan.summary().contains("حقوق ثابت")); assertEquals(plan.mileageBefore, plan.mileageAfter, 0.0)
        settings.update { it.copy(isRental = false) }
        assertTrue(runCatching { repo.serviceBatches.execute(plan) }.isFailure)
        assertTrue(db.services().allOnce().isEmpty())
    }

    private fun service(id: String, date: String, km: Double, hours: Double) = ServiceEntity(id, date, "night", "soren", km, hours, hours, "", "", "مبارکه", "اصفهان", "", "", 0, 0.0, 0.0, 0.0, 0L, "2026-01-01T00:00:00Z")

    @Test fun reviewDraftRestoresAnAtomicBackupAfterInterruptedReplacement() {
        val directory = File(context.cacheDir, "draft-recovery-synthetic").also { it.mkdirs() }
        try {
            val store = WorkReportDraft(directory)
            store.save(WorkReportDraft.Draft(emptyList(), emptyList(), emptyList(), "1403/01/01", "1403/01/02"))
            assertTrue(File(directory, "draft.json").renameTo(File(directory, "draft.json.bak")))
            val recovered = requireNotNull(store.load())
            assertEquals("1403/01/01", recovered.start); assertEquals("1403/01/02", recovered.end)
            assertTrue(File(directory, "draft.json").isFile)
        } finally { directory.deleteRecursively() }
    }
}
