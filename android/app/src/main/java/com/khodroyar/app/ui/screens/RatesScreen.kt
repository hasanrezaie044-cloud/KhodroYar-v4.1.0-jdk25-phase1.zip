package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import kotlinx.coroutines.launch

/**
 * Rate engine settings — the critical screen, and the one the migration flattened.
 *
 * Everything the legacy engine needs is editable here: the three kilometre rates, the
 * hourly rate, three fuel prices, the toll rate, night / dawn percentages, the night
 * and dawn boundaries, the vehicle type and the kilometre intervals. Rates are stored
 * PER JALALI YEAR: editing 1405 cannot change how a 1404 service was priced, and a
 * year without its own row inherits the nearest earlier year.
 *
 * "Save + recalculate" re-prices only the services of the selected year, after
 * showing how many records that is and asking for confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatesScreen(repository: AppRepository) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val currentYear = remember { Jalali.currentYear() }
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())

    var year by remember { mutableStateOf(currentYear) }
    val stored = remember(ratesByYear, year) { ratesByYear.forYear(year) }
    val ownYears = remember(ratesByYear) { ratesByYear.years() }
    val inherits = !ownYears.contains(year)

    var dirty by remember { mutableStateOf(false) }
    var nightKm by remember { mutableStateOf("") }
    var holidayKm by remember { mutableStateOf("") }
    var fixedRequestKm by remember { mutableStateOf("") }
    var hourRate by remember { mutableStateOf("") }
    var fuelGov by remember { mutableStateOf("") }
    var fuelSemi by remember { mutableStateOf("") }
    var fuelFree by remember { mutableStateOf("") }
    var toll by remember { mutableStateOf("") }
    var nightPercent by remember { mutableStateOf("") }
    var saharPercent by remember { mutableStateOf("") }
    var nightStart by remember { mutableStateOf("") }
    var saharStart by remember { mutableStateOf("") }
    var saharEnd by remember { mutableStateOf("") }
    var oilInterval by remember { mutableStateOf("") }
    var beltInterval by remember { mutableStateOf("") }
    var carType by remember { mutableStateOf(CarType.SOREN) }
    var bonusTypes by remember { mutableStateOf(Rates.DEFAULT.timeBonusServiceTypes.toSet()) }

    var confirmRecalculate by remember { mutableStateOf(false) }
    var affected by remember { mutableStateOf(0) }

    // Reload the form when the year changes or the stored rates change — unless the
    // user has unsaved edits, which must never be silently thrown away.
    LaunchedEffect(year, stored) {
        if (dirty) return@LaunchedEffect
        nightKm = stored.nightKm.trimNumber()
        holidayKm = stored.holidayKm.trimNumber()
        fixedRequestKm = stored.fixedRequestKm.trimNumber()
        hourRate = stored.hour.trimNumber()
        fuelGov = stored.fuelPriceGov.trimNumber()
        fuelSemi = stored.fuelPriceSemi.trimNumber()
        fuelFree = stored.fuelPriceFree.trimNumber()
        toll = stored.toll.trimNumber()
        nightPercent = stored.nightPercent.trimNumber()
        saharPercent = stored.saharPercent.trimNumber()
        nightStart = stored.nightStartTime
        saharStart = stored.saharStartTime
        saharEnd = stored.saharEndTime
        oilInterval = stored.oilChangeKmInterval.toString()
        beltInterval = stored.timingBeltKmInterval.toString()
        carType = stored.defaultCarType
        bonusTypes = stored.timeBonusServiceTypes.toSet()
    }

    fun build(): Rates = stored.copy(
        nightKm = nightKm.asDouble(),
        holidayKm = holidayKm.asDouble(),
        fixedRequestKm = fixedRequestKm.asDouble(),
        hour = hourRate.asDouble(),
        fuelPriceGov = fuelGov.asDouble(),
        fuelPriceSemi = fuelSemi.asDouble(),
        fuelPriceFree = fuelFree.asDouble(),
        toll = toll.asDouble(),
        defaultCarType = carType,
        oilChangeKmInterval = oilInterval.asInt(),
        timingBeltKmInterval = beltInterval.asInt(),
        nightPercent = nightPercent.asDouble(),
        saharPercent = saharPercent.asDouble(),
        nightStartTime = nightStart.ifBlank { stored.nightStartTime },
        saharStartTime = saharStart.ifBlank { stored.saharStartTime },
        saharEndTime = saharEnd.ifBlank { stored.saharEndTime },
        // An empty selection would silently disable the whole bonus feature, so the
        // legacy default (night + fixed) is kept as the floor.
        timeBonusServiceTypes = bonusTypes.toList().ifEmpty { Rates.DEFAULT.timeBonusServiceTypes },
    )

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppDimens.screenPadding,
            end = AppDimens.screenPadding,
            top = AppDimens.gutter,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                "نرخ‌ها و موتور محاسبه",
                if (inherits) "سال $year نرخ مستقل ندارد و از نزدیک‌ترین سال قبل ارث می‌برد"
                else "سال $year نرخ اختصاصی دارد",
            )
        }
        item {
            Column {
                Text(tr("سال مالی", "Fiscal year"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val years = remember(ownYears, currentYear) {
                    (ownYears + listOf(currentYear - 2, currentYear - 1, currentYear, currentYear + 1))
                        .distinct().sorted()
                }
                ChipRow(years, year, { it.toString() }) {
                    if (dirty) toast.show("تغییرات ذخیره‌نشده رها شد")
                    dirty = false
                    year = it
                }
            }
        }

        item { SectionTitle("نرخ کیلومتر", icon = Icons.Outlined.Speed) }
        item {
            SurfaceCard {
                MoneyField("کیلومتر شب", nightKm, { nightKm = it; dirty = true }, suffix = "تومان به ازای هر کیلومتر")
                Spacer(Modifier.height(10.dp))
                MoneyField("کیلومتر تعطیل", holidayKm, { holidayKm = it; dirty = true }, suffix = "تومان به ازای هر کیلومتر")
                Spacer(Modifier.height(10.dp))
                MoneyField("کیلومتر ثابت / درخواستی", fixedRequestKm, { fixedRequestKm = it; dirty = true }, suffix = "تومان به ازای هر کیلومتر")
                Spacer(Modifier.height(6.dp))
                Text(
                    "«در اختیار» نرخ کیلومتری ندارد و فقط جزء ساعتی محاسبه می‌شود.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { SectionTitle("ساعت و عوارضی", icon = Icons.Outlined.Schedule) }
        item {
            SurfaceCard {
                MoneyField("نرخ هر ساعت", hourRate, { hourRate = it; dirty = true })
                Spacer(Modifier.height(10.dp))
                MoneyField("نرخ هر عوارضی", toll, { toll = it; dirty = true })
            }
        }

        item { SectionTitle("نرخ سوخت", icon = Icons.Outlined.LocalGasStation) }
        item {
            SurfaceCard {
                MoneyField("سوخت دولتی", fuelGov, { fuelGov = it; dirty = true }, suffix = "تومان به ازای هر لیتر")
                Spacer(Modifier.height(10.dp))
                MoneyField("سوخت نیمه‌آزاد", fuelSemi, { fuelSemi = it; dirty = true }, suffix = "تومان به ازای هر لیتر")
                Spacer(Modifier.height(10.dp))
                MoneyField("سوخت آزاد", fuelFree, { fuelFree = it; dirty = true }, suffix = "تومان به ازای هر لیتر")
            }
        }

        item { SectionTitle("افزایش شب و سحر", icon = Icons.Outlined.NightsStay) }
        item {
            SurfaceCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("درصد شب", nightPercent, { nightPercent = it; dirty = true }, Modifier.weight(1f), suffix = "درصد")
                    NumberField("درصد سحر", saharPercent, { saharPercent = it; dirty = true }, Modifier.weight(1f), suffix = "درصد")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField("شروع شب", nightStart, { nightStart = it; dirty = true }, Modifier.weight(1f))
                    TimeField("شروع سحر", saharStart, { saharStart = it; dirty = true }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                TimeField("پایان سحر", saharEnd, { saharEnd = it; dirty = true })
                Spacer(Modifier.height(8.dp))
                Text(
                    "جزء ساعتی به تفکیک زمان واقعی محاسبه می‌شود؛ جزء کیلومتری فقط بر مبنای ساعت شروع سرویس ضریب می‌گیرد. " +
                        "درصد صفر یعنی افزایش غیرفعال است، اما قابلیت حذف نمی‌شود.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { SectionTitle("سرویس‌های مشمول افزایش زمانی", icon = Icons.Outlined.Bolt) }
        item {
            SurfaceCard {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ServiceType.entries.forEach { type ->
                        FilterChip(
                            selected = bonusTypes.contains(type),
                            onClick = {
                                bonusTypes = if (bonusTypes.contains(type)) bonusTypes - type else bonusTypes + type
                                dirty = true
                            },
                            label = { Text(type.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "پیش‌فرض قبلی: شب و ثابت. هر ترکیبی مجاز است.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { SectionTitle("خودرو و دوره‌های نگهداری", icon = Icons.Outlined.DirectionsCar) }
        item {
            SurfaceCard {
                SegmentedSelector(CarType.entries.toList(), carType, { it.label }, { carType = it; dirty = true })
                Spacer(Modifier.height(10.dp))
                MoneyField("دوره تعویض روغن", oilInterval, { oilInterval = it; dirty = true }, suffix = "کیلومتر")
                Spacer(Modifier.height(10.dp))
                MoneyField("دوره تسمه تایم", beltInterval, { beltInterval = it; dirty = true }, suffix = "کیلومتر")
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveRatesForYear(year, build())
                            dirty = false
                            toast.show("نرخ‌های سال $year ذخیره شد")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.width(8.dp)); Text("ذخیره نرخ‌های سال $year") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            affected = repository.countServicesInYear(year)
                            confirmRecalculate = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) { Icon(Icons.Outlined.Calculate, null); Spacer(Modifier.width(8.dp)); Text(tr("ذخیره و محاسبه مجدد", "Save & recalculate")) }

                Text(
                    "محاسبه مجدد فقط سرویس‌های سال $year را بازمحاسبه می‌کند. سال‌های قبل دست‌نخورده می‌مانند.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (confirmRecalculate) {
        ConfirmDialog(
            title = "محاسبه مجدد سال $year",
            message = "$affected سرویس سال $year با نرخ‌های جدید بازمحاسبه می‌شود. سرویس‌های سال‌های دیگر تغییر نمی‌کنند. ادامه می‌دهید؟",
            confirmLabel = "ذخیره و محاسبه",
            onConfirm = {
                confirmRecalculate = false
                scope.launch {
                    repository.saveRatesForYear(year, build())
                    val updated = repository.recalculateYear(year)
                    dirty = false
                    toast.show("$updated سرویس سال $year بازمحاسبه و ذخیره شد")
                }
            },
            onDismiss = { confirmRecalculate = false },
        )
    }
}
