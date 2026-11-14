package com.khodroyar.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.core.text.PersianInput
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.workreport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reports sub-screen. Daily counts are primary; no metric/review gate or DB writer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkReportScreen(vm: WorkReportViewModel = viewModel()) {
    val progress by vm.progress.collectAsState()
    var preview by remember { mutableStateOf<ReportPage?>(null) }
    var showPages by remember { mutableStateOf(false) }
    var showRows by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }
    var editDay by remember { mutableStateOf<String?>(null) }
    var showTextImport by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var pageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showComparison by remember { mutableStateOf(false) }
    var onlineConsent by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importDocuments(uris)
    }
    fun changePages(action: () -> Unit) {
        if (vm.rows.isEmpty() && vm.companyCounts.isEmpty()) action() else pageAction = action
    }
    LaunchedEffect(vm.comparison, vm.sparseComparison) {
        if (vm.comparison != null || vm.sparseComparison != null) { showDetails = false; showComparison = true }
    }
    BackHandler(vm.busy) { vm.stop() }

    Column(Modifier.fillMaxSize().testTag("work-report-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { changePages { picker.launch(arrayOf("image/*", "application/pdf")) } }, enabled = !vm.busy,
                modifier = Modifier.testTag("report-import")) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("عکس / PDF")
            }
            Spacer(Modifier.weight(1f))
            if (vm.pages.isNotEmpty()) TextButton(onClick = { confirmReset = true }, enabled = !vm.busy) { Text("پاک کردن") }
        }
        if (vm.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(progress.ifBlank { "در حال آماده‌سازی…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::stop) { Text("توقف") }
            }
        }
        vm.error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = vm::clearError) { Icon(Icons.Outlined.Close, "بستن خطا") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("work-report-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.pages.isEmpty()) item {
                EmptyState("گزارش شرکت", "عکس یا PDF را انتخاب کنید؛ خواندن آنلاین فقط با تأیید شماست.", Icons.Outlined.DocumentScanner)
            }
            if (vm.pages.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showPages = true }, modifier = Modifier.testTag("report-source-pages")) { Text("${vm.pages.size} صفحه") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onlineConsent = true }, enabled = !vm.busy && vm.pages.size in 1..10,
                        modifier = Modifier.testTag("report-online-assist")) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (vm.recognized.isEmpty()) "خواندن آنلاین" else "ارسال دوباره")
                    }
                }
                if (vm.pages.size > 10) Text("حداکثر ۱۰ صفحه در هر ارسال", style = MaterialTheme.typography.bodySmall)
            }
            // The range can be supplied even when extraction found no company
            // rows. App-only dates must not disappear behind an early return.
            if (vm.pages.isNotEmpty()) item {
                SurfaceCard(contentPadding = 10.dp) {
                    Text("تعداد روزانه", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("report-date-compare-title"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportRangeField("از", vm.start, !vm.busy, Modifier.weight(1f)) { vm.range(it, vm.end) }
                        ReportRangeField("تا", vm.end, !vm.busy, Modifier.weight(1f)) { vm.range(vm.start, it) }
                    }
                    if (vm.companyCounts.isNotEmpty()) Text("* اصلاح شما", style = MaterialTheme.typography.labelSmall)
                    Text("اختلاف = برنامه − شرکت · با اصل سند بررسی کنید.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.dateComparison.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("شرکت ${vm.dateComparison.sumOf { it.companyCount }}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("report-total-company"))
                        Text("برنامه ${vm.dateComparison.sumOf { it.appCount }}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("report-total-app"))
                        Text("اختلاف ${signedCount(vm.dateComparison.sumOf { it.difference })}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("report-total-difference"))
                    }
                }
            }
            if (vm.readWarnings.isNotEmpty() || vm.rows.any { it.included && it.kind != ReportRowKind.SERVICE }) item {
                TextButton(onClick = { showWarnings = true }, modifier = Modifier.testTag("report-read-warning")) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("پوشش خواندن را بررسی کنید")
                }
            }
            if (vm.dateComparison.isNotEmpty()) {
                item(key = "report-date-compare-header") { DateCountTableHeader() }
                items(vm.dateComparison, key = { "date-compare:${it.date}" }) { day ->
                    DateCountTableRow(day, enabled = !vm.busy, onEdit = { editDay = day.date })
                }
            }
            vm.dateComparisonError?.let { message -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).testTag("report-date-compare-error"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::retryDateComparison) { Text("تلاش دوباره") }
                }
            } }
            if (vm.recognized.isNotEmpty() && vm.dateComparison.isEmpty() && !vm.busy && vm.dateComparisonError == null) item {
                Text("برای این بازه تعدادی یافت نشد.", style = MaterialTheme.typography.bodySmall)
            }
            if (vm.pages.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editDay = "" }, enabled = !vm.busy, modifier = Modifier.testTag("report-add-count")) { Text("افزودن روز") }
                    TextButton(onClick = { showRows = true }, enabled = !vm.busy, modifier = Modifier.testTag("report-edit-rows")) { Text("اصلاح ردیف‌ها") }
                    TextButton(onClick = { showTextImport = true }, enabled = !vm.busy, modifier = Modifier.testTag("report-import-text")) { Text("متن صفحه") }
                    if (vm.recognized.any { it.source != ReportTextSource.LEGACY && it.text.isNotBlank() })
                        TextButton(onClick = { changePages { vm.reparseSavedText() } }, enabled = !vm.busy,
                            modifier = Modifier.testTag("report-reparse")) { Text("تحلیل دوبارهٔ متن") }
                    if (vm.rows.isNotEmpty()) TextButton(onClick = { showDetails = true }, enabled = !vm.busy,
                        modifier = Modifier.testTag("report-open-details")) { Text("جزئیات اختیاری") }
                }
            }
        }
    }
    if (showPages) ReportPagesDialog(vm, onPreview = { preview = it }, onChange = ::changePages) { showPages = false }
    if (showRows) ReportRowsDialog(vm, onPreview = { row -> preview = vm.pages.firstOrNull { it.id == row.pageId } }) { showRows = false }
    if (showDetails) ReportDetailsDialog(vm, onPreview = { row -> preview = vm.pages.firstOrNull { it.id == row.pageId } }) { showDetails = false }
    editDay?.let { date -> ReportCountCorrectionDialog(vm, date) { editDay = null } }
    if (showWarnings) FullReportDialog({ showWarnings = false }, "پوشش گزارش") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.readWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (vm.rows.any { it.included && it.kind != ReportRowKind.SERVICE }) Text("ردیف‌های تجمیعی در تعداد سرویس شمرده نمی‌شوند؛ تعداد روز را از سند اصلاح کنید.", style = MaterialTheme.typography.bodySmall)
            Text("صفرِ شرکت یعنی در متن ردیفی یافت نشد، نه اثبات نبود سرویس. برابری تعداد نیز تطابق سرویس‌ها را ثابت نمی‌کند.", style = MaterialTheme.typography.bodySmall)
        }
    }
    preview?.let { page ->
        FullReportDialog(onClose = { preview = null }, title = "اصل صفحه · ${page.name}") {
            PageImage(vm.documents, page, Modifier.weight(1f).fillMaxWidth(), thumbnail = false)
        }
    }
    if (showTextImport) ReportTextImportDialog(vm) { showTextImport = false }
    if (confirmReset) ConfirmDialog("پاک کردن پیش‌نویس؟", "فقط گزارش و اصلاح‌های آن پاک می‌شوند؛ سوابق برنامه حفظ می‌شوند.",
        confirmLabel = "پاک کردن", destructive = true, onConfirm = { confirmReset = false; vm.reset() }, onDismiss = { confirmReset = false })
    pageAction?.let { action -> ConfirmDialog("تحلیل تازه؟", "ردیف‌ها و اصلاح‌های پیش‌نویس بازنشانی می‌شوند؛ سوابق برنامه تغییر نمی‌کنند.",
        confirmLabel = "ادامه", onConfirm = { pageAction = null; action() }, onDismiss = { pageAction = null }) }
    if (onlineConsent) FastOcrAssistDialog(vm) { onlineConsent = false }
    if (showComparison) {
        val partial = vm.sparseComparison
        if (partial != null) SparseHoursComparisonDialog(partial, onLink = { company, app -> vm.link(company, app); showComparison = false }) { showComparison = false }
        else vm.comparison?.let { result -> WorkComparisonDialog(result, onLink = { company, app -> vm.link(company, app); showComparison = false }) { showComparison = false } }
    }
}

@Composable
private fun ReportRangeField(label: String, value: String, enabled: Boolean, modifier: Modifier, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier.semantics { contentDescription = "$label: $value" },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), shape = FieldShape) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value.ifBlank { "انتخاب" }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
        }
    }
    if (open) JalaliDatePickerDialog(value, label, onDismiss = { open = false }, onPick = { onValue(it); open = false })
}

private fun signedCount(value: Int): String = if (value > 0) "+$value" else value.toString()

@Composable
private fun RowScope.CountColumn(text: String, weight: Float, tag: String = "", muted: Boolean = false) {
    Text(text, Modifier.weight(weight).then(if (tag.isBlank()) Modifier else Modifier.testTag(tag)),
        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Center),
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun DateCountTableHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CountColumn("تاریخ", 1.75f, muted = true)
        CountColumn("شرکت", .7f, muted = true); CountColumn("برنامه", .7f, muted = true); CountColumn("اختلاف", .7f, muted = true)
        Spacer(Modifier.width(40.dp))
    }
}

@Composable
private fun DateCountTableRow(day: DateServiceComparison, enabled: Boolean, onEdit: () -> Unit) {
    Surface(shape = FieldShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("report-date-compare-${day.date}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            CountColumn(day.date, 1.75f, "report-count-date-${day.date}")
            CountColumn("${day.companyCount}${if (day.manuallyCorrected) "*" else ""}", .7f, "report-count-company-${day.date}")
            CountColumn(day.appCount.toString(), .7f, "report-count-app-${day.date}")
            CountColumn(signedCount(day.difference), .7f, "report-count-difference-${day.date}")
            IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.width(40.dp).testTag("report-count-edit-${day.date}")) {
                Icon(Icons.Outlined.Edit, "اصلاح تعداد ${day.date}", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ReportCountCorrectionDialog(vm: WorkReportViewModel, initialDate: String, close: () -> Unit) {
    var date by remember(initialDate) { mutableStateOf(initialDate) }
    val current = vm.dateComparison.firstOrNull { it.date == initialDate }
    var count by remember(initialDate) { mutableStateOf(current?.companyCount?.toString().orEmpty()) }
    FullReportDialog(close, "اصلاح تعداد شرکت") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(date, { date = ReportDateText.normalize(it.take(16)) }, readOnly = initialDate.isNotEmpty(), label = { Text("تاریخ") },
                modifier = Modifier.fillMaxWidth().testTag("report-count-date-input"), singleLine = true,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            OutlinedTextField(count, { count = it.take(12) }, label = { Text("تعداد شرکت") }, modifier = Modifier.fillMaxWidth().testTag("report-count-input"),
                singleLine = true, textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            val day = vm.dateComparison.firstOrNull { it.date == PersianInput.date(date) }
            Text("خوانده‌شده: ${day?.extractedCount ?: 0} · برنامه: ${day?.appCount ?: 0}", style = MaterialTheme.typography.bodySmall)
            Text("اصلاح شما با * مشخص می‌شود؛ ردیف OCR یا سرویس برنامه ساخته و تغییر داده نمی‌شود.", style = MaterialTheme.typography.bodySmall)
            if (day != null && day.companyCount == 0 && !day.manuallyCorrected) Text("صفر یعنی در متن یافت نشد؛ اصل سند را بررسی کنید.", style = MaterialTheme.typography.bodySmall)
            val canonical = PersianInput.date(date)
            if (canonical != null && (vm.start.isBlank() || vm.end.isBlank() || canonical !in vm.start..vm.end))
                Text("ابتدا بازهٔ گزارش را برای این تاریخ تنظیم کنید.", style = MaterialTheme.typography.bodySmall)
            if (date in vm.companyCounts) TextButton(onClick = { vm.restoreCompanyCount(date); close() }, enabled = !vm.busy,
                modifier = Modifier.testTag("report-count-restore")) { Text("استفاده از تعداد خوانده‌شده") }
        }
        Button(onClick = { if (vm.correctCompanyCount(date, count)) close() },
            enabled = !vm.busy && PersianInput.date(date)?.let { it in vm.start..vm.end } == true && PersianInput.count(count) != null,
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("report-count-save")) { Text("ثبت اصلاح") }
    }
}

@Composable
private fun ReportRowsDialog(vm: WorkReportViewModel, onPreview: (ReportRow) -> Unit, close: () -> Unit) {
    FullReportDialog(close, "ردیف‌های شرکت") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("report-rows-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("اصلاح تاریخ و انتخاب ردیف؛ بدون نیاز به کیلومتر و ساعت.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::addRow, enabled = !vm.busy, modifier = Modifier.testTag("report-add-row")) { Text("افزودن سرویس از سند") }
            }
            items(vm.rows, key = { it.id }) { row ->
                SurfaceCard(contentPadding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.included, { vm.edit(row.copy(included = it)) }, enabled = !vm.busy, modifier = Modifier.testTag("report-row-include-${row.id}"))
                        OutlinedTextField(row.date, { vm.edit(row.copy(date = it.take(16))) }, label = { Text("تاریخ") }, enabled = !vm.busy,
                            modifier = Modifier.weight(1f).testTag("report-row-date-${row.id}"), singleLine = true,
                            isError = row.included && ReportDateText.parse(row.date) == null,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                        IconButton(onClick = { onPreview(row) }) { Icon(Icons.Outlined.Image, "صفحهٔ ${row.page}") }
                    }
                    Text("صفحه ${row.page} · ردیف ${row.ordinal.ifBlank { (row.line + 1).toString() }}${if (row.id.startsWith("manual:")) " · دستی" else ""}", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(row.raw, maxLines = 2, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content)) }
                    if (row.kind != ReportRowKind.DAILY_TOTAL) TextButton(onClick = {
                        vm.edit(row.copy(kind = if (row.kind == ReportRowKind.GROUPED) ReportRowKind.SERVICE else ReportRowKind.GROUPED, count = "1"))
                    }, enabled = !vm.busy) { Text(if (row.kind == ReportRowKind.GROUPED) "تجمیعی؛ تغییر به سرویس" else "یک سرویس؛ تغییر به تجمیعی") }
                    else Text("جمع روزانه؛ در شمارش ردیف‌های سرویس نیست", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReportPagesDialog(vm: WorkReportViewModel, onPreview: (ReportPage) -> Unit, onChange: (() -> Unit) -> Unit, close: () -> Unit) {
    FullReportDialog(close, "صفحه‌های گزارش") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.pages, key = { it.id }) { page ->
                SurfaceCard {
                    Text("${vm.pages.indexOf(page) + 1} · ${page.name}", maxLines = 2, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onPreview(page) }, modifier = Modifier.fillMaxWidth()) {
                        PageImage(vm.documents, page, Modifier.fillMaxWidth().height(140.dp), thumbnail = true)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { onChange { vm.move(page.id, -1) } }, enabled = !vm.busy && page != vm.pages.first()) { Icon(Icons.Outlined.ArrowUpward, "صفحه جلوتر") }
                        IconButton(onClick = { onChange { vm.move(page.id, 1) } }, enabled = !vm.busy && page != vm.pages.last()) { Icon(Icons.Outlined.ArrowDownward, "صفحه عقب‌تر") }
                        IconButton(onClick = { onChange { vm.rotate(page.id) } }, enabled = !vm.busy) { Icon(Icons.Outlined.RotateRight, "چرخش ۹۰ درجه") }
                        IconButton(onClick = { onChange { vm.remove(page.id) } }, enabled = !vm.busy) { Icon(Icons.Outlined.DeleteOutline, "حذف صفحه") }
                    }
                }
            }
        }
    }
}

/** Optional legacy metric review stays off the count-first main flow. */
@Composable
private fun ReportDetailsDialog(vm: WorkReportViewModel, onPreview: (ReportRow) -> Unit, close: () -> Unit) {
    val horizontal = rememberScrollState()
    FullReportDialog(close, "جزئیات اختیاری") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("report-details-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("کیلومتر و ساعت فقط برای تطبیق جزئی؛ شمارش روزانه مستقل است.", style = MaterialTheme.typography.bodySmall)
                if (vm.companyCounts.isNotEmpty()) Text("اصلاح تعداد روزانه در این بخش اعمال نمی‌شود؛ مبنا ردیف‌های سند است.", style = MaterialTheme.typography.bodySmall)
                if (vm.links.isNotEmpty()) TextButton(onClick = vm::clearLinks) { Text("بازنشانی تطبیق‌های دستی") }
            }
            items(vm.rows, key = { it.id }) { row ->
                ReportRowEditor(row, horizontal, enabled = !vm.busy, onChange = vm::edit, onPreview = { onPreview(row) })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.reviewConfirmed, vm::setReview, enabled = !vm.busy, modifier = Modifier.testTag("report-review-confirm"))
                    Text("جزئیات و بازه را با سند بررسی کردم.", style = MaterialTheme.typography.bodySmall)
                }
                vm.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::compare, enabled = !vm.busy && vm.reviewConfirmed, modifier = Modifier.fillMaxWidth().testTag("report-compare")) { Text("تطبیق جزئیات") }
            }
        }
    }
}

