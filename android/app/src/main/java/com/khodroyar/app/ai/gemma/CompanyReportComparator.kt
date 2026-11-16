package com.khodroyar.app.ai.gemma

import kotlin.math.abs

/**
 * Deterministic comparison between an extracted company report and the app's own service
 * records. The AI model extracts; THIS code compares. Numbers in the output are computed
 * here, so they are exact and reproducible — the model only ever paraphrases them for
 * the user afterwards, and every uncertain extraction stays flagged.
 */
object CompanyReportComparator {

    /** An app-side service, stripped of Room types so this stays pure JVM. */
    data class AppServiceRow(
        val id: String,
        val date: String,
        val km: Double,
        val hours: Double,
        val income: Long,
    )

    const val KM_TOLERANCE = 0.5     // km equal within ±0.5
    const val HOURS_TOLERANCE = 0.25 // hours equal within ±15 minutes

    enum class RowStatus { MATCHED, DIFFERS, MISSING_IN_APP, EXTRA_IN_APP, DUPLICATE_PAPER, DUPLICATE_APP }

    data class RowComparison(
        val status: RowStatus,
        val paperIndex: Int,
        val paperDate: String,
        val paperKm: Double?,
        val paperHours: Double?,
        val paperRoute: String,
        val appServiceId: String?,
        val appKm: Double?,
        val appHours: Double?,
        val appIncome: Long?,
        val kmDiff: Double?,
        val hoursDiff: Double?,
        val uncertainFields: List<String> = emptyList(),
    )

    data class ComparisonResult(
        val rows: List<RowComparison>,
        val matched: Int,
        val differs: Int,
        val missingInApp: Int,
        val extraInApp: Int,
        val duplicatesPaper: Int,
        val duplicatesApp: Int,
        val paperSumKm: Double,
        val appSumKm: Double,
        val paperSumHours: Double,
        val appSumHours: Double,
        /** null = the report photo had no readable printed totals to check against. */
        val printedTotalsOk: Boolean?,
        val notes: List<String>,
    )

    fun compare(report: CompanyReportParser.ExtractedReport, appServices: List<AppServiceRow>): ComparisonResult {
        val notes = ArrayList<String>(report.warnings)
        val rows = ArrayList<RowComparison>()
        val usedAppIds = HashSet<String>()
        val appByDate = appServices.groupBy { it.date }

        // ---- duplicate detection on BOTH sides (same date, same km, same route) -------
        fun dupKey(date: String?, km: Double?, route: String) = "${date ?: "?"}/${km ?: -1.0}/$route"
        val paperGroups = report.rows.groupBy { dupKey(it.date, it.km, it.origin + "→" + it.destination) }
        val paperDups = paperGroups.values.filter { it.size > 1 }
        val duplicateRowIndexes = paperDups.flatten().drop(1).map { it.index }.toSet()

        var matched = 0
        var differs = 0
        var missing = 0

        report.rows.sortedBy { it.date ?: "9999" }.forEach { row ->
            val route = "${row.origin}→${row.destination}"
            if (row.index in duplicateRowIndexes) {
                rows.add(
                    RowComparison(RowStatus.DUPLICATE_PAPER, row.index, row.date ?: "؟", row.km, row.hours, route,
                        null, null, null, null, null, null, row.uncertain),
                )
                return@forEach
            }
            val date = row.date
            if (date == null) {
                rows.add(
                    RowComparison(RowStatus.MISSING_IN_APP, row.index, "؟", row.km, row.hours, route,
                        null, null, null, null, null, null, row.uncertain + "date"),
                )
                missing++
                return@forEach
            }
            val candidates = appByDate[date].orEmpty().filter { it.id !in usedAppIds }
            if (candidates.isEmpty()) {
                rows.add(
                    RowComparison(RowStatus.MISSING_IN_APP, row.index, date, row.km, row.hours, route,
                        null, null, null, null, null, null, row.uncertain),
                )
                missing++
                return@forEach
            }
            // Best candidate: closest km, then closest hours.
            val best = candidates.minWith(
                compareBy({ abs(it.km - (row.km ?: it.km)) }, { abs(it.hours - (row.hours ?: it.hours)) }),
            )
            val kmDiff = row.km?.let { it - best.km }
            val hoursDiff = row.hours?.let { it - best.hours }
            val closeKm = kmDiff == null || abs(kmDiff) <= KM_TOLERANCE
            val closeHours = hoursDiff == null || abs(hoursDiff) <= HOURS_TOLERANCE
            usedAppIds.add(best.id)
            val status = if (closeKm && closeHours) RowStatus.MATCHED else RowStatus.DIFFERS
            if (status == RowStatus.MATCHED) matched++ else differs++
            rows.add(
                RowComparison(status, row.index, date, row.km, row.hours, route,
                    best.id, best.km, best.hours, best.income, kmDiff, hoursDiff, row.uncertain),
            )
        }

        // ---- app rows inside the report's date range that no paper row claimed --------
        val dates = report.rows.mapNotNull { it.date }
        val extra = ArrayList<RowComparison>()
        if (dates.isNotEmpty()) {
            val minD = dates.min(); val maxD = dates.max()
            appServices.filter { it.id !in usedAppIds && it.date in minD..maxD }.forEach { s ->
                extra.add(
                    RowComparison(RowStatus.EXTRA_IN_APP, 0, s.date, null, null, "",
                        s.id, s.km, s.hours, s.income, null, null),
                )
            }
        }
        rows.addAll(extra)

        // ---- app-side duplicates inside the range --------------------------------------
        var dupApp = 0
        if (dates.isNotEmpty()) {
            val minD = dates.min(); val maxD = dates.max()
            appServices.filter { it.date in minD..maxD }
                .groupBy { dupKey(it.date, it.km, "") }
                .values.filter { it.size > 1 }
                .forEach { group ->
                    dupApp += group.size - 1
                    group.drop(1).forEach { s ->
                        rows.add(
                            RowComparison(RowStatus.DUPLICATE_APP, 0, s.date, null, null, "",
                                s.id, s.km, s.hours, s.income, null, null),
                        )
                    }
                }
        }

        // ---- printed totals vs SUM of extracted rows (internal consistency of the photo) ----
        var totalsOk: Boolean? = null
        if (report.printedTotalKm != null) {
            val d = report.printedTotalKm - report.sumKm
            totalsOk = abs(d) <= KM_TOLERANCE.coerceAtLeast(1.0)
            if (!totalsOk) notes.add("مجموع کیلومتر روی برگه (${f(report.printedTotalKm)}) با جمع ردیف‌های خوانده‌شده (${f(report.sumKm)}) برابر نیست (اختلاف ${f(d)}). احتمالاً یک ردیف اشتباه خوانده شده.")
        }
        if (report.printedTotalHours != null) {
            val d = report.printedTotalHours - report.sumHours
            if (totalsOk == null) totalsOk = abs(d) <= 0.5
            if (abs(d) > 0.5) {
                notes.add("مجموع ساعت روی برگه (${f(report.printedTotalHours)}) با جمع ردیف‌ها (${f(report.sumHours)}) برابر نیست (اختلاف ${f(d)}).")
                totalsOk = false
            }
        }

        return ComparisonResult(
            rows = rows.sortedWith(compareBy({ it.paperDate }, { it.paperIndex })),
            matched = matched,
            differs = differs,
            missingInApp = missing,
            extraInApp = extra.size,
            duplicatesPaper = paperDups.sumOf { it.size - 1 },
            duplicatesApp = dupApp,
            paperSumKm = report.sumKm,
            appSumKm = appServices.filter { it.id in usedAppIds }.sumOf { it.km } + extra.sumOf { it.appKm ?: 0.0 },
            paperSumHours = report.sumHours,
            appSumHours = appServices.filter { it.id in usedAppIds }.sumOf { it.hours } + extra.sumOf { it.appHours ?: 0.0 },
            printedTotalsOk = totalsOk,
            notes = notes,
        )
    }

