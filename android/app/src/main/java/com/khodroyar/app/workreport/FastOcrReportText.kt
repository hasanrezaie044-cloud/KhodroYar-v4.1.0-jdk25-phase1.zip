package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import java.math.BigDecimal
import java.text.Normalizer

/**
 * Plain-text reader for the fixed Sepahan statement and KhodroYar vehicle report.
 * A valid date anchors a review row; each individual row counts as ONE service,
 * never its ordinal. Kilometres/hours are optional, unverified prefill only.
 *
 * Sepahan's ordinal next to the date establishes logical RTL (ordinal, date,
 * route, km, hours, ignored columns) or visual LTR reading order. Date/ordinal
 * clusters bound whole rows, including wrapped lines. The vehicle report's
 * explicit type/time-range column establishes date-first order without ordinals.
 * Neither income tails, title dates nor footer totals may become row fields.
 * Unknown hours stay blank; neither optional metric gates daily count comparison.
 */
object FastOcrReportText {
    private val numberToken = Regex("[0-9]+(?::[0-9]{2}|[.٫][0-9]+)?")
    private val commaMoney = Regex("[0-9]{1,3}(?:[,٬][0-9]{3})+")
    private val clock = "(?:--:--|[0-9]{1,2}:[0-9]{2})"
    private val timeRange = Regex("$clock\\s*تا\\s*$clock")
    private val clockToken = Regex(clock)
    private val word = Regex("[آ-یA-Za-z]{2,}")
    // \z, NOT $: Java's $ also matches before a final newline, which let the
    // tail of the previous row's income (4,154,530\n) masquerade as an ordinal.
    private val ordinalBefore = Regex("(?<![0-9,٬./:٫])([0-9]{1,3})[ \\t]{0,8}\\z")
    private val ordinalAfter = Regex("\\A[ \\t]{0,8}([0-9]{1,3})(?![0-9,٬./:٫])")
    // A wrapped ordinal must occupy its ENTIRE cell line. In particular, never
    // match the last digits of an income line such as 4,154,530 before a newline.
    private val wrappedOrdinalBefore = Regex("(?:\\A|\\n)[ \\t]{0,8}([0-9]{1,3})[ \\t]{0,8}\\r?\\n[ \\t]{0,8}\\z")
    private val wrappedOrdinalAfter = Regex("\\A[ \\t]{0,8}\\r?\\n[ \\t]{0,8}([0-9]{1,3})[ \\t]{0,8}(?=\\r?\\n|\\z)")
    private val vehicleType = Regex("\\A\\s*(?:ثابت|شب|درخواستی|تعطیل)(?=\\s|[0-9-]|\\z)")
    private val titleSeparator = Regex("\\s*(?:تا|الی|[-–—]|to)\\s*", RegexOption.IGNORE_CASE)
    private val headerDateLabel = Regex("(?:تاریخ\\s*(?:گزارش|چاپ|تهیه)|(?:از|تا)\\s*تاریخ)\\s*[:：]?\\s*\\z")
    private val blankCell = Regex("(?:^|\\s)[.-](?:\\s|$)")
    private val sectionBoundary = Regex(
        "(?:^|\\n)[ \\t]*(?:جمع|مجموع|حقوق|بیمه|خلاصه|جزئیات|گزارش|خالص|دریافتی|کسور|total|subtotal|summary)(?=\\s|[:：0-9]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val footerLabels = listOf("جمع", "مجموع", "حقوق", "بیمه", "خلاصه", "خالص", "دریافتی", "کسور", "total", "subtotal", "summary")
    private val headerWords = listOf(
        "تاریخ", "مبدا", "مبدأ", "مقصد", "ساعت", "کیلومتر", "سرویس", "ردیف",
        "شرکت", "راننده", "شماره", "خودرو", "صورت", "حساب", "صفحه", "سری", "ماه",
    )

    const val MAX_ROWS_PER_PAGE = 500

    private data class Anchor(val start: Int, val end: Int, val date: String)
    private data class Token(val value: String, val start: Int, val end: Int)
    private data class Shape(val ordinal: String?, val km: String?, val hours: String?, val rangeUnknown: Boolean)
    private enum class Direction { AFTER_DATE, BEFORE_DATE, UNKNOWN }

    private fun blank(regex: Regex, text: String): String = regex.replace(text) { " ".repeat(it.value.length) }

    /** Partial/invalid ranges are UNKNOWN. A complete range may wrap past midnight. */
    private fun durationHours(range: String): String? {
        val clocks = clockToken.findAll(range).map { it.value }.toList()
        fun minutes(value: String?): Int? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return h * 60 + m
        }
        val start = minutes(clocks.getOrNull(0)) ?: return null
        val end = minutes(clocks.getOrNull(1)) ?: return null
        val duration = (end - start + 1440) % 1440
        if (duration == 0) return null
        return "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}"
    }

