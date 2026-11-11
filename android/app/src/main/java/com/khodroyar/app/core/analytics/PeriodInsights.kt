package com.khodroyar.app.core.analytics

import com.khodroyar.app.core.jalali.Jalali

/** Inclusive, canonical Jalali windows. Comparisons never mix a partial month with a full one. */
data class ReportWindow(val start: String, val end: String) {
    val days: Int get() = Jalali.daysBetween(start, end) + 1
    init {
        require(listOf(start, end).all { value ->
            Regex("1[3-6]\\d{2}/\\d{2}/\\d{2}").matches(value) &&
                Jalali.parse(value)?.let { Jalali.isValid(it.year, it.month, it.day) } == true
        } && start <= end && days in 1..3660) { "بازهٔ تاریخ معتبر نیست یا از ده سال بیشتر است." }
    }
    fun previous(): ReportWindow = ReportWindow(Jalali.addDays(start, -days), Jalali.addDays(start, -1))
    companion object {
        fun monthToDate(today: String): Pair<ReportWindow, ReportWindow> {
            val now = requireNotNull(Jalali.parse(today))
            val previous = requireNotNull(Jalali.parse(Jalali.addMonths(today, -1)))
            val length = minOf(now.day, Jalali.monthLength(previous.year, previous.month))
            return ReportWindow(Jalali.format(now.year, now.month, 1), Jalali.format(now.year, now.month, length)) to
                ReportWindow(Jalali.format(previous.year, previous.month, 1), Jalali.format(previous.year, previous.month, length))
        }
    }
}

/** Same additive cash basis as the existing financial report; no repricing of stored income. */
data class FinancialSlice(
    val serviceIncome: Double = 0.0,
    val personalIncome: Double = 0.0,
    val fuelCost: Double = 0.0,
    val maintenanceCost: Double = 0.0,
    val personalExpense: Double = 0.0,
    val km: Double = 0.0,
    val hours: Double = 0.0,
    val serviceCount: Int = 0,
    val recordCount: Int = 0,
) {
    val income: Double get() = serviceIncome + personalIncome
    val expense: Double get() = fuelCost + maintenanceCost + personalExpense
    val net: Double get() = income - expense
    val vehicleNet: Double get() = serviceIncome - fuelCost - maintenanceCost
    // Personal money is not silently attributed to the car. Zero denominators stay unavailable.
    val vehicleNetPerKm: Double? get() = if (km > 0 && km.isFinite()) vehicleNet / km else null
    val vehicleNetPerHour: Double? get() = if (hours > 0 && hours.isFinite()) vehicleNet / hours else null
    val hasData: Boolean get() = recordCount > 0
}

data class ExpenseGroup(val key: String, val amount: Double)
data class ExpenseChange(val key: String, val current: Double, val previous: Double) {
    val increase: Double get() = current - previous
    val percent: Double? get() = PeriodInsights.percentChange(current, previous)
}

data class PeriodComparison(
    val currentWindow: ReportWindow,
    val previousWindow: ReportWindow,
    val current: FinancialSlice,
    val previous: FinancialSlice,
    val increases: List<ExpenseChange>,
)

object PeriodInsights {
    /** A missing/zero/negative baseline is not a fabricated 100% increase. */
    fun percentChange(current: Double, previous: Double): Double? =
        if (current.isFinite() && previous.isFinite() && previous > 0) (current - previous) * 100.0 / previous else null

    fun increases(current: List<ExpenseGroup>, previous: List<ExpenseGroup>): List<ExpenseChange> {
        val now = current.associate { it.key to it.amount }
        val before = previous.associate { it.key to it.amount }
        return (now.keys + before.keys).map { key -> ExpenseChange(key, now[key] ?: 0.0, before[key] ?: 0.0) }
            .filter { it.increase.isFinite() && it.increase > 0.0 }.sortedByDescending { it.increase }.take(3)
    }
}
