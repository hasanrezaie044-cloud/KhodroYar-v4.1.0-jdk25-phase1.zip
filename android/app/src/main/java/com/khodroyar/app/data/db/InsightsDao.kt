package com.khodroyar.app.data.db

import androidx.room.Dao
import androidx.room.Query
import com.khodroyar.app.core.analytics.ExpenseGroup
import com.khodroyar.app.core.analytics.FinancialSlice
import com.khodroyar.app.core.analytics.MileageDay
import kotlinx.coroutines.flow.Flow

/** Bounded SQL reads for on-device insights; schema and historical financial calculations stay unchanged. */
@Dao
interface InsightsDao {
    @Query("""
        SELECT
        (SELECT COALESCE(SUM(income),0) FROM services WHERE date BETWEEN :start AND :end) AS serviceIncome,
        (SELECT COALESCE(SUM(amount),0) FROM personal_incomes WHERE date BETWEEN :start AND :end) AS personalIncome,
        (SELECT COALESCE(SUM(total),0) FROM fuels WHERE date BETWEEN :start AND :end) AS fuelCost,
        (SELECT COALESCE(SUM(cost),0) FROM maintenances WHERE date BETWEEN :start AND :end) AS maintenanceCost,
        (SELECT COALESCE(SUM(amount),0) FROM personal_expenses WHERE date BETWEEN :start AND :end) AS personalExpense,
        (SELECT COALESCE(SUM(km),0) FROM services WHERE date BETWEEN :start AND :end AND type NOT IN ('fixed-salary','leave')) AS km,
        (SELECT COALESCE(SUM(hours),0) FROM services WHERE date BETWEEN :start AND :end AND type NOT IN ('fixed-salary','leave')) AS hours,
        (SELECT COUNT(*) FROM services WHERE date BETWEEN :start AND :end) AS serviceCount,
        ((SELECT COUNT(*) FROM services WHERE date BETWEEN :start AND :end) +
         (SELECT COUNT(*) FROM personal_incomes WHERE date BETWEEN :start AND :end) +
         (SELECT COUNT(*) FROM fuels WHERE date BETWEEN :start AND :end) +
         (SELECT COUNT(*) FROM maintenances WHERE date BETWEEN :start AND :end) +
         (SELECT COUNT(*) FROM personal_expenses WHERE date BETWEEN :start AND :end)) AS recordCount
    """)
    fun financial(start: String, end: String): Flow<FinancialSlice>

    @Query("""
        SELECT 'fuel' AS `key`, COALESCE(SUM(total),0) AS amount FROM fuels WHERE date BETWEEN :start AND :end
        UNION ALL
        SELECT 'maintenance' AS `key`, COALESCE(SUM(cost),0) AS amount FROM maintenances WHERE date BETWEEN :start AND :end
        UNION ALL
        SELECT 'personal:' || category AS `key`, COALESCE(SUM(amount),0) AS amount FROM personal_expenses
        WHERE date BETWEEN :start AND :end GROUP BY category
    """)
    fun expenses(start: String, end: String): Flow<List<ExpenseGroup>>

    @Query("""
        SELECT date, COALESCE(SUM(km),0) AS km FROM services
        WHERE date BETWEEN :start AND :end AND type NOT IN ('fixed-salary','leave') AND km > 0
        GROUP BY date ORDER BY date
    """)
    fun mileageDays(start: String, end: String): Flow<List<MileageDay>>

    @Query("""
        SELECT value, COUNT(*) AS uses FROM (
            SELECT amount AS value FROM personal_expenses WHERE :kind = 'EXPENSE'
            UNION ALL SELECT total AS value FROM fuels WHERE :kind = 'FUEL'
            UNION ALL SELECT cost AS value FROM maintenances WHERE :kind = 'MAINTENANCE'
        ) WHERE value >= 0 GROUP BY value HAVING COUNT(*) > 1
        ORDER BY uses DESC, value DESC LIMIT 3
    """)
    fun frequentAmounts(kind: String): Flow<List<FrequentAmount>>

    @Query("SELECT * FROM services ORDER BY timestamp DESC, date DESC, id DESC LIMIT 3")
    fun recentServices(): Flow<List<ServiceEntity>>
    @Query("SELECT * FROM fuels ORDER BY timestamp DESC, date DESC, id DESC LIMIT 3")
    fun recentFuels(): Flow<List<FuelEntity>>
    @Query("SELECT * FROM maintenances ORDER BY timestamp DESC, date DESC, id DESC LIMIT 3")
    fun recentMaintenance(): Flow<List<MaintenanceEntity>>
    @Query("SELECT * FROM personal_expenses ORDER BY timestamp DESC, date DESC, id DESC LIMIT 3")
    fun recentExpenses(): Flow<List<PersonalExpenseEntity>>
    @Query("SELECT * FROM personal_expenses WHERE date = :date ORDER BY timestamp DESC, id DESC LIMIT 21")
    suspend fun expensesOn(date: String): List<PersonalExpenseEntity>

    @Query("SELECT date, COUNT(*) AS count, COALESCE(SUM(km),0) AS totalKm, COALESCE(SUM(hours),0) AS totalHours, COALESCE(SUM(income),0) AS totalIncome FROM services WHERE date BETWEEN :start AND :end GROUP BY date ORDER BY date")
    fun trendDays(start: String, end: String): Flow<List<DailyTotals>>
    @Query("SELECT * FROM services ORDER BY date DESC, startTime DESC LIMIT 5")
    fun latestServices(): Flow<List<ServiceEntity>>
    @Query("SELECT * FROM services WHERE date BETWEEN :start AND :end ORDER BY date, startTime")
    fun serviceRecords(start: String, end: String): Flow<List<ServiceEntity>>
    @Query("SELECT * FROM fuels WHERE date BETWEEN :start AND :end ORDER BY date")
    fun fuelRecords(start: String, end: String): Flow<List<FuelEntity>>
    @Query("SELECT * FROM maintenances WHERE date BETWEEN :start AND :end ORDER BY date")
    fun maintenanceRecords(start: String, end: String): Flow<List<MaintenanceEntity>>
    @Query("SELECT * FROM personal_expenses WHERE date BETWEEN :start AND :end ORDER BY date")
    fun expenseRecords(start: String, end: String): Flow<List<PersonalExpenseEntity>>
    @Query("SELECT * FROM personal_incomes WHERE date BETWEEN :start AND :end ORDER BY date")
    fun incomeRecords(start: String, end: String): Flow<List<PersonalIncomeEntity>>

    @Query("SELECT * FROM fuels WHERE id LIKE :prefix || '%' LIMIT 2") suspend fun fuelByIdPrefix(prefix: String): List<FuelEntity>
    @Query("SELECT * FROM maintenances WHERE id LIKE :prefix || '%' LIMIT 2") suspend fun maintenanceByIdPrefix(prefix: String): List<MaintenanceEntity>
    @Query("SELECT * FROM personal_expenses WHERE id LIKE :prefix || '%' LIMIT 2") suspend fun expenseByIdPrefix(prefix: String): List<PersonalExpenseEntity>

    @Query("SELECT * FROM fuels WHERE id = :id") suspend fun fuel(id: String): FuelEntity?
    @Query("SELECT * FROM maintenances WHERE id = :id") suspend fun maintenance(id: String): MaintenanceEntity?
    @Query("SELECT * FROM personal_expenses WHERE id = :id") suspend fun expense(id: String): PersonalExpenseEntity?
}

/** A bounded, aggregate suggestion from actual past values, never a default write. */
data class FrequentAmount(val value: Double, val uses: Int)
