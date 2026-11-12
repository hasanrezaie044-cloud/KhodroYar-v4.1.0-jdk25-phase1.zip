package com.khodroyar.app.reconciliation

import com.khodroyar.app.core.persian.PersianText
import java.math.BigDecimal

/** The comparison API intentionally cannot accept origin/destination/type/route. */
data class AppWorkRecord(val id: String, val date: String, val kilometers: Double, val hours: Double)
data class StatementPage(val id: String, val reviewed: Boolean = false)
data class RowArea(val left: Int, val top: Int, val right: Int, val bottom: Int)
enum class StatementRowKind { SERVICE, TOTAL, OTHER }
data class StatementRow(
    val id: String,
    val pageId: String,
    val date: String = "",
    val kilometers: String = "",
    val hours: String = "",
    val count: String = "1",
    val rawText: String = "",
    val confidence: Int = 0,
    val area: RowArea? = null,
    val kind: StatementRowKind = StatementRowKind.SERVICE,
    val confirmed: Boolean = false,
    val excluded: Boolean = false,
    /** Same values on different pages can also be legitimate trips. Never auto-delete them. */
    val keepDuplicate: Boolean = false,
)

data class WorkTotals(val services: Int = 0, val kilometers: BigDecimal = BigDecimal.ZERO, val hours: BigDecimal = BigDecimal.ZERO) {
    operator fun plus(other: WorkTotals) = WorkTotals(Math.addExact(services, other.services), kilometers + other.kilometers, hours + other.hours)
    operator fun minus(other: WorkTotals) = WorkTotals(Math.subtractExact(services, other.services), kilometers - other.kilometers, hours - other.hours)
}
enum class DayStatus { MATCHED, DISCREPANCY, REVIEW }
data class DailyComparison(
    val date: String, val app: WorkTotals, val company: WorkTotals,
    /** Always app minus company. Positive service count = missing from company. */
    val difference: WorkTotals, val status: DayStatus, val explanations: List<String>,
)
data class ReconciliationReport(
    val from: String, val to: String, val days: List<DailyComparison>,
    val app: WorkTotals, val company: WorkTotals, val difference: WorkTotals,
    val reviewIssues: List<String>,
) {
    val final: Boolean get() = reviewIssues.isEmpty()
    val matchedDays: Int get() = days.count { it.status == DayStatus.MATCHED }
    val discrepancyDays: Int get() = days.count { it.status == DayStatus.DISCREPANCY }
    val reviewDays: Int get() = days.count { it.status == DayStatus.REVIEW }
}

object ReconciliationEngine {
    // Decimal arithmetic avoids 0.1 + 0.2 floating-point false discrepancies.
    private val epsilon = BigDecimal("0.000000001")
    private fun different(v: BigDecimal) = v.abs() > epsilon

    fun values(row: StatementRow): Pair<String, WorkTotals>? {
        val date = PersianText.date(row.date) ?: return null
        val km = PersianText.decimal(row.kilometers) ?: return null
        val hours = PersianText.hours(row.hours) ?: return null
        val count = PersianText.count(row.count) ?: return null
        return date to WorkTotals(count, km, hours)
    }

    /** Flags both copies, but not two equal services on the SAME page/day. */
    fun duplicateCandidates(rows: List<StatementRow>): Set<String> = rows
        .filter { !it.excluded && it.kind == StatementRowKind.SERVICE }
        .mapNotNull { r -> values(r)?.let { (d, t) ->
            Triple("$d|${t.services}|${t.kilometers.stripTrailingZeros()}|${t.hours.stripTrailingZeros()}", r.pageId, r.id)
        } }.groupBy { it.first }.values.filter { group -> group.map { it.second }.distinct().size > 1 }
        .flatMap { it.map { entry -> entry.third } }.toSet()

