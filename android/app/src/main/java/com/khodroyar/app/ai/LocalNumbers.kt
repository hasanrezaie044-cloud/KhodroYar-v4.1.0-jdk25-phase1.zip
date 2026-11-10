package com.khodroyar.app.ai

/** Deliberately small, strict Persian/English number grammar. Never guesses a missing amount. */
internal object LocalNumbers {
    sealed interface Value {
        data class Found(val number: Double) : Value
        data object Missing : Value
        data object Ambiguous : Value
        data object Invalid : Value
    }

    private val words = mapOf(
        "صفر" to 0.0, "یک" to 1.0, "یه" to 1.0, "دو" to 2.0, "سه" to 3.0,
        "چهار" to 4.0, "پنج" to 5.0, "شش" to 6.0, "هفت" to 7.0, "هشت" to 8.0, "نه" to 9.0,
        "ده" to 10.0, "یازده" to 11.0, "دوازده" to 12.0, "سیزده" to 13.0, "چهارده" to 14.0,
        "پانزده" to 15.0, "شانزده" to 16.0, "هفده" to 17.0, "هجده" to 18.0, "نوزده" to 19.0,
        "بیست" to 20.0, "سی" to 30.0, "چهل" to 40.0, "پنجاه" to 50.0, "شصت" to 60.0,
        "هفتاد" to 70.0, "هشتاد" to 80.0, "نود" to 90.0, "صد" to 100.0, "یکصد" to 100.0,
        "دویست" to 200.0, "سیصد" to 300.0, "چهارصد" to 400.0, "پانصد" to 500.0,
        "ششصد" to 600.0, "هفتصد" to 700.0, "هشتصد" to 800.0, "نهصد" to 900.0, "نیم" to 0.5,
    )
    private val scales = mapOf("هزار" to 1_000.0, "میلیون" to 1_000_000.0, "میلیارد" to 1_000_000_000.0)
    private const val numeric = "[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?"
    private val atom = "(?:$numeric|" + (words.keys + scales.keys + "منفی").sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")"
    private val expression = "$atom(?:\\s+(?:$atom|و))*"
    private const val left = "(?<![\\p{L}\\p{N}.,+/-])"
    private const val right = "(?![\\p{L}\\p{N}])"

    /** Input must already have Latin digits and normalised spacing. Currency is required. */
    fun money(text: String): Value = extract(text, "تومان|تومن|ریال|tomans?|rials?") { amount, unit ->
        if (unit == "ریال" || unit.startsWith("rial")) amount / 10.0 else amount
    }

    fun liters(text: String): Value = extract(text, "لیتر|liters?") { amount, _ -> amount }

    fun kilometers(text: String): Value = extract(text, "کیلومتر|km") { amount, _ -> amount }

    private fun extract(text: String, units: String, convert: (Double, String) -> Double): Value {
        val matches = Regex("$left($expression)\\s*($units)$right").findAll(text).toList()
        if (matches.isEmpty()) return Value.Missing
        if (matches.size != 1) return Value.Ambiguous
        val match = matches.single()
        // Refuse malformed grouping, fractions, or an unparsed number/negative prefix.
        val prefix = text.substring(0, match.range.first).trimEnd()
        if (Regex("(?:[\\d.+/-]|\\d[,.\\s]+|منفی|نصف|ربع|پنجم|سوم|negative)$").containsMatchIn(prefix)) return Value.Invalid
        val number = parse(match.groupValues[1]) ?: return Value.Invalid
        return Value.Found(convert(number, match.groupValues[2]))
    }

    private fun parse(expression: String): Double? {
        val tokens = expression.trim().split(Regex("\\s+"))
        if (tokens.firstOrNull() == "و" || tokens.lastOrNull() == "و" || tokens.contains("منفی")) return null
        var total = 0.0
        var group = 0.0
        var lastWasValue = false
        var lastWasAnd = false
        var hasGroup = false
        var previousScale = Double.POSITIVE_INFINITY
        for (token in tokens) {
            if (token == "و") {
                if (lastWasAnd) return null
                lastWasAnd = true
                lastWasValue = false
                continue
            }
            val scale = scales[token]
            if (scale != null) {
                if (lastWasAnd || scale >= previousScale) return null
                previousScale = scale
                if (scale == 1000.0) {
                    group = (if (hasGroup) group else 1.0) * scale
                    hasGroup = true
                } else {
                    total += (if (hasGroup) group else 1.0) * scale
                    group = 0.0
                    hasGroup = false
                }
                lastWasValue = false
            } else {
                if (lastWasValue) return null
                val value = words[token] ?: token.replace(",", "").toDoubleOrNull() ?: return null
                if (!value.isFinite() || value < 0.0) return null
                // "دو میلیون و نیم" is intentionally not guessed; write "دو و نیم میلیون".
                if (value == 0.5 && total > 0 && group == 0.0) return null
                group += value
                hasGroup = true
                lastWasValue = true
            }
            lastWasAnd = false
        }
        return (total + group).takeIf { it.isFinite() }
    }
}
