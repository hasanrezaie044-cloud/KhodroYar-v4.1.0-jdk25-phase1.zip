package com.khodroyar.app.data.legacy

import com.khodroyar.app.core.finance.LoanCalculator
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.core.rates.TimeRates
import com.khodroyar.app.data.db.*
import kotlinx.serialization.json.*

/**
 * Reads the JSON produced by the React Native app — both the exported backup file
 * (`exportAllData`) and a raw dump of the AsyncStorage keys — and turns it into Room
 * rows.
 *
 * DATA SAFETY IS THE POINT OF THIS FILE. Every field is read defensively: missing,
 * null and wrong-typed values fall back to the same defaults the JS code used, so a
 * partially-corrupt or older backup still restores everything it legitimately holds
 * instead of failing wholesale. Records keep their original `id` and `timestamp`,
 * which makes restores idempotent.
 *
 * Legacy rate aliases from v2.2 (`night`, `holiday`, `fixedKm`, `request`,
 * `available`, `fixedHour`, `nightHour`) are honoured exactly as `normalizeRates`
 * did, so a years-old backup still resolves to the right numbers.
 *
 * The PIN hash is deliberately NOT imported, matching the JS behaviour.
 */
object LegacyBackup {

    const val BACKUP_FORMAT_VERSION = "3.4.3"

    data class Counts(
        val services: Int = 0, val fuels: Int = 0, val maintenances: Int = 0,
        val loans: Int = 0, val personalExpenses: Int = 0, val personalIncomes: Int = 0,
        val personnel: Int = 0, val holidays: Int = 0, val rateYears: Int = 0,
    ) {
        val total: Int get() = services + fuels + maintenances + loans +
            personalExpenses + personalIncomes + personnel + holidays
    }

    data class Preview(
        val valid: Boolean,
        val error: String? = null,
        val version: String? = null,
        val exportDate: String? = null,
        val versionWarning: String? = null,
        val counts: Counts = Counts(),
    )

