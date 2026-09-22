package com.khodroyar.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.IncomeCalculator
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import com.khodroyar.app.ui.theme.heroBrush
import kotlinx.coroutines.launch

/**
 * Service registration — now a real full-screen form, not a modal sheet.
 *
 * The sheet was the source of the three complaints about this screen: a bottom sheet
 * re-measures when the IME appears (the page "moved"), its own scroll container fought
 * the keyboard so focused fields ended up underneath it, and a 95%-height sheet with
 * inline rows felt cramped. This screen instead:
 *
 *  * fills the window and scrolls normally, so Compose's automatic
 *    bring-into-view scrolls a focused field above the keyboard;
 *  * is inset with `imePadding()` by the shell, once, so nothing double-shifts;
 *  * groups fields into titled cards in the order the job is actually done;
 *  * keeps the running total pinned to the bottom, above the keyboard;
 *  * picks the service type and the vehicle with inline cards / a segmented control —
 *    no dialog, no dropdown, nothing that can cover a field.
 *
 * The business logic is untouched: the same [IncomeCalculator] breakdown drives the
 * live total and the saved amount, and the same repository calls persist it.
 */
@Composable
fun ServiceFormScreen(
    repository: AppRepository,
    initialDate: String,
    editing: ServiceEntity? = null,
    onDone: (() -> Unit)? = null,
) {
    // In tab mode (onDone == null) a successful save clears the form for the next
    // entry; as an overlay it closes instead.
    var resetToken by remember { mutableStateOf(0) }
    key(resetToken, editing?.id) {
        ServiceFormBody(
            repository = repository,
            initialDate = initialDate,
            editing = editing,
            standalone = onDone == null,
            onFinished = { if (onDone != null) onDone() else resetToken++ },
        )
    }
}

