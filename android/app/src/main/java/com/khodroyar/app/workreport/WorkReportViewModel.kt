package com.khodroyar.app.workreport

import android.app.Application
import android.content.Context
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
    var sparse by mutableStateOf(true)
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
    fun recognize() = work {
        val result = engine.recognize(pages, sparse, progress = { progress.value = it })
        val extracted = withContext(Dispatchers.Default) { WorkReportParser.extract(result) }
        require(extracted.size <= 10000) { "گزارش بیش از ۱۰٬۰۰۰ ردیف دارد؛ آن را تقسیم کنید." }
        recognized = result; rows = extracted; links = emptyList()
        val period = CompanyReportTemplate.period(result, extracted)
        start = period.start; end = period.end; periodNote = period.note; invalidate(); persist()
        if (result.any { it.words.isEmpty() }) error = "یک یا چند صفحه ناخوانا بود. پیش از مقایسه، تصویر را اصلاح کنید یا آن صفحه را صریحاً حذف و دوباره پردازش کنید."
        else if (rows.isEmpty()) error = "ردیف قابل استخراج پیدا نشد؛ چرخش، کیفیت تصویر یا حالت چیدمان را بررسی کنید."
    }

    /** One-time key entry; stored encrypted. Returns true when the key was valid and saved. */
    fun saveFastOcrKey(raw: String): Boolean {
        if (busy) return false
        val saved = fastOcrKeys.save(raw) ?: return false
        fastOcrKeySaved = true
        return saved.isNotBlank()
    }
    fun clearFastOcrKey() { if (!busy) { fastOcrKeys.clear(); fastOcrKeySaved = false } }

    /**
     * Explicit-consent online assist via FastOCR (api.fastocr.org), using the
     * user's own API key. Pages are rendered at the same bounded plan as the
     * offline path, uploaded ONCE per explicit tap, and the plain text answer is
     * parsed conservatively into the SAME editable review table: ambiguous or
     * unassignable fields stay blank, hours are never zeroed, counts/km/identity
     * comparisons stay advisory and strict ServiceWorkComparison is untouched.
     * Nothing is stored server-side by us and the key never leaves the device
     * except to api.fastocr.org over HTTPS.
     */
    fun recognizeFastOcr() = work {
        val key = fastOcrKeys.key() ?: error("ابتدا کلید API خود را وارد کنید.")
        progress.value = "آماده‌سازی تصاویر برای ارسال یک‌باره…"
        require(pages.isNotEmpty()) { "ابتدا صفحه‌های گزارش را انتخاب کنید." }
        require(pages.size <= 10) { "در هر ارسال آنلاین حداکثر ۱۰ صفحه." }
        val client = FastOcrClient()
        val merged = mutableListOf<ReportRow>()
        val lineCounts = mutableListOf<Int>()
        var truncatedPages = 0
        val named = pages.withIndex().associate { (i, p) -> p.id to "PAGE-${i + 1}" }
        for ((i, page) in pages.withIndex()) {
            try {
                val bitmap = withContext(Dispatchers.IO) { documents.render(page, 2_400_000, 2000) }
                // FastOCR's documented create call requires a *.pdf filename; wrap the
                // rendered page in an in-memory one-page PDF (still a photo of the page).
                val pdf = try { OnlineReportAssist.pdf(bitmap) } finally { bitmap.recycle() }
                require(pdf.size <= FastOcrClient.MAX_IMAGE_BYTES) { "سند صفحهٔ ${i + 1} برای ارسال بزرگ است؛ تصویر را کوچک‌تر انتخاب کنید." }
                progress.value = "ارسال صفحهٔ ${i + 1} از ${pages.size} به api.fastocr.org…"
                // Network ALWAYS on the IO dispatcher; the client's calls block.
                val (text, status) = withContext(Dispatchers.IO) {
                    client.ocrPage(key, "page-${i + 1}.pdf", pdf, "application/pdf") { if (job?.isActive != true) throw CancellationException() }
                }
                if (status.truncated || status.pagesProcessed in 0 until status.pagesTotal) truncatedPages++
                lineCounts += text.split('\n').count { it.isNotBlank() }
                val rows = withContext(Dispatchers.Default) { FastOcrReportText.parsePage(page.id, i + 1, text) }
                merged += rows.map { it.copy(raw = "[${named[page.id]}] ${it.raw}") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Stage + exception class, so a screenshot pinpoints the failure without leaking anything.
                throw IllegalStateException("صفحهٔ ${i + 1} — ${e.javaClass.simpleName}: ${e.message ?: "(بدون پیام)"}")
            }
        }
        val extracted = merged
        require(extracted.isNotEmpty()) {
            // Line counts are metadata only; document text is never echoed back.
            "متن دریافتی FastOCR روی ${lineCounts.size} صفحه مجموعاً ${lineCounts.sum()} خط بود ولی هیچ خطی شکل «تاریخ شمسی + مقدار» ردیف گزارش را نداشت. اگر 402/429 دیدید یعنی سهمیهٔ حساب شما تمام شده؛ وگرنه راستا/کیفیت تصویر را اصلاح کنید. هیچ چیزی خودکار ذخیره نشد."
        }
        // Rows come from FastOCR text, but the same-comparison gate requires a
        // proven, complete offline pass over every page — that part always stays
        // local and never sends anything anywhere.
        progress.value = "خواندن آفلاین همان صفحه‌ها برای بازبینی و مقایسه…"
        val offline = engine.recognize(pages, sparse, progress = { progress.value = it })
        rows = extracted; recognized = offline; links = emptyList()
        val range = DateServiceCountComparison.suggestedRange(extracted)
        start = range?.first.orEmpty(); end = range?.second.orEmpty()
        periodNote = "پیش‌پر اختیاری آنلاین از api.fastocr.org انجام شد (کلید شما، فقط یک‌بار برای این دوره). هر خانه را با اصل تصویر تطبیق دهید؛ مقادیر مبهم عمداً خالی گذاشته شده‌اند و بازه را با سند تأیید کنید."
        invalidate(); persist()
        error = when {
            truncatedPages > 0 -> "سرویس $truncatedPages صفحه را ناقص خواند؛ آن‌ها را دستی با تصویر بازبینی کنید."
            offline.any { it.words.isEmpty() } -> "یک یا چند صفحه در خواندن آفلاین ناخوانا بود؛ پیش از مقایسه آن را اصلاح یا صریحاً حذف و دوباره پردازش کنید."
            else -> null
        }
    }
    fun remap(columns: ReportColumns) = work {
        require(recognized.isNotEmpty()) { "ابتدا OCR را اجرا کنید." }
        rows = withContext(Dispatchers.Default) { WorkReportParser.extract(recognized, columns) }; links = emptyList(); invalidate(); persist()
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
        require(recognized.size == pages.size && recognized.all { it.words.isNotEmpty() }) { "همهٔ صفحه‌ها باید پردازش شوند؛ صفحهٔ ناخوانا را اصلاح یا صریحاً حذف کنید." }
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
    fun stop() { engine.stop(); job?.cancel() }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { error = "پردازش متوقف شد؛ اطلاعات ثبت‌شدهٔ برنامه تغییری نکرد."; throw e }
            catch (e: OutOfMemoryError) { error = "حافظه برای این صفحه کافی نبود؛ تصویر کوچک‌تر یا PDF با صفحه‌های کمتر انتخاب کنید." }
            catch (e: LinkageError) { error = "موتور آفلاین روی این پردازنده بارگذاری نشد؛ پردازش انجام نشد." }
            catch (e: Exception) {
                val detail = (listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ")).take(240)
                error = if (detail.isNotBlank()) detail else "پردازش انجام نشد؛ فایل یا مقدارهای ورودی را بررسی کنید."
            }
            finally { busy = false; progress.value = "" }
        }
    }
    override fun onCleared() { engine.stop(); super.onCleared() }
}
