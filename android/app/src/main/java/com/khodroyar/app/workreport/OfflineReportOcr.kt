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

    suspend fun recognize(pages: List<ReportPage>, sparse: Boolean, diagnostics: ((String) -> Unit)? = null, progress: (String) -> Unit,
        /** Explicit-consent online assist: VM-owned HTTPS sender; returns response JSON per page. */
        onlineSender: (suspend (String, Bitmap) -> String)? = null): List<OcrPage> = withContext(Dispatchers.IO) {
        engineLock.withLock {
            require(pages.isNotEmpty()) { "ابتدا تصویر یا PDF انتخاب کنید." }
            val modelDirectory = installModels()
            currentCoroutineContext().ensureActive()
            val documents = ReportDocuments(context)
            val api = TessBaseAPI { value -> progress("خواندن آفلاین · ${value.percent}٪ از صفحهٔ جاری") }
            var printedApi: TessBaseAPI? = null
            var primaryLanguages = ""
            fun configurePrimary(languages: String) {
                if (languages == primaryLanguages) return
                // Init changes native state and resets ALL options. Serialize it
                // with stop/recycle, not recognition, and reapply every setting.
                synchronized(activeLock) {
                    check(api.init(modelDirectory.path, languages, TessBaseAPI.OEM_LSTM_ONLY)) { "باز کردن مدل‌های آفلاین OCR انجام نشد." }
                    api.setVariable("preserve_interword_spaces", "1")
                    api.setVariable("classify_enable_learning", "0")
                    api.setVariable("tessedit_enable_doc_dict", "0")
                    api.setVariable("user_defined_dpi", "220")
                    primaryLanguages = languages
                    active = listOfNotNull(api, printedApi)
                }
            }
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
                configurePrimary("fas+ara+eng")
                currentCoroutineContext().ensureActive()
                val result = mutableListOf<OcrPage>()
                pages.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    progress("صفحهٔ ${index + 1} از ${pages.size} · آماده‌سازی تصویر")
                    currentCoroutineContext().ensureActive()
                    var bitmap = documents.render(page)
                    try {
                        val coroutine = currentCoroutineContext()
                        bitmap = ReportPagePreprocessor.prepare(bitmap, diagnostics) { coroutine.ensureActive() }
                        currentCoroutineContext().ensureActive()
                        configurePrimary("fas+ara+eng") // Generic page/header recognition is unchanged.
                        val onlineFallback = onlineSender?.let { sender ->
                            progress("صفحهٔ ${index + 1} · ارسال اختیاری آنلاینِ تأییدشده")
                            val body = sender(page.id, bitmap)
                            val words = OnlineReportAssist.parseResponse(body).firstOrNull { it.pageId == page.id }?.words.orEmpty()
                            diagnostics?.invoke("Optional online assist page ${index + 1}: accepted ${words.size} word boxes; nothing auto-applied")
                            if (words.isEmpty()) null else OnlineCellFallback(api, bitmap, words)
                        }
                        currentCoroutineContext().ensureActive()
                        coroutine.ensureActive()
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
                            // Select the Persian print language pair solely from
                            // actual template headings, BEFORE reading any values.
                            // Competing Arabic-fast priors overconfidently turn
                            // short Arabic fives into Latin zeroes on this form.
                            // No engine is selected in response to a numeric result
                            // or conflict. All existing rejection gates remain.
                            configurePrimary("fas+eng")
                            coroutine.ensureActive()
                            api.setImage(bitmap) // Init discards the previous page image.
                            diagnostics?.invoke("Fixed-form primary languages: ${api.initLanguagesAsString}; selected from header before cell recognition")
                            readCompanyTable(api, ::printedEngine, onlineFallback, bitmap, headerWords, header, diagnostics) { row, count -> progress("صفحهٔ ${index + 1} از ${pages.size} · خواندن ردیف $row از $count") }
                        }.orEmpty()
                        val refined = if (company.isEmpty()) refineTable(api, ::printedEngine, onlineFallback, bitmap, headerWords, diagnostics) else headerWords
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
    private suspend fun refineTable(api: TessBaseAPI, printedEngine: () -> TessBaseAPI, onlineFallback: OnlineCellFallback?, bitmap: Bitmap, original: List<OcrWord>, diagnostics: ((String) -> Unit)?): List<OcrWord> {
        val fixed = CompanyReportTemplate.header(original) != null
        val printed = if (fixed) PrintedCellOcr(printedEngine) else null
        val regions = ReportTableGeometry.regions(original)
        require(regions.size <= 1600) { "جدول این صفحه برای بازخوانی دقیق بزرگ است؛ صفحه را به بخش‌های کوچک‌تر تقسیم کنید." }
        val result = original.toMutableList()
        val reader = if (regions.isEmpty()) null else NumericGlyphOcr(context)
        val coroutine = currentCoroutineContext()
        val pixels = regions.groupBy { it.top to it.bottom }.values.flatMap { row ->
            requireNotNull(reader).readRow(bitmap, row, diagnostics) { coroutine.ensureActive() }.entries.map { it.key to it.value }
        }.toMap()
        fun canonical(field: NumericField, raw: String): String? = when (field) {
            NumericField.DATE -> ReportDateText.parse(raw)
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
            api.pageSegMode = if (fixed && region.field != NumericField.DATE) TessBaseAPI.PageSegMode.PSM_SINGLE_WORD else TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            val digits = "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩"
            val whitelist = when (region.field) {
                NumericField.DATE -> "" // Also preserve readable «جمع»/footer labels in the date cell.
                NumericField.COUNT, NumericField.ORDINAL -> digits + ".٫,٬"
                NumericField.KM -> digits + ".٫,٬"
                NumericField.HOURS -> if (fixed) "" else digits + ".٫:"
            }
            api.setVariable("tessedit_char_whitelist", whitelist)
            api.setRectangle(left, top, width, height)
            val blankHours = fixed && region.field == NumericField.HOURS && blankCell(bitmap, GridRect(region.left, region.top, region.right, region.bottom))
            val raw = if (blankHours) "" else api.getUTF8Text().orEmpty().trim()
            val confidence = if (blankHours) 0f else api.meanConfidence().toFloat()
            currentCoroutineContext().ensureActive()
            val tesseract = canonical(region.field, raw)
            val pixel = pixels[region]?.takeIf { canonical(region.field, it.text) != null }
            val printedFooter = if (region.field == NumericField.DATE && pixel == null) reader?.readFooter(bitmap, region) else null
            val footer = region.field == NumericField.DATE && Regex("^(?:جمع(?:\\s*کل)?|مجموع|total|subtotal)$", RegexOption.IGNORE_CASE).matches(PersianInput.latin(raw))
            val marker = fixed && region.field == NumericField.HOURS && raw.trim() in listOf(".", "-")
            val classic = if (fixed && !blankHours && !marker && pixel == null && !(tesseract != null && confidence >= 85f) && !footer)
                printed?.read(bitmap, region, diagnostics) { coroutine.ensureActive() } else null
            val decision = if (fixed) CompanyCellPolicy.decide(region.field, buildList {
                add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.TESSERACT, raw, confidence))
                pixel?.let { add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.GLYPH, it.text, it.confidence)) }
                classic?.let { add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.CLASSIC, it.text, it.confidence)) }
            }, strongViewConflict = classic?.conflict == true) else null
            val online = if ((decision == null || decision.text.isEmpty() || decision.conflict) && !blankHours && !marker)
                onlineFallback?.read(GridRect(region.left, region.top, region.right, region.bottom), region.field != NumericField.DATE,
                    if (region.field == NumericField.DATE) "" else digits + ".٫,٬")?.takeIf { canonical(region.field, it.first) != null } else null
            // On the fixed form the optional online reading joins the SAME evidence
            // policy: it can complete a still-unknown cell but never rescues or
            // overrides a genuine strong conflict.
            val decisionFinal = if (fixed && online != null) CompanyCellPolicy.decide(region.field, buildList {
                add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.TESSERACT, raw, confidence))
                pixel?.let { add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.GLYPH, it.text, it.confidence)) }
                classic?.let { add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.CLASSIC, it.text, it.confidence)) }
                if (online != null) add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.ONLINE, online.first, online.second))
            }, strongViewConflict = classic?.conflict == true) else decision
            val conflict = decisionFinal?.conflict ?: (pixel != null && tesseract != null && confidence >= 85 && canonical(region.field, pixel.text) != tesseract)
            diagnostics?.invoke("Cell $region: fixed=$fixed; Tesseract='$raw' ($confidence); pixels=$pixel; printed=$classic; printedFooter=$printedFooter; online=$online; strongConflict=$conflict")
            val onlineValue = online?.let { canonical(region.field, it.first) }
            val value = when {
                conflict || blankHours || marker -> null
                fixed && decisionFinal != null && decisionFinal.text.isNotEmpty() -> decisionFinal.text to decisionFinal.confidence
                !fixed && pixel != null -> pixel.text to pixel.confidence
                printedFooter != null -> printedFooter.text to printedFooter.confidence
                footer && confidence >= 70 -> raw to confidence
                !fixed && tesseract != null && confidence >= 85 -> raw to confidence
                // Optional online words are strictly last: they never displace a
                // local reading, blank cell, dash marker or strong conflict.
                onlineValue != null && online!!.second >= 85f -> onlineValue to online.second
                else -> null
            }
            val inside = original.filter { it.x > region.left && it.x < region.right && it.y > region.top && it.y < region.bottom }
            if (value != null) {
                result.removeAll(inside.toSet())
                result += OcrWord(value.first, region.left, region.lineTop, region.right, region.lineBottom, value.second)
            } else if (conflict || blankHours || marker || fixed) {
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
    private suspend fun readCompanyTable(api: TessBaseAPI, printedEngine: () -> TessBaseAPI, onlineFallback: OnlineCellFallback?, bitmap: Bitmap, original: List<OcrWord>, header: CompanyReportTemplate.Header,
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
            NumericField.DATE -> ReportDateText.parse(text)
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
            var hoursBlank = false
            val readings = regions.associate { region ->
                val rect = GridRect(region.left, region.top, region.right, region.bottom)
                if (region.field == NumericField.HOURS && blankCell(bitmap, rect)) {
                    hoursBlank = true
                    diagnostics?.invoke("Fixed-form HOURS: actual blank pixels; no zero or OCR value substituted")
                    return@associate region.field to ("" to 0f)
                }
                val raw = read(rect, when (region.field) {
                    NumericField.DATE, NumericField.HOURS -> "" // Preserve labels, dots/dashes and unreadable letters.
                    NumericField.COUNT, NumericField.ORDINAL, NumericField.KM -> digits + ".٫,٬"
                }, singleToken = region.field != NumericField.DATE)
                if (region.field == NumericField.DATE) dateLabel = raw
                val tess = canonical(region.field, raw.first)
                val pixel = pixels[region]?.takeIf { canonical(region.field, it.text) != null }
                // A qualified glyph or >=85 native reading is already strong.
                // Classic remains bounded/lazy, but a sole 75..84 LSTM candidate
                // can now be used when no stronger evidence contradicts it.
                val classic = if (pixel == null && !(tess != null && raw.second >= 85f))
                    printed.read(bitmap, region, diagnostics) { coroutine.ensureActive() } else null
                val evidence = mutableListOf<CompanyCellPolicy.Evidence>().apply {
                    add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.TESSERACT, raw.first, raw.second))
                    pixel?.let { add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.GLYPH, it.text, it.confidence)) }
                    classic?.let { add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.CLASSIC, it.text, it.confidence)) }
                }
                var decision = CompanyCellPolicy.decide(region.field, evidence, strongViewConflict = classic?.conflict == true)
                if (decision.text.isEmpty() || decision.conflict) {
                    val online = onlineFallback?.read(GridRect(region.left, region.top, region.right, region.bottom), region.field != NumericField.DATE,
                        if (region.field == NumericField.DATE) "" else "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩.٫,٬")
                    if (online != null && canonical(region.field, online.first) != null) {
                        evidence.add(CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.ONLINE, online.first, online.second))
                        decision = CompanyCellPolicy.decide(region.field, evidence, strongViewConflict = classic?.conflict == true)
                        diagnostics?.invoke("Fixed-form ${region.field}: optional online reading used for the still-unknown cell")
                    }
                }
                val marker = region.field == NumericField.HOURS && raw.first.trim() in listOf(".", "-")
                if (marker) {
                    // A printed dot/dash is not evidence of numeric zero. If
                    // another reader sees a number, keep the ambiguity explicit.
                    hoursBlank = decision.text.isEmpty() && !decision.conflict
                    decision = CompanyCellPolicy.Decision(conflict = decision.text.isNotEmpty() || decision.conflict)
                }
                diagnostics?.invoke("Fixed-form ${region.field}: tess=$raw; pixels=$pixel; printed=$classic; conflict=${decision.conflict}; accepted=$decision")
                region.field to (decision.text to decision.confidence)
            }
            fun value(field: NumericField) = readings[field]?.first.orEmpty()
            fun text(column: CompanyReportTemplate.Column) = row.cells[column]?.let { read(it).first }.orEmpty()
            val origin = text(CompanyReportTemplate.Column.ORIGIN)
            val destination = text(CompanyReportTemplate.Column.DESTINATION)
            // A route containing «جمع» must not erase a real service row.
            val total = mergedFooter || (footerEvidence && value(NumericField.DATE).isBlank() && value(NumericField.ORDINAL).isBlank() && dateLabel.second >= 70 && ReportLabels.footerOnly(dateLabel.first))
            val raw = "ردیف ${value(NumericField.ORDINAL)} | تاریخ ${value(NumericField.DATE)} | $origin ← $destination | کیلومتر ${value(NumericField.KM)} | ساعت ${value(NumericField.HOURS)}" + if (total) " | جمع" else ""
            output += CompanyOcrRow(index, raw, value(NumericField.DATE), value(NumericField.ORDINAL), value(NumericField.KM), value(NumericField.HOURS),
                origin, destination, (listOf(NumericField.DATE, NumericField.KM) + if (value(NumericField.HOURS).isNotBlank()) listOf(NumericField.HOURS) else emptyList())
                    .minOf { readings[it]?.second ?: 0f }, total, hoursBlank)
            if (total) break
        }
        return output
    }

    private fun blankCell(bitmap: Bitmap, rect: GridRect): Boolean {
        val l = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val t = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((rect.right * bitmap.width).toInt() - l).coerceIn(1, bitmap.width - l)
        val h = ((rect.bottom * bitmap.height).toInt() - t).coerceIn(1, bitmap.height - t)
        if (w.toLong() * h > PrintedCellRaster.MAX_PIXELS) return false
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, l, t, w, h)
        return PrintedCellRaster.blank(PrintedCellRaster.grey(w, h, pixels))
    }

    /**
     * Words returned by the explicitly-consented online service for THIS page.
     * Intersecting word boxes are re-read with the same bounds/cancellation-safe
     * cells; falling back to a single cell crop on the shared engine never sends
     * anything itself and never overrides a strong conflict or a blank cell.
     */
    private class OnlineCellFallback(private val api: TessBaseAPI, private val bitmap: Bitmap, private val words: List<OcrWord>) {
        fun read(rect: GridRect, singleToken: Boolean, whitelist: String): Pair<String, Float>? {
            val inside = words.filter { it.x > rect.left && it.x < rect.right && it.y > rect.top && it.y < rect.bottom }
            if (inside.isNotEmpty()) return inside.sortedByDescending { it.x }.joinToString("") { it.text } to inside.minOf { it.confidence }
            val l = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val t = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val w = ((rect.right * bitmap.width).toInt() - l).coerceIn(1, bitmap.width - l)
            val h = ((rect.bottom * bitmap.height).toInt() - t).coerceIn(1, bitmap.height - t)
            api.setImage(bitmap) // Same preprocessed page the offline path already used.
            api.pageSegMode = if (singleToken) TessBaseAPI.PageSegMode.PSM_SINGLE_WORD else TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            api.setVariable("tessedit_char_whitelist", whitelist)
            api.setRectangle(l, t, w, h)
            val text = api.getUTF8Text().orEmpty().trim()
            return (text to api.meanConfidence().toFloat()).takeIf { text.isNotEmpty() }
        }
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
