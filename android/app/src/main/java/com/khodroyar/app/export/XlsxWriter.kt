package com.khodroyar.app.export

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free XLSX writer.
 *
 * An .xlsx file is just a ZIP of OOXML parts, so `java.util.zip` plus string building
 * is all that is required. This intentionally replaces the `xlsx` npm package (SheetJS,
 * ~1 MB of JavaScript that had to be parsed and executed at runtime): output is written
 * straight to a stream, so memory stays flat regardless of row count.
 *
 * Supported, matching what utils/reportExport.ts produced:
 *  - multiple sheets with custom names
 *  - a bold header row
 *  - per-column widths
 *  - right-to-left sheet view (essential for the Persian reports)
 *  - frozen header row and an autofilter over the used range
 *  - numeric cells written as numbers, text as inline strings
 *
 * Everything is streamed and XML-escaped; no shared-string table is used, which keeps
 * the writer single-pass.
 */
class XlsxWriter {

    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: Double) : Cell

        /**
         * A monetary value. Written as a real number (so Excel can still sum it) but
         * always carrying the "#,##0" thousands format, unlike [Number] which only
         * picks the format up above 1000.
         */
        data class Money(val value: Double) : Cell
    }

    data class Column(val width: Double)

    data class Sheet(
        val name: String,
        val header: List<String>,
        val rows: List<List<Cell>>,
        val columns: List<Column> = emptyList(),
        val rightToLeft: Boolean = true,
        val autoFilter: Boolean = true,
        val freezeHeader: Boolean = true,
    )

    private val sheets = ArrayList<Sheet>()

    fun addSheet(sheet: Sheet): XlsxWriter { sheets.add(sheet); return this }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { write(it) }
    }

    fun write(out: OutputStream) {
        require(sheets.isNotEmpty()) { "workbook needs at least one sheet" }
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes())
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbook())
            zip.put("xl/_rels/workbook.xml.rels", workbookRels())
            zip.put("xl/styles.xml", styles())
            sheets.forEachIndexed { i, s -> zip.put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun ZipOutputStream.put(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        sheets.indices.forEach {
            append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        """</Relationships>"""

    private fun workbook(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s ->
            append("""<sheet name="${esc(sheetName(s.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.indices.forEach {
            append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
        }
        append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    /** Style 0 = normal, style 1 = bold header with fill, style 2 = thousands-separated number. */
    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        """<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0"/></numFmts>""" +
        """<fonts count="2">""" +
        """<font><sz val="11"/><name val="Calibri"/></font>""" +
        """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""" +
        """</fonts>""" +
        """<fills count="3"><fill><patternFill patternType="none"/></fill>""" +
        """<fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FF007F6D"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="3">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """</cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""

    private fun sheetXml(s: Sheet): String = buildString(1024 + s.rows.size * 128) {
        val lastCol = maxOf(s.header.size, s.rows.maxOfOrNull { it.size } ?: 0)
        val lastRow = s.rows.size + 1
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        append("""<sheetViews><sheetView workbookViewId="0"""")
        if (s.rightToLeft) append(""" rightToLeft="1"""")
        append(">")
        if (s.freezeHeader) {
            append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
        }
        append("</sheetView></sheetViews>")

        if (s.columns.isNotEmpty()) {
            append("<cols>")
            s.columns.forEachIndexed { i, c ->
                append("""<col min="${i + 1}" max="${i + 1}" width="${c.width}" customWidth="1"/>""")
            }
            append("</cols>")
        }

        append("<sheetData>")
        // header
        append("""<row r="1">""")
        s.header.forEachIndexed { c, title ->
            append("""<c r="${ref(c, 1)}" s="1" t="inlineStr"><is><t xml:space="preserve">${esc(title)}</t></is></c>""")
        }
        append("</row>")
        // body
        s.rows.forEachIndexed { rIdx, row ->
            val r = rIdx + 2
            append("""<row r="$r">""")
            row.forEachIndexed { c, cell ->
                when (cell) {
                    is Cell.Number -> {
                        val v = if (cell.value.isFinite()) cell.value else 0.0
                        val style = if (v == Math.floor(v) && kotlin.math.abs(v) >= 1000) """ s="2"""" else ""
                        append("""<c r="${ref(c, r)}"$style><v>${trimNum(v)}</v></c>""")
                    }
                    is Cell.Money -> {
                        val v = if (cell.value.isFinite()) cell.value else 0.0
                        append("""<c r="${ref(c, r)}" s="2"><v>${trimNum(v)}</v></c>""")
                    }
                    is Cell.Text -> {
                        if (cell.value.isEmpty()) append("""<c r="${ref(c, r)}"/>""")
                        else append("""<c r="${ref(c, r)}" t="inlineStr"><is><t xml:space="preserve">${esc(cell.value)}</t></is></c>""")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData>")

        if (s.autoFilter && lastCol > 0) {
            append("""<autoFilter ref="A1:${ref(lastCol - 1, lastRow)}"/>""")
        }
        append("</worksheet>")
    }

    private fun trimNum(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else v.toString()

    companion object {
        /** A1-style reference for a zero-based column and one-based row. */
        fun ref(col: Int, row: Int): String = colName(col) + row

        fun colName(col: Int): String {
            var c = col
            val sb = StringBuilder()
            while (true) {
                sb.insert(0, ('A' + (c % 26)))
                c = c / 26 - 1
                if (c < 0) break
            }
            return sb.toString()
        }

        /** Excel sheet names: max 31 chars, and []:*?/\ are illegal. */
        fun sheetName(raw: String): String {
            val cleaned = raw.map { if (it in "[]:*?/\\") '-' else it }.joinToString("")
            return if (cleaned.length <= 31) cleaned.ifEmpty { "Sheet" } else cleaned.substring(0, 31)
        }

        fun esc(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (ch in s) when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                // Control chars are illegal in XML 1.0; drop them rather than emit invalid output.
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') sb.append(ch)
            }
            return sb.toString()
        }
    }
}
