package com.khodroyar.app.ai

/** Typed in-app destinations only. No arbitrary URL, intent, accessibility or cross-app control. */
enum class AgentSection(val wire: String, val label: String) {
    HOME("home", "خانه"), SERVICES("services", "ثبت سرویس"), CALENDAR("calendar", "تقویم"),
    REPORTS("reports", "گزارش‌ها"), SETTINGS("settings", "تنظیمات"), FINANCE("finance", "مالی و اقساط"),
    FUEL("fuel", "سوخت"), MAINTENANCE("maintenance", "تعمیرات و نگهداری"), INSURANCE("insurance", "بیمه و مدارک"),
    RATES("rates", "نرخ‌ها"), TRAFFIC("traffic", "دستیار ترافیک"), SEARCH("search", "جستجو");
    companion object { fun fromWire(wire: String?) = entries.firstOrNull { it.wire == wire } }
}
