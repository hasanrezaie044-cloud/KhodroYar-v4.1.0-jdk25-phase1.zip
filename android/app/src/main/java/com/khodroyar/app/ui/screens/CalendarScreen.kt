package com.khodroyar.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.HolidayEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
import kotlinx.coroutines.launch

/**
 * Real interactive Jalali calendar.
 *
 * `selectedDate` is the single piece of state everything on this screen derives from:
 * change it and the services, income, fuel, maintenance, personal expenses and the
 * daily totals all recompute.
 *
 * Holidays combine the offline official table ([IranHolidays]) with the user's manual
 * list, and Thursday / Friday stay red. No network, no external calendar API.
 *
 * New: a compact monthly work summary sits at the top — total kilometres, total hours
 * and the number of services for the displayed month, and nothing else. The detailed
 * per-service lines stay where they belong, under the selected day.
 */
@Composable
fun CalendarScreen(
    repository: AppRepository,
    onQuickService: (String) -> Unit,
    onEditService: (ServiceEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val today = remember { Jalali.todayString() }
    val start = remember { Jalali.today() }

    var selectedDate by remember { mutableStateOf(today) }
    var viewYear by remember { mutableStateOf(start.year) }
    var viewMonth by remember { mutableStateOf(start.month) }

    val monthPrefix = "$viewYear/${viewMonth.toString().padStart(2, '0')}"

    val monthServices by repository.services(monthPrefix).collectAsState(initial = emptyList())
    val monthFuels by repository.fuels(monthPrefix).collectAsState(initial = emptyList())
    val monthMaintenance by repository.maintenances(monthPrefix).collectAsState(initial = emptyList())
    val monthExpenses by repository.personalExpenses(monthPrefix).collectAsState(initial = emptyList())
    val manualHolidays by repository.manualHolidays.collectAsState(initial = emptyList<HolidayEntity>())

    val manualDates = remember(manualHolidays) { manualHolidays.map { it.date }.toHashSet() }
    val isHoliday: (String) -> Boolean = remember(manualDates) {
        { date -> manualDates.contains(date) || IranHolidays.isOfficialHoliday(date) }
    }
    val datesWithData = remember(monthServices, monthFuels, monthMaintenance, monthExpenses) {
        HashSet<String>().apply {
            monthServices.forEach { add(it.date) }
            monthFuels.forEach { add(it.date) }
            monthMaintenance.forEach { add(it.date) }
            monthExpenses.forEach { add(it.date) }
        }
    }

    // --- monthly summary: exactly three numbers, nothing more ---
    val monthKm = remember(monthServices) { monthServices.sumOf { it.km } }
    val monthHours = remember(monthServices) { monthServices.sumOf { it.hours } }
    val monthIncome = remember(monthServices) { monthServices.sumOf { it.income } }

    // --- everything below is derived from selectedDate ---
    val dayServices = remember(monthServices, selectedDate) { monthServices.filter { it.date == selectedDate } }
    val dayFuels = remember(monthFuels, selectedDate) { monthFuels.filter { it.date == selectedDate } }
    val dayMaintenance = remember(monthMaintenance, selectedDate) { monthMaintenance.filter { it.date == selectedDate } }
    val dayExpenses = remember(monthExpenses, selectedDate) { monthExpenses.filter { it.date == selectedDate } }

    val dayIncome = dayServices.sumOf { it.income }
    val fuelCost = dayFuels.sumOf { it.total }
    val maintenanceCost = dayMaintenance.sumOf { it.cost }
    val personalCost = dayExpenses.sumOf { it.amount }
    val totalExpense = fuelCost + maintenanceCost + personalCost

    val selectedYmd = Jalali.parse(selectedDate)
    val weekday = selectedYmd?.let { Jalali.weekdayName(it.year, it.month, it.day) } ?: ""
    val holidayTitle = remember(selectedDate, manualHolidays) {
        manualHolidays.firstOrNull { it.date == selectedDate }?.let { it.title.ifBlank { "تعطیل (دستی)" } }
            ?: IranHolidays.titleFor(selectedDate)
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
    ) {
        /* --------------------------------------------- compact month summary */
        item {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Insights, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "خلاصه کارکرد ${Jalali.monthName(viewMonth)} $viewYear",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(AppDimens.gutter))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.gap),
                ) {
                    MetricCard(
                        "کیلومتر ماه", money(monthKm), Icons.Outlined.Speed,
                        Modifier.weight(1f), compact = true,
                    )
                    MetricCard(
                        "ساعت ماه", Analytics.formatHours(monthHours), Icons.Outlined.Schedule,
                        Modifier.weight(1f), compact = true,
                    )
                    MetricCard(
                        "تعداد سرویس", monthServices.size.toString(), Icons.Outlined.Route,
                        Modifier.weight(1f), compact = true,
                    )
                }
                if (monthIncome > 0) {
                    Spacer(Modifier.height(AppDimens.gap))
                    InfoRow(
                        "درآمد این ماه",
                        "${amount(monthIncome)} تومان",
                        emphasis = true,
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        /* ---------------------------------------------------------- month grid */
        item {
            SurfaceCard {
                MonthNavigator(
                    year = viewYear,
                    month = viewMonth,
                    trailing = "${monthServices.size} سرویس در این ماه",
                    onPrevious = {
                        if (viewMonth == 1) { viewMonth = 12; viewYear -= 1 } else viewMonth -= 1
                    },
                    onNext = {
                        if (viewMonth == 12) { viewMonth = 1; viewYear += 1 } else viewMonth += 1
                    },
                )
                Spacer(Modifier.height(10.dp))
                JalaliMonthGrid(
                    year = viewYear,
                    month = viewMonth,
                    selected = selectedDate,
                    today = today,
                    hasData = { datesWithData.contains(it) },
                    isHoliday = isHoliday,
                    onSelect = { selectedDate = it },
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val t = Jalali.today()
                        viewYear = t.year; viewMonth = t.month
                        selectedDate = Jalali.format(t.year, t.month, t.day)
                    }) { Text(tr("برو به امروز", "Go to today")) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "نقطه = روز دارای اطلاعات",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        /* ------------------------------------------------ selected day summary */
        item {
            GradientCard(brush = heroBrush()) {
                Text(
                    "روز انتخاب‌شده",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "$selectedDate  ·  $weekday",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                holidayTitle?.let {
                    Text(
                        "تعطیل: $it",
                        color = Color(0xFFFFC9BF),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                MetricPair(
                    first = { m ->
                        MetricCard("تعداد سرویس", dayServices.size.toString(), Icons.Outlined.Route, m, compact = true)
                    },
                    second = { m ->
                        MetricCard("درآمد سرویس", amount(dayIncome), Icons.Outlined.Payments, m, compact = true)
                    },
                )
                Spacer(Modifier.height(AppDimens.gap))
                MetricPair(
                    first = { m ->
                        MetricCard("کیلومتر", money(dayServices.sumOf { it.km }), Icons.Outlined.Speed, m, compact = true)
                    },
                    second = { m ->
                        MetricCard(
                            "کارکرد",
                            "${Analytics.formatHours(dayServices.sumOf { it.hours })} ساعت",
                            Icons.Outlined.Schedule, m, compact = true,
                        )
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onQuickService(selectedDate) },
                    modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                    shape = FieldShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Outlined.AddCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ثبت سرویس برای $selectedDate", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        /* --------------------------------------------------- daily expenses box */
        item {
            Collapsible(
                title = "هزینه‌های این روز",
                summary = "${amount(totalExpense)} تومان",
            ) {
                if (dayFuels.isEmpty() && dayMaintenance.isEmpty() && dayExpenses.isEmpty()) {
                    Text(
                        "برای این روز هزینه‌ای ثبت نشده است.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dayFuels.forEach { fuel ->
                            ExpenseLine(
                                icon = Icons.Outlined.LocalGasStation,
                                type = "سوخت · ${FuelType.fromWire(fuel.type).label}",
                                date = fuel.date,
                                amountText = amount(fuel.total),
                                description = "${money(fuel.liters)} لیتر",
                            )
                        }
                        dayMaintenance.forEach { item ->
                            ExpenseLine(
                                icon = Icons.Outlined.Build,
                                type = "تعمیرات · ${item.typeText.ifBlank { item.type }}",
                                date = item.date,
                                amountText = amount(item.cost),
                                description = listOfNotNull(
                                    item.km.takeIf { it > 0 }?.let { "کیلومتر ${money(it)}" },
                                    item.description.ifBlank { null },
                                ).joinToString(" · "),
                            )
                        }
                        dayExpenses.forEach { expense ->
                            ExpenseLine(
                                icon = Icons.Outlined.ShoppingBag,
                                type = "خرید شخصی · ${expense.category}",
                                date = expense.date,
                                amountText = amount(expense.amount),
                                description = expense.title,
                                onDelete = {
                                    scope.launch {
                                        repository.deletePersonalExpense(expense.id)
                                        toast.show("هزینه شخصی حذف شد")
                                    }
                                },
                            )
                        }
                        HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        InfoRow(
                            "جمع هزینه روز", amount(totalExpense),
                            emphasis = true, valueColor = MaterialTheme.colorScheme.secondary,
                        )
                        InfoRow("خالص روز (درآمد − هزینه)", amount(dayIncome - totalExpense))
                    }
                }
            }
        }

        /* ------------------------------------------------ services of that day */
        item { SectionTitle("سرویس‌های $selectedDate", "${dayServices.size} مورد", Icons.Outlined.Route) }
        if (dayServices.isEmpty()) {
            item {
                EmptyState(
                    "برای این روز سرویسی ثبت نشده",
                    "با دکمه بالا، فرم ثبت با همین تاریخ باز می‌شود.",
                    Icons.Outlined.Route,
                )
            }
        } else {
            items(dayServices, key = { it.id }) { service ->
                ServiceRow(service, detailed = true, onClick = { onEditService(service) })
            }
        }
    }
}

@Composable
private fun ExpenseLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    type: String,
    date: String,
    amountText: String,
    description: String,
    onDelete: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(19.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(type, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(date, description).filter { it.isNotBlank() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(amountText, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Shared service row, used by the calendar, the dashboard and reports. */
@Composable
fun ServiceRow(
    service: ServiceEntity,
    detailed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight)
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = if (onClick == null) base else base.clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(service.date, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            ServiceType.fromWire(service.type).label,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    "${service.origin.ifBlank { "مبدأ نامشخص" }}  ←  ${service.destination.ifBlank { "مقصد نامشخص" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detailed) {
                    Text(
                        listOf(
                            if (service.startTime.isNotBlank() || service.endTime.isNotBlank())
                                "${service.startTime.ifBlank { "--:--" }} تا ${service.endTime.ifBlank { "--:--" }}" else "",
                            "${money(service.km)} کیلومتر",
                            "${Analytics.formatHours(service.hours)} ساعت",
                            if (service.tollCount > 0) "${service.tollCount} عوارضی" else "",
                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                amount(service.income),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}
