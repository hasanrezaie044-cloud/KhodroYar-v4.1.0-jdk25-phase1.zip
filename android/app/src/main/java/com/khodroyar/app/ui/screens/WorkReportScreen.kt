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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.core.text.PersianInput
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.workreport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The existing Reports/toolkit route, not a separate activity or app. No database writer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkReportScreen(vm: WorkReportViewModel = viewModel()) {
    val progress by vm.progress.collectAsState()
    var preview by remember { mutableStateOf<ReportPage?>(null) }
    var showColumns by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var pageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showComparison by remember { mutableStateOf(false) }
    val horizontal = rememberScrollState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importDocuments(uris)
    }
    fun changePages(action: () -> Unit) { if (vm.rows.isEmpty()) action() else pageAction = action }
    LaunchedEffect(vm.comparison) { if (vm.comparison != null) showComparison = true }
    BackHandler(vm.busy) { vm.stop() }

    Column(Modifier.fillMaxSize().testTag("work-report-screen")) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { changePages { picker.launch(arrayOf("image/*", "application/pdf")) } }, enabled = !vm.busy,
                modifier = Modifier.testTag("report-import")) { Icon(Icons.Outlined.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("انتخاب عکس / PDF") }
            TextButton(onClick = { confirmReset = true }, enabled = !vm.busy && vm.pages.isNotEmpty()) { Text("پاک کردن پیش‌نویس") }
        }
        if (vm.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(progress.ifBlank { "در حال آماده‌سازی…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::stop) { Text("توقف") }
            }
        }
        vm.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(12.dp), shape = FieldShape) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = vm::clearError) { Icon(Icons.Outlined.Close, "بستن خطا") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("work-report-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.pages.isEmpty()) item {
                EmptyState("گزارش شرکت را انتخاب کنید", "خواندن فارسی، عربی و انگلیسی کاملاً روی گوشی انجام می‌شود. سندی ارسال نمی‌شود.", Icons.Outlined.DocumentScanner)
            }
            if (vm.pages.isNotEmpty()) item {
                Text("${vm.pages.size} صفحه · ترتیب و جهت را قبل از پردازش بررسی کنید", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vm.pages, key = { it.id }) { page ->
                        SurfaceCard(modifier = Modifier.width(228.dp), contentPadding = 8.dp) {
                            Text("صفحهٔ ${vm.pages.indexOf(page) + 1} · ${page.name}", maxLines = 2, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { preview = page }, modifier = Modifier.fillMaxWidth()) {
                                PageImage(vm.documents, page, Modifier.fillMaxWidth().height(120.dp), thumbnail = true)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = { changePages { vm.move(page.id, -1) } }, enabled = !vm.busy && page != vm.pages.first()) { Icon(Icons.Outlined.ArrowUpward, "صفحه جلوتر") }
                                IconButton(onClick = { changePages { vm.move(page.id, 1) } }, enabled = !vm.busy && page != vm.pages.last()) { Icon(Icons.Outlined.ArrowDownward, "صفحه عقب‌تر") }
                                IconButton(onClick = { changePages { vm.rotate(page.id) } }, enabled = !vm.busy) { Icon(Icons.Outlined.RotateRight, "چرخش ۹۰ درجه") }
                                IconButton(onClick = { changePages { vm.remove(page.id) } }, enabled = !vm.busy) { Icon(Icons.Outlined.DeleteOutline, "حذف صفحه") }
                            }
                        }
                    }
                }
                SwitchRow("چیدمان جدول پراکنده", "برای جدول‌های خط‌کشی‌شده روشن؛ برای متن معمولی خاموش", vm.sparse) { if (!vm.busy) vm.sparse = it }
                OutlinedButton(onClick = { changePages { vm.recognize() } }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().testTag("report-ocr")) {
                    Icon(Icons.Outlined.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text(if (vm.rows.isEmpty()) "خواندن آفلاین صفحه‌ها" else "خواندن دوباره (بازنشانی ویرایش‌ها)")
                }
            }
            if (vm.recognized.isNotEmpty()) item {
                SurfaceCard {
                    Text("جدول بازبینی", style = MaterialTheme.typography.titleMedium)
                    Text("${vm.rows.count { it.included }} ردیف انتخاب‌شده · ${vm.rows.count { !it.included }} ردیف کنارگذاشته‌شده", style = MaterialTheme.typography.bodySmall)
                    Text("خانهٔ خالی یعنی ناخوانا/نامشخص، نه صفر. ساعت اعشاری یا HH:mm؛ مسیرها در مقایسه نقشی ندارند.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { showColumns = true }, enabled = !vm.busy) { Text("تنظیم ستون‌های OCR") }
                        TextButton(onClick = vm::addRow, enabled = !vm.busy) { Text("افزودن ردیف دستی") }
                    }
                    JalaliDateField("ابتدای بازهٔ کامل گزارش", vm.start, { vm.range(it, vm.end) })
                    Spacer(Modifier.height(8.dp))
                    JalaliDateField("پایان بازهٔ کامل گزارش", vm.end, { vm.range(vm.start, it) })
                    Text("بازه را از سند بررسی کنید؛ روزهایی که فقط در برنامه هستند هم در همین بازه مقایسه می‌شوند.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (vm.rows.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(horizontal).width(760.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("شامل", Modifier.width(48.dp)); Text("تاریخ", Modifier.width(160.dp)); Text("تعداد", Modifier.width(90.dp))
                    Text("کیلومتر", Modifier.width(120.dp)); Text("ساعت", Modifier.width(120.dp)); Text("اصل صفحه", Modifier.width(120.dp))
                }
            }
            items(vm.rows, key = { it.id }) { row ->
                ReportRowEditor(row, horizontal, enabled = !vm.busy, onChange = vm::edit,
                    onPreview = { preview = vm.pages.firstOrNull { it.id == row.pageId } })
            }
            if (vm.rows.isNotEmpty()) item {
                SurfaceCard {
                    val invalid = vm.rows.count { it.included && it.validated() == null }
                    if (invalid > 0) Text("$invalid ردیف مقدار نامعتبر/ناخوانا دارد؛ ابتدا اصلاح کنید یا صریحاً از مقایسه کنار بگذارید.", color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.reviewConfirmed, onCheckedChange = { if (!vm.busy) vm.setReview(it) }, modifier = Modifier.testTag("report-review-confirm"))
                        Text("جدول، صفحه‌ها، بازه و ردیف‌های کنارگذاشته‌شده را با سند اصلی بازبینی کردم.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (vm.rows.isNotEmpty()) Surface(shadowElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Button(onClick = vm::compare, enabled = !vm.busy && vm.reviewConfirmed, modifier = Modifier.fillMaxWidth().testTag("report-compare")) { Text("مقایسهٔ روزبه‌روز با خودرویار") }
                vm.comparison?.let { TextButton(onClick = { showComparison = true }, modifier = Modifier.fillMaxWidth()) { Text("نمایش نتیجهٔ مقایسه") } }
            }
        }
    }
    preview?.let { page ->
        FullReportDialog(onClose = { preview = null }, title = "اصل صفحه · ${page.name}") {
            PageImage(vm.documents, page, Modifier.weight(1f).fillMaxWidth(), thumbnail = false)
            Text("چرخش و ترتیب از فهرست صفحه‌ها قابل تغییر است.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showColumns) ColumnMappingDialog(onApply = { vm.remap(it); showColumns = false }, onDismiss = { showColumns = false })
    if (confirmReset) ConfirmDialog("پاک کردن پیش‌نویس مقایسه", "فقط اسناد واردشده و جدول OCR پاک می‌شود؛ سرویس‌ها و داده‌های اصلی برنامه تغییر نمی‌کنند.",
        confirmLabel = "پاک کردن", destructive = true, onConfirm = { confirmReset = false; vm.reset() }, onDismiss = { confirmReset = false })
    pageAction?.let { action -> ConfirmDialog("بازنشانی جدول OCR", "تغییر صفحه‌ها یا خواندن دوباره، ویرایش‌های جدول فعلی را پاک می‌کند. سوابق اصلی برنامه تغییر نمی‌کنند.",
        confirmLabel = "ادامه", onConfirm = { pageAction = null; action() }, onDismiss = { pageAction = null }) }
    if (showComparison) vm.comparison?.let { result -> WorkComparisonDialog(result) { showComparison = false } }
}

@Composable
private fun ReportRowEditor(row: ReportRow, scroll: ScrollState, enabled: Boolean, onChange: (ReportRow) -> Unit, onPreview: () -> Unit) {
    SurfaceCard(contentPadding = 8.dp) {
        Row(Modifier.horizontalScroll(scroll).width(760.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(row.included, { onChange(row.copy(included = it)) }, enabled = enabled, modifier = Modifier.width(48.dp))
            ReportCell(row.date, "تاریخ", 160, enabled, row.included && PersianInput.date(row.date) == null, "report-cell-${row.id}-date") { onChange(row.copy(date = it)) }
            ReportCell(row.count, "تعداد", 90, enabled, row.included && PersianInput.count(row.count) == null, "report-cell-${row.id}-count") { onChange(row.copy(count = it)) }
            ReportCell(row.km, "کیلومتر", 120, enabled, row.included && PersianInput.decimal(row.km) == null, "report-cell-${row.id}-km") { onChange(row.copy(km = it)) }
            ReportCell(row.hours, "ساعت", 120, enabled, row.included && PersianInput.hours(row.hours) == null, "report-cell-${row.id}-hours") { onChange(row.copy(hours = it)) }
            TextButton(onClick = onPreview, modifier = Modifier.width(120.dp)) { Text("صفحهٔ ${row.page}") }
        }
        Text(row.note, color = if (row.included && row.validated() == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
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
        value = withContext(Dispatchers.IO) { try { documents.render(page, if (thumbnail) 120000 else 2400000, if (thumbnail) 420 else 2000) }
            catch (_: Exception) { failure = true; null } }
    }
    val image = bitmap
    DisposableEffect(image) { onDispose { image?.recycle() } }
    if (image != null) Image(image.asImageBitmap(), "اصل صفحهٔ گزارش", modifier, contentScale = ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) { if (failure) Text("پیش‌نمایش قابل خواندن نیست") else CircularProgressIndicator(Modifier.size(24.dp)) }
}
@Composable
private fun FullReportDialog(onClose: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
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
private fun ColumnMappingDialog(onApply: (ReportColumns) -> Unit, onDismiss: () -> Unit) {
    var count by remember { mutableStateOf("") }; var km by remember { mutableStateOf("") }; var hours by remember { mutableStateOf("") }
    val mapping = ReportColumns(PersianInput.count(count), PersianInput.count(km), PersianInput.count(hours))
    FullReportDialog(onDismiss, "ستون‌های جدول") {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("اگر سرستون‌ها خوانا نیستند، شمارهٔ خانه‌های عددی را از راست به چپ وارد کنید؛ خانهٔ تاریخ هم شمرده می‌شود. نتیجه را با تصویر تطبیق دهید. اعمال این تنظیم ویرایش‌های جدول را بازنشانی می‌کند.")
            OutlinedTextField(count, { count = it }, label = { Text("شمارهٔ ستون تعداد") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(km, { km = it }, label = { Text("شمارهٔ ستون کیلومتر") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(hours, { hours = it }, label = { Text("شمارهٔ ستون ساعت") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApply(mapping) }, enabled = mapping.valid(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("اعمال ستون‌ها") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("انصراف") }
            }
            TextButton(onClick = { onApply(ReportColumns()) }, modifier = Modifier.fillMaxWidth()) { Text("تشخیص از سرستون‌ها") }
        }
    }
}
@Composable
private fun WorkComparisonDialog(result: WorkComparison, close: () -> Unit) {
    var onlyDifferences by remember { mutableStateOf(false) }
    fun n(v: java.math.BigDecimal) = PersianInput.display(v)
    val mismatches = result.days.count { it.status != DayStatus.MATCH }
    FullReportDialog(close, "مقایسهٔ گزارش شرکت و خودرویار") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("report-comparison"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SurfaceCard {
                    Text("${result.start} تا ${result.end}", style = MaterialTheme.typography.labelLarge)
                    Text("$mismatches روز دارای مغایرت؛ برابر بودن جمع کل به معنی تطابق روزها نیست.")
                    Text("شرکت: ${result.company.count} سرویس · ${n(result.company.km)} کیلومتر · ${n(result.company.hours)} ساعت")
                    Text("خودرویار: ${result.app.count} سرویس · ${n(result.app.km)} کیلومتر · ${n(result.app.hours)} ساعت")
                    Text("اختلاف شرکت − برنامه: ${result.company.count - result.app.count} سرویس · ${n(result.company.km - result.app.km)} کیلومتر · ${n(result.company.hours - result.app.hours)} ساعت")
                    SwitchRow("فقط روزهای مغایر", "", onlyDifferences) { onlyDifferences = it }
                }
            }
            items(result.days.filter { !onlyDifferences || it.status != DayStatus.MATCH }, key = { it.date }) { day ->
                SurfaceCard {
                    Text("${day.date} · ${day.status.label}", color = if (day.status == DayStatus.MATCH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text(day.company?.let { "شرکت: ${it.count} سرویس · ${n(it.km)} کیلومتر · ${n(it.hours)} ساعت" } ?: "شرکت: رکوردی ندارد (مقدار ناخوانا نیست)")
                    Text(day.app?.let { "خودرویار: ${it.count} سرویس · ${n(it.km)} کیلومتر · ${n(it.hours)} ساعت" } ?: "خودرویار: رکوردی ندارد")
                    Text("اختلاف: ${day.countDifference} سرویس · ${n(day.kmDifference)} کیلومتر · ${n(day.hoursDifference)} ساعت", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("بازگشت به جدول؛ هیچ سابقه‌ای تغییر نکرد") }
    }
}
