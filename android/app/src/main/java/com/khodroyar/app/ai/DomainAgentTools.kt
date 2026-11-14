package com.khodroyar.app.ai

import android.content.Context
import com.khodroyar.app.core.analytics.*
import com.khodroyar.app.core.holidays.WorkCalendar
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.*
import com.khodroyar.app.core.text.PersianInput
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.data.repo.ServiceBatchPlan
import com.khodroyar.app.workreport.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.util.UUID

object DomainToolSpecs {
    private val year = ToolParam("year", ParamType.INT, min = 1300.0, max = 1600.0)
    private val id = ToolParam("id", ParamType.ID, true, "شناسه از ابزار جستجو/فهرست؛ نه حدس مدل")
    private val date = ToolParam("date", ParamType.DATE, true)
    private val type = ToolParam("service_type", ParamType.ENUM, true, options = ServiceType.entries.map { it.wire })
    private val km = ToolParam("km", ParamType.NUMBER, true, min = 0.0, max = 3000.0)
    private val hours = ToolParam("hours", ParamType.NUMBER, true, min = 0.0, max = 24.0)
    private val fields = listOf(ToolParam("origin", ParamType.TEXT, true), ToolParam("destination", ParamType.TEXT, true), km, hours,
        ToolParam("start_time", ParamType.TEXT, desc = "HH:mm؛ اگر واقعاً مشخص است"), ToolParam("end_time", ParamType.TEXT, desc = "HH:mm"),
        ToolParam("toll_count", ParamType.INT, min = 0.0, max = 100.0),
        ToolParam("mission_food", ParamType.NUMBER, max = 1.0e9), ToolParam("mission_toll", ParamType.NUMBER, max = 1.0e9), ToolParam("mission_fine", ParamType.NUMBER, max = 1.0e9))
    private val range = listOf(ToolParam("start_date", ParamType.DATE, true), ToolParam("end_date", ParamType.DATE, true))
    val tools = listOf(
        ToolSpec("get_jalali_calendar", ToolKind.READ, "ابتدا/انتهای دقیق ماه یا سال شمسی، روزهای تقویمی و کاری با منطق تعطیلات برنامه", listOf(year.copy(required = true), ToolParam("month", ParamType.INT, min = 1.0, max = 12.0))),
        ToolSpec("get_service_catalog", ToolKind.READ, "نوع‌های واقعی سرویس، نرخ سال و الگوی واقعی ذخیره‌شده؛ بدون نام مسافر/اطلاعات پرسنل", listOf(year)),
        ToolSpec("add_service", ToolKind.WRITE, "یک سرویس سفر با جزئیات صریح واقعی؛ درآمد را برنامه محاسبه می‌کند، پیش‌نمایش و تأیید لازم است", listOf(date, type) + fields),
        ToolSpec("update_service", ToolKind.WRITE, "ویرایش دقیق یک سرویس با شناسهٔ واقعی و پیش‌نمایش قبل/بعد؛ درآمد دوباره طبق نرخ تاریخ محاسبه می‌شود",
            listOf(id, date.copy(required = false), type.copy(required = false)) + fields.map { it.copy(required = false) }),
        ToolSpec("delete_service", ToolKind.DELETE, "حذف همان یک سرویس انتخاب‌شده با تأیید صریح؛ نه همهٔ سرویس‌های مشابه", listOf(id)),
        ToolSpec("compare_financial_periods", ToolKind.READ, "مقایسهٔ دو بازهٔ مشخص: درآمد/هزینه/خالص، کیلومتر، ساعت و منبع محاسبات", range + listOf(ToolParam("previous_start", ParamType.DATE, true), ToolParam("previous_end", ParamType.DATE, true))),
        ToolSpec("compare_company_report", ToolKind.READ, "مقایسهٔ read-only گزارش واردشدهٔ شرکت با سوابق؛ counts بدون نیاز به km/hour؛ metrics فقط در صورت درخواست جزئیات و دادهٔ معتبر، با همان موتور فعلی برنامه", range +
            ToolParam("detail", ParamType.ENUM, options = listOf("counts", "metrics"))),
        ToolSpec("explain_service_price", ToolKind.READ, "ریز محاسبهٔ همان سرویس با IncomeCalculator؛ درآمد ثبت‌شده از محاسبه با نرخ فعلیِ سالِ تاریخ تفکیک می‌شود و هیچ چیز بازنویسی نمی‌شود", listOf(id)),
        ToolSpec("find_service_duplicates", ToolKind.READ, "گروه‌های بالقوهٔ تکراریِ سرویس با مشخصات یکسان؛ تشخیص قطعی نیست و چیزی حذف نمی‌کند", range),
        ToolSpec("list_insurance", ToolKind.READ, "بیمه/مدارک و تاریخ انقضا/هزینه/یادآوری با شناسه؛ شماره بیمه‌نامه ارسال نمی‌شود"),
        ToolSpec("update_insurance", ToolKind.WRITE, "ویرایش همان بیمه/مدرک با پیش‌نمایش و تأیید", listOf(id, ToolParam("title", ParamType.TEXT),
            ToolParam("expiry_date", ParamType.DATE, allowFuture = true), ToolParam("cost", ParamType.NUMBER), ToolParam("reminder", ParamType.ENUM, options = listOf("on", "off")))),
        ToolSpec("delete_insurance", ToolKind.DELETE, "حذف همان مدرک/یادآور بیمه پس از تأیید", listOf(id)),
    )
    val names = tools.map { it.name }.toSet()
}

