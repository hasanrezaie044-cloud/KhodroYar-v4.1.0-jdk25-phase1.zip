package com.khodroyar.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.backup.RecordAttachmentFiles
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.db.RecordAttachmentEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.InfoRow
import java.io.File

@Composable
fun MaintenanceDetails(repository: AppRepository, item: MaintenanceEntity, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") }
            Text("جزئیات تعمیر", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onEdit, shape = FieldShape) { Icon(Icons.Outlined.Edit, null); Text("ویرایش") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow("نوع", if (item.type == MaintenanceType.OTHER.wire) item.typeText else MaintenanceType.fromWire(item.type).label)
            InfoRow("شرح", item.description.ifBlank { "—" })
            InfoRow("هزینه", "${item.cost.toLong()} تومان")
            InfoRow("کیلومتر", if (item.km > 0) "${item.km.toLong()} کیلومتر" else "—")
            InfoRow("قطعات تعویض‌شده", item.replacementParts.ifBlank { "—" })
            InfoRow("یادداشت", item.notes.ifBlank { "—" })
            InfoRow("تاریخ", item.date)
            if (item.attachmentPath.isNotBlank()) {
                val bitmap = remember(item.attachmentPath) { runCatching { BitmapFactory.decodeFile(item.attachmentPath)?.asImageBitmap() }.getOrNull() }
                if (bitmap != null) Image(bitmap, "پیوست تعمیر", Modifier.fillMaxWidth().height(220.dp))
                Button(onClick = {
                    val file = File(item.attachmentPath)
                    RecordAttachmentFiles.restoreToGallery(context, RecordAttachmentEntity(
                        id = "legacy-${item.id}", recordType = "maintenance", recordId = item.id,
                        filePath = item.attachmentPath, mimeType = when (file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }, addedAt = 0L,
                    ))
                }, shape = FieldShape) { Icon(Icons.Outlined.SaveAlt, null); Text("بازگردانی تصویر قدیمی به گالری") }
            }
            RecordAttachmentsPanel(repository, "maintenance", item.id)
            Spacer(Modifier.height(12.dp))
        }
    }
}