@Composable
private fun ServiceFormBody(
    repository: AppRepository,
    initialDate: String,
    editing: ServiceEntity?,
    standalone: Boolean,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())

    var date by remember { mutableStateOf(editing?.date ?: initialDate.ifBlank { Jalali.todayString() }) }
    var type by remember { mutableStateOf(ServiceType.fromWire(editing?.type)) }
    var carType by remember { mutableStateOf(CarType.fromWire(editing?.carType)) }
    var km by remember { mutableStateOf(editing?.km?.trimNumber() ?: "") }
    var hours by remember { mutableStateOf(editing?.hours?.trimNumber() ?: "") }
    var startTime by remember { mutableStateOf(editing?.startTime ?: "") }
    var endTime by remember { mutableStateOf(editing?.endTime ?: "") }
    var origin by remember { mutableStateOf(editing?.origin ?: "") }
    var destination by remember { mutableStateOf(editing?.destination ?: "") }
    var passengers by remember { mutableStateOf(editing?.passengers ?: "") }
    var requestNumber by remember { mutableStateOf(editing?.requestNumber?.digitsOnly()?.take(12) ?: "") }
    var tollCount by remember { mutableStateOf(editing?.tollCount?.takeIf { it > 0 }?.toString() ?: "") }
    var missionFood by remember { mutableStateOf(editing?.missionFood?.trimNumber() ?: "") }
    var missionToll by remember { mutableStateOf(editing?.missionToll?.trimNumber() ?: "") }
    var missionFine by remember { mutableStateOf(editing?.missionFine?.trimNumber() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Default vehicle follows settings until the user picks another one.
    val year = Jalali.parse(date)?.year ?: Jalali.currentYear()
    val rates = remember(ratesByYear, year) { ratesByYear.forYear(year) }
    LaunchedEffect(rates.defaultCarType) {
        if (editing == null) carType = rates.defaultCarType
    }

    // Hours are intentionally manual for every service. Start/end are kept only
    // for time-bonus calculation; they never overwrite the user's Hours field.
    val suggestOrigin by repository.suggestions("origin").collectAsState(initial = emptyList())
    val suggestDestination by repository.suggestions("destination").collectAsState(initial = emptyList())
    val suggestPassengers by repository.suggestions("passengers").collectAsState(initial = emptyList())
    val suggestKm by repository.suggestions("km").collectAsState(initial = emptyList())
    val suggestToll by repository.suggestions("tollCount").collectAsState(initial = emptyList())

    val breakdown = remember(type, km, hours, startTime, endTime, tollCount, missionFood, missionToll, missionFine, rates) {
        IncomeCalculator.breakdown(
            serviceType = type,
            km = km.asDouble(),
            hours = hours.asDouble(),
            tollCount = tollCount.asInt(),
            missionFood = missionFood.asDouble(),
            missionToll = missionToll.asDouble(),
            missionFine = missionFine.asDouble(),
            startTime = startTime.ifBlank { null },
            endTime = endTime.ifBlank { null },
            rates = rates,
        )
    }

    fun save() {
        if (Jalali.parse(date) == null) { toast.show("تاریخ سرویس معتبر نیست"); return }
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (editing == null) {
                    repository.addService(
                        date = date, type = type, carType = carType.wire,
                        km = km.asDouble(), hours = hours.asDouble(), workHours = hours.asDouble(),
                        startTime = startTime, endTime = endTime,
                        origin = origin.trim(), destination = destination.trim(),
                        passengers = passengers.trim(), requestNumber = requestNumber.trim(),
                        tollCount = tollCount.asInt(),
                        missionFood = missionFood.asDouble(),
                        missionToll = missionToll.asDouble(),
                        missionFine = missionFine.asDouble(),
                    )
                    toast.show("سرویس ثبت شد · ${money(breakdown.finalAmount)} تومان")
                } else {
                    repository.updateService(
                        editing.copy(
                            date = date, type = type.wire, carType = carType.wire,
                            km = km.asDouble(), hours = hours.asDouble(), workHours = hours.asDouble(),
                            startTime = startTime, endTime = endTime,
                            origin = origin.trim(), destination = destination.trim(),
                            passengers = passengers.trim(), requestNumber = requestNumber.trim(),
                            tollCount = tollCount.asInt(),
                            missionFood = missionFood.asDouble(),
                            missionToll = missionToll.asDouble(),
                            missionFine = missionFine.asDouble(),
                        )
                    )
                    toast.show("سرویس ویرایش شد · ${money(breakdown.finalAmount)} تومان")
                }
                onFinished()
            } catch (e: Exception) {
                toast.show("ثبت سرویس انجام نشد")
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap()
                .padding(horizontal = AppDimens.screenPadding)
                .padding(top = AppDimens.gutter, bottom = AppDimens.gutter),
            verticalArrangement = Arrangement.spacedBy(AppDimens.gutter),
        ) {
            /* ------------------------------------------------------- live total */
            GradientCard(brush = heroBrush()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editing != null) "ویرایش سرویس" else "ثبت سرویس جدید",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "$date  ·  ${type.label}  ·  ${carType.label}",
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "مبلغ محاسبه‌شده",
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            money(breakdown.finalAmount),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }

            /* -------------------------------------------------- 1. service type */
            FormSection("نوع سرویس", Icons.Outlined.Category, collapsible = false) {
                OptionSelector(
                    label = "",
                    options = ServiceType.entries.toList(),
                    selected = type,
                    optionLabel = { it.label },
                    optionHint = { option ->
                        val rate = rates.kmRateFor(option)
                        if (rate > 0) "${money(rate)} / کیلومتر" else "فقط ساعتی"
                    },
                    onSelect = { type = it },
                    columns = 2,
                )
            }

            /* ------------------------------------------- 2. date, time, mileage */
            FormSection("زمان و مسافت", Icons.Outlined.Schedule, collapsible = false) {
                JalaliDateField("تاریخ سرویس", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap))
                Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                    TimeField("ساعت شروع", startTime, { startTime = it }, Modifier.weight(1f))
                    TimeField("ساعت پایان", endTime, { endTime = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(AppDimens.gap))
                NumberField(
                    "ساعت کارکرد", hours,
                    { hours = it },
                    suffix = "ورود دستی",
                )
                Spacer(Modifier.height(AppDimens.gap))
                SuggestField("کیلومتر", km, { km = it }, suggestKm, numeric = true)
                Spacer(Modifier.height(AppDimens.gap))
                SuggestField("تعداد عوارضی", tollCount, { tollCount = it }, suggestToll, numeric = true)
            }

            /* ---------------------------------------------- 3. route & occupants */
            FormSection("مسیر و سرنشین", Icons.Outlined.Route, collapsible = false) {
                SuggestField("مبدأ", origin, { origin = it }, suggestOrigin)
                Spacer(Modifier.height(AppDimens.gap))
                SuggestField("مقصد", destination, { destination = it }, suggestDestination)
                Spacer(Modifier.height(AppDimens.gap))
                SuggestField("سرنشین / نام", passengers, { passengers = it }, suggestPassengers)
            }

            /* ------------------------------------------- 4. request number & car */
            FormSection("شماره درخواست و خودرو", Icons.Outlined.ConfirmationNumber, collapsible = false) {
                DigitsField(
                    label = "شماره درخواست",
                    value = requestNumber,
                    onValue = { requestNumber = it },
                    maxLength = 12,
                    imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(AppDimens.gap))
                Text(
                    "خودرو",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SegmentedSelector(
                    options = CarType.entries.toList(),
                    selected = carType,
                    label = { it.label },
                    onSelect = { carType = it },
                )
            }

            /* --------------------------------------------- 5. mission-only items */
            if (type == ServiceType.MISSION) {
                FormSection("اقلام مأموریت", Icons.Outlined.Work, collapsible = false) {
                    MoneyField("هزینه غذا", missionFood, { missionFood = it })
                    Spacer(Modifier.height(AppDimens.gap))
                    MoneyField("عوارضی مأموریت", missionToll, { missionToll = it })
                    Spacer(Modifier.height(AppDimens.gap))
                    MoneyField("جریمه مأموریت", missionFine, { missionFine = it }, imeAction = ImeAction.Done)
                }
            }

            /* ----------------------------------------------------- 6. breakdown */
            FormSection("محاسبه زنده درآمد", Icons.Outlined.Calculate, collapsible = false) {
                LiveBreakdown(breakdown)
            }

            if (editing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("حذف این سرویس", "Delete this service"), color = MaterialTheme.colorScheme.error)
                }
            }
            if (standalone) {
                Text(
                    "برای دیدن، ویرایش یا حذف سرویس‌های ثبت‌شده از تقویم استفاده کنید.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        /* --------------------------------------------------------- pinned save */
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.screenPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "جمع نهایی",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${money(breakdown.finalAmount)} تومان",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { save() },
                    enabled = !saving,
                    modifier = Modifier.height(AppDimens.buttonHeight),
                    shape = FieldShape,
                ) {
                    Icon(Icons.Outlined.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editing != null) "ذخیره تغییرات" else "ذخیره سرویس")
                }
            }
        }
    }

    if (confirmDelete && editing != null) {
        ConfirmDialog(
            title = "حذف سرویس",
            message = "سرویس ${editing.date} با مبلغ ${money(editing.income)} تومان حذف شود؟",
            confirmLabel = "حذف",
            destructive = true,
            onConfirm = {
                scope.launch {
                    repository.deleteService(editing.id)
                    toast.show("سرویس حذف شد")
                    confirmDelete = false
                    onFinished()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Titled card. Every form in the app is built from these, which is what makes them match. */
@Composable
fun FormSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(false) }
    SurfaceCard {
        Surface(
            onClick = { if (collapsible) expanded = !expanded },
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (collapsible) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (expanded) "بستن" else "باز کردن",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!collapsible || expanded) {
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Shows exactly how the number was reached, so the amount is never a black box. */
@Composable
private fun LiveBreakdown(breakdown: IncomeCalculator.Breakdown) {
    Column {
        InfoRow("نرخ کیلومتر", "${money(breakdown.kmRate)} تومان")
        if (breakdown.kmPercent != 0.0) {
            InfoRow("افزایش کیلومتری (بر مبنای ساعت شروع)", "${breakdown.kmPercent}٪")
        }
        InfoRow("مبلغ کیلومتر", "${money(breakdown.kmAmount)} تومان")
        val h = breakdown.hourly
        if (h.normalHours > 0) {
            InfoRow("ساعت عادی", "${Analytics.formatHours(h.normalHours)} ساعت · ${money(h.normalAmount)}")
        }
        if (h.nightHours > 0) {
            InfoRow("ساعت شب (${breakdown.nightPercent}٪)", "${Analytics.formatHours(h.nightHours)} ساعت · ${money(h.nightAmount)}")
        }
        if (h.saharHours > 0) {
            InfoRow("ساعت سحر (${breakdown.saharPercent}٪)", "${Analytics.formatHours(h.saharHours)} ساعت · ${money(h.saharAmount)}")
        }
        if (breakdown.extrasAmount != 0.0) {
            InfoRow("عوارضی و اقلام مأموریت", "${money(breakdown.extrasAmount)} تومان")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        InfoRow(
            "درآمد این سرویس",
            "${money(breakdown.finalAmount)} تومان",
            emphasis = true,
            valueColor = MaterialTheme.colorScheme.primary,
        )
    }
}

/** "12.0" -> "12" so an edited record does not gain noise in its text fields. */
fun Double.trimNumber(): String =
    if (this == Math.floor(this) && !this.isInfinite()) this.toLong().toString() else this.toString()
