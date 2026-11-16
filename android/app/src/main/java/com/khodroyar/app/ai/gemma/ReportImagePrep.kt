package com.khodroyar.app.ai.gemma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Prepares a user-picked photo for the Gemma vision input.
 *
 * Rules from the spec, implemented:
 *  - aspect ratio is ALWAYS preserved (no cropping);
 *  - downscaling is bounded: the long side caps at [MAX_LONG_SIDE] px, which keeps lid on
 *    LiteRT-LM's image-patch budget while surviving small Persian table text — the value
 *    was chosen after the runtime's documented patch limit (≈2520 patches) so a full-page
 *    A4 report stays readable;
 *  - output is JPEG, because LiteRT-LM accepts JPEG/PNG bytes (WebP is rejected by it);
 *  - EXIF rotation is applied so rotated phone photos OCR correctly;
 *  - the ORIGINAL picked file is never modified; a thrown-out-of-memory decode degrades
 *    to a clearer error instead of crashing the process.
 */
object ReportImagePrep {

    const val MAX_LONG_SIDE = 1280
    private const val JPEG_QUALITY = 88
    private const val THUMB_SIDE = 220

    data class Prepared(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        /** Small JPEG for the chat bubble thumbnail (taken from the same scaled bitmap). */
        val thumbnailBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Prepared && jpegBytes.contentEquals(other.jpegBytes)
        override fun hashCode(): Int = jpegBytes.size
    }

    suspend fun load(context: Context, uri: Uri): Prepared? = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= MAX_LONG_SIDE) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0f
            val scale = MAX_LONG_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            if (rotation != 0f || scale < 1f) {
                val m = Matrix()
                if (scale < 1f) m.postScale(scale, scale)
                if (rotation != 0f) m.postRotate(rotation)
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                if (rotated != bitmap) bitmap.recycle()
                bitmap = rotated
            }
            val bytes = bitmap.toJpeg(JPEG_QUALITY)
            val thumbScale = THUMB_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val thumb = if (thumbScale < 1f) {
                val m = Matrix().apply { postScale(thumbScale, thumbScale) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap
            val thumbBytes = thumb.toJpeg(80)
            if (thumb != bitmap) thumb.recycle()
            val result = Prepared(bytes, bitmap.width, bitmap.height, thumbBytes)
            bitmap.recycle()
            result
        } catch (oom: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val out = ByteArrayOutputStream(96 * 1024)
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun exifRotation(ins: java.io.InputStream): Float = try {
        when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
}
