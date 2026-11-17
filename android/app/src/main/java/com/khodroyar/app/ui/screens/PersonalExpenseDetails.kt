package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.data.db.PersonalExpenseEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.InfoRow

@Composable
fun PersonalExpenseDetails(repository: AppRepository, item: PersonalExpenseEntity, onBack: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") }
            Text("جزئیات هزینه", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onEdit, shape = FieldShape) { Icon(Icons.Outlined.Edit, null); Text("ویرایش") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow("نوع", PurchaseCategory.fromWire(item.category).label)
            InfoRow("شرح", item.title.ifBlank { "—" })
            InfoRow("مبلغ", "${item.amount.toLong()} تومان")
            InfoRow("تاریخ", item.date)
            InfoRow("کیلومتر مرتبط", if (item.km > 0) "${item.km.toLong()} کیلومتر" else "—")
            InfoRow("یادداشت", item.notes.ifBlank { "—" })
            RecordAttachmentsPanel(repository, "expense", item.id)
        }
    }
}
