package com.khodroyar.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * Dashboard.
 *
 * Every metric the app already had is preserved. What is new:
 *  * privacy mode is a real control again — the eye button sits on the hero card and
 *    toggles on each tap, instead of being buried in Settings;
 *  * "مقایسه و روند" now has an actual chart, built from stored records for the last
 *    weeks / months / years, with an explicit insufficient-data state instead of
 *    invented numbers;
 *  * the personnel card opens a full details screen instead of a tooltip;
 *  * every tile row goes through [MetricPair], so neighbouring cards are always the
 *    same size regardless of how long the number inside them is.
 */
@Composable
fun DashboardScreen(
    repository: AppRepository,
    settings: AppSettings,
    onOpenReports: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenPersonnel: (PersonnelEntity) -> Unit,
    onTogglePrivacy: () -> Unit,
) {
    val sectionOpen = remember { mutableStateOf(setOf<String>()) }
    fun toggleSection(key: String) {
        sectionOpen.value = if (key in sectionOpen.value) sectionOpen.value - key else sectionOpen.value + key
    }

    val today = remember { Jalali.todayString() }
    val ymd = remember(today) { Jalali.parse(today) ?: Jalali.today() }
    val yearPrefix = "${ymd.year}/"
    val monthPrefix = "${ymd.year}/${ymd.month.toString().padStart(2, '0')}"
    val previousMonth = remember(today) { Jalali.addMonths(today, -1) }
    val previousMonthPrefix = remember(previousMonth) {
        Jalali.parse(previousMonth)?.let { "${it.year}/${it.month.toString().padStart(2, '0')}" } ?: monthPrefix
    }

    // All services: needed for the year-over-year comparison, which cannot come from a
    // single-year query. The per-period totals still come from the SQL aggregates.
    val allServices by repository.services().collectAsState(initial = emptyList())
    val monthTotals by repository.serviceTotals(monthPrefix).collectAsState(initial = ServiceTotals())
    val previousMonthTotals by repository.serviceTotals(previousMonthPrefix).collectAsState(initial = ServiceTotals())
    val yearTotals by repository.serviceTotals(yearPrefix).collectAsState(initial = ServiceTotals())
    val dailyTotals by repository.dailyTotals(yearPrefix).collectAsState(initial = emptyList())
    val fuelCostYear by repository.fuelCost(yearPrefix).collectAsState(initial = 0.0)
    val fuelLitersYear by repository.fuelLiters(yearPrefix).collectAsState(initial = 0.0)
    val maintenanceYear by repository.maintenanceCost(yearPrefix).collectAsState(initial = 0.0)
    val personnel by repository.personnel().collectAsState(initial = emptyList())
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())

    val yearServices = remember(allServices, yearPrefix) { allServices.filter { it.date.startsWith(yearPrefix) } }
    val todaySummary = remember(yearServices, today) { Analytics.daySummary(yearServices, today) }
    val average = remember(yearServices, today) { Analytics.recentAverage(yearServices, today, 14) }
    val week = remember(dailyTotals, today) { Analytics.weekTotals(dailyTotals, today) }
    val recent = remember(allServices) { allServices.take(5) }
    val holidayTitle = remember(today) { IranHolidays.titleFor(today) }

    // Oil-change status needs two one-shot reads; recomputed whenever records change.
    val oilState = produceState<Analytics.ServiceDue?>(initialValue = null, yearServices, maintenanceYear, ratesByYear) {
        val rates = ratesByYear.forYear(ymd.year)
        value = Analytics.oilChangeDue(repository.latestMaintenance("oil-change"), maxOf(settings.oilCurrentKm, repository.highestKm()), rates, settings.oilNextKm)
    }

    val avgIncomePerService =
        if (monthTotals.count > 0) monthTotals.totalIncome.toDouble() / monthTotals.count else 0.0
    val goal = settings.monthlyIncomeGoal
    val goalProgress = if (goal > 0) (monthTotals.totalIncome / goal).coerceIn(0.0, 1.0) else 0.0
    val netProfitYear = yearTotals.totalIncome - fuelCostYear - maintenanceYear

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
    ) {
        /* ------------------------------------------------------------- today */
        item {
            GradientCard(brush = heroBrush()) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "امروز",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (!settings.privacyMode) {
                            Text(today, color = Color.White, style = MaterialTheme.typography.displayMedium, maxLines = 1)
                        }
                        holidayTitle?.takeIf { !settings.privacyMode }?.let {
                            Text(
                                "تعطیل رسمی: $it",
                                color = Color(0xFFFFC9BF),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Privacy mode: back on the dashboard, one tap on / one tap off.
                    PrivacyToggle(enabled = settings.privacyMode, onToggle = onTogglePrivacy)
                }
                Spacer(Modifier.height(14.dp))
                MetricPair(
                    first = { m ->
                        MetricCard("سرویس امروز", todaySummary.count.toString(), Icons.Outlined.Route, m, compact = true)
                    },
                    second = { m ->
                        MetricCard("درآمد امروز", amount(todaySummary.totalIncome), Icons.Outlined.Payments, m, compact = true)
                    },
                )
                Spacer(Modifier.height(AppDimens.gap))
                MetricPair(
                    first = { m ->
                        MetricCard("کیلومتر امروز", money(todaySummary.totalKm), Icons.Outlined.Speed, m, compact = true)
                    },
                    second = { m ->
                        MetricCard(
                            "کارکرد امروز",
                            "${Analytics.formatHours(todaySummary.totalHours)} ساعت",
                            Icons.Outlined.Schedule, m, compact = true,
                        )
                    },
                )
            }
        }

        if (!settings.privacyMode) {
        /* ------------------------------------------------- comparison & trend */
        item { CollapsibleSectionTitle(
            "مقایسه و روند", icon = Icons.Outlined.TrendingUp,
            expanded = "trend" in sectionOpen.value, onToggle = { toggleSection("trend") },
            englishTitle = "Comparison & Trend"
        ) }
        if ("trend" in sectionOpen.value) {
            item { TrendSection(allServices, today, settings.privacyMode) }
            item {
                SurfaceCard {
                CompareRow("درآمد امروز در برابر میانگین ۱۴ روز", todaySummary.totalIncome.toDouble(), average.avgIncome)
                CompareRow("کیلومتر امروز در برابر میانگین", todaySummary.totalKm, average.avgKm)
                CompareRow("کارکرد امروز در برابر میانگین", todaySummary.totalHours, average.avgHours)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow("هفته جاری", "${week.count} سرویس · ${amount(week.totalIncome)} تومان")
                InfoRow("میانگین درآمد هر سرویس (این ماه)", "${amount(avgIncomePerService)} تومان")
                InfoRow(
                    "ماه گذشته",
                    "${previousMonthTotals.count} سرویس · ${amount(previousMonthTotals.totalIncome)} تومان",
                )
                CompareRow(
                    "درآمد این ماه در برابر ماه گذشته",
                    monthTotals.totalIncome.toDouble(),
                    previousMonthTotals.totalIncome.toDouble(),
                )
            }
        }

        }

        /* --------------------------------------------------- monthly target */
        item {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("هدف درآمد ماهانه", "Monthly Income Goal"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (goal > 0) "${amount(monthTotals.totalIncome)} از ${amount(goal)} تومان"
                            else "هدفی تعیین نشده است — از تنظیمات مشخص کنید",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (goal > 0) {
                        Text(
                            "${(goalProgress * 100).toInt()}٪",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                if (goal > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { goalProgress.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }

        /* -------------------------------------------------- annual figures */
        item { CollapsibleSectionTitle(
            "آمار سال ${ymd.year}", icon = Icons.Outlined.CalendarToday,
            expanded = "annual" in sectionOpen.value, onToggle = { toggleSection("annual") },
            englishTitle = "Annual Statistics"
        ) }
        if ("annual" in sectionOpen.value) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                    MetricPair(
                    first = { m -> MetricCard("سرویس", yearTotals.count.toString(), Icons.Outlined.Route, m) },
                    second = { m -> MetricCard("کیلومتر", money(yearTotals.totalKm), Icons.Outlined.Speed, m) },
                )
                MetricPair(
                    first = { m ->
                        MetricCard("کارکرد", "${Analytics.formatHours(yearTotals.totalHours)} ساعت", Icons.Outlined.Schedule, m)
                    },
                    second = { m -> MetricCard("درآمد", amount(yearTotals.totalIncome), Icons.Outlined.Payments, m) },
                )
                MetricPair(
                    first = { m ->
                        MetricCard("سوخت", amount(fuelCostYear), Icons.Outlined.LocalGasStation, m, accent = MaterialTheme.colorScheme.secondary)
                    },
                    second = { m ->
                        MetricCard("لیتر", money(fuelLitersYear), Icons.Outlined.WaterDrop, m, accent = MaterialTheme.colorScheme.tertiary)
                    },
                )
                MetricPair(
                    first = { m ->
                        MetricCard("تعمیرات", amount(maintenanceYear), Icons.Outlined.Build, m, accent = MaterialTheme.colorScheme.secondary)
                    },
                    second = { m ->
                        MetricCard(
                            "سود خالص", amount(netProfitYear), Icons.Outlined.TrendingUp, m,
                            accent = if (netProfitYear >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }

        }

        /* ------------------------------------------------------ oil status */
        oilState.value?.let { due ->
            item {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.OilBarrel, null,
                            tint = if (due.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(tr("وضعیت تعویض روغن", "Oil Change Status"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    due.lastKm == null -> "سابقه تعویض روغن ثبت نشده است"
                                    due.isOverdue -> "از موعد ${money(-(due.remainingKm ?: 0.0))} کیلومتر گذشته است"
                                    else -> "تا تعویض بعدی ${money(due.remainingKm ?: 0.0)} کیلومتر"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        /* ------------------------------------------------- personnel card */
        item { CollapsibleSectionTitle(
            "پرسنل", if (personnel.isEmpty()) "" else "${personnel.size} نفر", Icons.Outlined.Group,
            expanded = "personnel" in sectionOpen.value, onToggle = { toggleSection("personnel") },
            englishTitle = "Personnel"
        ) }
        if ("personnel" in sectionOpen.value) {
            item {
            if (personnel.isEmpty()) {
                SurfaceCard {
                    Text(tr("اطلاعات پرسنلی ثبت نشده است", "No personnel information has been added"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "از تنظیمات ← پرسنل، اطلاعات را یک بار وارد کنید.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                    personnel.forEach { person ->
                        ActionRow(
                            title = person.name.ifBlank { "پرسنل" },
                            subtitle = listOf(
                                person.personnelCode.takeIf { it.isNotBlank() }?.let { "کد $it" } ?: "",
                                person.costCenter.takeIf { it.isNotBlank() }?.let { "مرکز هزینه $it" } ?: "",
                            ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "مشاهده اطلاعات کامل" },
                            icon = Icons.Outlined.Badge,
                            trailing = "مشاهده",
                            onClick = { onOpenPersonnel(person) },
                        )
                    }
                }
            }
        }

        }

        item { CollapsibleSectionTitle(
            "آخرین سرویس‌ها", icon = Icons.Outlined.History,
            expanded = "recent" in sectionOpen.value, onToggle = { toggleSection("recent") },
            englishTitle = "Recent Services"
        ) }
        if ("recent" in sectionOpen.value) {
            if (recent.isEmpty()) {
            item {
                EmptyState(
                    "هنوز سرویسی ثبت نشده",
                    "از تب «ثبت سرویس» اولین سرویس را وارد کنید تا داشبورد زنده شود.",
                    Icons.Outlined.Route,
                )
            }
            } else {
                items(recent, key = { it.id }) { ServiceRow(it) }
            }
        }
        } // privacyMode: advanced dashboard sections
    }
}

/* ------------------------------------------------------------ privacy toggle */

@Composable
private fun PrivacyToggle(enabled: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.12f),
        onClick = onToggle,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (enabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (enabled) "خاموش کردن حالت حریم خصوصی" else "روشن کردن حالت حریم خصوصی",
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (enabled) "مبالغ پنهان" else "مبالغ نمایان",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/* --------------------------------------------------------------- trend block */

/**
 * The chart for "مقایسه و روند".
 *
 * The scale selector answers the three questions asked of it directly: this week vs the
 * previous weeks, this month vs the previous months, and this year vs the previous
 * years. Buckets are filled from stored services only; when fewer than two buckets
 * contain any record there is nothing meaningful to compare, so an explicit
 * insufficient-data state is shown instead of drawing a flat or fabricated line.
 */
@Composable
private fun TrendSection(
    allServices: List<com.khodroyar.app.data.db.ServiceEntity>,
    today: String,
    privacyMode: Boolean,
) {
    var scale by remember { mutableStateOf(Analytics.TrendScale.MONTH) }
    var metric by remember { mutableStateOf(TrendMetric.INCOME) }

    val buckets = when (scale) {
        Analytics.TrendScale.WEEK -> 6
        Analytics.TrendScale.MONTH -> 6
        Analytics.TrendScale.YEAR -> 4
    }
    val points = remember(allServices, scale, buckets, today) {
        Analytics.trend(allServices, scale, buckets, today)
    }
    val populated = points.count { !it.isEmpty }
    val current = points.lastOrNull()
    val previous = points.getOrNull(points.lastIndex - 1)

    SurfaceCard {
        SegmentedSelector(
            options = listOf(Analytics.TrendScale.WEEK, Analytics.TrendScale.MONTH, Analytics.TrendScale.YEAR),
            selected = scale,
            label = {
                when (it) {
                    Analytics.TrendScale.WEEK -> "هفتگی"
                    Analytics.TrendScale.MONTH -> "ماهانه"
                    Analytics.TrendScale.YEAR -> "سالانه"
                }
            },
            onSelect = { scale = it },
        )
        Spacer(Modifier.height(AppDimens.gap))
        SegmentedSelector(
            options = TrendMetric.entries.toList(),
            selected = metric,
            label = { it.label },
            onSelect = { metric = it },
        )
        Spacer(Modifier.height(AppDimens.gutter))

        if (populated < 2) {
            InsufficientData(
                "برای رسم نمودار روند، به داده‌ی حداقل دو دوره نیاز است.\n" +
                    "تا آن زمان عددی ساخته نمی‌شود؛ با ثبت سرویس‌های بیشتر نمودار خودکار فعال می‌شود."
            )
        } else {
            TrendBarChart(
                points = points,
                metric = metric,
                masked = privacyMode && metric == TrendMetric.INCOME,
            )
            Spacer(Modifier.height(AppDimens.gutter))
            if (current != null && previous != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${metric.label} — ${current.label} در برابر ${previous.label}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DeltaChip(current.metricValue(metric), previous.metricValue(metric))
                }
            }
            val best = points.maxByOrNull { it.metricValue(metric) }
            if (best != null && best.metricValue(metric) > 0.0) {
                InfoRow(
                    "بیشترین ${metric.label}",
                    "${best.label} · " +
                        (if (privacyMode && metric == TrendMetric.INCOME) "••••••"
                        else formatMetric(best.metricValue(metric), metric)) +
                        " ${metric.unit}",
                )
            }
        }
    }
}

@Composable
private fun CompareRow(label: String, current: Double, reference: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        DeltaChip(current, reference)
    }
}
