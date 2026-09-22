package com.khodroyar.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAOs expose Flows so Compose recomposes off the database rather than an in-memory
 * mirror of it. Aggregations are pushed into SQL so large datasets never load into
 * memory just to be summed — one of the main wins over the JS version, which held
 * every record in a React state array and recomputed totals on the JS thread.
 */

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services ORDER BY date DESC, startTime DESC")
    fun observeAll(): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE date LIKE :prefix || '%' ORDER BY date DESC, startTime DESC")
    fun observeByDatePrefix(prefix: String): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE date = :date ORDER BY startTime")
    fun observeByDate(date: String): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE date BETWEEN :start AND :end ORDER BY date, startTime")
    suspend fun rangeOnce(start: String, end: String): List<ServiceEntity>

    @Query("SELECT * FROM services WHERE date LIKE :prefix || '%' ORDER BY date, startTime")
    suspend fun byDatePrefixOnce(prefix: String): List<ServiceEntity>

    @Query("SELECT * FROM services ORDER BY date, startTime")
    suspend fun allOnce(): List<ServiceEntity>

    @Query("SELECT * FROM services WHERE id = :id")
    suspend fun byId(id: String): ServiceEntity?

    @Query("""
        SELECT COUNT(*) AS count,
               COALESCE(SUM(km), 0)     AS totalKm,
               COALESCE(SUM(hours), 0)  AS totalHours,
               COALESCE(SUM(income), 0) AS totalIncome
        FROM services WHERE date LIKE :prefix || '%'
    """)
    fun observeTotals(prefix: String): Flow<ServiceTotals>

    @Query("""
        SELECT type, COUNT(*) AS count,
               COALESCE(SUM(km), 0)     AS totalKm,
               COALESCE(SUM(hours), 0)  AS totalHours,
               COALESCE(SUM(income), 0) AS totalIncome
        FROM services WHERE date LIKE :prefix || '%' GROUP BY type
    """)
    fun observeTotalsByType(prefix: String): Flow<List<ServiceTypeTotals>>

    @Query("""
        SELECT date, COUNT(*) AS count,
               COALESCE(SUM(km), 0)     AS totalKm,
               COALESCE(SUM(hours), 0)  AS totalHours,
               COALESCE(SUM(income), 0) AS totalIncome
        FROM services WHERE date LIKE :prefix || '%' GROUP BY date ORDER BY date
    """)
    fun observeDailyTotals(prefix: String): Flow<List<DailyTotals>>

    @Query("SELECT MAX(km) FROM services")
    suspend fun maxKm(): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: ServiceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<ServiceEntity>)
    @Update suspend fun update(item: ServiceEntity)
    @Delete suspend fun delete(item: ServiceEntity)
    @Query("DELETE FROM services WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM services") suspend fun clear()
}

data class ServiceTotals(
    val count: Int = 0,
    val totalKm: Double = 0.0,
    val totalHours: Double = 0.0,
    val totalIncome: Long = 0,
)

data class ServiceTypeTotals(
    val type: String,
    val count: Int,
    val totalKm: Double,
    val totalHours: Double,
    val totalIncome: Long,
)

data class DailyTotals(
    val date: String,
    val count: Int,
    val totalKm: Double,
    val totalHours: Double,
    val totalIncome: Long,
)

@Dao
interface FuelDao {
    @Query("SELECT * FROM fuels ORDER BY date DESC") fun observeAll(): Flow<List<FuelEntity>>
    @Query("SELECT * FROM fuels WHERE date LIKE :prefix || '%' ORDER BY date DESC")
    fun observeByDatePrefix(prefix: String): Flow<List<FuelEntity>>
    @Query("SELECT * FROM fuels WHERE date LIKE :prefix || '%' ORDER BY date")
    suspend fun byDatePrefixOnce(prefix: String): List<FuelEntity>
    @Query("SELECT * FROM fuels ORDER BY date") suspend fun allOnce(): List<FuelEntity>
    @Query("SELECT COALESCE(SUM(total),0) FROM fuels WHERE date LIKE :prefix || '%'")
    fun observeTotalCost(prefix: String): Flow<Double>
    @Query("SELECT COALESCE(SUM(liters),0) FROM fuels WHERE date LIKE :prefix || '%'")
    fun observeTotalLiters(prefix: String): Flow<Double>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: FuelEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<FuelEntity>)
    @Query("DELETE FROM fuels WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM fuels") suspend fun clear()
}

