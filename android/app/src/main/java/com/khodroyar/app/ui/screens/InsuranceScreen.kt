package com.khodroyar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.khodroyar.app.core.records.DocumentCategory
import com.khodroyar.app.core.records.DocumentStatus
import com.khodroyar.app.core.records.RecordKey
import com.khodroyar.app.core.records.RecordKind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.db.InsuranceEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import kotlinx.coroutines.launch

/**
 * Insurance & documents.
 *
 * Brought up to the same shape as the other tools screens (Fuel/Maintenance): a metric
 * row up top, a full-screen form instead of an AlertDialog, delete now asks for
 * confirmation first (it didn't before), and fields go through the shared
 * JalaliDateField/MoneyField/SwitchRow components instead of bare TextFields/Checkbox.
 * No field or DAO call was removed — insert-as-upsert (Room's REPLACE conflict
 * strategy) is unchanged, so editing still saves through the same `insert()` call.
 */
@Composable
fun InsuranceScreen(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val insurance by repository.insurance.observeAll().collectAsState(initial = emptyList())
    var showForm by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<RecordKey?>(null) }

    if (showForm) {
        InsuranceForm(repository, editingId) { showForm = false; editingId = null }
        return
    }

    val today = remember { Jalali.todayString() }
    val soonCutoff = remember(today) { Jalali.addMonths(today, 1) }
    val expiredCount = remember(insurance, today) {
        insurance.count { it.expiryDate.isNotBlank() && it.expiryDate < today }
    }
    val soonCount = remember(insurance, today, soonCutoff) {
        insurance.count { it.expiryDate.isNotBlank() && it.expiryDate in today..soonCutoff }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            MetricPair(
                first = { m ->
                    MetricCard("تعداد رکورد", insurance.size.toString(), Icons.Outlined.Description, m, compact = true)
                },
                second = { m ->
                    MetricCard(
                        if (expiredCount > 0) "منقضی‌شده" else "نزدیک به انقضا (۳۰ روز)",
                        (if (expiredCount > 0) expiredCount else soonCount).toString(),
                        if (expiredCount > 0) Icons.Outlined.ErrorOutline else Icons.Outlined.NotificationsActive,
                        m, compact = true,
                    )
                },
            )
        }
        if (insurance.isEmpty()) {
            EmptyState(
                "بیمه یا مدرکی ثبت نشده",
                "",
                Icons.Outlined.Shield,
                Modifier.weight(1f),
            )
        } else {
            // 5.9.0: booklet covers, most urgent first (expired, then soonest expiry, undated last).
            val sorted = remember(insurance, today) {
                insurance.sortedWith(compareBy<InsuranceEntity> { if (it.expiryDate.isBlank()) 1 else 0 }.thenBy { it.expiryDate })
            }
            val columns = if (LocalConfiguration.current.screenWidthDp < 340) 1 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.weight(1f).testTag("insurance-booklets"),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.gap),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                gridItems(sorted, key = { it.id }) { item ->
                    DocumentBookletCover(item, today) { details = RecordKey(RecordKind.INSURANCE, item.id) }
                }
            }
        }
        SurfaceCard(modifier = Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)) {
            Button(
                onClick = { editingId = null; showForm = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("ثبت بیمه یا مدرک جدید", "Add Insurance / Document"))
            }
        }
    }

    details?.let { key ->
        RecordDetailsPage(
            repository = repository,
            key = key,
            onDismiss = { details = null },
            onEdit = { details = null; editingId = key.id; showForm = true },
        )
    }
}

/**
 * A document as a passport-style booklet cover: binding spine, category, emblem, title,
 * issuer, and an expiry band whose colour carries the status (expired = error colour).
 */
