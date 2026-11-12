package com.khodroyar.app.workreport

import android.content.Context
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
    @Volatile private var active: TessBaseAPI? = null
    fun stop() { active?.stop() }

    suspend fun recognize(pages: List<ReportPage>, sparse: Boolean, progress: (String) -> Unit): List<OcrPage> = withContext(Dispatchers.IO) {
        engineLock.withLock {
            require(pages.isNotEmpty()) { "ابتدا تصویر یا PDF انتخاب کنید." }
            val modelDirectory = installModels()
            currentCoroutineContext().ensureActive()
            val documents = ReportDocuments(context)
            val api = TessBaseAPI { value -> progress("خواندن آفلاین · ${value.percent}٪ از صفحهٔ جاری") }
            active = api
            try {
                check(api.init(modelDirectory.path, "fas+ara+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "باز کردن مدل‌های آفلاین OCR انجام نشد." }
                api.setVariable("preserve_interword_spaces", "1")
                api.setVariable("user_defined_dpi", "220")
                api.pageSegMode = if (sparse) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT else TessBaseAPI.PageSegMode.PSM_AUTO
                val result = mutableListOf<OcrPage>()
                pages.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    progress("صفحهٔ ${index + 1} از ${pages.size} · آماده‌سازی تصویر")
                    val bitmap = documents.render(page)
                    try {
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
                        result += OcrPage(page.id, index + 1, words, text)
                    } finally { api.clear(); bitmap.recycle() }
                }
                result
            } finally { active = null; api.recycle() }
        }
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