@Dao
interface MaintenanceDao {
    @Query("SELECT * FROM maintenances ORDER BY date DESC") fun observeAll(): Flow<List<MaintenanceEntity>>
    @Query("SELECT * FROM maintenances WHERE date LIKE :prefix || '%' ORDER BY date DESC")
    fun observeByDatePrefix(prefix: String): Flow<List<MaintenanceEntity>>
    @Query("SELECT * FROM maintenances WHERE date LIKE :prefix || '%' ORDER BY date")
    suspend fun byDatePrefixOnce(prefix: String): List<MaintenanceEntity>
    @Query("SELECT * FROM maintenances ORDER BY date") suspend fun allOnce(): List<MaintenanceEntity>
    @Query("SELECT COALESCE(SUM(cost),0) FROM maintenances WHERE date LIKE :prefix || '%'")
    fun observeTotalCost(prefix: String): Flow<Double>
    @Query("SELECT * FROM maintenances WHERE type = :type ORDER BY km DESC LIMIT 1")
    suspend fun latestByType(type: String): MaintenanceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: MaintenanceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<MaintenanceEntity>)
    @Query("DELETE FROM maintenances WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM maintenances") suspend fun clear()
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans ORDER BY date DESC") fun observeAll(): Flow<List<LoanEntity>>
    @Query("SELECT * FROM loans ORDER BY date") suspend fun allOnce(): List<LoanEntity>
    @Query("SELECT * FROM loans WHERE id = :id") suspend fun byId(id: String): LoanEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: LoanEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<LoanEntity>)
    @Update suspend fun update(item: LoanEntity)
    @Query("DELETE FROM loans WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM loans") suspend fun clear()
}

@Dao
interface PersonalExpenseDao {
    @Query("SELECT * FROM personal_expenses ORDER BY date DESC") fun observeAll(): Flow<List<PersonalExpenseEntity>>
    @Query("SELECT * FROM personal_expenses WHERE date LIKE :prefix || '%' ORDER BY date DESC")
    fun observeByDatePrefix(prefix: String): Flow<List<PersonalExpenseEntity>>
    @Query("SELECT * FROM personal_expenses ORDER BY date") suspend fun allOnce(): List<PersonalExpenseEntity>
    @Query("SELECT COALESCE(SUM(amount),0) FROM personal_expenses WHERE date LIKE :prefix || '%'")
    fun observeTotal(prefix: String): Flow<Double>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: PersonalExpenseEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<PersonalExpenseEntity>)
    @Query("DELETE FROM personal_expenses WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM personal_expenses") suspend fun clear()
}

@Dao
interface PersonalIncomeDao {
    @Query("SELECT * FROM personal_incomes ORDER BY date DESC") fun observeAll(): Flow<List<PersonalIncomeEntity>>
    @Query("SELECT * FROM personal_incomes WHERE date LIKE :prefix || '%' ORDER BY date DESC")
    fun observeByDatePrefix(prefix: String): Flow<List<PersonalIncomeEntity>>
    @Query("SELECT * FROM personal_incomes ORDER BY date") suspend fun allOnce(): List<PersonalIncomeEntity>
    @Query("SELECT COALESCE(SUM(amount),0) FROM personal_incomes WHERE date LIKE :prefix || '%'")
    fun observeTotal(prefix: String): Flow<Double>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: PersonalIncomeEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<PersonalIncomeEntity>)
    @Query("DELETE FROM personal_incomes WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM personal_incomes") suspend fun clear()
}

@Dao
interface PersonnelDao {
    @Query("SELECT * FROM personnel ORDER BY name") fun observeAll(): Flow<List<PersonnelEntity>>
    @Query("SELECT * FROM personnel ORDER BY name") suspend fun allOnce(): List<PersonnelEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: PersonnelEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<PersonnelEntity>)
    @Update suspend fun update(item: PersonnelEntity)
    @Query("DELETE FROM personnel WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM personnel") suspend fun clear()
}

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays ORDER BY date") fun observeAll(): Flow<List<HolidayEntity>>
    @Query("SELECT * FROM holidays ORDER BY date") suspend fun allOnce(): List<HolidayEntity>
    @Query("SELECT date FROM holidays") fun observeDates(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: HolidayEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(items: List<HolidayEntity>)
    @Query("DELETE FROM holidays WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM holidays") suspend fun clear()
}

@Dao
interface RatesDao {
    @Query("SELECT * FROM rates_by_year") fun observeAll(): Flow<List<RatesEntity>>
    @Query("SELECT * FROM rates_by_year") suspend fun allOnce(): List<RatesEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: RatesEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<RatesEntity>)
    @Query("DELETE FROM rates_by_year") suspend fun clear()
}

@Dao
interface SuggestionDao {
    @Query("SELECT value FROM suggestions WHERE field = :field ORDER BY usedAt DESC LIMIT 20")
    fun observeField(field: String): Flow<List<String>>
    @Query("SELECT value FROM suggestions WHERE field = :field ORDER BY usedAt DESC LIMIT 20")
    suspend fun fieldOnce(field: String): List<String>
    @Query("SELECT DISTINCT field FROM suggestions")
    suspend fun fields(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: SuggestionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<SuggestionEntity>)
    /** Keeps the newest 20 per field, matching the JS `slice(0, 20)` cap. */
    @Query("""
        DELETE FROM suggestions WHERE field = :field AND value NOT IN
        (SELECT value FROM suggestions WHERE field = :field ORDER BY usedAt DESC LIMIT 20)
    """)
    suspend fun trim(field: String)
    @Query("DELETE FROM suggestions") suspend fun clear()
}

/** Wipe-and-replace used by backup restore, in one transaction. */
@Dao
interface MaintenanceOpsDao {
    @Transaction
    @Query("SELECT 1")
    suspend fun noop(): Int
}
