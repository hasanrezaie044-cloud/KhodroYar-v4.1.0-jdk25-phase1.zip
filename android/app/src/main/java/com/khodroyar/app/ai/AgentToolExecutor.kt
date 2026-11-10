package com.khodroyar.app.ai

import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.data.db.FuelEntity
import com.khodroyar.app.data.db.InsuranceEntity
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.db.PersonalExpenseEntity
import com.khodroyar.app.data.db.PersonalIncomeEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID

/**
 * Runs validated tools through the EXISTING [AppRepository]. The model never touches the
 * database: it only names a tool; this class performs it and reports the verified result.
 */
class AgentToolExecutor(private val repo: AppRepository) : AgentToolRunner {

    // ------------------------------------------------------------ formatting

    private fun n(v: Double): String = Analytics.formatNumber(v)
    private fun n(v: Long): String = Analytics.formatNumber(v)
    private fun short(id: String): String = id.take(8)
    private fun clip(s: String, max: Int = 40): String = s.replace('\n', ' ').take(max)
    private fun p2(v: Int): String = String.format(Locale.US, "%02d", v)

    private fun curYear(): Int = Jalali.currentYear()
    private fun curMonth(): Int = Jalali.today().month

    private fun monthPrefix(y: Int, m: Int) = "$y/${p2(m)}"

    private fun prefixFor(call: ValidatedCall): Pair<String, String> {
        val y = call.int("year")
        val m = call.int("month")
        return when {
            y != null && m != null -> monthPrefix(y, m) to "ماه ${Jalali.monthName(m)} $y"
            m != null -> monthPrefix(curYear(), m) to "ماه ${Jalali.monthName(m)} ${curYear()}"
            y != null -> "$y" to "سال $y"
            else -> monthPrefix(curYear(), curMonth()) to "ماه ${Jalali.monthName(curMonth())} ${curYear()}"
        }
    }

    // ----------------------------------------------------------- data bundle

    private class Bundle(
        val services: List<ServiceEntity>,
        val fuels: List<FuelEntity>,
        val maints: List<MaintenanceEntity>,
        val expenses: List<PersonalExpenseEntity>,
        val incomes: List<PersonalIncomeEntity>,
    ) {
        val serviceIncome: Long get() = services.sumOf { it.income }
        val personalIncome: Double get() = incomes.sumOf { it.amount }
        val personalExpense: Double get() = expenses.sumOf { it.amount }
        val fuelCost: Double get() = fuels.sumOf { it.total }
        val maintCost: Double get() = maints.sumOf { it.cost }
        val totalIncome: Double get() = serviceIncome.toDouble() + personalIncome
        val totalExpense: Double get() = personalExpense + fuelCost + maintCost
    }

    private suspend fun load(prefix: String): Bundle = Bundle(
        services = repo.servicesByPrefixOnce(prefix),
        fuels = repo.fuelsByPrefixOnce(prefix),
        maints = repo.maintenancesByPrefixOnce(prefix),
        expenses = repo.personalExpenses(prefix).first(),
        incomes = repo.personalIncomes(prefix).first(),
    )

    private fun summaryLines(b: Bundle): String = buildString {
        append("درآمد سرویس‌ها: ").append(n(b.serviceIncome)).append(" تومان (").append(b.services.size).append(" سرویس)\n")
        append("درآمد شخصی: ").append(n(b.personalIncome)).append(" تومان\n")
        append("جمع درآمد: ").append(n(b.totalIncome)).append(" تومان\n")
        append("هزینه شخصی: ").append(n(b.personalExpense)).append(" تومان\n")
        append("هزینه سوخت: ").append(n(b.fuelCost)).append(" تومان\n")
        append("هزینه تعمیرات و سرویس: ").append(n(b.maintCost)).append(" تومان\n")
        append("جمع هزینه: ").append(n(b.totalExpense)).append(" تومان\n")
        append("خالص (درآمد منهای هزینه): ").append(n(b.totalIncome - b.totalExpense)).append(" تومان")
    }

