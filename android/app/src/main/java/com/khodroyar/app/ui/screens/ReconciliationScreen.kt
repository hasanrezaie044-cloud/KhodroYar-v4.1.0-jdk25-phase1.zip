package com.khodroyar.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khodroyar.app.core.persian.PersianText
import com.khodroyar.app.data.backup.BackupFiles
import com.khodroyar.app.reconciliation.*
import com.khodroyar.app.ui.components.CardShape
import com.khodroyar.app.ui.components.FieldShape
import com.khodroyar.app.ui.components.fieldColors
import com.khodroyar.app.ui.theme.AppDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val MatchGreen = Color(0xFF23824B)
private val ReviewOrange = Color(0xFFAF6500)
private val DifferenceRed = Color(0xFFBF303A)

/** An additional tool, not a redesign of any existing tab. All business logic lives outside Compose. */
@Composable
fun ReconciliationScreen(vm: ReconciliationViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<StatementRow?>(null) }
    var preview by remember { mutableStateOf<ImportedStatementPage?>(null) }
    var remove by remember { mutableStateOf<ImportedStatementPage?>(null) }
    var columnsVisible by rememberSaveable { mutableStateOf(false) }
    var mapped by rememberSaveable { mutableStateOf(false) }
    var ds by rememberSaveable { mutableStateOf("67") }; var de by rememberSaveable { mutableStateOf("100") }
    var ks by rememberSaveable { mutableStateOf("34") }; var ke by rememberSaveable { mutableStateOf("66") }
    var hs by rememberSaveable { mutableStateOf("0") }; var he by rememberSaveable { mutableStateOf("33") }
    var exportText by remember { mutableStateOf("") }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var rangeConfirmed by rememberSaveable(vm.from, vm.to) { mutableStateOf(false) }
    val duplicates = remember(vm.rows) { ReconciliationEngine.duplicateCandidates(vm.rows) }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { BackupFiles.writeToUri(context, uri, exportText) }
            exportResult = if (ok) "گزارش ذخیره شد." else "ذخیرهٔ گزارش انجام نشد."
        }
    }
    fun band(start: String, end: String): ColumnBand? {
        val a = PersianText.decimal(start)?.toDouble() ?: return null
        val b = PersianText.decimal(end)?.toDouble() ?: return null
        return ColumnBand(a / 100, b / 100)
    }
    val columns = if (!mapped) null else {
        val d = band(ds, de); val k = band(ks, ke); val h = band(hs, he)
        if (d != null && k != null && h != null) StatementColumns(d, k, h).takeIf { it.valid() } else null
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(AppDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WorkCard {
                    Text("مغایرت‌گیری کارکرد", style = MaterialTheme.typography.titleLarge)
                    Text("۱. ورود سند  ←  ۲. بازبینی صفحات  ←  ۳. اصلاح ردیف‌ها  ←  ۴. بازه و گزارش", style = MaterialTheme.typography.bodySmall)
                    Text("فقط تاریخ شمسی، تعداد سرویس، کیلومتر و ساعت مقایسه می‌شود. مسیر و شرح اثری ندارند. این ابزار هیچ سرویس موجودی را تغییر نمی‌دهد.")
                    Text("خواندن فارسی با افزونهٔ اختیاری Tesseract و دادهٔ fas انجام می‌شود؛ پردازش محلی است. بدون افزونه هم ورود دستی ممکن است.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { runCatching { vm.ocr.openSetup() }.onFailure { exportResult = it.message } }, enabled = !vm.busy) { Text("راه‌اندازی افزونهٔ OCR فارسی") }
                    Button(onClick = { importFiles.launch(arrayOf("image/*", "application/pdf")) }, enabled = !vm.busy && vm.loaded && vm.pages.size < 40) { Text("ورود تصویر، چند تصویر یا PDF") }
                    Text("فایل‌ها در فضای خصوصی برنامه نگه داشته می‌شوند. حداکثر ۴۰ صفحه و ۸۰ مگابایت برای هر فایل؛ بدون مجوز دسترسی عمومی فایل‌ها.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (vm.busy) item {
                WorkCard {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(vm.progress.ifBlank { "در حال آماده‌سازی…" })
                    TextButton(onClick = vm::cancel) { Text("توقف") }
                }
            }
            vm.message?.let { message -> item {
                WorkCard {
                    Text(message, color = ReviewOrange)
                    TextButton(onClick = vm::dismissMessage) { Text("بستن پیام") }
                }
            } }
            items(vm.pages, key = { "page-${it.id}" }) { page ->
                WorkCard {
                    val index = vm.pages.indexOfFirst { it.id == page.id }
                    Text("صفحه ${PersianText.fa((index + 1).toString())}", style = MaterialTheme.typography.titleMedium)
                    val shown = if (vm.files.rendered(page).isFile && page.processed) vm.files.rendered(page) else vm.files.image(page)
                    StatementImage(shown, modifier = Modifier.fillMaxWidth().height(190.dp).clickable { preview = page }, angle = if (page.processed) 0f else page.rotation + page.deskew, enhance = !page.processed && page.enhance)
                    Text("برای تصویر بزرگ‌تر لمس کنید. با چرخاندن یا تغییر پردازش، ردیف‌های این صفحه باید دوباره استخراج شوند.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { preview = page }, enabled = !vm.busy) { Text("بزرگ‌نمایی") }
                        TextButton(onClick = { vm.updatePage(page.copy(rotation = (page.rotation + 90) % 360)) }, enabled = !vm.busy) { Text("چرخش ۹۰°") }
                        TextButton(onClick = { vm.movePage(page.id, -1) }, enabled = !vm.busy && index > 0) { Text("صفحهٔ قبل") }
                        TextButton(onClick = { vm.movePage(page.id, 1) }, enabled = !vm.busy && index < vm.pages.lastIndex) { Text("صفحهٔ بعد") }
                        TextButton(onClick = { remove = page }, enabled = !vm.busy) { Text("حذف صفحه") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(page.enhance, { vm.updatePage(page.copy(enhance = it)) }, enabled = !vm.busy)
                        Text("افزایش ملایم کنتراست و خاکستری")
                    }
                    var angle by remember(page.id, page.deskew) { mutableFloatStateOf(page.deskew) }
                    Text("اصلاح کجی دستی: ${PersianText.fa("%.1f".format(java.util.Locale.ROOT, angle))} درجه", style = MaterialTheme.typography.bodySmall)
                    Slider(value = angle, onValueChange = { angle = it }, onValueChangeFinished = { if (angle != page.deskew) vm.updatePage(page.copy(deskew = angle)) }, valueRange = -5f..5f, enabled = !vm.busy)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(page.reviewed, { vm.reviewPage(page.id, it) }, enabled = !vm.busy)
                        Text("تمام تصویر را دیدم؛ همهٔ سرویس‌ها وارد شده‌اند و ردیف‌های جمع/توضیح کنار گذاشته شده‌اند.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.addRow(page.id) }, enabled = !vm.busy) { Text("افزودن ردیف دستی") }
                        if (page.processed) TextButton(onClick = { vm.updatePage(page) }, enabled = !vm.busy) { Text("پاک‌کردن استخراج این صفحه") }
                    }
                }
            }
            if (vm.pages.isNotEmpty()) item {
                WorkCard {
                    Text("خواندن جدول", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { columnsVisible = !columnsVisible }) { Text("تنظیم محل ستون‌ها روی تصویر") }
                    if (columnsVisible) {
                        Text("درصد افقی از لبهٔ چپ تصویر: صفر = چپ، صد = راست. محدودهٔ واقعی هر ستون را از تصویر مشخص کنید، خصوصاً اگر جدول ستون‌های اضافه دارد. ترتیب مسیر و نام سفر اهمیتی ندارد.", style = MaterialTheme.typography.bodySmall)
                        ColumnRange("تاریخ", ds, de, { ds = it; mapped = false }, { de = it; mapped = false })
                        ColumnRange("کیلومتر", ks, ke, { ks = it; mapped = false }, { ke = it; mapped = false })
                        ColumnRange("ساعت", hs, he, { hs = it; mapped = false }, { he = it; mapped = false })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(mapped, { mapped = it }, enabled = !vm.busy)
                            Text("محل سه ستون را روی تصویر تأیید می‌کنم.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (mapped && columns == null) Text("محدودهٔ ستون‌ها نامعتبر یا هم‌پوشان است.", color = DifferenceRed)
                    Text("بدون تأیید ستون‌ها، هیچ کیلومتر یا ساعتِ بدون برچسب حدس زده نمی‌شود؛ آن‌ها را در بازبینی دستی کامل کنید. صفحه‌های متفاوت را جداگانه و با محدودهٔ مناسب بخوانید.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.extract(columns) }, enabled = !vm.busy && (!mapped || columns != null) && vm.pages.any { !it.processed }) { Text("استخراج آفلاین صفحات انجام‌نشده") }
                }
            }
            if (vm.rows.isNotEmpty()) item {
                Text("بازبینی و اصلاح ردیف‌ها", style = MaterialTheme.typography.titleMedium)
                Text("مقادیر خالی صفر نیستند. هر ردیف سرویس باید با تصویر تأیید شود. ردیف روزانهٔ تجمیعی را فقط با تعداد واقعی سرویس اصلاح کنید؛ جمع دوره نباید سرویس حساب شود.", style = MaterialTheme.typography.bodySmall)
            }
            items(vm.rows, key = { "row-${it.id}" }) { row ->
                val valid = ReconciliationEngine.values(row) != null
                val needsDuplicateReview = row.id in duplicates && !row.keepDuplicate
                val accepted = row.confirmed && valid && !needsDuplicateReview
                val inactive = row.excluded || row.kind != StatementRowKind.SERVICE
                val color = if (inactive) MaterialTheme.colorScheme.onSurfaceVariant else if (accepted) MatchGreen else ReviewOrange
                WorkCard {
                    val number = vm.pages.indexOfFirst { it.id == row.pageId } + 1
                    Text("صفحه ${PersianText.fa(number.toString())} · ${if (inactive) "کنار گذاشته شده" else if (accepted) "تأییدشده" else "نیاز به بازبینی"}", color = color, style = MaterialTheme.typography.titleSmall)
                    if (row.kind == StatementRowKind.SERVICE && !row.excluded) {
                        Text("${row.date.ifBlank { "تاریخ ناخوانا" }} · ${row.count} سرویس · ${row.kilometers.ifBlank { "؟" }} کیلومتر · ${row.hours.ifBlank { "؟" }} ساعت", style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                    }
                    Text(row.rawText.ifBlank { "ردیف دستی" }, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    if (!inactive && row.confidence < 80) Text("اطمینان OCR پایین یا ورود دستی؛ تصویر را حتماً بررسی کنید.", color = ReviewOrange, style = MaterialTheme.typography.bodySmall)
                    if (needsDuplicateReview && !inactive) Text("مقادیر مشابه در صفحهٔ دیگر: تکراری بودن قطعی نیست. در پنجرهٔ اصلاح، نگه‌داشتن هر دو یا کنارگذاشتن نسخهٔ تکراری را انتخاب کنید.", color = ReviewOrange, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editing = row }, enabled = !vm.busy) { Text("تصویر، اصلاح و تأیید") }
                }
            }
            if (vm.pages.isNotEmpty()) item {
                WorkCard {
                    Text("انتخاب بازهٔ شمسی", style = MaterialTheme.typography.titleMedium)
                    WorkField("از تاریخ (۱۴۰۵/۰۱/۰۱)", vm.from, { vm.range(it, vm.to) }, !vm.busy)
                    WorkField("تا تاریخ (۱۴۰۵/۰۱/۳۱)", vm.to, { vm.range(vm.from, it) }, !vm.busy)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rangeConfirmed, { rangeConfirmed = it }, enabled = !vm.busy)
                        Text("بازهٔ گزارش را تأیید می‌کنم؛ روزهای بیرون بازه محاسبه نمی‌شوند.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = vm::compare, enabled = !vm.busy && rangeConfirmed) { Text("مقایسه با سرویس‌های واقعی خودرویار") }
                    Text("خواندن فقط از پایگاه دادهٔ فعلی است؛ ساعت همان فیلد ساعت کارکرد در فرم سرویس است. اصلاح صورت‌وضعیت هیچ تغییری در سرویس‌ها ایجاد نمی‌کند.", style = MaterialTheme.typography.bodySmall)
                }
            }
            vm.report?.let { report ->
                item { ReportSummary(report) }
                item {
                    OutlinedButton(onClick = {
                        exportText = ReconciliationTextReport.write(report)
                        saveReport.launch("KhodroYar-work-${report.from.replace('/', '-')}_${report.to.replace('/', '-')}.txt")
                    }) { Text(if (report.final) "ذخیرهٔ گزارش فارسی" else "ذخیرهٔ پیش‌نمایش غیرقطعی") }
                }
                item { DailyTable(report) }
                items(report.days, key = { "day-${it.date}" }) { day ->
                    WorkCard {
                        Text(PersianText.fa(day.date), color = statusColor(day.status), style = MaterialTheme.typography.titleMedium)
                        day.explanations.forEach { Text(it) }
                    }
                }
                if (report.reviewIssues.isNotEmpty()) item {
                    WorkCard {
                        Text("موارد بازبینی — این گزارش نهایی نیست", color = ReviewOrange)
                        report.reviewIssues.forEach { Text(it, color = ReviewOrange, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        editing?.let { row ->
            RowEditor(row, vm.pages.firstOrNull { it.id == row.pageId }, vm.files, row.id in duplicates,
                onDismiss = { editing = null }, onSave = { updated, confirm, keep ->
                    vm.editRow(updated)
                    if (confirm) vm.confirmRow(updated.id, keep)
                    editing = null
                })
        }
        preview?.let { page ->
            Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var zoom by remember { mutableFloatStateOf(1f) }
                    Column(Modifier.padding(12.dp)) {
                        TextButton(onClick = { preview = null }) { Text("بستن تصویر") }
                        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                            val shown = if (vm.files.rendered(page).isFile && page.processed) vm.files.rendered(page) else vm.files.image(page)
                            StatementImage(shown, modifier = Modifier.requiredSize((340 * zoom).dp, (500 * zoom).dp), angle = if (page.processed) 0f else page.rotation + page.deskew, enhance = !page.processed && page.enhance)
                        }
                        Slider(zoom, { zoom = it }, valueRange = 1f..4f)
                        Text("برای دیدن جزئیات، بزرگ‌نمایی و تصویر را پیمایش کنید.")
                    }
                }
            }
        }
        remove?.let { page -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("حذف صفحهٔ صورت‌وضعیت؟") }, text = { Text("فقط تصویر و استخراج این صفحه از پیش‌نویس حذف می‌شود؛ سرویس‌های خودرویار تغییری نمی‌کنند.") }, confirmButton = { TextButton(onClick = { vm.removePage(page.id); remove = null }) { Text("حذف صفحه") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("انصراف") } }) }
        exportResult?.let { result -> AlertDialog(onDismissRequest = { exportResult = null }, title = { Text("مغایرت‌گیری کارکرد") }, text = { Text(result) }, confirmButton = { TextButton(onClick = { exportResult = null }) { Text("باشه") } }) }
    }
}

@Composable
private fun WorkCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun WorkField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onChange(it.take(80)) }, modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) }, shape = FieldShape, colors = fieldColors(), textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
}

@Composable
private fun ColumnRange(title: String, start: String, end: String, onStart: (String) -> Unit, onEnd: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkField("$title از ٪", start, onStart, modifier = Modifier.weight(1f))
        WorkField("تا ٪", end, onEnd, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RowEditor(row: StatementRow, page: ImportedStatementPage?, files: StatementFiles, duplicate: Boolean, onDismiss: () -> Unit, onSave: (StatementRow, Boolean, Boolean) -> Unit) {
    var date by remember(row.id) { mutableStateOf(row.date) }; var km by remember(row.id) { mutableStateOf(row.kilometers) }
    var hours by remember(row.id) { mutableStateOf(row.hours) }; var count by remember(row.id) { mutableStateOf(row.count) }
    var exclude by remember(row.id) { mutableStateOf(row.excluded) }; var kind by remember(row.id) { mutableStateOf(row.kind) }
    var keep by remember(row.id) { mutableStateOf(row.keepDuplicate) }
    val updated = row.copy(date = date, kilometers = km, hours = hours, count = count, excluded = exclude, kind = kind)
    val valid = exclude || kind != StatementRowKind.SERVICE || ReconciliationEngine.values(updated) != null && (!duplicate || keep)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = CardShape) {
            Column(Modifier.heightIn(max = 720.dp).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("بازبینی ردیف با تصویر", style = MaterialTheme.typography.titleLarge)
                page?.let {
                    val file = if (files.rendered(it).isFile && it.processed) files.rendered(it) else files.image(it)
                    StatementImage(file, if (it.processed) row.area else null, Modifier.fillMaxWidth().height(160.dp), angle = if (it.processed) 0f else it.rotation + it.deskew, enhance = !it.processed && it.enhance)
                }
                Text(row.rawText, style = MaterialTheme.typography.bodySmall)
                WorkField("تاریخ شمسی", date, { date = it })
                WorkField("تعداد سرویس (هر ردیف معمولاً یک)", count, { count = it })
                WorkField("کیلومتر (خالی ≠ صفر)", km, { km = it })
                WorkField("ساعت اعشاری یا ۳۰:۰۰", hours, { hours = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(kind == StatementRowKind.SERVICE, { kind = if (it) StatementRowKind.SERVICE else StatementRowKind.OTHER })
                    Text("این ردیف سرویس است، نه جمع/سرصفحه/توضیح.", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exclude, { exclude = it }); Text("این ردیف کنار گذاشته شود.") }
                if (duplicate && kind == StatementRowKind.SERVICE && !exclude) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }); Text("بررسی کردم؛ با وجود مقادیر مشابه در صفحهٔ دیگر، این سرویس مستقل و معتبر است.", style = MaterialTheme.typography.bodySmall)
                }
                if (!valid) Text("مقادیر خوانا و کامل و تصمیم دربارهٔ تکرار لازم است. هیچ مقدار ناخوانایی صفر فرض نمی‌شود.", color = ReviewOrange)
                Button(onClick = { onSave(updated, true, keep) }, enabled = valid) { Text("با تصویر بررسی و تأیید کردم") }
                OutlinedButton(onClick = { onSave(updated, false, false) }) { Text("ذخیره برای بررسی بعدی") }
                TextButton(onClick = onDismiss) { Text("انصراف") }
            }
        }
    }
}

@Composable
private fun StatementImage(file: File, area: RowArea? = null, modifier: Modifier = Modifier, angle: Float = 0f, enhance: Boolean = false) {
    val stamp = file.lastModified()
    val bitmap by produceState<Bitmap?>(null, file.absolutePath, stamp, area, angle, enhance) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1400) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
                if (area == null) {
                    val rotated = if (angle == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(angle) }, true)
                    if (rotated !== decoded) decoded.recycle()
                    if (enhance || angle % 90f != 0f) {
                        val result = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                        val colors = ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix(floatArrayOf(1.15f,0f,0f,0f,-12f, 0f,1.15f,0f,0f,-12f, 0f,0f,1.15f,0f,-12f, 0f,0f,0f,1f,0f))) }
                        Canvas(result).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(rotated, 0f, 0f, Paint().apply { if (enhance) colorFilter = ColorMatrixColorFilter(colors) }) }
                        rotated.recycle(); result
                    } else rotated
                } else {
                    val sample = options.inSampleSize
                    val l = (area.left / sample).coerceIn(0, decoded.width - 1); val t = ((area.top - 12) / sample).coerceIn(0, decoded.height - 1)
                    val r = (area.right / sample).coerceIn(l + 1, decoded.width); val b = ((area.bottom + 12) / sample).coerceIn(t + 1, decoded.height)
                    Bitmap.createBitmap(decoded, l, t, r - l, b - t).also { if (it !== decoded) decoded.recycle() }
                }
            }.getOrNull()
        }
    }
    if (bitmap == null) Box(modifier, contentAlignment = Alignment.Center) { Text("پیش‌نمایش تصویر") }
    else Image(bitmap!!.asImageBitmap(), "تصویر صورت‌وضعیت برای بازبینی", modifier, contentScale = ContentScale.Fit)
}

