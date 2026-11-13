package com.khodroyar.app.workreport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.khodroyar.app.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * A small, local visual reader for printed numeric cells, not a language model.
 * Tesseract establishes the table/row geometry; this reader compares the actual
 * ink with glyphs rendered from the app's licensed fonts and Android's fonts.
 * It does NOT correct strings with likely dates, totals, neighbouring values or
 * fixture constants. Every glyph must pass both a shape-distance and a distinct
 * runner-up margin for digits. Punctuation is accepted only when all valid
 * spellings agree on one numeric value. Unknown/touching/ambiguous digits reject
 * the entire cell; ambiguous amounts such as 1,234 vs 1.234 remain unknown.
 *
 * Position relative to the whole row is retained: Persian/Arabic zero is not a
 * decimal point, and the lower Arabic decimal separator is not a date slash.
 * Templates are generated once in memory. Nothing is downloaded or persisted.
 */
class NumericGlyphOcr(context: Context) {
    data class Reading(val text: String, val confidence: Float, val maximumDistance: Float, val minimumMargin: Float)
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    private data class Feature(val pixels: FloatArray, val aspect: Float, val height: Float, val middle: Float)
    private data class Template(val label: Char, val feature: Feature)
    private data class LabelTemplate(val label: String, val feature: Feature)
    private data class Mask(val width: Int, val height: Int, val ink: BooleanArray) {
        fun bounds(left: Int = 0, right: Int = width): Box? {
            var top = height; var bottom = -1; var first = right; var last = -1
            for (y in 0 until height) for (x in left until right) if (ink[y * width + x]) {
                top = minOf(top, y); bottom = maxOf(bottom, y); first = minOf(first, x); last = maxOf(last, x)
            }
            return if (last < 0) null else Box(first, top, last + 1, bottom + 1)
        }
    }
    private data class Cell(val region: NumericRegion, val top: Int, val mask: Mask)
    private val templates = templates(context.applicationContext)
    private val labels by lazy { labels(context.applicationContext) }

