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
import com.khodroyar.app.core.analytics.DailyTrends
import com.khodroyar.app.core.analytics.HomeGoalLine
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.data.repo.EntryReceipt
import com.khodroyar.app.core.holidays.IranHolidays
import com.khodroyar.app.core.jalali.Jalali
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
    onEditPersonnel: (PersonnelEntity) -> Unit = onOpenPersonnel,
    onTogglePrivacy: () -> Unit,
    onOpenTraffic: () -> Unit = {},
    onAddService: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    recentEntry: EntryReceipt? = null,
    onEditQuick: () -> Unit = {},
    onUndoQuick: () -> Unit = {},
    onRecordMaintenance: (MaintenanceType) -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
) {
    val today = remember { Jalali.todayString() }
    val ymd = remember(today) { Jalali.parse(today) ?: Jalali.today() }
    val dailyTrendRows by remember(ymd.year) {
        repository.insights.trendDays(Jalali.format(ymd.year - 3, 1, 1), Jalali.format(ymd.year, 12, Jalali.monthLength(ymd.year, 12)))
    }.collectAsState(initial = emptyList())
    val personnel by repository.personnel().collectAsState(initial = emptyList())
    val todaySummary by remember(today) { repository.serviceTotals(today) }.collectAsState(initial = ServiceTotals())
    val holidayTitle = remember(today) { IranHolidays.titleFor(today) }

    // 5.9.0: the monthly target is drawn as a dashed line in the trend chart, not a card.
    val goal = settings.monthlyIncomeGoal

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
                Text(amount(todaySummary.totalIncome), modifier = Modifier.testTag("today-income"), color = Color.White, style = MaterialTheme.typography.displaySmall,
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAddService, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("home-quick-add"), shape = CardShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                    Icon(Icons.Outlined.Bolt, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp)); Text(tr("ثبت سریع", "Quick add"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Outlined.Add, null)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashboardAction(tr("دستیار هوشمند", "Assistant"), Icons.Outlined.AutoAwesome, Modifier.weight(1f), onOpenAssistant)
                    DashboardAction(tr("ترافیک مسیر", "Traffic"), Icons.Outlined.DirectionsCar, Modifier.weight(1f), onOpenTraffic)
                }
            }
        }
        recentEntry?.let { entry -> item { RecentQuickEntryCard(entry, onEditQuick, onUndoQuick) } }
        if (!settings.privacyMode) {
            item {
                TrendSection(dailyRows = dailyTrendRows, today = today, onOpenReports = onOpenReports, monthlyGoal = goal)
            }
        }

        if (personnel.isNotEmpty()) {
            item {
                PersonnelBooklets(personnel, onOpen = onOpenPersonnel, onEdit = onEditPersonnel)
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
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, size = 32.dp)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

/* --------------------------------------------------------------- trend block */

/** Compact native controls; the time axis deliberately advances towards the right in Persian too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendSection(
    dailyRows: List<com.khodroyar.app.data.db.DailyTotals>,
    today: String,
    onOpenReports: () -> Unit,
    monthlyGoal: Double = 0.0,
) {
    var scale by rememberSaveable { mutableStateOf(Analytics.TrendScale.MONTH) }
    var metric by rememberSaveable { mutableStateOf(TrendMetric.INCOME) }
    var barView by rememberSaveable { mutableStateOf(false) }
    val points = remember(dailyRows, scale, today) { DailyTrends.points(dailyRows, scale, today) }
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
        // The monthly income target is only comparable with monthly (and, ×12, yearly) income.
        val goalLine = HomeGoalLine.of(monthlyGoal, metric == TrendMetric.INCOME, scale)
        val goalLabel = when (scale) {
            Analytics.TrendScale.YEAR -> tr("هدف سالانه (۱۲ × ماهانه)", "Yearly target (12 × monthly)")
            else -> tr("هدف ماهانه", "Monthly target")
        }
        if (points.count { !it.isEmpty } < 2) {
            InsufficientData(tr("دادهٔ کافی نیست", "Not enough data"))
        } else if (barView) {
            TrendBarChart(points, metric, modifier = Modifier.testTag("home-bar-chart"), barHeight = 180.dp,
                timelineDirection = LayoutDirection.Ltr, showPointSelector = false, goalValue = goalLine, goalLabel = goalLabel)
        } else {
            TrendLineChart(points, metric, modifier = Modifier.testTag("home-line-chart"), height = 180.dp,
                timelineDirection = LayoutDirection.Ltr, showPointSelector = false, goalValue = goalLine, goalLabel = goalLabel)
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