    // ---------------------------------------------------------- confirmation

    /** Persian summary the USER confirms. Built from validated values, not from model prose. */
    override fun describe(call: ValidatedCall): String {
        val a = call
        fun amt(key: String) = a.dbl(key)?.let { "${n(it)} تومان" }
        return when (a.spec.name) {
            "add_expense" -> "ثبت هزینه: ${amt("amount")}" +
                (a.str("category")?.let { " · دسته: ${PurchaseCategory.fromWire(it).label}" } ?: "") +
                (a.str("title")?.let { " · «$it»" } ?: "") + " · تاریخ ${a.str("date") ?: Jalali.todayString()}"
            "add_repair" -> "ثبت هزینه تعمیر: ${amt("amount")}" +
                (a.str("description")?.let { " · «$it»" } ?: "") + (a.dbl("km")?.let { " · ${n(it)} کیلومتر" } ?: "") +
                " · تاریخ ${a.str("date") ?: Jalali.todayString()}"
            "add_car_service" -> "ثبت سرویس خودرو: ${MaintenanceType.fromWire(a.str("service_type")).label}" +
                (amt("amount")?.let { " · هزینه $it" } ?: "") + (a.dbl("km")?.let { " · ${n(it)} کیلومتر" } ?: "") +
                " · تاریخ ${a.str("date") ?: Jalali.todayString()}"
            "add_fuel" -> "ثبت سوخت: ${a.dbl("liters")?.let { n(it) }} لیتر · ${amt("total_cost")}" +
                " · ${FuelType.fromWire(a.str("fuel_type")).label} · تاریخ ${a.str("date") ?: Jalali.todayString()}"
            "create_reminder" -> "ایجاد یادآور: «${a.str("title")}» · انقضا ${a.str("expiry_date")}" +
                (a.str("company")?.let { " · $it" } ?: "") + (amt("cost")?.let { " · $it" } ?: "")
            "update_expense", "update_maintenance", "update_fuel" -> {
                val changes = a.args.filterKeys { it != "id" }.entries.joinToString("، ") { (k, v) -> "${fieldLabel(k)}: ${if (v is Double) n(v) else v}" }
                "ویرایش رکورد ${short(a.str("id") ?: "")}: $changes"
            }
            "delete_expense", "delete_maintenance", "delete_fuel" -> "حذف دائمی رکورد ${short(a.str("id") ?: "")}"
            else -> a.spec.name
        }
    }

    private fun fieldLabel(k: String) = when (k) {
        "amount" -> "مبلغ"
        "title" -> "عنوان"
        "category" -> "دسته"
        "date" -> "تاریخ"
        "km" -> "کیلومتر"
        "description" -> "توضیح"
        "liters" -> "لیتر"
        "total_cost" -> "مبلغ کل"
        else -> k
    }

    // ------------------------------------------------------------- execution

