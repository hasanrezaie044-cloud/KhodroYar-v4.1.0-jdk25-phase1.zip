package com.khodroyar.app.data.repo

import androidx.room.withTransaction
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.*
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.prefs.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

enum class QuickKind(val label: String, val english: String) {
    EXPENSE("هزینه", "Expense"), SERVICE("سرویس", "Trip"), FUEL("سوخت", "Fuel"), MAINTENANCE("تعمیر", "Maintenance")
}

/** A proposal, never an instruction. Source records are copied without IDs, receipts or request numbers. */
data class QuickDraft(
    val kind: QuickKind = QuickKind.EXPENSE,
    val date: String = Jalali.todayString(),
    val title: String = "",
    val amount: Double = 0.0,
    val category: String = "other",
    val liters: Double = 0.0,
    val fuelType: String = "gov",
    val maintenanceType: String = "repair",
    val km: Double = 0.0,
    val carType: String = "soren",
    val trip: ServiceEntity? = null,
) {
    fun error(today: String = Jalali.todayString()): String? = when {
        !Regex("1[3-6]\\d{2}/\\d{2}/\\d{2}").matches(date) || Jalali.parse(date)?.let { !Jalali.isValid(it.year, it.month, it.day) } != false || date > today -> "تاریخ معتبر و حداکثر امروز را انتخاب کن."
        !amount.isFinite() || amount !in 0.0..1_000_000_000.0 -> "مبلغ معتبر و حداکثر یک میلیارد تومان لازم است."
        title.length > 80 || title.any { it.isISOControl() } -> "عنوان باید کوتاه و بدون نویسهٔ کنترلی باشد."
        CarType.entries.none { it.wire == carType } -> "خودرو معتبر نیست."
        kind == QuickKind.EXPENSE && (amount <= 0 || title.isBlank() || PurchaseCategory.entries.none { it.wire == category }) -> "عنوان، مبلغ مثبت و دستهٔ هزینه را مشخص کن."
        kind == QuickKind.FUEL && (!liters.isFinite() || liters !in 0.1..500.0 || FuelType.entries.none { it.wire == fuelType }) -> "مقدار سوخت بین ۰٫۱ تا ۵۰۰ لیتر و نوع آن را مشخص کن."
        kind == QuickKind.MAINTENANCE && (!km.isFinite() || km !in 0.0..3_000_000.0 || MaintenanceType.entries.none { it.wire == maintenanceType }) -> "نوع تعمیر و کیلومتر معتبر را مشخص کن."
        kind == QuickKind.SERVICE && !validTrip() -> "برای ثبت سریع سرویس، یک سابقه یا الگوی کامل انتخاب کن؛ فرم کامل هم در دسترس است."
        else -> null
    }

    private fun validTrip(): Boolean {
        val row = trip ?: return false
        val type = ServiceType.fromWireOrNull(row.type) ?: return false
        val fixed = type in ServiceType.FIXED_AMOUNT_TYPES
        return row.km.isFinite() && row.km in 0.0..3000.0 &&
            row.hours.isFinite() && row.hours in 0.0..24.0 && row.workHours.isFinite() && row.workHours in 0.0..24.0 && row.tollCount in 0..100 &&
            listOf(row.missionFood, row.missionToll, row.missionFine).all { it.isFinite() && it in 0.0..1_000_000_000.0 } &&
            listOf(row.startTime,row.endTime).all { it.isEmpty() || com.khodroyar.app.automation.validTime(it) } &&
            (fixed || row.origin.isNotBlank() && row.destination.isNotBlank() && (row.km > 0 || row.hours > 0))
    }

    companion object {
        fun from(row: PersonalExpenseEntity, date: String = Jalali.todayString()) = QuickDraft(QuickKind.EXPENSE, date, row.title, row.amount, row.category)
        fun from(row: FuelEntity, date: String = Jalali.todayString()) = QuickDraft(QuickKind.FUEL, date, amount = row.total, liters = row.liters, fuelType = row.type, carType = row.carType)
        fun from(row: MaintenanceEntity, date: String = Jalali.todayString()) = QuickDraft(QuickKind.MAINTENANCE, date, row.typeText, row.cost, maintenanceType = row.type, km = row.km, carType = row.carType)
        fun from(row: ServiceEntity, date: String = Jalali.todayString()) = QuickDraft(QuickKind.SERVICE, date, carType = row.carType,
            trip = row.copy(id = "", date = date, requestNumber = "", attachmentPath = "", timestamp = ""))
    }
}

data class TrackedChange(val wire: String, val beforeCurrent: Double, val beforeNext: Double, val afterCurrent: Double, val afterNext: Double)
/** Only one session receipt is retained. Restart/restore cannot resurrect a stale delete command. */
data class EntryReceipt(
    val id: String, val draft: QuickDraft, val row: Any, val revision: String = UUID.randomUUID().toString(),
    val odometerBefore: Double? = null, val odometerAfter: Double? = null, val tracked: TrackedChange? = null,
)

