package com.khodroyar.app.reconciliation

import com.khodroyar.app.core.persian.PersianText

/** Coordinates refer to the exact rotated/preprocessed page shown in the review UI. */
data class OcrWord(val text: String, val confidence: Int, val area: RowArea)
data class OcrPage(val id: String, val width: Int, val height: Int, val words: List<OcrWord>)
data class ColumnBand(val start: Double, val end: Double) {
    fun contains(x: Double) = x >= start && x <= end
    fun valid() = start >= 0 && end <= 1 && start < end
}
data class StatementColumns(val date: ColumnBand, val kilometers: ColumnBand, val hours: ColumnBand) {
    fun valid(): Boolean {
        val bands = listOf(date, kilometers, hours)
        return bands.all { it.valid() } && bands.sortedBy { it.start }.zipWithNext().all { (a, b) -> a.end <= b.start }
    }
}

/** Conservative table-row extraction; no route matching, guessed numbers or totals-as-trips. */
object StatementParser {
    private val datePattern = Regex("(?<![0-9])[0-9]{4}\\s*[/.-]\\s*[0-9]{1,2}\\s*[/.-]\\s*[0-9]{1,2}(?![0-9])")
    private val totalPattern = Regex("(?:^|\\s)(?:جمع(?: کل)?|مجموع|جمعا|جمعاً|total|subtotal)(?:\\s|$|:)", RegexOption.IGNORE_CASE)
    private val headerPattern = Regex("تاریخ|کیلومتر|مسافت|ساعت|کارکرد|ردیف|شرح|مقصد|مبدا")

    fun parse(page: OcrPage, columns: StatementColumns? = null): List<StatementRow> {
        require(page.width > 0 && page.height > 0)
        require(columns == null || columns.valid())
        // Group words geometrically rather than trusting OCR's bidirectional text ordering.
        val lines = mutableListOf<MutableList<OcrWord>>()
        for (word in page.words.sortedBy { (it.area.top + it.area.bottom) / 2 }) {
            val cy = (word.area.top + word.area.bottom) / 2
            val line = lines.lastOrNull()
            val anchor = line?.firstOrNull()?.area
            if (anchor != null && kotlin.math.abs(cy - (anchor.top + anchor.bottom) / 2) <= maxOf(5, (anchor.bottom - anchor.top) / 2)) line.add(word)
            else lines.add(mutableListOf(word))
        }
        return lines.mapIndexed { index, words ->
            val raw = words.sortedByDescending { it.area.left }.joinToString(" ") { it.text }
            val normalized = PersianText.letters(raw)
            val area = RowArea(words.minOf { it.area.left }, words.minOf { it.area.top }, words.maxOf { it.area.right }, words.maxOf { it.area.bottom })
            val dateAnywhere = datePattern.find(PersianText.digits(raw))?.value.orEmpty()
            val numeric = words.any { PersianText.digits(it.text).any { c -> c in '0'..'9' } }
            val kind = when {
                totalPattern.containsMatchIn(normalized) -> StatementRowKind.TOTAL
                !numeric -> StatementRowKind.OTHER
                Regex("^(?:صفحه|page)\\s*[:0-9 /از]*$", RegexOption.IGNORE_CASE).matches(normalized) -> StatementRowKind.OTHER
                headerPattern.containsMatchIn(normalized) && dateAnywhere.isEmpty() && words.count { w -> PersianText.digits(w.text).any { it in '0'..'9' } } < 2 -> StatementRowKind.OTHER
                dateAnywhere.isNotEmpty() || columns != null -> StatementRowKind.SERVICE
                // A damaged date with two numeric cells must not silently disappear.
                words.count { w -> PersianText.digits(w.text).any { it in '0'..'9' } } >= 2 -> StatementRowKind.SERVICE
                else -> StatementRowKind.OTHER
            }
            fun field(band: ColumnBand): String = words.filter {
                band.contains((it.area.left + it.area.right) / 2.0 / page.width)
            }.sortedBy { it.area.left }.joinToString(" ") { PersianText.digits(it.text) }.trim()
            // No column layout = no unlabeled numeric guesses, even if OCR confidence is high.
            val date = if (columns != null) field(columns.date) else dateAnywhere
            val km = if (columns != null) field(columns.kilometers) else ""
            val hours = if (columns != null) field(columns.hours).replace(Regex("\\s*:\\s*"), ":") else ""
            StatementRow(
                id = "${page.id}-${index + 1}", pageId = page.id, date = date,
                kilometers = km, hours = hours, rawText = raw,
                confidence = words.minOfOrNull { it.confidence } ?: 0, area = area, kind = kind,
                // EVERY row requires human confirmation. Confidence is not numeric correctness.
                confirmed = false,
            )
        }
    }
}
