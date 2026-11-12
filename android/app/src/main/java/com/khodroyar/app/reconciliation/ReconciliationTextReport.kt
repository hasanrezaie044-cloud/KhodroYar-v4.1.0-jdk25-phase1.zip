package com.khodroyar.app.reconciliation

import com.khodroyar.app.core.persian.PersianText

object ReconciliationTextReport {
    fun write(report: ReconciliationReport): String = buildString {
        appendLine("مغایرت‌گیری کارکرد خودرویار — ${if (report.final) "گزارش تأییدشده" else "پیش‌نمایش غیرقطعی"}")
        appendLine("بازه: ${PersianText.fa(report.from)} تا ${PersianText.fa(report.to)}")
        appendLine("اختلاف = برنامه منهای شرکت. ساعت از فیلد ساعت کارکرد سرویس‌های موجود خوانده می‌شود.")
        appendLine("مبدأ، مقصد، مسیر و شرح در تطبیق دخالت ندارند. هیچ رکورد سرویس تغییر نکرده است.")
        if (!report.final) appendLine("هشدار: جمع‌ها فقط شامل مقادیر معتبر/تأییدشده‌اند؛ مقادیر ناخوانا صفر نیستند.")
        fun total(label: String, totals: WorkTotals) = appendLine("$label: ${PersianText.fa(totals.services.toString())} سرویس | ${PersianText.format(totals.kilometers)} کیلومتر | ${PersianText.format(totals.hours)} ساعت")
        total("برنامه", report.app); total("شرکت", report.company); total("اختلاف", report.difference)
        appendLine("روزهای برابر: ${PersianText.fa(report.matchedDays.toString())} | روزهای مغایر: ${PersianText.fa(report.discrepancyDays.toString())} | نیاز به بررسی: ${PersianText.fa(report.reviewDays.toString())}")
        appendLine("\nتاریخ | سرویس برنامه | سرویس شرکت | کیلومتر برنامه | کیلومتر شرکت | ساعت برنامه | ساعت شرکت | وضعیت")
        report.days.forEach { d ->
            val status = when (d.status) { DayStatus.MATCHED -> "برابر"; DayStatus.DISCREPANCY -> "مغایر"; DayStatus.REVIEW -> "نیاز به بررسی" }
            appendLine(listOf(PersianText.fa(d.date), PersianText.fa(d.app.services.toString()), PersianText.fa(d.company.services.toString()), PersianText.format(d.app.kilometers), PersianText.format(d.company.kilometers), PersianText.format(d.app.hours), PersianText.format(d.company.hours), status).joinToString(" | "))
            d.explanations.forEach { appendLine("  • $it") }
        }
        if (report.reviewIssues.isNotEmpty()) { appendLine("\nموارد نیازمند بازبینی:"); report.reviewIssues.forEach { appendLine("• $it") } }
    }
}
