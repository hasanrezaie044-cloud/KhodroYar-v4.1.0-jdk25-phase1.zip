package com.khodroyar.app.workreport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sparse page OCR can omit an entire short heading (notably تاریخ/مقصد).
 * Re-read ONLY the strip established by several actual header words. Missing
 * anchors must come from newly recognised label pixels, never guessed column
 * coordinates, a previous page, numeric values, amounts or dates in the rows.
 */
internal class PrintedHeaderOcr(private val primary: TessBaseAPI, private val engine: () -> TessBaseAPI) {
    fun refine(bitmap: Bitmap, original: List<OcrWord>, diagnostics: ((String) -> Unit)? = null,
        checkCancelled: () -> Unit = {}): List<OcrWord> {
        val labels = original.filter { CompanyReportTemplate.column(it.text) != null }
        val bands = WorkReportParser.lines(labels).filter { words ->
            val columns = words.mapNotNull { CompanyReportTemplate.column(it.text) }.toSet()
            CompanyReportTemplate.Column.KM in columns && CompanyReportTemplate.Column.HOURS in columns &&
                columns.any { it in setOf(CompanyReportTemplate.Column.ORDINAL, CompanyReportTemplate.Column.ORIGIN, CompanyReportTemplate.Column.DESTINATION) }
        }.take(4)
        if (bands.isEmpty()) return original
        val result = original.toMutableList()
        for (band in bands) {
            checkCancelled()
            val centre = band.map { it.y }.average().toFloat()
            if (original.any { abs(it.y - centre) < .035f && ReportLabels.has(it.text, "تعداد", "count", "services") }) continue
            val height = band.maxOf { it.bottom - it.top }.coerceAtLeast(.006f)
            val top = ((band.minOf { it.top } - height * .65f).coerceAtLeast(0f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val bottom = ((band.maxOf { it.bottom } + height * .65f).coerceAtMost(1f) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            if (bitmap.width.toLong() * (bottom - top) > PrintedCellRaster.MAX_PIXELS) continue
            val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
            var scaled: Bitmap? = null
            val recovered = mutableListOf<OcrWord>()
            try {
                val scale = minOf(2.0, sqrt(800_000.0 / (crop.width.toDouble() * crop.height)))
                val image = Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt().coerceAtLeast(1), (crop.height * scale).toInt().coerceAtLeast(1), true)
                scaled = image
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                val originalGrey = PrintedCellRaster.grey(image.width, image.height, pixels)
                // Rules can hurt label recognition, but also help the native line
                // segmenter. Neither preparation is an oracle: retain BOTH bounded
                // rasters and require the same label/location checks for either.
                val preparations = listOf(originalGrey, PrintedCellRaster.withoutRules(image.width, image.height, originalGrey))
                for (api in listOf(primary, engine())) {
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                    api.setVariable("tessedit_char_whitelist", "")
                    for (grey in preparations) for (view in PrintedCellRaster.View.entries) {
                        checkCancelled()
                        val content = Bitmap.createBitmap(PrintedCellRaster.pixels(grey, view), image.width, image.height, Bitmap.Config.ARGB_8888)
                        try {
                            val padded = Bitmap.createBitmap(image.width + 24, image.height + 24, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(padded).apply { drawColor(Color.WHITE); drawBitmap(content, 12f, 12f, null) }
                                api.setImage(padded); api.getUTF8Text()
                                checkCancelled()
                                api.resultIterator?.let { iterator ->
                                    try {
                                        iterator.begin()
                                        do {
                                            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                                            val text = iterator.getUTF8Text(level).orEmpty().trim()
                                            val box = iterator.getBoundingBox(level)
                                            val confidence = iterator.confidence(level)
                                            val isLabel = CompanyReportTemplate.column(text) != null || ReportLabels.has(text, "تعداد", "count", "services")
                                            if (isLabel && confidence >= 70 && box != null && box.size == 4) {
                                                // Use the realised raster ratios, including rounding, not an assumed scale.
                                                recovered += OcrWord(text, ((box[0] - 12f) / image.width).coerceIn(0f, 1f),
                                                    ((top + (box[1] - 12f) * crop.height / image.height) / bitmap.height).coerceIn(0f, 1f),
                                                    ((box[2] - 12f) / image.width).coerceIn(0f, 1f),
                                                    ((top + (box[3] - 12f) * crop.height / image.height) / bitmap.height).coerceIn(0f, 1f), confidence)
                                            }
                                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                                    } finally { iterator.delete() }
                                }
                            } finally { api.clear(); padded.recycle() }
                        } finally { content.recycle() }
                    }
                }
            } finally {
                scaled?.takeIf { it !== crop && it !== bitmap }?.recycle()
                if (crop !== bitmap) crop.recycle()
            }
            // A newly readable actual COUNT label vetoes fixed single-service interpretation.
            result += recovered.filter { ReportLabels.has(it.text, "تعداد", "count", "services") }.distinctBy { it.text to it.x }
            for ((column, words) in recovered.filter { CompanyReportTemplate.column(it.text) != null }.groupBy { CompanyReportTemplate.column(it.text) }) {
                if (band.any { CompanyReportTemplate.column(it.text) == column }) continue
                // Disagreeing locations do not establish a missing header anchor.
                if (words.maxOf { it.x } - words.minOf { it.x } > .025f) continue
                words.maxByOrNull { it.confidence }?.let { result += it }
            }
        }
        val added = result.drop(original.size)
        if (added.isNotEmpty()) diagnostics?.invoke("Printed header labels from pixels: $added")
        return result
    }
}
