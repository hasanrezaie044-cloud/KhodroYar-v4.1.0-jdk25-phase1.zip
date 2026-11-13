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
    var onlineConsent by remember { mutableStateOf(false) }
    val horizontal = rememberScrollState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importDocuments(uris)
    }
    fun changePages(action: () -> Unit) { if (vm.rows.isEmpty()) action() else pageAction = action }
    LaunchedEffect(vm.comparison, vm.sparseComparison) { if (vm.comparison != null || vm.sparseComparison != null) showComparison = true }
    LaunchedEffect(vm.recognized) { horizontal.scrollTo(0) }
    BackHandler(vm.busy) { vm.stop() }

    Column(Modifier.fillMaxSize().testTag("work-report-screen")) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { changePages { picker.launch(arrayOf("image/*", "application/pdf")) } }, enabled = !vm.busy,
                modifier = Modifier.testTag("report-import")) { Icon(Icons.Outlined.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("انتخاب عکس / PDF") }
            TextButton(onClick = { confirmReset = true }, enabled = !vm.busy) { Text("پاک کردن پیش‌نویس") }
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
                OutlinedButton(onClick = { onlineConsent = true }, enabled = !vm.busy && vm.pages.size in 1..10,
                    modifier = Modifier.fillMaxWidth().testTag("report-online-assist")) {
                    Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("پیش‌پر اختیاری آنلاین با FastOCR (تصاویر به api.fastocr.org فرستاده می‌شود)")
                }
            }
            if (vm.recognized.isNotEmpty()) item {
                SurfaceCard {
                    Text("جدول بازبینی", style = MaterialTheme.typography.titleMedium)
                    Text("${vm.rows.count { it.included }} ردیف انتخاب‌شده · ${vm.rows.count { !it.included }} ردیف کنارگذاشته‌شده", style = MaterialTheme.typography.bodySmall)
                    Text("هر ردیف فرم شرکت یک سرویس است؛ «ردیف» تعداد سرویس نیست. ساعت خالی، نقطه یا خط تیره نامشخص است، نه صفر؛ مقایسهٔ تعداد/کیلومتر ممکن است ولی ساعت و هویت سرویس قطعی نمی‌شود. ساعت عددی اعشاری یا HH:mm؛ مسیر فقط برای شناسایی در بازبینی نمایش داده می‌شود.", style = MaterialTheme.typography.bodySmall)
                    if (vm.rows.any { it.kind == ReportRowKind.GROUPED }) Text("شرح تجمیعی دیده شده است؛ پوشش آن در نتیجه باید دستی تعیین شود تا سرویس پوشش‌داده‌شده، جاافتاده اعلام نشود.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (vm.links.isNotEmpty()) TextButton(onClick = vm::clearLinks, enabled = !vm.busy) { Text("بازنشانی ${vm.links.size} تطبیق دستی (بدون تغییر سوابق)") }
                    Row {
                        TextButton(onClick = { showColumns = true }, enabled = !vm.busy) { Text("تنظیم ستون‌های OCR") }
                        TextButton(onClick = vm::addRow, enabled = !vm.busy) { Text("افزودن ردیف دستی") }
                    }
                    JalaliDateField("ابتدای بازهٔ کامل گزارش", vm.start, { vm.range(it, vm.end) })
                    Spacer(Modifier.height(8.dp))
                    JalaliDateField("پایان بازهٔ کامل گزارش", vm.end, { vm.range(vm.start, it) })
                    Text(vm.periodNote.ifBlank { "بازه را از سند بررسی کنید." }, style = MaterialTheme.typography.labelSmall)
                    Text("همهٔ ۲ یا ۳ برگهٔ یک دوره را با هم انتخاب کنید. روزهای اول/آخر ماه که فقط در برنامه هستند هم باید در بازه باشند.", style = MaterialTheme.typography.labelSmall)
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
                    val invalid = vm.rows.count { it.included && it.reviewed() == null }
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
                Button(onClick = vm::compare, enabled = !vm.busy && vm.reviewConfirmed, modifier = Modifier.fillMaxWidth().testTag("report-compare")) { Text("مقایسهٔ تک‌تک سرویس‌ها با خودرویار") }
                if (vm.comparison != null || vm.sparseComparison != null) TextButton(onClick = { showComparison = true }, modifier = Modifier.fillMaxWidth()) { Text("نمایش نتیجهٔ مقایسه") }
            }
        }
    }
    preview?.let { page ->
        FullReportDialog(onClose = { preview = null }, title = "اصل صفحه · ${page.name}") {
            PageImage(vm.documents, page, Modifier.weight(1f).fillMaxWidth(), thumbnail = false)
            Text("چرخش و ترتیب از فهرست صفحه‌ها قابل تغییر است.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showColumns) ColumnMappingDialog(singleServiceDefault = vm.rows.any { it.kind != ReportRowKind.DAILY_TOTAL }, onApply = { vm.remap(it); showColumns = false }, onDismiss = { showColumns = false })
    if (confirmReset) ConfirmDialog("پاک کردن پیش‌نویس مقایسه", "فقط اسناد واردشده و جدول OCR پاک می‌شود؛ سرویس‌ها و داده‌های اصلی برنامه تغییر نمی‌کنند.",
        confirmLabel = "پاک کردن", destructive = true, onConfirm = { confirmReset = false; vm.reset() }, onDismiss = { confirmReset = false })
    pageAction?.let { action -> ConfirmDialog("بازنشانی جدول OCR", "تغییر صفحه‌ها یا خواندن دوباره، ویرایش‌های جدول فعلی را پاک می‌کند. سوابق اصلی برنامه تغییر نمی‌کنند.",
        confirmLabel = "ادامه", onConfirm = { pageAction = null; action() }, onDismiss = { pageAction = null }) }
    if (onlineConsent) FastOcrAssistDialog(vm) { onlineConsent = false }
    if (showComparison) {
        val partial = vm.sparseComparison
        if (partial != null) SparseHoursComparisonDialog(partial, onLink = { company, app -> vm.link(company, app); showComparison = false }) { showComparison = false }
        else vm.comparison?.let { result -> WorkComparisonDialog(result, onLink = { company, app -> vm.link(company, app); showComparison = false }) { showComparison = false } }
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
            ReportCell(row.hours, "ساعت (می‌تواند خالی باشد)", 120, enabled,
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
        Text((listOf(row.note) + if (row.included) row.fieldMessages() else emptyList()).filter { it.isNotBlank() }.joinToString("\n"),
            color = if (row.included && row.reviewed() == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
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
private fun ColumnMappingDialog(singleServiceDefault: Boolean, onApply: (ReportColumns) -> Unit, onDismiss: () -> Unit) {
    var count by remember { mutableStateOf("") }; var km by remember { mutableStateOf("") }; var hours by remember { mutableStateOf("") }
    var singleService by remember { mutableStateOf(singleServiceDefault) }
    val mapping = ReportColumns(if (singleService) null else PersianInput.count(count), PersianInput.count(km), PersianInput.count(hours), singleService)
    FullReportDialog(onDismiss, "ستون‌های جدول") {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("اگر سرستون‌ها خوانا نیستند، شمارهٔ خانه‌های عددی را از راست به چپ وارد کنید؛ خانهٔ تاریخ هم شمرده می‌شود. نتیجه را با تصویر تطبیق دهید. اعمال این تنظیم ویرایش‌های جدول را بازنشانی می‌کند.")
            SwitchRow("هر ردیف یک سرویس است", "برای فرم شرکت روشن؛ ستون «ردیف» هرگز تعداد نیست", singleService) { singleService = it }
            if (!singleService) OutlinedTextField(count, { count = it }, label = { Text("شمارهٔ ستون تعداد واقعی (نه ردیف)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
private fun companyServiceText(row: ReportRow): String =
    "${row.date} · ${row.km} کیلومتر · ${row.hours.ifBlank { "نامشخص" }} ساعت · صفحهٔ ${row.page} / ردیف ${row.ordinal.ifBlank { (row.line + 1).toString() }}" +
        (if (row.origin.isNotBlank() || row.destination.isNotBlank()) "\n${row.origin} ← ${row.destination}" else "") +
        (if (row.kind == ReportRowKind.GROUPED) " · تجمیعی" else "")

private fun appServiceText(service: AppWorkService): String =
    "${service.date} · ${PersianInput.display(service.km)} کیلومتر · ${PersianInput.display(service.hours)} ساعت" +
        "\n${service.type} · ${service.origin} ← ${service.destination}" +
        (if (service.startTime.isNotBlank() || service.endTime.isNotBlank()) " · ${service.startTime.ifBlank { "—" }} تا ${service.endTime.ifBlank { "—" }}" else "") +
        " · شناسه ${service.id.take(8)}" + (if (service.requestNumber.isNotBlank()) " · درخواست ${service.requestNumber}" else "")

/** Explicit-consent optional online assist via FastOCR; offline remains the default everywhere. */
@Composable
private fun FastOcrAssistDialog(vm: WorkReportViewModel, close: () -> Unit) {
    var keyDraft by remember { mutableStateOf("") }
    FullReportDialog(close, "پیش‌پر اختیاری آنلاین صفحه‌ها با FastOCR") {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("پردازش عادی این صفحه کاملاً آفلاین است و همین‌طور می‌ماند. این دکمه فقط با تأیید صریح شما فعال می‌شود.", style = MaterialTheme.typography.bodySmall)
            Text("در صورت تأیید، تصویرِ ${vm.pages.size} صفحه (هرکدام حداکثر ${FastOcrClient.MAX_IMAGE_BYTES / 1_000_000} مگابایت JPEG) فقط یک‌بار و فقط برای همین دوره به api.fastocr.org ارسال می‌شود و در برنامه نگه داشته نمی‌شود. این سرویس متعلق به برنامه نیست؛ فقط با کلید خودِ شما و به حساب خودِ شماست.", style = MaterialTheme.typography.bodySmall)
            if (vm.fastOcrKeySaved) {
                Text("کلید API شما از قبل رمزنگاری‌شده ذخیره است؛ برای امنیت دوباره نمایش داده نمی‌شود. حذف یا عوض کردنش از دکمهٔ زیر ممکن است.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { keyDraft = ""; vm.clearFastOcrKey() }) { Text("حذف کلید ذخیره‌شده") }
            } else {
                Text("کلید API خود را از حساب FastOCR (شروع با fok_live_) بسازید و این‌جا وارد کنید؛ فقط یک‌بار لازم است:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(keyDraft, { keyDraft = it.trim() }, label = { Text("کلید API شما (fok_live_…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("report-fastocr-key"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                Text("کلید فقط روی همین گوشی و رمزنگاری‌شده نگه داشته می‌شود و در هیچ گزارش/صادراتی درج نمی‌شود.", style = MaterialTheme.typography.labelSmall)
            }
            Text("گیرندهٔ تصاویر: api.fastocr.org", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("متن دریافتی فقط جدول بازبینی را پیش‌پر می‌کند: هیچ مقداری خودکار صفر، ذخیره یا به سوابق اضافه نمی‌شود، خانه‌های خالی/نقطه/خط‌تیره دست‌نخورده می‌مانند و هر ردیف با همان ویرایشگر دستی قابل اصلاح است.", style = MaterialTheme.typography.bodySmall)
            Text("سند ممکن است نام و مبالغ محرمانه داشته باشد؛ ارسال به یک سرویس بیرونی و حذف دائمی آن (حداکثر ۳۰ روز نزد آن‌ها) مسئولیت و توافق خودتان است.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val keyReady = vm.fastOcrKeySaved || keyDraft.startsWith("fok_")
                Button(onClick = {
                    val ok = if (vm.fastOcrKeySaved) true else vm.saveFastOcrKey(keyDraft)
                    if (ok) { vm.recognizeFastOcr(); close() }
                }, enabled = keyReady && vm.pages.isNotEmpty() && !vm.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("report-online-send")) { Text("ارسال به api.fastocr.org و پیش‌پر کردن") }
                OutlinedButton(onClick = close, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("انصراف؛ هیچ چیز ارسال نشود") }
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
