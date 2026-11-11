package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.data.db.ServiceTotals
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens

/** The same SQL totals previously displayed on Home, fetched only when this section is open. */
@Composable
internal fun AnnualReportStatistics(repository: AppRepository, year: Int) {
    val prefix = "$year/"
    val totals by repository.serviceTotals(prefix).collectAsState(initial = ServiceTotals())
    val fuelCost by repository.fuelCost(prefix).collectAsState(initial = 0.0)
    val fuelLiters by repository.fuelLiters(prefix).collectAsState(initial = 0.0)
    val maintenanceCost by repository.maintenanceCost(prefix).collectAsState(initial = 0.0)
    val net = totals.totalIncome - fuelCost - maintenanceCost
    Column(Modifier.testTag("report-annual-statistics"), verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
        MetricPair(
            first = { MetricCard(tr("سرویس", "Trips"), money(totals.count), Icons.Outlined.Route, it, compact = true) },
            second = { MetricCard(tr("کیلومتر", "Distance"), money(totals.totalKm), Icons.Outlined.Speed, it, compact = true) },
        )
        MetricPair(
            first = { MetricCard(tr("کارکرد", "Hours"), Analytics.formatHours(totals.totalHours), Icons.Outlined.Schedule, it, compact = true) },
            second = { MetricCard(tr("درآمد", "Income"), amount(totals.totalIncome), Icons.Outlined.Payments, it, compact = true) },
        )
        MetricPair(
            first = { MetricCard(tr("سوخت", "Fuel"), amount(fuelCost), Icons.Outlined.LocalGasStation, it, compact = true) },
            second = { MetricCard(tr("لیتر", "Litres"), money(fuelLiters), Icons.Outlined.WaterDrop, it, compact = true) },
        )
        MetricPair(
            first = { MetricCard(tr("تعمیرات", "Maintenance"), amount(maintenanceCost), Icons.Outlined.Build, it, compact = true) },
            second = { MetricCard(tr("سود خالص", "Net profit"), amount(net), Icons.Outlined.TrendingUp, it, compact = true,
                accent = if (net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
        )
    }
}
