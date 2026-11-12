package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput

/**
 * Numeric syntax can distinguish a decimal-like punctuation mark from an invalid
 * grouping, but must NEVER choose between two readable amounts or uncertain
 * digits. E.g. 580,09 is not a grouped number; 1,234 vs 1.234 stays unknown.
 * No values, dates, totals or neighbouring cells are used as correction hints.
 */
object NumericReadingGrammar {
    fun uniqueValue(field: NumericField, glyphs: List<List<Char>>): String? {
        if (glyphs.isEmpty() || glyphs.size > 40 || glyphs.any { it.isEmpty() }) return null
        // Only punctuation alternatives may be resolved by numeric syntax. A
        // doubtful digit is unreadable even if only one date would be valid.
        if (glyphs.any { options -> options.size > 1 && options.any { it in '0'..'9' } }) return null
        var spellings = listOf("")
        for (options in glyphs) {
            if (spellings.size * options.size > 64) return null
            spellings = spellings.flatMap { prefix -> options.distinct().map { prefix + it } }
        }
        val valid = spellings.mapNotNull { text -> canonical(field, text)?.let { text to it } }
        return valid.takeIf { it.map { pair -> pair.second }.distinct().size == 1 }?.firstOrNull()?.first
    }

    internal fun canonical(field: NumericField, text: String): String? = when (field) {
        NumericField.DATE -> PersianInput.date(text)
        NumericField.COUNT -> PersianInput.count(text)?.toString()
        NumericField.ORDINAL -> PersianInput.count(text)?.takeIf { it > 0 }?.toString()
        NumericField.KM -> PersianInput.decimal(text)?.let(PersianInput::display)
        NumericField.HOURS -> PersianInput.hours(text)?.let(PersianInput::display)
    }
}
