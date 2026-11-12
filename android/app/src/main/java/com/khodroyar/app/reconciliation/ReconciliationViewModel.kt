package com.khodroyar.app.reconciliation

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.core.persian.PersianText
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ReconciliationViewModel(app: Application) : AndroidViewModel(app) {
    val files = StatementFiles(app)
    val ocr = OfflineOcrClient(app)
    private val store = ReconciliationDraftStore(app)
    private val repository = AppRepository(app)
    private val saveLock = Mutex()
    private var saveJob: Job? = null
    var pages by mutableStateOf<List<ImportedStatementPage>>(emptyList()); private set
    var rows by mutableStateOf<List<StatementRow>>(emptyList()); private set
    var from by mutableStateOf(""); private set
    var to by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    var report by mutableStateOf<ReconciliationReport?>(null); private set
    var loaded by mutableStateOf(false); private set
    private var job: Job? = null
    private var storageFailed = false

    init {
        busy = true
        job = viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { store.load() }
                pages = draft.pages; rows = draft.rows; from = draft.from; to = draft.to
            } catch (_: Exception) {
                storageFailed = true
                message = "پیش‌نویس قبلی خوانده نشد. فایل‌ها و سرویس‌ها دست‌نخورده‌اند؛ پیش از شروع بررسی تازه، نسخهٔ فایل‌ها را نگه دارید."
            } finally { busy = false; loaded = true }
        }
    }
    private fun changed(invalidateReport: Boolean = true) {
        if (invalidateReport) report = null
        if (storageFailed) return
        saveJob?.cancel()
        val snapshot = ReconciliationDraft(pages, rows, from, to)
        saveJob = viewModelScope.launch {
            delay(150)
            try { saveLock.withLock { withContext(Dispatchers.IO) { store.save(snapshot) } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "ذخیرهٔ پیش‌نویس انجام نشد. پیش از بستن صفحه، فضای ذخیره‌سازی را بررسی کنید." }
        }
    }
    private fun work(block: suspend () -> Unit) {
        if (busy || !loaded || storageFailed) return
        message = null; report = null; busy = true
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { message = "پردازش متوقف شد؛ صفحات انجام‌نشده نیاز به بازبینی دارند."; throw e }
            catch (_: OutOfMemoryError) { message = "حافظه کافی نیست؛ سند را به صفحات کوچک‌تر تقسیم کنید." }
            catch (e: Exception) { message = e.message ?: "عملیات انجام نشد؛ هیچ رکورد سرویس تغییر نکرد." }
            finally { busy = false; progress = ""; changed(invalidateReport = false) }
        }
    }
    fun dismissMessage() { message = null }
    fun import(uris: List<Uri>) = work {
        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress = "ورود فایل ${PersianText.fa((index + 1).toString())}"
            val incoming = withContext(Dispatchers.IO) { files.import(uri, 40 - pages.size) }
            val fingerprints = withContext(Dispatchers.IO) { pages.map { files.fingerprint(it) }.toMutableSet() }
            var skipped = 0
            for (p in incoming) {
                val hash = withContext(Dispatchers.IO) { files.fingerprint(p) }
                if (fingerprints.add(hash)) pages = pages + p else { withContext(Dispatchers.IO) { files.remove(p) }; skipped++ }
            }
            if (skipped > 0) message = "${PersianText.fa(skipped.toString())} صفحهٔ تصویری کاملاً یکسان دوباره وارد نشد. سرویس‌های مشابه فقط برای بازبینی علامت می‌خورند."
        }
    }
    fun updatePage(page: ImportedStatementPage) {
        if (busy) return
        pages = pages.map { if (it.id == page.id) page.copy(reviewed = false, processed = false) else it }
        rows = rows.filterNot { it.pageId == page.id }
        changed()
    }
    fun reviewPage(id: String, checked: Boolean) {
        if (busy) return
        pages = pages.map { if (it.id == id) it.copy(reviewed = checked) else it }; changed()
    }
    fun movePage(id: String, delta: Int) {
        if (busy) return
        val list = pages.toMutableList(); val index = list.indexOfFirst { it.id == id }; val target = index + delta
        if (index < 0 || target !in list.indices) return
        java.util.Collections.swap(list, index, target); pages = list; changed()
    }
    fun removePage(id: String) = work {
        val page = pages.firstOrNull { it.id == id } ?: return@work
        pages = pages.filterNot { it.id == id }; rows = rows.filterNot { it.pageId == id }
        withContext(Dispatchers.IO) { files.remove(page) }
    }
    fun extract(columns: StatementColumns?) = work {
        require(columns == null || columns.valid()) { "محدودهٔ ستون‌ها نامعتبر یا هم‌پوشان است" }
        check(ocr.installed()) { "افزونهٔ اختیاری OCR فارسی هم‌امضا را نصب و فایل زبان را وارد کنید. ورود دستی بدون افزونه نیز ممکن است." }
        for ((index, page) in pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            // Never overwrite reviewed/corrected rows. Users can explicitly reset a page first.
            if (page.processed) continue
            progress = "خواندن صفحه ${PersianText.fa((index + 1).toString())} از ${PersianText.fa(pages.size.toString())}"
            val image = withContext(Dispatchers.Default) { files.prepare(page) }
            val result = ocr.recognize(page.id, image)
            val parsed = withContext(Dispatchers.Default) { StatementParser.parse(result, columns) }
            rows = rows.filterNot { it.pageId == page.id } + parsed
            pages = pages.map { if (it.id == page.id) it.copy(processed = true, reviewed = false) else it }
        }
        if (from.isBlank() || to.isBlank()) inferRange()
        message = "استخراج تمام شد. همهٔ ردیف‌ها و کامل بودن هر صفحه را با تصویر بررسی کنید؛ OCR، حتی با اطمینان بالا، تأیید نهایی نیست."
    }
    private fun inferRange() {
        val dates = rows.filter { it.kind == StatementRowKind.SERVICE && !it.excluded }.mapNotNull { PersianText.date(it.date) }.sorted()
        if (dates.isNotEmpty()) { from = dates.first(); to = dates.last() }
    }
    fun addRow(pageId: String) {
        if (busy) return
        rows = rows + StatementRow(UUID.randomUUID().toString(), pageId, confidence = 0, rawText = "ورود دستی")
        pages = pages.map { if (it.id == pageId) it.copy(reviewed = false) else it }; changed()
    }
    fun editRow(row: StatementRow) {
        if (busy) return
        rows = rows.map { if (it.id == row.id) row.copy(confirmed = false, keepDuplicate = false) else it }
        pages = pages.map { if (it.id == row.pageId) it.copy(reviewed = false) else it }; changed()
    }
    fun confirmRow(id: String, keepDuplicate: Boolean = false) {
        if (busy) return
        val row = rows.firstOrNull { it.id == id } ?: return
        if (ReconciliationEngine.values(row) == null && !row.excluded && row.kind == StatementRowKind.SERVICE) {
            message = "تاریخ، تعداد، کیلومتر و ساعت باید کامل و معتبر باشند. مقدار ناخوانا را صفر نگذارید."; return
        }
        rows = rows.map { if (it.id == id) it.copy(confirmed = true, keepDuplicate = keepDuplicate) else it }; changed()
    }
    fun range(start: String, end: String) { if (!busy) { from = start; to = end; changed() } }
    fun compare() {
        if (busy) return
        val start = PersianText.date(from); val end = PersianText.date(to)
        if (start == null || end == null || start > end) { message = "بازهٔ شمسی معتبر را انتخاب و تأیید کنید."; return }
        work {
            // A SINGLE real Room snapshot. No string-based SQL range filter that could drop Persian dates.
            val records = repository.services().first().map { AppWorkRecord(it.id, it.date, it.km, it.hours) }
            val result = withContext(Dispatchers.Default) { ReconciliationEngine.compare(records, rows, pages.map { StatementPage(it.id, it.reviewed) }, start, end) }
            report = result
        }
    }
    fun cancel() { job?.cancel() }
}
