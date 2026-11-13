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
 * Unknown hours stay blank; all existing review/comparison gates still apply.
 */
object FastOcrReportText {
    private val numberToken = Regex("[0-9]+(?::[0-9]{2}|[.٫][0-9]+)?")
    private val commaMoney = Regex("[0-9]{1,3}(?:[,٬][0-9]{3})+")
    private val clock = "(?:--:--|[0-9]{1,2}:[0-9]{2})"
    private val timeRange = Regex("$clock\\s*تا\\s*$clock")
    private val clockToken = Regex(clock)
    private val word = Regex("[آ-یA-Za-z]{3,}")
    // \z, NOT $: Java's $ also matches before a final newline, which let the
    // tail of the previous row's income (4,154,530\n) masquerade as an ordinal.
    private val ordinalBefore = Regex("(?<![0-9,٬./:٫])([0-9]{1,2})[ \\t]{0,3}\\z")
    private val ordinalAfter = Regex("\\A[ \\t]{0,3}([0-9]{1,2})(?![0-9,٬./:٫])")
    private val vehicleType = Regex("\\A\\s*(?:ثابت|شب|درخواستی|تعطیل)(?=\\s|[0-9-]|\\z)")
    private val titleSeparator = Regex("\\s*(?:تا|الی|[-–—]|to)\\s*", RegexOption.IGNORE_CASE)
    private val headerDateLabel = Regex("(?:تاریخ\\s*(?:گزارش|چاپ|تهیه)|(?:از|تا)\\s*تاریخ)\\s*[:：]?\\s*\\z")
    private val blankCell = Regex("(?:^|\\s)[.-](?:\\s|$)")
    private val junkWords = listOf(
        "جمع", "مجموع", "حقوق", "بیمه", "سپرده", "غذا", "هتل", "عوارض",
        "خلاصه", "جزئیات", "گزارش", "درآمد", "هزینه", "سوخت", "تعمیرات",
        "وام", "پاداش", "معوقه", "جریمه", "تعاونی", "صندوق", "الحساب",
        "کسور", "دریافتی", "خالص", "مبلغ", "تومان", "کسر",
        "total", "subtotal", "summary",
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
        val prefix = full.substring(maxOf(lineStart, anchor.start - 160), anchor.start)
        if (headerDateLabel.containsMatchIn(prefix)) return true
        if (prefix.trim().trimEnd(':', '：') == "تاریخ") return true
        val gluedBefore = word.findAll(prefix).lastOrNull()?.takeIf { it.range.last == prefix.lastIndex }?.value.orEmpty()
        val suffix = full.substring(anchor.end, minOf(full.length, anchor.end + 40))
        val gluedAfter = word.find(suffix)?.takeIf { it.range.first == 0 }?.value.orEmpty()
        return headerWords.any { gluedBefore.startsWith(it) || gluedAfter.startsWith(it) } ||
            footerLabels.any { prefix.trimStart().lowercase().startsWith(it) || gluedAfter.startsWith(it) }
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
            .filter { PersianInput.hours(it.value)?.let { h -> h > BigDecimal.ZERO && h <= BigDecimal(24) } == true }
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

    fun parsePage(pageId: String, pageNumber: Int, text: String): List<ReportRow> {
        require(text.length <= FastOcrClient.MAX_TEXT_BYTES)
        val full = PersianInput.latin(Normalizer.normalize(text, Normalizer.Form.NFKC))
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
        // The supported fixed statement is monthly. Do not import its printing
        // date from another month. Header anchors still bound adjacent windows.
        val byMonth = dates.indices.filterNot { ignored[it] }.groupingBy { dates[it].date.take(7) }.eachCount()
        val top = byMonth.maxByOrNull { it.value }
        if (top != null && top.value >= 2 && byMonth.count { it.value == top.value } == 1) {
            dates.indices.filter { !dates[it].date.startsWith(top.key) }.forEach { ignored[it] = true }
        }
        val before = arrayOfNulls<Token>(dates.size)
        val after = arrayOfNulls<Token>(dates.size)
        var rtl = 0; var ltr = 0; var vehicle = false
        for (i in dates.indices) {
            if (ignored[i]) continue
            val anchor = dates[i]
            val prefixStart = maxOf(0, anchor.start - 16)
            ordinalBefore.find(full.substring(prefixStart, anchor.start))
                ?.takeIf { PersianInput.count(it.groupValues[1]) in 1..99 }?.let { m ->
                    val group = m.groups[1]!!
                    before[i] = Token(group.value, prefixStart + group.range.first, prefixStart + group.range.last + 1)
                }
            ordinalAfter.find(full.substring(anchor.end, minOf(full.length, anchor.end + 16)))
                ?.takeIf { PersianInput.count(it.groupValues[1]) in 1..99 }?.let { m ->
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
            var start = when (direction) {
                Direction.AFTER_DATE -> ordinal?.start ?: anchor.start
                Direction.BEFORE_DATE -> if (i == 0) 0 else after[i - 1]?.end ?: dates[i - 1].end
                Direction.UNKNOWN -> if (i == 0) 0 else maxOf((dates[i - 1].start + anchor.start) / 2, dates[i - 1].end)
            }
            var end = when (direction) {
                Direction.AFTER_DATE -> if (i == dates.lastIndex) full.length
                    else if (vehicle) dates[i + 1].start else before[i + 1]?.start ?: dates[i + 1].start
                Direction.BEFORE_DATE -> ordinal?.end ?: anchor.end
                Direction.UNKNOWN -> if (i == dates.lastIndex) full.length else minOf((anchor.end + dates[i + 1].start) / 2, dates[i + 1].start)
            }
            // A preceding excluded header may contain a driver code AFTER its
            // date. Exclude the rest of that header line as well as the date.
            if (direction == Direction.BEFORE_DATE && i > 0 && ignored[i - 1]) {
                val headerEnd = full.indexOf('\n', dates[i - 1].end)
                if (headerEnd in start until anchor.start) start = headerEnd + 1
            }
            if (start > anchor.start || end < anchor.end) continue
            val lower = full.substring(start, end).lowercase()
            val cutAfter = junkWords.mapNotNull { w ->
                lower.indexOf(w, anchor.end - start).takeIf { it >= 0 }?.let { start + it }
            }.minOrNull()
            val cutBefore = junkWords.mapNotNull { w ->
                lower.substring(0, anchor.start - start).lastIndexOf(w).takeIf { it >= 0 }?.let { start + it + w.length }
            }.maxOrNull()
            if (cutAfter != null) end = cutAfter
            if (cutBefore != null) start = cutBefore
            if (start > anchor.start || end < anchor.end) continue
            val win = full.substring(start, end)
            val localOrdinal = ordinal?.takeIf { it.start >= start && it.end <= end }
                ?.let { Token(it.value, it.start - start, it.end - start) }
            val withoutDate = win.replaceRange(anchor.start - start, anchor.end - start, " ".repeat(anchor.end - anchor.start))
            if (PersianInput.datePattern.findAll(withoutDate).any { PersianInput.date(it.value) != null }) continue
            val payload = blank(commaMoney, blank(timeRange, withoutDate))
            val explicitVehicle = vehicleType.containsMatchIn(full.substring(anchor.end, minOf(end, anchor.end + 80)))
            // Ordinal + date is sufficient even when km/hours are unreadable.
            // Date-only titles, salary lines and summaries are not service rows.
            if (localOrdinal == null && !explicitVehicle && !numberToken.containsMatchIn(payload)) continue
            val shape = columns(win, anchor.start - start, anchor.end - start, localOrdinal, direction)
            val km = shape.km?.let(PersianInput::decimal)
            val hours = shape.hours.orEmpty()
            val grouped = CompanyReportTemplate.isGrouped(win)
            rows += ReportRow(
                id = "$pageId:fastocr:${rows.size}", pageId = pageId, page = pageNumber, line = rows.size,
                raw = win.trim().take(160), date = anchor.date, count = "1",
                km = km?.let(PersianInput::display).orEmpty(), hours = hours,
                confidence = 0f, included = true,
                note = "متن آنلاین FastOCR؛ " + when {
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
