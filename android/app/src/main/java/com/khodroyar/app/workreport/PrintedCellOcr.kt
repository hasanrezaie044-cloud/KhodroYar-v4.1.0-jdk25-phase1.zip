package com.khodroyar.app.workreport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import kotlin.math.sqrt

/**
 * Supplemental classic Persian print classifier for a header/grid-established
 * cell that neither LSTM nor glyph shapes could read. The official Apache-2.0
 * model is source-pinned; no private glyphs or per-company values train it.
 *
 * Three bounded photometric views, no search through dates/amounts. Confidence
 * is never boosted by trying several views: at least one original native score
 * must be >=85, and ALL accepted views must agree. Strong disagreements remain
 * unknown. Tiny ink remains unreadable even after enlargement.
 */
internal class PrintedCellOcr(private val engine: () -> TessBaseAPI) {
    data class Reading(val text: String, val confidence: Float, val agreeingViews: Int)

    fun read(bitmap: Bitmap, region: NumericRegion, diagnostics: ((String) -> Unit)? = null, checkCancelled: () -> Unit = {}): Reading? =
        readViews(bitmap, GridRect(region.left, region.top, region.right, region.bottom), region.field, 85f,
            { NumericReadingGrammar.canonical(region.field, it) }, diagnostics, checkCancelled)

    fun footer(bitmap: Bitmap, region: GridRect, checkCancelled: () -> Unit = {}): Reading? =
        readViews(bitmap, region, NumericField.DATE, 70f, { if (ReportLabels.footerOnly(it)) "جمع" else null }, null, checkCancelled)

    private fun readViews(bitmap: Bitmap, rect: GridRect, field: NumericField, minimum: Float,
        canonical: (String) -> String?, diagnostics: ((String) -> Unit)?, checkCancelled: () -> Unit): Reading? {
        checkCancelled()
        if (listOf(rect.left, rect.top, rect.right, rect.bottom).any { !it.isFinite() || it !in 0f..1f } || rect.left >= rect.right || rect.top >= rect.bottom) return null
        val left = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val width = ((rect.right * bitmap.width).toInt() - left).coerceIn(1, bitmap.width - left)
        val height = ((rect.bottom * bitmap.height).toInt() - top).coerceIn(1, bitmap.height - top)
        if (width.toLong() * height > PrintedCellRaster.MAX_PIXELS) return null
        val source = Bitmap.createBitmap(bitmap, left, top, width, height)
        var scaled: Bitmap? = null
        try {
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            if (!PrintedCellRaster.legibleHeight(width, height, PrintedCellRaster.grey(width, height, pixels))) return null
            val scale = minOf(2.0, 3000.0 / maxOf(width, height), sqrt(900_000.0 / (width.toDouble() * height)))
            val image = Bitmap.createScaledBitmap(source, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
            scaled = image
            val enlarged = IntArray(image.width * image.height)
            image.getPixels(enlarged, 0, image.width, 0, 0, image.width, image.height)
            val grey = PrintedCellRaster.grey(image.width, image.height, enlarged)
            val api = engine()
            val digits = "0123456789۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩"
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            api.setVariable("tessedit_char_whitelist", when (field) {
                NumericField.DATE -> "" // Preserve labels/letters; never force them into a valid date.
                NumericField.COUNT, NumericField.ORDINAL -> digits
                NumericField.KM -> digits + ".٫,٬"
                NumericField.HOURS -> digits + ".٫:"
            })
            val accepted = mutableListOf<Pair<String, Float>>()
            for (view in PrintedCellRaster.View.entries) {
                checkCancelled()
                val content = Bitmap.createBitmap(PrintedCellRaster.pixels(grey, view), image.width, image.height, Bitmap.Config.ARGB_8888)
                try {
                    val padded = Bitmap.createBitmap(image.width + 24, image.height + 24, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(padded).apply { drawColor(Color.WHITE); drawBitmap(content, 12f, 12f, null) }
                        api.setImage(padded)
                        val raw = api.getUTF8Text().orEmpty().trim()
                        val confidence = api.meanConfidence().toFloat()
                        checkCancelled()
                        val value = canonical(raw)
                        diagnostics?.invoke("Printed $field $view: raw='$raw'; confidence=$confidence; valid=${value != null}")
                        if (value != null && confidence >= minimum) accepted += value to confidence
                    } finally { api.clear(); padded.recycle() }
                } finally { content.recycle() }
            }
            if (accepted.map { it.first }.distinct().size != 1) {
                if (accepted.isNotEmpty()) diagnostics?.invoke("Printed $field: strong views disagree; left unknown.")
                return null
            }
            return Reading(accepted.first().first, accepted.maxOf { it.second }, accepted.size)
        } finally {
            scaled?.takeIf { it !== source && it !== bitmap }?.recycle()
            if (source !== bitmap) source.recycle()
        }
    }
}
