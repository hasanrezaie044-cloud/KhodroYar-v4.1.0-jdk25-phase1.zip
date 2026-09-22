package com.khodroyar.app.data.repo

import android.content.Context
import com.khodroyar.app.core.finance.LoanCalculator
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.IncomeCalculator
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.legacy.LegacyBackup
import com.khodroyar.app.data.legacy.toEntity
import com.khodroyar.app.data.legacy.toRates
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Single write/read surface over Room. All suspend functions hop to the IO
 * dispatcher; all reads are Flows so the UI observes the database rather than a
 * duplicated in-memory copy (the main structural fix versus the React Context that
 * held every record in component state and re-serialised the whole array to
 * AsyncStorage on every mutation).
 */
class AppRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val io = Dispatchers.IO
    private val settingsStore = SettingsStore.get(context)

    // ----------------------------------------------------------- app settings

    /** Appearance, privacy mode, income goal and reminder switches. */
    val settings: Flow<AppSettings> = settingsStore.state
    val settingsNow: AppSettings get() = settingsStore.current

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)
    fun markBackupDone() = settingsStore.markBackupDone()

    // ------------------------------------------------------------------ rates

    val ratesByYear: Flow<RatesByYear> = db.rates().observeAll().map { rows ->
        RatesByYear(rows.associate { it.year to it.toRates() })
    }

    /** Rates for the current Jalali year, inheriting from the nearest earlier year. */
    val activeRates: Flow<Rates> = ratesByYear.map { it.forYear(Jalali.currentYear()) }

    suspend fun ratesFor(year: Int): Rates = withContext(io) {
        RatesByYear(db.rates().allOnce().associate { it.year to it.toRates() }).forYear(year)
    }

    /** Only ever writes the active year, leaving historical pricing untouched. */
    suspend fun updateActiveRates(transform: (Rates) -> Rates) = withContext(io) {
        val year = Jalali.currentYear()
        val current = ratesFor(year)
        db.rates().upsert(transform(current).toEntity(year))
    }

    /**
     * Writes the rate set of ONE Jalali year. Nothing else is touched, so editing
     * 1405 can never change how a 1404 service was priced.
     */
    suspend fun saveRatesForYear(year: Int, rates: Rates) = withContext(io) {
        db.rates().upsert(rates.toEntity(year))
    }

    /** Years that have their own explicit rate row. */
    suspend fun rateYears(): List<Int> = withContext(io) { db.rates().allOnce().map { it.year }.sorted() }

    /** How many services the "Save + recalculate" action would re-price. */
    suspend fun countServicesInYear(year: Int): Int = withContext(io) {
        db.services().byDatePrefixOnce("$year/").size
    }

    // --------------------------------------------------------------- services

    fun services(): Flow<List<ServiceEntity>> = db.services().observeAll()
    fun services(datePrefix: String): Flow<List<ServiceEntity>> =
        db.services().observeByDatePrefix(datePrefix)
    fun serviceTotals(datePrefix: String): Flow<ServiceTotals> =
        db.services().observeTotals(datePrefix)
    fun serviceTotalsByType(datePrefix: String): Flow<List<ServiceTypeTotals>> =
        db.services().observeTotalsByType(datePrefix)
    fun dailyTotals(datePrefix: String): Flow<List<DailyTotals>> =
        db.services().observeDailyTotals(datePrefix)

    /**
     * Inserts a service, computing income with the rate set for the service's OWN
     * Jalali year so back-dated entries are priced historically correctly.
     */
    suspend fun addService(
        date: String, type: ServiceType, carType: String,
        km: Double, hours: Double, workHours: Double,
        startTime: String, endTime: String,
        origin: String, destination: String, passengers: String,
        requestNumber: String, tollCount: Int,
        missionFood: Double, missionToll: Double, missionFine: Double,
    ): String = withContext(io) {
        val year = Jalali.parse(date)?.year ?: Jalali.currentYear()
        val rates = ratesFor(year)
        val income = IncomeCalculator.calculate(
            type, km, hours, tollCount, missionFood, missionToll, missionFine,
            rates, startTime.ifEmpty { null }, endTime.ifEmpty { null },
        )
        val id = java.util.UUID.randomUUID().toString()
        db.services().insert(
            ServiceEntity(
                id = id, date = date, type = type.wire, carType = carType,
                km = km, hours = hours, workHours = workHours,
                startTime = startTime, endTime = endTime,
                origin = origin, destination = destination, passengers = passengers,
                requestNumber = requestNumber, tollCount = tollCount,
                missionFood = missionFood, missionToll = missionToll, missionFine = missionFine,
                income = income, timestamp = java.time.Instant.now().toString(),
            )
        )
        rememberSuggestions(
            "origin" to origin, "destination" to destination, "passengers" to passengers,
            "km" to (if (km > 0) money0(km) else ""),
            "startTime" to startTime, "endTime" to endTime,
            "tollCount" to (if (tollCount > 0) tollCount.toString() else ""),
        )
        id
    }

    suspend fun updateService(entity: ServiceEntity, recomputeIncome: Boolean = true) = withContext(io) {
        val row = if (!recomputeIncome) entity else {
            val year = Jalali.parse(entity.date)?.year ?: Jalali.currentYear()
            val rates = ratesFor(year)
            entity.copy(
                income = IncomeCalculator.calculate(
                    ServiceType.fromWire(entity.type), entity.km, entity.hours, entity.tollCount,
                    entity.missionFood, entity.missionToll, entity.missionFine, rates,
                    entity.startTime.ifEmpty { null }, entity.endTime.ifEmpty { null },
                )
            )
        }
        db.services().update(row)
    }

    /** Services of exactly one day — the calendar's selectedDate binding. */
    fun servicesForDate(date: String): Flow<List<ServiceEntity>> = db.services().observeByDate(date)

    suspend fun servicesForDateOnce(date: String): List<ServiceEntity> =
        withContext(io) { db.services().rangeOnce(date, date) }

    suspend fun servicesInRangeOnce(start: String, end: String): List<ServiceEntity> =
        withContext(io) { db.services().rangeOnce(start, end) }

    suspend fun servicesByPrefixOnce(prefix: String): List<ServiceEntity> =
        withContext(io) { db.services().byDatePrefixOnce(prefix) }

    suspend fun fuelsByPrefixOnce(prefix: String): List<FuelEntity> =
        withContext(io) { db.fuels().byDatePrefixOnce(prefix) }

    suspend fun maintenancesByPrefixOnce(prefix: String): List<MaintenanceEntity> =
        withContext(io) { db.maintenances().byDatePrefixOnce(prefix) }

    suspend fun loansOnce(): List<LoanEntity> = withContext(io) { db.loans().allOnce() }

    suspend fun deleteService(id: String) = withContext(io) { db.services().deleteById(id) }
    suspend fun service(id: String): ServiceEntity? = withContext(io) { db.services().byId(id) }

    /**
     * Re-prices services with current rates. Scoped to the ACTIVE year only, so
     * historical pricing for previous years stays frozen — same constraint the JS
     * `recalculateAllServices` enforced.
     */
    suspend fun recalculateActiveYear(): Int = recalculateYear(Jalali.currentYear())

    /** Re-prices the services of ONE year with that year's rates. */
    suspend fun recalculateYear(year: Int): Int = withContext(io) {
        val rates = ratesFor(year)
        val rows = db.services().byDatePrefixOnce("$year/")
        val updated = rows.map { s ->
            s.copy(
                income = IncomeCalculator.calculate(
                    ServiceType.fromWire(s.type), s.km, s.hours, s.tollCount,
                    s.missionFood, s.missionToll, s.missionFine, rates,
                    s.startTime.ifEmpty { null }, s.endTime.ifEmpty { null },
                )
            )
        }
        db.services().insertAll(updated)
        updated.size
    }

    // ------------------------------------------------------- fuels / upkeep

    fun fuels(): Flow<List<FuelEntity>> = db.fuels().observeAll()
    fun fuels(prefix: String): Flow<List<FuelEntity>> = db.fuels().observeByDatePrefix(prefix)
    fun fuelCost(prefix: String): Flow<Double> = db.fuels().observeTotalCost(prefix)
    fun fuelLiters(prefix: String): Flow<Double> = db.fuels().observeTotalLiters(prefix)

    suspend fun addFuel(date: String, type: String, liters: Double, total: Double, carType: String) =
        withContext(io) {
            db.fuels().insert(FuelEntity(
                java.util.UUID.randomUUID().toString(), date, type, liters, total, carType,
                java.time.Instant.now().toString(),
            ))
        }

    suspend fun deleteFuel(id: String) = withContext(io) { db.fuels().deleteById(id) }

    fun maintenances(): Flow<List<MaintenanceEntity>> = db.maintenances().observeAll()
    fun maintenances(prefix: String): Flow<List<MaintenanceEntity>> =
        db.maintenances().observeByDatePrefix(prefix)
    fun maintenanceCost(prefix: String): Flow<Double> = db.maintenances().observeTotalCost(prefix)

    suspend fun addMaintenance(
        date: String, type: String, typeText: String,
        cost: Double, km: Double, carType: String, description: String,
    ) = withContext(io) {
        db.maintenances().insert(MaintenanceEntity(
            java.util.UUID.randomUUID().toString(), date, type, typeText,
            cost, km, carType, description, java.time.Instant.now().toString(),
        ))
    }

    suspend fun deleteMaintenance(id: String) = withContext(io) { db.maintenances().deleteById(id) }
    suspend fun latestMaintenance(type: String) = withContext(io) { db.maintenances().latestByType(type) }
    suspend fun highestKm(): Double = withContext(io) { db.services().maxKm() ?: 0.0 }

    // ------------------------------------------------------------------ loans

    fun loans(): Flow<List<LoanEntity>> = db.loans().observeAll()

    suspend fun addLoan(
        title: String, totalAmount: Double, interestPercent: Double,
        date: String, installmentsCount: Int,
    ) = withContext(io) {
        db.loans().insert(LoanEntity(
            java.util.UUID.randomUUID().toString(), title, totalAmount, interestPercent,
            date, maxOf(1, installmentsCount), "", java.time.Instant.now().toString(),
        ))
    }

    suspend fun toggleInstallment(loanId: String, index: Int) = withContext(io) {
        val loan = db.loans().byId(loanId) ?: return@withContext
        val paid = parseInstallments(loan.paidInstallments).toMutableSet()
        if (!paid.add(index)) paid.remove(index)
        val updatedPaid = paid.sorted()
        val schedule = LoanCalculator.schedule(
            loan.date, loan.totalAmount, loan.interestPercent, loan.installmentsCount, updatedPaid
        )
        if (schedule.isNotEmpty() && schedule.all { it.paid }) {
            db.loans().deleteById(loanId)
        } else {
            db.loans().update(loan.copy(paidInstallments = updatedPaid.joinToString(",")))
        }
    }

    suspend fun updateLoan(
        id: String, title: String, totalAmount: Double, interestPercent: Double,
        date: String, installmentsCount: Int,
    ) = withContext(io) {
        val loan = db.loans().byId(id) ?: return@withContext
        db.loans().update(loan.copy(
            title = title, totalAmount = totalAmount, interestPercent = interestPercent,
            date = date, installmentsCount = maxOf(1, installmentsCount),
        ))
    }

    suspend fun deleteLoan(id: String) = withContext(io) { db.loans().deleteById(id) }

    /**
     * Legacy behaviour: installments whose due date has passed are marked paid on
     * load. Preserved, but surfaced as an explicit result count so the user is told
     * what changed instead of it happening silently.
     */
    suspend fun cleanupCompletedLoans(): Int = withContext(io) {
        var removed = 0
        for (loan in db.loans().allOnce()) {
            val schedule = LoanCalculator.schedule(
                loan.date, loan.totalAmount, loan.interestPercent,
                loan.installmentsCount, parseInstallments(loan.paidInstallments)
            )
            if (schedule.isNotEmpty() && schedule.all { it.paid }) {
                db.loans().deleteById(loan.id)
                removed++
            }
        }
        removed
    }

    suspend fun markOverdueInstallments(): Int = withContext(io) {
        var changed = 0
        for (loan in db.loans().allOnce()) {
            val before = parseInstallments(loan.paidInstallments).sorted()
            val after = LoanCalculator.autoMarkOverdue(loan.date, loan.installmentsCount, before)
            if (after != before) {
                val schedule = LoanCalculator.schedule(
                    loan.date, loan.totalAmount, loan.interestPercent, loan.installmentsCount, after
                )
                if (schedule.isNotEmpty() && schedule.all { it.paid }) {
                    db.loans().deleteById(loan.id)
                } else {
                    db.loans().update(loan.copy(paidInstallments = after.joinToString(",")))
                }
                changed += after.size - before.size
            }
        }
        changed
    }

    fun loanSummary(loan: LoanEntity): LoanCalculator.Summary = LoanCalculator.summarize(
        loan.date, loan.totalAmount, loan.interestPercent,
        loan.installmentsCount, parseInstallments(loan.paidInstallments),
    )

    private fun parseInstallments(raw: String): List<Int> =
        raw.split(',').mapNotNull { it.trim().toIntOrNull() }

    // ------------------------------------------------- personal money & people

    fun personalExpenses(prefix: String): Flow<List<PersonalExpenseEntity>> =
        db.personalExpenses().observeByDatePrefix(prefix)
    fun personalExpenseTotal(prefix: String): Flow<Double> = db.personalExpenses().observeTotal(prefix)
    fun personalIncomes(prefix: String): Flow<List<PersonalIncomeEntity>> =
        db.personalIncomes().observeByDatePrefix(prefix)
    fun personalIncomeTotal(prefix: String): Flow<Double> = db.personalIncomes().observeTotal(prefix)

    suspend fun addPersonalExpense(date: String, category: String, title: String, amount: Double) =
        withContext(io) {
            db.personalExpenses().insert(PersonalExpenseEntity(
                java.util.UUID.randomUUID().toString(), date, category, title, amount,
                java.time.Instant.now().toString(),
            ))
        }

    suspend fun deletePersonalExpense(id: String) = withContext(io) { db.personalExpenses().deleteById(id) }

    suspend fun addPersonalIncome(date: String, category: String, title: String, amount: Double) =
        withContext(io) {
            db.personalIncomes().insert(PersonalIncomeEntity(
                java.util.UUID.randomUUID().toString(), date, category, title, amount,
                java.time.Instant.now().toString(),
            ))
        }

    suspend fun deletePersonalIncome(id: String) = withContext(io) { db.personalIncomes().deleteById(id) }

    fun personnel(): Flow<List<PersonnelEntity>> = db.personnel().observeAll()

    suspend fun addPersonnel(name: String, code: String, costCenter: String, phone: String) =
        withContext(io) {
            db.personnel().insert(PersonnelEntity(
                java.util.UUID.randomUUID().toString(), name, code,
                costCenter.filter { it.isDigit() }, phone, java.time.Instant.now().toString(),
            ))
        }

    /**
     * Updates an existing personnel row in place. Used by the personnel details screen,
     * which edits the record the user already entered — it must never insert a second
     * one, so this goes through @Update on the primary key rather than a fresh insert.
     */
    suspend fun updatePersonnel(
        id: String, name: String, code: String, costCenter: String, phone: String,
    ) = withContext(io) {
        val existing = db.personnel().allOnce().firstOrNull { it.id == id } ?: return@withContext
        db.personnel().update(
            existing.copy(
                name = name,
                personnelCode = code,
                costCenter = costCenter.filter { it.isDigit() },
                phone = phone,
            )
        )
    }

    suspend fun deletePersonnel(id: String) = withContext(io) { db.personnel().deleteById(id) }

    // --------------------------------------------------------------- holidays

    val manualHolidays: Flow<List<HolidayEntity>> = db.holidays().observeAll()

    /**
     * A day is a holiday when it is either in the user's manual list OR in the
     * official table for that year. Neither source overrides the other — same rule
     * as the JS `isHoliday`.
     */
    val holidayResolver: Flow<(String) -> Boolean> = db.holidays().observeDates().map { manual ->
        val dates: Set<String> = HashSet(manual)
        return@map { date: String -> dates.contains(date) || IranHolidays.isOfficialHoliday(date) }
    }

    suspend fun addHoliday(date: String, title: String) = withContext(io) {
        db.holidays().insert(HolidayEntity(
            java.util.UUID.randomUUID().toString(), date, title,
            java.time.Instant.now().toString(),
        ))
    }

    suspend fun removeHoliday(id: String) = withContext(io) { db.holidays().deleteById(id) }

    suspend fun holidayTitle(date: String): String? = withContext(io) {
        db.holidays().allOnce().firstOrNull { it.date == date }
            ?.let { it.title.ifBlank { "تعطیل (دستی)" } }
            ?: IranHolidays.titleFor(date)
    }

    // ------------------------------------------------------------ suggestions

    fun suggestions(field: String): Flow<List<String>> = db.suggestions().observeField(field)

    private suspend fun rememberSuggestions(vararg entries: Pair<String, String>) {
        val now = System.currentTimeMillis()
        val items = entries
            .filter { it.second.trim().isNotEmpty() }
            .map { SuggestionEntity(it.first, it.second.trim(), now) }
        if (items.isEmpty()) return
        db.suggestions().upsertAll(items)
        items.map { it.field }.distinct().forEach { db.suggestions().trim(it) }
    }

    /** Integer-ish rendering used when a numeric entry is stored as a suggestion. */
    private fun money0(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    // ------------------------------------------------------- backup & restore

    /**
     * Exports the same JSON shape the React Native app produced, so old and new
     * builds can read each other's backups. The PIN is deliberately excluded.
     */
    suspend fun exportBackupJson(): String = withContext(io) {
        val services = db.services().allOnce()
        val fuels = db.fuels().allOnce()
        val maint = db.maintenances().allOnce()
        val loans = db.loans().allOnce()
        val expenses = db.personalExpenses().allOnce()
        val incomes = db.personalIncomes().allOnce()
        val people = db.personnel().allOnce()
        val holidays = db.holidays().allOnce()
        val rateRows = db.rates().allOnce()
        val activeYear = Jalali.currentYear()
        val suggestionFields = (listOf("origin", "destination", "passengers") + db.suggestions().fields())
            .distinct()
            .associateWith { db.suggestions().fieldOnce(it) }

        buildString {
            append("{\n")
            append("\"services\": ["); append(services.joinToString(",") { it.toJson() }); append("],\n")
            append("\"fuels\": ["); append(fuels.joinToString(",") { it.toJson() }); append("],\n")
            append("\"maintenances\": ["); append(maint.joinToString(",") { it.toJson() }); append("],\n")
            append("\"loans\": ["); append(loans.joinToString(",") { it.toJson() }); append("],\n")
            append("\"personalExpenses\": ["); append(expenses.joinToString(",") { it.toJson() }); append("],\n")
            append("\"personalIncomes\": ["); append(incomes.joinToString(",") { it.toJson() }); append("],\n")
            append("\"personnel\": ["); append(people.joinToString(",") { it.toJson() }); append("],\n")
            append("\"holidays\": ["); append(holidays.joinToString(",") { it.toJson() }); append("],\n")
            append("\"rates\": ")
            append((rateRows.firstOrNull { it.year == activeYear } ?: rateRows.lastOrNull())?.toJson() ?: "{}")
            append(",\n")
            append("\"ratesByYear\": {")
            append(rateRows.joinToString(",") { "\"${it.year}\": ${it.toJson()}" })
            append("},\n")
            // Autocomplete history, in the legacy object-of-arrays shape.
            append("\"suggestions\": {")
            append(suggestionFields.entries.joinToString(",") { (field, values) ->
                "\"$field\": [" + values.joinToString(",") { "\"" + esc(it) + "\"" } + "]"
            })
            append("},\n")
            // Appearance / goals / reminder preferences. Never the PIN.
            val settingsJson = settingsStore.toBackupJson()
            append("\"settings\": "); append(settingsJson); append(",\n")
            append("\"appearance\": "); append(settingsJson); append(",\n")
            append("\"goals\": "); append(settingsJson); append(",\n")
            append("\"reminderPrefs\": "); append(settingsJson); append(",\n")
            append("\"exportDate\": \"${java.time.Instant.now()}\",\n")
            append("\"version\": \"${LegacyBackup.BACKUP_FORMAT_VERSION}\",\n")
            append("\"backupScope\": \"full-app-data\",\n")
            append("\"backupNote\": \"پشتیبان کامل داده‌های ثبت‌شده برنامه؛ PIN برای امنیت وارد نمی‌شود.\"\n")
            append("}")
        }
    }

    fun previewBackup(json: String) = LegacyBackup.preview(json)

    /**
     * Replaces all data with the backup contents, in one transaction: either the
     * whole restore lands or nothing changes, so a bad file can never leave a
     * half-restored database.
     */
    suspend fun restoreBackup(json: String): Result<LegacyBackup.Counts> = withContext(io) {
        val payload = LegacyBackup.parse(json)
            ?: return@withContext Result.failure(IllegalArgumentException("ساختار فایل نامعتبر است"))
        try {
            db.runInTransaction {
                // Room's runInTransaction needs blocking calls; DAO suspend functions
                // are invoked through runBlocking on the already-IO thread.
                kotlinx.coroutines.runBlocking {
                    db.services().clear(); db.fuels().clear(); db.maintenances().clear()
                    db.loans().clear(); db.personalExpenses().clear(); db.personalIncomes().clear()
                    db.personnel().clear(); db.holidays().clear(); db.suggestions().clear()
                    if (payload.rates.isNotEmpty()) db.rates().clear()

                    db.services().insertAll(payload.services)
                    db.fuels().insertAll(payload.fuels)
                    db.maintenances().insertAll(payload.maintenances)
                    db.loans().insertAll(payload.loans)
                    db.personalExpenses().insertAll(payload.personalExpenses)
                    db.personalIncomes().insertAll(payload.personalIncomes)
                    db.personnel().insertAll(payload.personnel)
                    db.holidays().insertAll(payload.holidays)
                    db.suggestions().upsertAll(payload.suggestions)
                    if (payload.rates.isNotEmpty()) db.rates().upsertAll(payload.rates)
                }
            }
            applySettingsFrom(json)
            Result.success(payload.counts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Restores appearance / goals / reminder preferences from a backup file.
     * Missing keys keep their current value, and anything unknown is ignored, so an
     * older backup can never wipe a newer setting.
     */
    private fun applySettingsFrom(rawJson: String) {
        val root = try {
            Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(rawJson) as? JsonObject ?: return
        } catch (e: Exception) {
            return
        }
        val block = listOf("settings", "appearance", "goals", "reminderPrefs")
            .firstNotNullOfOrNull { root[it] as? JsonObject } ?: return

        fun prim(k: String) = block[k] as? JsonPrimitive
        fun str(k: String) = prim(k)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun bool(k: String) = prim(k)?.booleanOrNull
        fun dbl(k: String) = prim(k)?.doubleOrNull
        fun int(k: String) = dbl(k)?.toInt()

        settingsStore.applyBackup(
            themeMode = str("themeMode"),
            language = str("language"),
            oilCurrentKm = dbl("oilCurrentKm"),
            oilNextKm = dbl("oilNextKm"),
            privacyMode = bool("privacyMode"),
            monthlyIncomeGoal = dbl("monthlyIncomeGoal"),
            remindDailySummary = bool("remindDailySummary"),
            remindWeeklySummary = bool("remindWeeklySummary"),
            remindMonthlySummary = bool("remindMonthlySummary"),
            remindInstallments = bool("remindInstallments"),
            remindOilChange = bool("remindOilChange"),
            remindBodyInsurance = bool("remindBodyInsurance"),
            remindVehicleInsurance = bool("remindVehicleInsurance"),
            remindInspection = bool("remindInspection"),
            remindBackup = bool("remindBackup"),
            bodyInsuranceDate = str("bodyInsuranceDate"),
            vehicleInsuranceDate = str("vehicleInsuranceDate"),
            inspectionDate = str("inspectionDate"),
            backupIntervalDays = int("backupIntervalDays"),
        )
    }

    suspend fun isEmpty(): Boolean = withContext(io) {
        db.services().allOnce().isEmpty() && db.fuels().allOnce().isEmpty() &&
            db.maintenances().allOnce().isEmpty() && db.loans().allOnce().isEmpty()
    }
}

// --- JSON emitters kept next to the repository so the backup shape stays in one place ---

private fun esc(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.toString()
}

private fun num(d: Double): String =
    if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

internal fun ServiceEntity.toJson() = """{"id":"${esc(id)}","date":"${esc(date)}","type":"${esc(type)}","carType":"${esc(carType)}","km":${num(km)},"hours":${num(hours)},"workHours":${num(workHours)},"startTime":"${esc(startTime)}","endTime":"${esc(endTime)}","origin":"${esc(origin)}","destination":"${esc(destination)}","passengers":"${esc(passengers)}","requestNumber":"${esc(requestNumber)}","tollCount":$tollCount,"missionFood":${num(missionFood)},"missionToll":${num(missionToll)},"missionFine":${num(missionFine)},"income":$income,"timestamp":"${esc(timestamp)}"}"""

internal fun FuelEntity.toJson() = """{"id":"${esc(id)}","date":"${esc(date)}","type":"${esc(type)}","liters":${num(liters)},"total":${num(total)},"carType":"${esc(carType)}","timestamp":"${esc(timestamp)}"}"""

internal fun MaintenanceEntity.toJson() = """{"id":"${esc(id)}","date":"${esc(date)}","type":"${esc(type)}","typeText":"${esc(typeText)}","cost":${num(cost)},"km":${num(km)},"carType":"${esc(carType)}","description":"${esc(description)}","timestamp":"${esc(timestamp)}"}"""

internal fun LoanEntity.toJson(): String {
    val paid = paidInstallments.split(',').mapNotNull { it.trim().toIntOrNull() }
    return """{"id":"${esc(id)}","title":"${esc(title)}","totalAmount":${num(totalAmount)},"interestPercent":${num(interestPercent)},"date":"${esc(date)}","installmentsCount":$installmentsCount,"paidInstallments":[${paid.joinToString(",")}],"timestamp":"${esc(timestamp)}"}"""
}

internal fun PersonalExpenseEntity.toJson() = """{"id":"${esc(id)}","date":"${esc(date)}","category":"${esc(category)}","title":"${esc(title)}","amount":${num(amount)},"timestamp":"${esc(timestamp)}"}"""

internal fun PersonalIncomeEntity.toJson() = """{"id":"${esc(id)}","date":"${esc(date)}","category":"${esc(category)}","title":"${esc(title)}","amount":${num(amount)},"timestamp":"${esc(timestamp)}"}"""

internal fun PersonnelEntity.toJson() = """{"id":"${esc(id)}","name":"${esc(name)}","personnelCode":"${esc(personnelCode)}","costCenter":"${esc(costCenter)}","phone":"${esc(phone)}","timestamp":"${esc(timestamp)}"}"""

internal fun HolidayEntity.toJson() = """{"id":"${esc(id)}","date":"${esc(date)}","title":"${esc(title)}","timestamp":"${esc(timestamp)}"}"""

internal fun RatesEntity.toJson() = """{"nightKm":${num(nightKm)},"holidayKm":${num(holidayKm)},"fixedRequestKm":${num(fixedRequestKm)},"hour":${num(hour)},"fuelPriceGov":${num(fuelPriceGov)},"fuelPriceSemi":${num(fuelPriceSemi)},"fuelPriceFree":${num(fuelPriceFree)},"toll":${num(toll)},"defaultCarType":"${esc(defaultCarType)}","oilChangeKmInterval":$oilChangeKmInterval,"timingBeltKmInterval":$timingBeltKmInterval,"nightPercent":${num(nightPercent)},"saharPercent":${num(saharPercent)},"nightStartTime":"${esc(nightStartTime)}","saharStartTime":"${esc(saharStartTime)}","saharEndTime":"${esc(saharEndTime)}","timeBonusServiceTypes":[${timeBonusServiceTypes.split(",").filter{it.isNotBlank()}.joinToString(","){"\"${esc(it.trim())}\""}}]}"""