    /** Regions must describe ONE row, so small zeros/points keep their baseline. */
    fun readRow(bitmap: Bitmap, regions: List<NumericRegion>, diagnostics: ((String) -> Unit)? = null, cellBaselines: Boolean = false, checkCancelled: () -> Unit = {}): Map<NumericRegion, Reading?> {
        if (regions.isEmpty()) return emptyMap()
        val cells = regions.mapNotNull { region ->
            checkCancelled()
            val left = (region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = (region.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (region.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            if ((right - left).toLong() * (bottom - top) > 1_000_000) null
            else Cell(region, top, mask(bitmap, left, top, right - left, bottom - top, removeGrid = true))
        }
        val bounds = cells.mapNotNull { cell -> cell.mask.bounds()?.let { cell.top + it.top to cell.top + it.bottom } }
        if (bounds.isEmpty()) return emptyMap()
        val rowTop = bounds.minOf { it.first }; val rowBottom = bounds.maxOf { it.second }
        // Tiny print is not promoted into confident numbers by rescaling noise.
        if (rowBottom - rowTop < 12) return emptyMap()
        return cells.associate { cell ->
            checkCancelled()
            // A date has full-height digits/slashes and may use a different
            // font size. Other numeric cells MUST retain the complete row's
            // baseline: even >12px Arabic fives can be shorter than full digits.
            // Normalising a cell of only those glyphs to its own height turns
            // them into Western zeroes. Tiny zeroes/points also keep the row.
            val own = cell.mask.bounds()?.takeIf { cellBaselines && cell.region.field == NumericField.DATE && it.height >= 12 }
            cell.region to read(cell.mask, own?.top ?: (rowTop - cell.top), own?.bottom ?: (rowBottom - cell.top), cell.region.field,
                diagnostics?.let { callback -> { message -> callback("${cell.region.field}: $message") } }, checkCancelled)
        }
    }

    private fun read(mask: Mask, rowTop: Int, rowBottom: Int, field: NumericField, diagnostics: ((String) -> Unit)?, checkCancelled: () -> Unit): Reading? {
        val columns = BooleanArray(mask.width)
        for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask.ink[y * mask.width + x]) columns[x] = true
        val groups = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (x in 0..mask.width) {
            if (x < mask.width && columns[x]) { if (start < 0) start = x }
            else if (start >= 0) { groups += start to x; start = -1 }
        }
        if (groups.isEmpty() || groups.size > 40) return null
        val glyphs = mutableListOf<List<Char>>()
        var ambiguousPunctuation = false
        var maximumDistance = 0f; var minimumMargin = 1f
        for ((left, right) in groups) {
            checkCancelled()
            val box = mask.bounds(left, right) ?: return null
            val feature = feature(mask, box, rowTop, rowBottom)
            val scores = FloatArray(LABELS.length) { Float.POSITIVE_INFINITY }
            for (template in templates) {
                val score = distance(feature, template.feature)
                val index = LABELS.indexOf(template.label)
                if (score < scores[index]) scores[index] = score
            }
            val ordered = scores.indices.sortedBy { scores[it] }
            val best = scores[ordered[0]]; val margin = scores[ordered[1]] - best
            val options = ordered.filter { scores[it] - best < MIN_MARGIN }.map { LABELS[it] }
            if (best > MAX_DISTANCE || options.isEmpty() || options.size > 1 &&
                (options.any { it in '0'..'9' } || options.any { scores[LABELS.indexOf(it)] > MAX_DISTANCE })) {
                diagnostics?.invoke("Rejected fragment $box after '$glyphs': best=${LABELS[ordered[0]]}:$best, runner=${LABELS[ordered[1]]}:${scores[ordered[1]]}, margin=$margin, aspect=${feature.aspect}, height=${feature.height}, middle=${feature.middle}")
                return null
            }
            if (options.size > 1) ambiguousPunctuation = true
            glyphs += options
            maximumDistance = maxOf(maximumDistance, options.maxOf { scores[LABELS.indexOf(it)] }); minimumMargin = minOf(minimumMargin, margin)
        }
        val text = NumericReadingGrammar.uniqueValue(field, glyphs) ?: return null
        if (ambiguousPunctuation) diagnostics?.invoke("Punctuation shapes $glyphs have just one valid numeric value; spelling=$text. No uncertain digit was substituted.")
        val confidence = (98f - 240f * maximumDistance).coerceIn(70f, if (ambiguousPunctuation) 79f else 98f)
        return Reading(text, confidence, maximumDistance, minimumMargin)
    }

    /** A printed footer word is read from its own pixels, not inferred from sums. */
    fun readFooter(bitmap: Bitmap, region: NumericRegion): Reading? {
        val left = (region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val width = ((region.right * bitmap.width).toInt() - left).coerceIn(1, bitmap.width - left)
        val height = ((region.bottom * bitmap.height).toInt() - top).coerceIn(1, bitmap.height - top)
        if (width.toLong() * height > 1_000_000) return null
        val mask = mask(bitmap, left, top, width, height, removeGrid = true)
        val box = mask.bounds() ?: return null
        val feature = feature(mask, box, box.top, box.bottom)
        val scores = labels.groupBy { it.label }.mapValues { (_, values) -> values.minOf { distance(feature, it.feature) } }.entries.sortedBy { it.value }
        val best = scores[0]; val margin = scores[1].value - best.value
        return if (best.value <= .075f && margin >= .035f) Reading(best.key, 98f - 240f * best.value, best.value, margin) else null
    }

    companion object {
        private const val WIDTH = 24
        private const val HEIGHT = 32
        private const val LABELS = "0123456789./:,-"
        private const val MAX_DISTANCE = .115f
        private const val MIN_MARGIN = .020f
        private val scripts = listOf("0123456789", "۰۱۲۳۴۵۶۷۸۹", "٠١٢٣٤٥٦٧٨٩")
        @Volatile private var cached: List<Template>? = null
        @Volatile private var cachedLabels: List<LabelTemplate>? = null

        private fun templates(context: Context): List<Template> = cached ?: synchronized(this) {
            cached ?: run {
                val fonts = listOf(R.font.vazirmatn_regular, R.font.vazirmatn_medium, R.font.vazirmatn_semibold, R.font.vazirmatn_bold)
                    .mapNotNull { ResourcesCompat.getFont(context, it) } +
                    listOf("sans-serif", "sans-serif-condensed", "serif", "monospace").flatMap { family ->
                        listOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC).map { Typeface.create(family, it) }
                    }
                val printFonts = listOf(R.font.parastoo_regular, R.font.parastoo_bold).mapNotNull { ResourcesCompat.getFont(context, it) }
                val variants = fonts.map { it to 72f } + printFonts.flatMap { font -> listOf(32f, 48f, 72f).map { font to it } }
                val result = mutableListOf<Template>()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                for ((font, size) in variants) {
                    paint.typeface = font; paint.textSize = size
                    for (script in scripts) {
                        val glyphs = (script + "./:٫,٬-").mapNotNull { char ->
                            if (!paint.hasGlyph(char.toString())) null else {
                                val bitmap = Bitmap.createBitmap(180, 160, Bitmap.Config.ARGB_8888)
                                val value = try {
                                    bitmap.eraseColor(Color.WHITE)
                                    Canvas(bitmap).drawText(char.toString(), 30f, 100f, paint)
                                    mask(bitmap, 0, 0, bitmap.width, bitmap.height, removeGrid = false)
                                } finally { bitmap.recycle() }
                                value.bounds()?.let { Triple(char, value, it) }
                            }
                        }
                        val reference = glyphs.filter { it.first in script || it.first == '/' }
                        if (reference.isEmpty()) continue
                        val top = reference.minOf { it.third.top }; val bottom = reference.maxOf { it.third.bottom }
                        glyphs.forEach { (char, value, box) ->
                            val label = when (char) {
                                in '۰'..'۹' -> '0' + (char - '۰')
                                in '٠'..'٩' -> '0' + (char - '٠')
                                '٫' -> '.'
                                '٬' -> ','
                                else -> char
                            }
                            result += Template(label, feature(value, box, top, bottom))
                        }
                    }
                }
                result.toList().also { cached = it }
            }
        }

        private fun labels(context: Context): List<LabelTemplate> = cachedLabels ?: synchronized(this) {
            cachedLabels ?: run {
                val fonts = listOf(R.font.vazirmatn_regular, R.font.vazirmatn_medium, R.font.vazirmatn_semibold, R.font.vazirmatn_bold,
                    R.font.parastoo_regular, R.font.parastoo_bold)
                    .mapNotNull { ResourcesCompat.getFont(context, it) } +
                    listOf("sans-serif", "serif", "monospace").flatMap { family -> listOf(Typeface.NORMAL, Typeface.BOLD).map { Typeface.create(family, it) } }
                val result = mutableListOf<LabelTemplate>()
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 60f; color = Color.BLACK }
                for (font in fonts) for (word in listOf("جمع", "جمع کل", "مجموع", "total", "subtotal")) {
                    paint.typeface = font
                    val bitmap = Bitmap.createBitmap(700, 180, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        val layout = StaticLayout.Builder.obtain(word, 0, word.length, paint, 700).setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setIncludePad(false).build()
                        val canvas = Canvas(bitmap); canvas.translate(0f, 30f); layout.draw(canvas)
                        val mask = mask(bitmap, 0, 0, bitmap.width, bitmap.height, removeGrid = false)
                        mask.bounds()?.let { box -> result += LabelTemplate(word, feature(mask, box, box.top, box.bottom)) }
                    } finally { bitmap.recycle() }
                }
                result.toList().also { cachedLabels = it }
            }
        }

        /** Otsu on a bounded cell; the white paper need not be exactly RGB 255. */
        private fun mask(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int, removeGrid: Boolean): Mask {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, left, top, width, height)
            val histogram = IntArray(256)
            for (i in pixels.indices) {
                val color = pixels[i]
                val gray = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) ushr 8
                pixels[i] = gray; histogram[gray]++
            }
            val sum = histogram.indices.sumOf { it.toDouble() * histogram[it] }
            var background = 0; var partial = 0.0; var variance = -1.0; var threshold = 128
            for (value in 0..254) {
                background += histogram[value]
                if (background == 0) continue
                val foreground = pixels.size - background
                if (foreground == 0) break
                partial += value.toDouble() * histogram[value]
                val difference = partial / background - (sum - partial) / foreground
                val candidate = background.toDouble() * foreground * difference * difference
                if (candidate > variance) { variance = candidate; threshold = value }
            }
            threshold = threshold.coerceIn(40, 210)
            val ink = BooleanArray(pixels.size) { pixels[it] <= threshold }
            if (removeGrid) {
                val rows = BooleanArray(height) { y -> (0 until width).count { x -> ink[y * width + x] } > width * .8f }
                val columns = BooleanArray(width) { x -> (0 until height).count { y -> ink[y * width + x] } > height * .8f }
                for (y in 0 until height) for (x in 0 until width) if (rows[y] || columns[x]) ink[y * width + x] = false
            }
            return Mask(width, height, ink)
        }

