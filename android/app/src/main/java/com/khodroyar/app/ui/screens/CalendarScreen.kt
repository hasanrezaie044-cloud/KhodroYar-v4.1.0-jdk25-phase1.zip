package com.khodroyar.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.holidays.WorkCalendar
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
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
    onQuickFuel: (String) -> Unit,
    onQuickMaintenance: (String) -> Unit,
    onQuickExpense: (String) -> Unit,
    onEditService: (ServiceEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val today = remember { Jalali.todayString() }
    val start = remember { Jalali.today() }

    var selectedDate by remember { mutableStateOf(today) }
    // Tapping a service opens its full detail sheet; editing and deleting are actions
    // inside that sheet, never the result of the tap itself.
    var detail by remember { mutableStateOf<ServiceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<ServiceEntity?>(null) }
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
                    "",
                    Icons.Outlined.Route,
                )
            }
        } else {
            items(dayServices, key = { it.id }) { service ->
                ServiceRow(service, detailed = true, onClick = { detail = service })
            }
        }
    }

    // Keep the sheet bound to the freshest copy of the record, so an edit made from
    // inside it is reflected the moment the list re-emits.
    val detailService = detail?.let { current -> monthServices.firstOrNull { it.id == current.id } ?: current }
    if (detailService != null) {
        ServiceDetailSheet(
            service = detailService,
            onDismiss = { detail = null },
            onEdit = {
                detail = null
                onEditService(detailService)
            },
            onDelete = { pendingDelete = detailService },
        )
    }

    pendingDelete?.let { service ->
        ConfirmDialog(
            title = "حذف سرویس",
            message = "سرویس ${service.date} با مبلغ ${money(service.income)} تومان حذف شود؟",
            confirmLabel = "حذف",
            destructive = true,
            onConfirm = {
                scope.launch {
                    repository.deleteService(service.id)
                    toast.show("سرویس حذف شد")
                    pendingDelete = null
                    detail = null
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * Everything stored about one service, plus the two actions that used to happen by
 * accident: a tap on a calendar row jumped straight into the edit form, so there was no
 * way to simply *look* at a service. Now the tap shows the record and the edit / delete
 * buttons live at the bottom of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceDetailSheet(
    service: ServiceEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val weekday = Jalali.parse(service.date)
        ?.let { Jalali.weekdayName(it.year, it.month, it.day) } ?: ""

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppDimens.screenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
        ) {
            GradientCard(brush = heroBrush()) {
                Text(
                    "جزئیات سرویس",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "${service.date}  ·  $weekday",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${amount(service.income)} تومان",
                    color = Color.White,
                    style = MaterialTheme.typography.displayMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SurfaceCard {
                InfoRow("نوع سرویس", ServiceType.fromWire(service.type).label)
                InfoRow("خودرو", CarType.fromWire(service.carType).label)
                InfoRow("تاریخ", "${service.date}  ·  $weekday")
                InfoRow(
                    "ساعت شروع تا پایان",
                    if (service.startTime.isBlank() && service.endTime.isBlank()) "—"
                    else "${service.startTime.ifBlank { "--:--" }} تا ${service.endTime.ifBlank { "--:--" }}",
                )
                InfoRow("ساعت کارکرد", "${Analytics.formatHours(service.hours)} ساعت")
                InfoRow("کیلومتر", "${money(service.km)} کیلومتر")
            }

            SurfaceCard {
                InfoRow("مبدأ", service.origin.ifBlank { "—" })
                InfoRow("مقصد", service.destination.ifBlank { "—" })
                InfoRow("سرنشین / نام", service.passengers.ifBlank { "—" })
                InfoRow("شماره درخواست", service.requestNumber.ifBlank { "—" })
                InfoRow("تعداد عوارضی", service.tollCount.toString())
            }

            if (service.missionFood > 0 || service.missionToll > 0 || service.missionFine > 0) {
                SurfaceCard {
                    Text("اقلام مأموریت", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("هزینه غذا", "${amount(service.missionFood)} تومان")
                    InfoRow("عوارضی مأموریت", "${amount(service.missionToll)} تومان")
                    InfoRow("جریمه مأموریت", "${amount(service.missionFine)} تومان")
                }
            }

            SurfaceCard {
                InfoRow(
                    "درآمد این سرویس",
                    "${amount(service.income)} تومان",
                    emphasis = true,
                    valueColor = MaterialTheme.colorScheme.primary,
                )
                if (service.km > 0) {
                    InfoRow("درآمد هر کیلومتر", "${amount(service.income / service.km)} تومان")
                }
                if (service.hours > 0) {
                    InfoRow("درآمد هر ساعت", "${amount(service.income / service.hours)} تومان")
                }
            }

            if (service.attachmentPath.isNotBlank()) {
                val toast = LocalToast.current
                SurfaceCard {
                    Text("پیوست عکس", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    AttachmentPreviewOpenable(
                        service.attachmentPath,
                        onOpenFailed = { toast.show("نمایش عکس ممکن نشد") },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) {
                    Icon(Icons.Outlined.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("ویرایش", "Edit"))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("حذف", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(tr("بستن", "Close"))
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
    modifier: Modifier = Modifier,
) {
    val base = modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight)
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = if (onClick == null) base else base.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
