package com.khodroyar.app.workreport

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.core.text.PersianInput
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.core.rates.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal

class WorkReportViewModel(app: Application) : AndroidViewModel(app) {
    val documents = ReportDocuments(app)
    private val draft = WorkReportDraft(documents.directory)
    private val engine = OfflineReportOcr(app)
    private val repository = AppRepository(app)
    private val persistLock = Mutex()
    private var saveJob: Job? = null
    private var job: Job? = null
    private var sourceRevision = 0
    val progress = MutableStateFlow("")
    var pages by mutableStateOf<List<ReportPage>>(emptyList()); private set
    var recognized by mutableStateOf<List<OcrPage>>(emptyList()); private set
    var rows by mutableStateOf<List<ReportRow>>(emptyList()); private set
    var busy by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var start by mutableStateOf(""); private set
    var end by mutableStateOf(""); private set
    var periodNote by mutableStateOf(""); private set
    var links by mutableStateOf<List<ReportMatchLink>>(emptyList()); private set
    var comparison by mutableStateOf<WorkComparison?>(null); private set
    var reviewConfirmed by mutableStateOf(false); private set
    var sparse by mutableStateOf(true)

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { draft.load() }?.let { saved ->
                    pages = saved.pages; recognized = saved.recognized; rows = saved.rows; start = saved.start; end = saved.end
                    links = saved.links; periodNote = saved.periodNote
                }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error = "برای باز کردن پیش‌نویس حافظه کافی نبود؛ فایل‌های اصلی حفظ شده‌اند. می‌توانید پیش‌نویس را پاک و سند کوچک‌تری انتخاب کنید." }
            catch (_: Exception) { error = "پیش‌نویس قبلی قابل بازیابی نیست؛ فایل‌های اصلی پاک نشده‌اند. برای شروع دوباره «پاک کردن پیش‌نویس» را انتخاب کنید." }
            finally { busy = false }
        }
        viewModelScope.launch {
            repository.maintenanceInputsChanged().collect {
                sourceRevision++
                if (comparison != null) { comparison = null; error = "سوابق برنامه تغییر کرد؛ برای نتیجهٔ تازه دوباره مقایسه کنید." }
            }
        }
    }
    fun clearError() { error = null }
    private fun snapshot() = WorkReportDraft.Draft(pages, recognized, rows, start, end, links, periodNote)
    private fun persist() {
        saveJob?.cancel()
        val snapshot = snapshot()
        saveJob = viewModelScope.launch {
            delay(150)
            try { withContext(Dispatchers.IO) { persistLock.withLock { draft.save(snapshot) } } }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error = "حافظه برای ذخیرهٔ پیش‌نویس کافی نیست؛ سند را به چند بخش کوچک‌تر تقسیم کنید. نتیجهٔ فعلی هنوز در صفحه است." }
            catch (_: Exception) { error = "ذخیرهٔ پیش‌نویس کامل نشد؛ فضای گوشی را بررسی کنید. نتیجه هنوز در این صفحه موجود است." }
        }
    }
    private fun invalidate() { comparison = null; reviewConfirmed = false }
    fun setReview(value: Boolean) { if (!busy) { reviewConfirmed = value; comparison = null } }
    fun range(a: String, b: String) { if (!busy) { start = a; end = b; invalidate(); persist() } }
    fun importDocuments(uris: List<Uri>) = work {
        pages = withContext(Dispatchers.IO) { documents.import(uris, pages) }
        recognized = emptyList(); rows = emptyList(); links = emptyList(); periodNote = ""; invalidate(); persist()
    }
    fun rotate(id: String) = changePages(pages.map { if (it.id == id) it.copy(rotation = (it.rotation + 1) % 4) else it })
    fun remove(id: String) = changePages(pages.filter { it.id != id })
    fun move(id: String, delta: Int) {
        val index = pages.indexOfFirst { it.id == id }; val target = index + delta
        if (index < 0 || target !in pages.indices) return
        val list = pages.toMutableList(); list.add(target, list.removeAt(index)); changePages(list)
    }
    private fun changePages(value: List<ReportPage>) {
        if (busy) return
        pages = value; recognized = emptyList(); rows = emptyList(); links = emptyList(); periodNote = ""; invalidate(); persist()
        // Keep removed source files until explicit reset. This also makes interrupted
        // draft writes recoverable; no stale background prune can delete a fresh import.
    }
    fun reset() = work {
        saveJob?.cancel()
        val empty = WorkReportDraft.Draft(emptyList(), emptyList(), emptyList(), "", "")
        withContext(Dispatchers.IO) { persistLock.withLock { draft.save(empty); documents.prune(emptyList()) } }
        pages = emptyList(); recognized = emptyList(); rows = emptyList(); links = emptyList(); periodNote = ""; start = ""; end = ""; invalidate()
    }
    fun recognize() = work {
        val result = engine.recognize(pages, sparse) { progress.value = it }
        val extracted = withContext(Dispatchers.Default) { WorkReportParser.extract(result) }
        require(extracted.size <= 10000) { "گزارش بیش از ۱۰٬۰۰۰ ردیف دارد؛ آن را تقسیم کنید." }
        recognized = result; rows = extracted; links = emptyList(); invalidate()
        val period = CompanyReportTemplate.period(result, extracted)
        start = period.start; end = period.end; periodNote = period.note; persist()
        if (result.any { it.words.isEmpty() }) error = "یک یا چند صفحه ناخوانا بود. پیش از مقایسه، تصویر را اصلاح کنید یا آن صفحه را صریحاً حذف و دوباره پردازش کنید."
        else if (rows.isEmpty()) error = "ردیف قابل استخراج پیدا نشد؛ چرخش، کیفیت تصویر یا حالت چیدمان را بررسی کنید."
    }
    fun remap(columns: ReportColumns) = work {
        require(recognized.isNotEmpty()) { "ابتدا OCR را اجرا کنید." }
        rows = withContext(Dispatchers.Default) { WorkReportParser.extract(recognized, columns) }; links = emptyList(); invalidate(); persist()
    }
    fun edit(row: ReportRow) { if (!busy) {
        rows = rows.map { if (it.id == row.id) row else it }
        links = links.filterNot { row.id in it.companyIds }; invalidate(); persist()
    } }
    fun clearLinks() { if (!busy) { links = emptyList(); invalidate(); persist() } }
    fun link(companyIds: List<String>, appIds: List<String>) {
        if (busy) return
        val result = comparison ?: return
        if (companyIds.isEmpty() || appIds.isEmpty() || companyIds.any { id -> result.companyRows.none { it.id == id } } || appIds.any { id -> result.appServices.none { it.id == id } }) return
        val link = ReportMatchLink(companyIds.distinct(), appIds.distinct(), ServiceWorkComparison.fingerprint(result.appServices.filter { it.id in appIds }))
        links = links.filterNot { old -> old.companyIds.any { it in companyIds } || old.appIds.any { it in appIds } } + link
        invalidate(); persist()
    }
    fun addRow() {
        if (busy || pages.isEmpty() || rows.size >= 10000) return
        rows = rows + ReportRow("manual:${java.util.UUID.randomUUID()}", pages.first().id, 1, rows.size, "ردیف واردشده توسط کاربر", count = "1",
            note = "ورود دستی یک سرویس؛ تاریخ، کیلومتر و ساعت الزامی است", kind = ReportRowKind.SERVICE)
        invalidate(); persist()
    }
    fun compare() = work {
        require(reviewConfirmed) { "ابتدا جدول، ردیف‌های کنارگذاشته‌شده و بازه را بازبینی و تأیید کنید." }
        require(recognized.size == pages.size && recognized.all { it.words.isNotEmpty() }) { "همهٔ صفحه‌ها باید پردازش شوند؛ صفحهٔ ناخوانا را اصلاح یا صریحاً حذف کنید." }
        val selected = rows.filter { it.included }
        require(selected.isNotEmpty()) { "هیچ ردیفی برای مقایسه انتخاب نشده است." }
        val values = selected.map { r -> requireNotNull(r.validated()) { "ردیف ${r.ordinal.ifBlank { (r.line + 1).toString() }} صفحهٔ ${r.page} تاریخ/تعداد/کیلومتر/ساعت ناخوانا یا نامعتبر دارد؛ خانهٔ قرمز را اصلاح کنید. صفر جایگزین نمی‌شود." } }
        val a = requireNotNull(PersianInput.date(start)) { "ابتدای بازه معتبر نیست." }
        val b = requireNotNull(PersianInput.date(end)) { "پایان بازه معتبر نیست." }
        require(a <= b && values.all { it.date in a..b }) { "بازه باید تمام روزهای جدول را دربر بگیرد." }
        val revision = sourceRevision
        val saved = repository.servicesInRangeOnce(a, b).map { s ->
            require(s.km.isFinite() && s.km >= 0 && s.hours.isFinite() && s.hours >= 0) { "یکی از سوابق برنامه مقدار معتبر ندارد؛ ابتدا آن را اصلاح کنید." }
            // Same manually-entered hours as Reports/Excel, NOT elapsed start/end time.
            AppWorkService(s.id, s.date, BigDecimal.valueOf(s.km), BigDecimal.valueOf(s.hours), s.origin, s.destination,
                s.startTime, s.endTime, ServiceType.fromWireOrNull(s.type)?.label ?: s.type, s.requestNumber)
        }
        val result = withContext(Dispatchers.Default) { ServiceWorkComparison.compare(selected, saved, a, b, links) }
        require(revision == sourceRevision) { "سوابق برنامه حین پردازش تغییر کرد؛ دوباره مقایسه کنید تا نتیجه تازه باشد." }
        comparison = result
    }
    fun stop() { engine.stop(); job?.cancel() }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { error = "پردازش متوقف شد؛ اطلاعات ثبت‌شدهٔ برنامه تغییری نکرد."; throw e }
            catch (e: OutOfMemoryError) { error = "حافظه برای این صفحه کافی نبود؛ تصویر کوچک‌تر یا PDF با صفحه‌های کمتر انتخاب کنید." }
            catch (e: LinkageError) { error = "موتور آفلاین روی این پردازنده بارگذاری نشد؛ پردازش انجام نشد." }
            catch (e: Exception) { error = e.message?.take(240) ?: "پردازش انجام نشد؛ فایل یا مقدارهای ورودی را بررسی کنید." }
            finally { busy = false; progress.value = "" }
        }
    }
    override fun onCleared() { engine.stop(); super.onCleared() }
}
