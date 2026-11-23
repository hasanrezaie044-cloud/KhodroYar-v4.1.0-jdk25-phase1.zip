package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.finance.InstallmentEditRules
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
    // 5.9.1: dates are validated here so an impossible or free-text date can never be saved
    // (blank paid date = unpaid). The repository repeats the same check.
    val canonicalDue = InstallmentEditRules.canonicalDate(dueDate)
    val canonicalPaid = InstallmentEditRules.paidDate(paidDate)
    val value = amount.asDouble().toLong()
    val canSave = value >= 0 && canonicalDue != null && canonicalPaid != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ویرایش قسط ${row.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("مبلغ قسط", amount, { amount = it })
                JalaliDateField("سررسید", dueDate, { dueDate = it })
                TextFieldR("تاریخ پرداخت (خالی = پرداخت‌نشده)", paidDate, { paidDate = it },
                    modifier = Modifier.testTag("installment-paid-date"))
                if (canonicalPaid == null) {
                    Text("تاریخ پرداخت معتبر نیست؛ مثال: ۱۴۰۵/۰۲/۱۵",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("installment-paid-date-error"))
                }
                if (canonicalDue == null) {
                    Text("تاریخ سررسید معتبر نیست", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                TextFieldR("یادداشت", notes, { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, modifier = Modifier.testTag("installment-save"), onClick = {
                if (canonicalDue != null && canonicalPaid != null && value >= 0) {
                    onSave(row.copy(amount = value, dueDate = canonicalDue, paidDate = canonicalPaid, notes = notes.trim()))
                }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
