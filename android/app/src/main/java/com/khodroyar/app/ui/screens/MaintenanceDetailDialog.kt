package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.khodroyar.app.data.repo.AppRepository
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.data.attachments.Attachments
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.ui.components.AttachmentPreviewOpenable
import com.khodroyar.app.ui.components.InfoRow
import com.khodroyar.app.ui.components.amount
import com.khodroyar.app.ui.components.money
import com.khodroyar.app.ui.components.LocalToast
import com.khodroyar.app.ui.components.tr

/** Read-only record inspection. Editing and deletion remain distinct, explicit actions. */
@Composable
internal fun MaintenanceDetailDialog(
    item: MaintenanceEntity,
    repository: AppRepository,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val type = MaintenanceType.fromWire(item.type).label.let {
        if (item.typeText.isNotBlank() && item.type == MaintenanceType.OTHER.wire) item.typeText else it
    }
    val toast = LocalToast.current
    val attachmentOpenFailedMessage = tr("نمایش پیوست ممکن نشد", "Could not open attachment")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("جزئیات تعمیر و نگهداری", "Maintenance details")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow(tr("نوع", "Type"), type)
                InfoRow(tr("تاریخ", "Date"), item.date)
                InfoRow(tr("هزینه", "Cost"), "${amount(item.cost)} ${tr("تومان", "Toman")}", emphasis = true)
                if (item.km > 0) InfoRow(tr("کیلومتر خودرو", "Odometer"), "${money(item.km)} ${tr("کیلومتر", "km")}")
                InfoRow(tr("خودرو", "Vehicle"), CarType.fromWire(item.carType).label)
                if (item.description.isNotBlank()) InfoRow(tr("شرح", "Description"), item.description)
                if (item.replacementParts.isNotBlank()) InfoRow(tr("قطعات تعویضی", "Replaced parts"), item.replacementParts)
                if (item.notes.isNotBlank()) InfoRow(tr("یادداشت", "Notes"), item.notes)
                if (item.attachmentPath.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("پیوست", "Attachment"), style = MaterialTheme.typography.titleSmall)
                    if (Attachments.exists(item.attachmentPath)) {
                        AttachmentPreviewOpenable(item.attachmentPath, onOpenFailed = { toast.show(attachmentOpenFailedMessage) })
                    } else {
                        Text(tr("فایل پیوست در این دستگاه پیدا نشد.", "Attachment file is not available on this device."), color = MaterialTheme.colorScheme.error)
                    }
                }
                RecordAttachmentsPanel(repository, "maintenance", item.id)
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
