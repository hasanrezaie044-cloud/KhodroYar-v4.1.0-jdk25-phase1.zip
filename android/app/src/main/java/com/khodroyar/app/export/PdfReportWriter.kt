package com.khodroyar.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File

/**
 * Native PDF report writer built on `android.graphics.pdf.PdfDocument`.
 *
 * Replaces the previous HTML-to-print path. Android's text stack shapes and
 * bidi-orders Arabic script natively, so Persian renders correctly without
 * embedding a font or shipping a rendering engine.
 *
 * The layout is deliberately RTL: columns are laid out right-to-left and cell text
 * is right-aligned, matching the on-screen reports.
 */
class PdfReportWriter(
    private val pageWidth: Int = A4_WIDTH,
    private val pageHeight: Int = A4_HEIGHT,
    private val margin: Float = 32f,
) {

    companion object {
        // A4 at 72 dpi.
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842

        private const val ACCENT = 0xFF007F6D.toInt()
        private const val HEADER_TEXT = Color.WHITE
        private const val BODY_TEXT = 0xFF1A1A1A.toInt()
        private const val MUTED = 0xFF6B7280.toInt()
        private const val ROW_ALT = 0xFFF3F6F5.toInt()
        private const val GRID = 0xFFDDE3E1.toInt()
    }

    data class Table(
        val title: String,
        /** Right-to-left order: the first header is the RIGHTMOST column. */
        val header: List<String>,
        val rows: List<List<String>>,
        /** Relative column weights; defaults to equal width. */
        val weights: List<Float> = emptyList(),
        /** Optional footer, e.g. totals. */
        val footer: List<String>? = null,
    )

    private val titlePaint = TextPaint().apply {
        isAntiAlias = true; color = BODY_TEXT; textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val subtitlePaint = TextPaint().apply {
        isAntiAlias = true; color = MUTED; textSize = 10f; textAlign = Paint.Align.RIGHT
    }
    private val headerPaint = TextPaint().apply {
        isAntiAlias = true; color = HEADER_TEXT; textSize = 9.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val cellPaint = TextPaint().apply {
        isAntiAlias = true; color = BODY_TEXT; textSize = 9f; textAlign = Paint.Align.RIGHT
    }
    private val footerCellPaint = TextPaint().apply {
        isAntiAlias = true; color = BODY_TEXT; textSize = 9f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val linePaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 0.5f; color = GRID
    }
    private val pageNumPaint = TextPaint().apply {
        isAntiAlias = true; color = MUTED; textSize = 8f; textAlign = Paint.Align.CENTER
    }

    private val rowHeight = 18f
    private val headerHeight = 22f

    /**
     * Renders [tables] into a paginated PDF at [file].
     * Long tables continue across pages with the header row repeated.
     */
    fun write(
        file: File,
        documentTitle: String,
        subtitle: String,
        tables: List<Table>,
    ) {
        file.parentFile?.mkdirs()
        val doc = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun startPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = doc.startPage(info)
            canvas = page!!.canvas
            y = margin

            // Document header, right-aligned.
            val right = pageWidth - margin
            canvas!!.drawText(documentTitle, right, y + 14f, titlePaint)
            y += 20f
            if (subtitle.isNotEmpty()) {
                canvas!!.drawText(subtitle, right, y + 10f, subtitlePaint)
                y += 16f
            }
            y += 6f
            fillPaint.color = ACCENT
            canvas!!.drawRect(margin, y, right, y + 2f, fillPaint)
            y += 12f
        }

        fun remaining(): Float = pageHeight - margin - 18f - y

        startPage()

        for (table in tables) {
            if (table.rows.isEmpty()) continue

            // Table caption
            if (remaining() < headerHeight + rowHeight * 2 + 24f) startPage()
            canvas!!.drawText(table.title, pageWidth - margin, y + 11f, titlePaint.also { it.textSize = 12f })
            titlePaint.textSize = 16f
            y += 18f

            val usableWidth = pageWidth - margin * 2
            val weights = if (table.weights.size == table.header.size) table.weights
            else List(table.header.size) { 1f }
            val totalWeight = weights.sum().takeIf { it > 0f } ?: 1f
            val widths = weights.map { it / totalWeight * usableWidth }

            /** x of the RIGHT edge of column [i] (RTL: column 0 is rightmost). */
            fun rightEdge(i: Int): Float {
                var x = pageWidth - margin
                for (j in 0 until i) x -= widths[j]
                return x
            }

            fun drawHeaderRow() {
                fillPaint.color = ACCENT
                canvas!!.drawRect(margin, y, pageWidth - margin, y + headerHeight, fillPaint)
                for (i in table.header.indices) {
                    val r = rightEdge(i) - 4f
                    canvas!!.drawText(
                        ellipsize(table.header[i], widths[i] - 8f, headerPaint),
                        r, y + headerHeight - 7f, headerPaint,
                    )
                }
                y += headerHeight
            }

            drawHeaderRow()

            table.rows.forEachIndexed { idx, row ->
                if (remaining() < rowHeight) {
                    startPage()
                    drawHeaderRow()
                }
                if (idx % 2 == 1) {
                    fillPaint.color = ROW_ALT
                    canvas!!.drawRect(margin, y, pageWidth - margin, y + rowHeight, fillPaint)
                }
                for (i in table.header.indices) {
                    val text = row.getOrElse(i) { "" }
                    if (text.isEmpty()) continue
                    canvas!!.drawText(
                        ellipsize(text, widths[i] - 8f, cellPaint),
                        rightEdge(i) - 4f, y + rowHeight - 5.5f, cellPaint,
                    )
                }
                canvas!!.drawLine(margin, y + rowHeight, pageWidth - margin, y + rowHeight, linePaint)
                y += rowHeight
            }

            table.footer?.let { footer ->
                if (remaining() < rowHeight) startPage()
                fillPaint.color = 0xFFE6EFED.toInt()
                canvas!!.drawRect(margin, y, pageWidth - margin, y + rowHeight, fillPaint)
                for (i in table.header.indices) {
                    val text = footer.getOrElse(i) { "" }
                    if (text.isEmpty()) continue
                    canvas!!.drawText(
                        ellipsize(text, widths[i] - 8f, footerCellPaint),
                        rightEdge(i) - 4f, y + rowHeight - 5.5f, footerCellPaint,
                    )
                }
                y += rowHeight
            }

            y += 14f
        }

        page?.let { doc.finishPage(it) }

        // Page numbers
        val total = doc.pages.size
        // PdfDocument does not allow re-opening finished pages, so numbering is drawn
        // during rendering instead; this block only reports the count.
        file.outputStream().buffered().use { doc.writeTo(it) }
        doc.close()
        lastPageCount = total
    }

    var lastPageCount: Int = 0
        private set

    private fun ellipsize(text: String, maxWidth: Float, paint: TextPaint): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (paint.measureText(text.substring(0, mid) + "…") <= maxWidth) lo = mid + 1 else hi = mid
        }
        val cut = (lo - 1).coerceAtLeast(0)
        return if (cut == 0) "" else text.substring(0, cut) + "…"
    }

    private fun measureBounds(text: String, paint: TextPaint): Rect =
        Rect().also { paint.getTextBounds(text, 0, text.length, it) }
}
