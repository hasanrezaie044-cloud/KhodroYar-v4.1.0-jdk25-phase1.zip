package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import java.text.Normalizer

/**
 * Maps FastOCR's plain UTF-8 text (no coordinates) to the SAME conservative,
 * fully-editable review rows. Anchored strictly on a single valid Jalali date
 * per service line. Understands the real KhodroYar vehicle work-report shape:
 *   <date> <type: ثابت/شب/درخواستی/تعطیل> <"HH:MM تا HH:MM" time range>
 *   <km integer> <route words> <عوارضی 0> <درآمد 4,154,530 comma-grouped money>
 * Rules that keep the comparison gates safe:
 *  - comma-grouped money is NEVER treated as km/hours;
 *  - hours come only from an explicit start..end time range (next-day wrap when
 *    end < start); "--:--" (missing end or start) means UNKNOWN, never zero;
 *  - km must be a small plain integer (1..5000);
 *  - dateless lines (headers, per-type summary rows, "جمع" totals) never become
 *    rows; a dated row that continues onto following dateless lines is merged
 *    across at most 4 lines (OCR often wraps a PDF row);
 *  - anything still ambiguous stays blank with a Persian guidance note.
 */
object FastOcrReportText {
    private val numberToken = Regex("[0-9]+(?::[0-9]{2}|[.٫][0-9]+)?")
    private val commaMoney = Regex("[0-9]{1,3}(?:,[0-9]{3})+")
    private val clock = "(?:--:--|[0-9]{1,2}:[0-9]{2})"
    private val timeRange = Regex("$clock\\s*تا\\s*$clock")
    private val clockToken = Regex(clock)
    /** Totals, headers, money and summary blocks are never service rows. */
    private val skipWords = listOf(
        "جمع", "مجموع", "حقوق", "بیمه", "سپرده", "غذا", "هتل", "عوارض",
        "خلاصه", "جزئیات", "گزارش", "درآمد", "هزینه", "سوخت", "تعمیرات",
        "نوع خدمت", "نوع سرویس", "تعداد سرویس", "مبلغ", "تومان",
        "total", "subtotal", "summary",
    )
    private val headerWords = listOf("تاریخ", "مبدا", "مبدأ", "مقصد", "ساعت", "کیلومتر", "سرویس")

    const val MAX_ROWS_PER_PAGE = 400
    private const val MAX_MERGE_LINES = 4

    /** PDF/OCR text often carries Arabic presentation forms (ﺗﺎ/ﺳﺎﻋﺖ…); NFKC first. */
    private fun normLatin(raw: String): String =
        PersianInput.latin(Normalizer.normalize(raw, Normalizer.Form.NFKC))

    private fun skip(line: String): Boolean = ReportLabels.forms(line).any { form ->
        skipWords.any { word -> form.contains(ReportLabels.normal(word)) }
    }
    private fun header(line: String): Boolean = ReportLabels.forms(line).any { form ->
        headerWords.count { form.contains(ReportLabels.normal(it)) } >= 2
    }

    /** "16:00 تا 06:00" -> 14:00; wraps past midnight; partial ranges return null. */
    private fun durationHours(range: String): String? {
        val (a, b) = clockToken.findAll(range).map { it.value }.toList().let { it.getOrNull(0) to it.getOrNull(1) }
        if (a.isNullOrBlank() || b.isNullOrBlank() || a == "--:--" || b == "--:--") return null
        fun mm(s: String): Int? = Regex("([0-9]{1,2}):([0-9]{2})").matchEntire(s)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val start = mm(a) ?: return null; val end = mm(b) ?: return null
        val diff = end - start + if (end < start) 1440 else 0
        if (diff !in 1..1439) return null
        return "${diff / 60}:%02d".format(diff % 60)
    }

    private class Shape(
        val date: String?, val ordinal: String?, val kmToken: String?, val hoursToken: String?,
        val assigned: Boolean, val anyTokens: Boolean, val unknownHoursRange: Boolean,
    )

