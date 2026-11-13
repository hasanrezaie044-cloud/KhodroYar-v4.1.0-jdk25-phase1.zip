package com.khodroyar.app.workreport

import android.content.Context
import android.graphics.Bitmap
import com.khodroyar.app.core.text.PersianInput
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** All recognition is JNI Tesseract on this device, with source-pinned bundled models. */
class OfflineReportOcr(private val context: Context) {
    private val activeLock = Any()
    private var active = emptyList<TessBaseAPI>()
    // stop() may run on the UI thread while the IO coroutine finishes. Serialize
    // only lifecycle/stop, never recognition, so a recycled JNI pointer is not used.
    fun stop() { synchronized(activeLock) { active.forEach { it.stop() } } }

    suspend fun recognize(pages: List<ReportPage>, sparse: Boolean, diagnostics: ((String) -> Unit)? = null, progress: (String) -> Unit): List<OcrPage> = withContext(Dispatchers.IO) {
        engineLock.withLock {
            require(pages.isNotEmpty()) { "ابتدا تصویر یا PDF انتخاب کنید." }
            val modelDirectory = installModels()
            currentCoroutineContext().ensureActive()
            val documents = ReportDocuments(context)
            val api = TessBaseAPI { value -> progress("خواندن آفلاین · ${value.percent}٪ از صفحهٔ جاری") }
            var printedApi: TessBaseAPI? = null
            fun printedEngine(): TessBaseAPI {
                printedApi?.let { return it }
                val print = TessBaseAPI()
                printedApi = print
                check(print.init(modelDirectory.path, "fas_print", TessBaseAPI.OEM_TESSERACT_ONLY)) { "باز کردن مدل چاپی آفلاین انجام نشد." }
                print.setVariable("classify_enable_learning", "0")
                print.setVariable("tessedit_enable_doc_dict", "0")
                print.setVariable("user_defined_dpi", "220")
                synchronized(activeLock) { active = listOf(api, print) }
                return print
            }
            try {
                check(api.init(modelDirectory.path, "fas+ara+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "باز کردن مدل‌های آفلاین OCR انجام نشد." }
                synchronized(activeLock) { active = listOf(api) }
                currentCoroutineContext().ensureActive()
                api.setVariable("preserve_interword_spaces", "1")
                api.setVariable("user_defined_dpi", "220")
                val result = mutableListOf<OcrPage>()
                pages.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    progress("صفحهٔ ${index + 1} از ${pages.size} · آماده‌سازی تصویر")
                    currentCoroutineContext().ensureActive()
                    var bitmap = documents.render(page)
                    try {
                        val coroutine = currentCoroutineContext()
                        bitmap = ReportImageGrid.deskew(bitmap) { coroutine.ensureActive() }
                        currentCoroutineContext().ensureActive()
                        api.pageSegMode = if (sparse) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT else TessBaseAPI.PageSegMode.PSM_AUTO
                        api.setVariable("tessedit_char_whitelist", "")
                        api.setImage(bitmap)
                        val text = api.getUTF8Text().orEmpty()
                        currentCoroutineContext().ensureActive()
                        val words = mutableListOf<OcrWord>()
                        api.resultIterator?.let { iterator ->
                            try {
                                iterator.begin()
                                do {
                                    val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                                    val value = iterator.getUTF8Text(level).orEmpty().trim()
                                    val box = iterator.getBoundingBox(level)
                                    if (value.isNotEmpty() && box != null && box.size == 4) {
                                        words += OcrWord(value, box[0].toFloat() / bitmap.width, box[1].toFloat() / bitmap.height,
                                            box[2].toFloat() / bitmap.width, box[3].toFloat() / bitmap.height, iterator.confidence(level))
                                    }
                                    require(words.size <= 50000) { "متن یک صفحه بیش از حد بزرگ است." }
                                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                            } finally { iterator.delete() }
                        }
                        diagnostics?.invoke("Page ${index + 1} initial text: $text; words: $words")
                        val headerWords = if (CompanyReportTemplate.header(words) == null)
                            PrintedHeaderOcr(api, ::printedEngine).refine(bitmap, words, diagnostics) { coroutine.ensureActive() } else words
                        if (headerWords !== words) api.setImage(bitmap) // Header crops never replace the page used for numeric cells.
                        val company = CompanyReportTemplate.header(headerWords)?.let { header ->
                            readCompanyTable(api, ::printedEngine, bitmap, headerWords, header, diagnostics) { row, count -> progress("صفحهٔ ${index + 1} از ${pages.size} · خواندن ردیف $row از $count") }
                        }.orEmpty()
                        val refined = if (company.isEmpty()) refineTable(api, bitmap, headerWords, diagnostics) else headerWords
                        result += OcrPage(page.id, index + 1, refined, text, company)
                    } finally { api.clear(); bitmap.recycle() }
                    require(result.sumOf { it.words.size } <= 200000) { "متن این مقایسه بیش از حد بزرگ است؛ سند را به چند بخش تقسیم کنید." }
                }
                result
            } finally { synchronized(activeLock) { active = emptyList(); printedApi?.recycle(); api.recycle() } }
        }
    }

    /**
     * Sparse-page recognition suppresses isolated one-digit counts and can confuse
     * decimal marks. Read actual header-defined cells with both the local visual
     * numeric reader and single-line Tesseract. Conflicting strong readings stay
     * blank. This is image recognition, not substitutions or totals-based guesses.
     */
    private suspend fun refineTable(api: TessBaseAPI, bitmap: Bitmap, original: List<OcrWord>, diagnostics: ((String) -> Unit)?): List<OcrWord> {
        val regions = ReportTableGeometry.regions(original)
        require(regions.size <= 1600) { "جدول این صفحه برای بازخوانی دقیق بزرگ است؛ صفحه را به بخش‌های کوچک‌تر تقسیم کنید." }
        val result = original.toMutableList()
        val reader = if (regions.isEmpty()) null else NumericGlyphOcr(context)
        val coroutine = currentCoroutineContext()
        val pixels = regions.groupBy { it.top to it.bottom }.values.flatMap { row ->
            requireNotNull(reader).readRow(bitmap, row, diagnostics) { coroutine.ensureActive() }.entries.map { it.key to it.value }
        }.toMap()
        fun canonical(field: NumericField, raw: String): String? = when (field) {
            NumericField.DATE -> PersianInput.date(raw)
            NumericField.COUNT -> PersianInput.count(raw)?.toString()
            NumericField.ORDINAL -> PersianInput.count(raw)?.takeIf { it > 0 }?.toString()
            NumericField.KM -> PersianInput.decimal(raw)?.let(PersianInput::display)
            NumericField.HOURS -> PersianInput.hours(raw)?.let(PersianInput::display)
        }
        for (region in regions) {
            currentCoroutineContext().ensureActive()
            val left = (region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val width = ((region.right * bitmap.width).toInt() - left).coerceIn(1, bitmap.width - left)
            val height = ((region.bottom * bitmap.height).toInt() - top).coerceIn(1, bitmap.height - top)
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            val digits = "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩"
            val whitelist = when (region.field) {
                NumericField.DATE -> "" // Also preserve readable «جمع»/footer labels in the date cell.
                NumericField.COUNT, NumericField.ORDINAL -> digits + ".٫,٬"
                NumericField.KM -> digits + ".٫,٬"
                NumericField.HOURS -> digits + ".٫:"
            }
            api.setVariable("tessedit_char_whitelist", whitelist)
            api.setRectangle(left, top, width, height)
            val raw = api.getUTF8Text().orEmpty().trim()
            val confidence = api.meanConfidence().toFloat()
            currentCoroutineContext().ensureActive()
            val tesseract = canonical(region.field, raw)
            val pixel = pixels[region]?.takeIf { canonical(region.field, it.text) != null }
            val printedFooter = if (region.field == NumericField.DATE && pixel == null) reader?.readFooter(bitmap, region) else null
            val footer = region.field == NumericField.DATE && Regex("^(?:جمع(?:\\s*کل)?|مجموع|total|subtotal)$", RegexOption.IGNORE_CASE).matches(PersianInput.latin(raw))
            val conflict = pixel != null && tesseract != null && confidence >= 85 && canonical(region.field, pixel.text) != tesseract
            diagnostics?.invoke("Cell $region: Tesseract='$raw' ($confidence); pixels=$pixel; printedFooter=$printedFooter; strongConflict=$conflict")
            val value = when {
                conflict -> null
                pixel != null -> pixel.text to pixel.confidence
                printedFooter != null -> printedFooter.text to printedFooter.confidence
                footer && confidence >= 70 -> raw to confidence
                tesseract != null && confidence >= 85 -> raw to confidence
                else -> null
            }
            val inside = original.filter { it.x > region.left && it.x < region.right && it.y > region.top && it.y < region.bottom }
            if (value != null) {
                result.removeAll(inside.toSet())
                result += OcrWord(value.first, region.left, region.lineTop, region.right, region.lineBottom, value.second)
            } else if (conflict) {
                // No engine is treated as an oracle when two strong readings differ.
                result.removeAll(inside.filter { PersianInput.latin(it.text).any { c -> c in '0'..'9' } }.toSet())
            } else {
                // A weak page-level numeric guess is not rescued by an unreadable cell.
                // Retain labels, but leave uncertain numeric fields blank for explicit review.
                result.removeAll(inside.filter { it.confidence < 85 && PersianInput.latin(it.text).any { c -> c in '0'..'9' } }.toSet())
            }
        }
        return result
    }

    /** Read every ruled row, including one whose date/numbers were entirely missed by sparse OCR. */
    private suspend fun readCompanyTable(api: TessBaseAPI, printedEngine: () -> TessBaseAPI, bitmap: Bitmap, original: List<OcrWord>, header: CompanyReportTemplate.Header,
        diagnostics: ((String) -> Unit)?, progress: (Int, Int) -> Unit): List<CompanyOcrRow> {
        val coroutine = currentCoroutineContext()
        val grid = ReportImageGrid.ink(bitmap) { coroutine.ensureActive() }.companyRows(header) { coroutine.ensureActive() }
        require(grid.size <= 400) { "جدول این صفحه بیش از ۴۰۰ ردیف دارد؛ آن را تقسیم کنید." }
        if (grid.isEmpty()) return emptyList()
        val reader = NumericGlyphOcr(context)
        val printed = PrintedCellOcr(printedEngine)
        val output = mutableListOf<CompanyOcrRow>()
        val fields = mapOf(CompanyReportTemplate.Column.DATE to NumericField.DATE, CompanyReportTemplate.Column.ORDINAL to NumericField.ORDINAL,
            CompanyReportTemplate.Column.KM to NumericField.KM, CompanyReportTemplate.Column.HOURS to NumericField.HOURS)
        fun canonical(field: NumericField, text: String): String? = when (field) {
            NumericField.DATE -> PersianInput.date(text)
            NumericField.COUNT -> PersianInput.count(text)?.toString()
            NumericField.ORDINAL -> PersianInput.count(text)?.takeIf { it > 0 }?.toString()
            NumericField.KM -> PersianInput.decimal(text)?.let(PersianInput::display)
            NumericField.HOURS -> PersianInput.hours(text)?.let(PersianInput::display)
        }
        fun read(rect: GridRect, whitelist: String = "", singleToken: Boolean = false): Pair<String, Float> {
            coroutine.ensureActive()
            val l = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val t = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val w = ((rect.right * bitmap.width).toInt() - l).coerceIn(1, bitmap.width - l)
            val h = ((rect.bottom * bitmap.height).toInt() - t).coerceIn(1, bitmap.height - t)
            // A ruled numeric cell is ONE token, not a sparse sentence. Dates
            // and descriptions retain line segmentation (including footer text).
            api.pageSegMode = if (singleToken) TessBaseAPI.PageSegMode.PSM_SINGLE_WORD else TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            api.setVariable("tessedit_char_whitelist", whitelist)
            api.setRectangle(l, t, w, h)
            return api.getUTF8Text().orEmpty().trim() to api.meanConfidence().toFloat()
        }
        val digits = "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩"
        for ((index, row) in grid.withIndex()) {
            coroutine.ensureActive(); progress(index + 1, grid.size)
            val span = read(row.description)
            val initialWords = original.filter { it.x in row.description.left..row.description.right && it.y > row.description.top && it.y < row.description.bottom }
            val initial = initialWords.sortedByDescending { it.x }.joinToString(" ") { it.text }
            val spanFooter = span.second >= 70f && ReportLabels.footerOnly(span.first)
            val initialFooter = initialWords.isNotEmpty() && initialWords.all { it.confidence >= 70f } && ReportLabels.footerOnly(initial)
            val printedFooter = if (!row.reliable && !spanFooter && !initialFooter) reader.readFooter(bitmap,
                NumericRegion(NumericField.DATE, row.description.left, row.description.top, row.description.right, row.description.bottom)) else null
            val classicFooter = if (!row.reliable && !spanFooter && !initialFooter && printedFooter == null)
                printed.footer(bitmap, row.description) { coroutine.ensureActive() } else null
            val footerEvidence = spanFooter || initialFooter || printedFooter != null || classicFooter != null
            val mergedFooter = !row.reliable && footerEvidence
            if (!row.reliable && !mergedFooter) {
                output += CompanyOcrRow(index, "خط‌کشی این بخش ناقص/چند ردیف ناخوانا است؛ با اصل سند به ردیف‌های جدا اصلاح یا اضافه کنید.", "", "", "", "")
                continue
            }
            val regions = row.cells.mapNotNull { (column, rect) -> fields[column]?.let { NumericRegion(it, rect.left, rect.top, rect.right, rect.bottom) } }
            val pixels = reader.readRow(bitmap, regions, diagnostics, cellBaselines = true) { coroutine.ensureActive() }
            var dateLabel = "" to 0f
            val readings = regions.associate { region ->
                val raw = read(GridRect(region.left, region.top, region.right, region.bottom), when (region.field) {
                    NumericField.DATE -> "" // Dates/total labels must not be manufactured by a digits-only whitelist.
                    NumericField.COUNT, NumericField.ORDINAL, NumericField.KM -> digits + ".٫,٬"
                    NumericField.HOURS -> digits + ".٫:"
                }, singleToken = region.field != NumericField.DATE)
                if (region.field == NumericField.DATE) dateLabel = raw
                val tess = canonical(region.field, raw.first)
                val pixel = pixels[region]?.takeIf { canonical(region.field, it.text) != null }
                val conflict = pixel != null && tess != null && raw.second >= 85 && canonical(region.field, pixel.text) != tess
                // Preserve existing accepted readings and strong conflict rejection.
                // The print classifier only supplies an otherwise UNKNOWN cell.
                val classic = if (!conflict && pixel == null && !(tess != null && raw.second >= 85f))
                    printed.read(bitmap, region, diagnostics) { coroutine.ensureActive() } else null
                val value = when {
                    conflict -> "" to 0f
                    pixel != null -> pixel.text to pixel.confidence
                    tess != null && raw.second >= 85f -> raw
                    classic != null -> classic.text to classic.confidence
                    else -> "" to 0f
                }
                diagnostics?.invoke("Fixed-form ${region.field}: tess=$raw; pixels=$pixel; printed=$classic; conflict=$conflict; accepted=$value")
                region.field to value
            }
            fun value(field: NumericField) = readings[field]?.first.orEmpty()
            fun text(column: CompanyReportTemplate.Column) = row.cells[column]?.let { read(it).first }.orEmpty()
            val origin = text(CompanyReportTemplate.Column.ORIGIN)
            val destination = text(CompanyReportTemplate.Column.DESTINATION)
            // A route containing «جمع» must not erase a real service row.
            val total = mergedFooter || (footerEvidence && value(NumericField.DATE).isBlank() && value(NumericField.ORDINAL).isBlank() && dateLabel.second >= 70 && ReportLabels.footerOnly(dateLabel.first))
            val raw = "ردیف ${value(NumericField.ORDINAL)} | تاریخ ${value(NumericField.DATE)} | $origin ← $destination | کیلومتر ${value(NumericField.KM)} | ساعت ${value(NumericField.HOURS)}" + if (total) " | جمع" else ""
            output += CompanyOcrRow(index, raw, value(NumericField.DATE), value(NumericField.ORDINAL), value(NumericField.KM), value(NumericField.HOURS),
                origin, destination, listOf(NumericField.DATE, NumericField.KM, NumericField.HOURS).minOf { readings[it]?.second ?: 0f }, total)
            if (total) break
        }
        return output
    }

    internal fun installModels(): File {
        val root = File(context.noBackupFilesDir, "offline-ocr")
        val target = File(root, "tessdata").also { it.mkdirs() }
        val manifest = JSONObject(context.assets.open("ocr/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i); val name = entry.getString("name")
            if (!name.endsWith(".traineddata")) continue
            require(name in listOf("fas.traineddata", "fas_print.traineddata", "ara.traineddata", "eng.traineddata"))
            val file = File(target, name)
            val hash = entry.getString("sha256")
            if (file.length() != entry.getLong("bytes") || digest(file) != hash) {
                val atomic = AtomicFile(file); val out = atomic.startWrite()
                try { context.assets.open("ocr/$name").use { it.copyTo(out) }; atomic.finishWrite(out) }
                catch (e: Throwable) { atomic.failWrite(out); throw e }
                check(file.length() == entry.getLong("bytes") && digest(file) == hash) { "مدل $name سالم نیست؛ پردازش متوقف شد." }
            }
        }
        return root
    }
    private fun digest(file: File): String {
        if (!file.isFile) return ""
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { private val engineLock = Mutex() }
}
