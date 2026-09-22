package com.khodroyar.app.ui.screens

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
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import kotlinx.coroutines.launch

/** Maintenance + mileage based oil-change tracker. */
@Composable
fun MaintenanceScreen(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val year = remember { Jalali.currentYear() }
    val records by repository.maintenances().collectAsState(initial = emptyList())
    val cost by repository.maintenanceCost("$year/").collectAsState(initial = 0.0)
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())
    val highestServiceKm by produceState(0.0, records, settings.oilCurrentKm) {
        value = maxOf(settings.oilCurrentKm, repository.highestKm())
    }
    val rates = remember(ratesByYear, year) { ratesByYear.forYear(year) }
    val lastOil = records.filter { it.type == MaintenanceType.OIL_CHANGE.wire }.maxByOrNull { it.km }
    val oilDue = remember(lastOil, highestServiceKm, rates, settings.oilNextKm) {
        Analytics.oilChangeDue(lastOil, highestServiceKm, rates, settings.oilNextKm)
    }

    var showForm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MaintenanceEntity?>(null) }

    if (showForm) {
        MaintenanceForm(repository, settings, rates) { showForm = false }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            MetricPair(
                first = { m -> MetricCard("کیلومتر فعلی", money(highestServiceKm), Icons.Outlined.Speed, m, compact = true) },
                second = { m -> MetricCard("کیلومتر بعدی روغن", if (settings.oilNextKm > 0) money(settings.oilNextKm) else "—", Icons.Outlined.OilBarrel, m, compact = true) },
            )
            Spacer(Modifier.height(AppDimens.gap))
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.OilBarrel, null, tint = if (oilDue.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(tr("تعویض روغن", "Oil change"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                settings.oilNextKm <= 0 && oilDue.lastKm == null -> "کیلومتر بعدی را ثبت کنید"
                                oilDue.isOverdue -> "موعد تعویض گذشته است؛ ${money(-(oilDue.remainingKm ?: 0.0))} کیلومتر"
                                else -> "تا موعد بعدی ${money(oilDue.remainingKm ?: 0.0)} کیلومتر باقی مانده"
                            },
                            color = if (oilDue.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("هر ${money(oilDue.intervalKm)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (records.isEmpty()) {
            EmptyState("سابقه‌ای ثبت نشده", "تعویض روغن و تعمیرات را ثبت کنید تا یادآور کیلومتری کار کند.", Icons.Outlined.Build, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                items(records, key = { it.id }) { item ->
                    Surface(
                        shape = FieldShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.type == MaintenanceType.OIL_CHANGE.wire) Icons.Outlined.OilBarrel else Icons.Outlined.Build, null, tint = MaterialTheme.colorScheme.secondary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(MaintenanceType.fromWire(item.type).label.let { if (item.typeText.isNotBlank() && item.type == MaintenanceType.OTHER.wire) item.typeText else it }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(item.date, if (item.km > 0) "کیلومتر ${money(item.km)}" else "", item.description).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(amount(item.cost), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            IconButton(onClick = { pendingDelete = item }) { Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        SurfaceCard(modifier = Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)) {
            Button(
                onClick = { showForm = true },
                modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                shape = FieldShape,
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("ثبت تعمیرات", "Add Maintenance"))
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "حذف سابقه تعمیر", message = "رکورد ${item.date} حذف شود?", confirmLabel = "حذف", destructive = true,
            onConfirm = { scope.launch { repository.deleteMaintenance(item.id); toast.show("سابقه تعمیر حذف شد"); pendingDelete = null } },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun MaintenanceForm(repository: AppRepository, settings: AppSettings, rates: com.khodroyar.app.core.rates.Rates, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var date by remember { mutableStateOf(Jalali.todayString()) }
    var type by remember { mutableStateOf(MaintenanceType.OIL_CHANGE) }
    var typeText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var currentKmText by remember { mutableStateOf(if (settings.oilCurrentKm > 0) settings.oilCurrentKm.trimNumber().digitsOnly() else "") }
    var nextKmText by remember { mutableStateOf(if (settings.oilNextKm > 0) settings.oilNextKm.trimNumber().digitsOnly() else "") }
    var description by remember { mutableStateOf("") }
    var carType by remember { mutableStateOf(rates.defaultCarType) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).clearFocusOnTap().padding(horizontal = AppDimens.screenPadding).padding(top = AppDimens.gutter, bottom = AppDimens.gutter), verticalArrangement = Arrangement.spacedBy(AppDimens.gutter)) {
            FormSection("نوع تعمیر", Icons.Outlined.Build) {
                OptionSelector(label = "", options = MaintenanceType.entries.toList(), selected = type, optionLabel = { it.label }, onSelect = { type = it }, columns = 2)
                if (type == MaintenanceType.OTHER) { Spacer(Modifier.height(AppDimens.gap)); TextFieldR("عنوان تعمیر", typeText, { typeText = it }) }
            }
            FormSection("کیلومتر تعویض روغن", Icons.Outlined.OilBarrel) {
                NumberField("کیلومتر فعلی", currentKmText, { currentKmText = it }, suffix = "کیلومتر")
                Spacer(Modifier.height(AppDimens.gap))
                NumberField("کیلومتر بعدی", nextKmText, { nextKmText = it }, suffix = "کیلومتر")
                Spacer(Modifier.height(6.dp))
                Text("اپ با سرویس‌های ثبت‌شده کیلومتر فعلی را به‌روز می‌کند و وقتی به کیلومتر بعدی برسید، هشدار می‌دهد.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            FormSection("تاریخ، هزینه و توضیحات", Icons.Outlined.Event) {
                JalaliDateField("تاریخ", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap)); MoneyField("هزینه", costText, { costText = it })
                Spacer(Modifier.height(AppDimens.gap)); TextFieldR("توضیحات", description, { description = it }, singleLine = false, imeAction = ImeAction.Done)
            }
            FormSection("خودرو", Icons.Outlined.DirectionsCar) {
                SegmentedSelector(options = CarType.entries.toList(), selected = carType, label = { it.label }, onSelect = { carType = it })
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppDimens.screenPadding, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(AppDimens.buttonHeight), shape = FieldShape) { Text(tr("انصراف", "Cancel")) }
                Button(onClick = {
                    scope.launch {
                        val current = currentKmText.asDouble()
                        val next = nextKmText.asDouble().takeIf { it > 0 } ?: if (type == MaintenanceType.OIL_CHANGE && current > 0) current + rates.oilChangeKmInterval else 0.0
                        if (type == MaintenanceType.OIL_CHANGE) {
                            repository.updateSettings { it.copy(oilCurrentKm = current, oilNextKm = next) }
                        }
                        repository.addMaintenance(date, type.wire, typeText.ifBlank { type.label }, costText.asDouble(), current, carType.wire, description.trim())
                        toast.show("سابقه تعمیر ثبت شد")
                        onClose()
                    }
                }, modifier = Modifier.weight(1f).height(AppDimens.buttonHeight), shape = FieldShape) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}