    private fun shape(latin: String): Shape {
        val dates = PersianInput.datePattern.findAll(latin).mapNotNull { PersianInput.date(it.value) }.distinct()
        val date = dates.singleOrNull() // zero or two-different dates mean it is not a service row shape
        var rest = PersianInput.datePattern.replace(latin, " ")
        // Time range first: a fixed "HH:MM تا HH:MM" form supplies hours directly.
        val range = timeRange.find(rest)?.value
        val duration = range?.let(::durationHours)
        rest = timeRange.replace(rest, " ")
        val unknownHours = range != null && duration == null
        // Money with thousands separators is never km/hours.
        rest = commaMoney.replace(rest, " ")
        val tokens = numberToken.findAll(rest).map { it.value }.toList()
        if (range != null) {
            val km = tokens.firstOrNull { PersianInput.count(it)?.let { n -> n in 1..5000 } == true }
            return Shape(date, null, km, duration, km != null || duration != null, tokens.isNotEmpty(), unknownHours)
        }
        val ordinal = if (tokens.size == 3) PersianInput.count(tokens[0])?.takeIf { it in 1..10000 }?.toString() else null
        val triple = tokens.size == 3 && ordinal != null
        val pair = tokens.size == 2 && PersianInput.decimal(tokens[0]) != null &&
            PersianInput.hours(tokens[1]) != null && (':' in tokens[1] || '.' in tokens[1] || '٫' in tokens[1])
        return Shape(
            date, ordinal,
            if (triple) tokens[1] else if (pair) tokens[0] else null,
            if (triple) tokens[2] else if (pair) tokens[1] else null,
            triple || pair, tokens.isNotEmpty(), false,
        )
    }

    fun parsePage(pageId: String, pageNumber: Int, text: String): List<ReportRow> {
        require(text.length <= FastOcrClient.MAX_TEXT_BYTES)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        require(lines.size <= 10_000)
        val rows = mutableListOf<ReportRow>()
        val consumed = BooleanArray(lines.size)
        for (i in lines.indices) {
            if (rows.size >= MAX_ROWS_PER_PAGE || consumed[i]) continue
            val line = lines[i]
            if (skip(line) || header(line)) continue
            var latin = normLatin(line)
            var s = shape(latin)
            if (s.date == null) {
                // A dateless line is never a service row on its own: headers,
                // per-type summary lines and bare money totals stay out.
                continue
            }
            // Merge forward: a printed row often wraps over several OCR lines.
            var mergedRaw = line
            var merged = 0
            while (s.kmToken == null && merged + 1 < MAX_MERGE_LINES) {
                val j = i + merged + 1
                if (j >= lines.size || consumed[j] || skip(lines[j]) || header(lines[j])) break
                val nextLatin = normLatin(lines[j])
                if (PersianInput.datePattern.containsMatchIn(nextLatin)) break // next service row begins
                mergedRaw += " · " + lines[j]
                latin += " " + nextLatin
                merged++
                consumed[j] = true
                s = shape(latin)
            }
            val km = s.kmToken?.let(PersianInput::decimal)
            val hours = s.hoursToken?.takeIf { PersianInput.hours(it) != null }.orEmpty()
            rows += ReportRow(
                id = "$pageId:fastocr:${rows.size}", pageId = pageId, page = pageNumber, line = rows.size,
                raw = mergedRaw, date = requireNotNull(s.date), count = "1",
                km = km?.let(PersianInput::display).orEmpty(),
                hours = hours,
                confidence = 0f, included = true,
                note = "متن آنلاین FastOCR؛ " + when {
                    s.kmToken != null && hours.isNotBlank() -> "مقادیر را با اصل سند تطبیق دهید"
                    s.kmToken != null -> "ساعت نامشخص است (--:--)؛ خالی می‌ماند، صفر نیست"
                    s.hoursToken != null -> "کیلومتر روشن نیامد؛ دستی وارد کنید"
                    s.anyTokens -> "ترتیب/تعداد مقدارها آشکار نیست؛ کیلومتر و ساعت را از روی اصل تصویر وارد کنید"
                    else -> "کیلومتر/ساعت در این خط روشن نیامد؛ از روی اصل تصویر دستی وارد کنید"
                },
                kind = if (CompanyReportTemplate.isGrouped(mergedRaw)) ReportRowKind.GROUPED else ReportRowKind.SERVICE,
                ordinal = s.ordinal.orEmpty(),
                hoursBlank = Regex("(?:^|\\s)[.-](?:\\s|$)").containsMatchIn(latin),
            )
        }
        return rows
    }
}
