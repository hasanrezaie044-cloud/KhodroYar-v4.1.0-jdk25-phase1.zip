package com.khodroyar.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Main destinations deliberately mirror the user's daily flow, not the database tables.
 *
 * The old "بیشتر" tab was a launcher for unrelated tools; it is now "تنظیمات" and holds
 * settings only. The tools it used to hide moved to the right-edge side drawer
 * ([ToolDestination]).
 */
enum class AppDestination(val title: String, val englishTitle: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("خانه", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    SERVICES("ثبت سرویس", "Add Service", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
    CALENDAR("تقویم", "Calendar", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    REPORTS("گزارش", "Reports", Icons.Outlined.Assessment, Icons.Filled.Assessment),
    SETTINGS("تنظیمات", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Secondary tools. Reached from the right-edge slide-out panel, never from a tab. */
enum class ToolDestination(val title: String, val englishTitle: String, val subtitle: String, val englishSubtitle: String, val icon: ImageVector) {
    FUEL("سوخت", "Fuel", "ثبت سوخت‌گیری و هزینه", "Fuel entries and costs", Icons.Outlined.LocalGasStation),
    FINANCE("مالی، وام و اقساط", "Finance & Loans", "نمای کلی، وام‌ها، خرید و درآمد شخصی", "Loans, purchases and personal income", Icons.Outlined.AccountBalanceWallet),
    MAINTENANCE("تعمیرات و نگهداری", "Maintenance", "تعویض روغن، لاستیک، لنت و…", "Oil, tires, brakes and more", Icons.Outlined.Build),
    RATES("نرخ‌ها", "Rates", "نرخ کیلومتر، ساعت، سوخت و عوارضی", "Km, hour, fuel and toll rates", Icons.Outlined.Percent),
}
