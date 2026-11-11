package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.data.db.PersonnelEntity
import com.khodroyar.app.data.db.ServiceTotals
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush

/** A daily overview. Yearly totals and recent records live in Reports. */
@Composable
fun DashboardScreen(
    repository: AppRepository,
    settings: AppSettings,
    onOpenReports: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenPersonnel: (PersonnelEntity) -> Unit,
    onTogglePrivacy: () -> Unit,
    onOpenTraffic: () -> Unit = {},
    onAddService: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
) {
    var personnelExpanded by remember { mutableStateOf(false) }
    val today = remember { Jalali.todayString() }
    val ymd = remember(today) { Jalali.parse(today) ?: Jalali.today() }
    val monthPrefix = "${ymd.year}/${ymd.month.toString().padStart(2, '0')}"
    val allServices by repository.services().collectAsState(initial = emptyList())
    val monthTotals by repository.serviceTotals(monthPrefix).collectAsState(initial = ServiceTotals())
    val personnel by repository.personnel().collectAsState(initial = emptyList())
    val maintenanceRecords by repository.maintenances().collectAsState(initial = emptyList())
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())
    val todaySummary = remember(allServices, today) { Analytics.daySummary(allServices, today) }
    val holidayTitle = remember(today) { IranHolidays.titleFor(today) }

    // Maintenance due statuses based on driven km from registered services.
    val maintenanceStates = produceState<List<Analytics.ServiceDue>>(initialValue = emptyList(), allServices, maintenanceRecords, ratesByYear, settings) {
        val rates = ratesByYear.forYear(ymd.year)
        val odo = settings.vehicleCurrentKm
        value = listOf(
            repository.maintenanceDue("oil-change", "تعویض روغن", rates.oilChangeKmInterval, odo, settings.oilNextKm, settings.oilCurrentKm),
            repository.maintenanceDue("timing-belt", "تسمه تایم", rates.timingBeltKmInterval, odo, settings.beltNextKm, settings.beltCurrentKm),
            repository.maintenanceDue("spark-plug", "شمع", rates.sparkPlugKmInterval, odo, settings.sparkNextKm, settings.sparkCurrentKm),
            repository.maintenanceDue("brake-pad", "لنت ترمز", rates.brakePadKmInterval, odo, settings.brakeNextKm, settings.brakeCurrentKm),
            repository.maintenanceDue("fuel-filter", "فیلتر بنزین", rates.fuelFilterKmInterval, odo, settings.filterNextKm, settings.filterCurrentKm),
        )
    }

    val goal = settings.monthlyIncomeGoal
    val goalProgress = if (goal > 0) (monthTotals.totalIncome / goal).coerceIn(0.0, 1.0) else 0.0

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.testTag("dashboard-list"),
            contentPadding = PaddingValues(
                start = AppDimens.screenPadding,
                end = AppDimens.screenPadding,
                top = AppDimens.gutter,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
        ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (settings.displayName.isNotBlank()) "سلام، ${settings.displayName}" else tr("سلام، خوش آمدید", "Welcome back"),
                        style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconBadge(Icons.Outlined.WbSunny, tint = MaterialTheme.colorScheme.tertiary, size = 44.dp)
            }
        }
        item {
            GradientCard(brush = heroBrush(), contentPadding = 18.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("درآمد امروز", "Today's income"), color = Color(0xFFB9CFD0), style = MaterialTheme.typography.labelLarge)
                        if (!settings.privacyMode) Text(today, color = Color(0xFFB9CFD0), style = MaterialTheme.typography.labelSmall)
                    }
                    PrivacyToggle(enabled = settings.privacyMode, onToggle = onTogglePrivacy)
                }
                Spacer(Modifier.height(12.dp))
                Text(amount(todaySummary.totalIncome), color = Color.White, style = MaterialTheme.typography.displaySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("تومان", "Toman"), color = Color(0xFFA5CFC5), style = MaterialTheme.typography.labelMedium)
                holidayTitle?.takeIf { !settings.privacyMode }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("تعطیل رسمی: $it", color = Color(0xFFFFD6B1), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.13f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroStat(tr("سرویس", "Trips"), money(todaySummary.count), Modifier.weight(1f), Icons.Outlined.Route)
                    HeroStat(tr("کیلومتر", "Kilometres"), money(todaySummary.totalKm), Modifier.weight(1f), Icons.Outlined.Speed)
                    HeroStat(tr("ساعت کار", "Hours"), Analytics.formatHours(todaySummary.totalHours), Modifier.weight(1f), Icons.Outlined.Schedule)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardAction(tr("ثبت سرویس", "Add service"), Icons.Outlined.Add, Modifier.weight(1f), onAddService)
                DashboardAction(tr("دستیار داخلی", "Assistant"), Icons.Outlined.AutoAwesome, Modifier.weight(1f), onOpenAssistant)
                DashboardAction(tr("ترافیک مسیر", "Traffic"), Icons.Outlined.DirectionsCar, Modifier.weight(1f), onOpenTraffic)
            }
        }
        if (goal > 0 && !settings.privacyMode) {
            item {
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Outlined.Flag, size = 36.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(tr("هدف این ماه", "This month's goal"), style = MaterialTheme.typography.titleSmall)
                            Text("${amount(monthTotals.totalIncome)} / ${amount(goal)} تومان", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        StatusPill("${money(goalProgress * 100)}٪")
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { goalProgress.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }

        if (!settings.privacyMode) {
            item {
                TrendSection(allServices = allServices, today = today, onOpenReports = onOpenReports)
            }
        }

        /* -------------------------- collapsible periodic service status */
        if (maintenanceStates.value.any { it.nextKm > 0 || it.baseCurrentKm > 0 }) {
            item {
                val dues = maintenanceStates.value
                val overdueCount = dues.count { it.isOverdue }
                val summary = when {
                    overdueCount > 0 -> "$overdueCount مورد از موعد گذشته"
                    else -> {
                        val next = dues.filter { it.remainingKm != null }.minByOrNull { it.remainingKm!! }
                        if (next != null) "نزدیک‌ترین: ${next.label} · ${money(next.remainingKm ?: 0.0)} کیلومتر"
                        else "وضعیت سرویس‌های دوره‌ای"
                    }
                }
                Collapsible(
                    title = tr("وضعیت سرویس‌های دوره‌ای", "Periodic service status"),
                    summary = summary,
                    initiallyExpanded = overdueCount > 0,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.tightGap)) {
                        dues.forEach { due ->
                            Surface(
                                shape = FieldShape,
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (due.isOverdue) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        when (due.label) {
                                            "تعویض روغن" -> Icons.Outlined.OilBarrel
                                            "تسمه تایم" -> Icons.Outlined.Settings
                                            "شمع" -> Icons.Outlined.Bolt
                                            "لنت ترمز" -> Icons.Outlined.Warning
                                            else -> Icons.Outlined.FilterAlt
                                        },
                                        null,
                                        tint = when {
                                            due.isOverdue -> MaterialTheme.colorScheme.error
                                            due.isDueSoon -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(due.label, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                        Text(
                                            buildString {
                                                if (due.baseCurrentKm > 0 || due.nextKm > 0) {
                                                    append("فعلی ${if (due.baseCurrentKm > 0) money(due.baseCurrentKm) else "—"}")
                                                    append(" · بعدی ${if (due.nextKm > 0) money(due.nextKm) else "—"} · ")
                                                }
                                                append(
                                                    when {
                                                        due.remainingKm == null && due.nextKm <= 0 -> "کیلومتر را تنظیم کنید"
                                                        due.isOverdue -> "از موعد ${money(-(due.remainingKm ?: 0.0))} گذشته"
                                                        else -> "باقی ${money(due.remainingKm ?: 0.0)}"
                                                    },
                                                )
                                            },
                                            color = if (due.isOverdue) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text("هر ${money(due.intervalKm)}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (personnel.isNotEmpty()) {
            item {
                CollapsibleSectionTitle(
                    "پرسنل", "${personnel.size}", Icons.Outlined.Group,
                    expanded = personnelExpanded, onToggle = { personnelExpanded = !personnelExpanded }, englishTitle = "Personnel",
                )
            }
            if (personnelExpanded) {
                items(personnel, key = { "person-${it.id}" }) { person ->
                    ActionRow(
                        title = person.name.ifBlank { "پرسنل" },
                        subtitle = listOf(person.personnelCode, person.costCenter).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = Icons.Outlined.Badge,
                        onClick = { onOpenPersonnel(person) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(1.dp).testTag("dashboard-end")) }
        }
    }
}

/* ------------------------------------------------------------ privacy toggle */

@Composable
private fun PrivacyToggle(enabled: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.10f)) {
        IconButton(onClick = onToggle) {
            Icon(if (enabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (enabled) tr("نمایش مبالغ", "Show amounts") else tr("پنهان کردن مبالغ", "Hide amounts"), tint = Color.White, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun DashboardAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/* --------------------------------------------------------------- trend block */

/** Compact native controls; the time axis deliberately advances towards the right in Persian too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendSection(
    allServices: List<com.khodroyar.app.data.db.ServiceEntity>,
    today: String,
    onOpenReports: () -> Unit,
) {
    var scale by rememberSaveable { mutableStateOf(Analytics.TrendScale.MONTH) }
    var metric by rememberSaveable { mutableStateOf(TrendMetric.INCOME) }
    var barView by rememberSaveable { mutableStateOf(false) }
    val points = remember(allServices, scale, today) {
        Analytics.trend(allServices, scale, if (scale == Analytics.TrendScale.YEAR) 4 else 6, today)
    }
    val periodLabels = mapOf(
        Analytics.TrendScale.WEEK to tr("هفتگی", "Weekly"),
        Analytics.TrendScale.MONTH to tr("ماهانه", "Monthly"),
        Analytics.TrendScale.YEAR to tr("سالانه", "Yearly"),
    )
    val metricLabels = mapOf(TrendMetric.INCOME to tr("درآمد", "Income"), TrendMetric.KM to tr("کیلومتر", "Distance"),
        TrendMetric.HOURS to tr("ساعت", "Hours"), TrendMetric.COUNT to tr("تعداد", "Trips"))
    SurfaceCard(modifier = Modifier.testTag("home-trend")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("روند عملکرد", "Performance"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenReports) { Text(tr("گزارش", "Reports")) }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            ChartChoice(Analytics.TrendScale.entries.toList(), scale, { periodLabels.getValue(it) }, { scale = it }, "home-period")
            ChartChoice(TrendMetric.entries.toList(), metric, { metricLabels.getValue(it) }, { metric = it }, "home-metric")
            Row {
                IconToggleButton(checked = !barView, onCheckedChange = { barView = false }, modifier = Modifier.testTag("home-line-toggle")) {
                    Icon(Icons.Outlined.ShowChart, tr("نمودار خطی", "Line chart"), tint = if (!barView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconToggleButton(checked = barView, onCheckedChange = { barView = true }, modifier = Modifier.testTag("home-bar-toggle")) {
                    Icon(Icons.Outlined.BarChart, tr("نمودار ستونی", "Bar chart"), tint = if (barView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (points.count { !it.isEmpty } < 2) {
            InsufficientData(tr("دادهٔ کافی نیست", "Not enough data"))
        } else if (barView) {
            TrendBarChart(points, metric, modifier = Modifier.testTag("home-bar-chart"), barHeight = 180.dp,
                timelineDirection = LayoutDirection.Ltr, showPointSelector = false)
        } else {
            TrendLineChart(points, metric, modifier = Modifier.testTag("home-line-chart"), height = 180.dp,
                timelineDirection = LayoutDirection.Ltr, showPointSelector = false)
        }
    }
}

@Composable
private fun <T> ChartChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(tag).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(label(selected), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); open = false },
                    trailingIcon = if (option == selected) { { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) } } else null)
            }
        }
    }
}
