package com.khodroyar.app.workreport

import com.khodroyar.app.core.text.PersianInput
import java.math.BigDecimal
import java.math.RoundingMode

/** Read-only snapshot of an ACTUAL service, retaining the identity lost by GROUP BY date. */
data class AppWorkService(
    val id: String, val date: String, val km: BigDecimal, val hours: BigDecimal,
    val origin: String = "", val destination: String = "", val startTime: String = "", val endTime: String = "",
    val type: String = "", val requestNumber: String = "",
) {
    fun work() = DailyWork(date, 1, km, hours)
}

data class ReportMatchLink(val companyIds: List<String>, val appIds: List<String>, val appFingerprint: String)
enum class ServiceMatchStatus(val label: String) {
    MATCH("مطابق تاریخ، کیلومتر و ساعت"), ONLY_IN_APP("در گزارش شرکت نیست"), ONLY_IN_COMPANY("فقط در گزارش شرکت"),
    DIFFERENT("مغایرت کیلومتر/ساعت یا تاریخ"), AMBIGUOUS("تطبیق مبهم؛ نیاز به تعیین سرویس"), GROUPED("تطبیق گروهی؛ جزئیات تفکیک نشده"),
}
data class ServiceWorkMatch(
    val id: String, val status: ServiceMatchStatus, val company: List<ReportRow>, val app: List<AppWorkService>,
    val note: String = "", val manual: Boolean = false,
) {
    val companyTotals get() = WorkTotals.of(company.map { requireNotNull(it.validated()) })
    val appTotals get() = WorkTotals.of(app.map { it.work() })
}

/**
 * No nearest-value guesses, daily-total-only matching, or arbitrary pairing of
 * indistinguishable trips. Exact metric multiplicities are matched first. A
 * unique remaining pair is only a PROPOSED discrepancy. Many-to-one/one-to-many
 * and cross-day company summaries need explicit, read-only review links.
 */
object ServiceWorkComparison {
    private val epsilon = BigDecimal("0.000001")
    private fun equal(a: BigDecimal, b: BigDecimal) = (a - b).abs() <= epsilon
    private fun key(date: String, km: BigDecimal, hours: BigDecimal) =
        "$date|${km.setScale(6, RoundingMode.HALF_UP).toPlainString()}|${hours.setScale(6, RoundingMode.HALF_UP).toPlainString()}"

    fun fingerprint(services: List<AppWorkService>): String = WorkReportParser.fingerprint(services.sortedBy { it.id }.joinToString("\n") {
        listOf(it.id, it.date, PersianInput.display(it.km), PersianInput.display(it.hours), it.origin, it.destination,
            it.startTime, it.endTime, it.type, it.requestNumber).joinToString("") { value -> "${value.length}:$value" }
    })