@Composable
private fun ReportRowEditor(row: ReportRow, scroll: ScrollState, enabled: Boolean, onChange: (ReportRow) -> Unit, onPreview: () -> Unit) {
    SurfaceCard(contentPadding = 8.dp) {
        Row(Modifier.testTag("report-row-scroll-${row.id}").horizontalScroll(scroll).width(760.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(row.included, { onChange(row.copy(included = it)) }, enabled = enabled, modifier = Modifier.width(48.dp))
            ReportCell(row.date, "تاریخ", 160, enabled, row.included && ReportDateText.parse(row.date) == null, "report-cell-${row.id}-date") { onChange(row.copy(date = it)) }
            ReportCell(row.count, if (row.kind == ReportRowKind.DAILY_TOTAL) "تعداد" else "یک ردیف", 90, enabled && row.kind == ReportRowKind.DAILY_TOTAL,
                row.included && PersianInput.count(row.count) == null, "report-cell-${row.id}-count") { onChange(row.copy(count = it)) }
            ReportCell(row.km, "کیلومتر", 120, enabled, row.included && PersianInput.decimal(row.km) == null, "report-cell-${row.id}-km") { onChange(row.copy(km = it)) }
            ReportCell(row.hours, "ساعت", 120, enabled,
                row.included && PersianInput.hours(row.hours) == null && !ReportHoursText.isEmpty(row.hours),
                "report-cell-${row.id}-hours") { onChange(row.copy(hours = it, hoursBlank = ReportHoursText.isEmpty(it))) }
            TextButton(onClick = onPreview, modifier = Modifier.width(120.dp)) { Text("صفحهٔ ${row.page}") }
        }
        if (row.kind != ReportRowKind.DAILY_TOTAL) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ردیف سند: ${row.ordinal.ifBlank { "ناخوانا" }} · ${if (row.kind == ReportRowKind.GROUPED) "تجمیعی، نه یک سرویس" else "یک سرویس"}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onChange(row.copy(kind = if (row.kind == ReportRowKind.GROUPED) ReportRowKind.SERVICE else ReportRowKind.GROUPED, count = "1")) }, enabled = enabled) {
                Text(if (row.kind == ReportRowKind.GROUPED) "تغییر به یک سرویس" else "این ردیف تجمیعی است")
            }
        }
        SelectionContainer { Text(row.raw, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content)) }
    }
}
@Composable
private fun ReportCell(value: String, label: String, width: Int, enabled: Boolean, invalid: Boolean, tag: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.take(40)) }, modifier = Modifier.width(width.dp).testTag(tag), label = { Text(label) }, singleLine = true,
        enabled = enabled, isError = invalid, textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), colors = fieldColors())
}
@Composable
private fun PageImage(documents: ReportDocuments, page: ReportPage, modifier: Modifier, thumbnail: Boolean) {
    var failure by remember(page, thumbnail) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, page, thumbnail) {
        var rendered: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { rendered = documents.render(page, if (thumbnail) 120000 else 2400000, if (thumbnail) 420 else 2000) }
            value = rendered
        } catch (e: kotlinx.coroutines.CancellationException) {
            rendered?.recycle(); throw e
        } catch (_: OutOfMemoryError) { rendered?.recycle(); failure = true }
        catch (_: Exception) { rendered?.recycle(); failure = true }
    }
    val image = bitmap
    DisposableEffect(image) { onDispose { image?.recycle() } }
    if (image != null) Image(image.asImageBitmap(), "اصل صفحهٔ گزارش", modifier, contentScale = ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) { if (failure) Text("پیش‌نمایش قابل خواندن نیست") else CircularProgressIndicator(Modifier.size(24.dp)) }
}
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FullReportDialog(onClose: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // A dialog is a separate accessibility root, not a descendant of the
        // activity's ID-exporting semantics node.
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "بستن") }
                }
                content()
            }
        }
    }
}
@Composable
private fun ReportTextImportDialog(vm: WorkReportViewModel, close: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val page = vm.pages.getOrNull(selected)
    var text by remember(page?.id) { mutableStateOf(vm.recognized.firstOrNull { it.id == page?.id }?.text.orEmpty()) }
    FullReportDialog(close, "متن صفحهٔ گزارش") {
        Text("متن همین صفحه؛ بدون ارسال. اصلاح‌های پیش‌نویس بازنشانی می‌شوند.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.pages.size) { index -> FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text("صفحهٔ ${index + 1}") }) }
        }
        OutlinedTextField(text, { if (it.length <= FastOcrReportReader.MAX_DRAFT_TEXT) text = it },
            label = { Text("متن همین صفحه") }, modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp).testTag("report-text-input"),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
        Button(onClick = { page?.let { vm.importText(it.id, text) }; close() }, enabled = page != null && text.isNotBlank() && !vm.busy,
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("report-text-apply")) { Text("اعمال متن") }
    }
}

