package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import java.math.BigDecimal
import java.security.MessageDigest

/** Coordinates are normalised after page rotation. No route information is needed for comparison. */
data class OcrWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}
data class OcrPage(val id: String, val number: Int, val words: List<OcrWord>, val text: String)

data class ReportColumns(val count: Int? = null, val km: Int? = null, val hours: Int? = null) {
    // Manual positions count numeric cells from RIGHT to LEFT, including the date cell.
    val manual get() = count != null || km != null || hours != null
    fun valid() = listOfNotNull(count, km, hours).let { it.size == 3 && it.distinct().size == 3 && it.all { n -> n in 1..16 } }
}

data class ReportRow(
    val id: String, val pageId: String, val page: Int, val line: Int, val raw: String,
    val date: String = "", val count: String = "", val km: String = "", val hours: String = "",
    val confidence: Float = 0f, val included: Boolean = true,
    val note: String = "", val duplicateOf: String? = null,
) {
    fun validated(): DailyWork? {
        val d = PersianInput.date(date) ?: return null
        val c = PersianInput.count(count) ?: return null
        val k = PersianInput.decimal(km) ?: return null
        val h = PersianInput.hours(hours) ?: return null
        return DailyWork(d, c, k, h)
    }
}

data class DailyWork(val date: String, val count: Int, val km: BigDecimal, val hours: BigDecimal)
data class WorkTotals(val count: Int, val km: BigDecimal, val hours: BigDecimal) {
    companion object {
        fun of(rows: List<DailyWork>) = WorkTotals(rows.sumOf { it.count }, rows.fold(BigDecimal.ZERO) { a, r -> a + r.km }, rows.fold(BigDecimal.ZERO) { a, r -> a + r.hours })
    }
}
enum class DayStatus(val label: String) { MATCH("مطابق"), MISSING_IN_APP("در خودرویار ثبت نشده"), MISSING_IN_REPORT("در گزارش شرکت نیست"), DIFFERENT("مغایرت روزانه") }
data class WorkDayComparison(val date: String, val company: DailyWork?, val app: DailyWork?) {
    val status: DayStatus get() = when {
        app == null -> DayStatus.MISSING_IN_APP
        company == null -> DayStatus.MISSING_IN_REPORT
        countDifference == 0 && kmDifference.abs() <= BigDecimal("0.000001") && hoursDifference.abs() <= BigDecimal("0.000001") -> DayStatus.MATCH
        else -> DayStatus.DIFFERENT
    }
    val countDifference get() = (company?.count ?: 0) - (app?.count ?: 0)
    val kmDifference get() = (company?.km ?: BigDecimal.ZERO) - (app?.km ?: BigDecimal.ZERO)
    val hoursDifference get() = (company?.hours ?: BigDecimal.ZERO) - (app?.hours ?: BigDecimal.ZERO)
}
data class WorkComparison(val days: List<WorkDayComparison>, val company: WorkTotals, val app: WorkTotals, val start: String, val end: String)

object WorkReportComparison {
    fun aggregate(rows: List<DailyWork>): List<DailyWork> = rows.groupBy { it.date }.toSortedMap().map { (date, entries) ->
        val t = WorkTotals.of(entries); DailyWork(date, t.count, t.km, t.hours)
    }
    fun compare(company: List<DailyWork>, app: List<DailyWork>, start: String, end: String): WorkComparison {
        require(PersianInput.date(start) != null && PersianInput.date(end) != null && start <= end)
        require(company.all { it.date in start..end })
        val c = aggregate(company).associateBy { it.date }
        val a = aggregate(app.filter { it.date in start..end }).associateBy { it.date }
        return WorkComparison((c.keys + a.keys).sorted().map { WorkDayComparison(it, c[it], a[it]) },
            WorkTotals.of(c.values.toList()), WorkTotals.of(a.values.toList()), start, end)
    }
}

