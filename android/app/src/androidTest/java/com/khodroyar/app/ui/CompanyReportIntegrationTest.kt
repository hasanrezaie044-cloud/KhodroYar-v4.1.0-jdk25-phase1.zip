package com.khodroyar.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import androidx.work.WorkManager
import com.khodroyar.app.data.db.AppDatabase
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.ui.screens.WorkReportScreen
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.workreport.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic documents + mocked HTTP only. This is NOT live FastOCR/scan-accuracy proof. */
@RunWith(AndroidJUnit4::class)
class CompanyReportIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private val viewModels = ViewModelStore()
    private val fixture = listOf(
        listOf(listOf("7", "1405/05/10", "40", "0"), listOf("8", "1405/05/10", "75", "0")),
        listOf(listOf("9", "1405/05/11", "65", "2.5"), listOf("10", "1405/05/11", "90", "0")),
        listOf(listOf("11", "1405/05/12", "55", "1.25")),
    )
    @Before fun resetDisposableEmulatorOnly() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        runBlocking {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            withContext(Dispatchers.IO) { db.clearAllTables() }
            ReportDocuments(context).directory.deleteRecursively()
        }
        com.khodroyar.app.ai.FastOcrApiKeyStore(context).clear()
    }
    @After fun stopTestObservers() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
    }
    private fun service(id: String, row: List<String>) = ServiceEntity(id, row[1], "night", "soren", row[2].toDouble(), row[3].toDouble(), row[3].toDouble(),
        "07:00", "18:00", "شهر الف", "شهر ب", "", "", 0, 0.0, 0.0, 0.0, 0L, "2026-01-01T00:00:00Z")

    private fun pdf(name: String, count: Int): File {
        val file = File(context.cacheDir, "exports/$name.pdf").also { it.parentFile!!.mkdirs() }
        val doc = PdfDocument()
        try {
            repeat(count) { n ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(1359, 2942, n + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 36f }
                page.canvas.drawText("SYNTHETIC REPORT PAGE ${n + 1}", 70f, 100f, paint)
                fixture[n % fixture.size].forEachIndexed { i, row -> page.canvas.drawText(row.joinToString("   "), 70f, 250f + 80 * i, paint) }
                doc.finishPage(page)
            }
            file.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        return file
    }
    private fun import(file: File): List<ReportPage> = runBlocking {
        ReportDocuments(context).import(listOf(FileProvider.getUriForFile(context, "com.khodroyar.app.fileprovider", file)), emptyList())
    }

    private class MockFastOcr(val outputs: List<String>, val partial: Boolean = false) : FastOcrClient.Transport {
        val requests = mutableListOf<FastOcrClient.Request>()
        val uploads = mutableListOf<ByteArray>()
        private var index = -1
        override fun execute(request: FastOcrClient.Request): FastOcrClient.Response {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Blocking network must stay off the UI thread" }
            requests += request
            fun response(body: String, status: Int = 200) = FastOcrClient.Response(status, body.toByteArray())
            return when {
                request.url == "${FastOcrClient.BASE}/v1/documents" -> {
                    index++
                    response("""{"id":"doc-$index","upload_url":"https://storage.example.test/upload-$index"}""")
                }
                request.method == "PUT" -> {
                    assertNull(request.headers["Authorization"])
                    assertEquals("application/pdf", request.headers["Content-Type"])
                    val bytes = requireNotNull(request.body)
                    assertEquals("%PDF-", String(bytes.copyOfRange(0, 5)))
                    uploads += bytes
                    response("", 200)
                }
                request.url.endsWith("/start") -> response("", 202)
                request.url.contains("/output?") -> response("""{"url":"https://storage.example.test/text-$index"}""")
                request.url.startsWith("https://storage.example.test/text-") -> {
                    assertTrue(request.headers.isEmpty())
                    response(outputs[index])
                }
                else -> response("""{"status":"${if (partial) "partial" else "completed"}","pages_processed":1,"pages_total":1,"is_truncated":$partial}""")
            }
        }
    }

    @Test fun onlineOnlyConsentParserReviewAndLiveDatabaseCountsNeverModifyAnyService() {
        val file = pdf("online-three-page-synthetic", 3)
        val mock = MockFastOcr(fixture.map { page -> page.joinToString("\n") { r -> "${r[0]}${r[1]} شهر الف شهر ب ${r[2]} ${r[3]}" } })
        val vm = WorkReportViewModel(context.applicationContext as Application,
            FastOcrReportReader(FastOcrClient(mock, sleeper = {})))
        viewModels.put("synthetic-online-reader", vm)
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            CarManagerTheme { Surface(Modifier.fillMaxSize()) { WorkReportScreen(vm) } }
        } }
        compose.waitUntil(30000) { !vm.busy }
        compose.runOnIdle { vm.importDocuments(listOf(FileProvider.getUriForFile(context, "com.khodroyar.app.fileprovider", file))) }
        compose.waitUntil(30000) { !vm.busy && vm.pages.size == 3 }
        assertTrue(mock.requests.isEmpty())
        compose.onNodeWithTag("report-ocr").assertDoesNotExist()
        compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-online-assist"))
        compose.onNodeWithTag("report-online-assist").performClick()
        compose.onNodeWithTag("report-fastocr-host").assertTextEquals("گیرندهٔ تصاویر: api.fastocr.org")
        compose.onNodeWithTag("report-online-cancel").performClick()
        assertTrue("Cancel sends nothing", mock.requests.isEmpty())
        compose.onNodeWithTag("report-online-assist").performClick()
        compose.onNodeWithTag("report-fastocr-key").performTextInput("fok_live_synthetic_fixture_not_a_secret")
        compose.onNodeWithTag("report-online-send").performClick()
        compose.waitUntil(90000) { !vm.busy && vm.rows.size == 5 }
        assertEquals(vm.error, null)
        assertEquals(18, mock.requests.size)
        assertEquals(3, mock.uploads.size)
        assertTrue(vm.recognized.all { it.source == ReportTextSource.FASTOCR && it.words.isEmpty() })
        assertTrue(ReportReadCoverage.complete(vm.pages, vm.recognized))
        assertTrue(vm.rows.all { it.kind == ReportRowKind.SERVICE && it.count == "1" && it.validated() != null })
        assertEquals("1405/05/01", vm.start); assertEquals("1405/05/31", vm.end)
        assertTrue(vm.readWarnings.isEmpty())
        runBlocking {
            fixture.flatten().forEach { db.services().insert(service("actual-${it[0]}", it)) }
            db.services().insert(service("missing-ten", listOf("", "1405/05/10", "130", "4.5")))
            db.services().insert(service("missing-eleven", listOf("", "1405/05/11", "150", "5")))
        }
        val before = runBlocking { db.services().allOnce() }
        compose.waitUntil(30000) { vm.dateComparison.count { it.companyCount == 2 && it.appCount == 3 } == 2 }
        compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-date-compare-1405/05/10"))
        compose.onNodeWithTag("report-date-compare-1405/05/10").assertIsDisplayed()
            .assert(hasAnyDescendant(hasText("شرکت 2 سرویس"))).assert(hasAnyDescendant(hasText("برنامه 3 سرویس")))
        compose.onNodeWithTag("report-compare").assertIsNotEnabled()
        val reviewRow = vm.rows.first()
        compose.runOnIdle { vm.edit(reviewRow.copy(included = false)) }
        compose.waitUntil(30000) { vm.dateComparison.any { it.date == "1405/05/10" && it.companyCount == 1 && it.appCount == 3 } }
        compose.runOnIdle { vm.edit(reviewRow) }
        compose.waitUntil(30000) { vm.dateComparison.any { it.date == "1405/05/10" && it.companyCount == 2 && it.appCount == 3 } }
        assertFalse(vm.reviewConfirmed)
        assertEquals(before, runBlocking { db.services().allOnce() })
        runBlocking { db.services().insert(service("synthetic-first-day", listOf("", vm.start, "40", "1"))) }
        compose.waitUntil(30000) { vm.dateComparison.any { it.date == "1405/05/01" && it.companyCount == 0 && it.appCount == 1 } }
        compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-date-compare-1405/05/01"))
        compose.onNodeWithTag("report-date-compare-1405/05/01").assert(hasAnyDescendant(hasText("شرکت: در متن یافت نشد")))
        compose.runOnIdle { vm.range("1405/05/02", vm.end) }
        compose.waitUntil(30000) { vm.dateComparison.isNotEmpty() && vm.dateComparison.none { it.date == "1405/05/01" } }
        runBlocking { db.services().deleteById("synthetic-first-day") }
        compose.runOnIdle { vm.range("1405/05/01", vm.end) }
        compose.waitUntil(30000) { vm.dateComparison.isNotEmpty() && vm.dateComparison.none { it.date == "1405/05/01" } }
        compose.runOnIdle { vm.setReview(true); vm.compare() }
        compose.waitUntil(30000) { !vm.busy }
        val result = requireNotNull(vm.comparison) { vm.error.orEmpty() }
        assertEquals(setOf("missing-ten", "missing-eleven"), result.services.filter { it.status == ServiceMatchStatus.ONLY_IN_APP }.flatMap { it.app }.map { it.id }.toSet())
        assertEquals(5, result.services.count { it.status == ServiceMatchStatus.MATCH })
        assertEquals(before, runBlocking { db.services().allOnce() })
        compose.runOnIdle { vm.reparseSavedText() }
        compose.waitUntil(30000) { !vm.busy && vm.comparison == null }
        assertEquals("Reparse is local, not another billable upload", 18, mock.requests.size)
        assertFalse(vm.reviewConfirmed)
        val evidence = JSONObject().put("environment", "Synthetic report; mocked FastOCR transport; NOT live recognition")
            .put("passed", true).put("rows", 5).put("pdf_uploads", 3).put("requests", 18)
            .put("consent_cancel_sent_nothing", true).put("local_reparse_sent_nothing", true)
            .put("days", JSONArray(listOf("1405/05/10", "1405/05/11"))).put("unchanged_app_services", before.size)
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "online-report.json", "application/json", evidence.toString().toByteArray())
        file.delete()
    }

    @Test fun singlePagePdfIsSentByteForByteAndRotationCreatesOnlyOneBoundedPage() {
        val file = pdf("single-page-preservation-synthetic", 1)
        try {
            val documents = ReportDocuments(context)
            val page = import(file).single()
            assertArrayEquals(file.readBytes(), documents.pdfForUpload(page))
            val rotated = documents.pdfForUpload(page.copy(rotation = 1))
            assertFalse(rotated.contentEquals(file.readBytes()))
            val check = File(context.cacheDir, "rotated-upload-synthetic.pdf")
            try {
                check.writeBytes(rotated)
                ParcelFileDescriptor.open(check, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { p -> assertTrue(p.width > p.height); assertTrue(p.width.toLong() * p.height <= 6_000_000) }
                } }
            } finally { check.delete() }
        } finally { file.delete() }
    }

    @Test fun incompleteProviderResponseAndProvenanceSurviveDraftRecoveryAndCannotPassCoverage() {
        val file = pdf("partial-synthetic", 1)
        try {
            val documents = ReportDocuments(context); val pages = import(file)
            val reader = FastOcrReportReader(FastOcrClient(MockFastOcr(listOf("7 1405/05/10 شهر الف شهر ب 40 ."), partial = true), sleeper = {}))
            val read = runBlocking { reader.read(documents, pages, "fok_live_synthetic_fixture_only") }
            assertEquals("", read.rows.single().hours)
            assertTrue(read.pages.single().incomplete)
            WorkReportDraft(documents.directory).save(WorkReportDraft.Draft(pages, read.pages, read.rows, "1405/05/01", "1405/05/31"))
            val loaded = requireNotNull(WorkReportDraft(documents.directory).load())
            assertEquals(ReportTextSource.FASTOCR, loaded.recognized.single().source)
            assertTrue(loaded.recognized.single().incomplete)
            assertFalse(ReportReadCoverage.complete(loaded.pages, loaded.recognized))
            assertTrue(ReportReadCoverage.warnings(loaded.pages, loaded.recognized, loaded.rows).single().contains("ناقص"))
        } finally { file.delete() }
    }

    @Test fun cancellationBeforeUploadDoesNotCallTheTransportOrInstallAnyModel() {
        val file = pdf("cancel-synthetic", 1)
        try {
            val mock = MockFastOcr(listOf("unused"))
            val reader = FastOcrReportReader(FastOcrClient(mock, sleeper = {}))
            assertTrue(runCatching { runBlocking { reader.read(ReportDocuments(context), import(file), "fok_live_synthetic_fixture_only",
                checkCancelled = { throw CancellationException("synthetic cancellation") }) } }.exceptionOrNull() is CancellationException)
            assertTrue(mock.requests.isEmpty())
            assertFalse(File(context.noBackupFilesDir, "offline-ocr").exists())
        } finally { file.delete() }
    }
    @Test fun manualGroupReviewRestoresItsSnapshotButCannotApproveStaleServiceValues() {
        val documents = ReportDocuments(context)
        val page = ReportPage("synthetic", "00000000-0000-0000-0000-000000000000.img", "synthetic")
        val original = CompanyOcrRow(1, "synthetic group", "1403/04/12", "17", "140", "3", "کارکرد نیمه اول", "کارکرد نیمه اول", 95f)
        val ocr = OcrPage(page.id, 1, listOf(OcrWord("synthetic", .1f, .1f, .2f, .2f, 95f)), "synthetic", listOf(original))
        val rows = WorkReportParser.extract(listOf(ocr))
        val actual = listOf(AppWorkService("a", "1403/04/02", "60".toBigDecimal(), "1".toBigDecimal()), AppWorkService("b", "1403/04/03", "80".toBigDecimal(), "2".toBigDecimal()))
        val link = ReportMatchLink(rows.map { it.id }, actual.map { it.id }, ServiceWorkComparison.fingerprint(actual))
        val draft = WorkReportDraft(documents.directory)
        draft.save(WorkReportDraft.Draft(listOf(page), listOf(ocr), rows, "1403/04/01", "1403/04/31", listOf(link), "synthetic monthly period"))
        val loaded = requireNotNull(draft.load())
        assertEquals(ReportRowKind.GROUPED, loaded.rows.single().kind); assertEquals(listOf(original), loaded.recognized.single().companyRows)
        assertEquals(listOf(link), loaded.links)
        assertEquals(ServiceMatchStatus.GROUPED, ServiceWorkComparison.compare(loaded.rows, actual, loaded.start, loaded.end, loaded.links).services.single().status)
        assertTrue(runCatching { ServiceWorkComparison.compare(loaded.rows, actual.map { it.copy(hours = "9".toBigDecimal()) }, loaded.start, loaded.end, loaded.links) }.isFailure)
    }

    @Test fun slightlyOversizedPhotoRetainsNearFullPrintResolutionWithinTheExistingCaps() {
        val file = File(context.cacheDir, "exports/company-resolution-synthetic.png").also { it.parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(2200, 2900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            val documents = ReportDocuments(context)
            val pages = runBlocking { documents.import(listOf(FileProvider.getUriForFile(context, "com.khodroyar.app.fileprovider", file)), emptyList()) }
            val rendered = documents.render(pages.single())
            try {
                assertTrue(rendered.width > 2000 && rendered.height > 2700)
                assertTrue(rendered.width.toLong() * rendered.height <= 6000000 && maxOf(rendered.width, rendered.height) <= 3200)
            } finally { rendered.recycle() }
        } finally { file.delete() }
    }

    @Test fun recoveryStillReadsVersionOneDraftsWithoutInventingServiceIdentity() {
        val directory = File(context.cacheDir, "company-draft-v1-synthetic").also { it.mkdirs() }
        try {
            val legacy = JSONObject().put("version", 1).put("start", "1403/01/01").put("end", "1403/01/31")
                .put("pages", JSONArray()).put("ocr", JSONArray()).put("rows", JSONArray().put(JSONObject()
                    .put("id", "r").put("pageId", "p").put("page", 1).put("line", 1).put("raw", "synthetic")
                    .put("date", "1403/01/01").put("count", "2").put("km", "100").put("hours", "3")
                    .put("confidence", 90).put("included", true).put("note", "legacy").put("duplicate", JSONObject.NULL)))
            File(directory, "draft.json").writeText(legacy.toString())
            val loaded = requireNotNull(WorkReportDraft(directory).load())
            assertEquals(ReportRowKind.DAILY_TOTAL, loaded.rows.single().kind); assertTrue(loaded.links.isEmpty())
            assertEquals(2, loaded.rows.single().validated()!!.count)
        } finally { directory.deleteRecursively() }
    }


}
