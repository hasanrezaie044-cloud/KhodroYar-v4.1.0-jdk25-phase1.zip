package com.khodroyar.ocr

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Signature-protected, explicit bound service. Protocol 1 exchanges content URIs, not bitmaps. */
class PersianOcrService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val messenger by lazy { Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo ?: return
            val uid = msg.sendingUid
            val callers = packageManager.getPackagesForUid(uid).orEmpty()
            if (msg.what != 1 || "com.khodroyar.app" !in callers || packageManager.checkSignatures(uid, applicationInfo.uid) != PackageManager.SIGNATURE_MATCH) return
            val source = msg.data.getString("image") ?: return
            val uri = Uri.parse(source)
            if (uri.scheme != "content" || uri.authority != "com.khodroyar.app.fileprovider") return
            worker.execute { recognize(uri, reply) }
        }
    }) }
    override fun onBind(intent: Intent) = messenger.binder

    private fun recognize(uri: Uri, reply: Messenger) {
        val response = Message.obtain(null, 1)
        try {
            check(PersianData.ready(this)) { "ابتدا افزونهٔ OCR فارسی را باز کنید و فایل زبان رسمی را وارد کنید." }
            // The main app supplies a bounded raster; independently reject unexpected dimensions.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 12_000_000) { "تصویر بیش از حد بزرگ یا نامعتبر است." }
            val bitmap = contentResolver.openInputStream(uri)!!.use { requireNotNull(BitmapFactory.decodeStream(it)) }
            val tess = TessBaseAPI()
            try {
                check(tess.init(PersianData.root(this).absolutePath, "fas", TessBaseAPI.OEM_LSTM_ONLY)) { "راه‌اندازی OCR فارسی انجام نشد." }
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tess.setImage(bitmap)
                tess.getUTF8Text() // Start recognition before acquiring the word iterator.
                val words = JSONArray()
                val iterator = tess.resultIterator
                if (iterator != null) try {
                    iterator.begin()
                    do {
                        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                        val text = iterator.getUTF8Text(level).orEmpty().trim()
                        val box = iterator.getBoundingRect(level)
                        if (text.isNotEmpty()) words.put(JSONObject().put("text", text).put("confidence", iterator.confidence(level).toInt().coerceIn(0, 100))
                            .put("left", box.left).put("top", box.top).put("right", box.right).put("bottom", box.bottom))
                        require(words.length() <= 30000) { "متن صفحه بیش از حد طولانی است." }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally { iterator.delete() }
                val dir = File(cacheDir, "results").apply { mkdirs(); listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3600000 }?.forEach { it.delete() } }
                val file = File(dir, UUID.randomUUID().toString() + ".json")
                file.writeText(JSONObject().put("protocol", 1).put("width", bitmap.width).put("height", bitmap.height).put("words", words).toString())
                val result = FileProvider.getUriForFile(this, "com.khodroyar.ocr.files", file)
                grantUriPermission("com.khodroyar.app", result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                response.data = Bundle().apply { putString("result", result.toString()) }
            } finally { tess.recycle(); bitmap.recycle() }
        } catch (_: OutOfMemoryError) {
            response.data = Bundle().apply { putString("error", "حافظه کافی نیست؛ تصویر کوچک‌تر انتخاب کنید.") }
        } catch (e: Exception) {
            response.data = Bundle().apply { putString("error", e.message ?: "OCR انجام نشد؛ هیچ مقدار فرضی تولید نشد.") }
        }
        try { reply.send(response) } catch (_: RemoteException) { /* Client left; result expires locally. */ }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
