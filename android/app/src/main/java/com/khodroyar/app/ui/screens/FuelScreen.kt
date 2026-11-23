package com.khodroyar.app.ui.screens

import com.khodroyar.app.core.records.RecordKey
import com.khodroyar.app.core.records.RecordKind
import com.khodroyar.app.core.records.RecordDetails
import androidx.compose.ui.platform.testTag
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
    // Only the latest fill-ups are needed for the spike hint; history is browsed per month.
    val fuels by remember { repository.recentFuels(7) }.collectAsState(initial = emptyList())
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
    var editingFuel by remember { mutableStateOf<FuelEntity?>(null) }
    var details by remember { mutableStateOf<RecordKey?>(null) }

    if (showForm || editingFuel != null) {
        val existing = editingFuel
        FuelForm(repository, quickDate, existing = existing) {
            showForm = false
            editingFuel = null
            if (quickDate != null) onQuickDone()
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // 5.9.0: month-by-month booklet instead of one lifetime list (no per-module search;
        // the global search still finds fuel records).
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = AppDimens.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
        ) {
            item {
            Column {
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
            }
            }
            item {
                MonthBookBrowser(
                    kind = RecordKind.FUEL,
                    year = year,
                    onYearChange = { year = it },
                    months = repository::fuelMonths,
                    totalLabel = tr("هزینهٔ سوخت", "Fuel cost"),
                    showLiters = true,
                    tagPrefix = "fuel",
                ) { prefix ->
                    val monthFuels by remember(prefix) { repository.fuels(prefix) }.collectAsState(initial = null)
                    val list = monthFuels
                    when {
                        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        list.isEmpty() -> MonthEmpty(tr("در این ماه سوختی ثبت نشده است.", "No fuel recorded this month."))
                        else -> list.forEach { fuel ->
                            RecordListRow(
                                kind = RecordKind.FUEL,
                                title = "${money(fuel.liters)} لیتر · ${FuelType.fromWire(fuel.type).label}",
                                subtitle = listOf(RecordDetails.weekdayDate(fuel.date), fuel.station).filter { it.isNotBlank() }.joinToString(" · "),
                                amountText = amount(fuel.total),
                                onClick = { details = RecordKey(RecordKind.FUEL, fuel.id) },
                                modifier = Modifier.testTag("fuel-row-${fuel.id}"),
                            )
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

    details?.let { key ->
        val goneText = tr("این رکورد دیگر وجود ندارد", "This record no longer exists")
        RecordDetailsPage(
            repository = repository,
            key = key,
            onDismiss = { details = null },
            onEdit = {
                scope.launch {
                    // Edit always loads the stored row by id, so the form updates that row.
                    val row = repository.fuelById(key.id)
                    if (row == null) toast.show(goneText)
                    else { details = null; editingFuel = row }
                }
            },
        )
    }
}

@Composable
private fun FuelForm(repository: AppRepository, initialDate: String?, existing: FuelEntity? = null, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())

    var date by remember { mutableStateOf(existing?.date ?: initialDate ?: Jalali.todayString()) }
    var fuelType by remember { mutableStateOf(existing?.let { FuelType.fromWire(it.type) } ?: FuelType.GOV) }
    var carType by remember { mutableStateOf(existing?.let { CarType.fromWire(it.carType) } ?: CarType.SOREN) }
    var litersText by remember { mutableStateOf(existing?.liters?.let { it.trimNumber().digitsOnly() } ?: "") }
    var kmText by remember { mutableStateOf(existing?.km?.takeIf { it > 0 }?.trimNumber()?.digitsOnly().orEmpty()) }
    var station by remember { mutableStateOf(existing?.station.orEmpty()) }

    val year = Jalali.parse(date)?.year ?: Jalali.currentYear()
    val rates = remember(ratesByYear, year) { ratesByYear.forYear(year) }
    LaunchedEffect(rates.defaultCarType, existing?.id) { if (existing == null) carType = rates.defaultCarType }

    val rate = if (existing != null && existing.date == date && existing.type == fuelType.wire && existing.liters > 0.0)
        existing.total / existing.liters else rates.fuelPriceFor(fuelType)
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
                            if (existing == null) {
                                repository.addFuel(date, fuelType.wire, litersValue, total, carType.wire, kmText.asDouble(), station.trim().take(80))
                                toast.show("سوخت ثبت شد · ${money(total)} تومان")
                            } else {
                                repository.updateFuel(existing.copy(date = date, type = fuelType.wire, liters = litersValue, total = total, carType = carType.wire, km = kmText.asDouble(), station = station.trim().take(80)))
                                toast.show("رکورد سوخت ویرایش شد · ${money(total)} تومان")
                            }
                            onClose()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(if (existing == null) tr("ذخیره سوخت", "Save Fuel") else tr("ذخیره ویرایش", "Save changes")) }
            }
        }
    }
}