    fun compare(company: List<ReportRow>, app: List<AppWorkService>, start: String, end: String,
        links: List<ReportMatchLink> = emptyList()): WorkComparison {
        require(company.size <= 10000 && app.size <= 10000 && links.size <= 10000) { "بازه برای مقایسهٔ سرویس‌به‌سرویس بزرگ است؛ آن را کوچک‌تر کنید." }
        // Manual edits may use Persian/compact/unpadded dates. Compare canonical
        // copies, never raw display strings (and never mutate the review draft).
        val selected = company.filter { it.included }.map { it.copy(date = PersianInput.date(it.date) ?: it.date) }
        require(selected.map { it.id }.distinct().size == selected.size && app.map { it.id }.distinct().size == app.size)
        val values = selected.associate { it.id to requireNotNull(it.validated()) { "مقدار ناخوانا/نامعتبر؛ صفر جایگزین نمی‌شود." } }
        require(app.all { it.id.isNotBlank() && PersianInput.date(it.date) == it.date && it.km >= BigDecimal.ZERO && it.hours >= BigDecimal.ZERO })
        val inRange = app.filter { it.date in start..end }.sortedWith(compareBy({ it.date }, { it.startTime }, { it.id }))
        val daily = WorkReportComparison.compare(values.values.toList(), inRange.map { it.work() }, start, end)
        val remainingCompany = selected.sortedWith(compareBy({ it.date }, { it.page }, { it.line })).associateByTo(linkedMapOf()) { it.id }
        val remainingApp = inRange.associateByTo(linkedMapOf()) { it.id }
        val matches = mutableListOf<ServiceWorkMatch>()
        fun emit(status: ServiceMatchStatus, c: List<ReportRow>, a: List<AppWorkService>, note: String = "", manual: Boolean = false) {
            if (c.isEmpty() && a.isEmpty()) return
            val id = (c.firstOrNull()?.let { "company:${it.id}" } ?: "app:${a.first().id}")
            matches += ServiceWorkMatch(id, status, c, a, note, manual)
            c.forEach { remainingCompany.remove(it.id) }; a.forEach { remainingApp.remove(it.id) }
        }
        for (link in links) {
            require(link.companyIds.isNotEmpty() && link.appIds.isNotEmpty() && link.companyIds.distinct().size == link.companyIds.size && link.appIds.distinct().size == link.appIds.size) { "تطبیق دستی خالی یا تکراری است؛ دوباره انتخاب کنید." }
            val c = link.companyIds.map { requireNotNull(remainingCompany[it]) { "ردیف شرکت در دو تطبیق است یا کنار گذاشته شده؛ تطبیق دستی را بازنشانی کنید." } }
            val a = link.appIds.map { requireNotNull(remainingApp[it]) { "سرویس برنامه تغییر کرده/حذف شده یا در دو تطبیق است؛ دوباره انتخاب کنید." } }
            require(fingerprint(a) == link.appFingerprint) { "سوابق انتخاب‌شده بعد از تطبیق دستی تغییر کرده‌اند؛ تطبیق دستی را بازنشانی و دوباره بازبینی کنید." }
            val ct = WorkTotals.of(c.map { values.getValue(it.id) }); val at = WorkTotals.of(a.map { it.work() })
            val single = c.size == 1 && a.size == 1 && c.single().kind != ReportRowKind.GROUPED && ct.count == 1
            val same = equal(ct.km, at.km) && equal(ct.hours, at.hours) && (!single || c.single().date == a.single().date)
            emit(if (!same) ServiceMatchStatus.DIFFERENT else if (single) ServiceMatchStatus.MATCH else ServiceMatchStatus.GROUPED, c, a,
                if (single) "تطبیق این دو رکورد را خودتان تعیین کرده‌اید."
                else "پوشش گروه را خودتان تعیین کرده‌اید؛ جمع کیلومتر/ساعت مقایسه شده، نه هویت تک‌تک سرویس‌های داخل گروه. تعداد ردیف شرکت لزوماً تعداد سرویس نیست.", true)
        }

        val individual = remainingCompany.values.filter { it.kind != ReportRowKind.GROUPED && values.getValue(it.id).count == 1 }
        val cByKey = individual.groupBy { r -> values.getValue(r.id).let { key(it.date, it.km, it.hours) } }
        val aByKey = remainingApp.values.groupBy { key(it.date, it.km, it.hours) }
        for ((metric, c) in cByKey) {
            val a = aByKey[metric].orEmpty()
            if (a.isEmpty()) continue
            if (c.size == a.size) emit(ServiceMatchStatus.MATCH, c, a,
                if (a.size > 1) "${a.size} سرویس با مقدارهای یکسان؛ از این سه فیلد نمی‌توان مسیر/هویت آن‌ها را از هم تفکیک کرد. جفت دلخواه انتخاب نشده است." else "")
            else emit(ServiceMatchStatus.AMBIGUOUS, c, a,
                "شرکت ${c.size} ردیف و برنامه ${a.size} سرویس با تاریخ/کیلومتر/ساعت یکسان دارد. اختلاف تعداد روشن است، اما اینکه دقیقاً کدام سرویس کم/اضافه است از این مقدارها مشخص نیست؛ انتخاب دستی لازم است.")
        }

        // A printed daily total is not split into invented per-service distances/hours.
        val dailyGroups = remainingCompany.values.filter { it.kind == ReportRowKind.DAILY_TOTAL && values.getValue(it.id).count != 1 }.groupBy { it.date }
        for ((date, c) in dailyGroups) {
            val a = remainingApp.values.filter { it.date == date }
            val ct = WorkTotals.of(c.map { values.getValue(it.id) }); val at = WorkTotals.of(a.map { it.work() })
            val same = ct.count == at.count && equal(ct.km, at.km) && equal(ct.hours, at.hours)
            emit(if (same) ServiceMatchStatus.GROUPED else ServiceMatchStatus.AMBIGUOUS, c, a,
                "این ردیف جمع روزانه است؛ جمع‌ها ${if (same) "برابرند" else "متفاوت‌اند"}، ولی سند جزئیات هر سرویس را ندارد. از جمع روز نمی‌توان سرویس جاافتادهٔ مشخصی استخراج کرد.")
        }

        if (remainingCompany.values.any { it.kind == ReportRowKind.GROUPED }) {
            // The scope/type of «کارکرد نیمه ...» is not inferred from money or a
            // convenient sum. Preserve ALL remaining records as candidates.
            emit(ServiceMatchStatus.AMBIGUOUS, remainingCompany.values.toList(), remainingApp.values.toList(),
                "گزارش ردیف تجمیعی با پوشش نامشخص دارد. سرویس‌های باقی‌مانده ممکن است داخل آن باشند؛ هنوز «جاافتادهٔ قطعی» نامیده نمی‌شوند. با «تطبیق دستی» ردیف(های) شرکت و سرویس‌های واقعی پوشش‌داده‌شده را انتخاب کنید؛ سپس دوباره مقایسه کنید.")
        } else {
            val dates = (remainingCompany.values.map { it.date } + remainingApp.values.map { it.date }).distinct().sorted()
            for (date in dates) {
                val c = remainingCompany.values.filter { it.date == date }; val a = remainingApp.values.filter { it.date == date }
                when {
                    c.size == 1 && a.size == 1 -> emit(ServiceMatchStatus.DIFFERENT, c, a,
                        "پیشنهاد تطبیق: تنها دو رکورد باقی‌ماندهٔ این روز هستند. بدون شناسهٔ مشترک، ممکن است دو سرویس متفاوت باشند؛ برای قطعیت تطبیق دستی را تعیین کنید.")
                    c.isNotEmpty() && a.isNotEmpty() -> emit(ServiceMatchStatus.AMBIGUOUS, c, a,
                        "چند سرویس با مقدارهای متفاوت باقی مانده است؛ جفت‌سازی حدسی انجام نشد. ممکن است رفت‌وبرگشت در یک طرف یک ردیف و در طرف دیگر چند ردیف باشد. سرویس‌های متناظر را دستی تعیین کنید.")
                    c.isEmpty() -> a.forEach { emit(ServiceMatchStatus.ONLY_IN_APP, emptyList(), listOf(it), "در ردیف‌های انتخاب‌شدهٔ شرکت همتایی ندارد؛ اصل همهٔ صفحه‌ها و ردیف‌های کنارگذاشته‌شده را بررسی کنید.") }
                    a.isEmpty() -> c.forEach { emit(ServiceMatchStatus.ONLY_IN_COMPANY, listOf(it), emptyList(), "در سوابق واقعی برنامه در این بازه همتایی ندارد.") }
                }
            }
        }
        return daily.copy(services = matches.sortedWith(compareBy({ (it.company.map { r -> r.date } + it.app.map { r -> r.date }).minOrNull().orEmpty() }, { it.id })),
            companyRows = selected, appServices = inRange)
    }
}
