package com.khodroyar.app.workreport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/** App-private SAF imports; no media/storage permission and no network client. */
data class ReportPage(val id: String, val file: String, val name: String, val pdfPage: Int = -1, val rotation: Int = 0)
class ReportDocuments(private val context: Context) {
    val directory = File(context.noBackupFilesDir, "work-report").also { it.mkdirs() }
    private fun source(page: ReportPage): File {
        require(Regex("[a-f0-9-]+\\.(pdf|img)").matches(page.file))
        return File(directory, page.file)
    }
    suspend fun import(uris: List<Uri>, existing: List<ReportPage>): List<ReportPage> {
        val result = mutableListOf<ReportPage>()
        val staged = mutableListOf<File>()
        try {
            for (uri in uris) {
                currentCoroutineContext().ensureActive()
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.take(100) else null
                } ?: "گزارش"
                val pdf = context.contentResolver.getType(uri) == "application/pdf" || name.endsWith(".pdf", true)
                val file = File(directory, UUID.randomUUID().toString() + if (pdf) ".pdf" else ".img")
                staged += file
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536); var bytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n
                            require(bytes <= 100_000_000L) { "حجم هر سند حداکثر ۱۰۰ مگابایت است." }
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: error("فایل انتخاب‌شده قابل خواندن نیست.")
                require(directory.listFiles().orEmpty().filter { it.extension in setOf("pdf", "img") }.sumOf { it.length() } <= 250_000_000L) {
                    "حجم سندهای این مقایسه از ۲۵۰ مگابایت بیشتر است؛ گزارش را به چند مقایسه تقسیم کنید."
                }
                val count = if (pdf) try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
                } catch (e: Exception) {
                    throw IllegalArgumentException("PDF قابل باز شدن نیست؛ فایل خراب یا رمزدار است. PDF سالم و بدون رمز انتخاب کنید.", e)
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    require(options.outWidth > 0 && options.outHeight > 0) { "تصویر قابل خواندن نیست؛ PNG یا JPEG انتخاب کنید." }
                    1
                }
                require(existing.size + result.size + count <= MAX_PAGES) { "در هر مقایسه حداکثر $MAX_PAGES صفحه قابل پردازش است." }
                repeat(count) { n -> result += ReportPage(UUID.randomUUID().toString(), file.name, name, if (pdf) n else -1) }
            }
            return existing + result
        } catch (e: Throwable) { staged.forEach { it.delete() }; throw e }
    }

    /** A single bounded bitmap at a time. Thumbnails never decode full camera-resolution images. */
    fun render(page: ReportPage, maxPixels: Int = 6_000_000, maxEdge: Int = 3200): Bitmap {
        val file = source(page)
        val raw = if (page.pdfPage >= 0) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf -> pdf.openPage(page.pdfPage).use { p ->
                    val desired = 220f / 72f
                    val scale = minOf(desired, maxEdge.toFloat() / maxOf(p.width, p.height), sqrt(maxPixels.toFloat() / (p.width.toFloat() * p.height)))
                    Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    }
                } }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "تصویر قابل خواندن نیست." }
            val plan = ReportRasterPlan.of(bounds.outWidth, bounds.outHeight, maxPixels, maxEdge)
            val options = BitmapFactory.Options().apply {
                inSampleSize = plan.sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = true; inDensity = 1_000_000; inTargetDensity = plan.density
            }
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "حافظه یا قالب تصویر مناسب نیست." }
            val bitmap = if (decoded.width.toLong() * decoded.height <= maxPixels && maxOf(decoded.width, decoded.height) <= maxEdge) decoded
                else Bitmap.createScaledBitmap(decoded, plan.width, plan.height, true).also { if (it !== decoded) decoded.recycle() }
            val exif = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
            val matrix = Matrix().apply { when (exif) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
            } }
            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        }
        if (page.rotation == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(page.rotation * 90f) }, true)
            .also { if (it !== raw) raw.recycle() }
    }
    /** Preserve a single-page PDF byte-for-byte (including its text and image
     * resolution). Rasterise only rotated/multipage sources, at up to 6 MP. */
    fun pdfForUpload(page: ReportPage): ByteArray {
        val file = source(page)
        if (page.pdfPage == 0 && page.rotation == 0) {
            val single = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount == 1 }
            }
            if (single) {
                require(file.length() in 1..FastOcrClient.MAX_IMAGE_BYTES.toLong()) { "حجم PDF باید حداکثر ۲۰ مگابایت باشد." }
                return file.readBytes()
            }
        }
        val bitmap = render(page, 6_000_000, 3600)
        val bytes = try { ReportPagePdf.encode(bitmap) } finally { bitmap.recycle() }
        require(bytes.size <= FastOcrClient.MAX_IMAGE_BYTES) { "صفحهٔ باکیفیت برای ارسال بزرگ است؛ PDF یا برش نزدیک‌تر از جدول انتخاب کنید." }
        return bytes
    }

    fun prune(pages: List<ReportPage>) {
        val keep = pages.map { it.file }.toSet()
        directory.listFiles().orEmpty().filter { it.extension in setOf("pdf", "img") && it.name !in keep }.forEach { it.delete() }
    }
    companion object { const val MAX_PAGES = 100 }
}

