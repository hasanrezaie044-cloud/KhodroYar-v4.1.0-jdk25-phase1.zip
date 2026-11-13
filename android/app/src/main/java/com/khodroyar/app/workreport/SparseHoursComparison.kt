package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import java.math.BigDecimal

/** A reviewed company row can have KNOWN date/km/count and ABSENT hours. */
data class ReviewedWork(val date: String, val count: Int, val km: BigDecimal, val hours: BigDecimal?)

fun ReportRow.reviewed(): ReviewedWork? {
    val date = ReportDateText.parse(this.date) ?: return null
    val count = PersianInput.count(this.count) ?: return null
    if (kind != ReportRowKind.DAILY_TOTAL && count != 1) return null
    val km = PersianInput.decimal(this.km) ?: return null
    val hours = if (ReportHoursText.isEmpty(this.hours)) null else PersianInput.hours(this.hours) ?: return null
    return ReviewedWork(date, count, km, hours)
}

/** Specific, recomputed messages do not keep a stale OCR failure after editing. */
fun ReportRow.fieldMessages(): List<String> = buildList {
    if (ReportDateText.parse(date) == null) add("تاریخ این ردیف خوانده نشد؛ دستی وارد کنید")
    if (PersianInput.count(count) == null || kind != ReportRowKind.DAILY_TOTAL && PersianInput.count(count) != 1) add("تعداد معتبر نیست؛ شمارهٔ ردیف تعداد سرویس نیست")
    if (PersianInput.decimal(km) == null) add("کیلومتر این ردیف خوانده نشد؛ دستی وارد کنید")
    if (ReportHoursText.isEmpty(hours)) add(if (hoursBlank) "ساعت خالی است؛ صفر فرض نشده و اختلاف ساعت نامشخص می‌ماند" else "ساعت خالی یا ناخواناست؛ اختلاف ساعت نامشخص می‌ماند")
    else if (PersianInput.hours(hours) == null) add("ساعت معتبر نیست؛ اصلاح کنید یا خالی بگذارید تا نامشخص بماند")
}

data class ReviewedTotals(val count: Int, val km: BigDecimal, val hours: BigDecimal?) {
    companion object {
        fun of(values: List<ReviewedWork>): ReviewedTotals = ReviewedTotals(values.sumOf { it.count },
            values.fold(BigDecimal.ZERO) { sum, value -> sum + value.km },
            if (values.any { it.hours == null }) null else values.fold(BigDecimal.ZERO) { sum, value -> sum + requireNotNull(value.hours) })
    }
}

enum class ReviewedDayStatus(val label: String) {
    EQUAL_TOTALS("جمع روز برابر است؛ نه اثبات هویت سرویس‌ها"), UNKNOWN_HOURS("تعداد/کیلومتر برابر؛ ساعت نامشخص"),
    DIFFERENT("مغایرت جمع روز"), MISSING_IN_APP("در این روز رکوردی در برنامه نیست"),
    MISSING_IN_REPORT("در این روز ردیفی در شرکت نیست"), GROUPED("پوشش تجمیع نامشخص؛ تعداد سرویس قابل استنتاج نیست"),
}

data class ReviewedWorkDay(val date: String, val company: ReviewedTotals?, val app: WorkTotals?, val unknownHours: Int, val serviceCountKnown: Boolean) {
    val countDifference: Int? get() = if (serviceCountKnown) (company?.count ?: 0) - (app?.count ?: 0) else null
    val status: ReviewedDayStatus get() = when {
        app == null -> ReviewedDayStatus.MISSING_IN_APP
        company == null -> ReviewedDayStatus.MISSING_IN_REPORT
        !serviceCountKnown -> ReviewedDayStatus.GROUPED
        countDifference != 0 || kmDifference.abs() > BigDecimal("0.000001") -> ReviewedDayStatus.DIFFERENT
        hoursDifference == null -> ReviewedDayStatus.UNKNOWN_HOURS
        requireNotNull(hoursDifference).abs() > BigDecimal("0.000001") -> ReviewedDayStatus.DIFFERENT
        else -> ReviewedDayStatus.EQUAL_TOTALS
    }
    val kmDifference get() = (company?.km ?: BigDecimal.ZERO) - (app?.km ?: BigDecimal.ZERO)
    val hoursDifference: BigDecimal? get() = if (unknownHours > 0) null else (company?.hours ?: BigDecimal.ZERO) - (app?.hours ?: BigDecimal.ZERO)
}

data class SparseHoursResult(
    val start: String, val end: String, val days: List<ReviewedWorkDay>,
    val company: ReviewedTotals, val app: WorkTotals,
    val companyRows: List<ReportRow>, val appServices: List<AppWorkService>,
    val heldDates: Set<String>, val deferredLinkCount: Int,
    /** Only unaffected, completely known days. Never the entire period's totals. */
    val completeDays: WorkComparison,
) {
    val serviceCountKnown get() = companyRows.none { it.kind == ReportRowKind.GROUPED }
}