/** Explicit preview -> confirm -> verified Room write. No network, new table, model, or background service. */
class QuickEntries(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val ratesFor: suspend (Int) -> Rates,
) {
    val receipt: StateFlow<EntryReceipt?> get() = latest
    val recoveryNotice: StateFlow<String?> get() = settings.quickMileageNotice
    fun acknowledgeRecoveryNotice() = settings.acknowledgeQuickMileageNotice()
    suspend fun recover() = withContext(Dispatchers.IO + NonCancellable) {
        RecordMutations.mutex.withLock { QuickMileageJournal.recover(db, settings) }
        Unit
    }


    suspend fun previewIncome(draft: QuickDraft): Long? = draft.trip?.let { row ->
        val rates = ratesFor(requireNotNull(Jalali.parse(draft.date)).year)
        IncomeCalculator.calculate(ServiceType.fromWire(row.type), row.km, row.hours, row.tollCount,
            row.missionFood, row.missionToll, row.missionFine, rates, row.startTime.ifBlank { null }, row.endTime.ifBlank { null })
    }

    suspend fun save(draft: QuickDraft, operationId: String, expectedIncome: Long? = null, editing: EntryReceipt? = null, expectedExpenseSource: PersonalExpenseEntity? = null): EntryReceipt = withContext(Dispatchers.IO + NonCancellable) {
        RecordMutations.mutate(db, settings) {
            require(draft.error() == null) { draft.error().orEmpty() }
            require(runCatching { UUID.fromString(operationId) }.isSuccess)
            consumed[operationId]?.let { previous ->
                check(latest.value?.revision == previous.revision) { "این درخواست قبلاً پردازش شده؛ دوباره ثبت نشد." }
                return@mutate previous
            }
            if (editing != null) check(editing.draft.kind == draft.kind && latest.value?.revision == editing.revision && matches(editing)) { "ثبت تغییر کرده است؛ دوباره آن را از فهرست اصلی باز کن." }
            val income = previewIncome(draft)
            check(income == expectedIncome) { "نرخ‌ها تغییر کرده‌اند؛ خلاصه را دوباره بررسی کن." }
            val id = editing?.id ?: operationId
            val timestamp = java.time.Instant.now().toString()
            val row: Any = when (draft.kind) {
                QuickKind.EXPENSE -> PersonalExpenseEntity(id, draft.date, draft.category, draft.title.trim(), draft.amount, timestamp)
                QuickKind.FUEL -> FuelEntity(id, draft.date, draft.fuelType, draft.liters, draft.amount, draft.carType, timestamp)
                QuickKind.MAINTENANCE -> MaintenanceEntity(id, draft.date, draft.maintenanceType,
                    draft.title.ifBlank { MaintenanceType.fromWire(draft.maintenanceType).label }, draft.amount, draft.km, draft.carType, "", timestamp)
                QuickKind.SERVICE -> requireNotNull(draft.trip).copy(id = id, date = draft.date, carType = draft.carType, income = requireNotNull(income), timestamp = timestamp, attachmentPath = "", requestNumber = "")
            }
            val before = settings.current
            val mileage = (row as? ServiceEntity)?.takeIf { ServiceType.fromWire(it.type) !in ServiceType.FIXED_AMOUNT_TYPES }?.km
            val previousMileage = (editing?.row as? ServiceEntity)?.takeIf { ServiceType.fromWire(it.type) !in ServiceType.FIXED_AMOUNT_TYPES }?.km ?: 0.0
            if (editing?.odometerAfter != null) check(before.vehicleCurrentKm == editing.odometerAfter) { "کیلومتر خودرو بعد از این ثبت تغییر کرده؛ از ویرایش کامل سرویس استفاده کن." }
            val trackedType = (row as? MaintenanceEntity)?.type?.takeIf { Analytics.intervalFor(it, ratesFor(requireNotNull(Jalali.parse(draft.date)).year)) > 0 && draft.date == Jalali.todayString() && draft.km > 0 }
            val next = if (trackedType != null) draft.km + Analytics.intervalFor(trackedType, ratesFor(requireNotNull(Jalali.parse(draft.date)).year)) else 0.0
            if (editing?.tracked != null) {
                val old = editing.tracked
                check(before.currentKmFor(old.wire) == old.afterCurrent && before.nextKmFor(old.wire) == old.afterNext && trackedType == old.wire) { "تنظیم سرویس دوره‌ای تغییر کرده؛ از فرم کامل تعمیر استفاده کن." }
            }
            val touchesOdometer = mileage != null || previousMileage > 0
            var planned = before
            if (touchesOdometer) planned = planned.copy(vehicleCurrentKm = (planned.vehicleCurrentKm + (mileage ?: 0.0) - previousMileage).coerceAtLeast(0.0))
            if (trackedType != null) planned = planned.withTrackedKm(trackedType, draft.km, next)
            val journal = QuickMileageJournal.plan(draft.kind, id, editing?.row, row, before, planned, touchesOdometer, trackedType)
            journal?.let { settings.stageQuickMileage(it.encode(), it::beforeMatches) }
            var recovery = QuickMileageJournal.Recovery.NONE
            try {
                db.withTransaction {
                    if (expectedExpenseSource != null) check(db.insights().expense(expectedExpenseSource.id) == expectedExpenseSource) { "هزینهٔ مبنا هم‌زمان تغییر کرد؛ خلاصهٔ تازه لازم است. چیزی ثبت نشد." }
                    if (editing != null) check(matches(editing)) { "رکورد هم‌زمان ویرایش شد؛ تغییری نکرد." }
                    when (row) {
                        is PersonalExpenseEntity -> if (editing == null) check(db.personalExpenses().insertIfAbsent(row) != -1L) else db.personalExpenses().insert(row)
                        is FuelEntity -> if (editing == null) check(db.fuels().insertIfAbsent(row) != -1L) else db.fuels().insert(row)
                        is MaintenanceEntity -> if (editing == null) check(db.maintenances().insertIfAbsent(row) != -1L) else db.maintenances().insert(row)
                        is ServiceEntity -> if (editing == null) check(db.services().insertIfAbsent(row) != -1L) else db.services().update(row)
                        else -> error("نوع ثبت معتبر نیست")
                    }
                }
            } finally {
                if (journal != null) recovery = QuickMileageJournal.recover(db, settings)
            }
            check(recovery != QuickMileageJournal.Recovery.CONFLICT) { "رکورد ثبت شد؛ کیلومترِ دستی حفظ شد. فهرست و کیلومتر خودرو را بررسی کن؛ دوباره ثبت نکن." }
            val after = settings.current
            val change = trackedType?.let { TrackedChange(it, editing?.tracked?.beforeCurrent ?: before.currentKmFor(it), editing?.tracked?.beforeNext ?: before.nextKmFor(it), after.currentKmFor(it), after.nextKmFor(it)) }
            val saved = EntryReceipt(id, draft, row,
                odometerBefore = if (touchesOdometer) editing?.odometerBefore ?: before.vehicleCurrentKm else null,
                odometerAfter = if (touchesOdometer) after.vehicleCurrentKm else null, tracked = change)
            check(matches(saved)) { "نتیجهٔ ثبت در پایگاه‌داده تأیید نشد؛ فهرست اصلی را بررسی کن." }
            consumed[operationId] = saved; if (consumed.size > 256) consumed.remove(consumed.keys.first())
            latest.value = saved
            saved
        }
    }

    private suspend fun matches(receipt: EntryReceipt): Boolean = when (receipt.row) {
        is PersonalExpenseEntity -> db.insights().expense(receipt.id) == receipt.row
        is FuelEntity -> db.insights().fuel(receipt.id) == receipt.row
        is MaintenanceEntity -> db.insights().maintenance(receipt.id) == receipt.row
        is ServiceEntity -> db.services().byId(receipt.id) == receipt.row
        else -> false
    }

    /** Refuse stale receipts or changed odometer/tracking; never delete an arbitrary last row. */
    suspend fun undo(receipt: EntryReceipt): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        RecordMutations.mutate(db, settings) {
            if (latest.value?.revision != receipt.revision) return@mutate false
            if (!matches(receipt) || !stateMatches(receipt, settings.current)) return@mutate false
            val before = settings.current
            var planned = before
            receipt.odometerBefore?.let { planned = planned.copy(vehicleCurrentKm = it) }
            receipt.tracked?.let { planned = planned.withTrackedKm(it.wire, it.beforeCurrent, it.beforeNext) }
            val journal = QuickMileageJournal.plan(receipt.draft.kind, receipt.id, receipt.row, null, before, planned,
                receipt.odometerAfter != null, receipt.tracked?.wire)
            journal?.let { settings.stageQuickMileage(it.encode(), it::beforeMatches) }
            val success = try {
                db.withTransaction {
                    if (!matches(receipt)) return@withTransaction false
                    when (receipt.row) {
                        is PersonalExpenseEntity -> db.personalExpenses().deleteById(receipt.id)
                        is FuelEntity -> db.fuels().deleteById(receipt.id)
                        is MaintenanceEntity -> db.maintenances().deleteById(receipt.id)
                        is ServiceEntity -> db.services().deleteById(receipt.id)
                    }
                    true
                }
            } finally {
                if (journal != null) QuickMileageJournal.recover(db, settings)
            }
            if (success) latest.value = null
            success
        }
    }

    private fun stateMatches(receipt: EntryReceipt, state: AppSettings): Boolean =
        (receipt.odometerAfter == null || state.vehicleCurrentKm == receipt.odometerAfter) &&
            (receipt.tracked?.let { state.currentKmFor(it.wire) == it.afterCurrent && state.nextKmFor(it.wire) == it.afterNext } != false)

    companion object {
        private val latest = MutableStateFlow<EntryReceipt?>(null)
        private val consumed = LinkedHashMap<String, EntryReceipt>()
        fun invalidate() { latest.value = null }
        fun invalidateSettingsChange(before: AppSettings, after: AppSettings) {
            val receipt = latest.value ?: return
            if (receipt.odometerAfter != null && before.vehicleCurrentKm != after.vehicleCurrentKm ||
                receipt.tracked?.let { before.currentKmFor(it.wire) != after.currentKmFor(it.wire) || before.nextKmFor(it.wire) != after.nextKmFor(it.wire) } == true) latest.value = null
        }
    }
}