/** Conservative, geometry-based extraction. Missing columns remain blank for user review. */
object WorkReportParser {
    private enum class Field { COUNT, KM, HOURS }
    private val numeric = Regex("[0-9]+(?:[,٬٫.:/-][0-9]+)*")
    private fun clean(s: String) = PersianInput.latin(s).lowercase().replace(Regex("\\s+"), " ").trim()
    private fun header(s: String): Field? = when {
        s.contains("تعداد") || s == "count" || s == "services" -> Field.COUNT
        s.contains("کیلومتر") || s.contains("کیلو متر") || s == "km" || s == "kilometers" -> Field.KM
        s.contains("ساعت") || s == "hours" || s == "hour" -> Field.HOURS
        else -> null
    }
    private fun excluded(s: String): String? = when {
        Regex("(?:^|\\s)(?:جمع|مجموع|جمعکل|subtotal|total)(?:\\s|$)").containsMatchIn(s) -> "ردیف جمع؛ در محاسبهٔ روزها استفاده نمی‌شود"
        s.contains("صفحه") || Regex("^page\\s+[0-9]").containsMatchIn(s) -> "پاورقی صفحه"
        listOf("تاریخ چاپ", "تاریخ تهیه", "نام راننده", "شماره خودرو", "کد پرسنلی").any { s.contains(it) } -> "سربرگ گزارش"
        else -> null
    }
    fun lines(words: List<OcrWord>): List<List<OcrWord>> {
        val groups = mutableListOf<MutableList<OcrWord>>()
        words.filter { it.text.isNotBlank() }.sortedBy { it.y }.forEach { w ->
            val line = groups.lastOrNull()
            val height = maxOf((w.bottom - w.top).coerceAtLeast(.008f), line?.maxOfOrNull { it.bottom - it.top } ?: 0f)
            if (line != null && kotlin.math.abs(line.map { it.y }.average() - w.y) <= height * .6) line.add(w)
            else groups.add(mutableListOf(w))
        }
        return groups.map { it.sortedBy { w -> w.left } }
    }
    private fun dateCell(line: List<OcrWord>): Pair<String, List<OcrWord>>? {
        val candidates = mutableListOf<Pair<String, List<OcrWord>>>()
        for (ordered in listOf(line.sortedBy { it.x }, line.sortedByDescending { it.x })) {
            for (start in ordered.indices) for (size in 1..minOf(7, ordered.size - start)) {
                val words = ordered.subList(start, start + size)
                if (words.zipWithNext().any { (a, b) -> maxOf(a.left, b.left) - minOf(a.right, b.right) > .045f }) continue
                val text = words.joinToString("") { PersianInput.latin(it.text).trim() }
                PersianInput.date(text)?.let { candidates += it to words.toList() }
            }
        }
        // Two distinct readable dates in one line are not one daily record.
        return candidates.takeIf { it.map { c -> c.first }.distinct().size == 1 }?.minByOrNull { it.second.size }
    }

