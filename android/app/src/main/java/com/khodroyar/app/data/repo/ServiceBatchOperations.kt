package com.khodroyar.app.data.repo

import androidx.room.withTransaction
import com.khodroyar.app.automation.ServiceTemplate
import com.khodroyar.app.core.holidays.WorkCalendar
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.IncomeCalculator
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.core.text.PersianInput
import com.khodroyar.app.data.db.AppDatabase
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** App-owned immutable before/after plan. Provider JSON never supplies this object. */
data class ServiceChange(val before: ServiceEntity?, val after: ServiceEntity?)
data class ServiceBatchPlan(
    val action: String, val operationId: String, val start: String, val end: String,
    val sourceHash: String, val changes: List<ServiceChange>, val skipped: Int,
    val presetHash: String?, val rates: Map<Int, String>, val mileageBefore: Double, val mileageAfter: Double,
    val template: ServiceTemplate?, val perDay: Int?, val workdaysOnly: Boolean,
) {
    val created get() = changes.mapNotNull { it.after }
    fun summary(): String = buildString {
        append(when (action) { "bulk_register_services" -> "ثبت گروهی"; "bulk_edit_services" -> "ویرایش گروهی"; else -> "حذف گروهی" })
        append(" · ${changes.size} سرویس\nاز $start تا $end (شامل هر دو روز)\n")
        if (template != null) append(template.summary()).append("\nتعداد هدف: $perDay سرویس در هر روز ${if (workdaysOnly) "کاری" else "تقویمی؛ حتی تعطیلات"}\n")
        if (skipped > 0) append("$skipped نوبت موجود/غیرکاری کنار گذاشته شد؛ رکورد موجود دوباره ساخته نمی‌شود.\n")
        val old = changes.mapNotNull { it.before }; val next = created
        append("قبل: ${old.size} سرویس · ${old.sumOf { it.km }} کیلومتر · ${old.sumOf { it.hours }} ساعت\n")
        append("بعد: ${next.size} سرویس · ${next.sumOf { it.km }} کیلومتر · ${next.sumOf { it.hours }} ساعت\n")
        append("درآمد رکوردهای بعد: ${next.sumOf { it.income }} تومان؛ با نرخ سال هر تاریخ\n")
        append("کیلومتر خودرو: $mileageBefore ← $mileageAfter\n\nروزهای دقیقِ تغییر:\n")
        changes.groupBy { (it.after ?: it.before)!!.date }.toSortedMap().forEach { (date, rows) ->
            val b = rows.mapNotNull { it.before }; val a = rows.mapNotNull { it.after }
            append("$date: ${b.size} → ${a.size} سرویس؛ ${b.sumOf { it.km }} → ${a.sumOf { it.km }} km؛ ${b.sumOf { it.hours }} → ${a.sumOf { it.hours }} ساعت\n")
        }
        append("\nهیچ تاریخ یا مقدار ناخوانایی حدس زده نمی‌شود. فقط همین خلاصه با دکمهٔ تأیید اجرا می‌شود.")
    }
}

