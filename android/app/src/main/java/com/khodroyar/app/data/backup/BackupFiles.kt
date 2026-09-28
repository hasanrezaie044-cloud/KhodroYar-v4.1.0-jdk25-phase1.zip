package com.khodroyar.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.khodroyar.app.core.jalali.Jalali
import java.io.File

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
        val stamp = System.currentTimeMillis() / 1000L
        return "car-backup-$date-$stamp.json"
    }

    /** Writes [content] into the user-picked document. Returns false on any failure. */
    fun writeToUri(context: Context, uri: Uri, content: String): Boolean = try {
        // `return` is not allowed inside an expression-body function, so the null
        // stream is handled as a value instead. Behaviour is unchanged: no stream
        // means no backup written, i.e. false.
        val out = context.contentResolver.openOutputStream(uri, "wt")
        if (out == null) false else {
            out.use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            true
        }
    } catch (e: Exception) {
        false
    }

    fun readFromUri(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (e: Exception) {
        null
    }

    /**
     * Also keeps a copy inside the app's own files dir (declared in file_paths.xml),
     * so a backup exists even if the user cancels the folder picker.
     */
    fun writeInternalCopy(context: Context, content: String, fileName: String): File? = try {
        val dir = File(context.filesDir, "VehicleManager").apply { mkdirs() }
        File(dir, fileName).apply { writeText(content) }
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
