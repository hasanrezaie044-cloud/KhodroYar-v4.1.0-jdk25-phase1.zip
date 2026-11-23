package com.khodroyar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.khodroyar.app.core.records.DetailTone
import com.khodroyar.app.core.records.DetailValue
import com.khodroyar.app.core.records.RecordDetail
import com.khodroyar.app.core.records.RecordDetails
import com.khodroyar.app.core.records.RecordKey
import com.khodroyar.app.core.records.RecordKind
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.appAccents
import com.khodroyar.app.ui.theme.heroBrush
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Icon per record kind, shared by details pages, booklets and list rows. */
val RecordKind.icon: ImageVector get() = when (this) {
    RecordKind.SERVICE -> Icons.Outlined.Route
    RecordKind.FUEL -> Icons.Outlined.LocalGasStation
    RecordKind.MAINTENANCE -> Icons.Outlined.Build
    RecordKind.EXPENSE -> Icons.Outlined.ShoppingBag
    RecordKind.INCOME -> Icons.Outlined.Payments
    RecordKind.LOAN -> Icons.Outlined.AccountBalanceWallet
    RecordKind.INSTALLMENT -> Icons.Outlined.EventAvailable
    RecordKind.INSURANCE -> Icons.Outlined.Shield
}

@Composable
fun DetailTone.color(): Color = when (this) {
    DetailTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DetailTone.GOOD -> appAccents.positive
    DetailTone.WARNING -> Color(0xFFB7791F)
    DetailTone.DANGER -> MaterialTheme.colorScheme.error
}

private sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val detail: RecordDetail?) : DetailState
}

/**
 * The one details page used by every module (5.9.0).
 *
 * It is opened with a [RecordKey] only, and reads the record by that primary key through
 * [AppRepository.recordDetail]; edits made anywhere re-render it, and if the row is
 * deleted the page closes itself. Edit is delegated to the module that owns the form
 * ([onEdit]) so it always updates the original row; delete goes through
 * [AppRepository.deleteRecord] after an explicit confirmation.
 */