        private fun feature(mask: Mask, box: Box, rowTop: Int, rowBottom: Int): Feature {
            // Area averages, rather than stretching a one-pixel dot into a digit.
            val stride = box.width + 1
            val integral = IntArray(stride * (box.height + 1))
            for (y in 0 until box.height) {
                var row = 0
                for (x in 0 until box.width) {
                    if (mask.ink[(box.top + y) * mask.width + box.left + x]) row++
                    integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
                }
            }
            val pixels = FloatArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val a = floor(x * box.width.toDouble() / WIDTH).toInt()
                val b = ceil((x + 1) * box.width.toDouble() / WIDTH).toInt()
                val c = floor(y * box.height.toDouble() / HEIGHT).toInt()
                val d = ceil((y + 1) * box.height.toDouble() / HEIGHT).toInt()
                val count = integral[d * stride + b] - integral[c * stride + b] - integral[d * stride + a] + integral[c * stride + a]
                pixels[y * WIDTH + x] = count.toFloat() / ((b - a) * (d - c))
            }
            val rowHeight = (rowBottom - rowTop).toFloat()
            return Feature(pixels, box.width.toFloat() / box.height, box.height / rowHeight, ((box.top + box.bottom) / 2f - rowTop) / rowHeight)
        }

        private fun distance(a: Feature, b: Feature): Float {
            var difference = 0f
            for (i in a.pixels.indices) difference += abs(a.pixels[i] - b.pixels[i])
            return .68f * difference / a.pixels.size + .08f * minOf(1f, abs(ln(a.aspect / b.aspect))) +
                .15f * minOf(1f, abs(a.height - b.height)) + .09f * minOf(1f, abs(a.middle - b.middle))
        }
    }
}
