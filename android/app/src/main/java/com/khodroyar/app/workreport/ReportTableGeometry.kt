package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput

/** Explicit header-derived regions, never a guessed count/km/hours order. */
enum class NumericField { DATE, COUNT, KM, HOURS }
data class NumericRegion(val field: NumericField, val left: Float, val top: Float, val right: Float, val bottom: Float)

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
        var headings = emptyMap<NumericField, Float>()
        val output = mutableListOf<NumericRegion>()
        for (line in WorkReportParser.lines(words)) {
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
            val height = (line.maxOf { it.bottom } - line.minOf { it.top }).coerceAtLeast(.01f)
            val top = (line.minOf { it.top } - height * .35f).coerceAtLeast(0f)
            val bottom = (line.maxOf { it.bottom } + height * .35f).coerceAtMost(1f)
            val columns = positions.entries.sortedBy { it.value }
            for ((index, entry) in columns.withIndex()) {
                val left = maxOf(0f, entry.value - .16f, if (index == 0) 0f else (columns[index - 1].value + entry.value) / 2)
                val right = minOf(1f, entry.value + .16f, if (index == columns.lastIndex) 1f else (columns[index + 1].value + entry.value) / 2)
                output += NumericRegion(entry.key, left, top, right, bottom)
            }
        }
        return output
    }
}
