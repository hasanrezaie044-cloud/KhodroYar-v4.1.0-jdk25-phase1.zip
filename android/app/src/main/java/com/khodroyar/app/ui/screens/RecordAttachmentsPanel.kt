package com.khodroyar.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.khodroyar.app.data.backup.RecordAttachmentFiles
import com.khodroyar.app.data.db.RecordAttachmentEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.LocalToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add/copy, view, replace, remove and restore-to-gallery for a persisted record image. */
@Composable
fun RecordAttachmentsPanel(repository: AppRepository, recordType: String, recordId: String) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val attachments by repository.attachments(recordType, recordId).collectAsState(initial = emptyList())
    var replacingId by remember(recordType, recordId) { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<RecordAttachmentEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val oldId = replacingId
        replacingId = null
        if (uri != null) scope.launch {
            val copied = withContext(Dispatchers.IO) { RecordAttachmentFiles.import(context, uri) }
            if (copied != null) {
                try {
                    repository.addAttachment(recordType, recordId, copied)
                    // Keep the old image intact until the new private copy is registered.
                    oldId?.let { repository.removeAttachment(it) }
                } catch (_: Exception) {
                    withContext(Dispatchers.IO) { RecordAttachmentFiles.deleteOwned(context, copied.filePath) }
                    toast.show("پیوست ثبت نشد")
                }
            } else toast.show("فایل انتخابی تصویر معتبر نیست یا بزرگ‌تر از حد مجاز است")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("پیوست تصاویر", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            AssistChip(onClick = { replacingId = null; picker.launch("image/*") }, label = { Text("افزودن") }, leadingIcon = {
                Icon(Icons.Outlined.AddPhotoAlternate, null)
            })
        }
        if (attachments.isEmpty()) {
            Text("تصویری پیوست نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(attachments, key = { it.id }) { row ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val bitmap = remember(row.filePath) {
                            runCatching { BitmapFactory.decodeFile(row.filePath)?.asImageBitmap() }.getOrNull()
                        }
                        Surface(
                            modifier = Modifier.clickable { viewing = row },
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            if (bitmap != null) Image(bitmap, "نمایش تصویر پیوست", Modifier.size(112.dp).clip(RoundedCornerShape(14.dp)))
                            else Text("تصویر در دسترس نیست", Modifier.size(112.dp), color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = { replacingId = row.id; picker.launch("image/*") }) {
                            Icon(Icons.Outlined.SwapHoriz, null); Spacer(Modifier.width(4.dp)); Text("تعویض تصویر")
                        }
                        if (RecordAttachmentFiles.canRestoreToGallery()) OutlinedButton(onClick = {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) { runCatching { RecordAttachmentFiles.restoreToGallery(context, row) }.getOrNull() }
                                toast.show(if (saved != null) "تصویر در گالری ذخیره شد" else "ذخیره در گالری انجام نشد؛ پیوست برنامه دست‌نخورده است")
                            }
                        }) {
                            Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(4.dp)); Text("بازگردانی به گالری")
                        }
                        OutlinedButton(onClick = { scope.launch { repository.removeAttachment(row.id) } }) {
                            Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(4.dp)); Text("حذف پیوست")
                        }
                    }
                }
            }
        }
    }

    viewing?.let { attachment ->
        val bitmap = remember(attachment.filePath) {
            runCatching { BitmapFactory.decodeFile(attachment.filePath)?.asImageBitmap() }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("تصویر پیوست") },
            text = {
                if (bitmap == null) Text("تصویر در دسترس نیست.")
                else Image(bitmap, "تصویر رکورد", Modifier.clip(RoundedCornerShape(12.dp)))
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("بستن") } },
        )
    }
}
