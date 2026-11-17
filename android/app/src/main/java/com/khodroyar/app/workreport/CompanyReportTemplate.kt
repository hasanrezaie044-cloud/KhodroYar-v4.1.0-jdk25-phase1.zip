package com.khodroyar.app.workreport

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.text.PersianInput
import java.text.Normalizer
import kotlin.math.abs

/** A cell read from the recurring company form. Empty text is UNKNOWN, not zero. */
data class CompanyOcrRow(
    val line: Int, val raw: String, val date: String, val ordinal: String,
    val km: String, val hours: String, val origin: String = "", val destination: String = "",
    val confidence: Float = 0f, val total: Boolean = false, val hoursBlank: Boolean = false,
)
enum class ReportRowKind { DAILY_TOTAL, SERVICE, GROUPED }
data class ReportPeriodProposal(val start: String = "", val end: String = "", val note: String = "")

/** Only labels, never numeric values, may be recognised in visual RTL order. */
internal object ReportLabels {
    fun normal(raw: String): String = PersianInput.latin(Normalizer.normalize(raw, Normalizer.Form.NFKC))
        .lowercase().replace(Regex("[ًٌٍَُِّْـءٔ]"), "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace(Regex("\\s+"), " ").trim()
    fun forms(raw: String): List<String> = normal(raw).let { listOf(it, it.reversed()) }
    fun has(raw: String, vararg labels: String) = forms(raw).any { s -> labels.any { normal(it) in s } }
    fun total(raw: String) = forms(raw).any { s ->
        Regex("(?:^|\\s)(?:جمع|مجموع|جمعکل|total|subtotal)(?:\\s|$)").containsMatchIn(s)
    }
    fun footerOnly(raw: String) = forms(raw).any { s ->
        Regex("^(?:جمع(?:\\s*کل)?|جمعکل|مجموع|total|subtotal)$").matches(s)
    }
}

/**
 * Explicit recognition of the fixed date/origin/destination/km/available-hours
 * form. «ردیف» is an ordinal, NEVER a count. Each page establishes its own
 * anchors; a continuation page cannot inherit another photograph's geometry.
 */
object CompanyReportTemplate {
    enum class Column { ORDINAL, DATE, ORIGIN, DESTINATION, KM, HOURS }
    data class Header(val columns: Map<Column, Float>, val top: Float, val bottom: Float) {
        fun bounds(column: Column): Pair<Float, Float>? {
            val x = columns[column] ?: return null
            val ordered = columns.entries.sortedBy { it.value }
            val i = ordered.indexOfFirst { it.key == column }
            val previous = ordered.getOrNull(i - 1)?.value
            val next = ordered.getOrNull(i + 1)?.value
            return maxOf(0f, previous?.let { (it + x) / 2 } ?: (x - ((next ?: (x + .08f)) - x) / 2)) to
                minOf(1f, next?.let { (it + x) / 2 } ?: (x + (x - (previous ?: (x - .08f))) / 2))
        }
    }
    fun column(raw: String): Column? {
        val forms = ReportLabels.forms(raw)
        return when {
            forms.any { "ردیف" in it || it == "row" || it == "ordinal" } -> Column.ORDINAL
            forms.any { "تاریخ" in it || it == "date" } -> Column.DATE
            forms.any { "مبدا" in it || it == "origin" } -> Column.ORIGIN
            forms.any { "مقصد" in it || it == "destination" } -> Column.DESTINATION
            forms.any { "کیلو" in it || it == "km" || it == "kilometers" } -> Column.KM
            forms.any { "ساعت" in it || "ساعات" in it || it == "hours" || it == "hour" } -> Column.HOURS
            else -> null
        }
    }
    fun header(words: List<OcrWord>): Header? {
        for (date in words.filter { column(it.text) == Column.DATE }) {
            val nearby = words.filter { abs(it.y - date.y) < .035f }
            if (nearby.any { ReportLabels.has(it.text, "تعداد", "count", "services") }) continue
            val anchors = nearby.mapNotNull { w -> column(w.text)?.let { it to w } }.groupBy({ it.first }, { it.second })
            val columns = anchors.mapValues { (_, values) -> values.map { it.x }.average().toFloat() }
            val d = columns[Column.DATE] ?: continue
            val k = columns[Column.KM] ?: continue
            val h = columns[Column.HOURS] ?: continue
            if (columns.keys.none { it in setOf(Column.ORDINAL, Column.ORIGIN, Column.DESTINATION) }) continue
            if (d - k !in .10f.. .70f || k - h !in .018f.. .20f) continue
            if (columns[Column.ORIGIN]?.let { it <= k || it >= d } == true) continue
            if (columns[Column.DESTINATION]?.let { it <= k || it >= d } == true) continue
            val used = anchors.values.flatten()
            return Header(columns, used.minOf { it.top }, used.maxOf { it.bottom })
        }
        return null
    }