private fun companyServiceText(row: ReportRow): String =
    "${row.date} · ${row.km} کیلومتر · ${row.hours.ifBlank { "نامشخص" }} ساعت · صفحهٔ ${row.page} / ردیف ${row.ordinal.ifBlank { (row.line + 1).toString() }}" +
        (if (row.origin.isNotBlank() || row.destination.isNotBlank()) "\n${row.origin} ← ${row.destination}" else "") +
        (if (row.kind == ReportRowKind.GROUPED) " · تجمیعی" else "")

private fun appServiceText(service: AppWorkService): String =
    "${service.date} · ${PersianInput.display(service.km)} کیلومتر · ${PersianInput.display(service.hours)} ساعت" +
        "\n${service.type} · ${service.origin} ← ${service.destination}" +
        (if (service.startTime.isNotBlank() || service.endTime.isNotBlank()) " · ${service.startTime.ifBlank { "—" }} تا ${service.endTime.ifBlank { "—" }}" else "") +
        " · شناسه ${service.id.take(8)}" + (if (service.requestNumber.isNotBlank()) " · درخواست ${service.requestNumber}" else "")

/** Each upload is separately consented; no local recognition engine. */
@Composable
private fun FastOcrAssistDialog(vm: WorkReportViewModel, close: () -> Unit) {
    var keyDraft by remember { mutableStateOf("") }
    FullReportDialog(close, "خواندن آنلاین صفحه‌ها با FastOCR") {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("report-online-consent-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("خواندن تصویر فقط آنلاین است؛ بدون تأیید صریح این پنجره هیچ سندی ارسال نمی‌شود.", style = MaterialTheme.typography.bodySmall)
            Text("در صورت تأیید، تصویرِ ${vm.pages.size} صفحه (هرکدام حداکثر ${FastOcrClient.MAX_IMAGE_BYTES / 1_000_000} مگابایت PDF) در هر بار تأیید به api.fastocr.org ارسال می‌شود. اصل سند و متن پاسخ فقط در پیش‌نویس خصوصی همین گوشی می‌ماند؛ سیاست نگهداری سرویس مستقل است. این سرویس متعلق به برنامه نیست؛ فقط با کلید خودِ شما و به حساب خودِ شماست.", style = MaterialTheme.typography.bodySmall)
            if (vm.fastOcrKeySaved) {
                Text("کلید API شما از قبل رمزنگاری‌شده ذخیره است؛ برای امنیت دوباره نمایش داده نمی‌شود. حذف یا عوض کردنش از دکمهٔ زیر ممکن است.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { keyDraft = ""; vm.clearFastOcrKey() }) { Text("حذف کلید ذخیره‌شده") }
            } else {
                Text("کلید API خود را از حساب FastOCR (شروع با fok_live_) بسازید و این‌جا وارد کنید؛ فقط یک‌بار لازم است:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(keyDraft, { keyDraft = it.trim() }, label = { Text("کلید API شما (fok_live_…)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("report-fastocr-key"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                Text("کلید فقط روی همین گوشی و رمزنگاری‌شده نگه داشته می‌شود و در هیچ گزارش/صادراتی درج نمی‌شود.", style = MaterialTheme.typography.labelSmall)
            }
            Text("گیرندهٔ تصاویر: api.fastocr.org", modifier = Modifier.testTag("report-fastocr-host"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("نتیجه فقط پیش‌نویس گزارش را جایگزین می‌کند؛ اصلاح‌های قبلی آن بازنشانی می‌شوند و سوابق برنامه تغییر نمی‌کنند. مقدار خالی صفر نمی‌شود.", style = MaterialTheme.typography.bodySmall)
            Text("سند ممکن است نام و مبالغ محرمانه داشته باشد؛ مدت نگهداری و حذف نزد سرویس تابع سیاست FastOCR است؛ فقط در صورت موافقت با آن ارسال کنید.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val keyReady = vm.fastOcrKeySaved || keyDraft.startsWith("fok_")
                Button(onClick = {
                    val ok = if (vm.fastOcrKeySaved) true else vm.saveFastOcrKey(keyDraft)
                    if (ok) { vm.recognizeFastOcr(); close() }
                }, enabled = keyReady && vm.pages.isNotEmpty() && !vm.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("report-online-send")) { Text("ارسال به api.fastocr.org و پیش‌پر کردن") }
                OutlinedButton(onClick = close, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("report-online-cancel")) { Text("انصراف؛ هیچ چیز ارسال نشود") }
            }
        }
    }
}

/** Full-period daily control for truly absent/unknown hours, not a second matcher. */
@Composable
private fun SparseHoursComparisonDialog(result: SparseHoursResult, onLink: (List<String>, List<String>) -> Unit, close: () -> Unit) {
    var showCompleteDays by remember(result) { mutableStateOf(false) }
    var detailDate by remember(result) { mutableStateOf<String?>(null) }
    fun n(value: java.math.BigDecimal) = PersianInput.display(value)
    FullReportDialog(close, "مقایسهٔ کل دوره؛ ساعت نامشخص") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("report-sparse-comparison"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SurfaceCard {
                    Text("${result.start} تا ${result.end}", style = MaterialTheme.typography.labelLarge)
                    val companyCount = if (result.serviceCountKnown) "${result.company.count} سرویس" else "${result.companyRows.size} ردیف؛ تعداد داخل تجمیع نامشخص"
                    Text("شرکت: $companyCount · ${n(result.company.km)} کیلومتر · ساعت نامشخص", Modifier.testTag("report-sparse-company-total"))
                    Text("خودرویار: ${result.app.count} سرویس · ${n(result.app.km)} کیلومتر · ${n(result.app.hours)} ساعت", Modifier.testTag("report-sparse-app-total"))
                    val count = if (result.serviceCountKnown) (result.company.count - result.app.count).toString() else "نامشخص"
                    Text("اختلاف جمع شرکت − برنامه: تعداد $count · ${n(result.company.km - result.app.km)} کیلومتر · ساعت نامشخص")
                    Text("این جمع‌ها مربوط به کل بازه‌اند، از سوابق واقعی برنامه؛ ساعت خالی یا ناخوانا صفر نشده است. ساعت برنامه همان ساعت کارکرد ثبت‌شده است، نه فاصلهٔ شروع/پایان.", style = MaterialTheme.typography.bodySmall)
                    Text("در روز دارای ساعت نامشخص، همهٔ نامزدهای همان روز برای بازبینی حفظ شده‌اند؛ از برابری تعداد/کیلومتر، هویت سرویس یا اختلاف ساعت حدس زده نمی‌شود.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!result.serviceCountKnown) Text("ردیف تجمیعی با پوشش نامشخص وجود دارد؛ تطبیق سرویس‌های کل دوره معلق است. روز بدون ردیف، اثبات جاافتادن یک سرویس مشخص نیست.", color = MaterialTheme.colorScheme.error)
                    if (result.deferredLinkCount > 0) Text("${result.deferredLinkCount} تطبیق دستی به‌علت ساعت/پوشش نامشخص معلق است؛ برای تأیید ساعت را اصلاح کنید.", style = MaterialTheme.typography.bodySmall)
                    if (result.completeDays.services.isNotEmpty()) OutlinedButton(onClick = { showCompleteDays = true }, modifier = Modifier.fillMaxWidth().testTag("report-complete-days")) {
                        Text("تطبیق سخت‌گیرانهٔ سرویس‌ها در روزهای دارای اطلاعات کامل")
                    }
                    Text("خلاصهٔ روزانهٔ کل دوره", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(result.days, key = { it.date }) { day ->
                SurfaceCard(modifier = Modifier.testTag("report-sparse-daily-${day.date}")) {
                    Text("${day.date} · ${day.status.label}", style = MaterialTheme.typography.titleSmall)
                    Text(day.company?.let {
                        val count = if (day.serviceCountKnown) "${it.count} سرویس" else "تعداد سرویس نامشخص"
                        "شرکت: $count · ${n(it.km)} کیلومتر · ${it.hours?.let(::n) ?: "نامشخص"} ساعت"
                    } ?: "شرکت: در این روز ردیفی ندارد")
                    Text(day.app?.let { "خودرویار: ${it.count} سرویس · ${n(it.km)} کیلومتر · ${n(it.hours)} ساعت" } ?: "خودرویار: در این روز رکوردی ندارد")
                    Text("اختلاف شرکت − برنامه: تعداد ${day.countDifference?.toString() ?: "نامشخص"} · ${n(day.kmDifference)} کیلومتر · ${day.hoursDifference?.let(::n) ?: "نامشخص"} ساعت", style = MaterialTheme.typography.bodySmall)
                    if (day.date in result.heldDates) Text("تطبیق سرویس‌های این روز به علت ساعت یا پوشش نامشخص معلق است؛ جاافتادهٔ مشخصی حدس زده نشده.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { detailDate = day.date }) { Text("نمایش ردیف‌های شرکت و سرویس‌های واقعی این روز") }
                }
            }
        }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("بازگشت به جدول؛ هیچ سابقه‌ای تغییر نکرد") }
    }
    if (showCompleteDays) WorkComparisonDialog(result.completeDays, onLink,
        scopeNote = "فقط روزهای خارج از تعلیق با اطلاعات کامل؛ جمع‌های این پنجره جمع کل دوره نیست. نتیجهٔ کامل روزانه در پنجرهٔ قبلی است.") { showCompleteDays = false }
    detailDate?.let { date ->
        FullReportDialog({ detailDate = null }, "سوابق واقعی و ردیف‌های شرکت · $date") {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("مشاهدهٔ سوابق است، نه جفت‌سازی قطعی؛ ساعت خالی نامشخص می‌ماند.", style = MaterialTheme.typography.bodySmall) }
                item { Text("شرکت", style = MaterialTheme.typography.titleSmall) }
                items(result.companyRows.filter { it.date == date }, key = { "company:${it.id}" }) { Text(companyServiceText(it), style = MaterialTheme.typography.bodySmall) }
                item { Text("خودرویار · ساعت کارکرد ثبت‌شده", style = MaterialTheme.typography.titleSmall) }
                items(result.appServices.filter { it.date == date }, key = { "app:${it.id}" }) { Text(appServiceText(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun WorkComparisonDialog(result: WorkComparison, onLink: (List<String>, List<String>) -> Unit, scopeNote: String = "", close: () -> Unit) {
    var onlyDifferences by remember { mutableStateOf(false) }
    var showDaily by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var manualSeed by remember { mutableStateOf<ServiceWorkMatch?>(null) }
    fun n(v: java.math.BigDecimal) = PersianInput.display(v)
    FullReportDialog(close, "مقایسهٔ سرویس‌به‌سرویس شرکت و خودرویار") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("report-comparison"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SurfaceCard {
                    Text("${result.start} تا ${result.end}", style = MaterialTheme.typography.labelLarge)
                    if (scopeNote.isNotBlank()) Text(scopeNote, color = MaterialTheme.colorScheme.error)
                    val missing = result.services.filter { it.status == ServiceMatchStatus.ONLY_IN_APP }.sumOf { it.app.size }
                    val extra = result.services.filter { it.status == ServiceMatchStatus.ONLY_IN_COMPANY }.sumOf { it.company.size }
                    val ambiguous = result.services.count { it.status == ServiceMatchStatus.AMBIGUOUS }
                    Text("$missing سرویس فقط در برنامه · $extra ردیف فقط در شرکت · $ambiguous مجموعهٔ مبهم")
                    val companyCount = if (result.companyServiceCountKnown) "${result.company.count} سرویس" else "${result.companyRows.size} ردیف؛ تعداد سرویس داخل تجمیع نامشخص"
                    Text("شرکت: $companyCount · ${n(result.company.km)} کیلومتر · ${n(result.company.hours)} ساعت")
                    Text("خودرویار: ${result.app.count} سرویس · ${n(result.app.km)} کیلومتر · ${n(result.app.hours)} ساعت")
                    Text("اختلاف جمع شرکت − برنامه: ${n(result.company.km - result.app.km)} کیلومتر · ${n(result.company.hours - result.app.hours)} ساعت")
                    Text("برابر بودن جمع روز/ماه به معنی تطابق سرویس‌ها نیست. ساعت، همان ساعت کارکرد ثبت‌شده است؛ از شروع/پایان محاسبه نمی‌شود.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { manualSeed = null; manualOpen = true }, modifier = Modifier.fillMaxWidth().testTag("report-manual-link")) { Text("تعیین تطبیق دستی / پوشش ردیف تجمیعی") }
                    SwitchRow("فقط مغایرت‌ها و موارد نیازمند بررسی", "", onlyDifferences) { onlyDifferences = it }
                    SwitchRow("خلاصهٔ جمع روزها (اطلاعات کمکی)", "جایگزین نتیجهٔ سرویس‌ها نیست", showDaily) { showDaily = it }
                }
            }
            items(result.services.filter { !onlyDifferences || it.status != ServiceMatchStatus.MATCH }, key = { it.id }) { match ->
                SurfaceCard(modifier = Modifier.testTag("report-service-${match.id}")) {
                    Text(match.status.label, style = MaterialTheme.typography.titleSmall,
                        color = if (match.status == ServiceMatchStatus.MATCH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("شرکت · ${match.company.size} ردیف", style = MaterialTheme.typography.labelLarge)
                    match.company.take(20).forEach { Text(companyServiceText(it), style = MaterialTheme.typography.bodySmall) }
                    if (match.company.isEmpty()) Text("در ردیف‌های انتخاب‌شدهٔ شرکت همتایی نیست؛ ناخوانا به معنی صفر نیست.", style = MaterialTheme.typography.bodySmall)
                    if (match.company.size > 20) Text("${match.company.size - 20} ردیف دیگر در پنجرهٔ تطبیق دستی قابل مشاهده است.")
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("خودرویار · ${match.app.size} سرویس واقعی", style = MaterialTheme.typography.labelLarge)
                    match.app.take(20).forEach { Text(appServiceText(it), style = MaterialTheme.typography.bodySmall) }
                    if (match.app.isEmpty()) Text("در برنامه همتایی نیست.", style = MaterialTheme.typography.bodySmall)
                    if (match.app.size > 20) Text("${match.app.size - 20} سرویس دیگر در پنجرهٔ تطبیق دستی قابل مشاهده است.")
                    if (match.company.isNotEmpty() && match.app.isNotEmpty() && match.status != ServiceMatchStatus.MATCH) {
                        val c = match.companyTotals; val a = match.appTotals
                        Text("اختلاف جمع این مجموعه (شرکت − برنامه): ${n(c.km - a.km)} کیلومتر · ${n(c.hours - a.hours)} ساعت", style = MaterialTheme.typography.bodySmall)
                    }
                    if (match.note.isNotBlank()) Text(match.note, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { manualSeed = match; manualOpen = true }) { Text("تطبیق دستی این مورد") }
                }
            }
            if (showDaily) {
                item { Text("جمع روزها فقط برای کنترل اعداد است؛ وضعیت سرویس‌ها را بالا ببینید.", style = MaterialTheme.typography.labelLarge) }
                items(result.days, key = { "daily:${it.date}" }) { day ->
                    SurfaceCard {
                        Text(day.date)
                        Text(day.company?.let { "شرکت: ${it.count} سرویس/ردیف · ${n(it.km)} کیلومتر · ${n(it.hours)} ساعت" } ?: "شرکت: ردیفی در این روز ندارد")
                        Text(day.app?.let { "خودرویار: ${it.count} سرویس · ${n(it.km)} کیلومتر · ${n(it.hours)} ساعت" } ?: "خودرویار: رکوردی ندارد")
                        Text("${day.status.label} · اختلاف تعداد ${day.countDifference} · ${n(day.kmDifference)} کیلومتر · ${n(day.hoursDifference)} ساعت", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("بازگشت به جدول؛ هیچ سابقه‌ای تغییر نکرد") }
    }
    if (manualOpen) ReportManualLinkDialog(result, manualSeed, onApply = { c, a -> manualOpen = false; onLink(c, a) }, onClose = { manualOpen = false })
}

@Composable
private fun ReportManualLinkDialog(result: WorkComparison, seed: ServiceWorkMatch?, onApply: (List<String>, List<String>) -> Unit, onClose: () -> Unit) {
    val initialCompany = seed?.company?.let { rows -> rows.filter { it.kind == ReportRowKind.GROUPED }.ifEmpty { rows.take(1) }.map { it.id }.toSet() }.orEmpty()
    var company by remember { mutableStateOf(initialCompany) }
    var app by remember { mutableStateOf(if (seed?.status in listOf(ServiceMatchStatus.MATCH, ServiceMatchStatus.DIFFERENT)) seed?.app?.map { it.id }?.toSet().orEmpty() else emptySet<String>()) }
    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("") }
    fun visible(text: String) = PersianInput.latin(text).contains(PersianInput.latin(filter).trim(), ignoreCase = true)
    FullReportDialog(onClose, "تطبیق دستی؛ بدون تغییر سوابق") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ردیف(های) شرکت و سرویس(های) واقعی متناظر را انتخاب کنید؛ برای تجمیع یا رفت‌وبرگشت می‌توانید چند مورد انتخاب کنید. انتخاب جدید، تطبیق‌های دستی هم‌پوشان را جایگزین می‌کند.", style = MaterialTheme.typography.bodySmall)
            Text("${company.size} ردیف شرکت / ${app.size} سرویس برنامه انتخاب شده")
            OutlinedTextField(filter, { filter = it.take(100) }, label = { Text("فیلتر تاریخ، مسیر یا شناسه (اختیاری)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("ردیف‌های شرکت") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("سرویس‌های برنامه") })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == 0) items(result.companyRows.filter { visible(companyServiceText(it)) }, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(row.id in company, { checked -> company = if (checked) company + row.id else company - row.id }, modifier = Modifier.testTag("report-link-company-${row.id}"))
                    Text(companyServiceText(row), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            } else items(result.appServices.filter { visible(appServiceText(it)) }, key = { it.id }) { service ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(service.id in app, { checked -> app = if (checked) app + service.id else app - service.id }, modifier = Modifier.testTag("report-link-app-${service.id}"))
                    Text(appServiceText(service), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            val c = WorkTotals.of(result.companyRows.filter { it.id in company }.mapNotNull { it.validated() })
            val a = WorkTotals.of(result.appServices.filter { it.id in app }.map { it.work() })
            Text("انتخاب شرکت: ${PersianInput.display(c.km)} کیلومتر / ${PersianInput.display(c.hours)} ساعت · برنامه: ${PersianInput.display(a.km)} کیلومتر / ${PersianInput.display(a.hours)} ساعت", style = MaterialTheme.typography.bodySmall)
            Text("این انتخاب سندِ تطابق تک‌سرویس‌های یک تجمیع نیست. پس از اعمال، جدول را دوباره تأیید و مقایسه کنید. هیچ سرویس یا مبلغی تغییر نمی‌کند.", style = MaterialTheme.typography.labelSmall)
            Button(onClick = { onApply(result.companyRows.filter { it.id in company }.map { it.id }, result.appServices.filter { it.id in app }.map { it.id }) },
                enabled = company.isNotEmpty() && app.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("report-link-apply")) { Text("اعمال این تطبیق برای مقایسه") }
        }
    }
}
