package com.khodroyar.app.ai

import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.data.repo.AgentMaintenanceChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** Upgrades existing create tools: exact app-owned row/ID/defaults before approval, atomic readback, replay-safe. */
class SafeRecordTools(private val repo: AppRepository, private val existing: AgentToolRunner = AgentToolExecutor(repo)) : AgentToolRunner {
    private val creates = setOf("add_expense", "add_repair", "add_car_service", "add_fuel", "create_reminder")
    override suspend fun prepare(call: ValidatedCall): ValidatedCall {
        if (call.spec.name !in creates) return existing.prepare(call)
        val date = call.str("date") ?: Jalali.todayString()
        val id = UUID.randomUUID().toString(); val now = java.time.Instant.now().toString()
        val settings = repo.settingsNow
        val rates = repo.ratesFor(Jalali.currentYear())
        val row: Any = when (call.spec.name) {
            "add_expense" -> {
                val category = call.str("category") ?: "other"
                PersonalExpenseEntity(id, date, category, call.str("title") ?: PurchaseCategory.fromWire(category).label, requireNotNull(call.dbl("amount")), now)
            }
            "add_fuel" -> FuelEntity(id, date, FuelType.fromWire(call.str("fuel_type")).wire, requireNotNull(call.dbl("liters")),
                requireNotNull(call.dbl("total_cost")), rates.defaultCarType.wire, now)
            "create_reminder" -> InsuranceEntity(id, requireNotNull(call.str("title")), requireNotNull(call.str("expiry_date")),
                call.dbl("cost") ?: 0.0, call.str("company").orEmpty(), "", true, now)
            else -> {
                val type = if (call.spec.name == "add_repair") MaintenanceType.REPAIR else MaintenanceType.fromWire(call.str("service_type"))
                MaintenanceEntity(id, date, type.wire, type.label, call.dbl("amount") ?: 0.0, call.dbl("km") ?: settings.vehicleCurrentKm,
                    rates.defaultCarType.wire, call.str("description").orEmpty(), now)
            }
        }
        val tracking = (row as? MaintenanceEntity)?.let { repo.prepareAgentMaintenance(it) }
        val args = call.args + mapOf("created_record" to row) + if (tracking == null) emptyMap() else mapOf("tracked_change" to tracking)
        return ValidatedCall(call.spec, args)
    }
    override fun describe(call: ValidatedCall): String = if (call.spec.name !in creates) existing.describe(call) else {
        fun n(value: Double) = Analytics.formatNumber(value)
        "یک رکورد جدید؛ فقط پس از تأیید:\n" + when (val row = call.args["created_record"]) {
            is PersonalExpenseEntity -> "هزینه «${row.title}» · دسته ${PurchaseCategory.fromWire(row.category).label} · ${n(row.amount)} تومان · ${row.date}"
            is FuelEntity -> "سوخت ${n(row.liters)} لیتر · ${n(row.total)} تومان · ${FuelType.fromWire(row.type).label} · خودرو ${row.carType} · ${row.date}"
            is MaintenanceEntity -> "${row.typeText} · ${n(row.cost)} تومان · ${n(row.km)} کیلومتر · ${row.date}\n${row.description}\n${(call.args["tracked_change"] as? AgentMaintenanceChange)?.change?.let { "موعد همین نوع (کیلومتر ثبت/بعدی): ${it.beforeCurrent}/${it.beforeNext} → ${it.afterCurrent}/${it.afterNext}؛ با منطق فرم نگهداری و ثبت مقاوم به وقفه." } ?: "موعد دوره‌ای تغییر نمی‌کند؛ سوابق قبلی دست‌نخورده‌اند."}"
            is InsuranceEntity -> "یادآور/مدرک «${row.type}» · انقضا ${row.expiryDate} · ${n(row.cost)} تومان · شرکت ${row.company.ifBlank { "ثبت نشده" }} · اعلان فعال"
            else -> throw AgentUserException("پیش‌نمایش آماده نیست؛ چیزی ذخیره نمی‌شود.")
        }
    }
    override suspend fun describeTarget(call: ValidatedCall): String? = existing.describeTarget(call)
    override suspend fun execute(call: ValidatedCall): ToolOutcome {
        if (call.spec.name !in creates) return existing.execute(call)
        val row = call.args["created_record"] ?: return ToolOutcome(false, "پیش‌نمایش معتبر موجود نیست؛ چیزی ثبت نشد.")
        return withContext(NonCancellable) {
            try {
                repo.applyApprovedAgentRecord(null, row, call.args["tracked_change"] as? AgentMaintenanceChange)
                ToolOutcome(true, "ثبت و بازخوانی همین رکورد در دیتابیس تأیید شد:\n${describe(call)}\nشناسهٔ ثابت: " + when (row) {
                    is PersonalExpenseEntity -> row.id; is FuelEntity -> row.id; is MaintenanceEntity -> row.id; is InsuranceEntity -> row.id; else -> ""
                })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ToolOutcome(false, "ثبت/بازخوانی رکورد تأیید نشد. همان عملیات خودکار تکرار نمی‌شود؛ سوابق را بررسی کنید.") }
        }
    }
}
