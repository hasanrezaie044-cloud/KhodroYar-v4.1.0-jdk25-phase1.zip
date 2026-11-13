package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput

/**
 * FastOCR returns plain UTF-8 text (no coordinates), preserving logical reading
 * order. Each candidate line is parsed CONSERVATIVELY: a valid Jalali date must
 * be present, and numbers may be assigned to کیلومتر/ساعت ONLY when the shape is
 * unambiguous — either "<ordinal> <km> <hours>" (3 tokens, first a small count)
 * or "<km> <hours>" where the hours token visibly carries ':' or '.'. Every
 * other shape (extra tokens, missing values, totals/salary labels) leaves the
 * cells blank for manual review. No value is ever inferred from neighbours and
 * absent hours stay blank — never zero — so the unchanged strict comparison
 * keeps requiring real user review.
 */
object FastOcrReportText {
    private val numberToken = Regex("[0-9]+(?::[0-9]{2}|[.٫][0-9]+)?")
    /** Totals, salary and payment lines are never service rows. */
    private val skipWords = listOf("جمع", "مجموع", "حقوق", "بیمه", "سپرده", "غذا", "هتل", "عوارض", "total", "subtotal")

    const val MAX_ROWS_PER_PAGE = 400

    private fun skip(line: String): Boolean = ReportLabels.forms(line).any { form ->
        skipWords.any { word -> form.contains(ReportLabels.normal(word)) }
    }

    fun parsePage(pageId: String, pageNumber: Int, text: String): List<ReportRow> {
        require(text.length <= FastOcrClient.MAX_TEXT_BYTES)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        require(lines.size <= 10_000)
        val rows = mutableListOf<ReportRow>()
        for (line in lines) {
            if (rows.size >= MAX_ROWS_PER_PAGE) break
            if (skip(line)) continue
            val latin = PersianInput.latin(line)
            val dates = PersianInput.datePattern.findAll(latin).mapNotNull { PersianInput.date(it.value) }.distinct()
            val date = dates.singleOrNull()
            // Remove every date-shaped span before extracting plain numbers so a
            // date's day/month can never leak into km/hours.
            val withoutDates = PersianInput.datePattern.replace(latin, " ")
            val tokens = numberToken.findAll(withoutDates).map { it.value }.toList()
            if (date == null && tokens.isEmpty()) continue // pure title/label lines are not rows
            // Unambiguous shapes only:
            //  - 3 tokens: <ردیف> <کیلومتر> <ساعت>, first must be a believable ordinal
            //  - 2 tokens: <کیلومتر> <ساعت>, only when the hours token carries ':' or '.'
            // Anything else leaves ردیف/کیلومتر/ساعت blank for manual review.
            val ordinal = if (tokens.size == 3) PersianInput.count(tokens[0])?.takeIf { it in 1..10000 }?.toString() else null
            val triple = tokens.size == 3 && ordinal != null
            val pair = tokens.size == 2 && PersianInput.decimal(tokens[0]) != null &&
                PersianInput.hours(tokens[1]) != null && (':' in tokens[1] || '.' in tokens[1] || '٫' in tokens[1])
            val assigned = triple || pair
            val kmToken = if (assigned) (if (triple) tokens[1] else tokens[0]) else null
            val hoursToken = if (assigned) (if (triple) tokens[2] else tokens[1]) else null
            val km = kmToken?.let(PersianInput::decimal)
            val hoursOk = hoursToken?.let { PersianInput.hours(it) != null } == true
            val suspiciousOrdinal = tokens.size == 3 && ordinal == null
            rows += ReportRow(
                id = "$pageId:fastocr:${rows.size}", pageId = pageId, page = pageNumber, line = rows.size,
                raw = line, date = date.orEmpty(), count = "1",
                km = km?.let(PersianInput::display).orEmpty(),
                // Hours keep their human token ("12:15"/"8.5"), never a synthesized decimal/zero.
                hours = if (assigned && hoursOk) hoursToken.orEmpty() else "",
                confidence = 0f, included = true,
                note = "متن آنلاین FastOCR؛ " + when {
                    date == null -> "تاریخ معتبر واحد پیدا نشد؛ دستی وارد کنید"
                    !assigned && tokens.isNotEmpty() -> "ترتیب/تعداد مقدارها آشکار نیست؛ کیلومتر و ساعت را از روی اصل تصویر وارد کنید"
                    suspiciousOrdinal -> "ردیف اول نامعتبر است؛ اعداد را از روی اصل تصویر وارد کنید"
                    !assigned -> "مقداری برای کیلومتر/ساعت پیدا نشد؛ دستی وارد کنید"
                    km == null -> "کیلومتر ناخوانا ماند؛ دستی وارد کنید"
                    !hoursOk -> "ساعت نامشخص است؛ اگر ساعت دارد دستی وارد کنید، وگرنه خالی می‌ماند (صفر نیست)"
                    else -> "مقادیر را با اصل تصویر تطبیق دهید"
                },
                kind = if (CompanyReportTemplate.isGrouped(line)) ReportRowKind.GROUPED else ReportRowKind.SERVICE,
                ordinal = ordinal.orEmpty(),
                hoursBlank = Regex("(?:^|\\s)[.-](?:\\s|$)").containsMatchIn(withoutDates),
            )
        }
        return rows
    }
}