    data class Payload(
        val services: List<ServiceEntity>,
        val fuels: List<FuelEntity>,
        val maintenances: List<MaintenanceEntity>,
        val loans: List<LoanEntity>,
        val personalExpenses: List<PersonalExpenseEntity>,
        val personalIncomes: List<PersonalIncomeEntity>,
        val personnel: List<PersonnelEntity>,
        val holidays: List<HolidayEntity>,
        val rates: List<RatesEntity>,
        val suggestions: List<SuggestionEntity>,
        val counts: Counts,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- defensive readers ----

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonObject.prim(k: String) = this[k]?.takeIf { it is JsonPrimitive && it !is JsonNull } as? JsonPrimitive

    private fun JsonObject.str(k: String, def: String = ""): String = prim(k)?.contentOrNull ?: def
    private fun JsonObject.dbl(k: String, def: Double = 0.0): Double {
        val p = prim(k) ?: return def
        return p.doubleOrNull ?: p.contentOrNull?.trim()?.replace(",", "")?.toDoubleOrNull() ?: def
    }
    private fun JsonObject.int(k: String, def: Int = 0): Int = dbl(k, def.toDouble()).toInt()
    private fun JsonObject.lng(k: String, def: Long = 0L): Long {
        val d = dbl(k, def.toDouble())
        return if (d.isFinite()) TimeRates.jsRound(d) else def
    }
    private fun JsonObject.boolOr(k: String, def: Boolean): Boolean =
        prim(k)?.booleanOrNull ?: prim(k)?.contentOrNull?.equals("true", true) ?: def

    /** Reads the first present key among [keys] — used for the v2.2 rate aliases. */
    private fun JsonObject.dblAny(vararg keys: String, def: Double): Double {
        for (k in keys) if (prim(k) != null) return dbl(k, def)
        return def
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString()
    private fun nowIso(): String = java.time.Instant.now().toString()

    /** Structural check mirroring the JS `validateImportData`. */
    private fun isStructurallyValid(root: JsonObject): Boolean {
        for (k in listOf("services", "fuels", "maintenances")) {
            val v = root[k]
            if (v != null && v !is JsonNull && v !is JsonArray) return false
        }
        return true
    }

    fun preview(jsonString: String): Preview {
        val root = try {
            json.parseToJsonElement(jsonString) as? JsonObject
                ?: return Preview(false, error = "فرمت JSON نامعتبر است")
        } catch (e: Exception) {
            return Preview(false, error = "فرمت JSON نامعتبر است")
        }
        if (!isStructurallyValid(root)) {
            return Preview(false, error = "ساختار فایل پشتیبان نامعتبر است")
        }
        val counts = Counts(
            services = root.arr("services")?.size ?: 0,
            fuels = root.arr("fuels")?.size ?: 0,
            maintenances = root.arr("maintenances")?.size ?: 0,
            loans = root.arr("loans")?.size ?: 0,
            personalExpenses = root.arr("personalExpenses")?.size ?: 0,
            personalIncomes = root.arr("personalIncomes")?.size ?: 0,
            personnel = root.arr("personnel")?.size ?: 0,
            holidays = root.arr("holidays")?.size ?: 0,
            rateYears = root.obj("ratesByYear")?.size ?: if (root.obj("rates") != null) 1 else 0,
        )
        val version = root.prim("version")?.contentOrNull
        val warning = if (version != null) {
            val backupMajor = version.substringBefore('.').toIntOrNull()
            val currentMajor = BACKUP_FORMAT_VERSION.substringBefore('.').toIntOrNull()
            if (backupMajor != null && currentMajor != null && backupMajor > currentMajor)
                "این فایل پشتیبان با نسخه‌ای جدیدتر از برنامه فعلی ساخته شده؛ ممکن است برخی اطلاعات به‌درستی بازیابی نشوند."
            else null
        } else {
            "این فایل پشتیبان نسخه مشخصی ندارد (احتمالاً مربوط به نسخه‌های قدیمی‌تر برنامه است) — بازیابی همچنان قابل انجام است."
        }
        return Preview(true, version = version,
            exportDate = root.prim("exportDate")?.contentOrNull,
            versionWarning = warning, counts = counts)
    }

    fun parse(jsonString: String): Payload? {
        val root = try {
            json.parseToJsonElement(jsonString) as? JsonObject ?: return null
        } catch (e: Exception) { return null }
        if (!isStructurallyValid(root)) return null

        val services = root.arr("services").orEmptyObjects().map { o ->
            ServiceEntity(
                id = o.str("id").ifEmpty { newId() },
                date = o.str("date"),
                type = ServiceType.fromWire(o.str("type")).wire,
                carType = o.str("carType", "soren"),
                km = o.dbl("km"),
                hours = o.dbl("hours"),
                workHours = o.dbl("workHours"),
                startTime = o.str("startTime"),
                endTime = o.str("endTime"),
                origin = o.str("origin"),
                destination = o.str("destination"),
                passengers = o.str("passengers"),
                requestNumber = o.str("requestNumber"),
                tollCount = o.int("tollCount"),
                missionFood = o.dbl("missionFood"),
                missionToll = o.dbl("missionToll"),
                missionFine = o.dbl("missionFine"),
                income = o.lng("income"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        val fuels = root.arr("fuels").orEmptyObjects().map { o ->
            FuelEntity(
                id = o.str("id").ifEmpty { newId() },
                date = o.str("date"),
                type = o.str("type", "gov"),
                liters = o.dbl("liters"),
                total = o.dbl("total"),
                carType = o.str("carType", "soren"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        val maintenances = root.arr("maintenances").orEmptyObjects().map { o ->
            MaintenanceEntity(
                id = o.str("id").ifEmpty { newId() },
                date = o.str("date"),
                type = o.str("type", "other"),
                typeText = o.str("typeText"),
                cost = o.dbl("cost"),
                km = o.dbl("km"),
                carType = o.str("carType", "soren"),
                description = o.str("description"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        val today = Jalali.todayString()
        val loans = root.arr("loans").orEmptyObjects().map { o ->
            val paidRaw = (o["paidInstallments"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
            val count = maxOf(1, o.int("installmentsCount", 1))
            // Same auto-mark-overdue pass the JS load path performed.
            val paid = LoanCalculator.autoMarkOverdue(
                o.str("date").ifEmpty { null }, count, paidRaw, today,
            )
            LoanEntity(
                id = o.str("id").ifEmpty { newId() },
                title = o.str("title"),
                totalAmount = o.dbl("totalAmount"),
                interestPercent = o.dbl("interestPercent"),
                date = o.str("date"),
                installmentsCount = count,
                paidInstallments = paid.joinToString(","),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        val expenses = root.arr("personalExpenses").orEmptyObjects().map { o ->
            PersonalExpenseEntity(
                id = o.str("id").ifEmpty { newId() },
                date = o.str("date"),
                category = o.str("category", "other"),
                title = o.str("title"),
                amount = o.dbl("amount"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        val incomes = root.arr("personalIncomes").orEmptyObjects().map { o ->
            PersonalIncomeEntity(
                id = o.str("id").ifEmpty { newId() },
                date = o.str("date"),
                category = o.str("category", "other"),
                title = o.str("title"),
                amount = o.dbl("amount"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        val personnel = root.arr("personnel").orEmptyObjects().map { o ->
            PersonnelEntity(
                id = o.str("id").ifEmpty { newId() },
                name = o.str("name"),
                personnelCode = o.str("personnelCode"),
                costCenter = o.str("costCenter").filter { it.isDigit() },
                phone = o.str("phone"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        // Manual holidays are de-duplicated by date, as `addHoliday` did.
        val seenDates = HashSet<String>()
        val holidays = root.arr("holidays").orEmptyObjects().mapNotNull { o ->
            val date = o.str("date")
            if (date.isEmpty() || !seenDates.add(date)) return@mapNotNull null
            HolidayEntity(
                id = o.str("id").ifEmpty { newId() },
                date = date,
                title = o.str("title"),
                timestamp = o.str("timestamp").ifEmpty { nowIso() },
            )
        }

        // Rates: prefer the full per-year map; otherwise migrate the single legacy
        // global rate object onto the active year, exactly like the JS import did.
        val rateRows = ArrayList<RatesEntity>()
        val byYear = root.obj("ratesByYear")
        if (byYear != null && byYear.isNotEmpty()) {
            for ((yearKey, value) in byYear) {
                val year = yearKey.toIntOrNull() ?: continue
                val o = value as? JsonObject ?: continue
                rateRows.add(normalizeRates(o).toEntity(year))
            }
        } else {
            root.obj("rates")?.let { rateRows.add(normalizeRates(it).toEntity(Jalali.currentYear())) }
        }

        val now = System.currentTimeMillis()
        val suggestions = ArrayList<SuggestionEntity>()
        root.obj("suggestions")?.let { s ->
            // Legacy files carry origin / destination / passengers; newer ones may add
            // more quick-entry fields. Any key holding an array of strings is accepted.
            for (field in s.keys) {
                val list = s.arr(field) ?: continue
                // Preserve order: the JS array is newest-first.
                list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter { it.isNotBlank() }
                    .take(20)
                    .forEachIndexed { i, v ->
                        suggestions.add(SuggestionEntity(field, v.trim(), now - i))
                    }
            }
        }

        return Payload(
            services, fuels, maintenances, loans, expenses, incomes, personnel,
            holidays, rateRows, suggestions,
            Counts(services.size, fuels.size, maintenances.size, loans.size,
                expenses.size, incomes.size, personnel.size, holidays.size, rateRows.size),
        )
    }

    private fun JsonArray?.orEmptyObjects(): List<JsonObject> =
        this?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** Field-for-field equivalent of `normalizeRates` in AppContext.tsx, aliases included. */
    fun normalizeRates(o: JsonObject): Rates {
        val d = Rates.DEFAULT
        val bonusTypes = (o["timeBonusServiceTypes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.mapNotNull { ServiceType.fromWireOrNull(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: d.timeBonusServiceTypes
        return Rates(
            nightKm = o.dblAny("nightKm", "night", def = d.nightKm),
            holidayKm = o.dblAny("holidayKm", "holiday", def = d.holidayKm),
            fixedRequestKm = o.dblAny("fixedRequestKm", "fixedKm", "request", def = d.fixedRequestKm),
            hour = o.dblAny("hour", "available", "fixedHour", "nightHour", def = d.hour),
            fuelPriceGov = o.dbl("fuelPriceGov", d.fuelPriceGov),
            fuelPriceSemi = o.dbl("fuelPriceSemi", d.fuelPriceSemi),
            fuelPriceFree = o.dbl("fuelPriceFree", d.fuelPriceFree),
            toll = o.dbl("toll", d.toll),
            defaultCarType = com.khodroyar.app.core.rates.CarType.fromWire(o.str("defaultCarType", d.defaultCarType.wire)),
            oilChangeKmInterval = o.int("oilChangeKmInterval", d.oilChangeKmInterval),
            timingBeltKmInterval = o.int("timingBeltKmInterval", d.timingBeltKmInterval),
            nightPercent = o.dbl("nightPercent", d.nightPercent),
            saharPercent = o.dbl("saharPercent", d.saharPercent),
            nightStartTime = o.str("nightStartTime").ifBlank { d.nightStartTime },
            saharStartTime = o.str("saharStartTime").ifBlank { d.saharStartTime },
            saharEndTime = o.str("saharEndTime").ifBlank { d.saharEndTime },
            timeBonusServiceTypes = bonusTypes,
        )
    }
}

fun Rates.toEntity(year: Int) = RatesEntity(
    year = year,
    nightKm = nightKm, holidayKm = holidayKm, fixedRequestKm = fixedRequestKm, hour = hour,
    fuelPriceGov = fuelPriceGov, fuelPriceSemi = fuelPriceSemi, fuelPriceFree = fuelPriceFree,
    toll = toll, defaultCarType = defaultCarType.wire,
    oilChangeKmInterval = oilChangeKmInterval, timingBeltKmInterval = timingBeltKmInterval,
    nightPercent = nightPercent, saharPercent = saharPercent,
    nightStartTime = nightStartTime, saharStartTime = saharStartTime, saharEndTime = saharEndTime,
    timeBonusServiceTypes = timeBonusServiceTypes.joinToString(",") { it.wire },
)

fun RatesEntity.toRates() = Rates(
    nightKm = nightKm, holidayKm = holidayKm, fixedRequestKm = fixedRequestKm, hour = hour,
    fuelPriceGov = fuelPriceGov, fuelPriceSemi = fuelPriceSemi, fuelPriceFree = fuelPriceFree,
    toll = toll, defaultCarType = com.khodroyar.app.core.rates.CarType.fromWire(defaultCarType),
    oilChangeKmInterval = oilChangeKmInterval, timingBeltKmInterval = timingBeltKmInterval,
    nightPercent = nightPercent, saharPercent = saharPercent,
    nightStartTime = nightStartTime, saharStartTime = saharStartTime, saharEndTime = saharEndTime,
    timeBonusServiceTypes = timeBonusServiceTypes.split(",")
        .mapNotNull { ServiceType.fromWireOrNull(it.trim()) }
        .ifEmpty { Rates.DEFAULT.timeBonusServiceTypes },
)
