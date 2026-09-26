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
fun FuelScreen(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val year = remember { Jalali.currentYear() }
    val fuels by repository.fuels().collectAsState(initial = emptyList())
    val cost by repository.fuelCost("$year/").collectAsState(initial = 0.0)
    val liters by repository.fuelLiters("$year/").collectAsState(initial = 0.0)
    var showForm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FuelEntity?>(null) }

    if (showForm) {
        FuelForm(repository) { showForm = false }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AppDimens.screenPadding, vertical = 8.dp)) {
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
        }
        if (fuels.isEmpty()) {
            EmptyState(
                "سوختی ثبت نشده",
                "سوخت‌گیری‌ها را ثبت کنید تا هزینه دقیق محاسبه شود.",
                Icons.Outlined.LocalGasStation,
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.gap),
            ) {
                items(fuels, key = { it.id }) { fuel ->
                    Surface(
                        shape = FieldShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.rowMinHeight),
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
                            IconButton(onClick = { pendingDelete = fuel }) {
                                Icon(Icons.Outlined.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
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
                .height(AppDimens.buttonHeight),
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
private fun FuelForm(repository: AppRepository, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())

    var date by remember { mutableStateOf(Jalali.todayString()) }
    var fuelType by remember { mutableStateOf(FuelType.GOV) }
    var carType by remember { mutableStateOf(CarType.SOREN) }
    var litersText by remember { mutableStateOf("") }

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
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("انصراف", "Cancel")) }
                Button(
                    onClick = {
                        if (litersValue <= 0.0) { toast.show("مقدار لیتر را وارد کنید"); return@Button }
                        scope.launch {
                            repository.addFuel(date, fuelType.wire, litersValue, total, carType.wire)
                            toast.show("سوخت ثبت شد · ${money(total)} تومان")
                            onClose()
                        }
                    },
                    modifier = Modifier.weight(1f).height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Text(tr("ذخیره سوخت", "Save Fuel")) }
            }
        }
    }
}