private fun statusColor(status: DayStatus) = when (status) { DayStatus.MATCHED -> MatchGreen; DayStatus.DISCREPANCY -> DifferenceRed; DayStatus.REVIEW -> ReviewOrange }

@Composable
private fun ReportSummary(report: ReconciliationReport) {
    WorkCard {
        Text(if (report.final) "گزارش تأییدشده" else "پیش‌نمایش — نیاز به بازبینی", style = MaterialTheme.typography.titleLarge, color = if (report.final) MaterialTheme.colorScheme.primary else ReviewOrange)
        if (!report.final) Text("جمع‌ها فقط شامل مقادیر معتبر و تأییدشده‌اند؛ دادهٔ ناخوانا یا تأییدنشده صفر محسوب نشده است.", color = ReviewOrange)
        Text("${PersianText.fa(report.from)} تا ${PersianText.fa(report.to)}")
        Text("اختلاف = برنامه منهای شرکت. برابری جمع کل، مغایرت روزانه را پنهان نمی‌کند.", style = MaterialTheme.typography.bodySmall)
        TotalsLine("خودرویار", report.app); TotalsLine("شرکت", report.company); TotalsLine("اختلاف", report.difference)
        Text("روز برابر: ${PersianText.fa(report.matchedDays.toString())} · روز مغایر: ${PersianText.fa(report.discrepancyDays.toString())} · روز نیازمند بازبینی: ${PersianText.fa(report.reviewDays.toString())}")
    }
}

