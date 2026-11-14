package com.khodroyar.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

class ChatImageUnavailable : IOException("Local chat image unavailable")

/** System URI grants only; no broad storage/camera permission. Original metadata is never kept. */
class ChatImageStore(context: Context) {
    private val app = context.applicationContext
    // Saved chat references must survive Android cache eviction, but never enter backup/export.
    private val root get() = File(app.noBackupFilesDir, "ai-chat-images").apply { mkdirs() }
    fun file(ref: ChatImageRef): File = File(root, ref.fileName)
    fun cameraUri(): Uri {
        val exports = File(app.cacheDir, "exports").apply { mkdirs() }
        exports.listFiles()?.filter { it.name.startsWith("ai-camera-") && System.currentTimeMillis() - it.lastModified() > 2 * 86400000L }?.forEach { it.delete() }
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", File(exports, "ai-camera-${UUID.randomUUID()}.jpg"))
    }
    /** Deletes only this flow's own camera staging file, never an external provider's chosen file. */
    fun discardCamera(uri: Uri) {
        val name = uri.lastPathSegment.orEmpty()
        if (uri.authority == "${app.packageName}.fileprovider" && Regex("ai-camera-[A-Za-z0-9-]+\\.jpg").matches(name))
            File(app.cacheDir, "exports/$name").delete()
    }
    suspend fun prepare(uri: Uri): ChatImageRef = withContext(Dispatchers.IO) {
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                // Decoder memory/hostile-file protection, not a token or account allowance.
                require(result.size() + n <= MAX_SOURCE_BYTES); result.write(buffer, 0, n)
            }
            result.toByteArray()
        } ?: throw ChatImageUnavailable()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            inSampleSize = sample
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
            }
        }
        var transformed: Bitmap? = null
        val ref = ChatImageRef("${UUID.randomUUID()}.jpg")
        try {
            val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { transformed = it }
            file(ref).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            require(file(ref).length() in 1L..MAX_WIRE_BYTES)
            ref
        } catch (e: Exception) { file(ref).delete(); throw e }
        finally { transformed?.takeIf { it !== decoded }?.recycle(); decoded.recycle() }
        // A fresh JPEG contains no original EXIF/GPS/name or original URI.
    }
    suspend fun wire(ref: ChatImageRef): AIImage = withContext(Dispatchers.IO) {
        val f = file(ref)
        if (!f.isFile || f.length() !in 1L..MAX_WIRE_BYTES) throw ChatImageUnavailable()
        AIImage("image/jpeg", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
    }
    fun discard(ref: ChatImageRef) { file(ref).delete() }
    fun prune(retained: Set<ChatImageRef>) {
        val names = retained.map { it.fileName }.toSet()
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") && it.name !in names }?.forEach { it.delete() }
    }
    companion object {
        const val MAX_SOURCE_BYTES = 24 * 1024 * 1024
        private const val MAX_WIRE_BYTES = 18L * 1024 * 1024 // base64 fits the 24 MiB structural bound.
    }
}
