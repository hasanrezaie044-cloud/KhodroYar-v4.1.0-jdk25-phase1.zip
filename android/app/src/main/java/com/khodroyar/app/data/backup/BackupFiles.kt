package com.khodroyar.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.khodroyar.app.core.jalali.Jalali
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Real device file storage for backups — the native equivalent of the legacy
 * StorageAccessFramework path.
 *
 * Share alone is NOT a backup: [writeToUri] persists the JSON to a folder the user
 * picked through the system document picker (ACTION_CREATE_DOCUMENT), which needs no
 * storage permission and survives app uninstall. Share is kept as an extra route.
 */
object BackupFiles {

    const val MIME_JSON = "application/json"

    fun suggestedFileName(): String {
        val date = Jalali.todayString().replace("/", "-")
        val stamp = System.currentTimeMillis()
        return "car-backup-$date-$stamp.json"
    }

    /** Writes [content] into the user-picked document. Returns false on any failure. */
    fun writeToUri(context: Context, uri: Uri, content: String): Boolean {
        if (content.isEmpty()) return false
        val ok = try {
            // Some document providers reject the non-default "wt" mode. The default
            // mode is the most interoperable SAF write mode; the newly-created document
            // is already empty, so truncation is unnecessary.
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("No output stream for SAF document")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!ok) runCatching { context.contentResolver.delete(uri, null, null) }
        return ok
    }

    /** Streams an already-created backup file to SAF without buffering another full copy in RAM. */
    fun writeToUri(context: Context, uri: Uri, source: File): Boolean {
        if (!source.isFile || source.length() <= 0L) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return false
        }
        val ok = try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("No output stream for SAF document")
            var copied = 0L
            BufferedInputStream(FileInputStream(source)).use { input ->
                BufferedOutputStream(output).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                    }
                    out.flush()
                }
            }
            copied == source.length()
        } catch (_: Exception) {
            false
        }
        if (!ok) runCatching { context.contentResolver.delete(uri, null, null) }
        return ok
    }

    /** Creates an atomic cache file so a large backup is not kept in Compose state or copied on UI. */
    fun writeExportFile(context: Context, content: String, fileName: String): File {
        require(content.isNotEmpty()) { "پشتیبان خالی ساخته نشد." }
        val directory = File(context.cacheDir, "backup-exports")
        check(directory.isDirectory || directory.mkdirs()) { "فضای موقت برای ساخت پشتیبان در دسترس نیست." }
        val target = File(directory, fileName)
        check(!target.exists()) { "نام فایل موقت پشتیبان تکراری است." }
        val temporary = File(directory, ".$fileName.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).buffered().use { writer ->
                    writer.write(content)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            check(temporary.length() > 0L && temporary.renameTo(target)) {
                "ذخیرهٔ موقت پشتیبان کامل نشد. فضای خالی دستگاه را بررسی کنید."
            }
            return target
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    fun readFromUri(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (e: Exception) {
        null
    }

    /** Keeps an internal copy for automatic-backup fallback; this folder is removed on uninstall. */
    fun writeInternalCopy(context: Context, content: String, fileName: String): File? = try {
        val dir = File(context.filesDir, "VehicleManager").apply { mkdirs() }
        val temporary = File(dir, ".$fileName.${UUID.randomUUID()}.tmp")
        val target = File(dir, fileName)
        FileOutputStream(temporary).use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).buffered().use { writer ->
                writer.write(content)
                writer.flush()
                stream.fd.sync()
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            null
        } else target
    } catch (e: Exception) {
        null
    }

    fun shareText(context: Context, content: String, fileName: String, chooserTitle: String): Boolean = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeText(content) }
        shareFile(context, file, MIME_JSON, chooserTitle)
        true
    } catch (e: Exception) {
        false
    }

    fun shareFile(context: Context, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pdf" -> "application/pdf"
        "json" -> MIME_JSON
        else -> "application/octet-stream"
    }
}
