package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput

/** Explicit header-derived regions, never a guessed count/km/hours order. */
enum class NumericField { DATE, COUNT, KM, HOURS, ORDINAL }
data class NumericRegion(val field: NumericField, val left: Float, val top: Float, val right: Float, val bottom: Float,
    val lineTop: Float = top, val lineBottom: Float = bottom)

object ReportTableGeometry {
    private fun field(raw: String): NumericField? {
        val text = PersianInput.latin(raw).lowercase().trim()
        return when {
            text.contains("تاریخ") || text == "date" -> NumericField.DATE
            text.contains("تعداد") || text == "count" || text == "services" -> NumericField.COUNT
            text.contains("کیلومتر") || text.contains("کیلو متر") || text == "km" || text == "kilometers" -> NumericField.KM
            text.contains("ساعت") || text == "hours" || text == "hour" -> NumericField.HOURS
            else -> null
        }
    }
    fun regions(words: List<OcrWord>): List<NumericRegion> {
        CompanyReportTemplate.header(words)?.let { header ->
            val fields = mapOf(CompanyReportTemplate.Column.DATE to NumericField.DATE, CompanyReportTemplate.Column.ORDINAL to NumericField.ORDINAL,
                CompanyReportTemplate.Column.KM to NumericField.KM, CompanyReportTemplate.Column.HOURS to NumericField.HOURS)
            return WorkReportParser.lines(words).filter { line -> line.map { it.y }.average() > header.bottom &&
                line.any { w -> PersianInput.latin(w.text).any { it in '0'..'9' } } }.flatMap { line ->
                val top = line.minOf { it.top }; val bottom = line.maxOf { it.bottom }
                fields.mapNotNull { (column, field) -> header.bounds(column)?.let { (l, r) -> NumericRegion(field, l, top, r, bottom) } }
            }
        }
        var headings = emptyMap<NumericField, Float>()
        val output = mutableListOf<NumericRegion>()
        val lines = WorkReportParser.lines(words)
        for ((lineIndex, line) in lines.withIndex()) {
            val found = line.mapNotNull { w -> field(w.text)?.let { it to w.x } }.toMap()
            if (found.keys.containsAll(listOf(NumericField.COUNT, NumericField.KM, NumericField.HOURS))) {
                headings = found
                continue
            }
            if (!headings.keys.containsAll(listOf(NumericField.COUNT, NumericField.KM, NumericField.HOURS))) continue
            if (line.none { PersianInput.latin(it.text).any { c -> c in '0'..'9' } }) continue
            val positions = headings.toMutableMap()
            if (NumericField.DATE !in positions) {
                line.filter { PersianInput.date(it.text) != null }.singleOrNull()?.let { positions[NumericField.DATE] = it.x }
            }
            // Without the date boundary an outer count region could swallow a date.
            if (NumericField.DATE !in positions || positions.values.sorted().zipWithNext().any { (a, b) -> b - a < .04f }) continue
            val lineTop = line.minOf { it.top }; val lineBottom = line.maxOf { it.bottom }
            val height = (lineBottom - lineTop).coerceAtLeast(.01f)
            // Missing isolated cells/RTL descenders may extend beyond the page
            // recogniser's numeric boxes. Include the whitespace, never a neighbour.
            val previous = lines.getOrNull(lineIndex - 1)?.maxOf { it.bottom } ?: 0f
            val next = lines.getOrNull(lineIndex + 1)?.minOf { it.top } ?: 1f
            val top = maxOf(0f, lineTop - height * .8f, (previous + lineTop) / 2)
            val bottom = minOf(1f, lineBottom + height * .8f, (lineBottom + next) / 2)
            val columns = positions.entries.sortedBy { it.value }
            for ((index, entry) in columns.withIndex()) {
                val left = maxOf(0f, entry.value - .16f, if (index == 0) 0f else (columns[index - 1].value + entry.value) / 2)
                val right = minOf(1f, entry.value + .16f, if (index == columns.lastIndex) 1f else (columns[index + 1].value + entry.value) / 2)
                output += NumericRegion(entry.key, left, top, right, bottom, lineTop, lineBottom)
            }
        }
        return output
    }
}
