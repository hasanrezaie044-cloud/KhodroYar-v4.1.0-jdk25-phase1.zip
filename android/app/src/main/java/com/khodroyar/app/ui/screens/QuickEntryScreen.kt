package com.khodroyar.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.finance.*
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.*
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.repo.*
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.AppDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** One-handed quick entry. Suggestions fill a draft only; saving always has a separate review. */
@Composable
fun QuickEntryScreen(
    repository: AppRepository,
    initialKind: QuickKind = QuickKind.EXPENSE,
    initialMaintenanceType: String? = null,
    editing: EntryReceipt? = null,
    onFullService: (String) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)
    val expenses by remember { repository.insights.recentExpenses() }.collectAsState(initial = emptyList())
    val fuels by remember { repository.insights.recentFuels() }.collectAsState(initial = emptyList())
    val services by remember { repository.insights.recentServices() }.collectAsState(initial = emptyList())
    val repairs by remember { repository.insights.recentMaintenance() }.collectAsState(initial = emptyList())
    val original = editing?.draft ?: QuickDraft(initialKind, maintenanceType = initialMaintenanceType ?: "repair", km = if (initialMaintenanceType != null) settings.vehicleCurrentKm else 0.0)
    var draft by remember { mutableStateOf(original) }
    var amountText by rememberSaveable { mutableStateOf(if (editing != null || original.amount > 0) original.amount.trimNumber() else "") }
    var litersText by rememberSaveable { mutableStateOf(if (original.liters > 0) original.liters.trimNumber() else "") }
    var kmText by rememberSaveable { mutableStateOf(if (original.km > 0) original.km.trimNumber() else "") }
    var tripKmText by rememberSaveable { mutableStateOf(original.trip?.km?.trimNumber().orEmpty()) }
    var tripHoursText by rememberSaveable { mutableStateOf(original.trip?.hours?.trimNumber().orEmpty()) }
    var review by remember { mutableStateOf<QuickDraft?>(null) }
    var income by remember { mutableStateOf<Long?>(null) }
    var operationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val toast = LocalToast.current
    val savedMessage = tr("ثبت در پایگاه‌داده تأیید شد", "Saved and verified")
    val preparationError = tr("آماده‌سازی انجام نشد؛ دوباره تلاش کن.", "Could not prepare this entry.")
    val reviewing = review != null
    val frequentAmounts by remember(draft.kind) { repository.insights.frequentAmounts(draft.kind.name) }.collectAsState(initial = emptyList())

    fun fill(value: QuickDraft) {
        draft = value; amountText = if (value.kind != QuickKind.SERVICE) value.amount.trimNumber() else ""
        litersText = value.liters.takeIf { it > 0 }?.trimNumber().orEmpty()
        kmText = value.km.takeIf { it > 0 }?.trimNumber().orEmpty()
        tripKmText = value.trip?.km?.trimNumber().orEmpty(); tripHoursText = value.trip?.hours?.trimNumber().orEmpty(); error = null
    }
    BackHandler { if (!busy) { if (reviewing) { review = null; error = null } else onDone() } }

    Column(Modifier.fillMaxSize().testTag("quick-entry-screen")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).clearFocusOnTap()
            .padding(horizontal = AppDimens.screenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(if (reviewing) Icons.Outlined.FactCheck else Icons.Outlined.Bolt, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (reviewing) tr("بررسی و تأیید", "Review & confirm") else if (editing != null) tr("اصلاح ثبت اخیر", "Edit recent entry") else tr("ثبت سریع", "Quick add"), style = MaterialTheme.typography.headlineSmall)
                    if (reviewing) StatusPill(tr("هنوز ثبت نشده", "Not saved yet"))
                }
            }
            if (!reviewing) {
                if (editing == null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickKind.entries.forEach { kind -> FilterChip(selected = draft.kind == kind, onClick = {
                        fill(QuickDraft(kind = kind, date = draft.date, carType = draft.carType))
                    }, label = { Text(tr(kind.label, kind.english)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("quick-kind-${kind.name}")) }
                }
                if (editing == null) {
                    val suggestions: List<Pair<String, QuickDraft>> = when (draft.kind) {
                        QuickKind.EXPENSE -> expenses.map { "${it.title} · ${amount(it.amount)}" to QuickDraft.from(it, draft.date) }
                        QuickKind.FUEL -> fuels.map { "${money(it.liters)} ${tr("لیتر", "litres")} · ${it.date}" to QuickDraft.from(it, draft.date) }
                        QuickKind.MAINTENANCE -> repairs.map { it.typeText to QuickDraft.from(it, draft.date).copy(km = settings.vehicleCurrentKm) }
                        QuickKind.SERVICE -> services.map { "${it.origin} ← ${it.destination}" to QuickDraft.from(it, draft.date) }
                    }
                    if (suggestions.isNotEmpty()) {
                        Column {
                            Text(tr("تکرار از سابقه", "Repeat from history"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                suggestions.forEachIndexed { i, (label, value) -> SuggestionChip(onClick = { fill(value) }, label = { Text(label, maxLines = 1) }, modifier = Modifier.heightIn(min = 48.dp).testTag("quick-suggestion-$i")) }
                            }
                        }
                    }
                }
                SurfaceCard {
                    JalaliDateField(tr("تاریخ", "Date"), draft.date, { draft = draft.copy(date = it) })
                    Spacer(Modifier.height(14.dp))
                    when (draft.kind) {
                        QuickKind.EXPENSE -> {
                            TextFieldR(tr("عنوان", "Title"), draft.title, { draft = draft.copy(title = it) }, modifier = Modifier.testTag("quick-title"))
                            Spacer(Modifier.height(12.dp))
                            MoneyField(tr("مبلغ", "Amount"), amountText, { amountText = it }, modifier = Modifier.testTag("quick-amount"))
                            Spacer(Modifier.height(12.dp))
                            QuickChoice(tr("دسته", "Category"), PurchaseCategory.entries.map { it.wire to it.label }, draft.category) { draft = draft.copy(category = it) }
                        }
                        QuickKind.FUEL -> {
                            QuickChoice(tr("نوع سوخت", "Fuel type"), FuelType.entries.map { it.wire to it.label }, draft.fuelType) { draft = draft.copy(fuelType = it) }
                            Spacer(Modifier.height(12.dp))
                            NumberField(tr("مقدار سوخت", "Fuel amount"), litersText, { litersText = it }, suffix = tr("لیتر", "litres"), modifier = Modifier.testTag("quick-liters"))
                            Spacer(Modifier.height(12.dp))
                            MoneyField(tr("مبلغ پرداختی", "Paid amount"), amountText, { amountText = it }, modifier = Modifier.testTag("quick-amount"))
                            val rates by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())
                            val rate = rates.forYear(Jalali.parse(draft.date)?.year ?: Jalali.currentYear()).fuelPriceFor(FuelType.fromWire(draft.fuelType))
                            if (rate > 0 && litersText.asDouble() > 0) TextButton(onClick = { amountText = (rate * litersText.asDouble()).trimNumber() }) {
                                Text(tr("استفاده از نرخ ذخیره‌شده", "Use saved fuel rate") + " · ${amount(rate * litersText.asDouble())}")
                            }
                        }
                        QuickKind.MAINTENANCE -> {
                            QuickChoice(tr("نوع تعمیر", "Maintenance type"), MaintenanceType.entries.map { it.wire to it.label }, draft.maintenanceType) { draft = draft.copy(maintenanceType = it, title = "") }
                            if (draft.maintenanceType == "other") {
                                Spacer(Modifier.height(12.dp)); TextFieldR(tr("عنوان", "Title"), draft.title, { draft = draft.copy(title = it) })
                            }
                            Spacer(Modifier.height(12.dp))
                            MoneyField(tr("هزینه", "Cost"), amountText, { amountText = it }, modifier = Modifier.testTag("quick-amount"))
                            Spacer(Modifier.height(12.dp))
                            NumberField(tr("کیلومتر هنگام تعمیر", "Odometer at maintenance"), kmText, { kmText = it }, suffix = tr("کیلومتر", "km"))
                        }
                        QuickKind.SERVICE -> {
                            val trip = draft.trip
                            if (trip == null) Text(tr("یک سابقه یا الگوی سرویس انتخاب کن.", "Choose a recent trip or a saved preset."), style = MaterialTheme.typography.bodyMedium)
                            else {
                                Text("${trip.origin} ← ${trip.destination}", style = MaterialTheme.typography.titleMedium)
                                Text(ServiceType.fromWire(trip.type).label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                NumberField(tr("مسافت", "Distance"), tripKmText, { tripKmText = it; draft = draft.copy(trip = trip.copy(km = it.asDouble())) }, suffix = tr("کیلومتر", "km"))
                                Spacer(Modifier.height(12.dp))
                                NumberField(tr("مدت کار", "Work duration"), tripHoursText, { tripHoursText = it; draft = draft.copy(trip = trip.copy(hours = it.asDouble(), workHours = it.asDouble())) }, suffix = tr("ساعت", "hours"))
                                Spacer(Modifier.height(12.dp))
                                Collapsible(title = tr("مسیر و زمان سفر", "Route and time"), summary = "") {
                                    TextFieldR(tr("مبدأ", "Origin"), trip.origin, { draft = draft.copy(trip = trip.copy(origin = it)) })
                                    Spacer(Modifier.height(12.dp))
                                    TextFieldR(tr("مقصد", "Destination"), trip.destination, { draft = draft.copy(trip = trip.copy(destination = it)) })
                                    Spacer(Modifier.height(12.dp))
                                    TextFieldR(tr("شروع HH:mm", "Start HH:mm"), trip.startTime, { draft = draft.copy(trip = trip.copy(startTime = it.toLatinDigits())) })
                                    Spacer(Modifier.height(12.dp))
                                    TextFieldR(tr("پایان HH:mm", "End HH:mm"), trip.endTime, { draft = draft.copy(trip = trip.copy(endTime = it.toLatinDigits())) })
                                }
                            }
                            if (settings.autoServicePresetSaved) TextButton(onClick = {
                                val s = settings
                                val row = ServiceEntity("", draft.date, s.autoServiceType, s.autoServiceCarType, s.autoServiceKm, s.autoServiceHours, s.autoServiceHours,
                                    s.autoServiceStartTime, s.autoServiceEndTime, s.autoServiceOrigin, s.autoServiceDestination, s.autoServicePassengers, "", s.autoServiceTollCount, 0.0, 0.0, 0.0, 0L, "")
                                fill(QuickDraft.from(row, draft.date))
                            }) { Text(tr("الگوی ذخیره‌شده", "Saved trip preset")) }
                            if (editing == null) OutlinedButton(onClick = { onFullService(draft.date) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr("فرم کامل سرویس", "Full trip form")) }
                        }
                    }
                    if (draft.kind != QuickKind.SERVICE && frequentAmounts.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(tr("مبالغ پرتکرار", "Frequent recorded amounts"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            frequentAmounts.forEachIndexed { index, hint ->
                                SuggestionChip(onClick = { amountText = hint.value.trimNumber() }, label = { Text(amount(hint.value)) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("frequent-amount-$index"))
                            }
                        }
                    }
                    if (draft.kind != QuickKind.EXPENSE) {
                        Spacer(Modifier.height(12.dp))
                        QuickChoice(tr("خودرو", "Car"), CarType.entries.map { it.wire to it.label }, draft.carType) { draft = draft.copy(carType = it) }
                    }
                }
            } else {
                val value = requireNotNull(review)
                QuickReview(value, income)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("quick-error")) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { if (reviewing) { review = null; error = null } else onDone() }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = FieldShape) { Text(if (reviewing) tr("اصلاح", "Edit") else tr("انصراف", "Cancel")) }
                Button(onClick = {
                    if (busy) return@Button
                    focus.clearFocus(); keyboard?.hide()
                    if (!reviewing) {
                        val value = draft.copy(amount = amountText.asDouble(), liters = litersText.asDouble(), km = kmText.asDouble())
                        val validation = if (value.kind != QuickKind.SERVICE && amountText.isBlank()) "مبلغ را وارد کن؛ برای ثبت رایگان، صفر بنویس." else value.error()
                        if (validation != null) error = validation else {
                            busy = true
                            scope.launch {
                                try { income = repository.quickEntries.previewIncome(value); review = value; operationId = UUID.randomUUID().toString(); error = null }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = preparationError }
                                finally { busy = false }
                            }
                        }
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                repository.quickEntries.save(requireNotNull(review), operationId, income, editing)
                                toast.show(savedMessage); onDone()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "ثبت تأیید نشد؛ فهرست را بررسی کن."; review = null }
                            finally { busy = false }
                        }
                    }
                }, enabled = !busy, modifier = Modifier.weight(1.4f).heightIn(min = 52.dp).testTag("quick-confirm"), shape = FieldShape) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else { Icon(if (reviewing) Icons.Outlined.Check else Icons.Outlined.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (reviewing) tr("تأیید و ثبت", "Confirm & save") else tr("بررسی ثبت", "Review entry")) }
                }
            }
        }
    }
}

