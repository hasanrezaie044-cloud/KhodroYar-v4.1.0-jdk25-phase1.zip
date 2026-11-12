package com.khodroyar.ocr

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Only the audited official Persian model is accepted; no executable/downloaded code. */
object PersianData {
    const val SIZE = 431500L
    const val SHA256 = "db1c0a91208aff00d3cf1ed2c1d23f76419afd5f024688b4f71adc3f2ce4a505"
    fun root(context: Context) = File(context.filesDir, "tesseract")
    private fun model(context: Context) = File(root(context), "tessdata/fas.traineddata")
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(65536)
            while (true) { val n = stream.read(bytes); if (n < 0) break; sha.update(bytes, 0, n) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    fun ready(context: Context): Boolean = model(context).let { it.isFile && it.length() == SIZE && digest(it) == SHA256 }
    fun import(context: Context, uri: Uri) {
        val target = model(context)
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile, "fas.tmp")
        try {
            val source = requireNotNull(context.contentResolver.openInputStream(uri))
            source.use { input -> tmp.outputStream().use { out ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= SIZE) { "اندازهٔ فایل زبان معتبر نیست" }; out.write(buffer, 0, n) }
            } }
            require(tmp.length() == SIZE && digest(tmp) == SHA256) { "فایل زبان فارسی تأیید نشد؛ fas.traineddata رسمی همراه انتشار را انتخاب کنید." }
            check(tmp.renameTo(target)) { "ذخیرهٔ فایل زبان انجام نشد" }
        } finally { tmp.delete() }
    }
}
