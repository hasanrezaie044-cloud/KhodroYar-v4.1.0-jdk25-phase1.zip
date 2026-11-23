package com.khodroyar.app.core.finance

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.text.PersianInput

/**
 * Validation for a single edited loan installment (5.9.1 audit).
 *
 * Confirmed defects fixed here:
 *  * the paid-date field accepted any text: "abc" or Persian digits were stored, marked the
 *    installment as paid and broke date ordering/comparisons (paid dates are compared as
 *    canonical `YYYY/MM/DD` strings everywhere else);
 *  * an impossible due date such as `1405/13/40` passed the old shape-only regex.
 */
object InstallmentEditRules {
    /** Canonical `YYYY/MM/DD` for a real Jalali date written with Latin/Persian/Arabic digits, else null. */
    fun canonicalDate(raw: String): String? {
        val text = PersianInput.latin(raw).trim()
        if (!Regex("""^\d{4}/\d{1,2}/\d{1,2}$""").matches(text)) return null
        val ymd = Jalali.parse(text) ?: return null
        if (!Jalali.isValid(ymd.year, ymd.month, ymd.day)) return null
        return Jalali.format(ymd.year, ymd.month, ymd.day)
    }

    /** Blank = unpaid (""), a valid date = canonical paid date, anything else = invalid (null). */
    fun paidDate(raw: String): String? = if (PersianInput.latin(raw).isBlank()) "" else canonicalDate(raw)

    /** Edits that only touch a loan's title/notes must not regenerate its installment schedule. */
    fun scheduleTermsChanged(
        oldTotal: Double, oldInterest: Double, oldDate: String, oldCount: Int, oldMonthly: Double,
        newTotal: Double, newInterest: Double, newDate: String, newCount: Int, newMonthly: Double,
    ): Boolean = oldTotal != newTotal || oldInterest != newInterest || oldDate != newDate ||
        maxOf(1, oldCount) != maxOf(1, newCount) || oldMonthly != newMonthly
}
