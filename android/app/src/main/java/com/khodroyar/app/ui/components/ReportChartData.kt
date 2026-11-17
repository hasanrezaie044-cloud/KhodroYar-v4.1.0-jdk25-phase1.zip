package com.khodroyar.app.ui.components

/** Display partitions only. Income and expense are never mixed, and a sum is never drawn beside its parts. */
internal object ReportChartData {
    data class Part(val id: String, val label: String, val value: Double, val colorIndex: Int)
    fun parts(expenses: Boolean, fuel: Double, maintenance: Double, purchases: Double, serviceIncome: Double, personalIncome: Double): List<Part> =
        if (expenses) listOf(Part("fuel", "سوخت", fuel, 1), Part("maint", "تعمیرات", maintenance, 4), Part("buy", "خریدهای شخصی", purchases, 2))
        else listOf(Part("income", "درآمد سرویس‌ها", serviceIncome, 0), Part("pincome", "درآمد شخصی", personalIncome, 3))
}