@Composable
private fun DocumentBookletCover(item: InsuranceEntity, today: String, onOpen: () -> Unit) {
    val status = remember(item.expiryDate, today) { DocumentStatus.of(item.expiryDate, today) }
    val category = remember(item.type) { DocumentCategory.of(item.type) }
    val tone = status.tone.color()
    val expired = status.kind == DocumentStatus.Kind.EXPIRED
    val cover = if (expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onCover = if (expired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp),
        color = cover,
        border = BorderStroke(if (expired) 1.5.dp else 1.dp, tone.copy(alpha = if (expired) 0.9f else 0.35f)),
        modifier = Modifier.fillMaxWidth().testTag("insurance-booklet-${item.id}")
            .semantics(mergeDescendants = true) { contentDescription = "${item.type}، ${category.label}، ${status.label}" },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(10.dp).fillMaxHeight().background(onCover.copy(alpha = 0.12f)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(category.label, style = MaterialTheme.typography.labelSmall, color = onCover.copy(alpha = 0.75f), maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = onCover.copy(alpha = 0.08f), border = BorderStroke(1.dp, onCover.copy(alpha = 0.3f))) {
                        Icon(if (category == DocumentCategory.INSURANCE) Icons.Outlined.Shield else Icons.Outlined.Description, null,
                            Modifier.padding(10.dp).size(24.dp), tint = onCover)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(item.type.ifBlank { "مدرک" }, style = MaterialTheme.typography.titleSmall, color = onCover, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                if (item.company.isNotBlank()) Text(item.company, style = MaterialTheme.typography.bodySmall, color = onCover.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                // Expiry band: the most prominent line on the cover.
                Surface(shape = RoundedCornerShape(10.dp), color = tone.copy(alpha = if (expired) 0.18f else 0.12f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(if (item.expiryDate.isBlank()) "بدون تاریخ انقضا" else "انقضا ${item.expiryDate}",
                            style = MaterialTheme.typography.labelLarge, color = if (expired) MaterialTheme.colorScheme.error else onCover,
                            fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(status.label, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1,
                            modifier = Modifier.testTag("insurance-status-${item.id}"))
                    }
                }
            }
        }
    }
}

@Composable
private fun InsuranceForm(repository: AppRepository, editingId: String?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    var type by remember { mutableStateOf("بیمه شخص ثالث") }
    var expiryDate by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var policyNumber by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(editingId == null) }

    LaunchedEffect(editingId) {
        if (editingId != null) {
            repository.insurance.byId(editingId)?.let { item ->
                type = item.type
                expiryDate = item.expiryDate
                costText = if (item.cost > 0) item.cost.toLong().toString() else ""
                company = item.company
                policyNumber = item.policyNumber
                reminder = item.reminder
            }
            loaded = true
        }
    }

    if (!loaded) return

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap()
                .padding(horizontal = AppDimens.screenPadding)
                .padding(top = AppDimens.gutter, bottom = AppDimens.gutter),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
        ) {
            FormSection("نوع بیمه یا مدرک", Icons.Outlined.Shield, collapsible = false) {
                TextFieldR("نوع", type, { type = it })
            }
            FormSection("تاریخ انقضا و یادآوری", Icons.Outlined.Event, collapsible = false) {
                JalaliDateField("تاریخ انقضا", expiryDate, { expiryDate = it })
                Spacer(Modifier.height(AppDimens.gap))
                SwitchRow(
                    "یادآوری پیش از انقضا", "",
                    reminder,
                ) { reminder = it }
            }
            FormSection("هزینه و مشخصات", Icons.Outlined.Description, collapsible = false) {
                MoneyField("هزینه", costText, { costText = it })
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("شرکت بیمه", company, { company = it })
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("شماره بیمه‌نامه", policyNumber, { policyNumber = it }, imeAction = ImeAction.Done)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppDimens.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight), shape = FieldShape) {
                    Text(tr("انصراف", "Cancel"))
                }
                Button(
                    onClick = {
                        scope.launch {
                            val id = editingId ?: java.util.UUID.randomUUID().toString()
                            repository.insurance.insert(
                                InsuranceEntity(
                                    id = id,
                                    type = type.ifBlank { "بیمه" },
                                    expiryDate = expiryDate,
                                    cost = costText.asDouble(),
                                    company = company.trim(),
                                    policyNumber = policyNumber.trim(),
                                    reminder = reminder,
                                    timestamp = System.currentTimeMillis().toString(),
                                )
                            )
                            toast.show(if (editingId != null) "رکورد به‌روزرسانی شد" else "رکورد ثبت شد")
                            onClose()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}