    fun isGrouped(raw: String): Boolean {
        // Whole-token matching (both directions for bidi-misordered words): a
        // destination like «فولاد و پالبکس» must never match «نیمه»/«کارکرد»
        // fragments inside ordinary words, while «کارکرد نیمه اول» still counts.
        val words = ReportLabels.normal(raw).split(' ').filter { it.isNotBlank() }
        val tokens = words.flatMap { listOf(it, it.reversed()) }.toSet()
        if (tokens.any { it == "تجمیع" || it == "نیمه" }) return true
        return (tokens.contains("کارکرد") && (tokens.contains("دوره") || tokens.contains("ماه"))) ||
            (tokens.contains("جمع") && tokens.contains("سرویس"))
    }

    fun extract(page: OcrPage): List<ReportRow>? {
        val header = header(page.words)
        if (page.companyRows.isEmpty() && header == null) return null
        val cells = if (page.companyRows.isNotEmpty()) page.companyRows else fallback(page, requireNotNull(header))
        return cells.map { cell ->
            val grouped = isGrouped("${cell.origin} ${cell.destination}")
            ReportRow("${page.id}:company:${cell.line}", page.id, page.number, cell.line, cell.raw,
                ReportDateText.parse(cell.date).orEmpty(), "1", cell.km, ReportHoursText.normalize(cell.hours), cell.confidence,
                included = !cell.total,
                note = when {
                    cell.total -> "ردیف جمع؛ دوباره در محاسبه استفاده نمی‌شود"
                    grouped -> "شرح تجمیعی است؛ یک سرویس فرض نمی‌شود. پوشش آن باید در نتیجه به‌صورت دستی مشخص شود."
                    cell.confidence < 85f -> "اطمینان خواندن پایین است؛ با اصل تصویر تطبیق دهید"
                    else -> "هر ردیف یک سرویس است؛ شمارهٔ ردیف تعداد سرویس نیست. با اصل تصویر بازبینی کنید."
                }, kind = if (grouped) ReportRowKind.GROUPED else ReportRowKind.SERVICE,
                ordinal = cell.ordinal, origin = cell.origin, destination = cell.destination,
                hoursBlank = cell.hoursBlank || cell.hours.trim() in listOf(".", "-"))
        }
    }

    /** Fallback for a legible header but damaged/unavailable grid; no fabricated cells. */
    internal fun fallback(page: OcrPage, header: Header): List<CompanyOcrRow> {
        val output = mutableListOf<CompanyOcrRow>()
        for ((i, line) in WorkReportParser.lines(page.words).withIndex()) {
            if (line.map { it.y }.average() <= header.bottom) continue
            fun words(column: Column) = header.bounds(column)?.let { (l, r) -> line.filter { it.x in l..r } }.orEmpty()
            fun text(column: Column) = words(column).sortedByDescending { it.x }.joinToString(" ") { it.text }
            val date = WorkReportParser.dateCell(words(Column.DATE).filter { it.confidence >= 75f })?.first.orEmpty()
            val raw = line.sortedByDescending { it.x }.joinToString(" ") { it.text }
            val labelWords = line.filter { w -> w.text.any { it.isLetter() } }
            val footerLabelsOnly = labelWords.isNotEmpty() && labelWords.all { w -> ReportLabels.forms(w.text).any { it in listOf("جمع", "کل", "مجموع", "total", "subtotal") } }
            val total = date.isEmpty() && words(Column.ORDINAL).none { PersianInput.count(it.text)?.let { n -> n > 0 } == true } && ReportLabels.total(raw) && footerLabelsOnly
            val numericColumns = listOf(Column.DATE, Column.ORDINAL, Column.KM, Column.HOURS)
            if (!total && numericColumns.none { c -> words(c).any { w -> PersianInput.latin(w.text).any { it in '0'..'9' } } }) continue
            fun number(c: Column): String {
                val word = words(c).singleOrNull()?.takeIf { it.confidence >= 75f } ?: return ""
                val field = when (c) { Column.ORDINAL -> NumericField.ORDINAL; Column.KM -> NumericField.KM; Column.HOURS -> NumericField.HOURS; else -> return "" }
                return CompanyCellPolicy.canonical(field, word.text).orEmpty()
            }
            output += CompanyOcrRow(i, raw, date, number(Column.ORDINAL), number(Column.KM), number(Column.HOURS),
                text(Column.ORIGIN), text(Column.DESTINATION), line.minOf { it.confidence }, total,
                hoursBlank = words(Column.HOURS).singleOrNull()?.text?.trim() in listOf(".", "-"))
            if (total) break // Salary/insurance/payments below the grid are NOT service rows.
        }
        return output
    }

