package com.khodroyar.app.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.khodroyar.app.data.db.RecordAttachmentEntity
import java.io.File
import java.util.UUID

/** Private, owned image copies for record attachments. Gallery URIs are never persisted. */
object RecordAttachmentFiles {
    private const val DIR = "record-attachments"
    private const val MAX_BYTES = 32L * 1024 * 1024

    fun import(context: Context, uri: Uri): RecordAttachmentEntity? = runCatching {
        val resolver = context.applicationContext.contentResolver
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: return null
        val extension = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        val input = resolver.openInputStream(uri) ?: return null
        input.use { source ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES) { "Image is too large" }
                    output.write(buffer, 0, n)
                }
            }
        }
        require(file.length() > 0L)
        RecordAttachmentEntity(UUID.randomUUID().toString(), "", "", file.absolutePath, mime, System.currentTimeMillis())
    }.getOrNull()

    fun isOwned(context: Context, path: String): Boolean = runCatching {
        val target = File(path).canonicalFile
        val roots = listOf(File(context.filesDir, DIR), File(context.filesDir, "attachments"))
            .map { it.canonicalFile.path + File.separator }
        target.isFile && roots.any { target.path.startsWith(it) }
    }.getOrDefault(false)

    fun deleteOwned(context: Context, path: String): Boolean =
        if (isOwned(context, path)) runCatching { File(path).delete() }.getOrDefault(false) else false

    fun restoreToGallery(context: Context, attachment: RecordAttachmentEntity): Uri? {
        if (!isOwned(context, attachment.filePath)) return null
        val source = File(attachment.filePath)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Images.Media.MIME_TYPE, attachment.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KhodroYar")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("Could not open gallery destination")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
