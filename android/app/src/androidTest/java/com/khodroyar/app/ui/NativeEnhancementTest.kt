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

    /** Real bundled Tesseract, real SAF/FileProvider import, real multipage PdfRenderer. */
    @Test fun offlinePersianArabicEnglishOcrPdfReviewAndDailyComparisonEndToEnd() {
        val pdf = File(context.cacheDir, "exports/work-report-synthetic.pdf").also { it.parentFile!!.mkdirs() }
        val image = File(context.cacheDir, "exports/work-report-synthetic.png")
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        drawFixture(Canvas(bitmap))
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val document = PdfDocument()
        try {
            repeat(2) { n ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(2000, 1000, n + 1).create())
                drawFixture(page.canvas); document.finishPage(page)
            }
            pdf.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        val documents = ReportDocuments(context)
        val uris = listOf(pdf, image).map { FileProvider.getUriForFile(context, "com.khodroyar.app.fileprovider", it) }
        val imported = runBlocking { documents.import(uris, emptyList()) }
        assertEquals(3, imported.size); assertEquals(listOf(0, 1, -1), imported.map { it.pdfPage })
        val normal = documents.render(imported.first(), 100000, 400)
        val rotated = documents.render(imported.first().copy(rotation = 1), 100000, 400)
        assertEquals(normal.width, rotated.height); assertEquals(normal.height, rotated.width); normal.recycle(); rotated.recycle()

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Device recognition must work with all data radios disabled, not a mocked OCR provider.
        automation.executeShellCommand("svc wifi disable").close(); automation.executeShellCommand("svc data disable").close()
        try {
            val diagnostics = mutableListOf<String>()
            val recognized = runBlocking { OfflineReportOcr(context).recognize(imported.take(2), true, diagnostics = { diagnostics += it }) {} }
            val rows = WorkReportParser.extract(recognized)
            println("Synthetic offline OCR text: ${recognized.map { it.text }}")
            println("Synthetic OCR rows: $rows")
            println("Synthetic OCR words: ${recognized.map { it.words }}")
            println("Synthetic numeric regions: ${recognized.map { ReportTableGeometry.regions(it.words) }}")
            val evidence = JSONObject().put("environment", "synthetic fixture, API35 emulator, bundled native engine, radios disabled")
                .put("pages", org.json.JSONArray().apply { recognized.forEach { page -> put(JSONObject().put("text", page.text)
                    .put("words", org.json.JSONArray().apply { page.words.forEach { w -> put(JSONObject().put("text", w.text).put("x", w.x.toDouble()).put("y", w.y.toDouble()).put("confidence", w.confidence.toDouble())) } })) } })
                .put("rows", org.json.JSONArray().apply { rows.forEach { row -> put(JSONObject().put("date", row.date).put("count", row.count).put("km", row.km).put("hours", row.hours).put("included", row.included).put("duplicate", row.duplicateOf != null).put("raw", row.raw)) } })
            evidence.put("diagnostics", org.json.JSONArray(diagnostics))
            publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "offline-ocr.json", "application/json", evidence.toString(2).toByteArray())
            publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "fixture-native-ocr.png", "image/png", image.readBytes())
            val selected = rows.filter { it.included }
            assertEquals("Three data rows; second PDF page is a duplicate. Raw: $rows", 3, selected.size)
            assertEquals(listOf("1403/01/01", "1403/01/02", "1403/01/03"), selected.map { it.date }.sorted())
            assertEquals(listOf(2, 3, 1), selected.sortedBy { it.date }.map { requireNotNull(it.validated()) { it.toString() }.count })
            assertEquals(3, rows.count { it.duplicateOf != null })
            val totals = WorkTotals.of(selected.map { it.validated()!! })
            assertEquals(6, totals.count); assertEquals(250.5, totals.km.toDouble(), .000001); assertEquals(6.25, totals.hours.toDouble(), .000001)
            // Company/app grand totals agree but dates 1/2 have swapped service counts.
            runBlocking {
                repeat(3) { db.services().insert(service("ocr-day1-$it", "1403/01/01", 120.5 / 3, 3.5 / 3)) }
                repeat(2) { db.services().insert(service("ocr-day2-$it", "1403/01/02", 50.0, 1.125)) }
                db.services().insert(service("ocr-day3", "1403/01/03", 30.0, .5))
                WorkReportDraft(documents.directory).save(WorkReportDraft.Draft(imported.take(2), recognized, rows, "1403/01/01", "1403/01/03"))
            }
            val vm = WorkReportViewModel(context.applicationContext as Application)
            compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CarManagerTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { WorkReportScreen(vm) } }
            } }
            compose.waitUntil(30000) { !vm.busy && vm.rows.isNotEmpty() }
            compose.onNodeWithTag("report-compare").assertIsNotEnabled()
            val first = vm.rows.first { it.included }
            compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-cell-${first.id}-count"))
            compose.onNodeWithTag("report-cell-${first.id}-count").performScrollTo().performTextReplacement("?")
            compose.runOnIdle { assertNull(vm.rows.first { it.id == first.id }.validated()); vm.setReview(true) }
            compose.onNodeWithTag("report-compare").performClick()
            compose.waitUntil(15000) { !vm.busy && vm.error != null }
            assertNull(vm.comparison)
            compose.runOnIdle { vm.edit(vm.rows.first { it.id == first.id }.copy(count = first.count)); vm.clearError() }
            compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-review-confirm"))
            compose.onNodeWithTag("report-review-confirm").performClick()
            compose.onNodeWithTag("report-compare").performClick()
            compose.waitUntil(30000) { vm.comparison != null }
            val result = requireNotNull(vm.comparison)
            assertEquals(result.company.count, result.app.count)
            assertEquals(0.0, (result.company.km - result.app.km).toDouble(), .000001)
            assertEquals(2, result.days.count { it.status == DayStatus.DIFFERENT })
            compose.onNodeWithTag("report-comparison").assertIsDisplayed()
            assertEquals(6, runBlocking { db.services().allOnce().size }) // Comparison did not modify records.
        } finally {
            automation.executeShellCommand("svc wifi enable").close(); automation.executeShellCommand("svc data enable").close()
            pdf.delete(); image.delete()
        }
    }
    @Test fun cancellationAndConcurrentStopNeverUseARecycledNativeEngine() {
        runBlocking {
            val image = File(context.cacheDir, "exports/work-report-cancel-synthetic.png").also { it.parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
            drawFixture(Canvas(bitmap))
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val documents = ReportDocuments(context)
            val pages = documents.import(listOf(FileProvider.getUriForFile(context, "com.khodroyar.app.fileprovider", image)), emptyList())
            val engine = OfflineReportOcr(context)
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            val proceed = java.util.concurrent.CountDownLatch(1)
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(Dispatchers.IO) {
                engine.recognize(pages, true) {
                    if (it.startsWith("صفحهٔ")) {
                        ready.complete(Unit)
                        check(proceed.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }
            try {
                kotlinx.coroutines.withTimeout(30000) { ready.await() } // The real bundled JNI engine is initialised.
                val stopping = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(Dispatchers.Default) {
                    repeat(2000) { engine.stop(); kotlinx.coroutines.yield() }
                }
                job.cancel(); proceed.countDown(); job.join(); stopping.join()
                repeat(100) { engine.stop() }
                assertTrue(job.isCancelled)
            } finally {
                job.cancel(); proceed.countDown(); job.join(); engine.stop()
                pages.forEach { File(documents.directory, it.file).delete() }; image.delete()
            }
        }
    }

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

    @Test fun visualNumericReaderPreservesFiveEightZeroScriptsAndRejectsUnknownInk() {
        val scripts = listOf("۰۱۲۳۴۵۶۷۸۹", "٠١٢٣٤٥٦٧٨٩", "0123456789")
        val fonts = listOf(ResourcesCompat.getFont(context, R.font.vazirmatn_bold), android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL),
            android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL))
        val regions = listOf(NumericField.DATE, NumericField.COUNT, NumericField.KM, NumericField.HOURS).mapIndexed { n, field ->
            val x = .8f - n * .2f
            NumericRegion(field, x - .095f, .15f, x + .095f, .8f)
        }
        val reader = NumericGlyphOcr(context)
        for ((index, font) in fonts.withIndex()) {
            val bitmap = Bitmap.createBitmap(2400, 360, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 64f; color = Color.BLACK }
                val digits = scripts[index]
                fun script(text: String) = text.map { if (it in '0'..'9') digits[it - '0'] else if (it == '.' && index < 2) '٫' else it }.joinToString("")
                fun cell(text: String, x: Float) {
                    val value = script(text)
                    val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, 440).setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setTextDirection(TextDirectionHeuristics.LTR).setIncludePad(false).build()
                    canvas.save(); canvas.translate(x - 220f, 100f); layout.draw(canvas); canvas.restore()
                }
                val expected = listOf("1403/11/27", "8", "580.09", "5:45")
                expected.forEachIndexed { n, value -> cell(value, 1920f - n * 480) }
                val messages = mutableListOf<String>()
                val values = reader.readRow(bitmap, regions, diagnostics = { messages += it })
                val evidence = JSONObject().put("font", index).put("readings", values.toString()).put("diagnostics", org.json.JSONArray(messages))
                publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "numeric-font-$index.json", "application/json", evidence.toString().toByteArray())
                val pixels = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "fixture-numeric-font-$index.png", "image/png", pixels)
                expected.forEachIndexed { n, value -> assertEquals("font=$index, field=${regions[n].field}, readings=$values", value, values[regions[n]]?.text) }
                // Not every mark in a numeric cell is a number, including a missing zero.
                canvas.drawRect(1150f, 50f, 1750f, 300f, Paint().apply { color = Color.WHITE })
                cell("?", 1440f)
                assertNull(reader.readRow(bitmap, regions)[regions[1]])
            } finally { bitmap.recycle() }
        }
    }

    private fun drawFixture(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val font = ResourcesCompat.getFont(context, R.font.vazirmatn_regular)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 60f; color = Color.BLACK; typeface = font }
        fun text(value: String, x: Float, y: Float, rtl: Boolean = false, width: Int = 320) {
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR).setIncludePad(false).build()
            canvas.save(); canvas.translate(x - width / 2f, y); layout.draw(canvas); canvas.restore()
        }
        text("تاریخ", 1600f, 100f, true); text("تعداد", 1200f, 100f, true); text("کیلومتر", 800f, 100f, true); text("ساعت", 400f, 100f, true)
        val rows = listOf(listOf("۱۴۰۳/۰۱/۰۱", "۲", "۱۲۰٫۵", "۳٫۵"), listOf("١٤٠٣/٠١/٠٢", "٣", "١٠٠", "٢٫٢٥"), listOf("1403/01/03", "1", "30", "0.5"))
        rows.forEachIndexed { n, values ->
            listOf(1600f, 1200f, 800f, 400f).forEachIndexed { c, x -> text(values[c], x, 280f + n * 150f, width = if (c == 0) 440 else 300) }
        }
        text("جمع", 1600f, 800f, true); text("۶", 1200f, 800f); text("۲۵۰٫۵", 800f, 800f); text("۶٫۲۵", 400f, 800f)
    }
}