/** Atomic, bounded recovery of the review draft, not a new Room database or cloud sync. */
class WorkReportDraft(private val directory: File) {
    private val file = android.util.AtomicFile(File(directory, "draft.json"))
    data class Draft(val pages: List<ReportPage>, val recognized: List<OcrPage>, val rows: List<ReportRow>, val start: String, val end: String,
        val links: List<ReportMatchLink> = emptyList(), val periodNote: String = "", val companyCounts: Map<String, Int> = emptyMap())
    fun save(draft: Draft) {
        val j = JSONObject().put("version", 5).put("start", draft.start).put("end", draft.end).put("periodNote", draft.periodNote)
        require(draft.companyCounts.size <= 3660 && draft.companyCounts.all { (date, count) -> com.khodroyar.app.core.text.PersianInput.date(date) == date && count in 0..10000 })
        j.put("companyCounts", JSONObject(draft.companyCounts))
        j.put("links", JSONArray().apply { draft.links.forEach { link -> put(JSONObject().put("company", JSONArray(link.companyIds)).put("app", JSONArray(link.appIds)).put("fingerprint", link.appFingerprint)) } })
        j.put("pages", JSONArray().apply { draft.pages.forEach { p -> put(JSONObject().put("id", p.id).put("file", p.file).put("name", p.name).put("pdf", p.pdfPage).put("rotation", p.rotation)) } })
        j.put("ocr", JSONArray().apply { draft.recognized.forEach { p -> put(JSONObject().put("id", p.id).put("number", p.number).put("text", p.text.take(200000)).put("source", p.source.name).put("incomplete", p.incomplete).put("words", JSONArray().apply {
            p.words.forEach { w -> put(JSONArray(listOf(w.text, w.left, w.top, w.right, w.bottom, w.confidence))) }
        }).put("companyRows", JSONArray().apply { p.companyRows.forEach { r -> put(JSONObject().put("line", r.line).put("raw", r.raw).put("date", r.date)
            .put("ordinal", r.ordinal).put("km", r.km).put("hours", r.hours).put("origin", r.origin).put("destination", r.destination).put("confidence", r.confidence).put("total", r.total).put("hoursBlank", r.hoursBlank)) } })) } })
        j.put("rows", JSONArray().apply { draft.rows.forEach { r -> put(JSONObject().put("id", r.id).put("pageId", r.pageId).put("page", r.page).put("line", r.line).put("raw", r.raw)
            .put("date", r.date).put("count", r.count).put("km", r.km).put("hours", r.hours).put("confidence", r.confidence).put("included", r.included).put("note", r.note).put("duplicate", r.duplicateOf ?: JSONObject.NULL)
            .put("kind", r.kind.name).put("ordinal", r.ordinal).put("origin", r.origin).put("destination", r.destination).put("hoursBlank", r.hoursBlank)) } })
        val data = j.toString().toByteArray(); require(data.size <= 32_000_000) { "گزارش بیش از حد بزرگ است؛ آن را تقسیم کنید." }
        val out = file.startWrite()
        try { out.write(data); file.finishWrite(out) } catch (e: Throwable) { file.failWrite(out); throw e }
    }
    fun load(): Draft? {
        // openRead restores .bak after a crash; checking only the base file first
        // would wrongly discard a perfectly recoverable draft on older Androids.
        val input = try { file.openRead() } catch (e: java.io.FileNotFoundException) {
            if (!file.baseFile.exists() && !File(directory, "draft.json.bak").exists()) return null else throw e
        }
        val j = input.use { require(file.baseFile.length() <= 32_000_000); JSONObject(it.bufferedReader().readText()) }
        require(j.getInt("version") in 1..5)
        val pages = j.getJSONArray("pages").objects().map { p -> ReportPage(p.getString("id"), p.getString("file"), p.getString("name"), p.getInt("pdf"), p.getInt("rotation").also { require(it in 0..3) }) }
        require(pages.size <= ReportDocuments.MAX_PAGES)
        val recognized = j.getJSONArray("ocr").objects().map { p ->
            val words = p.getJSONArray("words"); require(words.length() <= 50000)
            val company = p.optJSONArray("companyRows") ?: JSONArray(); require(company.length() <= 10000)
            OcrPage(p.getString("id"), p.getInt("number"), (0 until words.length()).map { i -> words.getJSONArray(i).let { a -> OcrWord(a.getString(0), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), a.getDouble(5).toFloat()) } }, p.getString("text"),
                company.objects().map { r -> CompanyOcrRow(r.getInt("line"), r.getString("raw"), r.getString("date"), r.getString("ordinal"), r.getString("km"), r.getString("hours"), r.optString("origin"), r.optString("destination"), r.getDouble("confidence").toFloat(), r.optBoolean("total"), r.optBoolean("hoursBlank")) },
                source = ReportTextSource.entries.firstOrNull { it.name == p.optString("source") } ?: ReportTextSource.LEGACY,
                incomplete = p.optBoolean("incomplete"))
        }
        require(recognized.size <= pages.size && recognized.sumOf { it.words.size } <= 200000)
        val rowArray = j.getJSONArray("rows"); require(rowArray.length() <= 10000)
        val rows = rowArray.objects().map { r -> ReportRow(r.getString("id"), r.getString("pageId"), r.getInt("page"), r.getInt("line"), r.getString("raw"), r.getString("date"), r.getString("count"), r.getString("km"), r.getString("hours"), r.getDouble("confidence").toFloat(), r.getBoolean("included"), r.getString("note"), if (r.isNull("duplicate")) null else r.getString("duplicate"),
            ReportRowKind.valueOf(r.optString("kind", ReportRowKind.DAILY_TOTAL.name)), r.optString("ordinal"), r.optString("origin"), r.optString("destination"), r.optBoolean("hoursBlank")) }
        val linkArray = j.optJSONArray("links") ?: JSONArray(); require(linkArray.length() <= 10000)
        val links = linkArray.objects().map { link ->
            fun ids(name: String) = link.getJSONArray(name).let { a -> require(a.length() <= 10000); (0 until a.length()).map { a.getString(it) } }
            ReportMatchLink(ids("company"), ids("app"), link.getString("fingerprint"))
        }
        require(links.sumOf { it.companyIds.size } <= 10000 && links.sumOf { it.appIds.size } <= 10000)
        val counts = j.optJSONObject("companyCounts") ?: JSONObject()
        require(counts.length() <= 3660)
        val companyCounts = counts.keys().asSequence().associateWith { date ->
            require(com.khodroyar.app.core.text.PersianInput.date(date) == date)
            val value = counts.get(date)
            require(value is Int && value in 0..10000)
            value
        }
        return Draft(pages, recognized, rows, j.optString("start"), j.optString("end"), links, j.optString("periodNote"), companyCounts)
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