    override suspend fun execute(call: ValidatedCall): ToolOutcome = try {
        when (call.spec.name) {
            "get_today_expenses" -> todayExpenses()
            "get_monthly_report" -> periodReport(call, withBreakdown = true)
            "get_yearly_report" -> yearlyReport(call)
            "get_totals" -> totals(call)
            "list_maintenance" -> listMaintenance(call)
            "list_services" -> listServices(call)
            "list_expenses" -> listExpenses(call)
            "list_fuel" -> listFuel(call)
            "get_active_reminders" -> activeReminders()
            "analyze_fuel" -> analyzeFuel(call)
            "get_vehicle_status" -> vehicleStatus()
            "create_financial_report" -> periodReport(call, withBreakdown = true)
            "add_expense" -> addExpense(call)
            "add_repair" -> addMaintenance(call, MaintenanceType.REPAIR)
            "add_car_service" -> addMaintenance(call, MaintenanceType.fromWire(call.str("service_type")))
            "add_fuel" -> addFuel(call)
            "create_reminder" -> createReminder(call)
            "update_expense" -> updateExpense(call)
            "update_maintenance" -> updateMaintenance(call)
            "update_fuel" -> updateFuel(call)
            "delete_expense" -> deleteExpense(call)
            "delete_maintenance" -> deleteMaintenance(call)
            "delete_fuel" -> deleteFuel(call)
            else -> ToolOutcome(false, "ابزار مجاز نیست.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutcome(false, "عملیات انجام نشد: ${e.message ?: "خطای داخلی"}")
    }

    // ----------------------------------------------------------------- reads

    private suspend fun todayExpenses(): ToolOutcome {
        val today = Jalali.todayString()
        val b = load(today)
        val sb = StringBuilder("هزینه‌های امروز ($today):\n")
        b.expenses.forEach { sb.append("- شخصی: ").append(n(it.amount)).append(" تومان ").append(clip(it.title.ifBlank { PurchaseCategory.fromWire(it.category).label })).append('\n') }
        b.fuels.forEach { sb.append("- سوخت: ").append(n(it.total)).append(" تومان (").append(n(it.liters)).append(" لیتر)\n") }
        b.maints.forEach { sb.append("- تعمیر/سرویس: ").append(n(it.cost)).append(" تومان ").append(clip(it.typeText)).append('\n') }
        if (b.expenses.isEmpty() && b.fuels.isEmpty() && b.maints.isEmpty()) sb.append("هیچ هزینه‌ای برای امروز ثبت نشده است.\n")
        sb.append("جمع هزینه امروز: ").append(n(b.totalExpense)).append(" تومان")
        return ToolOutcome(true, sb.toString())
    }

    private suspend fun periodReport(call: ValidatedCall, withBreakdown: Boolean): ToolOutcome {
        val (prefix, label) = prefixFor(call)
        val b = load(prefix)
        val sb = StringBuilder("گزارش $label:\n").append(summaryLines(b))
        if (withBreakdown && b.expenses.isNotEmpty()) {
            sb.append("\nهزینه شخصی به تفکیک دسته:\n")
            b.expenses.groupBy { it.category }.entries
                .sortedByDescending { e -> e.value.sumOf { it.amount } }.take(8)
                .forEach { (cat, rows) -> sb.append("- ").append(PurchaseCategory.fromWire(cat).label).append(": ").append(n(rows.sumOf { it.amount })).append(" تومان\n") }
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun yearlyReport(call: ValidatedCall): ToolOutcome {
        val y = call.int("year") ?: curYear()
        val b = load("$y")
        val sb = StringBuilder("گزارش سال $y:\n").append(summaryLines(b)).append("\nبه تفکیک ماه (درآمد / هزینه):\n")
        for (m in 1..12) {
            val mp = monthPrefix(y, m)
            val inc = b.services.filter { it.date.startsWith(mp) }.sumOf { it.income }.toDouble() +
                b.incomes.filter { it.date.startsWith(mp) }.sumOf { it.amount }
            val exp = b.expenses.filter { it.date.startsWith(mp) }.sumOf { it.amount } +
                b.fuels.filter { it.date.startsWith(mp) }.sumOf { it.total } +
                b.maints.filter { it.date.startsWith(mp) }.sumOf { it.cost }
            if (inc != 0.0 || exp != 0.0) sb.append("- ").append(Jalali.monthName(m)).append(": ").append(n(inc)).append(" / ").append(n(exp)).append('\n')
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun totals(call: ValidatedCall): ToolOutcome {
        val (prefix, label) = when (call.str("period")) {
            "today" -> Jalali.todayString() to "امروز"
            "year" -> (call.int("year") ?: curYear()).toString().let { it to "سال $it" }
            "all" -> "" to "کل دوره"
            else -> prefixFor(call)
        }
        val b = load(prefix)
        return ToolOutcome(true, "مجموع $label:\n" + summaryLines(b))
    }

    private suspend fun listMaintenance(call: ValidatedCall): ToolOutcome {
        val prefix = if (call.has("year") || call.has("month")) prefixFor(call).first else ""
        val rows = repo.maintenancesByPrefixOnce(prefix).sortedByDescending { it.date }.take(call.int("limit") ?: 10)
        if (rows.isEmpty()) return ToolOutcome(true, "سابقهٔ تعمیر یا سرویسی ثبت نشده است.")
        val sb = StringBuilder("سوابق تعمیرات (شناسه | تاریخ | نوع | هزینه | کیلومتر | توضیح):\n")
        rows.forEach {
            sb.append(short(it.id)).append(" | ").append(it.date).append(" | ")
                .append(clip(it.typeText.ifBlank { MaintenanceType.fromWire(it.type).label }, 20)).append(" | ")
                .append(n(it.cost)).append(" | ").append(n(it.km)).append(" | ").append(clip(it.description)).append('\n')
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun listServices(call: ValidatedCall): ToolOutcome {
        val prefix = call.str("date") ?: if (call.has("year") || call.has("month")) prefixFor(call).first else ""
        val rows = repo.servicesByPrefixOnce(prefix).sortedByDescending { it.date }.take(call.int("limit") ?: 10)
        if (rows.isEmpty()) return ToolOutcome(true, "سرویسی ثبت نشده است.")
        val sb = StringBuilder("سرویس‌های ثبت‌شده (تاریخ | نوع | کیلومتر | ساعت | درآمد):\n")
        rows.forEach {
            sb.append(it.date).append(" | ").append(clip(it.type, 16)).append(" | ").append(n(it.km)).append(" | ")
                .append(n(it.hours)).append(" | ").append(n(it.income)).append('\n')
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun listExpenses(call: ValidatedCall): ToolOutcome {
        val prefix = if (call.has("year") || call.has("month")) prefixFor(call).first else ""
        val rows = repo.personalExpenses(prefix).first().sortedByDescending { it.date }.take(call.int("limit") ?: 10)
        if (rows.isEmpty()) return ToolOutcome(true, "هزینهٔ شخصی‌ای ثبت نشده است.")
        val sb = StringBuilder("هزینه‌ها (شناسه | تاریخ | دسته | مبلغ | عنوان):\n")
        rows.forEach {
            sb.append(short(it.id)).append(" | ").append(it.date).append(" | ").append(PurchaseCategory.fromWire(it.category).label)
                .append(" | ").append(n(it.amount)).append(" | ").append(clip(it.title)).append('\n')
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun listFuel(call: ValidatedCall): ToolOutcome {
        val prefix = if (call.has("year") || call.has("month")) prefixFor(call).first else ""
        val rows = repo.fuelsByPrefixOnce(prefix).sortedByDescending { it.date }.take(call.int("limit") ?: 10)
        if (rows.isEmpty()) return ToolOutcome(true, "سوخت‌گیری‌ای ثبت نشده است.")
        val sb = StringBuilder("سوخت‌گیری‌ها (شناسه | تاریخ | لیتر | مبلغ):\n")
        rows.forEach { sb.append(short(it.id)).append(" | ").append(it.date).append(" | ").append(n(it.liters)).append(" | ").append(n(it.total)).append('\n') }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun activeReminders(): ToolOutcome {
        val today = Jalali.todayString()
        val s = repo.settingsNow
        val sb = StringBuilder("یادآورهای فعال (امروز $today):\n")
        var count = 0
        repo.insurance.allOnce().filter { it.reminder }.sortedBy { it.expiryDate }.forEach {
            val left = Jalali.daysBetween(today, it.expiryDate)
            sb.append("- ").append(clip(it.type, 30)).append(": انقضا ").append(it.expiryDate)
                .append(if (left >= 0) " ($left روز مانده)" else " (${-left} روز گذشته)").append('\n')
            count++
        }
        listOf("بیمه بدنه" to s.bodyInsuranceDate, "بیمه شخص ثالث" to s.vehicleInsuranceDate, "معاینه فنی" to s.inspectionDate)
            .filter { it.second.isNotBlank() }.forEach { (label, d) ->
                val left = Jalali.daysBetween(today, d)
                sb.append("- ").append(label).append(": انقضا ").append(d).append(if (left >= 0) " ($left روز مانده)" else " (${-left} روز گذشته)").append('\n')
                count++
            }
        repo.loansOnce().forEach { loan ->
            val next = repo.loanSummary(loan).nextInstallment
            if (next != null) {
                sb.append("- قسط «").append(clip(loan.title.ifBlank { "وام" }, 25)).append("»: ").append(n(next.amount))
                    .append(" تومان، سررسید ").append(next.dueDate).append('\n')
                count++
            }
        }
        if (s.oilNextKm > 0 && s.vehicleCurrentKm > 0) {
            sb.append("- تعویض روغن: ").append(n(s.oilNextKm - s.vehicleCurrentKm)).append(" کیلومتر تا موعد\n")
            count++
        }
        if (count == 0) sb.append("یادآور فعالی وجود ندارد.")
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun analyzeFuel(call: ValidatedCall): ToolOutcome {
        val (prefix, label) = if (call.has("year") || call.has("month")) prefixFor(call) else (monthPrefix(curYear(), curMonth()) to "ماه ${Jalali.monthName(curMonth())} ${curYear()}")
        val fuels = repo.fuelsByPrefixOnce(prefix)
        if (fuels.isEmpty()) return ToolOutcome(true, "برای $label سوخت‌گیری ثبت نشده است.")
        val liters = fuels.sumOf { it.liters }
        val cost = fuels.sumOf { it.total }
        val km = repo.servicesByPrefixOnce(prefix).sumOf { it.km }
        val sb = StringBuilder("تحلیل سوخت $label:\n")
        sb.append("تعداد سوخت‌گیری: ").append(fuels.size).append('\n')
        sb.append("مجموع لیتر: ").append(n(liters)).append(" | مجموع هزینه: ").append(n(cost)).append(" تومان\n")
        if (liters > 0) sb.append("میانگین قیمت هر لیتر: ").append(n(cost / liters)).append(" تومان\n")
        if (km > 0 && liters > 0) {
            sb.append("کیلومتر ثبت‌شده در سرویس‌ها: ").append(n(km)).append('\n')
            sb.append("مصرف تقریبی: ").append(String.format(Locale.US, "%.1f", liters / km * 100.0)).append(" لیتر در ۱۰۰ کیلومتر\n")
            sb.append("هزینه سوخت هر کیلومتر: ").append(n(cost / km)).append(" تومان\n")
            sb.append("(مصرف بر پایهٔ کیلومتر سرویس‌های ثبت‌شده است و ممکن است با کارکرد واقعی فرق کند.)")
        } else {
            sb.append("کیلومتر سرویس‌های این بازه ثبت نشده؛ مصرف در ۱۰۰ کیلومتر قابل محاسبه نیست.")
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    private suspend fun vehicleStatus(): ToolOutcome {
        val s = repo.settingsNow
        val rates = repo.ratesFor(curYear())
        val sb = StringBuilder("وضعیت خودرو:\n")
        sb.append("کیلومتر فعلی: ").append(if (s.vehicleCurrentKm > 0) n(s.vehicleCurrentKm) else "ثبت نشده").append('\n')
        val types = listOf(
            MaintenanceType.OIL_CHANGE to rates.oilChangeKmInterval,
            MaintenanceType.TIMING_BELT to rates.timingBeltKmInterval,
            MaintenanceType.SPARK_PLUG to rates.sparkPlugKmInterval,
            MaintenanceType.BRAKE_PAD to rates.brakePadKmInterval,
            MaintenanceType.FUEL_FILTER to rates.fuelFilterKmInterval,
        )
        types.forEach { (mt, interval) ->
            val due = repo.maintenanceDue(
                typeWire = mt.wire, label = mt.label, interval = interval,
                currentOdometer = s.vehicleCurrentKm, nextKmOverride = s.nextKmFor(mt.wire), baseCurrentKm = s.currentKmFor(mt.wire),
            )
            sb.append("- ").append(mt.label).append(": ")
            val rem = due.remainingKm
            when {
                rem == null -> sb.append("اطلاعات کافی نیست")
                due.isOverdue -> sb.append("موعد گذشته (").append(n(-rem)).append(" کیلومتر)")
                else -> sb.append(n(rem)).append(" کیلومتر مانده").append(if (due.isDueSoon) " (نزدیک موعد)" else "")
            }
            sb.append('\n')
        }
        return ToolOutcome(true, sb.toString().trimEnd())
    }

    // ---------------------------------------------------------------- writes

    private fun now(): String = java.time.Instant.now().toString()

    private suspend fun addExpense(call: ValidatedCall): ToolOutcome {
        val amount = call.dbl("amount") ?: return ToolOutcome(false, "مبلغ مشخص نیست.")
        val date = call.str("date") ?: Jalali.todayString()
        val category = call.str("category") ?: PurchaseCategory.OTHER.wire
        val title = call.str("title") ?: PurchaseCategory.fromWire(category).label
        val before = repo.personalExpenses("").first().size
        repo.addPersonalExpense(date, category, title, amount)
        val after = repo.personalExpenses("").first()
        val saved = after.size == before + 1 && after.any { it.date == date && it.amount == amount && it.title == title }
        return if (saved) ToolOutcome(true, "ثبت شد و در پایگاه‌داده تأیید شد: هزینه ${n(amount)} تومان · $title · $date")
        else ToolOutcome(false, "ثبت هزینه تأیید نشد. لطفاً فهرست هزینه‌ها را بررسی کنید.")
    }

    private suspend fun addMaintenance(call: ValidatedCall, type: MaintenanceType): ToolOutcome {
        val date = call.str("date") ?: Jalali.todayString()
        val cost = call.dbl("amount") ?: 0.0
        val s = repo.settingsNow
        val km = call.dbl("km") ?: s.vehicleCurrentKm
        val desc = call.str("description") ?: ""
        val carType = repo.ratesFor(curYear()).defaultCarType.wire
        val before = repo.maintenancesByPrefixOnce("").size
        // Same rule as the maintenance screen: only a record that is the newest of its tracked
        // type moves the "next due" odometer.
        val latest = repo.latestMaintenance(type.wire)
        val tracked = type in MaintenanceType.TRACKED && km > 0 && (latest == null || km >= latest.km)
        repo.addMaintenance(date, type.wire, type.label, cost, km, carType, desc)
        if (tracked) {
            val interval = Analytics.intervalFor(type.wire, repo.ratesFor(curYear()))
            repo.updateSettings { it.withTrackedKm(type.wire, km, km + interval) }
        }
        val after = repo.maintenancesByPrefixOnce("")
        val saved = after.size == before + 1 && after.any { it.date == date && it.cost == cost && it.type == type.wire }
        return if (saved) ToolOutcome(true, "ثبت شد و در پایگاه‌داده تأیید شد: ${type.label} · ${n(cost)} تومان · $date")
        else ToolOutcome(false, "ثبت تأیید نشد. لطفاً بخش تعمیرات را بررسی کنید.")
    }

    private suspend fun addFuel(call: ValidatedCall): ToolOutcome {
        val liters = call.dbl("liters") ?: return ToolOutcome(false, "مقدار لیتر مشخص نیست.")
        val total = call.dbl("total_cost") ?: return ToolOutcome(false, "مبلغ مشخص نیست.")
        val date = call.str("date") ?: Jalali.todayString()
        val type = FuelType.fromWire(call.str("fuel_type"))
        val carType = repo.ratesFor(curYear()).defaultCarType.wire
        val before = repo.fuelsByPrefixOnce("").size
        repo.addFuel(date, type.wire, liters, total, carType)
        val after = repo.fuelsByPrefixOnce("")
        val saved = after.size == before + 1 && after.any { it.date == date && it.liters == liters && it.total == total }
        return if (saved) ToolOutcome(true, "ثبت شد و در پایگاه‌داده تأیید شد: ${n(liters)} لیتر سوخت · ${n(total)} تومان · $date")
        else ToolOutcome(false, "ثبت سوخت تأیید نشد. لطفاً بخش سوخت را بررسی کنید.")
    }

    private suspend fun createReminder(call: ValidatedCall): ToolOutcome {
        val title = call.str("title") ?: return ToolOutcome(false, "عنوان مشخص نیست.")
        val expiry = call.str("expiry_date") ?: return ToolOutcome(false, "تاریخ انقضا مشخص نیست.")
        val item = InsuranceEntity(
            id = UUID.randomUUID().toString(), type = title, expiryDate = expiry,
            cost = call.dbl("cost") ?: 0.0, company = call.str("company") ?: "",
            policyNumber = "", reminder = true, timestamp = now(),
        )
        repo.insurance.insert(item)
        val saved = repo.insurance.byId(item.id) != null
        return if (saved) ToolOutcome(true, "یادآور ساخته و تأیید شد: «$title» · انقضا $expiry (در بخش «بیمه و مدارک» دیده می‌شود)")
        else ToolOutcome(false, "ساخت یادآور تأیید نشد.")
    }

    // --------------------------------------------------------- edit / delete

    /** Resolves the 8-character id shown by list tools (or a full id) to exactly one record. */
    private fun <T> resolve(idArg: String, rows: List<T>, idOf: (T) -> String): T? {
        val hits = rows.filter { idOf(it) == idArg || idOf(it).startsWith(idArg) }
        return if (hits.size == 1) hits[0] else null
    }

    private suspend fun updateExpense(call: ValidatedCall): ToolOutcome {
        val old = resolve(call.str("id") ?: "", repo.personalExpenses("").first()) { it.id }
            ?: return ToolOutcome(false, "رکورد پیدا نشد یا شناسه یکتا نیست.")
        val upd = old.copy(
            amount = call.dbl("amount") ?: old.amount, title = call.str("title") ?: old.title,
            category = call.str("category") ?: old.category, date = call.str("date") ?: old.date,
        )
        repo.updatePersonalExpense(upd)
        val saved = repo.personalExpenses("").first().firstOrNull { it.id == old.id }
        return if (saved == upd) ToolOutcome(true, "ویرایش شد و تأیید شد: ${n(upd.amount)} تومان · ${upd.title} · ${upd.date}")
        else ToolOutcome(false, "ویرایش تأیید نشد.")
    }

    private suspend fun updateMaintenance(call: ValidatedCall): ToolOutcome {
        val old = resolve(call.str("id") ?: "", repo.maintenancesByPrefixOnce("")) { it.id }
            ?: return ToolOutcome(false, "رکورد پیدا نشد یا شناسه یکتا نیست.")
        val upd = old.copy(
            cost = call.dbl("amount") ?: old.cost, km = call.dbl("km") ?: old.km,
            description = call.str("description") ?: old.description, date = call.str("date") ?: old.date,
        )
        repo.updateMaintenance(upd)
        val saved = repo.maintenancesByPrefixOnce("").firstOrNull { it.id == old.id }
        return if (saved == upd) ToolOutcome(true, "ویرایش شد و تأیید شد: ${upd.typeText} · ${n(upd.cost)} تومان · ${upd.date}")
        else ToolOutcome(false, "ویرایش تأیید نشد.")
    }

    private suspend fun updateFuel(call: ValidatedCall): ToolOutcome {
        val old = resolve(call.str("id") ?: "", repo.fuelsByPrefixOnce("")) { it.id }
            ?: return ToolOutcome(false, "رکورد پیدا نشد یا شناسه یکتا نیست.")
        val upd: FuelEntity = old.copy(
            liters = call.dbl("liters") ?: old.liters, total = call.dbl("total_cost") ?: old.total, date = call.str("date") ?: old.date,
        )
        repo.updateFuel(upd)
        val saved = repo.fuelsByPrefixOnce("").firstOrNull { it.id == old.id }
        return if (saved == upd) ToolOutcome(true, "ویرایش شد و تأیید شد: ${n(upd.liters)} لیتر · ${n(upd.total)} تومان · ${upd.date}")
        else ToolOutcome(false, "ویرایش تأیید نشد.")
    }

    private suspend fun deleteExpense(call: ValidatedCall): ToolOutcome {
        val old = resolve(call.str("id") ?: "", repo.personalExpenses("").first()) { it.id }
            ?: return ToolOutcome(false, "رکورد پیدا نشد یا شناسه یکتا نیست. چیزی حذف نشد.")
        repo.deletePersonalExpense(old.id)
        val gone = repo.personalExpenses("").first().none { it.id == old.id }
        return if (gone) ToolOutcome(true, "حذف شد و تأیید شد: هزینه ${n(old.amount)} تومان · ${old.date}")
        else ToolOutcome(false, "حذف تأیید نشد.")
    }

    private suspend fun deleteMaintenance(call: ValidatedCall): ToolOutcome {
        val old = resolve(call.str("id") ?: "", repo.maintenancesByPrefixOnce("")) { it.id }
            ?: return ToolOutcome(false, "رکورد پیدا نشد یا شناسه یکتا نیست. چیزی حذف نشد.")
        repo.deleteMaintenance(old.id)
        val gone = repo.maintenancesByPrefixOnce("").none { it.id == old.id }
        return if (gone) ToolOutcome(true, "حذف شد و تأیید شد: ${old.typeText} · ${n(old.cost)} تومان · ${old.date}")
        else ToolOutcome(false, "حذف تأیید نشد.")
    }

    private suspend fun deleteFuel(call: ValidatedCall): ToolOutcome {
        val old = resolve(call.str("id") ?: "", repo.fuelsByPrefixOnce("")) { it.id }
            ?: return ToolOutcome(false, "رکورد پیدا نشد یا شناسه یکتا نیست. چیزی حذف نشد.")
        repo.deleteFuel(old.id)
        val gone = repo.fuelsByPrefixOnce("").none { it.id == old.id }
        return if (gone) ToolOutcome(true, "حذف شد و تأیید شد: سوخت ${n(old.liters)} لیتر · ${n(old.total)} تومان · ${old.date}")
        else ToolOutcome(false, "حذف تأیید نشد.")
    }

    /** Human-readable description of the record a delete would remove (shown before confirming). */
    override suspend fun describeTarget(call: ValidatedCall): String? {
        val idArg = call.str("id") ?: return null
        return when (call.spec.name) {
            "delete_expense" -> resolve(idArg, repo.personalExpenses("").first()) { it.id }?.let { "هزینه ${n(it.amount)} تومان · ${clip(it.title)} · ${it.date}" }
            "delete_maintenance" -> resolve(idArg, repo.maintenancesByPrefixOnce("")) { it.id }?.let { "${it.typeText} · ${n(it.cost)} تومان · ${it.date}" }
            "delete_fuel" -> resolve(idArg, repo.fuelsByPrefixOnce("")) { it.id }?.let { "سوخت ${n(it.liters)} لیتر · ${n(it.total)} تومان · ${it.date}" }
            else -> null
        }
    }
}
