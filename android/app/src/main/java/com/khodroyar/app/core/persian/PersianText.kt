package com.khodroyar.app.core.persian

import com.khodroyar.app.core.jalali.Jalali
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/** Locale-independent parsing. Invalid/missing input is null, NEVER zero. */
object PersianText {
    private val controls = Regex("[\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]")
    fun digits(text: String): String = buildString {
        for (c in controls.replace(Normalizer.normalize(text, Normalizer.Form.NFKC), "")) {
            append(when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            })
        }
    }

    fun letters(text: String): String = digits(text).replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
        .replace('\u200c', ' ').replace(Regex("""[\p{M}ـ]"""), "").replace(Regex("\\s+"), " ").trim()

    fun decimal(text: String?): BigDecimal? {
        if (text == null) return null
        val s = digits(text).trim().replace('٫', '.').replace('٬', ',').replace('،', ',')
            .replace('\u00a0', ' ').replace('\u202f', ' ')
        // Spaces and commas can group THOUSANDS, but cannot join arbitrary OCR tokens.
        if (!Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,3}(?: [0-9]{3})+)(?:\\.[0-9]+)?").matches(s)) return null
        return s.replace(",", "").replace(" ", "").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it <= BigDecimal("1000000000") }
    }

    fun count(text: String?): Int? = decimal(text)?.let {
        try { it.intValueExact().takeIf { n -> n in 1..100000 } } catch (_: ArithmeticException) { null }
    }

    /** Duration, not clock time. 30:00 is valid; 10:75 and a missing value are not. */
    fun hours(text: String?): BigDecimal? {
        if (text == null) return null
        val s = digits(text).trim()
        if (':' !in s) return decimal(s)
        val m = Regex("([0-9]{1,6}):([0-9]{2})").matchEntire(s) ?: return null
        val minutes = m.groupValues[2].toInt()
        if (minutes !in 0..59) return null
        return m.groupValues[1].toBigDecimal().add(BigDecimal(minutes).divide(BigDecimal(60), 12, RoundingMode.HALF_UP))
    }

    /** Compares Jalali keys directly: no timezone, Gregorian conversion or day offset. */
    fun date(text: String?): String? {
        if (text == null) return null
        val s = digits(text).trim().replace(Regex("\\s*[/\\-.⁄∕]\\s*"), "/")
        val m = Regex("([0-9]{4})/([0-9]{1,2})/([0-9]{1,2})").matchEntire(s) ?: return null
        val y = m.groupValues[1].toInt(); val month = m.groupValues[2].toInt(); val day = m.groupValues[3].toInt()
        if (y !in 1300..1600 || !Jalali.isValid(y, month, day)) return null
        return Jalali.format(y, month, day)
    }

    fun fa(text: String): String = buildString {
        text.forEach { append(if (it in '0'..'9') '۰' + (it - '0') else if (it == '.') '٫' else it) }
    }

    fun format(value: BigDecimal): String = fa(value.stripTrailingZeros().toPlainString())
}
