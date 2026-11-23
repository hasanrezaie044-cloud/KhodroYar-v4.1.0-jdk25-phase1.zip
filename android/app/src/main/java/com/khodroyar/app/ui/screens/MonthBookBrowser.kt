package com.khodroyar.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.records.MonthBook
import com.khodroyar.app.core.records.RecordKind
import com.khodroyar.app.data.db.MonthAggregate
import com.khodroyar.app.ui.components.*
import kotlinx.coroutines.flow.Flow

/**
 * Month-by-month history booklet (5.9.0), shared by fuel, maintenance and purchases.
 *
 * Overview: twelve month "pages" of the selected Jalali year, built from one SQL aggregate
 * row per month. Opening a page shows that month's summary and records only — the records
 * are collected by [monthRecords] from a `YYYY/MM` prefix query, so no screen ever renders
 * a lifetime list. System back (including predictive back) returns to the overview.
 */
@Composable
fun MonthBookBrowser(
    kind: RecordKind,
    year: Int,
    onYearChange: (Int) -> Unit,
    months: (Int) -> Flow<List<MonthAggregate>>,
    totalLabel: String,
    showLiters: Boolean = false,
    tagPrefix: String,
    monthRecords: @Composable ColumnScope.(prefix: String) -> Unit,
) {
    val rows by remember(year) { months(year) }.collectAsState(initial = emptyList())
    val pages = remember(year, rows) { MonthBook.pages(year, rows) }
    val totals = remember(pages) { MonthBook.totals(pages) }
    var openMonth by rememberSaveable(tagPrefix) { mutableStateOf<Int?>(null) }

    BackHandler(enabled = openMonth != null) { openMonth = null }

    Column(Modifier.fillMaxWidth().testTag("$tagPrefix-monthbook"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val month = openMonth
        if (month == null) {
            YearCover(kind, year, totals, totalLabel, showLiters, tagPrefix)
            MonthGrid(pages, showLiters, tagPrefix) { openMonth = it }
        } else {
            val page = pages.getOrNull(month - 1) ?: MonthBook.Page(year, month, 0, 0.0, 0.0)
            MonthHeader(page, onBack = { openMonth = null }, onShift = { delta ->
                val (y, m) = MonthBook.shift(year, month, delta)
                if (y != year) onYearChange(y)
                openMonth = m
            }, tagPrefix = tagPrefix)
            MonthSummary(page, totalLabel, showLiters)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { monthRecords(page.prefix) }
        }
    }
}

@Composable
private fun YearCover(kind: RecordKind, year: Int, totals: MonthBook.YearTotals, totalLabel: String, showLiters: Boolean, tagPrefix: String) {
    SurfaceCard(contentPadding = 14.dp, modifier = Modifier.testTag("$tagPrefix-year-cover")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kind.icon, size = 34.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(tr("دفتر ماهانهٔ $year", "Monthly book $year"), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Text(tr("${totals.count} رکورد در ${totals.activeMonths} ماه", "${totals.count} records in ${totals.activeMonths} months"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BookStat(totalLabel, "${amount(totals.total)}", Modifier.weight(1f))
            BookStat(tr("میانگین ماه فعال", "Avg. active month"), amount(totals.monthlyAverage), Modifier.weight(1f))
            if (showLiters) BookStat(tr("لیتر", "Litres"), money(totals.liters), Modifier.weight(1f))
        }
    }
}

@Composable
private fun BookStat(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 12 pages; 3 per row (2 on very narrow or large-font screens). Empty months stay visible but quiet. */
@Composable
private fun MonthGrid(pages: List<MonthBook.Page>, showLiters: Boolean, tagPrefix: String, onOpen: (Int) -> Unit) {
    val config = LocalConfiguration.current
    val columns = if (config.screenWidthDp < 340 || config.fontScale > 1.3f) 2 else 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { page -> MonthPage(page, showLiters, Modifier.weight(1f), tagPrefix) { onOpen(page.month) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MonthPage(page: MonthBook.Page, showLiters: Boolean, modifier: Modifier, tagPrefix: String, onOpen: () -> Unit) {
    val current = remember(page.year, page.month) { Jalali.parse(Jalali.todayString())?.let { it.year == page.year && it.month == page.month } == true }
    val description = if (page.isEmpty) "${page.name}: بدون رکورد" else "${page.name}: ${page.count} رکورد"
    Surface(
        shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp),
        color = if (page.isEmpty) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (current) 1.5.dp else 1.dp,
            if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier
            .heightIn(min = 76.dp)
            .alpha(if (page.isEmpty) 0.62f else 1f)
            .testTag("$tagPrefix-month-${page.month}")
            .clickable(onClickLabel = tr("باز کردن ماه", "Open month"), onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Page binding edge.
            Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 10.dp)) {
                Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(2.dp),
                    color = if (page.isEmpty) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) {}
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(page.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (page.isEmpty) {
                    Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(amount(page.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (showLiters && page.liters > 0) "${page.count} · ${money(page.liters)} L" else tr("${page.count} رکورد", "${page.count}"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(page: MonthBook.Page, onBack: () -> Unit, onShift: (Int) -> Unit, tagPrefix: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-month-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text(tr("همهٔ ماه‌ها", "All months"))
        }
        Spacer(Modifier.weight(1f))
        // Chronological arrows: in RTL "previous" sits on the right, as in the calendar.
        IconButton(onClick = { onShift(-1) }, modifier = Modifier.testTag("$tagPrefix-month-prev")) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, tr("ماه قبل", "Previous month"))
        }
        Text("${page.name} ${page.year}", style = MaterialTheme.typography.titleMedium, maxLines = 1,
            modifier = Modifier.semantics { heading() }.testTag("$tagPrefix-month-title"))
        IconButton(onClick = { onShift(1) }, modifier = Modifier.testTag("$tagPrefix-month-next")) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, tr("ماه بعد", "Next month"))
        }
    }
}

@Composable
private fun MonthSummary(page: MonthBook.Page, totalLabel: String, showLiters: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("monthbook-summary")) {
        BookStat(totalLabel, "${amount(page.total)} ${tr("تومان", "Toman")}", Modifier.weight(1.4f))
        BookStat(tr("تعداد", "Count"), money(page.count), Modifier.weight(0.8f))
        if (showLiters) BookStat(tr("لیتر", "Litres"), money(page.liters), Modifier.weight(0.9f))
    }
    if (showLiters && page.liters > 0) {
        Text(tr("میانگین هر لیتر: ${amount(page.total / page.liters)} تومان", "Average per litre: ${amount(page.total / page.liters)}"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared empty state for an opened month without records. */
@Composable
fun MonthEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
}