@Composable
private fun QuickReview(value: QuickDraft, income: Long?) {
    SurfaceCard(modifier = Modifier.testTag("quick-review")) {
        InfoRow(tr("نوع ثبت", "Entry type"), tr(value.kind.label, value.kind.english))
        InfoRow(tr("تاریخ", "Date"), value.date)
        when (value.kind) {
            QuickKind.EXPENSE -> { InfoRow(tr("عنوان", "Title"), value.title); InfoRow(tr("دسته", "Category"), PurchaseCategory.fromWire(value.category).label) }
            QuickKind.FUEL -> { InfoRow(tr("نوع سوخت", "Fuel type"), FuelType.fromWire(value.fuelType).label); InfoRow(tr("مقدار", "Amount"), "${money(value.liters)} ${tr("لیتر", "litres")}") }
            QuickKind.MAINTENANCE -> { InfoRow(tr("تعمیر", "Maintenance"), MaintenanceType.fromWire(value.maintenanceType).label); InfoRow(tr("کیلومتر", "Odometer"), money(value.km)) }
            QuickKind.SERVICE -> value.trip?.let { row ->
                InfoRow(tr("مسیر", "Route"), "${row.origin} ← ${row.destination}"); InfoRow(tr("نوع سرویس", "Trip type"), ServiceType.fromWire(row.type).label)
                InfoRow(tr("مسافت / ساعت", "Distance / hours"), "${money(row.km)} / ${row.hours}")
                InfoRow(tr("زمان سفر", "Trip time"), "${row.startTime.ifBlank { "—" }} — ${row.endTime.ifBlank { "—" }}")
                InfoRow(tr("عوارض", "Tolls"), money(row.tollCount))
                if (row.missionFood + row.missionToll + row.missionFine > 0) InfoRow(tr("مبالغ مأموریت", "Mission amounts"), amount(row.missionFood + row.missionToll + row.missionFine))
            }
        }
        if (value.kind != QuickKind.EXPENSE) InfoRow(tr("خودرو", "Car"), CarType.fromWire(value.carType).label)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        InfoRow(if (value.kind == QuickKind.SERVICE) tr("درآمد با نرخ سال ثبت", "Income at entry-year rates") else tr("مبلغ", "Amount"), amount(income?.toDouble() ?: value.amount) + " " + tr("تومان", "Toman"), emphasis = true)
        if (value.kind == QuickKind.SERVICE && value.trip?.let { ServiceType.fromWire(it.type) !in ServiceType.FIXED_AMOUNT_TYPES && it.km > 0 } == true)
            Text(tr("مسافت به کیلومتر خودرو اضافه می‌شود.", "Trip distance will advance the odometer."), style = MaterialTheme.typography.bodySmall)
        if (value.kind == QuickKind.MAINTENANCE && MaintenanceType.fromWire(value.maintenanceType) in MaintenanceType.TRACKED && value.date == Jalali.todayString() && value.km > 0)
            Text(tr("کیلومتر تعویض و موعد بعدی این قطعه به‌روز می‌شود.", "Replacement mileage and this part’s next target will be updated."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun QuickChoice(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = FieldShape) {
            Text(label + ": " + (options.firstOrNull { it.first == selected }?.second ?: "—"), Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (wire, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(wire) },
                trailingIcon = { if (wire == selected) Icon(Icons.Outlined.Check, null) }) }
        }
    }
}

@Composable
internal fun RecentQuickEntryCard(receipt: EntryReceipt, onEdit: () -> Unit, onUndo: () -> Unit) {
    SurfaceCard(modifier = Modifier.testTag("recent-quick-entry"), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(Icons.Outlined.CheckCircle, tint = MaterialTheme.colorScheme.secondary, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("ثبت سریع اخیر", "Recent quick entry"), style = MaterialTheme.typography.labelMedium)
                Text(receipt.draft.title.ifBlank { tr(receipt.draft.kind.label, receipt.draft.kind.english) }, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("edit-quick-entry")) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("اصلاح", "Edit")) }
            TextButton(onClick = onUndo, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("undo-quick-entry"), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("برگرداندن", "Undo"))
            }
        }
    }
}
