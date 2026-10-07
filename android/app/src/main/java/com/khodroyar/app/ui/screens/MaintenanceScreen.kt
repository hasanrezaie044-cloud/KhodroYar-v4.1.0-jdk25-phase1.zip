package com.khodroyar.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
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
    val allServices by repository.services().collectAsState(initial = emptyList())
    val vehicleKm = settings.vehicleCurrentKm
    var editingVehicleKm by remember { mutableStateOf(false) }
    var vehicleKmText by remember(vehicleKm) {
        mutableStateOf(if (vehicleKm > 0) vehicleKm.trimNumber().digitsOnly() else "")
    }
    val rates = remember(ratesByYear, year) { ratesByYear.forYear(year) }

    // Due status: remaining = nextReplacementKm - vehicleCurrentKm
    val trackedItems by produceState(
        initialValue = emptyList<Pair<MaintenanceType, Analytics.ServiceDue>>(),
        records, allServices, rates, settings,
    ) {
        val types = listOf(
            MaintenanceType.OIL_CHANGE to rates.oilChangeKmInterval,
            MaintenanceType.TIMING_BELT to rates.timingBeltKmInterval,
            MaintenanceType.SPARK_PLUG to rates.sparkPlugKmInterval,
            MaintenanceType.BRAKE_PAD to rates.brakePadKmInterval,
            MaintenanceType.FUEL_FILTER to rates.fuelFilterKmInterval,
        )
        value = types.map { (mt, interval) ->
            mt to repository.maintenanceDue(
                typeWire = mt.wire,
                label = mt.label,
                interval = interval,
                currentOdometer = settings.vehicleCurrentKm,
                nextKmOverride = settings.nextKmFor(mt.wire),
                baseCurrentKm = settings.currentKmFor(mt.wire),
            )
        }
    }

    var selectedYear by remember { mutableStateOf(year) }
    val yearPrefix = "$selectedYear/"
    val yearRecords = remember(records, yearPrefix) {
        records.filter { it.date.startsWith(yearPrefix) }
    }
    val yearCost = remember(yearRecords) { yearRecords.sumOf { it.cost } }

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MaintenanceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<MaintenanceEntity?>(null) }
    var editingTracked by remember { mutableStateOf<MaintenanceType?>(null) }

    if (showForm) {
        MaintenanceForm(repository, settings, rates) { showForm = false }
        return
    }
    if (editing != null) {
        MaintenanceForm(repository, settings, rates, existing = editing) { editing = null }
        return
    }
    if (editingTracked != null) {
        val mt = editingTracked!!
        TrackedServiceKmEditor(
            type = mt,
            intervalKm = Analytics.intervalFor(mt.wire, rates),
            settings = settings,
            due = trackedItems.firstOrNull { it.first == mt }?.second,
            onClose = { editingTracked = null },
            onSave = { current, next ->
                scope.launch {
                    repository.updateSettings { it.withTrackedKm(mt.wire, current, next) }
                    toast.show("${mt.label}: کیلومتر ذخیره شد")
                    editingTracked = null
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppDimens.screenPadding,
                end = AppDimens.screenPadding,
                top = 8.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
        ) {
            item {
                MetricPair(
                    first = { m ->
                        MetricCard(
                            "کیلومتر فعلی خودرو",
                            money(settings.vehicleCurrentKm),
                            Icons.Outlined.Speed,
                            m,
                            compact = true,
                            onClick = { editingVehicleKm = true },
                        )
                    },
                    second = { m -> MetricCard("هزینه سال $selectedYear", amount(yearCost), Icons.Outlined.Build, m, compact = true) },
                )
            }
            item {
                val overdueCount = trackedItems.count { it.second.isOverdue }
                val summary = when {
                    overdueCount > 0 -> "$overdueCount مورد از موعد گذشته — برای تنظیم ضربه بزنید"
                    else -> "برای تنظیم کیلومتر فعلی/بعدی روی هر مورد ضربه بزنید"
                }
                Collapsible(
                    title = tr("وضعیت سرویس‌های دوره‌ای", "Periodic service status"),
                    summary = summary,
                    initiallyExpanded = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.tightGap)) {
                        trackedItems.forEach { (itemType, due) ->
                            Surface(
                                shape = FieldShape,
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (due.isOverdue) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingTracked = itemType },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        when (itemType) {
                                            MaintenanceType.OIL_CHANGE -> Icons.Outlined.OilBarrel
                                            MaintenanceType.TIMING_BELT -> Icons.Outlined.Settings
                                            MaintenanceType.SPARK_PLUG -> Icons.Outlined.Bolt
                                            MaintenanceType.BRAKE_PAD -> Icons.Outlined.Warning
                                            else -> Icons.Outlined.FilterAlt
                                        },
                                        null,
                                        tint = when {
                                            due.isOverdue -> MaterialTheme.colorScheme.error
                                            due.isDueSoon -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(itemType.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                        val repl = if (due.baseCurrentKm > 0) money(due.baseCurrentKm) else "—"
                                        val nxt = if (due.nextKm > 0) money(due.nextKm) else "—"
                                        Text(
                                            "تعویض: $repl · بعدی: $nxt",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                        Text(
                                            when {
                                                due.remainingKm == null && due.nextKm <= 0 -> "کیلومتر تعویض بعدی را وارد کنید"
                                                due.isOverdue -> "موعد گذشته؛ ${money(-(due.remainingKm ?: 0.0))} کیلومتر بیش از حد"
                                                else -> "باقی‌مانده: ${money(due.remainingKm ?: 0.0)} کیلومتر"
                                            },
                                            color = if (due.isOverdue) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                        )
                                    }
                                    Icon(Icons.Outlined.ChevronLeft, "تنظیم", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedYear -= 1 }) { Icon(Icons.Outlined.ChevronRight, "سال قبل") }
                    Text("سوابق سال $selectedYear", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { selectedYear += 1 }) { Icon(Icons.Outlined.ChevronLeft, "سال بعد") }
                }
            }
            if (yearRecords.isEmpty()) {
                item {
                    EmptyState(
                        "سابقه‌ای در سال $selectedYear نیست",
                        "با دکمه پایین، تعویض روغن و تعمیرات را ثبت کنید.",
                        Icons.Outlined.Build,
                    )
                }
            } else {
                item {
                    Collapsible(
                        title = tr("سوابق تعمیرات", "Maintenance records"),
                        summary = "${yearRecords.size} رکورد · ${amount(yearCost)} تومان",
                        initiallyExpanded = true,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                            yearRecords.forEach { item ->
                                MaintenanceRow(
                                    item,
                                    onEdit = { editing = item },
                                    onDelete = { pendingDelete = item },
                                )
                            }
                        }
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 8.dp,
        ) {
            Button(
                onClick = { showForm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                    .height(AppDimens.buttonHeight),
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

    if (editingVehicleKm) {
        AlertDialog(
            onDismissRequest = { editingVehicleKm = false },
            title = { Text("کیلومتر فعلی خودرو") },
            text = {
                Column {
                    Text(
                        "این عدد پایه محاسبه همه سرویس‌های دوره‌ای است. با ثبت سرویس، خودکار افزایش می‌یابد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberField("کیلومتر فعلی", vehicleKmText, { vehicleKmText = it }, suffix = "کیلومتر")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = vehicleKmText.asDouble()
                    scope.launch {
                        repository.updateSettings { it.copy(vehicleCurrentKm = v) }
                        toast.show("کیلومتر فعلی ذخیره شد")
                        editingVehicleKm = false
                    }
                }) { Text("ذخیره") }
            },
            dismissButton = {
                TextButton(onClick = { editingVehicleKm = false }) { Text("انصراف") }
            },
        )
    }
}

@Composable
private fun MaintenanceRow(item: MaintenanceEntity, onEdit: () -> Unit = {}, onDelete: () -> Unit) {
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.type == MaintenanceType.OIL_CHANGE.wire) Icons.Outlined.OilBarrel else Icons.Outlined.Build,
                null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    MaintenanceType.fromWire(item.type).label.let {
                        if (item.typeText.isNotBlank() && item.type == MaintenanceType.OTHER.wire) item.typeText else it
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(item.date, if (item.km > 0) "کیلومتر ${money(item.km)}" else "", item.description)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(amount(item.cost), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "ویرایش") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun MaintenanceForm(
    repository: AppRepository,
    settings: AppSettings,
    rates: com.khodroyar.app.core.rates.Rates,
    existing: MaintenanceEntity? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var date by remember { mutableStateOf(existing?.date ?: Jalali.todayString()) }
    var type by remember { mutableStateOf(MaintenanceType.fromWire(existing?.type) ) }
    var typeText by remember { mutableStateOf(existing?.typeText ?: "") }
    var costText by remember { mutableStateOf(if (existing != null && existing.cost > 0) existing.cost.trimNumber().digitsOnly() else "") }
    var currentKmText by remember { mutableStateOf(
        when {
            existing != null && existing.km > 0 -> existing.km.trimNumber().digitsOnly()
            settings.oilCurrentKm > 0 -> settings.oilCurrentKm.trimNumber().digitsOnly()
            else -> ""
        }
    ) }
    var nextKmText by remember { mutableStateOf(if (settings.oilNextKm > 0) settings.oilNextKm.trimNumber().digitsOnly() else "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var attachmentPath by remember { mutableStateOf(existing?.attachmentPath ?: "") }
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
            val out = java.io.File(dir, "mnt_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            attachmentPath = out.absolutePath
            toast.show("پیوست ذخیره شد")
        } catch (_: Exception) {
            toast.show("ذخیره پیوست انجام نشد")
        }
    }
    var carType by remember { mutableStateOf(rates.defaultCarType) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).clearFocusOnTap().padding(horizontal = AppDimens.screenPadding).padding(top = AppDimens.gutter, bottom = AppDimens.gutter), verticalArrangement = Arrangement.spacedBy(AppDimens.gutter)) {
            FormSection("نوع تعمیر", Icons.Outlined.Build) {
                OptionSelector(label = "", options = MaintenanceType.entries.toList(), selected = type, optionLabel = { it.label }, onSelect = { type = it }, columns = 2)
                if (type == MaintenanceType.OTHER) { Spacer(Modifier.height(AppDimens.gap)); TextFieldR("عنوان تعمیر", typeText, { typeText = it }) }
            }
            if (type in MaintenanceType.TRACKED) {
                FormSection("کیلومتر ${type.label}", Icons.Outlined.Speed) {
                    NumberField("کیلومتر فعلی (هنگام تعویض)", currentKmText, { currentKmText = it }, suffix = "کیلومتر")
                    Spacer(Modifier.height(AppDimens.gap))
                    NumberField("کیلومتر بعدی (موعد)", nextKmText, { nextKmText = it }, suffix = "کیلومتر")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "با ثبت این تعویض، کیلومتر فعلی/بعدی ذخیره می‌شود. کیلومتر سرویس‌های بعدی از باقی‌مانده کم می‌شود.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FormSection("تاریخ، هزینه و توضیحات", Icons.Outlined.Event) {
                JalaliDateField("تاریخ", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap)); MoneyField("هزینه", costText, { costText = it })
                Spacer(Modifier.height(AppDimens.gap)); TextFieldR("توضیحات", description, { description = it }, singleLine = false, imeAction = ImeAction.Done)
            }
            FormSection("پیوست رسید / عکس", Icons.Outlined.AttachFile) {
                if (attachmentPath.isNotBlank()) {
                    Text(java.io.File(attachmentPath).name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("تعویض پیوست") }
                } else {
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text("انتخاب از گالری")
                    }
                }
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
                        if (type in MaintenanceType.TRACKED) {
                            val resolvedNext = next.takeIf { it > 0 }
                                ?: if (current > 0) current + Analytics.intervalFor(type.wire, rates) else 0.0
                            repository.updateSettings { it.withTrackedKm(type.wire, current, resolvedNext) }
                        }
                        if (existing != null) {
                            repository.updateMaintenance(
                                existing.copy(
                                    date = date,
                                    type = type.wire,
                                    typeText = typeText.ifBlank { type.label },
                                    cost = costText.asDouble(),
                                    km = current,
                                    carType = carType.wire,
                                    description = description.trim(),
                                    attachmentPath = attachmentPath,
                                )
                            )
                            toast.show("سابقه تعمیر ویرایش شد")
                        } else {
                            repository.addMaintenance(date, type.wire, typeText.ifBlank { type.label }, costText.asDouble(), current, carType.wire, description.trim(), attachmentPath)
                            toast.show("سابقه تعمیر ثبت شد")
                        }
                        onClose()
                    }
                }, modifier = Modifier.weight(1f).height(AppDimens.buttonHeight), shape = FieldShape) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}


@Composable
private fun TrackedServiceKmEditor(
    type: MaintenanceType,
    intervalKm: Int,
    settings: AppSettings,
    due: Analytics.ServiceDue?,
    onClose: () -> Unit,
    onSave: (current: Double, next: Double) -> Unit,
) {
    var currentText by remember {
        mutableStateOf(
            settings.currentKmFor(type.wire).takeIf { it > 0 }?.trimNumber()?.digitsOnly() ?: ""
        )
    }
    var nextText by remember {
        mutableStateOf(
            settings.nextKmFor(type.wire).takeIf { it > 0 }?.trimNumber()?.digitsOnly() ?: ""
        )
    }
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
            Text(type.label, style = MaterialTheme.typography.headlineSmall)
            Text(
                "کیلومتر تعویض = کیلومترشمار هنگام آخرین تعویض. کیلومتر تعویض بعدی = موعد بعدی. باقی‌مانده = کیلومتر تعویض بعدی − کیلومتر فعلی خودرو.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FormSection("کیلومتر", Icons.Outlined.Speed) {
                NumberField("کیلومتر تعویض (آخرین)", currentText, { currentText = it }, suffix = "کیلومتر")
                Spacer(Modifier.height(AppDimens.gap))
                NumberField("کیلومتر تعویض بعدی", nextText, { nextText = it }, suffix = "کیلومتر")
                Spacer(Modifier.height(AppDimens.gap))
                if (intervalKm > 0) {
                    OutlinedButton(
                        onClick = {
                            val cur = currentText.asDouble()
                            if (cur > 0) nextText = (cur + intervalKm).trimNumber().digitsOnly()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("محاسبه بعدی از بازه (${money(intervalKm.toDouble())} کیلومتر)")
                    }
                }
            }
            if (due != null) {
                FormSection("وضعیت فعلی", Icons.Outlined.Info) {
                    InfoRow("رانده‌شده از سرویس‌ها", "${money(due.drivenKm)} کیلومتر")
                    InfoRow("کیلومتر مؤثر", "${money(due.currentKm)} کیلومتر")
                    InfoRow(
                        "باقی‌مانده",
                        due.remainingKm?.let { "${money(it)} کیلومتر" } ?: "—",
                        valueColor = if (due.isOverdue) MaterialTheme.colorScheme.error else null,
                    )
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppDimens.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = {
                        var cur = currentText.asDouble()
                        var next = nextText.asDouble()
                        if (next <= 0 && cur > 0 && intervalKm > 0) next = cur + intervalKm
                        onSave(cur, next)
                    },
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره", "Save")) }
            }
        }
    }
}