class ServiceBatchOperations(private val db: AppDatabase, private val settings: SettingsStore, private val ratesFor: suspend (Int) -> Rates) {
    fun savedTemplate(s: AppSettings = settings.current): ServiceTemplate? = if (!s.autoServicePresetSaved) null else
        ServiceTemplate(s.autoServiceType, s.autoServiceCarType, s.autoServiceOrigin, s.autoServiceDestination, s.autoServiceKm,
            s.autoServiceHours, s.autoServiceStartTime, s.autoServiceEndTime, s.autoServiceTollCount, s.autoServicePassengers)
    private fun presetHash() = savedTemplate()?.let { hash(it.toString()) }
    suspend fun prepare(action: String, start: String, end: String, type: String, perDay: Int?, workdays: Boolean,
        km: Double?, hours: Double?, newType: String?): ServiceBatchPlan = withContext(Dispatchers.IO) {
        val dates = requireNotNull(PersianInput.days(start, end)) { "بازه باید معتبر و حداکثر ۳۶۶ روز باشد." }
        require(end <= Jalali.todayString()) { "برای آینده ثبت/تغییر انجام نمی‌شود." }
        require(type == "all" || ServiceType.fromWireOrNull(type) != null)
        require(km == null || km.isFinite() && km in 0.0..3000.0)
        require(hours == null || hours.isFinite() && hours in 0.0..24.0)
        val s = settings.current
        val source = db.services().rangeOnce(start, end)
        val selected = source.filter { type == "all" || it.type == type }
        val changes = mutableListOf<ServiceChange>()
        val rateRows = mutableMapOf<Int, Rates>()
        suspend fun rates(date: String): Rates {
            val year = requireNotNull(PersianInput.date(date)).take(4).toInt()
            return rateRows[year] ?: ratesFor(year).also { rateRows[year] = it }
        }
        suspend fun price(row: ServiceEntity): ServiceEntity = row.copy(income = IncomeCalculator.calculate(
            requireNotNull(ServiceType.fromWireOrNull(row.type)), row.km, row.hours, row.tollCount,
            row.missionFood, row.missionToll, row.missionFine, rates(row.date), row.startTime.ifBlank { null }, row.endTime.ifBlank { null }))
        val op = UUID.randomUUID().toString()
        var skipped = 0; var template: ServiceTemplate? = null
        when (action) {
            "bulk_register_services" -> {
                require(type != "all" && perDay != null && perDay in 1..20 && dates.size * perDay <= 1000) { "حداکثر ۱۰۰۰ سرویس و ۲۰ سرویس در روز مجاز است." }
                template = requireNotNull(savedTemplate()) { "ابتدا مسیر و مقدارهای واقعی را در تب ثبت سرویس با «ذخیره فرم فعلی برای ثبت خودکار» ذخیره کن؛ سپس بگو «الگو را ذخیره کردم». هنوز ثبتی انجام نشده است." }
                    .copy(type = type, km = km ?: savedTemplate()!!.km, hours = hours ?: savedTemplate()!!.hours)
                val t = requireNotNull(template)
                require(t.valid()) { "الگوی سرویس ناقص است؛ مسیر، کیلومتر و ساعت واقعی را در تب ثبت سرویس ذخیره کن. سپس به همین گفتگو برگرد." }
                require(s.isRental || ServiceType.fromWire(type) !in ServiceType.RENTAL_TYPES) { "نوع اجاره‌ای در تنظیمات این خودرو فعال نیست." }
                val holidays = db.holidays().allOnce().map { it.date }.toSet()
                dates.forEach { date ->
                    if (workdays && WorkCalendar.isNonWorkingDay(date, holidays)) { skipped += perDay; return@forEach }
                    val existing = selected.count { it.date == date }
                    skipped += minOf(existing, perDay)
                    for (ordinal in existing until perDay) {
                        val id = UUID.nameUUIDFromBytes("khodroyar:bulk:$op:$date:$ordinal".toByteArray()).toString()
                        changes += ServiceChange(null, price(ServiceEntity(id, date, t.type, t.carType, t.km, t.hours, t.hours,
                            t.startTime, t.endTime, t.origin, t.destination, t.passengers, "", t.tollCount, 0.0, 0.0, 0.0, 0L, java.time.Instant.now().toString())))
                    }
                }
            }
            "bulk_edit_services" -> {
                require(km != null || hours != null || newType != null) { "حداقل یک تغییر دقیق لازم است." }
                require(newType == null || ServiceType.fromWireOrNull(newType) != null)
                require(newType == null || s.isRental || ServiceType.fromWire(newType) !in ServiceType.RENTAL_TYPES) { "نوع اجاره‌ای در تنظیمات خودرو فعال نیست." }
                selected.forEach { old -> changes += ServiceChange(old, price(old.copy(km = km ?: old.km, hours = hours ?: old.hours,
                    workHours = hours ?: old.workHours, type = newType ?: old.type))) }
            }
            "bulk_delete_services" -> selected.forEach { changes += ServiceChange(it, null) }
            else -> error("عملیات سرویس مجاز نیست.")
        }
        require(changes.isNotEmpty()) { if (action == "bulk_register_services") "همهٔ نوبت‌های این بازه قبلاً موجودند یا غیرکاری‌اند؛ ثبت تکراری انجام نمی‌شود." else "سرویس مطابق این بازه و نوع پیدا نشد." }
        require(changes.size <= 1000) { "بیش از ۱۰۰۰ رکورد انتخاب شده؛ بازه را محدودتر کنید." }
        fun mileage(row: ServiceEntity?) = row?.takeIf { ServiceType.fromWire(it.type) !in ServiceType.FIXED_AMOUNT_TYPES }?.km ?: 0.0
        val delta = changes.sumOf { mileage(it.after) - mileage(it.before) }
        val before = s.vehicleCurrentKm.toFloat().toDouble()
        ServiceBatchPlan(action, op, start, end, hashRows(source), changes.toList(), skipped,
            if (template != null) presetHash() else null, rateRows.mapValues { it.value.toString() }, before,
            (before + delta).coerceAtLeast(0.0).toFloat().toDouble(), template, perDay, workdays)
    }

