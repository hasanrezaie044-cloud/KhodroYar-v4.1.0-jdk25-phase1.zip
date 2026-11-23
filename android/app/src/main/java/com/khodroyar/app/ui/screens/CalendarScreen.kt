package com.khodroyar.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.holidays.WorkCalendar
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.records.RecordKey
import com.khodroyar.app.core.records.RecordKind
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.HolidayEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush

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
    onQuickFuel: (String) -> Unit,
    onQuickMaintenance: (String) -> Unit,
    onQuickExpense: (String) -> Unit,
    onEditService: (ServiceEntity) -> Unit,
) {
    val today = remember { Jalali.todayString() }
    val start = remember { Jalali.today() }

    var selectedDate by remember { mutableStateOf(today) }
    // A tap never edits: every record of the day opens the shared details page (5.9.0),
    // where edit and delete are explicit actions (delete behind a confirmation).
    var detailKey by remember { mutableStateOf<RecordKey?>(null) }
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
        { date -> WorkCalendar.isNonWorkingDay(date, manualDates) }
    }
    // Red dots: every day that has any expense (fuel, repairs, personal purchases).
    val expenseCountByDate = remember(monthFuels, monthMaintenance, monthExpenses) {
        HashMap<String, Int>().apply {
            monthFuels.forEach { this[it.date] = (this[it.date] ?: 0) + 1 }
            monthMaintenance.forEach { this[it.date] = (this[it.date] ?: 0) + 1 }
            monthExpenses.forEach { this[it.date] = (this[it.date] ?: 0) + 1 }
        }
    }
    val serviceCountByDate = remember(monthServices) {
        monthServices.groupingBy { it.date }.eachCount()
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
            ?: WorkCalendar.weekendName(selectedDate)
    }

    LazyColumn(
        modifier = Modifier.testTag("calendar-list"),
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
    ) {
        /* --------------------------------------------- compact month summary */
        // 5.9.0: one slim strip. Each metric is an icon + value; the label lives in the
        // accessibility description so the strip stays a single line on small phones.
        item {
            Surface(
                shape = FieldShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("calendar-month-summary"),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Jalali.monthName(viewMonth)} $viewYear",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (monthIncome > 0) {
                            val monthIncomeText = "${amount(monthIncome)} تومان"
                            Text(
                                monthIncomeText,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.semantics { contentDescription = "درآمد این ماه: $monthIncomeText" },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SummaryIconChip(Icons.Outlined.Speed, money(monthKm), "کیلومتر ماه", Modifier.weight(1f), "calendar-km")
                        SummaryIconChip(Icons.Outlined.Schedule, Analytics.formatHours(monthHours), "ساعت ماه", Modifier.weight(1f), "calendar-hours")
                        SummaryIconChip(Icons.Outlined.Route, money(monthServices.size), "تعداد سرویس", Modifier.weight(1f), "calendar-count")
                    }
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
                    hasData = { serviceCountByDate.containsKey(it) },
                    dataCount = { serviceCountByDate[it] ?: 0 },
                    expenseCount = { expenseCountByDate[it] ?: 0 },
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
                Text(amount(dayIncome), color = Color.White, style = MaterialTheme.typography.displaySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("تومان درآمد سرویس", color = Color(0xFFB9CFD0), style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color.White.copy(alpha = 0.13f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroStat("سرویس", money(dayServices.size), Modifier.weight(1f))
                    HeroStat("کیلومتر", money(dayServices.sumOf { it.km }), Modifier.weight(1f))
                    HeroStat("ساعت", Analytics.formatHours(dayServices.sumOf { it.hours }), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onQuickService(selectedDate) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.buttonHeight),
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
                Spacer(Modifier.height(AppDimens.gap))
                Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                    QuickActionButton("ثبت سوخت", Icons.Outlined.LocalGasStation, Modifier.weight(1f)) { onQuickFuel(selectedDate) }
                    QuickActionButton("ثبت تعمیرات", Icons.Outlined.Build, Modifier.weight(1f)) { onQuickMaintenance(selectedDate) }
                    QuickActionButton("ثبت هزینه", Icons.Outlined.ShoppingBag, Modifier.weight(1f)) { onQuickExpense(selectedDate) }
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
                                onClick = { detailKey = RecordKey(RecordKind.FUEL, fuel.id) },
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
                                onClick = { detailKey = RecordKey(RecordKind.MAINTENANCE, item.id) },
                            )
                        }
                        dayExpenses.forEach { expense ->
                            ExpenseLine(
                                icon = Icons.Outlined.ShoppingBag,
                                type = "خرید شخصی · ${expense.category}",
                                date = expense.date,
                                amountText = amount(expense.amount),
                                description = expense.title,
                                // Delete lives on the details page, behind a confirmation, like every other record.
                                onClick = { detailKey = RecordKey(RecordKind.EXPENSE, expense.id) },
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
        // 5.9.1: a green notebook page of square shortcuts; each opens that service's own details.
        item(key = "calendar-day-services") {
            DayServicesNotebook(selectedDate, dayServices) { service -> detailKey = RecordKey(RecordKind.SERVICE, service.id) }
        }
    }

    detailKey?.let { key ->
        RecordDetailsPage(
            repository = repository,
            key = key,
            onDismiss = { detailKey = null },
            // Services edit in the calendar's own form; other kinds are edited from their module.
            onEdit = if (key.kind == RecordKind.SERVICE) { _ ->
                monthServices.firstOrNull { it.id == key.id }?.let { detailKey = null; onEditService(it) }
            } else null,
        )
    }
}

/** Columns for the square service grid: as many ~104dp cells as fit (fewer at large font). */
internal fun serviceGridColumns(widthDp: Float, fontScale: Float): Int {
    val cell = if (fontScale > 1.3f) 136f else 104f
    val gap = 8f
    return ((widthDp + gap) / (cell + gap)).toInt().coerceIn(2, 6)
}

@Composable
private fun DayServicesNotebook(date: String, services: List<ServiceEntity>, onOpen: (ServiceEntity) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("calendar-services-notebook"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NotebookCover(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, null, Modifier.size(20.dp), tint = NotebookInk.accent)
                Spacer(Modifier.width(8.dp))
                Text("سرویس‌های $date", Modifier.weight(1f).semantics { heading() }, color = NotebookInk.primary,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${services.size} مورد", color = NotebookInk.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        if (services.isEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), color = notebookPaper(), modifier = Modifier.fillMaxWidth()) {
                Text("برای این روز سرویسی ثبت نشده", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("calendar-service-grid")) {
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                val columns = serviceGridColumns(maxWidth.value, fontScale)
                val cell = (maxWidth - 8.dp * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    services.chunked(columns).forEach { row ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { service ->
                                ServiceSquareCard(service, Modifier.weight(1f).fillMaxHeight().heightIn(min = cell)) { onOpen(service) }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Square shortcut for one service. Delivered services keep a static green side accent and glow. */
@Composable
private fun ServiceSquareCard(service: ServiceEntity, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val green = com.khodroyar.app.ui.theme.appAccents.positive
    val type = ServiceType.fromWire(service.type).label
    val time = if (service.startTime.isNotBlank() || service.endTime.isNotBlank())
        "${service.startTime.ifBlank { "--:--" }}–${service.endTime.ifBlank { "--:--" }}" else ""
    val route = listOf(service.origin, service.destination).filter { it.isNotBlank() }.joinToString(" ← ")
    val incomeText = amount(service.income)
    val toman = tr("تومان", "Toman")
    val openLabel = tr("نمایش جزئیات", "Show details")
    val state = if (service.delivered) "تحویل شده" else "تحویل نشده"
    val description = listOf(type, time, route, "$incomeText $toman").filter { it.isNotBlank() }.joinToString("، ")
    Surface(
        shape = shape,
        color = if (service.delivered) androidx.compose.ui.graphics.lerp(notebookPaper(), green, 0.10f) else notebookPaper(),
        border = BorderStroke(if (service.delivered) 1.5.dp else 1.dp,
            if (service.delivered) green.copy(alpha = 0.75f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        modifier = modifier
            .deliveredGlow(service.delivered, shape, green)
            .clip(shape)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .testTag("calendar-service-${service.id}")
            .semantics(mergeDescendants = true) { contentDescription = description; stateDescription = state },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (service.delivered) DeliveredAccent(Modifier.testTag("calendar-service-delivered-${service.id}"))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(RecordKind.SERVICE.icon, size = 30.dp)
                    Spacer(Modifier.weight(1f))
                    if (service.delivered) Icon(Icons.Outlined.TaskAlt, null, Modifier.size(18.dp), tint = green)
                }
                Spacer(Modifier.height(4.dp))
                Text(type, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time.ifBlank { route.ifBlank { "—" } }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(incomeText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = Modifier.fillMaxWidth().clip(FieldShape)
        .let { if (onClick != null) it.clickable(onClickLabel = "جزئیات", onClick = onClick) else it }
        .padding(vertical = 4.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
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
    modifier: Modifier = Modifier,
) {
    val green = com.khodroyar.app.ui.theme.appAccents.positive
    val base = modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight)
        .deliveredGlow(service.delivered, FieldShape, green)
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(if (service.delivered) 1.5.dp else 1.dp,
            if (service.delivered) green.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = if (onClick == null) base else base.clickable(onClick = onClick),
    ) {
      Row(Modifier.height(IntrinsicSize.Min)) {
        if (service.delivered) DeliveredAccent(Modifier.testTag("service-delivered-${service.id}"))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(service.date, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (service.attachmentPath.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.AttachFile, "دارای عکس پیوست",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
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
            Text(
                amount(service.income) + " " + tr("تومان", "Toman"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
      }
    }
}


/** One of the per-day quick links on the selected-day card (fuel / repairs / expense). */
@Composable
private fun QuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = FieldShape,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Icon-only metric chip: compact visually, fully labelled for TalkBack. */
@Composable
private fun SummaryIconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier, tag: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.heightIn(min = 36.dp).testTag(tag).semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
