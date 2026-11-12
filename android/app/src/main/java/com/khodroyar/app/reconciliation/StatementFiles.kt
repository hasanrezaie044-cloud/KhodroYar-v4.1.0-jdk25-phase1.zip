package com.khodroyar.app.reconciliation

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private app files only. No media/storage/camera permission and no third-party PDF library. */
data class ImportedStatementPage(
    val id: String,
    val fileName: String,
    val rotation: Int = 0,
    val deskew: Float = 0f,
    val enhance: Boolean = false,
    val reviewed: Boolean = false,
    val processed: Boolean = false,
)

class StatementFiles(private val context: Context) {
    val directory = File(context.filesDir, "reconciliation").apply { mkdirs() }
    fun image(page: ImportedStatementPage) = File(directory, page.fileName)
    fun rendered(page: ImportedStatementPage) = File(directory, "${page.id}-review.jpg")
    fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    private fun write(bitmap: Bitmap): ImportedStatementPage {
        val id = UUID.randomUUID().toString()
        val page = ImportedStatementPage(id, "$id.jpg")
        image(page).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
        return page
    }

    /** A bounded raster prevents untrusted PDFs/images from exhausting heap in a long document. */
    fun import(uri: Uri, capacity: Int): List<ImportedStatementPage> {
        require(capacity > 0) { "حداکثر ۴۰ صفحه در هر بررسی" }
        val copy = File(directory, "import-${UUID.randomUUID()}.tmp")
        val created = mutableListOf<ImportedStatementPage>()
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> copy.outputStream().use { output ->
                val buffer = ByteArray(65536); var size = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= 80L * 1024 * 1024) { "فایل باید کوچک‌تر از ۸۰ مگابایت باشد" }; output.write(buffer, 0, n) }
            } }
            val pdf = copy.inputStream().use { input -> ByteArray(5).also { input.read(it) } }.contentEquals("%PDF-".toByteArray())
            if (pdf) {
                ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                    require(renderer.pageCount <= capacity) { "تعداد صفحات از ظرفیت باقی‌مانده بیشتر است؛ PDF را تقسیم کنید." }
                    repeat(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                        val scale = 2800.0 / maxOf(page.width, page.height)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); created += write(bitmap) } finally { bitmap.recycle() }
                    } }
                } }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(copy.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "تصویر قابل خواندن نیست؛ تصویر یا PDF معتبر انتخاب کنید." }
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2800) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                val bitmap = requireNotNull(BitmapFactory.decodeFile(copy.absolutePath, options))
                try {
                    val orientation = runCatching { ExifInterface(copy.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                    val matrix = Matrix().apply {
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                        }
                    }
                    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    try { created += write(oriented) } finally { if (oriented !== bitmap) oriented.recycle() }
                } finally { bitmap.recycle() }
            }
            return created
        } catch (error: Throwable) { created.forEach { image(it).delete() }; throw error
        } finally { copy.delete() }
    }

    fun fingerprint(page: ImportedStatementPage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        image(page).inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Manual deskew and mild contrast are reversible; original raster is retained for review. */
    fun prepare(page: ImportedStatementPage): File {
        val original = requireNotNull(BitmapFactory.decodeFile(image(page).absolutePath))
        var rotated: Bitmap? = null
        var enhanced: Bitmap? = null
        try {
            val matrix = Matrix().apply { postRotate(page.rotation + page.deskew) }
            rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            val source = requireNotNull(rotated)
            val result = if (page.enhance || page.deskew != 0f) {
                enhanced = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val colors = ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix(floatArrayOf(1.15f,0f,0f,0f,-12f, 0f,1.15f,0f,0f,-12f, 0f,0f,1.15f,0f,-12f, 0f,0f,0f,1f,0f))) }
                Canvas(enhanced!!).apply { drawColor(Color.WHITE); drawBitmap(source, 0f, 0f, Paint().apply { if (page.enhance) colorFilter = ColorMatrixColorFilter(colors) }) }
                enhanced!!
            } else source
            val output = rendered(page)
            output.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
            return output
        } finally {
            enhanced?.recycle()
            if (rotated !== original) rotated?.recycle()
            original.recycle()
        }
    }

    fun remove(page: ImportedStatementPage) { image(page).delete(); rendered(page).delete() }
}