@Composable
fun RecordDetailsPage(
    repository: AppRepository,
    key: RecordKey,
    onDismiss: () -> Unit,
    onEdit: ((RecordKey) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val state by remember(key) { repository.recordDetail(key).map { DetailState.Loaded(it) } }
        .collectAsState(initial = DetailState.Loading)
    var confirmDelete by remember(key) { mutableStateOf(false) }
    var deleting by remember(key) { mutableStateOf(false) }

    // A row that vanished (deleted here, by the assistant, or by a restore) closes the page.
    val goneText = tr("این رکورد دیگر وجود ندارد", "This record no longer exists")
    LaunchedEffect(state) {
        if ((state as? DetailState.Loaded)?.detail == null && state is DetailState.Loaded && !deleting) {
            toast.show(goneText)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("record-details"), color = MaterialTheme.colorScheme.surface) {
            val detail = (state as? DetailState.Loaded)?.detail
            Column(Modifier.fillMaxSize()) {
                DetailsTopBar(key.kind, onDismiss)
                if (detail == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(horizontal = AppDimens.screenPadding).padding(bottom = AppDimens.gutter),
                        verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
                    ) {
                        DetailHero(detail)
                        detail.sections.forEach { section ->
                            SurfaceCard(contentPadding = 14.dp) {
                                Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics { heading() })
                                Spacer(Modifier.height(6.dp))
                                section.fields.forEachIndexed { index, field ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    DetailRow(field.label, detailValueText(field.value), field.emphasis)
                                }
                            }
                        }
                        if (detail.notes.isNotBlank()) SurfaceCard(contentPadding = 14.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(tr("یادداشت", "Notes"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(detail.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (detail.legacyAttachmentPath.isNotBlank()) SurfaceCard(contentPadding = 14.dp) {
                            val openFailedText = tr("باز کردن عکس ممکن نشد", "Could not open the photo")
                            Text(tr("عکس ثبت‌شده با رکورد", "Photo saved with the record"), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            AttachmentPreviewOpenable(detail.legacyAttachmentPath,
                                onOpenFailed = { toast.show(openFailedText) })
                        }
                        key.kind.attachmentType?.let { type -> RecordAttachmentsPanel(repository, type, key.id) }
                    }
                    DetailActions(detail, editEnabled = onEdit != null && !deleting,
                        onEdit = { onEdit?.invoke(key) }, onDelete = { confirmDelete = true })
                }
            }
        }
    }

    val current = (state as? DetailState.Loaded)?.detail
    val resetText = tr("قسط بازنشانی شد", "Installment reset")
    val deletedText = tr("رکورد حذف شد", "Record deleted")
    val notDeletedText = tr("حذف تأیید نشد؛ رکورد دست‌نخورده باقی ماند", "Delete was not confirmed; nothing changed")
    if (confirmDelete && current != null) {
        ConfirmDialog(
            title = current.deleteTitle,
            message = current.deleteMessage,
            confirmLabel = current.deleteLabel,
            destructive = true,
            onConfirm = {
                confirmDelete = false
                deleting = true
                scope.launch {
                    val ok = runCatching { repository.deleteRecord(key) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .getOrDefault(false)
                    if (ok) {
                        toast.show(if (key.kind == RecordKind.INSTALLMENT) resetText else deletedText)
                        if (key.kind == RecordKind.INSTALLMENT) deleting = false else onDismiss()
                    } else {
                        deleting = false
                        toast.show(notDeletedText)
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailsTopBar(kind: RecordKind, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("record-details-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("بازگشت", "Back"))
        }
        Text(tr("جزئیات ${kind.title}", "Details"), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
    }
}

/** Document-style header: kind seal, title, Jalali date with weekday, status and amount. */
@Composable
private fun DetailHero(detail: RecordDetail) {
    GradientCard(brush = heroBrush(), contentPadding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.12f)) {
                Icon(detail.key.kind.icon, null, Modifier.padding(10.dp).size(24.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(detail.key.kind.title, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
                Text(detail.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("record-details-title"))
            }
        }
        if (detail.subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(detail.subtitle, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
        detail.status?.let {
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, detail.tone.color().copy(alpha = 0.8f))) {
                Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp).testTag("record-details-status"))
            }
        }
        detail.amount?.let { value ->
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.14f))
            Text(detail.amountLabel, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(amount(value), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("record-details-amount"))
                Spacer(Modifier.width(6.dp))
                Text(tr("تومان", "Toman"), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, emphasis: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End)
    }
}

@Composable
fun detailValueText(value: DetailValue): String = when (value) {
    is DetailValue.Text -> value.text
    is DetailValue.Money -> "${amount(value.toman)} ${tr("تومان", "Toman")}"
    is DetailValue.Number -> listOf(money(value.value), value.unit).filter { it.isNotBlank() }.joinToString(" ")
    is DetailValue.Date -> RecordDetails.weekdayDate(value.jalali)
}

@Composable
private fun DetailActions(detail: RecordDetail, editEnabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)) {
            detail.deleteNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                Button(onClick = onEdit, enabled = editEnabled, shape = FieldShape,
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight).testTag("record-details-edit")) {
                    Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(8.dp)); Text(tr("ویرایش", "Edit"))
                }
                OutlinedButton(onClick = onDelete, shape = FieldShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight).testTag("record-details-delete")) {
                    Icon(if (detail.key.kind == RecordKind.INSTALLMENT) Icons.Outlined.RestartAlt else Icons.Outlined.Delete, null,
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp)); Text(detail.deleteLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Compact list row used by month booklets and lists: tapping it opens the record's own
 * details page. No inline edit/delete icons, so a scroll can never delete by accident.
 */
@Composable
fun RecordListRow(
    kind: RecordKind,
    title: String,
    subtitle: String,
    amountText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingNote: String? = null,
) {
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier.fillMaxWidth().clickable(onClickLabel = tr("نمایش جزئیات", "Show details"), onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kind.icon, size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                amountText?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                trailingNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}
