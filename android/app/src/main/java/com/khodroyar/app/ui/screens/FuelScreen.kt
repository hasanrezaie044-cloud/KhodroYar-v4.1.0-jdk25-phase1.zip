package com.khodroyar.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.data.db.FuelEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import kotlinx.coroutines.launch

/**
 * Fuel. Behaviour unchanged: pick the fuel type, type the litres, and the amount is
 * computed live from the rate configured for that type in that Jalali year
 * (`litres × rate`). The amount is never typed by hand.
 *
 * The entry form moved out of a bottom sheet into a full-screen panel, and the fuel
 * type / vehicle pickers are inline cards.
 */
@Composable
fun FuelScreen(
    repository: AppRepository,
    /** Opened from a calendar day: go straight to the entry form with this date. */
    quickDate: String? = null,
    onQuickDone: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var year by remember { mutableStateOf(Jalali.currentYear()) }
    val fuels by repository.fuels().collectAsState(initial = emptyList())
    val cost by repository.fuelCost("$year/").collectAsState(initial = 0.0)
    val liters by repository.fuelLiters("$year/").collectAsState(initial = 0.0)
    val yearTotals by repository.serviceTotals("$year/").collectAsState(initial = com.khodroyar.app.data.db.ServiceTotals())
    val consumption = remember(liters, yearTotals.totalKm) {
        if (yearTotals.totalKm > 0) liters * 100.0 / yearTotals.totalKm else 0.0
    }
    // Recent 2 fill-ups vs previous average for simple spike hint
    val spikeHint = remember(fuels) {
        if (fuels.size < 3) null
        else {
            val recent = fuels.take(2).map { it.liters }.average()
            val older = fuels.drop(2).take(5).map { it.liters }.average()
            if (older > 0 && recent > older * 1.35) recent to older else null
        }
    }
    var showForm by remember { mutableStateOf(quickDate != null) }
    var editing by remember { mutableStateOf<FuelEntity?>(null) }
    var details by remember { mutableStateOf<FuelEntity?>(null) }
    var search by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf("date") }
    val visibleFuels = remember(fuels, year, search, sortMode) {
        val matching = fuels.filter { it.date.startsWith("$year/") &&
            (search.isBlank() || listOf(it.date, it.type, it.station, it.km.toString(), it.liters.toString(), it.total.toString())
                .any { value -> value.contains(search.trim(), ignoreCase = true) }) }
        when (sortMode) { "km" -> matching.sortedByDescending { it.km }; "cost" -> matching.sortedByDescending { it.total }; else -> matching.sortedByDescending { it.date } }
    }
    var pendingDelete by remember { mutableStateOf<FuelEntity?>(null) }

    if (showForm) {
        FuelForm(repository, quickDate, editing) { saved ->
            showForm = false
            editing = null
            if (saved != null) details = saved
            if (quickDate != null) onQuickDone()
        }
        return
    }
    details?.let { item ->
        FuelDetails(repository, item, onBack = { details = null }, onEdit = { details = null; editing = item; showForm = true })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { year -= 1 }) { Icon(Icons.Outlined.ChevronRight, "سال قبل") }
                Text("سوخت · سال $year", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { year += 1 }) { Icon(Icons.Outlined.ChevronLeft, "سال بعد") }
            }
            Spacer(Modifier.height(AppDimens.gap))
            MetricPair(
                first = { m ->
                    MetricCard("لیتر سال $year", money(liters), Icons.Outlined.WaterDrop, m, compact = true)
                },
                second = { m ->
                    MetricCard(
                        "هزینه سال $year", amount(cost), Icons.Outlined.LocalGasStation, m,
                        compact = true, accent = MaterialTheme.colorScheme.secondary,
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Text(tr("مصرف تقریبی", "Approx. consumption"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (yearTotals.totalKm > 0)
                        tr(
                            "${"%.1f".format(consumption)} لیتر در ۱۰۰ کیلومتر",
                            "${"%.1f".format(consumption)} L/100km",
                        )
                    else
                        "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (spikeHint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr(
                            "توجه: دو سوخت اخیر (${"%.0f".format(spikeHint.first)} لیتر میانگین) بالاتر از قبل است.",
                            "Note: last two fill-ups average higher than earlier ones.",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("جست‌وجوی سوخت") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("date" to "تاریخ", "km" to "کیلومتر", "cost" to "هزینه").forEach { (key, label) ->
                    FilterChip(modifier = Modifier.weight(1f), selected = sortMode == key, onClick = { sortMode = key }, label = { Text(label, maxLines = 1) })
                }
            }
        }
        if (visibleFuels.isEmpty()) {
            EmptyState(
                "سوختی ثبت نشده",
                "",
                Icons.Outlined.LocalGasStation,
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                // The saved records are the part worth folding away — not the entry form.
                item {
                    Collapsible(
                        title = tr("سوخت‌های ثبت‌شده", "Recorded fuel"),
                        summary = "${visibleFuels.size} رکورد · ${amount(visibleFuels.sumOf { it.total })} تومان",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                            visibleFuels.forEach { fuel ->
                                FuelRow(fuel, onClick = { details = fuel }, onEdit = { editing = fuel; showForm = true }, onDelete = { pendingDelete = fuel })
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { showForm = true },
            modifier = Modifier
                .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp)
                .fillMaxWidth()
                .heightIn(min = AppDimens.buttonHeight),
            shape = FieldShape,
        ) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("ثبت سوخت", "Add Fuel")) }
    }

    pendingDelete?.let { fuel ->
        ConfirmDialog(
            title = "حذف سوخت",
            message = "رکورد ${fuel.date} با ${money(fuel.liters)} لیتر حذف شود؟",
            confirmLabel = "حذف",
            destructive = true,
            onConfirm = {
                scope.launch {
                    repository.deleteFuel(fuel.id)
                    toast.show("رکورد سوخت حذف شد")
                    pendingDelete = null
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun FuelRow(fuel: FuelEntity, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.LocalGasStation, null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    "${money(fuel.liters)} لیتر · ${FuelType.fromWire(fuel.type).label}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    fuel.date,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                amount(fuel.total),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "ویرایش") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FuelForm(repository: AppRepository, initialDate: String?, editing: FuelEntity? = null, onSaved: (FuelEntity?) -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())

    var date by remember(editing?.id) { mutableStateOf(editing?.date ?: initialDate ?: Jalali.todayString()) }
    var fuelType by remember(editing?.id) { mutableStateOf(editing?.let { FuelType.fromWire(it.type) } ?: FuelType.GOV) }
    var carType by remember(editing?.id) { mutableStateOf(editing?.let { CarType.fromWire(it.carType) } ?: CarType.SOREN) }
    var litersText by remember(editing?.id) { mutableStateOf(editing?.liters?.trimNumber()?.digitsOnly().orEmpty()) }
    var kmText by remember(editing?.id) { mutableStateOf(editing?.km?.takeIf { it > 0 }?.trimNumber()?.digitsOnly().orEmpty()) }
    var station by remember(editing?.id) { mutableStateOf(editing?.station.orEmpty()) }

    val year = Jalali.parse(date)?.year ?: Jalali.currentYear()
    val rates = remember(ratesByYear, year) { ratesByYear.forYear(year) }
    LaunchedEffect(rates.defaultCarType) { carType = rates.defaultCarType }

    val rate = rates.fuelPriceFor(fuelType)
    val litersValue = litersText.asDouble()
    val total = litersValue * rate

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
            FormSection("نوع سوخت", Icons.Outlined.LocalGasStation) {
                OptionSelector(
                    label = "",
                    options = FuelType.entries.toList(),
                    selected = fuelType,
                    optionLabel = { it.label },
                    optionHint = { "${money(rates.fuelPriceFor(it))} / لیتر" },
                    onSelect = { fuelType = it },
                    columns = 2,
                )
            }
            FormSection("مقدار و تاریخ", Icons.Outlined.Event) {
                JalaliDateField("تاریخ", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap))
                NumberField("لیتر", litersText, { litersText = it }, suffix = "لیتر", imeAction = ImeAction.Done)
                Spacer(Modifier.height(AppDimens.gap))
                NumberField("کیلومترشمار", kmText, { kmText = it }, suffix = "کیلومتر")
                Spacer(Modifier.height(AppDimens.gap))
                TextFieldR("جایگاه سوخت (اختیاری)", station, { station = it })
            }
            FormSection("مبلغ محاسبه‌شده", Icons.Outlined.Calculate) {
                InfoRow("نرخ ${fuelType.label} (سال $year)", "${money(rate)} تومان")
                InfoRow("${money(litersValue)} لیتر × ${money(rate)}", "${money(total)} تومان")
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("مبلغ نهایی", "Final Amount"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${money(total)} تومان",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            FormSection("خودرو", Icons.Outlined.DirectionsCar) {
                SegmentedSelector(
                    options = CarType.entries.toList(),
                    selected = carType,
                    label = { it.label },
                    onSelect = { carType = it },
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = {
                        if (litersValue <= 0.0) { toast.show("مقدار لیتر را وارد کنید"); return@Button }
                        scope.launch {
                            val km = kmText.asDouble()
                            val saved = if (editing != null) {
                                editing.copy(date = date, type = fuelType.wire, liters = litersValue, total = total, carType = carType.wire, km = km, station = station.trim()).also { repository.updateFuel(it) }
                            } else {
                                val id = repository.addFuel(date, fuelType.wire, litersValue, total, carType.wire, km, station.trim())
                                FuelEntity(id, date, fuelType.wire, litersValue, total, carType.wire, java.time.Instant.now().toString(), km, station.trim())
                            }
                            toast.show(if (editing == null) "سوخت ثبت شد · ${money(total)} تومان" else "سوخت ویرایش شد · ${money(total)} تومان")
                            onSaved(saved)
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره سوخت", "Save Fuel")) }
            }
        }
    }
}
