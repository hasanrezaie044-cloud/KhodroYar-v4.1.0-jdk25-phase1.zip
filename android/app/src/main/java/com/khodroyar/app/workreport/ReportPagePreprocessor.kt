package com.khodroyar.app.workreport

import android.graphics.Bitmap

/** One full-resolution page; source files and review previews are never changed. */
internal object ReportPagePreprocessor {
    fun prepare(source: Bitmap, diagnostics: ((String) -> Unit)? = null, checkCancelled: () -> Unit = {}): Bitmap {
        val working = ReportImageGrid.deskew(source, checkCancelled)
        try {
            checkCancelled()
            val table = ReportImageGrid.ink(working, checkCancelled = checkCancelled).tableBounds(checkCancelled)
            // One reusable full-resolution array. Avoid three 24 MB copies
            // alongside the Android bitmap and two offline OCR engines.
            val pixels = IntArray(working.width * working.height)
            working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
            ReportPageRaster.illuminated(working.width, working.height, pixels, checkCancelled, inPlace = true)
            ReportPageRaster.tableOnly(working.width, working.height, pixels, table, checkCancelled, inPlace = true)
            diagnostics?.invoke("Preprocessing: bounded deskew/local paper gain; conservative ruled ROI=$table; source-resolution=${working.width}x${working.height}; no denoise/erosion")
            checkCancelled()
            if (working.isMutable) {
                working.setPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
                return working
            }
            val output = Bitmap.createBitmap(pixels, working.width, working.height, Bitmap.Config.ARGB_8888)
            working.recycle()
            return output
        } catch (e: Throwable) {
            if (working !== source) working.recycle()
            throw e
        }
    }
}
