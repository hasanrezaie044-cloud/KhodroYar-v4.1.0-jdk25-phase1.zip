package com.khodroyar.app.data.attachments

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photos attached to a service (or any record with an `attachmentPath`).
 *
 * A picked image is copied into the app's own `files/attachments` folder — already
 * declared in file_paths.xml, so it can be opened in the gallery app — shrunk to at
 * most [MAX_SIDE] px on its longer side and re-saved as JPEG, with the EXIF rotation
 * applied. Gallery originals are often 5–10 MB; a service receipt photo does not need
 * that, and the phone's storage does.
 *
 * Only the path is stored in the database and in backup JSON. The image files are NOT
 * part of the JSON backup, so after restoring on another phone a path can point at a
 * file that is not there — callers must treat a missing file as "no photo", see [exists].
 */
object Attachments {

    private const val DIR = "attachments"
    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 85

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** True only for files inside our own attachments folder (never delete anything else). */
    fun isOwn(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            File(path).canonicalPath.startsWith(dir(context).canonicalPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    fun exists(path: String): Boolean = path.isNotBlank() && File(path).isFile

    fun delete(context: Context, path: String) {
        if (isOwn(context, path)) runCatching { File(path).delete() }
    }

    /** @return absolute path of the stored copy, or null if the image could not be read. */
    suspend fun importImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val out = File(dir(context), "att_${System.currentTimeMillis()}.jpg")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withContext copyRaw(context, uri, out)

            var sample = 1
            while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext copyRaw(context, uri, out)

            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0f
            var bitmap = decoded
            val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            if (scale < 1f || rotation != 0f) {
                val matrix = Matrix().apply {
                    if (scale < 1f) postScale(scale, scale)
                    if (rotation != 0f) postRotate(rotation)
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun copyRaw(context: Context, uri: Uri, out: File): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > 0) out.absolutePath else null
    } catch (_: Exception) {
        null
    }

    private fun exifRotation(stream: java.io.InputStream): Float = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }

    /** Small preview bitmap (about [targetPx] on the longer side), or null if unreadable. */
    suspend fun loadPreview(path: String, targetPx: Int = 720): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (!exists(path)) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withContext null
            var sample = 1
            while (longest / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    /** Opens the photo in the phone's gallery/viewer. @return false if nothing could open it. */
    fun openInViewer(context: Context, path: String): Boolean = try {
        if (!exists(path)) false else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "نمایش عکس"))
            true
        }
    } catch (_: Exception) {
        false
    }
}