    suspend fun execute(plan: ServiceBatchPlan): Int = withContext(Dispatchers.IO + NonCancellable) {
        RecordMutations.mutate(db, settings, invalidateReceipt = true) {
            require(plan.changes.size in 1..1000 && plan.end <= Jalali.todayString())
            require(runCatching { UUID.fromString(plan.operationId) }.isSuccess)
            // A retry of the SAME approved plan is read-only when every expected after-row is present.
            // Deletion replay is also harmless; no replacement target is selected.
            val afterMatches = plan.changes.all { change -> db.services().byId((change.before ?: change.after)!!.id) == change.after }
            if (afterMatches) return@mutate plan.changes.size
            check(hashRows(db.services().rangeOnce(plan.start, plan.end)) == plan.sourceHash) { "سوابق بعد از پیش‌نمایش تغییر کرده‌اند؛ درخواست را دوباره بده. هیچ تغییری اعمال نشد." }
            check(plan.presetHash == null || plan.presetHash == presetHash()) { "الگوی ذخیره‌شده تغییر کرده است؛ پیش‌نمایش تازه لازم است." }
            for ((year, snapshot) in plan.rates) check(ratesFor(year).toString() == snapshot) { "نرخ‌ها تغییر کرده‌اند؛ پیش‌نمایش تازه لازم است." }
            val journal = ServiceBatchJournal.from(plan)
            if (journal != null) settings.stageQuickMileage(journal.encode()) { it.vehicleCurrentKm.toFloat().toDouble() == plan.mileageBefore }
            try {
                db.withTransaction {
                    check(hashRows(db.services().rangeOnce(plan.start, plan.end)) == plan.sourceHash) { "سوابق هم‌زمان تغییر کرده‌اند؛ دوباره بررسی کنید." }
                    plan.changes.forEach { change ->
                        val id = (change.before ?: change.after)!!.id
                        check(db.services().byId(id) == change.before) { "رکورد مبنا تغییر کرده است." }
                        when {
                            change.after == null -> db.services().deleteById(id)
                            change.before == null -> check(db.services().insertIfAbsent(change.after) != -1L)
                            else -> db.services().update(change.after)
                        }
                        check(db.services().byId(id) == change.after) { "بازخوانی رکورد انجام نشد." }
                    }
                }
            } finally {
                // This distinguishes a rolled-back transaction from a committed one. A crash
                // before this point is repaired through the same shared journal on next launch.
                if (journal != null) QuickMileageJournal.recover(db, settings)
            }
            plan.changes.size
        }
    }
    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun hashRows(rows: List<ServiceEntity>) = hash(rows.sortedBy { it.id }.joinToString("\n") { it.toString() })
    }
}

/** Version 2 of the existing private write-ahead journal; no Room schema/downgrade. */
internal data class ServiceBatchJournal(val rows: List<Triple<String, String?, String?>>, val beforeKm: Double, val afterKm: Double) {
    fun encode() = JSONObject().put("version", 2).put("kind", "SERVICE_BATCH").put("beforeKm", beforeKm).put("afterKm", afterKm)
        .put("rows", JSONArray().apply { rows.forEach { (id, before, after) -> put(JSONArray().put(id).put(before ?: JSONObject.NULL).put(after ?: JSONObject.NULL)) } }).toString()
    companion object {
        fun from(plan: ServiceBatchPlan): ServiceBatchJournal? = if (plan.mileageBefore == plan.mileageAfter) null else
            ServiceBatchJournal(plan.changes.map { Triple((it.before ?: it.after)!!.id, QuickMileageJournal.fingerprint(it.before), QuickMileageJournal.fingerprint(it.after)) }, plan.mileageBefore, plan.mileageAfter)
        suspend fun recover(encoded: String, db: AppDatabase, settings: SettingsStore): QuickMileageJournal.Recovery {
            val j = JSONObject(encoded); require(j.getInt("version") == 2 && j.getString("kind") == "SERVICE_BATCH")
            val a = j.getJSONArray("rows"); require(a.length() in 1..1000)
            val rows = (0 until a.length()).map { i -> val r = a.getJSONArray(i); Triple(r.getString(0), if (r.isNull(1)) null else r.getString(1), if (r.isNull(2)) null else r.getString(2)) }
            val actual = rows.map { QuickMileageJournal.fingerprint(db.services().byId(it.first)) }
            val before = j.getDouble("beforeKm"); val after = j.getDouble("afterKm"); require(before.isFinite() && after.isFinite() && before >= 0 && after >= 0)
            if (actual == rows.map { it.third }) {
                val applied = settings.finishQuickMileage({ it.vehicleCurrentKm.toFloat().toDouble() in setOf(before, after) }) { it.copy(vehicleCurrentKm = after) }
                if (applied) return QuickMileageJournal.Recovery.APPLIED
            } else if (actual == rows.map { it.second }) {
                settings.finishQuickMileage({ true }) { it }; return QuickMileageJournal.Recovery.NOT_COMMITTED
            }
            settings.finishQuickMileage({ true }, "ثبت گروهی/کیلومتر هم‌زمان تغییر کرده است؛ مقدار دستی حفظ شد. رکوردها و کیلومتر خودرو را بررسی کنید.") { it }
            return QuickMileageJournal.Recovery.CONFLICT
        }
    }
}
