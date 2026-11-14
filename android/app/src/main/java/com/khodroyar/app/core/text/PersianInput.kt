package com.khodroyar.app.core.text

import com.khodroyar.app.core.jalali.Jalali
import java.math.BigDecimal
import java.math.RoundingMode

/** Strict shared input contract. Unknown/malformed data is null, never an invented zero. */
object PersianInput {
    fun latin(value: String): String = buildString {
        value.forEach { ch -> when (ch) {
            in '۰'..'۹' -> append('0' + (ch - '۰'))
            in '٠'..'٩' -> append('0' + (ch - '٠'))
            'ي', 'ى' -> append('ی')
            'ك' -> append('ک')
            '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u2066', '\u2067', '\u2069' -> Unit
            '\u200c', '\u00a0' -> append(' ')
            else -> append(ch)
        } }
    }

    val datePattern = Regex("(?<![0-9])(?:1[3-6][0-9]{2}\\s*[/.-]\\s*[0-9]{1,2}\\s*[/.-]\\s*[0-9]{1,2}|1[3-6][0-9]{6})(?![0-9])")

    fun date(input: String): String? {
        val s = latin(input).trim().replace(Regex("\\s*([/.-])\\s*"), "$1")
        if (!Regex("(?:[0-9]{8}|[0-9]{4}[/.-][0-9]{1,2}[/.-][0-9]{1,2})").matches(s)) return null
        val p = if (Regex("[0-9]{8}").matches(s)) listOf(s.take(4), s.substring(4, 6), s.takeLast(2))
            else s.replace('-', '/').replace('.', '/').split('/')
        if (p.size != 3 || p.any { !Regex("[0-9]+").matches(it) }) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return if (y in 1300..1600 && Jalali.isValid(y, m, d)) Jalali.format(y, m, d) else null
    }

    fun dates(input: String): List<String?> = datePattern.findAll(latin(input)).map { date(it.value) }.toList()

    fun decimal(input: String): BigDecimal? {
        val s = latin(input).trim().replace('٫', '.').replace('٬', ',')
        if (!Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,6})?").matches(s)) return null
        return s.replace(",", "").toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal("10000000") }
    }

    fun count(input: String): Int? = decimal(input)?.let {
        try { it.intValueExact().takeIf { n -> n in 0..10000 } } catch (_: ArithmeticException) { null }
    }

    fun hours(input: String): BigDecimal? {
        val s = latin(input).trim()
        if (!s.contains(':')) return decimal(s)
        if (!Regex("[0-9]{1,5}:[0-9]{2}").matches(s)) return null
        val p = s.split(':'); val h = p[0].toBigDecimal(); val m = p[1].toInt()
        if (m !in 0..59) return null
        return h + m.toBigDecimal().divide(BigDecimal(60), 6, RoundingMode.HALF_UP)
    }

    /** Inclusive, bounded iteration; invalid, reversed and unbounded mass requests fail closed. */
    fun days(start: String, end: String, limit: Int = 366): List<String>? {
        val a = date(start) ?: return null
        val b = date(end) ?: return null
        val n = Jalali.daysBetween(a, b) + 1
        return if (n in 1..limit) (0 until n).map { Jalali.addDays(a, it) } else null
    }

    fun display(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
