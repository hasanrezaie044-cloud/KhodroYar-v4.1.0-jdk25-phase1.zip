package com.khodroyar.app.ui

import android.app.Application
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
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
import com.khodroyar.app.data.db.AppDatabase
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.ui.screens.WorkReportScreen
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.workreport.*
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic only. No private statement, employee name, salary or real OCR text goes into CI. */
@RunWith(AndroidJUnit4::class)
class CompanyReportIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private val fixture = listOf(
        listOf(listOf("1", "1403/04/12", "40", "0"), listOf("2", "1403/04/12", "75", "0")),
        listOf(listOf("3", "1403/04/18", "65", "2.5"), listOf("4", "1403/04/23", "90", "0")),
        listOf(listOf("5", "1403/04/29", "55", "1.25")),
    )
    @Before fun resetDisposableEmulatorOnly() {
        runBlocking {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
            WorkManager.getInstance(context).cancelAllWork().result.get()
            withContext(Dispatchers.IO) { db.clearAllTables() }
            ReportDocuments(context).directory.deleteRecursively()
        }
    }
    private fun service(id: String, row: List<String>) = ServiceEntity(id, row[1], "night", "soren", row[2].toDouble(), row[3].toDouble(), row[3].toDouble(),
        "07:00", "18:00", "شهر الف", "شهر ب", "", "", 0, 0.0, 0.0, 0.0, 0L, "2026-01-01T00:00:00Z")

    @Test fun threePageRuledFormAndRepeatedScanFindTheThirdActualServiceOnTheSameDay() {
        val directory = File(context.cacheDir, "exports").also { it.mkdirs() }
        val pdf = File(directory, "company-template-synthetic.pdf")
        val image = File(directory, "company-template-repeated-synthetic.png")
        val document = PdfDocument()
        try {
            fixture.forEachIndexed { n, _ ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(2400, 1800, n + 1).create())
                drawCompany(page.canvas, n); document.finishPage(page)
            }
            pdf.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        drawCompany(Canvas(bitmap), 0)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val documents = ReportDocuments(context)
        val imported = runBlocking { documents.import(listOf(pdf, image).map { FileProvider.getUriForFile(context, "com.khodroyar.app.fileprovider", it) }, emptyList()) }
        assertEquals(4, imported.size)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("svc wifi disable").close(); automation.executeShellCommand("svc data disable").close()
        try {
            val diagnostics = mutableListOf<String>()
            val recognized = runBlocking { OfflineReportOcr(context).recognize(imported, true, diagnostics = { diagnostics += it }, progress = {}) }
            val rows = WorkReportParser.extract(recognized)
            val evidence = JSONObject().put("environment", "Synthetic fixed recurring form; three PDF pages plus repeated PNG; native offline engine")
                .put("rows", JSONArray().apply { rows.forEach { r -> put(JSONObject().put("date", r.date).put("ordinal", r.ordinal).put("count", r.count).put("km", r.km).put("hours", r.hours).put("included", r.included).put("kind", r.kind.name).put("note", r.note)) } })
                .put("diagnostics", JSONArray(diagnostics))
            publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "company-template-ocr.json", "application/json", evidence.toString().toByteArray())
            publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "fixture-company-template.png", "image/png", image.readBytes())
            assertTrue("Every PDF/PNG page must establish its own recognised header", recognized.all { CompanyReportTemplate.header(it.words) != null })
            assertEquals("Header-selected language policy, never a numeric-result retry", imported.size,
                diagnostics.count { it == "Fixed-form primary languages: fas+eng; selected from header before cell recognition" })
            val selected = rows.filter { it.included }
            assertEquals("All five real rows, not ordinals or footers: $rows", 5, selected.size)
            assertTrue(selected.all { it.kind == ReportRowKind.SERVICE && it.count == "1" && it.validated() != null })
            assertEquals(2, rows.count { it.duplicateOf != null })
            assertEquals(4, rows.count { it.note.contains("ردیف جمع") })
            val period = CompanyReportTemplate.period(recognized, rows)
            assertEquals("1403/04/01", period.start); assertEquals("1403/04/31", period.end)
            runBlocking {
                fixture.flatten().forEach { db.services().insert(service("actual-${it[0]}", it)) }
                db.services().insert(service("actual-missing-third", listOf("", "1403/04/12", "130", "4.5")))
                WorkReportDraft(documents.directory).save(WorkReportDraft.Draft(imported, recognized, rows, period.start, period.end, periodNote = period.note))
            }
            val before = runBlocking { db.services().allOnce() }
            val vm = WorkReportViewModel(context.applicationContext as Application)
            compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CarManagerTheme { Surface(Modifier.fillMaxSize()) { WorkReportScreen(vm) } }
            } }
            compose.waitUntil(30000) { !vm.busy && vm.rows.size == rows.size }
            compose.waitUntil(30000) {
                vm.dateComparison.any { it.date == "1403/04/12" && it.companyCount == 2 && it.appCount == 3 && it.difference == -1 }
            }
            compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-date-compare-1403/04/12"))
            compose.onNodeWithTag("report-date-compare-1403/04/12").assertIsDisplayed()
                .assert(hasAnyDescendant(hasText("شرکت 2 سرویس")))
                .assert(hasAnyDescendant(hasText("برنامه 3 سرویس")))
            compose.onNodeWithTag("report-compare").assertIsNotEnabled()
            val reviewRow = vm.rows.first { it.included && it.date == "1403/04/12" }
            compose.runOnIdle { vm.edit(reviewRow.copy(included = false)) }
            compose.waitUntil(30000) {
                vm.dateComparison.any { it.date == "1403/04/12" && it.companyCount == 1 && it.appCount == 3 }
            }
            compose.runOnIdle { vm.edit(reviewRow) }
            compose.waitUntil(30000) {
                vm.dateComparison.any { it.date == "1403/04/12" && it.companyCount == 2 && it.appCount == 3 }
            }
            assertFalse(vm.reviewConfirmed)
            assertEquals(before, runBlocking { db.services().allOnce() }) // Count reads/row edits never write services.
            runBlocking { db.services().insert(service("synthetic-first-day", listOf("", period.start, "40", "1"))) }
            compose.waitUntil(30000) {
                vm.dateComparison.any { it.date == period.start && it.companyCount == 0 && it.appCount == 1 }
            }
            compose.runOnIdle { vm.range("1403/04/02", period.end) }
            compose.waitUntil(30000) { vm.dateComparison.isNotEmpty() && vm.dateComparison.none { it.date == period.start } }
            compose.runOnIdle { vm.range(period.start, period.end) }
            compose.waitUntil(30000) { vm.dateComparison.any { it.date == period.start && it.appCount == 1 } }
            runBlocking { db.services().deleteById("synthetic-first-day") }
            compose.waitUntil(30000) { vm.dateComparison.isNotEmpty() && vm.dateComparison.none { it.date == period.start } }
            assertEquals(before, runBlocking { db.services().allOnce() })
            compose.runOnIdle { vm.setReview(true) }
            compose.onNodeWithTag("report-compare").performClick()
            compose.waitUntil(30000) { !vm.busy }
            val result = requireNotNull(vm.comparison) { vm.error.orEmpty() }
            val missing = result.services.single { it.status == ServiceMatchStatus.ONLY_IN_APP }.app.single()
            assertEquals("actual-missing-third", missing.id); assertEquals("1403/04/12", missing.date)
            assertEquals(130.0, missing.km.toDouble(), .000001); assertEquals(4.5, missing.hours.toDouble(), .000001)
            assertEquals(5, result.services.count { it.status == ServiceMatchStatus.MATCH })
            compose.onNodeWithTag("report-comparison").performScrollToNode(hasText("در گزارش شرکت نیست"))
            compose.onNodeWithText("در گزارش شرکت نیست").assertIsDisplayed()
            assertEquals(before, runBlocking { db.services().allOnce() })
        } finally {
            automation.executeShellCommand("svc wifi enable").close(); automation.executeShellCommand("svc data enable").close()
            pdf.delete(); image.delete()
        }
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

    @Test fun pinnedClassicPrintModelActuallyRunsButNeverTurnsTinyInkIntoAConfidentDate() {
        val api = TessBaseAPI()
        val bitmap = Bitmap.createBitmap(500, 110, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(api.init(OfflineReportOcr(context).installModels().path, "fas_print", TessBaseAPI.OEM_TESSERACT_ONLY))
            api.setVariable("classify_enable_learning", "0"); api.setVariable("tessedit_enable_doc_dict", "0"); api.setVariable("user_defined_dpi", "220")
            var requested = 0
            val reader = PrintedCellOcr { requested++; api }
            val region = NumericRegion(NumericField.DATE, 0f, 0f, 1f, 1f)
            bitmap.eraseColor(Color.WHITE)
            assertNull(reader.read(bitmap, region)); assertEquals(0, requested)
            Canvas(bitmap).drawRect(20f, 40f, 24f, 46f, Paint().apply { color = Color.BLACK })
            assertNull(reader.read(bitmap, region)); assertEquals(0, requested)
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 38f; typeface = ResourcesCompat.getFont(context, R.font.parastoo_regular) }
            Canvas(bitmap).drawText("1402/07/18", 30f, 65f, paint)
            val logs = mutableListOf<String>()
            val read = requireNotNull(reader.read(bitmap, region, diagnostics = { logs += it })) { logs.joinToString("\n") }
            assertEquals("1402/07/18", read.text); assertTrue(read.confidence >= 85f); assertTrue(read.agreeingViews > 0)
            assertFalse(bitmap.isRecycled)
            assertTrue(runCatching { reader.read(bitmap, region) { error("cancel") } }.isFailure)
        } finally { api.recycle(); bitmap.recycle() }
    }

    @Test fun ArabicFivesAndSmallZeroesKeepTheirRowBaselineEvenAboveTwelvePixels() {
        val bitmap = Bitmap.createBitmap(900, 150, Bitmap.Config.ARGB_8888)
        val font = requireNotNull(ResourcesCompat.getFont(context, R.font.vazirmatn_regular))
        val regions = listOf(
            NumericRegion(NumericField.DATE, 0f, 0f, .55f, 1f),
            NumericRegion(NumericField.KM, .56f, 0f, .72f, 1f),
            NumericRegion(NumericField.HOURS, .74f, 0f, .84f, 1f),
            NumericRegion(NumericField.ORDINAL, .86f, 0f, 1f, 1f),
        )
        val reader = NumericGlyphOcr(context)
        val evidence = JSONArray()
        val actual = mutableListOf<Triple<String?, String?, String?>>()
        val cases = listOf("٥٥" to "55", "٠" to "0", "۵۵" to "55", "0" to "0")
        try {
            for ((text, _) in cases) {
                bitmap.eraseColor(Color.WHITE)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = font }
                fun draw(value: String, x: Float, size: Float) { paint.textSize = size; canvas.drawText(value, x, 90f, paint) }
                draw("1402/07/18", 25f, 38f); draw(text, 525f, 42f); draw("٠", 675f, 40f); draw("٥", 800f, 42f)
                val logs = mutableListOf<String>()
                val readings = reader.readRow(bitmap, regions, diagnostics = { logs += it }, cellBaselines = true)
                fun value(field: NumericField) = readings.entries.single { it.key.field == field }.value?.text
                actual += Triple(value(NumericField.KM), value(NumericField.HOURS), value(NumericField.ORDINAL))
                evidence.put(JSONObject().put("input", text).put("readings", readings.toString()).put("diagnostics", JSONArray(logs)))
            }
            publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "numeric-font-row-baselines.json", "application/json", evidence.toString().toByteArray())
            cases.forEachIndexed { index, (_, expected) ->
                assertEquals(evidence.toString(), Triple(expected, "0", "5"), actual[index])
            }
        } finally { bitmap.recycle() }
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

    private fun drawCompany(canvas: Canvas, page: Int) {
        canvas.drawColor(Color.WHITE)
        val font = requireNotNull(ResourcesCompat.getFont(context, R.font.vazirmatn_regular))
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 38f; color = Color.BLACK }
        fun cell(value: String, left: Int, right: Int, y: Float, rtl: Boolean = false, size: Float = 38f) {
            text.textSize = size
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, text, right - left - 8).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR).setIncludePad(false).build()
            canvas.save(); canvas.translate(left + 4f, y); layout.draw(canvas); canvas.restore()
        }
        cell("تیر ماه ۱۴۰۳ · نمونهٔ ساختگی", 900, 2200, 70f, true)
        val xs = listOf(120, 320, 500, 680, 860, 1032, 1152, 1272, 1656, 1944, 2196, 2304)
        val data = fixture[page]; val bottom = 360 + data.size * 150; val end = bottom + 150
        val line = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        for (x in xs) canvas.drawLine(x.toFloat(), 270f, x.toFloat(), (if (x in listOf(1656, 1944, 2196)) bottom else end).toFloat(), line)
        (listOf(270, 360) + (1..data.size).map { 360 + it * 150 } + end).forEach { y -> canvas.drawLine(120f, y.toFloat(), 2304f, y.toFloat(), line) }
        listOf("سری", "عوارض", "غذا", "هتل", "عوارض بزرگراه", "ساعت", "کیلومتر", "مقصد", "مبدأ", "تاریخ", "ردیف").forEachIndexed { n, label ->
            cell(label, xs[n], xs[n + 1], 295f, true, 28f)
        }
        val digits = listOf("0123456789", "۰۱۲۳۴۵۶۷۸۹", "٠١٢٣٤٥٦٧٨٩")[page]
        fun script(value: String) = value.map { if (it in '0'..'9') digits[it - '0'] else it }.joinToString("")
        data.forEachIndexed { index, values ->
            val y = 405f + index * 150
            cell(script(values[0]), 2196, 2304, y, size = 42f); cell(script(values[1]), 1944, 2196, y)
            cell("الف", 1656, 1944, y, true); cell("ب", 1272, 1656, y, true)
            cell(script(values[2]), 1152, 1272, y, size = 42f); cell(script(values[3]), 1032, 1152, y, size = 40f)
        }
        cell("جمع", 1272, 2304, bottom + 42f, true, 48f)
        cell(script(data.sumOf { it[2].toDouble() }.toInt().toString()), 1152, 1272, bottom + 42f, size = 38f)
        cell(script(data.sumOf { it[3].toDouble() }.toString()), 1032, 1152, bottom + 42f, size = 38f)
        cell("مبالغ ساختگی؛ جزو سرویس نیست", 500, 2200, end + 130f, true)
    }
}
