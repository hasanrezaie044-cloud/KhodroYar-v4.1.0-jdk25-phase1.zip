package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.db.LoanInstallmentEntity
import com.khodroyar.app.ui.components.JalaliDateField
import com.khodroyar.app.ui.components.MoneyField
import com.khodroyar.app.ui.components.TextFieldR
import com.khodroyar.app.ui.components.asDouble
import com.khodroyar.app.ui.components.digitsOnly

@Composable
fun LoanInstallmentEditDialog(row: LoanInstallmentEntity, onDismiss: () -> Unit, onSave: (LoanInstallmentEntity) -> Unit) {
    var amount by remember(row.id) { mutableStateOf(row.amount.toDouble().trimNumber().digitsOnly()) }
    var dueDate by remember(row.id) { mutableStateOf(row.dueDate) }
    var paidDate by remember(row.id) { mutableStateOf(row.paidDate) }
    var notes by remember(row.id) { mutableStateOf(row.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ویرایش قسط ${row.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("مبلغ قسط", amount, { amount = it })
                JalaliDateField("سررسید", dueDate, { dueDate = it })
                TextFieldR("تاریخ پرداخت (خالی = پرداخت‌نشده)", paidDate, { paidDate = it })
                TextFieldR("یادداشت", notes, { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.asDouble().toLong()
                if (value >= 0 && dueDate.isNotBlank()) onSave(row.copy(amount = value, dueDate = dueDate, paidDate = paidDate.trim(), notes = notes.trim()))
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
