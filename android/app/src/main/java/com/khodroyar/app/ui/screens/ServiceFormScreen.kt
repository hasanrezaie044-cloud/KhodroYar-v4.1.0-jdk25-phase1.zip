package com.khodroyar.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khodroyar.app.core.analytics.Analytics
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.holidays.WorkCalendar
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.IncomeCalculator
import com.khodroyar.app.core.rates.RatesByYear
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.core.rates.ServiceTypeDetector
import com.khodroyar.app.data.attachments.Attachments
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.prefs.ServicePreset
import com.khodroyar.app.data.prefs.ServicePresets
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.AutoServiceScheduler
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
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val toast = LocalToast.current
    val ratesByYear by repository.ratesByYear.collectAsState(initial = RatesByYear.empty())
    val settings by repository.settings.collectAsState(initial = repository.settingsNow)
    val allServices by repository.services().collectAsState(initial = emptyList())
    val lastService = remember(allServices, editing) {
        if (editing != null) null else allServices.firstOrNull()
    }

    var date by remember { mutableStateOf(editing?.date ?: initialDate.ifBlank { Jalali.todayString() }) }
    val availableServiceTypes = remember(settings.isRental) {
        if (settings.isRental) ServiceType.entries.toList()
        else ServiceType.entries.filter { it !in ServiceType.RENTAL_TYPES }
    }
    // null = the type is detected from start time + request number; a value is the
    // user's manual choice. An existing record only counts as manual when detection
    // would not reproduce its type (mission, holiday, rental…), so editing never
    // silently rewrites it.
    var manualType by remember { mutableStateOf<ServiceType?>(null) }
    // The user's own holidays arrive from the database a moment after the form opens. An
    // existing record is only classified (auto vs. manual) once they are here, so a saved
    // type is never flipped to "holiday" just because the list had not loaded yet.
    var manualHolidayDates by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) {
        repository.manualHolidays.collect { list -> manualHolidayDates = list.map { it.date }.toSet() }
    }
    var manualResolved by remember { mutableStateOf(editing == null) }
    LaunchedEffect(manualHolidayDates != null) {
        val e = editing
        if (!manualResolved && e != null && manualHolidayDates != null) {
            manualType = ServiceTypeDetector.manualOverride(
                ServiceType.fromWire(e.type), e.startTime, e.requestNumber,
                WorkCalendar.isNonWorkingDay(e.date, manualHolidayDates.orEmpty()),
            )
            manualResolved = true
        }
    }
    // Keep a manual choice valid when rental is toggled off
    LaunchedEffect(availableServiceTypes) {
        if (manualType != null && manualType !in availableServiceTypes) manualType = null
    }
    var carType by remember { mutableStateOf(CarType.fromWire(editing?.carType)) }
    var km by remember { mutableStateOf(editing?.km?.trimNumber() ?: "") }
    var hours by remember { mutableStateOf(editing?.hours?.trimNumber() ?: "") }
    var startTime by remember { mutableStateOf(editing?.startTime ?: "") }
    var endTime by remember { mutableStateOf(editing?.endTime ?: "") }
    var origin by remember { mutableStateOf(editing?.origin ?: "") }
    var destination by remember { mutableStateOf(editing?.destination ?: "") }
    var passengers by remember { mutableStateOf(editing?.passengers ?: "") }
    var requestNumber by remember { mutableStateOf(editing?.requestNumber?.digitsOnly()?.take(12) ?: "") }
    // A holiday date (Thursday, Friday, official or manual) makes it a holiday service.
    val isHolidayDate = remember(date, manualHolidayDates) {
        WorkCalendar.isNonWorkingDay(date, manualHolidayDates.orEmpty())
    }
    val detectedType = remember(startTime, requestNumber, isHolidayDate) {
        ServiceTypeDetector.detect(startTime, requestNumber, isHolidayDate)
    }
    // Without a start time (on a working day) there is nothing to detect from; NIGHT is
    // the old default. While an existing record is still being classified it keeps its type.
    val type: ServiceType = manualType
        ?: if (!manualResolved) ServiceType.fromWire(editing?.type) else (detectedType ?: ServiceType.NIGHT)
    // A fixed service never has a request number.
    val effectiveRequest = if (type == ServiceType.FIXED) "" else requestNumber.trim()
    var tollCount by remember { mutableStateOf(editing?.tollCount?.takeIf { it > 0 }?.toString() ?: "") }
    var missionFood by remember { mutableStateOf(editing?.missionFood?.trimNumber() ?: "") }
    var missionToll by remember { mutableStateOf(editing?.missionToll?.trimNumber() ?: "") }
    var missionFine by remember { mutableStateOf(editing?.missionFine?.trimNumber() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Photo attachment. Picked images are copied into app storage straight away, so
    // every file picked during this visit is tracked: whatever is not the final choice
    // (replaced, removed, or the form abandoned) is deleted instead of piling up.
    val context = LocalContext.current
    var attachmentPath by remember { mutableStateOf(editing?.attachmentPath ?: "") }
    val sessionFiles = remember { mutableListOf<String>() }
    val savedFlag = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!savedFlag[0]) sessionFiles.forEach { Attachments.delete(context, it) } }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val path = Attachments.importImage(context, uri)
            if (path == null) toast.show("ذخیره عکس انجام نشد") else {
                sessionFiles.add(path)
                attachmentPath = path
            }
        }
    }

    // Default vehicle follows settings until the user picks another one.
    val year = com.khodroyar.app.core.text.PersianInput.date(date)?.take(4)?.toInt() ?: Jalali.currentYear()
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
    val suggestHours by repository.suggestions("hours").collectAsState(initial = emptyList())
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
        val canonicalDate = com.khodroyar.app.core.text.PersianInput.date(date)
        if (canonicalDate == null) { toast.show("تاریخ سرویس معتبر نیست"); return }
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (editing == null) {
                    repository.addService(
                        date = canonicalDate, type = type, carType = carType.wire,
                        km = km.asDouble(), hours = hours.asDouble(), workHours = hours.asDouble(),
                        startTime = startTime, endTime = endTime,
                        origin = origin.trim(), destination = destination.trim(),
                        passengers = passengers.trim(), requestNumber = effectiveRequest,
                        tollCount = tollCount.asInt(),
                        missionFood = missionFood.asDouble(),
                        missionToll = missionToll.asDouble(),
                        missionFine = missionFine.asDouble(),
                        attachmentPath = attachmentPath,
                    )
                    toast.show("سرویس ثبت شد · ${money(breakdown.finalAmount)} تومان")
                } else {
                    repository.updateService(
                        editing.copy(
                            date = canonicalDate, type = type.wire, carType = carType.wire,
                            km = km.asDouble(), hours = hours.asDouble(), workHours = hours.asDouble(),
                            startTime = startTime, endTime = endTime,
                            origin = origin.trim(), destination = destination.trim(),
                            passengers = passengers.trim(), requestNumber = effectiveRequest,
                            tollCount = tollCount.asInt(),
                            missionFood = missionFood.asDouble(),
                            missionToll = missionToll.asDouble(),
                            missionFine = missionFine.asDouble(),
                            attachmentPath = attachmentPath,
                        )
                    )
                    toast.show("سرویس ویرایش شد · ${money(breakdown.finalAmount)} تومان")
                }
                // Saved: drop every picked file that did not become the final photo, and
                // the previous photo if it was replaced or removed.
                savedFlag[0] = true
                sessionFiles.filter { it != attachmentPath }.forEach { Attachments.delete(context, it) }
                if (editing != null && editing.attachmentPath != attachmentPath) {
                    Attachments.delete(context, editing.attachmentPath)
                }
                focus.clearFocus(force = true); keyboard?.hide(); onFinished()
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
                Text(if (editing != null) tr("ویرایش سرویس", "Edit service") else tr("سرویس جدید", "New service"),
                    color = Color(0xFFB9CFD0), style = MaterialTheme.typography.labelLarge)
                Text("$date · ${type.label} · ${carType.label}", color = Color(0xFFB9CFD0), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Text(money(breakdown.finalAmount), color = Color.White, style = MaterialTheme.typography.displaySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("تومان · برآورد درآمد این سرویس", "Toman · estimated service income"), color = Color(0xFFA5CFC5),
                    style = MaterialTheme.typography.labelSmall)
            }

            ServiceTypeSection(
                type = type,
                isManual = manualType != null,
                options = listOf<ServiceType?>(null) + availableServiceTypes,
                selected = manualType,
                onSelect = { manualType = it },
            )

            /* ------------------------------------------- 2. date, time, mileage */
            FormSection("زمان و مسافت", Icons.Outlined.Schedule, collapsible = false) {
                JalaliDateField("تاریخ سرویس", date, { date = it })
                Spacer(Modifier.height(AppDimens.gap))
                Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.gap)) {
                    TimeField("ساعت شروع", startTime, { startTime = it }, Modifier.weight(1f))
                    TimeField("ساعت پایان", endTime, { endTime = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(AppDimens.gap))
                SuggestField("ساعت کارکرد", hours, { hours = it }, suggestHours, numeric = true)
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
                if (manualType != ServiceType.FIXED) {
                    DigitsField(
                        label = "شماره درخواست",
                        value = requestNumber,
                        onValue = { requestNumber = it },
                        maxLength = 12,
                        imeAction = ImeAction.Done,
                    )
                    Spacer(Modifier.height(AppDimens.gap))
                }
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

            /* ------------------------------------------------ photo attachment */
            FormSection("پیوست عکس (اختیاری)", Icons.Outlined.AttachFile, collapsible = false) {
                if (attachmentPath.isNotBlank()) {
                    AttachmentPreviewOpenable(attachmentPath, onOpenFailed = { toast.show("نمایش عکس ممکن نشد") })
                    Spacer(Modifier.height(AppDimens.gap))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickImage.launch("image/*") },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = FieldShape,
                        ) { Text("تعویض عکس", maxLines = 2) }
                        OutlinedButton(
                            onClick = { attachmentPath = "" },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = FieldShape,
                        ) { Text("حذف پیوست", color = MaterialTheme.colorScheme.error, maxLines = 2) }
                    }
                } else {
                    OutlinedButton(
                        onClick = { pickImage.launch("image/*") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = FieldShape,
                    ) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("انتخاب عکس از گالری", maxLines = 2)
                    }
                }
            }

            /* ----------------------------------------------------- 6. breakdown */

            /* -------------------------------- tools at bottom: auto + named presets */
            FormSection("ابزارهای ثبت (خودکار و پریست)", Icons.Outlined.Bolt, collapsible = true) {
                if (settings.autoServicePresetSaved) {
                    Spacer(Modifier.height(AppDimens.gap))
                    Surface(
                        shape = FieldShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            InfoRow(
                                "مسیر",
                                listOf(settings.autoServiceOrigin, settings.autoServiceDestination)
                                    .filter { it.isNotBlank() }.joinToString(" ← ").ifBlank { "—" },
                            )
                            InfoRow("کیلومتر / ساعت", "${settings.autoServiceKm.trimNumber()} / ${settings.autoServiceHours.trimNumber()}")
                            InfoRow("سرنشین", settings.autoServicePassengers.ifBlank { "—" })
                        }
                    }
                    Spacer(Modifier.height(AppDimens.gap))
                    OutlinedButton(
                        onClick = {
                            origin = settings.autoServiceOrigin
                            destination = settings.autoServiceDestination
                            if (settings.autoServicePassengers.isNotBlank()) passengers = settings.autoServicePassengers
                            km = if (settings.autoServiceKm > 0) settings.autoServiceKm.trimNumber() else km
                            hours = if (settings.autoServiceHours > 0) settings.autoServiceHours.trimNumber() else hours
                            startTime = settings.autoServiceStartTime
                            endTime = settings.autoServiceEndTime
                            tollCount = if (settings.autoServiceTollCount > 0) settings.autoServiceTollCount.toString() else tollCount
                            if (settings.autoServiceType.isNotBlank()) {
                                manualType = ServiceTypeDetector.manualOverride(
                                    ServiceType.fromWire(settings.autoServiceType),
                                    settings.autoServiceStartTime.ifBlank { startTime }, requestNumber, isHolidayDate,
                                )
                            }
                            if (settings.autoServiceCarType.isNotBlank()) carType = CarType.fromWire(settings.autoServiceCarType)
                            toast.show("فرم از ثبت خودکار پر شد")
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) {
                        Icon(Icons.Outlined.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("پر کردن از ثبت خودکار")
                    }
                }
                Spacer(Modifier.height(AppDimens.gap))
                OutlinedButton(
                    onClick = {
                        repository.updateSettings {
                            it.copy(
                                autoServicePresetSaved = true,
                                autoServiceOrigin = origin.trim(), autoServiceDestination = destination.trim(),
                                autoServicePassengers = passengers.trim(),
                                autoServiceKm = km.asDouble(), autoServiceHours = hours.asDouble(),
                                autoServiceStartTime = startTime, autoServiceEndTime = endTime,
                                autoServiceTollCount = tollCount.asInt(),
                                autoServiceType = type.wire, autoServiceCarType = carType.wire,
                            )
                        }
                        toast.show("برای ثبت خودکار ذخیره شد")
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) {
                    Icon(Icons.Outlined.SaveAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ذخیره فرم فعلی برای ثبت خودکار")
                }
                Spacer(Modifier.height(AppDimens.gap))
                SwitchRow(
                    "ثبت خودکار روزانه",
                    if (settings.autoServiceEnabled) {
                        "فعال — ${AutoServiceScheduler.runTimeLabel()}"
                    } else {
                        "غیرفعال"
                    },
                    settings.autoServiceEnabled,
                ) { enabled ->
                    if (enabled) {
                        repository.updateSettings {
                            it.copy(
                                autoServicePresetSaved = true,
                                autoServiceEnabled = true,
                                autoServiceOrigin = origin.trim(), autoServiceDestination = destination.trim(),
                                autoServicePassengers = passengers.trim(),
                                autoServiceKm = km.asDouble(), autoServiceHours = hours.asDouble(),
                                autoServiceStartTime = startTime, autoServiceEndTime = endTime,
                                autoServiceTollCount = tollCount.asInt(),
                                autoServiceType = type.wire, autoServiceCarType = carType.wire,
                            )
                        }
                        toast.show("ثبت خودکار روزانه فعال شد")
                    } else {
                        repository.updateSettings { it.copy(autoServiceEnabled = false) }
                        toast.show("ثبت خودکار روزانه غیرفعال شد")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("پریست‌های نام‌دار", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val presets = remember(settings.servicePresetsJson) { ServicePresets.parse(settings.servicePresetsJson) }
                var presetName by remember { mutableStateOf("") }
                presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf(preset.origin, preset.destination).filter { it.isNotBlank() }.joinToString(" ← ").ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = {
                            origin = preset.origin
                            destination = preset.destination
                            passengers = preset.passengers
                            km = if (preset.km > 0) preset.km.trimNumber() else km
                            hours = if (preset.hours > 0) preset.hours.trimNumber() else hours
                            startTime = preset.startTime.ifBlank { startTime }
                            endTime = preset.endTime.ifBlank { endTime }
                            if (preset.tollCount > 0) tollCount = preset.tollCount.toString()
                            if (preset.type.isNotBlank()) {
                                manualType = ServiceTypeDetector.manualOverride(
                                    ServiceType.fromWire(preset.type),
                                    preset.startTime.ifBlank { startTime }, requestNumber, isHolidayDate,
                                )
                            }
                            if (preset.carType.isNotBlank()) carType = CarType.fromWire(preset.carType)
                            toast.show("پریست «${preset.name}» اعمال شد")
                        }) { Text("اعمال") }
                        IconButton(onClick = {
                            val next = presets.filter { it.id != preset.id }
                            repository.updateSettings { it.copy(servicePresetsJson = ServicePresets.encode(next)) }
                            toast.show("حذف شد")
                        }) { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    }
                }
                if (presets.size < 8) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        colors = fieldColors(),
                        value = presetName,
                        onValueChange = { presetName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = FieldShape,
                        label = { Text("نام پریست جدید") },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            val name = presetName.trim()
                            if (name.isEmpty()) { toast.show("نام پریست را وارد کنید"); return@OutlinedButton }
                            val item = ServicePreset(
                                id = java.util.UUID.randomUUID().toString(),
                                name = name,
                                type = type.wire,
                                carType = carType.wire,
                                origin = origin.trim(),
                                destination = destination.trim(),
                                passengers = passengers.trim(),
                                km = km.asDouble(),
                                hours = hours.asDouble(),
                                startTime = startTime,
                                endTime = endTime,
                                tollCount = tollCount.asInt(),
                            )
                            val next = presets + item
                            repository.updateSettings { it.copy(servicePresetsJson = ServicePresets.encode(next)) }
                            presetName = ""
                            toast.show("پریست ذخیره شد")
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.buttonHeight),
                        shape = FieldShape,
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, null)
                        Spacer(Modifier.width(8.dp))
                        Text("ذخیره فرم به‌عنوان پریست")
                    }
                }
            }
            Spacer(Modifier.height(AppDimens.gap))

            FormSection("محاسبه زنده درآمد", Icons.Outlined.Calculate, collapsible = false) {
                LiveBreakdown(breakdown)
            }

            if (editing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.buttonHeight),
                    shape = FieldShape,
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("حذف این سرویس", "Delete this service"), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        /* Responsive, pinned actions: price, Save AND Cancel remain above IME/navigation. */
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.screenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("جمع نهایی", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("${money(breakdown.finalAmount)} تومان", Modifier.weight(1.6f),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { focus.clearFocus(force = true); keyboard?.hide(); onFinished() }, enabled = !saving,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("service-cancel"), shape = FieldShape) {
                        Text(if (standalone) "پاک کردن فرم" else "انصراف")
                    }
                    Button(onClick = { save() }, enabled = !saving,
                        modifier = Modifier.weight(1.3f).heightIn(min = 52.dp).testTag("service-save"), shape = FieldShape) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (editing != null) "ذخیره تغییرات" else "ذخیره سرویس", maxLines = 2)
                    }
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
    // Registration forms are always open: an entry form that has to be unfolded first
    // was pure friction. Only *lists* of saved records collapse (see `Collapsible`).
    collapsible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(true) }
    SurfaceCard {
        Row(Modifier.fillMaxWidth().clip(FieldShape).clickable(enabled = collapsible) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 36.dp)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            if (collapsible) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "بستن" else "باز کردن", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!collapsible || expanded) {
            Spacer(Modifier.height(18.dp))
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


/**
 * The service type, detected automatically from the start time and request number. The
 * header shows what was detected; opening it reveals the old manual choice as an
 * optional override ("خودکار" = back to detection).
 */
@Composable
private fun ServiceTypeSection(
    type: ServiceType,
    isManual: Boolean,
    options: List<ServiceType?>,
    selected: ServiceType?,
    onSelect: (ServiceType?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SurfaceCard {
        Surface(
            onClick = { open = !open },
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Category, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "نوع سرویس", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "${type.label} · ${if (isManual) "دستی" else "خودکار"}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (open) "بستن" else "باز کردن",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open) {
            Spacer(Modifier.height(12.dp))
            HorizontalChipSelector(
                options = options,
                selected = selected,
                label = { it?.label ?: "خودکار" },
                onSelect = onSelect,
            )
        }
    }
}
