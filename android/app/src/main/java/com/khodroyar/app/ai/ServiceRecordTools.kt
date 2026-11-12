package com.khodroyar.app.ai

import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.core.text.PersianInput
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.data.repo.ServiceBatchPlan
import kotlinx.coroutines.CancellationException

/** Existing app repository + existing approval gate, including app-owned optimistic snapshots. */
class ServiceRecordTools(private val repository: AppRepository) : AgentToolRunner {
    override suspend fun prepare(call: ValidatedCall): ValidatedCall = try {
        require(call.spec.name in ServiceCommandSpecs.names)
        if (call.spec.kind == ToolKind.READ) return call
        val plan = repository.serviceBatches.prepare(call.spec.name, requireNotNull(call.str("start_date")), requireNotNull(call.str("end_date")),
            requireNotNull(call.str("service_type")), call.int("per_day"), call.str("days") == "workdays", call.dbl("km"), call.dbl("hours"), call.str("new_type")).copy(reportAfter = call.str("report_after") == "on")
        ValidatedCall(call.spec, call.args + ("service_plan" to plan))
    } catch (e: CancellationException) { throw e }
    catch (e: IllegalArgumentException) { throw AgentUserException(e.message ?: "اطلاعات سرویس کامل نیست؛ چیزی تغییر نکرد.") }

    private fun plan(call: ValidatedCall): ServiceBatchPlan = (call.args["service_plan"] as? ServiceBatchPlan)
        ?.takeIf { it.action == call.spec.name } ?: throw AgentUserException("پیش‌نمایش معتبر موجود نیست؛ درخواست را دوباره بدهید.")
    override fun describe(call: ValidatedCall) = if (call.spec.kind == ToolKind.READ)
        "سرویس‌های ${call.str("start_date")} تا ${call.str("end_date")}" else plan(call).summary()
    override suspend fun describeTarget(call: ValidatedCall): String? = if (call.spec.name == "bulk_delete_services")
        plan(call).changes.mapNotNull { it.before }.takeIf { it.isNotEmpty() }?.let { rows ->
            "${rows.size} سرویس مشخص با شناسه‌های ثابت؛ ${rows.map { it.date }.distinct().size} روز. هیچ رکورد دیگری جایگزین هدف حذف نمی‌شود."
        } else null
    override suspend fun execute(call: ValidatedCall): ToolOutcome = try {
        if (call.spec.kind == ToolKind.READ) read(call) else {
            val p = plan(call)
            val n = repository.serviceBatches.execute(p)
            val result = "$n سرویس طبق همین پیش‌نمایش در پایگاه‌داده تأیید و بازخوانی شد.\n${p.start} تا ${p.end}\n" +
                "رکوردهای بعد: ${p.created.size} سرویس · ${p.created.sumOf { it.km }} کیلومتر · ${p.created.sumOf { it.hours }} ساعت\n" +
                "این اعداد فقط رکوردهای تغییرکرده‌اند؛ برای کل بازه بگو «گزارش همونا رو بده». ثبت‌های موجود دوباره ایجاد نشدند."
            val report = if (p.reportAfter) read(ValidatedCall(requireNotNull(AgentToolRegistry.find("report_services_range")), call.args)).text else ""
            ToolOutcome(true, result + if (report.isNotEmpty()) "\n\nگزارش پس از عملیات:\n$report" else "")
        }
    } catch (e: CancellationException) { throw e }
    catch (e: AgentUserException) { ToolOutcome(false, e.userMessage) }
    catch (e: Exception) { ToolOutcome(false, e.message?.take(250) ?: "عملیات تأیید نشد؛ سوابق را بررسی کنید.") }

    private suspend fun read(call: ValidatedCall): ToolOutcome {
        val start = requireNotNull(call.str("start_date")); val end = requireNotNull(call.str("end_date"))
        require(PersianInput.days(start, end) != null) { "بازهٔ جستجو/گزارش باید حداکثر ۳۶۶ روز باشد." }
        val rows = repository.servicesInRangeOnce(start, end).filter { call.str("service_type") == "all" || it.type == call.str("service_type") }
        if (rows.isEmpty()) return ToolOutcome(true, "از $start تا $end سرویس مطابق این نوع پیدا نشد.")
        fun n(value: Double) = Analytics.formatNumber(value)
        val text = buildString {
            append("$start تا $end\n${rows.size} سرویس · ${n(rows.sumOf { it.km })} کیلومتر · ${n(rows.sumOf { it.hours })} ساعت\nدرآمد: ${Analytics.formatNumber(rows.sumOf { it.income })} تومان\n")
            if (call.spec.name == "search_services_range") {
                rows.take(20).forEach { append("${it.id.take(8)} | ${it.date} | ${ServiceType.fromWire(it.type).label} | ${n(it.km)} km | ${n(it.hours)} ساعت\n") }
                if (rows.size > 20) append("فقط ۲۰ نتیجهٔ اول نمایش داده شد؛ جمع‌ها برای همهٔ ${rows.size} رکورد است. برای انتخاب دقیق بازه را کوتاه‌تر کن.\n")
            } else {
                val days = rows.groupBy { it.date }.toSortedMap()
                days.entries.take(30).forEach { (date, entries) -> append("$date: ${entries.size} سرویس · ${n(entries.sumOf { it.km })} km · ${n(entries.sumOf { it.hours })} ساعت\n") }
                if (days.size > 30) append("فقط ۳۰ روز اول نمایش داده شد؛ جمع‌ها برای کل بازه است. همهٔ جزئیات در تب گزارش‌ها موجود است.\n")
            }
        }
        return ToolOutcome(true, text)
    }
}