    private fun f(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    /** The Persian table shown to the user. Pure data — produced here, not by the model. */
    fun formatPersian(r: ComparisonResult): String = buildString {
        append("📋 نتیجهٔ مقایسهٔ گزارش شرکت با سوابق برنامه:\n\n")
        r.rows.forEach { row ->
            val icon = when (row.status) {
                RowStatus.MATCHED -> "✅"
                RowStatus.DIFFERS -> "⚠️"
                RowStatus.MISSING_IN_APP -> "❌"
                RowStatus.EXTRA_IN_APP -> "➕"
                RowStatus.DUPLICATE_PAPER, RowStatus.DUPLICATE_APP -> "🔁"
            }
            append(icon).append(' ').append(row.paperDate)
            if (row.paperKm != null || row.appKm != null) {
                append(" | کیلومتر: ")
                append(row.paperKm?.let { f(it) } ?: "—")
                if (row.status == RowStatus.DIFFERS && row.kmDiff != null) {
                    append("← برگه vs ").append(row.appKm?.let { f(it) }).append("← برنامه")
                    append(" (Δ ").append(if (row.kmDiff > 0) "+" else "").append(f(row.kmDiff)).append(")")
                }
            }
            if ((row.paperHours != null || row.appHours != null)) {
                append(" | ساعت: ").append(row.paperHours?.let { f(it) } ?: "—")
                if (row.status == RowStatus.DIFFERS && row.hoursDiff != null) {
                    append(" ← ").append(row.appHours?.let { f(it) }).append(" برنامه")
                }
            }
            when (row.status) {
                RowStatus.MISSING_IN_APP -> append(" — در برنامه ثبت نشده")
                RowStatus.EXTRA_IN_APP -> append(" — در برگه نیست؛ در برنامه هست")
                RowStatus.DUPLICATE_PAPER -> append(" — ردیف تکراری در برگه")
                RowStatus.DUPLICATE_APP -> append(" — رکورد تکراری در برنامه")
                else -> Unit
            }
            if (row.uncertainFields.isNotEmpty()) append(" [نیازمند بررسی: ").append(row.uncertainFields.joinToString("، ")).append(']')
            append('\n')
        }
        append('\n')
        append("جمع‌بندی: ${r.matched} مطابق • ${r.differs} مغایر • ${r.missingInApp} ثبت‌نشده در برنامه • ${r.extraInApp} فقط در برنامه")
        if (r.duplicatesPaper > 0 || r.duplicatesApp > 0) append(" • تکراری‌ها: برگه ${r.duplicatesPaper} / برنامه ${r.duplicatesApp}")
        append('\n')
        append("مجموع کیلومتر — برگه: ${f(r.paperSumKm)} | برنامه: ${f(r.appSumKm)}\n")
        append("مجموع ساعت — برگه: ${f(r.paperSumHours)} | برنامه: ${f(r.appSumHours)}\n")
        when (r.printedTotalsOk) {
            true -> append("✔ جمع‌های چاپ‌شدهٔ برگه با ردیف‌ها سازگار است.\n")
            false -> Unit // the mismatch lines are in notes
            null -> append("جمع کل روی برگه خوانده نشد؛ انطباق بر اساس ردیف‌ها انجام شد.\n")
        }
        r.notes.forEach { append("⚠ ").append(it).append('\n') }
    }.trimEnd()
}
