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

/** All recognition is JNI Tesseract on this device, with three models bundled in the APK. */
class OfflineReportOcr(private val context: Context) {
    private val activeLock = Any()
    private var active: TessBaseAPI? = null
    // stop() may run on the UI thread while the IO coroutine finishes. Serialize
    // only lifecycle/stop, never recognition, so a recycled JNI pointer is not used.
    fun stop() { synchronized(activeLock) { active?.stop() } }

    suspend fun recognize(pages: List<ReportPage>, sparse: Boolean, diagnostics: ((String) -> Unit)? = null, progress: (String) -> Unit): List<OcrPage> = withContext(Dispatchers.IO) {
        engineLock.withLock {
            require(pages.isNotEmpty()) { "ابتدا تصویر یا PDF انتخاب کنید." }
            val modelDirectory = installModels()
            currentCoroutineContext().ensureActive()
            val documents = ReportDocuments(context)
            val api = TessBaseAPI { value -> progress("خواندن آفلاین · ${value.percent}٪ از صفحهٔ جاری") }
            try {
                check(api.init(modelDirectory.path, "fas+ara+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "باز کردن مدل‌های آفلاین OCR انجام نشد." }
                synchronized(activeLock) { active = api }
                currentCoroutineContext().ensureActive()
                api.setVariable("preserve_interword_spaces", "1")
                api.setVariable("user_defined_dpi", "220")
                val result = mutableListOf<OcrPage>()
                pages.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    progress("صفحهٔ ${index + 1} از ${pages.size} · آماده‌سازی تصویر")
                    currentCoroutineContext().ensureActive()
                    val bitmap = documents.render(page)
                    try {
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
                        val refined = refineTable(api, bitmap, words, diagnostics)
                        result += OcrPage(page.id, index + 1, refined, text)
                    } finally { api.clear(); bitmap.recycle() }
                    require(result.sumOf { it.words.size } <= 200000) { "متن این مقایسه بیش از حد بزرگ است؛ سند را به چند بخش تقسیم کنید." }
                }
                result
            } finally { synchronized(activeLock) { active = null; api.recycle() } }
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
                NumericField.COUNT -> digits + ".٫,٬"
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

    private fun installModels(): File {
        val root = File(context.noBackupFilesDir, "offline-ocr")
        val target = File(root, "tessdata").also { it.mkdirs() }
        val manifest = JSONObject(context.assets.open("ocr/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i); val name = entry.getString("name")
            if (!name.endsWith(".traineddata")) continue
            require(name in listOf("fas.traineddata", "ara.traineddata", "eng.traineddata"))
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