@Composable
private fun TotalsLine(label: String, totals: WorkTotals) {
    Text("$label: ${PersianText.fa(totals.services.toString())} سرویس · ${PersianText.format(totals.kilometers)} کیلومتر · ${PersianText.format(totals.hours)} ساعت")
}

@Composable
private fun DailyTable(report: ReconciliationReport) {
    WorkCard {
        Text("مقایسهٔ روزانه (قابل پیمایش افقی)", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val labels = listOf("تاریخ", "سرویس برنامه", "سرویس شرکت", "کیلومتر برنامه", "کیلومتر شرکت", "ساعت برنامه", "ساعت شرکت", "وضعیت")
            Row { labels.forEach { Text(it, Modifier.width(116.dp).padding(8.dp), style = MaterialTheme.typography.labelMedium) } }
            HorizontalDivider()
            report.days.forEach { day ->
                val values = listOf(PersianText.fa(day.date), PersianText.fa(day.app.services.toString()), PersianText.fa(day.company.services.toString()), PersianText.format(day.app.kilometers), PersianText.format(day.company.kilometers), PersianText.format(day.app.hours), PersianText.format(day.company.hours), when (day.status) { DayStatus.MATCHED -> "برابر"; DayStatus.DISCREPANCY -> "مغایر"; DayStatus.REVIEW -> "نیاز به بررسی" })
                Row { values.forEach { Text(it, Modifier.width(116.dp).padding(8.dp), color = statusColor(day.status), style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
