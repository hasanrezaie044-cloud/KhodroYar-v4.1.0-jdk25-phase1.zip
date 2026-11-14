package com.khodroyar.app.workreport

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/** In-memory single-page upload format; no network, files, models or credentials. */
object ReportPagePdf {
    fun encode(bitmap: Bitmap): ByteArray {
        val doc = PdfDocument()
        return try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            doc.finishPage(page)
            ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally { doc.close() }
    }
}