    /** A monthly form must include app-only days at both ends of the month. */
    fun period(pages: List<OcrPage>, rows: List<ReportRow>): ReportPeriodProposal {
        val fixed = pages.any { it.companyRows.isNotEmpty() || header(it.words) != null }
        val dates = rows.filter { it.included }.mapNotNull { PersianInput.date(it.date) }
        if (!fixed) return ReportPeriodProposal(dates.minOrNull().orEmpty(), dates.maxOrNull().orEmpty(), "بازهٔ کامل سند را بررسی کنید.")
        val declared = pages.mapNotNull { page ->
            val top = header(page.words)?.top ?: return@mapNotNull null
            val words = page.words.filter { it.bottom < top }
            val months = Jalali.MONTH_NAMES.mapIndexedNotNull { index, name ->
                if (words.any { ReportLabels.forms(it.text).any { s -> s == ReportLabels.normal(name) } }) index + 1 else null
            }
            val years = words.mapNotNull { PersianInput.latin(it.text).trim().takeIf { s -> Regex("1[3-6][0-9]{2}").matches(s) }?.toIntOrNull()?.takeIf { it in 1300..1600 } }.distinct()
            if (months.size == 1 && years.size == 1) years.single() to months.single() else null
        }.distinct()
        val fromRows = dates.map { it.take(7) }.distinct()
        if (declared.size > 1 || fromRows.size > 1) return ReportPeriodProposal(note = "ماه صفحه‌ها/ردیف‌ها یکسان نیست؛ بازهٔ کامل را صریحاً تعیین کنید.")
        val month = declared.singleOrNull() ?: fromRows.singleOrNull()?.let { it.take(4).toInt() to it.takeLast(2).toInt() }
            ?: return ReportPeriodProposal(note = "ماه گزارش خوانده نشد؛ ابتدا و انتهای کامل ماه را وارد کنید.")
        val range = Jalali.monthRange(month.first, month.second)
        if (dates.any { it !in range.first..range.second }) return ReportPeriodProposal(note = "ماه سربرگ با تاریخ ردیف‌ها سازگار نیست؛ بازه را با سند بررسی کنید.")
        return ReportPeriodProposal(range.first, range.second,
            if (declared.isNotEmpty()) "کل ماهِ سربرگ پیشنهاد شده؛ با سند تأیید کنید، نه فقط اولین/آخرین سرویس."
            else "کل ماه از تاریخ ردیف‌ها پیشنهاد شده؛ ماه و کامل بودن همهٔ صفحه‌ها را با سند تأیید کنید.")
    }

    fun duplicates(rows: List<ReportRow>): List<ReportRow> {
        val seen = mutableMapOf<String, ReportRow>()
        return rows.map { row ->
            val v = row.reviewed()
            val ordinal = PersianInput.count(row.ordinal)?.takeIf { it > 0 }
            if (v == null || ordinal == null || !row.included || v.hours == null && !row.hoursBlank) row else {
                val key = "$ordinal|${v.date}|${PersianInput.display(v.km)}|${v.hours?.let(PersianInput::display) ?: "<EMPTY-HOURS>"}"
                val previous = seen[key]
                if (previous != null && previous.pageId != row.pageId) row.copy(included = false, duplicateOf = previous.id,
                    note = "همان شمارهٔ ردیف، تاریخ، کیلومتر و ساعت در صفحهٔ ${previous.page}؛ مشکوک به اسکن تکراری و کنار گذاشته شد. اگر شماره‌ها از نو شروع شده‌اند، با اصل سند بازگردانید.")
                else { seen.putIfAbsent(key, row); row }
            }
        }
    }
}
