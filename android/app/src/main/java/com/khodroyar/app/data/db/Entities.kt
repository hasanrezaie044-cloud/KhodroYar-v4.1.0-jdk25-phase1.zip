package com.khodroyar.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema. Field names, types and semantics mirror the TypeScript interfaces in
 * contexts/AppContext.tsx so legacy AsyncStorage payloads and backup files import
 * losslessly. Jalali dates stay "YYYY/MM/DD" strings — that format sorts
 * lexicographically in the same order it sorts chronologically, which the existing
 * queries and reports already rely on.
 *
 * Indices target the access patterns the screens actually use: filtering by date
 * prefix (month/year) and grouping by type.
 */

@Entity(tableName = "services", indices = [Index("date"), Index("type"), Index("carType")])
data class ServiceEntity(
    @PrimaryKey val id: String,
    val date: String,
    val type: String,
    val carType: String,
    val km: Double,
    val hours: Double,
    val workHours: Double,
    val startTime: String,
    val endTime: String,
    val origin: String,
    val destination: String,
    val passengers: String,
    val requestNumber: String,
    val tollCount: Int,
    val missionFood: Double,
    val missionToll: Double,
    val missionFine: Double,
    val income: Long,
    val timestamp: String,
)

@Entity(tableName = "fuels", indices = [Index("date"), Index("type")])
data class FuelEntity(
    @PrimaryKey val id: String,
    val date: String,
    val type: String,
    val liters: Double,
    val total: Double,
    val carType: String,
    val timestamp: String,
)

@Entity(tableName = "maintenances", indices = [Index("date"), Index("type")])
data class MaintenanceEntity(
    @PrimaryKey val id: String,
    val date: String,
    val type: String,
    val typeText: String,
    val cost: Double,
    val km: Double,
    val carType: String,
    val description: String,
    val timestamp: String,
)

@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey val id: String,
    val title: String,
    val totalAmount: Double,
    val interestPercent: Double,
    /** Jalali date the loan was received. */
    val date: String,
    val installmentsCount: Int,
    /** Comma-separated 1-based installment numbers, e.g. "1,2,5". */
    @ColumnInfo(name = "paidInstallments") val paidInstallments: String,
    val timestamp: String,
)

@Entity(tableName = "personal_expenses", indices = [Index("date"), Index("category")])
data class PersonalExpenseEntity(
    @PrimaryKey val id: String,
    val date: String,
    val category: String,
    val title: String,
    val amount: Double,
    val timestamp: String,
)

@Entity(tableName = "personal_incomes", indices = [Index("date"), Index("category")])
data class PersonalIncomeEntity(
    @PrimaryKey val id: String,
    val date: String,
    val category: String,
    val title: String,
    val amount: Double,
    val timestamp: String,
)

@Entity(tableName = "personnel")
data class PersonnelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val personnelCode: String,
    /** Digits only. */
    val costCenter: String,
    val phone: String,
    val timestamp: String,
)

/** Manually managed holidays. Independent of, and combined with, the official table. */
@Entity(tableName = "holidays", indices = [Index(value = ["date"], unique = true)])
data class HolidayEntity(
    @PrimaryKey val id: String,
    val date: String,
    val title: String,
    val timestamp: String,
)

/** Per-Jalali-year rate rows, so editing rates never rewrites historical pricing. */
@Entity(tableName = "rates_by_year")
data class RatesEntity(
    @PrimaryKey val year: Int,
    val nightKm: Double,
    val holidayKm: Double,
    val fixedRequestKm: Double,
    val hour: Double,
    val fuelPriceGov: Double,
    val fuelPriceSemi: Double,
    val fuelPriceFree: Double,
    val toll: Double,
    val defaultCarType: String,
    val oilChangeKmInterval: Int,
    val timingBeltKmInterval: Int,
    val nightPercent: Double,
    val saharPercent: Double,
    val nightStartTime: String,
    val saharStartTime: String,
    val saharEndTime: String,
    /** Comma-separated service-type wire values. */
    val timeBonusServiceTypes: String,
)

/** Autocomplete history for origin / destination / passengers. */
@Entity(tableName = "suggestions", primaryKeys = ["field", "value"])
data class SuggestionEntity(
    val field: String,
    val value: String,
    val usedAt: Long,
)
