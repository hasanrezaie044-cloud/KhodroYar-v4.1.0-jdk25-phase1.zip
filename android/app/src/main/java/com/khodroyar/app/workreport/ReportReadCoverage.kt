package com.khodroyar.app.workreport

object ReportReadCoverage {
    fun complete(pages: List<ReportPage>, read: List<OcrPage>): Boolean =
        pages.isNotEmpty() && pages.map { it.id }.toSet().size == pages.size &&
            read.size == pages.size && read.map { it.id }.toSet() == pages.map { it.id }.toSet() &&
            read.all { !it.incomplete && (it.text.isNotBlank() || it.words.isNotEmpty()) }

    fun warnings(pages: List<ReportPage>, read: List<OcrPage>, rows: List<ReportRow>): List<String> = buildList {
        if (read.isEmpty()) return@buildList
        pages.forEachIndexed { i, page ->
            val recognized = read.firstOrNull { it.id == page.id }
            val pageRows = rows.filter { it.pageId == page.id }
            when {
                recognized == null -> add("صفحهٔ ${i + 1} هنوز خوانده نشده است.")
                recognized.incomplete -> add("پاسخ صفحهٔ ${i + 1} ناقص است؛ آن را دوباره بخوانید یا صریحاً حذف کنید.")
                pageRows.isEmpty() -> add("از صفحهٔ ${i + 1} هیچ سرویس خوانده نشد؛ این به معنی نبود سرویس نیست.")
            }
            if (pageRows.size >= FastOcrReportText.MAX_ROWS_PER_PAGE) add("صفحهٔ ${i + 1} به سقف ردیف رسیده است؛ کامل بودن آن را با اصل سند بررسی کنید.")
            val missing = FastOcrReportText.missingOrdinals(pageRows)
            if (missing.isNotEmpty()) add("صفحهٔ ${i + 1}: شماره‌ردیف‌های ${missing.take(30).joinToString("، ")} خوانده نشدند؛ تاریخ/تعداد این بخش را با اصل سند بازبینی کنید.")
            if (pageRows.any { it.included && it.date.isBlank() }) add("صفحهٔ ${i + 1} ردیف بدون تاریخ خوانا دارد؛ تعداد روزانه هنوز کامل نیست.")
        }
    }
}
