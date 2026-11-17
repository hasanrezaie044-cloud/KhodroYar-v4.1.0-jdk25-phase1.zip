package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.data.db.FuelEntity
import com.khodroyar.app.ui.components.InfoRow
import com.khodroyar.app.ui.components.amount
import com.khodroyar.app.ui.components.money
import com.khodroyar.app.ui.components.tr

/** A fuel row opens its saved values first; edit and delete remain explicit follow-up actions. */
@Composable
internal fun FuelDetailDialog(
    fuel: FuelEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("جزئیات سوخت‌گیری", "Fuel entry details")) },
        text = {
            Column {
                InfoRow(tr("تاریخ", "Date"), fuel.date)
                InfoRow(tr("نوع سوخت", "Fuel type"), FuelType.fromWire(fuel.type).label)
                InfoRow(tr("خودرو", "Vehicle"), CarType.fromWire(fuel.carType).label)
                InfoRow(tr("مقدار", "Volume"), "${money(fuel.liters)} ${tr("لیتر", "L")}")
                if (fuel.liters > 0.0) InfoRow(tr("نرخ ثبت‌شده", "Saved unit price"), "${money(fuel.total / fuel.liters)} ${tr("تومان/لیتر", "Toman/L")}")
                InfoRow(tr("هزینهٔ کل", "Total cost"), "${amount(fuel.total)} ${tr("تومان", "Toman")}", emphasis = true, valueColor = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) {
                androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = null)
                Text(tr("ویرایش", "Edit"))
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onDelete) {
                    androidx.compose.material3.Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(tr("حذف", "Delete"), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(tr("بستن", "Close")) }
            }
        },
    )
}