    private fun headerDate(full: String, anchor: Anchor): Boolean {
        val lineStart = full.lastIndexOf('\n', anchor.start - 1) + 1
        val prefix = full.substring(maxOf(lineStart, anchor.start - 320), anchor.start)
        if (headerDateLabel.containsMatchIn(prefix)) return true
        if (prefix.trim().trimEnd(':', '：') == "تاریخ") {
            val nextDate = PersianInput.datePattern.find(full, anchor.end)?.range?.first ?: full.length
            val following = full.substring(anchor.end, minOf(nextDate, anchor.end + 400))
            // A labelled service record may have no ordinal at all. Bound the
            // check to THIS date, so a title cannot borrow the next row's route.
            return !Regex("(?m)^[ \\t]*(?:مبدأ|مبدا|مقصد)[ \\t]*[:：]").containsMatchIn(following)
        }
        val suffix = full.substring(anchor.end, minOf(full.length, anchor.end + 40))
        val gluedAfter = word.find(suffix)?.takeIf { it.range.first == 0 }?.value.orEmpty()
        // A route may start with شرکت/هتل/عوارضی, even glued to its date.
        // Exclude explicitly labelled metadata, not every such place word.
        return footerLabels.any { prefix.trimStart().lowercase().startsWith(it) || gluedAfter.startsWith(it) } ||
            Regex("(?:کد\\s*راننده|شماره\\s*(?:خودرو|گزارش)|تاریخ\\s*صدور)\\s*[:：]?\\s*\\z").containsMatchIn(prefix)

    }

    private fun columns(
        win: String, dateStart: Int, dateEnd: Int, ordinal: Token?, direction: Direction,
    ): Shape {
        val range = timeRange.find(win)
        var masked = blank(timeRange, blank(commaMoney, win))
        masked = masked.replaceRange(dateStart, dateEnd, " ".repeat(dateEnd - dateStart))
        val tokens = numberToken.findAll(masked).map { Token(it.value, it.range.first, it.range.last + 1) }
            .filterNot { ordinal != null && it.start == ordinal.start && it.end == ordinal.end }
            .filter { when (direction) {
                Direction.AFTER_DATE -> it.start >= dateEnd
                Direction.BEFORE_DATE -> it.end <= dateStart
                Direction.UNKNOWN -> true
            } }.toList()
        fun distance(token: Token): Int = if (token.start >= dateEnd) token.start - dateEnd else dateStart - token.end
        if (range != null) {
            val km = tokens.filter { PersianInput.decimal(it.value)?.let { n -> n >= BigDecimal.ONE && n <= BigDecimal(3000) } == true }
                .minByOrNull(::distance)?.value
            val hours = durationHours(range.value)
            return Shape(ordinal?.value, km, hours, hours == null)
        }
        // The serial is at the FAR edge of this fixed form. The nearest valid
        // distance wins over 684/1241/1640 in that serial column, in either order.
        val km = tokens.filter { PersianInput.decimal(it.value)?.let { n -> n >= BigDecimal(40) && n <= BigDecimal(3000) } == true }
            .minByOrNull(::distance)
        val hours = tokens.filter { it !== km }
            .filter { token ->
                PersianInput.hours(token.value)?.let { h ->
                    h <= BigDecimal(24) && (h > BigDecimal.ZERO || (h.signum() == 0 && km != null &&
                        // An explicitly printed zero directly next to the km cell
                        // is a known value. A blank/dot or a far serial zero is NOT.
                        (if (token.end <= km.start) win.substring(token.end, km.start)
                         else if (km.end <= token.start) win.substring(km.end, token.start) else "not adjacent").isBlank()))
                } == true
            }
            .filter { t ->
                // A dot/dash between km and a later number means the HOURS cell
                // is blank; a small serial beyond those cells is not an hour.
                if (km == null) true else if (distance(t) < distance(km)) false else {
                    val gap = if (t.end <= km.start) win.substring(t.end, km.start)
                        else win.substring(km.end, t.start)
                    !blankCell.containsMatchIn(gap)
                }
            }.minByOrNull(::distance)?.value
        return Shape(ordinal?.value, km?.value, hours, false)
    }

