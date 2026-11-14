package com.khodroyar.app.workreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The only image OCR path. Injectable transport, not an alternative production endpoint. */
class FastOcrReportReader(private val client: FastOcrClient = FastOcrClient()) {
    data class Result(val pages: List<OcrPage>, val rows: List<ReportRow>)

    suspend fun read(
        documents: ReportDocuments, pages: List<ReportPage>, key: String,
        progress: (String) -> Unit = {}, checkCancelled: () -> Unit = {},
    ): Result {
        require(pages.isNotEmpty() && pages.size <= 10) { "در هر ارسال حداکثر ۱۰ صفحه انتخاب کنید." }
        val read = mutableListOf<OcrPage>()
        val rows = mutableListOf<ReportRow>()
        for ((i, page) in pages.withIndex()) {
            checkCancelled()
            progress("آماده‌سازی با وضوح بالا · صفحهٔ ${i + 1} از ${pages.size}…")
            val pdf = withContext(Dispatchers.IO) { documents.pdfForUpload(page) }
            checkCancelled()
            progress("خواندن آنلاین · صفحهٔ ${i + 1} از ${pages.size} در api.fastocr.org…")
            val (text, status) = withContext(Dispatchers.IO) {
                client.ocrPage(key, "page-${i + 1}.pdf", pdf, "application/pdf", checkCancelled)
            }
            val incomplete = status.truncated || status.status == "partial" ||
                status.pagesProcessed in 0 until status.pagesTotal || text.length > MAX_DRAFT_TEXT
            // Keep the returned text locally so a parser update/review does NOT
            // require another paid upload. Never logs it, and never stores the key.
            val ocr = OcrPage(page.id, i + 1, emptyList(), text.take(MAX_DRAFT_TEXT),
                source = ReportTextSource.FASTOCR, incomplete = incomplete)
            read += ocr
            rows += withContext(Dispatchers.Default) { FastOcrReportText.parsePage(page.id, i + 1, ocr.text) }
        }
        return Result(read, rows)
    }

    companion object { const val MAX_DRAFT_TEXT = 200_000 }
}