/**
 * Does NOT change ServiceWorkComparison or give it manufactured zero hours.
 * Hold ALL candidates on an incomplete day (or the whole scope of a grouped
 * statement). Still show real daily counts/km across the COMPLETE period.
 * Full service matching remains the existing strict engine on unaffected days.
 */
object SparseHoursComparison {
    fun compare(company: List<ReportRow>, app: List<AppWorkService>, start: String, end: String,
        links: List<ReportMatchLink> = emptyList()): SparseHoursResult {
        require(company.size <= 10000 && app.size <= 10000 && links.size <= 10000)
        require(links.sumOf { it.companyIds.size.toLong() } <= 10000 && links.sumOf { it.appIds.size.toLong() } <= 10000)
        require(PersianInput.date(start) == start && PersianInput.date(end) == end && start <= end)
        val selected = company.filter { it.included }.map { it.copy(date = requireNotNull(ReportDateText.parse(it.date))) }
        val values = selected.associate { it.id to requireNotNull(it.reviewed()) { "تاریخ/کیلومتر/تعداد را اصلاح کنید؛ ساعت خالی صفر نمی‌شود." } }
        require(values.size == selected.size && selected.isNotEmpty() && values.values.all { it.date in start..end })
        require(values.values.any { it.hours == null })
        require(app.map { it.id }.distinct().size == app.size && app.all { it.id.isNotBlank() && PersianInput.date(it.date) == it.date && it.km >= BigDecimal.ZERO && it.hours >= BigDecimal.ZERO })
        val inRange = app.filter { it.date in start..end }.sortedWith(compareBy({ it.date }, { it.startTime }, { it.id }))
        val currentApp = inRange.associateBy { it.id }
        // Deferred links must still satisfy the same stale/disjoint safeguards.
        require(links.flatMap { it.companyIds }.distinct().size == links.sumOf { it.companyIds.size })
        require(links.flatMap { it.appIds }.distinct().size == links.sumOf { it.appIds.size })
        for (link in links) {
            require(link.companyIds.isNotEmpty() && link.appIds.isNotEmpty() && link.companyIds.all { it in values })
            val records = link.appIds.map { requireNotNull(currentApp[it]) { "سرویس تطبیق دستی تغییر کرده یا بیرون بازه است." } }
            require(ServiceWorkComparison.fingerprint(records) == link.appFingerprint) { "سوابق تطبیق دستی تغییر کرده‌اند؛ دوباره بازبینی کنید." }
        }
        val held = (if (selected.any { it.kind == ReportRowKind.GROUPED })
            (selected.map { it.date } + inRange.map { it.date }).toSet()
        else selected.filter { values.getValue(it.id).hours == null }.map { it.date }.toSet()).toMutableSet()
        // A cross-date manual link must be deferred as a whole, transitively.
        // Its other records must not leak back into automatic strict matching.
        val linkDates = links.map { link -> (link.companyIds.map { values.getValue(it).date } + link.appIds.map { currentApp.getValue(it).date }).toSet() }
        val linksByDate = mutableMapOf<String, MutableList<Int>>()
        linkDates.forEachIndexed { index, dates -> dates.forEach { date -> linksByDate.getOrPut(date) { mutableListOf() } += index } }
        val pending = java.util.ArrayDeque(held)
        val deferred = mutableSetOf<Int>()
        while (pending.isNotEmpty()) for (index in linksByDate[pending.removeFirst()].orEmpty()) {
            if (deferred.add(index)) for (date in linkDates[index]) if (held.add(date)) pending.addLast(date)
        }
        val complete = selected.filter { it.date !in held }
        val completeIds = complete.map { it.id }.toSet()
        val activeLinks = links.filter { link -> link.companyIds.all { it in completeIds } && link.appIds.all { currentApp.getValue(it).date !in held } }
        val strict = ServiceWorkComparison.compare(complete, inRange.filter { it.date !in held }, start, end, activeLinks)
        val cByDate = selected.groupBy { it.date }
        val aByDate = inRange.groupBy { it.date }
        val days = (cByDate.keys + aByDate.keys).sorted().map { date ->
            val rows = cByDate[date].orEmpty()
            val readings = rows.map { values.getValue(it.id) }
            ReviewedWorkDay(date, readings.takeIf { it.isNotEmpty() }?.let(ReviewedTotals::of),
                aByDate[date]?.let { records -> WorkTotals.of(records.map { it.work() }) },
                readings.count { it.hours == null }, rows.none { it.kind == ReportRowKind.GROUPED })
        }
        return SparseHoursResult(start, end, days, ReviewedTotals.of(values.values.toList()), WorkTotals.of(inRange.map { it.work() }),
            selected, inRange, held, links.size - activeLinks.size, strict)
    }
}
