package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.*
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.ui.components.*

@Composable
internal fun MaintenanceForecastCard(
    type: MaintenanceType, due: Analytics.ServiceDue, pace: DrivingPace?, today: String,
    configured: Boolean, onRecord: () -> Unit, onSettings: () -> Unit,
) {
    val color = when { configured && due.isOverdue -> MaterialTheme.colorScheme.error; configured && due.isDueSoon -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
    val eta = MaintenanceForecast.eta(due.remainingKm, pace, today, configured)
    SurfaceCard(modifier = Modifier.testTag("maintenance-${type.wire}"), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(when (type) { MaintenanceType.OIL_CHANGE -> Icons.Outlined.OilBarrel; MaintenanceType.SPARK_PLUG -> Icons.Outlined.Bolt; MaintenanceType.BRAKE_PAD -> Icons.Outlined.DiscFull; else -> Icons.Outlined.Build }, tint = color, size = 36.dp)
            Text(type.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (configured && (due.isOverdue || due.isDueSoon)) StatusPill(if (due.isOverdue) tr("موعد گذشته", "Overdue") else tr("نزدیک موعد", "Due soon"), color = color)
        }
        Spacer(Modifier.height(12.dp))
        Text(when {
            !configured -> tr("موعد تنظیم نشده", "Target not configured")
            due.remainingKm == null -> tr("کیلومتر خودرو مشخص نیست", "Current odometer is missing")
            due.isOverdue -> "${money(kotlin.math.abs(due.remainingKm ?: 0.0))} " + tr("کیلومتر از موعد گذشته", "km overdue")
            else -> "${money(due.remainingKm ?: 0.0)} " + tr("کیلومتر تا موعد", "km remaining")
        }, style = MaterialTheme.typography.titleMedium, color = color)
        if (configured) {
            Spacer(Modifier.height(4.dp))
            Text(tr("موعد کیلومتر", "Target odometer") + ": ${money(due.nextKm)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (eta != null) Text(tr("حدود", "About") + " ${eta.days} " + tr("روز", "days") + " · ${eta.date}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("maintenance-eta-${type.wire}"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRecord, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("maintenance-record-${type.wire}")) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("ثبت تعویض", "Record replacement")) }
            TextButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("تنظیم موعد", "Set target")) }
        }
    }
}