    fun extract(pages: List<OcrPage>, columns: ReportColumns = ReportColumns()): List<ReportRow> {
        require(!columns.manual || columns.valid()) { "برای هر سه مقدار ستون‌های جداگانه انتخاب کنید." }
        val rows = mutableListOf<ReportRow>()
        pages.forEach { page ->
            var headings = emptyMap<Field, Float>()
            // Each page must establish its own header geometry. We never assume that
            // the next scan has the previous page's layout.
            lines(page.words).forEachIndexed { index, line ->
                val raw = line.sortedByDescending { it.x }.joinToString(" ") { it.text }
                val plain = clean(raw)
                val found = line.mapNotNull { w -> header(clean(w.text))?.let { it to w.x } }.toMap()
                if (found.size >= 2 && !PersianInput.datePattern.containsMatchIn(plain)) {
                    headings = found
                    return@forEachIndexed
                }
                val dateCell = dateCell(line)
                val date = dateCell?.first
                val dateWords = dateCell?.second.orEmpty()
                val numericWords = line.filter { it !in dateWords && numeric.matches(PersianInput.latin(it.text).trim()) } +
                    if (dateCell != null) listOf(OcrWord(dateCell.first, dateWords.minOf { it.left }, dateWords.minOf { it.top },
                        dateWords.maxOf { it.right }, dateWords.maxOf { it.bottom }, dateWords.minOf { it.confidence })) else emptyList()
                val skip = excluded(plain)
                // Pure titles/labels aren't daily data. Numeric, undated lines are retained,
                // not dropped: an unreadable date must be corrected or explicitly excluded.
                if (date == null && numericWords.isEmpty()) return@forEachIndexed
                val values = mutableMapOf<Field, String>()
                if (columns.manual) {
                    val cells = numericWords.sortedByDescending { it.x }
                    listOf(Field.COUNT to columns.count, Field.KM to columns.km, Field.HOURS to columns.hours).forEach { (f, pos) ->
                        cells.getOrNull(requireNotNull(pos) - 1)?.let { w ->
                            if (PersianInput.date(w.text) == null) values[f] = w.text
                        }
                    }
                } else if (headings.size == 3) {
                    val assignments = numericWords.filter { PersianInput.date(it.text) == null }
                        .mapNotNull { w ->
                            val distances = headings.map { (f, x) -> f to kotlin.math.abs(w.x - x) }.sortedBy { it.second }
                            val first = distances.first()
                            if (first.second > .13f || (distances.size > 1 && distances[1].second - first.second < .015f)) null else first.first to w
                        }.groupBy({ it.first }, { it.second })
                    assignments.forEach { (f, words) -> if (words.size == 1) values[f] = words.single().text }
                }
                val confidence = line.minOf { it.confidence }
                val note = skip ?: when {
                    date == null -> "تاریخ ناخوانا یا نامعتبر؛ باید اصلاح یا صریحاً کنار گذاشته شود"
                    values.size < 3 -> "ستون نامشخص؛ مقدار حدس زده نشده است"
                    confidence < 85 -> "اطمینان OCR پایین است؛ با تصویر تطبیق دهید"
                    else -> "با تصویر اصلی بازبینی کنید"
                }
                rows.add(ReportRow("${page.id}:$index", page.id, page.number, index, raw,
                    date.orEmpty(), values[Field.COUNT].orEmpty(), values[Field.KM].orEmpty(), values[Field.HOURS].orEmpty(),
                    confidence, included = skip == null, note = note))
            }
        }
        return duplicates(rows)
    }

    private fun duplicateEvidence(raw: String): String {
        val joined = clean(raw).replace('٫', '.').replace('٬', ',').replace(Regex("\\s*([/.:,-])\\s*"), "$1")
        return numeric.replace(joined) { hit ->
            PersianInput.date(hit.value) ?: (if (hit.value.contains(':')) PersianInput.hours(hit.value) else PersianInput.decimal(hit.value))
                ?.let(PersianInput::display) ?: hit.value
        }.replace(Regex("\\s+"), "")
    }
    fun duplicates(rows: List<ReportRow>): List<ReportRow> {
        val seen = mutableMapOf<String, MutableMap<String, MutableList<ReportRow>>>()
        return rows.map { row ->
            val v = row.validated()
            if (v == null || !row.included) row else {
                val key = "${v.date}|${v.count}|${PersianInput.display(v.km)}|${PersianInput.display(v.hours)}|${duplicateEvidence(row.raw)}"
                val pages = seen.getOrPut(key) { linkedMapOf() }
                val here = pages.getOrPut(row.pageId) { mutableListOf() }
                val ordinal = here.size
                val previous = pages.entries.firstNotNullOfOrNull { (page, matches) -> if (page != row.pageId) matches.getOrNull(ordinal) else null }
                here += row
                // Match occurrences, not every equal trip on a later page to one source.
                if (previous != null) row.copy(included = false, duplicateOf = previous.id,
                    note = "مشکوک به تکرار ردیف صفحهٔ ${previous.page}؛ پیش‌فرض از جمع کنار گذاشته شد. قابل بازگرداندن است.")
                else row
            }
        }
    }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