    fun compare(
        records: List<AppWorkRecord>, rows: List<StatementRow>, pages: List<StatementPage>,
        from: String, to: String,
    ): ReconciliationReport {
        val start = requireNotNull(PersianText.date(from)) { "تاریخ شروع معتبر نیست" }
        val end = requireNotNull(PersianText.date(to)) { "تاریخ پایان معتبر نیست" }
        require(start <= end) { "شروع بازه باید پیش از پایان باشد" }
        require(rows.map { it.id }.distinct().size == rows.size) { "شناسه ردیف تکراری" }
        require(pages.map { it.id }.distinct().size == pages.size) { "شناسه صفحه تکراری" }
        val issues = mutableListOf<String>()
        val uncertainDates = mutableSetOf<String>()
        var unknownDate = false
        val app = sortedMapOf<String, WorkTotals>()
        val company = sortedMapOf<String, WorkTotals>()
        val pageIds = pages.map { it.id }.toSet()
        if (pages.isEmpty()) issues += "هیچ صفحه‌ای وارد نشده است."
        pages.filter { !it.reviewed }.forEach { issues += "صفحه ${it.id}: بازبینی تصویر و کامل بودن ردیف‌ها تأیید نشده است." }
        val duplicateIds = duplicateCandidates(rows)
        records.forEach { record ->
            val date = PersianText.date(record.date)
            if (date == null) {
                issues += "تاریخ سرویس برنامه با شناسه ${record.id} معتبر نیست؛ رکورد بدون تغییر باقی ماند."
                unknownDate = true
            } else if (date in start..end) {
                if (!record.kilometers.isFinite() || record.kilometers < 0 || !record.hours.isFinite() || record.hours < 0) {
                    issues += "کارکرد سرویس برنامه ${record.id} معتبر نیست؛ برای اصلاح به صفحهٔ سرویس مراجعه کنید."
                    uncertainDates += date
                } else app[date] = (app[date] ?: WorkTotals()) + WorkTotals(1, BigDecimal.valueOf(record.kilometers), BigDecimal.valueOf(record.hours))
            }
        }
        rows.forEach { row ->
            if (row.kind != StatementRowKind.SERVICE || row.excluded) return@forEach
            val value = values(row)
            val date = PersianText.date(row.date)
            // Unreadable dates could belong to this period, so they always block finalisation.
            val relevant = date == null || date in start..end
            if (!relevant) return@forEach
            val error = when {
                row.pageId !in pageIds -> "صفحهٔ مرجع یافت نشد"
                value == null -> "تاریخ، تعداد، کیلومتر یا ساعت خوانا و کامل نیست (خالی برابر صفر نیست)"
                !row.confirmed -> "مقادیر استخراج‌شده هنوز تأیید نشده‌اند"
                row.id in duplicateIds && !row.keepDuplicate -> "احتمال تکرار بین صفحات؛ نگه‌داشتن یا کنارگذاشتن را صریحاً انتخاب کنید"
                else -> null
            }
            if (error != null) {
                issues += "ردیف ${row.id}: $error."
                if (date != null) uncertainDates += date else unknownDate = true
            } else if (value != null) {
                company[value.first] = (company[value.first] ?: WorkTotals()) + value.second
            }
        }
        // Unreviewed/empty pages may contain services not recognised by OCR.
        val incompleteDocument = pages.isEmpty() || pages.any { !it.reviewed } || unknownDate
        val dates = (app.keys + company.keys + uncertainDates).filter { it in start..end }.toSortedSet()
        val days = dates.map { date ->
            val a = app[date] ?: WorkTotals(); val c = company[date] ?: WorkTotals(); val diff = a - c
            val notes = mutableListOf<String>()
            if (diff.services > 0) notes += "${PersianText.fa(diff.services.toString())} سرویس از صورت‌وضعیت شرکت کم است."
            if (diff.services < 0) notes += "${PersianText.fa((-diff.services).toString())} سرویس شرکت در برنامه ثبت نشده است."
            if (different(diff.kilometers)) notes += "اختلاف ${PersianText.format(diff.kilometers.abs())} کیلومتر؛ ${if (diff.kilometers.signum() > 0) "برنامه بیشتر" else "شرکت بیشتر"}."
            if (different(diff.hours)) notes += "اختلاف ${PersianText.format(diff.hours.abs())} ساعت؛ ${if (diff.hours.signum() > 0) "برنامه بیشتر" else "شرکت بیشتر"}."
            val status = when {
                incompleteDocument || date in uncertainDates -> DayStatus.REVIEW
                notes.isNotEmpty() -> DayStatus.DISCREPANCY
                else -> DayStatus.MATCHED
            }
            DailyComparison(date, a, c, diff, status,
                if (status == DayStatus.REVIEW) listOf("نتیجهٔ این روز موقت است؛ موارد نارنجی و تصویر صفحات را بازبینی کنید.") else notes.ifEmpty { listOf("تعداد سرویس، کیلومتر و ساعت برابر است.") })
        }
        val totalApp = app.values.fold(WorkTotals(), WorkTotals::plus)
        val totalCompany = company.values.fold(WorkTotals(), WorkTotals::plus)
        return ReconciliationReport(start, end, days, totalApp, totalCompany, totalApp - totalCompany, issues)
    }
}
