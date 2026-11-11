package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.*
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*

fun expenseGroupLabel(key: String): String = when (key) {
    "fuel" -> "سوخت"
    "maintenance" -> "تعمیر و نگهداری"
    else -> PurchaseCategory.fromWire(key.removePrefix("personal:")).label
}

/** Independent of the service-type filter: these are cash totals across all recorded categories. */
@Composable
internal fun FinancialComparisonSection(repository: AppRepository, from: String, to: String, today: String) {
    var expanded by remember { mutableStateOf(false) }
    val windows = remember(from, to, today) { runCatching { ReportWindow.forRange(from, to, today) }.getOrNull() }
    val window = windows?.first
    var comparison by remember { mutableStateOf<PeriodComparison?>(null) }
    LaunchedEffect(windows) {
        comparison = null
        if (windows != null) repository.comparison(windows.first, windows.second).collect { comparison = it }
    }
    SurfaceCard(modifier = Modifier.testTag("financial-comparison"), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(Icons.Outlined.CompareArrows, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(tr("مقایسهٔ مالی", "Financial comparison"), style = MaterialTheme.typography.titleMedium)
                Text(tr("همهٔ ثبت‌ها", "All recorded categories"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            window?.let { StatusPill("${it.days} ${tr("روز", "days")}") }
        }
        Spacer(Modifier.height(14.dp))
        val data = comparison
        when {
            window == null -> Text(tr("بازهٔ معتبر و حداکثر ده‌ساله را انتخاب کنید.", "Choose a valid range of at most ten years."), color = MaterialTheme.colorScheme.error)
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            !data.current.hasData && !data.previous.hasData -> Text(tr("برای این دو بازه ثبتی وجود ندارد.", "No records in these periods."))
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComparisonValue(tr("خالص بازهٔ انتخابی", "Selected period net"), data.currentWindow, data.current.net, data.current.hasData)
                    ComparisonValue(tr("خالص بازهٔ هم‌طول قبل", "Previous equal period net"), data.previousWindow, data.previous.net, data.previous.hasData)
                    if (data.current.hasData && data.previous.hasData) {
                        val delta = data.current.net - data.previous.net
                        val color = if (delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        val pct = PeriodInsights.percentChange(data.current.net, data.previous.net)
                        StatusPill((if (delta >= 0) "+" else "−") + amount(kotlin.math.abs(delta)) + " " + tr("تومان", "Toman") +
                            (if (pct != null && !LocalPrivacyMode.current) " · ${money(pct)}٪" else ""), color = color)
                    }
                }
                if (!expanded && data.previous.hasData) data.increases.firstOrNull()?.let { top ->
                    Text(tr("بیشترین رشد هزینه: ", "Largest cost rise: ") + expenseGroupLabel(top.key) + " · +${amount(top.increase)} " + tr("تومان", "Toman"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("comparison-details").fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (expanded) tr("بستن جزئیات", "Hide details") else tr("شاخص‌ها و تغییر هزینه", "Metrics and cost changes"))
                    Spacer(Modifier.width(6.dp)); Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (expanded) {
                    HorizontalDivider(); Spacer(Modifier.height(12.dp))
                    InfoRow(tr("درآمد ثبت‌شده", "Recorded income"), amount(data.current.income) + " " + tr("تومان", "Toman"))
                    InfoRow(tr("هزینهٔ ثبت‌شده", "Recorded expenses"), amount(data.current.expense) + " " + tr("تومان", "Toman"))
                    InfoRow(tr("خالص خودرو / کیلومتر", "Vehicle net / km"), data.current.vehicleNetPerKm?.let { amount(it) + " " + tr("تومان", "Toman") } ?: "—")
                    InfoRow(tr("خالص خودرو / ساعت", "Vehicle net / hour"), data.current.vehicleNetPerHour?.let { amount(it) + " " + tr("تومان", "Toman") } ?: "—")
                    Text(tr("خالص خودرو: درآمد سرویس منهای سوخت و نگهداری", "Vehicle net: trip income minus fuel and maintenance"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    if (data.increases.isEmpty()) Text(tr("افزایش هزینه‌ای در ثبت‌های این دو بازه دیده نشد.", "No recorded expense increase."), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(if (data.previous.hasData) tr("بیشترین افزایش هزینه", "Largest expense increases") else tr("بیشترین هزینهٔ ثبت‌شده", "Largest recorded expenses"), style = MaterialTheme.typography.titleSmall)
                        data.increases.forEach { change ->
                            InfoRow(expenseGroupLabel(change.key), "+${amount(change.increase)} " + tr("تومان", "Toman"), valueColor = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonValue(label: String, window: ReportWindow, value: Double, hasData: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(if (hasData) amount(value) else "—", style = MaterialTheme.typography.titleMedium)
        }
        Text("${window.start} — ${window.end}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
