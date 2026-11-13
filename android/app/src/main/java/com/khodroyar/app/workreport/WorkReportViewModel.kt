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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal

class WorkReportViewModel @JvmOverloads constructor(
    app: Application, private val reader: FastOcrReportReader = FastOcrReportReader(),
) : AndroidViewModel(app) {
    val documents = ReportDocuments(app)
    private val draft = WorkReportDraft(documents.directory)
    private val repository = AppRepository(app)
    private val persistLock = Mutex()
    private var saveJob: Job? = null
    private var dateComparisonJob: Job? = null
    private var dateComparisonRevision = 0
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
    var sparseComparison by mutableStateOf<SparseHoursResult?>(null); private set
    var reviewConfirmed by mutableStateOf(false); private set
    val readWarnings get() = ReportReadCoverage.warnings(pages, recognized, rows)
    /** Mandatory output: per-date company service count vs app-registered count. */
    var dateComparison by mutableStateOf<List<DateServiceComparison>>(emptyList()); private set
    var dateComparisonError by mutableStateOf<String?>(null); private set
    private val fastOcrKeys = com.khodroyar.app.ai.FastOcrApiKeyStore(app)
    /** Whether the user once stored their own FastOCR key (encrypted at rest). */
    var fastOcrKeySaved by mutableStateOf(false); private set

    init {
        fastOcrKeySaved = fastOcrKeys.key() != null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { draft.load() }?.let { saved ->
                    pages = saved.pages; recognized = saved.recognized; rows = saved.rows; start = saved.start; end = saved.end
                    links = saved.links; periodNote = saved.periodNote
                    refreshDateComparison()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error = "برای باز کردن پیش‌نویس حافظه کافی نبود؛ فایل‌های اصلی حفظ شده‌اند. می‌توانید پیش‌نویس را پاک و سند کوچک‌تری انتخاب کنید." }
            catch (_: Exception) { error = "پیش‌نویس قبلی قابل بازیابی نیست؛ فایل‌های اصلی پاک نشده‌اند. برای شروع دوباره «پاک کردن پیش‌نویس» را انتخاب کنید." }
            finally { busy = false }
        }
        viewModelScope.launch {
            repository.maintenanceInputsChanged().collect {
                sourceRevision++
                refreshDateComparison()
                if (comparison != null || sparseComparison != null) { comparison = null; sparseComparison = null; error = "سوابق برنامه تغییر کرد؛ برای نتیجهٔ تازه دوباره مقایسه کنید." }
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
    private fun invalidate() {
        comparison = null; sparseComparison = null; reviewConfirmed = false
        refreshDateComparison()
    }

    /** Cancel stale reads after edits, range changes, draft restore or DB changes. */
    private fun refreshDateComparison() {
        dateComparisonJob?.cancel()
        val revision = ++dateComparisonRevision
        dateComparison = emptyList(); dateComparisonError = null
        val snapshot = rows
        if (snapshot.none { it.included && it.kind == ReportRowKind.SERVICE && PersianInput.date(it.date) != null }) return
        val a = PersianInput.date(start) ?: return
        val b = PersianInput.date(end) ?: return
        if (a > b) return
        dateComparisonJob = viewModelScope.launch {
            try {
                delay(100) // Coalesce keystrokes without holding up OCR or draft saving.
                val appDates = repository.servicesInRangeOnce(a, b).map { it.date }
                val result = DateServiceCountComparison.compare(snapshot, appDates, a, b)
                if (revision == dateComparisonRevision) dateComparison = result
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (revision == dateComparisonRevision)
                    dateComparisonError = "مقایسهٔ تعداد فعلاً در دسترس نیست؛ جدول بازبینی محفوظ است."
            }
        }
    }
    fun setReview(value: Boolean) { if (!busy) { reviewConfirmed = value; comparison = null; sparseComparison = null } }
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
    /** One-time key entry; stored encrypted. Returns true when the key was valid and saved. */
    fun saveFastOcrKey(raw: String): Boolean {
        if (busy) return false
        val saved = fastOcrKeys.save(raw) ?: return false
        fastOcrKeySaved = true
        return saved.isNotBlank()
    }
    fun clearFastOcrKey() { if (!busy) { fastOcrKeys.clear(); fastOcrKeySaved = false } }

    /** Online only; no local OCR pass or model installation is required. */
    fun recognizeFastOcr() = work {
        val key = fastOcrKeys.key() ?: error("ابتدا کلید API خود را وارد کنید.")
        val context = currentCoroutineContext()
        val result = reader.read(documents, pages, key, progress = { progress.value = it }) { context.ensureActive() }
        applyReadResult(result.pages, result.rows,
            "خواندن آنلاین با api.fastocr.org انجام شد؛ تاریخ‌ها، ردیف‌های جاافتاده و کل بازه را با اصل سند بازبینی کنید.")
    }

    /** Re-run just the parser against privately saved text: no network/charge. */
    fun reparseSavedText() = work {
        val saved = recognized
        val oldRows = rows
        require(saved.any { it.source != ReportTextSource.LEGACY && it.text.isNotBlank() }) {
            "متن ذخیره‌شده موجود نیست؛ یک‌بار گزارش را بخوانید یا متن صفحه را وارد کنید."
        }
        val extracted = withContext(Dispatchers.Default) {
            saved.flatMap { page ->
                if (page.source == ReportTextSource.LEGACY) oldRows.filter { it.pageId == page.id }
                else FastOcrReportText.parsePage(page.id, page.number, page.text)
            }
        }
        applyReadResult(saved, extracted, "متن ذخیره‌شده دوباره تحلیل شد؛ هیچ صفحه‌ای ارسال و هزینه‌ای مصرف نشد. بازه را با سند تأیید کنید.")
    }

    /** A user's pasted/exported OCR text is review input, not a trusted OCR claim. */
    fun importText(pageId: String, text: String) = work {
        val index = pages.indexOfFirst { it.id == pageId }
        require(index >= 0 && text.isNotBlank() && text.length <= FastOcrReportReader.MAX_DRAFT_TEXT)
        val extracted = withContext(Dispatchers.Default) { FastOcrReportText.parsePage(pageId, index + 1, text) }
        val updated = recognized.filterNot { it.id == pageId } +
            OcrPage(pageId, index + 1, emptyList(), text, source = ReportTextSource.USER_TEXT)
        applyReadResult(updated.sortedBy { it.number }, rows.filterNot { it.pageId == pageId } + extracted,
            "متن صفحه را خودتان وارد کرده‌اید؛ با تصویر اصلی تطبیق دهید. چیزی ارسال نشد.")
    }

    private fun applyReadResult(read: List<OcrPage>, extracted: List<ReportRow>, note: String) {
        require(extracted.size <= 10000)
        val order = pages.withIndex().associate { it.value.id to it.index }
        recognized = read
        rows = extracted.sortedWith(compareBy<ReportRow> { order[it.pageId] ?: it.page }.thenBy { it.line })
        links = emptyList()
        val range = DateServiceCountComparison.suggestedRange(extracted)
        start = range?.first.orEmpty(); end = range?.second.orEmpty(); periodNote = note
        invalidate(); persist()
        error = if (rows.isEmpty()) "متن دریافت شد ولی سرویس خوانایی پیدا نشد؛ اصل صفحه یا متن ذخیره‌شده را بازبینی کنید. نبود ردیف، نبود سرویس نیست." else null
    }
    fun edit(row: ReportRow) { if (!busy) {
        val previous = rows.firstOrNull { it.id == row.id }
        val edited = row.copy(date = ReportDateText.normalize(row.date), hours = ReportHoursText.normalize(row.hours),
            hoursBlank = if (previous?.hours != row.hours) ReportHoursText.isEmpty(row.hours) else row.hoursBlank)
        rows = rows.map { if (it.id == row.id) edited else it }
        links = links.filterNot { row.id in it.companyIds }; invalidate(); persist()
    } }
    fun clearLinks() { if (!busy) { links = emptyList(); invalidate(); persist() } }
    fun link(companyIds: List<String>, appIds: List<String>) {
        if (busy) return
        val result = comparison ?: sparseComparison?.completeDays ?: return
        if (companyIds.isEmpty() || appIds.isEmpty() || companyIds.any { id -> result.companyRows.none { it.id == id } } || appIds.any { id -> result.appServices.none { it.id == id } }) return
        val link = ReportMatchLink(companyIds.distinct(), appIds.distinct(), ServiceWorkComparison.fingerprint(result.appServices.filter { it.id in appIds }))
        links = links.filterNot { old -> old.companyIds.any { it in companyIds } || old.appIds.any { it in appIds } } + link
        invalidate(); persist()
    }
    fun addRow() {
        if (busy || pages.isEmpty() || rows.size >= 10000) return
        rows = rows + ReportRow("manual:${java.util.UUID.randomUUID()}", pages.first().id, 1, rows.size, "ردیف واردشده توسط کاربر", count = "1",
            note = "ورود دستی یک سرویس؛ تاریخ و کیلومتر الزامی است؛ ساعت خالی نامشخص می‌ماند", kind = ReportRowKind.SERVICE)
        invalidate(); persist()
    }
    fun compare() = work {
        require(reviewConfirmed) { "ابتدا جدول، ردیف‌های کنارگذاشته‌شده و بازه را بازبینی و تأیید کنید." }
        require(ReportReadCoverage.complete(pages, recognized)) { "همهٔ صفحه‌ها باید کامل خوانده شوند؛ صفحهٔ ناقص/ناخوانا را اصلاح یا صریحاً حذف کنید." }
        val selected = rows.filter { it.included }.map { it.copy(date = ReportDateText.parse(it.date) ?: it.date, hours = ReportHoursText.normalize(it.hours)) }
        require(selected.isNotEmpty()) { "هیچ ردیفی برای مقایسه انتخاب نشده است." }
        val values = selected.map { r -> requireNotNull(r.reviewed()) { "ردیف ${r.ordinal.ifBlank { (r.line + 1).toString() }} صفحهٔ ${r.page}: ${r.fieldMessages().joinToString("؛ ")}؛ خانهٔ مشخص‌شده را اصلاح کنید. صفر جایگزین نمی‌شود." } }
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
        val result: Pair<WorkComparison?, SparseHoursResult?> = withContext(Dispatchers.Default) {
            if (values.any { it.hours == null }) null to SparseHoursComparison.compare(selected, saved, a, b, links)
            else ServiceWorkComparison.compare(selected, saved, a, b, links) to null
        }
        require(revision == sourceRevision) { "سوابق برنامه حین پردازش تغییر کرد؛ دوباره مقایسه کنید تا نتیجه تازه باشد." }
        comparison = result.first; sparseComparison = result.second
    }
    fun stop() { job?.cancel() }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { error = "پردازش متوقف شد؛ اطلاعات ثبت‌شدهٔ برنامه تغییری نکرد."; throw e }
            catch (e: OutOfMemoryError) { error = "حافظه برای این صفحه کافی نبود؛ تصویر کوچک‌تر یا PDF با صفحه‌های کمتر انتخاب کنید." }
            catch (e: Exception) {
                val detail = (listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ")).take(240)
                error = if (detail.isNotBlank()) detail else "پردازش انجام نشد؛ فایل یا مقدارهای ورودی را بررسی کنید."
            }
            finally { busy = false; progress.value = "" }
        }
    }
}