    /** Formatting recovery only: never infer a missing date from app records. */
    internal fun normalizeLayout(text: String): String {
        var result = PersianInput.latin(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .replace(Regex("[\\u061c\\u200b\\u200e\\u200f\\u202a-\\u202e\\u2060\\u2066-\\u2069\\ufeff]"), "")
            .replace('|', ' ').replace('⁄', '/').replace('∕', '/').replace('／', '/').replace('\\', '/')
        // FastOCR exports can contain HTML tables. Preserve row boundaries and
        // empty cells as spacing; never execute markup or merge separate rows.
        result = result.replace(Regex("<\\s*(?:script|style)\\b[^>]*>.*?<\\s*/\\s*(?:script|style)\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<\\s*(?:/\\s*tr|br\\s*/?)\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&(?:nbsp|#160|#x0*a0);", RegexOption.IGNORE_CASE), " ")
        // Some structured exports put the ordinal/date labels on separate cell
        // lines. Only collapse this explicit pair; a printing date is untouched.
        result = Regex("(?m)^[ \\t]*ردیف\\s*[:：]\\s*([0-9]{1,3})[ \\t]*\\r?\\n[ \\t]*تاریخ\\s*[:：]\\s*").replace(result) { m ->
            if (PersianInput.count(m.groupValues[1]) in 1..MAX_ROWS_PER_PAGE) "${m.groupValues[1]} " else m.value
        }
        // Join a split four-digit year and spaces around date separators only.
        // Never join '1 1' after the last slash: day 1 + ordinal 1 is ambiguous.
        val spacedDate = Regex("(?<![0-9])(1[ \\t]*[3-6][ \\t]*[0-9][ \\t]*[0-9])[ \\t]*/[ \\t]*(0[ \\t]+[1-9]|[0-9]{1,2})[ \\t]*/[ \\t]*(0[ \\t]+[1-9]|[0-9]{1,2})(?![0-9])")
        result = spacedDate.replace(result) { m ->
            val date = (1..3).joinToString("/") { m.groupValues[it].filter(Char::isDigit) }
            if (PersianInput.date(date) != null) date else m.value
        }
        // A five/six-digit 'year' can be an ordinal glued BEFORE the full,
        // independently valid four-digit year. A 3/4-digit 'day' can be a
        // two-digit day followed by an ordinal. Compact dates are NOT split.
        val before = Regex("(?<![0-9])([0-9]{1,3})(1[3-6][0-9]{2}/[0-9]{1,2}/[0-9]{2})(?![0-9])")
        result = before.replace(result) { m ->
            if (PersianInput.count(m.groupValues[1]) in 1..MAX_ROWS_PER_PAGE && PersianInput.date(m.groupValues[2]) != null)
                "${m.groupValues[1]} ${m.groupValues[2]}" else m.value
        }
        val after = Regex("(?<![0-9])(1[3-6][0-9]{2}/[0-9]{1,2}/[0-9]{2})([0-9]{1,3})(?![0-9])")
        result = after.replace(result) { m ->
            if (PersianInput.date(m.groupValues[1]) != null && PersianInput.count(m.groupValues[2]) in 1..MAX_ROWS_PER_PAGE)
                "${m.groupValues[1]} ${m.groupValues[2]}" else m.value
        }
        return result
    }

    /** A gap means OCR coverage is uncertain, NOT that the driver took no trip. */
    fun missingOrdinals(rows: List<ReportRow>): List<Int> {
        val ordinals = rows.mapNotNull { PersianInput.count(it.ordinal)?.takeIf { n -> n in 1..MAX_ROWS_PER_PAGE } }.toSet()
        if (ordinals.size < 2) return emptyList()
        return (ordinals.minOrNull()!!..ordinals.maxOrNull()!!).filterNot { it in ordinals }
    }

    fun parsePage(pageId: String, pageNumber: Int, text: String): List<ReportRow> {
        require(text.length <= FastOcrClient.MAX_TEXT_BYTES)
        val full = normalizeLayout(text)
        require(full.length <= FastOcrClient.MAX_TEXT_BYTES)
        val dates = PersianInput.datePattern.findAll(full).mapNotNull { m ->
            PersianInput.date(m.value)?.let { Anchor(m.range.first, m.range.last + 1, it) }
        }.toList()
        if (dates.isEmpty()) return emptyList()
        val ignored = BooleanArray(dates.size) { headerDate(full, dates[it]) }
        for (i in 0 until dates.lastIndex) {
            if (titleSeparator.matches(full.substring(dates[i].end, dates[i + 1].start))) {
                ignored[i] = true; ignored[i + 1] = true
            }
        }
        // Never vote away a genuine dated row from a minority/adjacent month.
        // Header labels and period-title ranges already identify title dates.
        val sections = sectionBoundary.findAll(full).toList()
        val tableHeadings = Regex("(?m)^.*$").findAll(full).filter { line ->
            line.value.contains("تاریخ") && listOf("ردیف", "مبدأ", "مبدا", "مقصد", "کیلومتر", "ساعت").count { line.value.contains(it) } >= 2 &&
                !PersianInput.datePattern.containsMatchIn(line.value)
        }.map { it.range.last + 1 }.toList()
        val before = arrayOfNulls<Token>(dates.size)
        val after = arrayOfNulls<Token>(dates.size)
        var rtl = 0; var ltr = 0; var vehicle = false
        for (i in dates.indices) {
            if (ignored[i]) continue
            val anchor = dates[i]
            val prefixStart = maxOf(0, anchor.start - 32)
            val prefix = full.substring(prefixStart, anchor.start)
            (ordinalBefore.find(prefix) ?: wrappedOrdinalBefore.find(prefix))
                ?.takeIf { PersianInput.count(it.groupValues[1]) in 1..MAX_ROWS_PER_PAGE }?.let { m ->
                    val group = m.groups[1]!!
                    before[i] = Token(group.value, prefixStart + group.range.first, prefixStart + group.range.last + 1)
                }
            val suffix = full.substring(anchor.end, minOf(full.length, anchor.end + 32))
            (ordinalAfter.find(suffix) ?: wrappedOrdinalAfter.find(suffix))
                ?.takeIf { PersianInput.count(it.groupValues[1]) in 1..MAX_ROWS_PER_PAGE }?.let { m ->
                    val group = m.groups[1]!!
                    after[i] = Token(group.value, anchor.end + group.range.first, anchor.end + group.range.last + 1)
                }
            val prevEnd = if (i == 0) 0 else dates[i - 1].end
            val nextStart = dates.getOrNull(i + 1)?.start ?: full.length
            if (before[i] != null && word.containsMatchIn(full.substring(anchor.end, nextStart))) rtl++
            if (after[i] != null && word.containsMatchIn(full.substring(prevEnd, anchor.start))) ltr++
            if (vehicleType.containsMatchIn(full.substring(anchor.end, minOf(nextStart, anchor.end + 80)))) vehicle = true
        }
        val direction = when {
            vehicle -> Direction.AFTER_DATE
            rtl > 0 && rtl >= ltr -> Direction.AFTER_DATE
            ltr > 0 -> Direction.BEFORE_DATE
            else -> Direction.UNKNOWN
        }
        val rows = mutableListOf<ReportRow>()
        for (i in dates.indices) {
            if (rows.size >= MAX_ROWS_PER_PAGE) break
            if (ignored[i]) continue
            val anchor = dates[i]
            val ordinal = when (direction) {
                Direction.AFTER_DATE -> if (vehicle) null else before[i]
                Direction.BEFORE_DATE -> after[i]
                Direction.UNKNOWN -> before[i] ?: after[i]
            }
            val lineStart = full.lastIndexOf('\n', anchor.start - 1) + 1
            val lineEnd = full.indexOf('\n', anchor.end).let { if (it < 0) full.length else it }
            val sameLineBefore = full.substring(lineStart, anchor.start)
            val sameLineAfter = full.substring(anchor.end, lineEnd)
            // An ordinal-less row still has its own direction. A page-wide vote
            // cannot be a prerequisite for retaining its readable date/count.
            val rowDirection = when {
                vehicle -> Direction.AFTER_DATE
                sameLineBefore.trim().trimEnd(':', '：') == "تاریخ" -> Direction.AFTER_DATE
                word.containsMatchIn(sameLineAfter) && !word.containsMatchIn(sameLineBefore) -> Direction.AFTER_DATE
                word.containsMatchIn(sameLineBefore) && !word.containsMatchIn(sameLineAfter) -> Direction.BEFORE_DATE
                direction != Direction.UNKNOWN -> direction
                else -> Direction.AFTER_DATE
            }
            val rowOrdinal = when {
                vehicle -> null
                direction == Direction.UNKNOWN -> ordinal
                rowDirection == Direction.BEFORE_DATE -> after[i]
                else -> before[i]
            }
            var start = when (rowDirection) {
                Direction.AFTER_DATE -> rowOrdinal?.takeIf { it.start < anchor.start }?.start ?: anchor.start
                Direction.BEFORE_DATE -> if (i == 0) 0 else after[i - 1]?.end ?: dates[i - 1].end
                Direction.UNKNOWN -> anchor.start
            }
            var end = when (rowDirection) {
                Direction.AFTER_DATE -> if (i == dates.lastIndex) full.length
                    else if (vehicle) dates[i + 1].start else before[i + 1]?.start ?: dates[i + 1].start
                Direction.BEFORE_DATE -> rowOrdinal?.end ?: anchor.end
                Direction.UNKNOWN -> anchor.end
            }
            // Header dates bound windows but never donate their trailing codes.
            if (rowDirection == Direction.BEFORE_DATE && i > 0 && ignored[i - 1]) {
                val headerEnd = full.indexOf('\n', dates[i - 1].end)
                if (headerEnd in start until anchor.start) start = headerEnd + 1
            }
            if (start > anchor.start || end < anchor.end) continue
            sections.filter { it.range.first >= anchor.end && it.range.first < end }.minByOrNull { it.range.first }
                ?.let { end = it.range.first }
            sections.filter { it.range.first in start until anchor.start }.maxByOrNull { it.range.first }?.let {
                val boundaryEnd = full.indexOf('\n', it.range.last + 1)
                if (boundaryEnd in start until anchor.start) start = boundaryEnd + 1
            }
            if (start > anchor.start || end < anchor.end) continue
            val win = full.substring(start, end)
            val localOrdinal = rowOrdinal?.takeIf { it.start >= start && it.end <= end }
                ?.let { Token(it.value, it.start - start, it.end - start) }
            val withoutDate = win.replaceRange(anchor.start - start, anchor.end - start, " ".repeat(anchor.end - anchor.start))
            if (PersianInput.datePattern.findAll(withoutDate).any { PersianInput.date(it.value) != null }) continue
            val payload = blank(commaMoney, blank(timeRange, withoutDate))
            val explicitVehicle = vehicleType.containsMatchIn(full.substring(anchor.end, minOf(end, anchor.end + 80)))
            val tableStart = tableHeadings.lastOrNull { it <= anchor.start }
            val inTable = tableStart != null && sections.none { it.range.first in tableStart until anchor.start }
            val routeWords = word.findAll(payload).any { token ->
                headerWords.none { it == token.value } && footerLabels.none { token.value.startsWith(it) }
            }
            // Keep every real dated table cell, even if ALL optional columns
            // failed. Identical dates are separate rows, never deduplicated.
            // Outside a table, require row/route content; an isolated title date
            // is not a service and missing dates are never manufactured.
            if (localOrdinal == null && !explicitVehicle && !numberToken.containsMatchIn(payload) && !routeWords && !inTable) continue
            val shape = columns(win, anchor.start - start, anchor.end - start, localOrdinal, rowDirection)
            val km = shape.km?.let(PersianInput::decimal)
            val hours = shape.hours.orEmpty()
            val grouped = CompanyReportTemplate.isGrouped(win)
            rows += ReportRow(
                id = "$pageId:fastocr:${rows.size}", pageId = pageId, page = pageNumber, line = rows.size,
                raw = win.trim().take(160), date = anchor.date, count = "1",
                km = km?.let(PersianInput::display).orEmpty(), hours = hours,
                confidence = 0f, included = true,
                note = "متن گزارش؛ نیازمند بازبینی؛ " + when {
                    grouped -> "شرح تجمیعی است؛ تعداد سرویس آن قطعی نیست و پوشش آن باید دستی مشخص شود"
                    km != null && hours.isNotBlank() -> "مقادیر را با اصل سند تطبیق دهید"
                    km == null && hours.isBlank() -> "کیلومتر/ساعت خوانده نشد؛ تاریخ و تعداد این سرویس برای بازبینی نگه داشته شد"
                    km == null -> "کیلومتر خوانده نشد؛ دستی اضافه کنید"
                    shape.rangeUnknown -> "ساعت نامشخص است (--:--)؛ خالی می‌ماند، صفر نیست"
                    else -> "ساعت این ردیف خالی/نقطه است؛ نامشخص می‌ماند، صفر نیست"
                },
                kind = if (grouped) ReportRowKind.GROUPED else ReportRowKind.SERVICE,
                ordinal = shape.ordinal.orEmpty(), hoursBlank = hours.isBlank(),
            )
        }
        return rows
    }
}
