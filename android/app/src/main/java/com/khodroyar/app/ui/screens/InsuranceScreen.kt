package com.khodroyar.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
    var pendingDelete by remember { mutableStateOf<InsuranceEntity?>(null) }

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
                "بیمه بدنه، شخص ثالث یا هر مدرک دیگر خودرو را اینجا ثبت کنید تا پیش از انقضا یادآوری شوید.",
                Icons.Outlined.Shield,
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                items(insurance, key = { it.id }) { item ->
                    val isExpired = item.expiryDate.isNotBlank() && item.expiryDate < today
                    Surface(
                        onClick = { editingId = item.id; showForm = true },
                        shape = FieldShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(
                            1.dp,
                            if (isExpired) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Shield, null,
                                tint = if (isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.type, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(
                                        if (item.expiryDate.isNotBlank()) (if (isExpired) "منقضی: " else "انقضا: ") + item.expiryDate else "",
                                        item.company,
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = if (isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (item.cost > 0) {
                                Text(
                                    amount(item.cost),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { pendingDelete = item }) {
                                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        SurfaceCard(modifier = Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)) {
            Button(
                onClick = { editingId = null; showForm = true },
                modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("ثبت بیمه یا مدرک جدید", "Add Insurance / Document"))
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "حذف رکورد",
            message = "رکورد «${item.type}» حذف شود؟",
            confirmLabel = "حذف",
            destructive = true,
            onConfirm = {
                scope.launch {
                    repository.insurance.deleteById(item.id)
                    toast.show("رکورد حذف شد")
                    pendingDelete = null
                }
            },
            onDismiss = { pendingDelete = null },
        )
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
                    "یادآوری پیش از انقضا", "نزدیک به تاریخ انقضا در وضعیت «نزدیک به انقضا» نشان داده می‌شود",
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
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(AppDimens.buttonHeight), shape = FieldShape) {
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
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}