/** Adapters, not duplicate databases/calculators: all writes use existing repository/batch transactions. */
class DomainAgentTools(context: Context, private val repo: AppRepository) : AgentToolRunner {
    private val reportDirectory = File(context.applicationContext.noBackupFilesDir, "work-report")
    override suspend fun prepare(call: ValidatedCall): ValidatedCall = withContext(Dispatchers.IO) {
        if (call.spec.kind == ToolKind.READ) return@withContext call
        try {
            if (call.spec.name.endsWith("_insurance")) {
                val before = repo.agentRecord("insurance", requireNotNull(call.str("id"))) as? InsuranceEntity
                    ?: throw AgentUserException("بیمه/مدرک با این شناسه پیدا نشد یا یکتا نیست؛ دوباره فهرست بگیرید.")
                val after = if (call.spec.kind == ToolKind.DELETE) null else before.copy(type = call.str("title") ?: before.type,
                    expiryDate = call.str("expiry_date") ?: before.expiryDate, cost = call.dbl("cost") ?: before.cost,
                    reminder = call.str("reminder")?.let { it == "on" } ?: before.reminder)
                return@withContext ValidatedCall(call.spec, call.args + mapOf("insurance_before" to before) +
                    if (after == null) emptyMap() else mapOf("insurance_after" to after))
            }
            val old: ServiceEntity? = if (call.spec.name == "add_service") null else {
                repo.agentRecord("service", requireNotNull(call.str("id"))) as? ServiceEntity
                    ?: throw AgentUserException("سرویس با این شناسه پیدا نشد یا یکتا نیست؛ اول جستجو کنید.")
            }
            val next = if (call.spec.name == "delete_service") null else if (old == null) {
                val date = requireNotNull(call.str("date"))
                ServiceEntity(UUID.randomUUID().toString(), date, requireNotNull(call.str("service_type")), repo.ratesFor(date.take(4).toInt()).defaultCarType.wire,
                    requireNotNull(call.dbl("km")), requireNotNull(call.dbl("hours")), requireNotNull(call.dbl("hours")), call.str("start_time").orEmpty(), call.str("end_time").orEmpty(),
                    requireNotNull(call.str("origin")), requireNotNull(call.str("destination")), "", "", call.int("toll_count") ?: 0,
                    call.dbl("mission_food") ?: 0.0, call.dbl("mission_toll") ?: 0.0, call.dbl("mission_fine") ?: 0.0, 0L, java.time.Instant.now().toString())
            } else old.copy(date = call.str("date") ?: old.date, type = call.str("service_type") ?: old.type,
                km = call.dbl("km") ?: old.km, hours = call.dbl("hours") ?: old.hours, workHours = call.dbl("hours") ?: old.workHours,
                origin = call.str("origin") ?: old.origin, destination = call.str("destination") ?: old.destination,
                startTime = call.str("start_time") ?: old.startTime, endTime = call.str("end_time") ?: old.endTime,
                tollCount = call.int("toll_count") ?: old.tollCount, missionFood = call.dbl("mission_food") ?: old.missionFood,
                missionToll = call.dbl("mission_toll") ?: old.missionToll, missionFine = call.dbl("mission_fine") ?: old.missionFine)
            val plan = repo.serviceBatches.prepareSingle(old?.id, next)
            ValidatedCall(call.spec, call.args + ("single_service_plan" to plan))
        } catch (e: CancellationException) { throw e }
        catch (e: AgentUserException) { throw e }
        catch (_: Exception) { throw AgentUserException("اطلاعات کامل/معتبر برای پیش‌نمایش ندارم. تاریخ، مسیر، کیلومتر، ساعت و شناسه را بررسی کنید؛ چیزی تغییر نکرد.") }
    }
    private fun insurance(row: InsuranceEntity) = "${row.id} · ${row.type} · انقضا ${row.expiryDate} · ${row.cost} تومان · یادآوری ${if (row.reminder) "فعال" else "خاموش"}"
    override fun describe(call: ValidatedCall): String = if (call.spec.name.endsWith("_insurance")) {
        val before = call.args["insurance_before"] as? InsuranceEntity ?: throw AgentUserException("پیش‌نمایش معتبر نیست.")
        "قبل: ${insurance(before)}\nبعد: " + ((call.args["insurance_after"] as? InsuranceEntity)?.let(::insurance) ?: "حذف همین مدرک")
    } else (call.args["single_service_plan"] as? ServiceBatchPlan)?.summary() ?: throw AgentUserException("پیش‌نمایش معتبر نیست.")
    override suspend fun describeTarget(call: ValidatedCall): String? = when (call.spec.name) {
        "delete_insurance" -> (call.args["insurance_before"] as? InsuranceEntity)?.let(::insurance)
        "delete_service" -> (call.args["single_service_plan"] as? ServiceBatchPlan)?.changes?.singleOrNull()?.before?.let { "${it.id} · ${it.date} · ${ServiceType.fromWire(it.type).label}" }
        else -> null
    }
    override suspend fun execute(call: ValidatedCall): ToolOutcome = withContext(Dispatchers.IO) {
        try {
            if (call.spec.kind != ToolKind.READ) {
                if (call.spec.name.endsWith("_insurance")) {
                    val before = call.args["insurance_before"] as? InsuranceEntity ?: throw AgentUserException("پیش‌نمایش معتبر نیست.")
                    repo.applyApprovedAgentRecord(before, call.args["insurance_after"])
                    return@withContext ToolOutcome(true, "تغییر همین بیمه/مدرک در دیتابیس تأیید و بازخوانی شد:\n${describe(call)}")
                }
                val plan = call.args["single_service_plan"] as? ServiceBatchPlan ?: throw AgentUserException("پیش‌نمایش معتبر نیست.")
                val count = repo.serviceBatches.execute(plan)
                return@withContext ToolOutcome(true, "$count سرویسِ همین پیش‌نمایش در دیتابیس تأیید و بازخوانی شد؛ درآمد و کیلومتر با منطق اصلی برنامه محاسبه شدند.\n${plan.start} تا ${plan.end}")
            }
            when (call.spec.name) {
                "get_jalali_calendar" -> {
                    val year = requireNotNull(call.int("year")); val month = call.int("month")
                    val range = if (month == null) Jalali.format(year, 1, 1) to Jalali.format(year, 12, Jalali.monthLength(year, 12)) else Jalali.monthRange(year, month)
                    val days = requireNotNull(PersianInput.days(range.first, range.second)); val holidays = repo.manualHolidayDatesOnce()
                    ToolOutcome(true, "ابتدا ${range.first} · انتها ${range.second} (هر دو شامل)\n${days.size} روز تقویمی · ${days.count { !WorkCalendar.isNonWorkingDay(it, holidays) }} روز کاری\nمنبع: تقویم شمسی و تعطیلات رسمی/دستی خود برنامه؛ پنجشنبه و جمعه غیرکاری‌اند.")
                }
                "get_service_catalog" -> {
                    val year = call.int("year") ?: Jalali.currentYear(); val rates = repo.ratesFor(year)
                    val preset = repo.serviceBatches.savedTemplate()
                    ToolOutcome(true, "نوع‌ها: " + ServiceType.entries.joinToString("؛ ") { "${it.wire}=${it.label}" } +
                        "\nخودروی پیش‌فرض نرخ این سال: ${rates.defaultCarType.wire} · حالت اجاره‌ای ${if (repo.settingsNow.isRental) "فعال" else "خاموش"}.\nنرخ‌های واقعی ذخیره‌شدهٔ $year (تومان): شب/km ${rates.nightKm}؛ تعطیل/km ${rates.holidayKm}؛ ثابت/درخواستی/km ${rates.fixedRequestKm}؛ ساعت ${rates.hour}؛ ساعت تعطیل ${rates.holidayHour}؛ عوارض/نوبت ${rates.toll}؛ حقوق ${rates.dailySalary}؛ مرخصی ${rates.leaveAmount}.\n" +
                        "زمان: شب ${rates.nightStartTime} تا ${rates.saharStartTime} با ${rates.nightPercent}٪؛ سحر تا ${rates.saharEndTime} با ${rates.saharPercent}٪؛ نوع‌های مشمول ${rates.timeBonusServiceTypes.joinToString { it.wire }}.\n" +
                        "درآمد نهایی با IncomeCalculator و قواعد زمان/نوع برنامه محاسبه می‌شود؛ نرخ صفر را تنظیم‌شده فرض نکنید.\n" +
                        (preset?.let { "الگوی واقعی: ${it.summary()}" } ?: "الگوی سرویس ذخیره نشده؛ برای ثبت گروهی، جزئیات صریح مسیر/کیلومتر/ساعت را از کاربر بگیرید (template_source=explicit) یا در فرم سرویس الگو ذخیره کنید."))
                }
                "list_insurance" -> {
                    val rows = repo.insurance.allOnce()
                    ToolOutcome(true, if (rows.isEmpty()) "بیمه/مدرکی ثبت نشده؛ اطلاعاتی حدس نمی‌زنم." else rows.take(20).joinToString("\n", postfix = "\nفقط ۲۰ ردیف اول؛ شماره بیمه‌نامه/شناسه شخصی ارسال نمی‌شود.", transform = ::insurance))
                }
                "compare_financial_periods" -> {
                    val a = ReportWindow(requireNotNull(call.str("start_date")), requireNotNull(call.str("end_date")))
                    val b = ReportWindow(requireNotNull(call.str("previous_start")), requireNotNull(call.str("previous_end")))
                    require(a.days <= 366 && b.days <= 366)
                    val data = repo.comparison(a, b).first()
                    fun summary(s: FinancialSlice) = "${s.serviceCount} سرویس · ${s.km} km · ${s.hours} ساعت\nدرآمد سرویس ${s.serviceIncome} + شخصی ${s.personalIncome} = ${s.income}؛ هزینهٔ شخصی ${s.personalExpense} + سوخت ${s.fuelCost} + نگهداری ${s.maintenanceCost} = ${s.expense}؛ خالص ${s.net} تومان"
                    ToolOutcome(true, "منبع: رکوردهای ثبت‌شده و همان محاسبات گزارش مالی؛ نرخ گذشته بازنویسی نشده.\n${a.start} تا ${a.end} (${a.days} روز):\n${summary(data.current)}\n${b.start} تا ${b.end} (${b.days} روز):\n${summary(data.previous)}\nتفاوت خالص ${data.current.net - data.previous.net} تومان. " + if (a.days != b.days) "بازه‌ها هم‌طول نیستند؛ درصد عملکرد مستقیم نسازید." else "بازه‌ها هم‌طول‌اند؛ نبود داده را صفر عملکرد قطعی فرض نکنید.")
                }
                "explain_service_price" -> {
                    val row = repo.agentRecord("service", requireNotNull(call.str("id"))) as? ServiceEntity
                        ?: return@withContext ToolOutcome(false, "شناسهٔ سرویس واقعی یکتا پیدا نشد؛ ابتدا جستجو کنید.")
                    val rates = repo.ratesFor(row.date.take(4).toInt())
                    val b = IncomeCalculator.breakdown(ServiceType.fromWire(row.type), row.km, row.hours, row.tollCount, row.missionFood,
                        row.missionToll, row.missionFine, row.startTime.ifBlank { null }, row.endTime.ifBlank { null }, rates)
                    ToolOutcome(true, "منبع: همان IncomeCalculator، نرخ فعلیِ ذخیره‌شده برای سال ${row.date.take(4)}؛ رکورد ${row.id} · ${row.date} · ${ServiceType.fromWire(row.type).label}.\n" +
                        "درآمدی که واقعاً ثبت شده: ${row.income} تومان؛ محاسبه با نرخ فعلی این سال: ${b.finalAmount} تومان. نرخ تاریخی از زمان ثبت ممکن است بعداً ویرایش شده باشد؛ این محاسبه رکورد را بازنویسی نمی‌کند.\n" +
                        "${row.km} km × نرخ ${b.kmRate} × (۱ + ${b.kmPercent}/۱۰۰) = ${b.kmAmount}؛ ساعت با نرخ ${b.hourRate}: عادی ${b.hourly.normalHours}/${b.hourly.normalAmount}، شب ${b.hourly.nightHours}/${b.hourly.nightAmount}، سحر ${b.hourly.saharHours}/${b.hourly.saharAmount}؛ جمع ساعت ${b.hourly.totalAmount}.\n" +
                        "اضافات/عوارض طبق نوع ${b.extrasAmount}؛ عوارض ${row.tollCount} × ${rates.toll}. در حقوق/مرخصی مبلغ ثابت است و km/hour محاسبه نمی‌شود. گردکردن نهایی با قاعدهٔ اصلی برنامه است.")
                }
                "compare_company_report" -> company(call)
                "find_service_duplicates" -> {
                    val start = requireNotNull(call.str("start_date")); val end = requireNotNull(call.str("end_date")); require(PersianInput.days(start, end) != null)
                    val rows = repo.servicesInRangeOnce(start, end)
                    val groups = rows.groupBy { listOf(it.date, it.type, it.carType, it.km, it.hours, it.startTime, it.endTime, it.origin, it.destination, it.tollCount, it.income) }.values.filter { it.size > 1 }
                    ToolOutcome(true, "${groups.size} گروه بالقوهٔ تکراری؛ مشخصات یکسان می‌تواند دو سفر واقعی باشد و اثبات تکرار نیست. چیزی حذف نشد.\n" + groups.take(10).joinToString("\n") { g -> "${g.first().date} · ${ServiceType.fromWire(g.first().type).label} · ${g.size} رکورد · شناسه‌ها ${g.take(10).joinToString { it.id }}" })
                }
                else -> ToolOutcome(false, "ابزار مجاز نیست.")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: AgentUserException) { ToolOutcome(false, e.userMessage) }
        catch (_: Exception) { ToolOutcome(false, "خواندن/تغییر دادهٔ معتبر تأیید نشد؛ عدد یا موفقیت حدسی اعلام نمی‌شود. بازه و پیش‌نمایش را دوباره بررسی کنید.") }
    }
    private suspend fun company(call: ValidatedCall): ToolOutcome {
        val start = requireNotNull(call.str("start_date")); val end = requireNotNull(call.str("end_date")); require(PersianInput.days(start, end) != null)
        val draft = WorkReportDraft(reportDirectory).load() ?: return ToolOutcome(true, "گزارش شرکت وارد نشده؛ ابتدا از گزارش‌ها ← مقایسهٔ کارکرد شرکت سند را با رضایت خود وارد/بخوانید. هیچ سندی به OpenRouter ارسال نمی‌شود.")
        val services = repo.servicesInRangeOnce(start, end)
        val comparison = DateServiceCountComparison.compare(draft.rows, services.map { it.date }, start, end, draft.companyCounts)
        val different = comparison.filter { it.difference != 0 }
        val detail = if (call.str("detail") == "metrics") companyMetrics(draft, services, start, end) else ""
        return ToolOutcome(true, "مقایسهٔ read-only $start تا $end؛ شرکت ${comparison.sumOf { it.companyCount }} / برنامه ${comparison.sumOf { it.appCount }} / اختلاف ${comparison.sumOf { it.difference }} (برنامه−شرکت).\n" +
            different.take(20).joinToString("\n") { "${it.date}: شرکت ${it.companyCount}${if (it.manuallyCorrected) "*" else ""} / برنامه ${it.appCount} / اختلاف ${it.difference}" } +
            "\n${different.size} روز دارای اختلاف؛ فقط ۲۰ روز اول بالا. * اصلاح دستیِ جدا از OCR است. اختلاف تعداد هویت سرویس گمشده/تکراری را ثابت نمی‌کند؛ OCR ممکن است ردیف نخوانده باشد. هیچ سرویس، متن OCR، اصلاح تعداد یا اصل سند تغییر/ارسال نشد." + detail)
    }
    private fun companyMetrics(draft: WorkReportDraft.Draft, services: List<ServiceEntity>, start: String, end: String): String = try {
        val selected = draft.rows.filter { it.included && (ReportDateText.parse(it.date) ?: "") in start..end }
            .map { it.copy(date = ReportDateText.parse(it.date) ?: it.date, hours = ReportHoursText.normalize(it.hours)) }
        val values = selected.map { it.reviewed() }
        if (selected.isEmpty() || values.any { it == null }) {
            "\nجزئیات کیلومتر/ساعت قابل تأیید نیست: تعداد/کیلومتر یا تاریخ بعضی ردیف‌ها ناقص است. جمع تعداد بالا مستقل و معتبر باقی می‌ماند؛ مقدار خالی صفر نمی‌شود."
        } else {
            val app = services.map { row ->
                require(row.km.isFinite() && row.km >= 0 && row.hours.isFinite() && row.hours >= 0)
                AppWorkService(row.id, row.date, BigDecimal.valueOf(row.km), BigDecimal.valueOf(row.hours), row.origin, row.destination,
                    row.startTime, row.endTime, ServiceType.fromWireOrNull(row.type)?.label ?: row.type, row.requestNumber)
            }
            val companyIds = selected.map { it.id }.toSet(); val appIds = app.map { it.id }.toSet()
            val links = draft.links.filter { link -> link.companyIds.any { it in companyIds } || link.appIds.any { it in appIds } }
            val sparse = if (values.any { it?.hours == null }) SparseHoursComparison.compare(selected, app, start, end, links) else null
            val strict = sparse?.completeDays ?: ServiceWorkComparison.compare(selected, app, start, end, links)
            "\nجزئیات با موتور فعلی مقایسهٔ برنامه، بدون تغییر رکورد یا تطبیق حدسی:\n" +
                (sparse?.let { "${it.heldDates.size} روز با ساعت نامشخص/پوشش تجمیعی از تطبیق قطعی کنار نگه داشته شد؛ اختلاف ساعت نامشخص است، نه صفر.\n" } ?: "") +
                strict.services.groupingBy { it.status.label }.eachCount().entries.joinToString("؛ ") { "${it.key}: ${it.value} گروه" } +
                "\n" + strict.services.filter { it.status != ServiceMatchStatus.MATCH }.take(8).joinToString("\n") { match ->
                    val dates = (match.company.map { it.date } + match.app.map { it.date }).distinct().joinToString()
                    "$dates · ${match.status.label} · شرکت ${match.company.size} ردیف / برنامه ${match.app.size} رکورد؛ ${match.note}"
                } + "\nفقط ۸ گروه مسئله‌دار اول؛ جزئیات کامل در بخش گزارش‌ها. نتیجهٔ ناقص OCR را فقدان قطعی سرویس تعبیر نکنید. اصلاح روزانهٔ تعداد، تطبیق جزئیات یا سوابق واقعی را تغییر نمی‌دهد."
        }
    } catch (_: Exception) { "\nجزئیات سرویس‌به‌سرویس تأیید نشد (مقدار ناقص، بازهٔ بزرگ یا تطبیق دستیِ تغییرکرده). جمع تعداد بالا مسدود نشده است؛ برای بازبینی جزئیات از بخش گزارش‌ها استفاده کنید." }

}
