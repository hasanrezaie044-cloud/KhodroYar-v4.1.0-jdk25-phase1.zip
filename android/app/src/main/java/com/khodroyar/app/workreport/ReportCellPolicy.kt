package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput

/** Formatting only: no digit is inserted, removed, reversed or substituted. */
object ReportDateText {
    fun normalize(raw: String): String = PersianInput.latin(raw).trim()
        .replace(Regex("\\s*([/.-])\\s*"), "$1")
        .replace(Regex("[/.-]{2,}"), "/")
    fun parse(raw: String): String? = PersianInput.date(normalize(raw))
}

object ReportHoursText {
    fun isEmpty(raw: String): Boolean = PersianInput.latin(raw).trim() in listOf("", ".", "-")
    fun normalize(raw: String): String = if (isEmpty(raw)) "" else raw
}

/**
 * Fixed-template policy only. The glyph reader must still pass its existing
 * distance/margin/grammar gates; classic native scores must still be >=85.
 * A single canonical LSTM reading >=75 is usable in the reviewed ruled form.
 * Scores are never increased by agreement, formatting or trying extra views.
 */
internal object CompanyCellPolicy {
    enum class Engine { GLYPH, TESSERACT, CLASSIC }
    data class Evidence(val engine: Engine, val raw: String, val confidence: Float)
    data class Decision(val text: String = "", val confidence: Float = 0f, val conflict: Boolean = false)

    fun canonical(field: NumericField, raw: String): String? = when (field) {
        NumericField.DATE -> ReportDateText.parse(raw)
        NumericField.COUNT -> PersianInput.count(raw)?.toString()
        NumericField.ORDINAL -> PersianInput.count(raw)?.takeIf { it > 0 }?.toString()
        NumericField.KM -> PersianInput.decimal(raw)?.let(PersianInput::display)
        NumericField.HOURS -> PersianInput.hours(raw)?.let(PersianInput::display)
    }

    fun decide(field: NumericField, evidence: List<Evidence>, strongViewConflict: Boolean = false): Decision {
        require(evidence.map { it.engine }.distinct().size == evidence.size)
        if (strongViewConflict) return Decision(conflict = true)
        val accepted = evidence.mapNotNull { e ->
            val minimum = if (e.engine == Engine.TESSERACT) 75f else if (e.engine == Engine.CLASSIC) 85f else 70f
            if (!e.confidence.isFinite() || e.confidence !in minimum..100f) null
            else canonical(field, e.raw)?.let { value -> e to value }
        }
        // Glyph confidence is a shape-distance score, not a native probability.
        // Its unchanged structural gates already establish a qualified reading.
        val strong = accepted.filter { (e, _) -> e.engine != Engine.TESSERACT || e.confidence >= 85f }
        if (strong.map { it.second }.distinct().size > 1) return Decision(conflict = true)
        val candidates = strong.ifEmpty { accepted }
        // No arbitrary choice between differing weak values either.
        if (candidates.map { it.second }.distinct().size > 1) return Decision(conflict = true)
        val chosen = candidates.maxByOrNull { it.first.confidence } ?: return Decision()
        return Decision(chosen.second, chosen.first.confidence)
    }
}
