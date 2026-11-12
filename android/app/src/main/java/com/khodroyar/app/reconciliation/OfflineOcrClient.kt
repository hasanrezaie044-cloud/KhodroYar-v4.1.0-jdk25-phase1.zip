package com.khodroyar.app.reconciliation

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No HTTP client, backend, key or fallback. The optional companion must share our certificate. */
class OfflineOcrClient(private val context: Context) {
    companion object { const val PACKAGE = "com.khodroyar.ocr" }
    fun installed(): Boolean = context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
    fun openSetup() {
        check(installed()) { "افزونهٔ OCR فارسیِ هم‌امضا را از بستهٔ همین انتشار نصب کنید؛ ورود دستی بدون افزونه هم ممکن است." }
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: throw IllegalStateException("افزونهٔ اختیاری OCR فارسی را از بستهٔ همین انتشار نصب کنید؛ یا اطلاعات را دستی وارد کنید.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun recognize(pageId: String, file: File): OcrPage {
        check(installed()) { "افزونهٔ OCR فارسیِ هم‌امضا نصب نیست. داده‌ها به هیچ سرویس آنلاینی ارسال نمی‌شوند؛ ورود دستی همچنان ممکن است." }
        val image = StatementFiles(context).uri(file)
        context.grantUriPermission(PACKAGE, image, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val result = withTimeout(180000) { request(image) }
            return withContext(Dispatchers.IO) {
                require(result.scheme == "content" && result.authority == "$PACKAGE.files")
                val raw = requireNotNull(context.contentResolver.openInputStream(result)).use { input ->
                    val bytes = input.readBytesBounded(8 * 1024 * 1024)
                    bytes.toString(Charsets.UTF_8)
                }
                val json = JSONObject(raw)
                require(json.getInt("protocol") == 1)
                val words = json.getJSONArray("words")
                require(words.length() <= 30000)
                OcrPage(pageId, json.getInt("width"), json.getInt("height"), (0 until words.length()).map { i ->
                    val w = words.getJSONObject(i)
                    OcrWord(w.getString("text").take(2000), w.getInt("confidence").coerceIn(0, 100), RowArea(w.getInt("left"), w.getInt("top"), w.getInt("right"), w.getInt("bottom")))
                })
            }
        } finally { context.revokeUriPermission(image, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private suspend fun request(image: Uri): Uri = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var bound = false
            lateinit var connection: ServiceConnection
            fun finish(uri: Uri?, error: Throwable?) {
                if (bound) { context.unbindService(connection); bound = false }
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error) else continuation.resume(requireNotNull(uri))
            }
            val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    val error = msg.data.getString("error")
                    val result = msg.data.getString("result")
                    if (msg.what != 1 || error != null || result == null) finish(null, IllegalStateException(error ?: "پاسخ OCR معتبر نیست"))
                    else finish(Uri.parse(result), null)
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    try { Messenger(binder).send(Message.obtain(null, 1).apply {
                        data = Bundle().apply { putString("image", image.toString()) }; replyTo = reply
                    }) } catch (e: RemoteException) { finish(null, e) }
                }
                override fun onServiceDisconnected(name: ComponentName) { finish(null, IllegalStateException("پردازش OCR متوقف شد؛ صفحه را دوباره بررسی کنید.")) }
                override fun onNullBinding(name: ComponentName) { finish(null, IllegalStateException("افزونه آماده نیست")) }
                override fun onBindingDied(name: ComponentName) { finish(null, IllegalStateException("اتصال محلی OCR قطع شد")) }
            }
            try {
                bound = context.bindService(Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.PersianOcrService")), connection, Context.BIND_AUTO_CREATE)
                if (!bound) finish(null, IllegalStateException("راه‌اندازی افزونه OCR انجام نشد"))
            } catch (e: Exception) { finish(null, e) }
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (bound) { context.unbindService(connection); bound = false } } }
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32768)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "فایل بیش از حد بزرگ است" }; out.write(buffer, 0, n) }
    return out.toByteArray()
}
