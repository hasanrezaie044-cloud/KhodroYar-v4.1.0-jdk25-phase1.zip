package com.khodroyar.app.core.analytics

/**
 * Where Home's trend chart may draw the monthly income target (5.9.0). The target is a
 * monthly toman amount, so it is only shown against income: as-is for monthly buckets
 * and ×12 for yearly buckets. Weekly buckets and km/hour/count metrics get no line,
 * because any conversion there would be invented.
 */
object HomeGoalLine {
    fun of(monthlyGoal: Double, incomeMetric: Boolean, scale: Analytics.TrendScale): Double? {
        if (!incomeMetric || !monthlyGoal.isFinite() || monthlyGoal <= 0.0) return null
        return when (scale) {
            Analytics.TrendScale.MONTH -> monthlyGoal
            Analytics.TrendScale.YEAR -> monthlyGoal * 12
            Analytics.TrendScale.WEEK -> null
        }
    }
}
