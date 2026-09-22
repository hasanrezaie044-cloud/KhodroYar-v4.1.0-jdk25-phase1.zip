package com.khodroyar.app.core.finance

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.TimeRates.jsRound

/**
 * Loan installment maths — ported 1:1 from utils/finance.ts.
 *
 * Simple interest on the whole principal; installments are equal except the last,
 * which absorbs the rounding remainder so the schedule always sums to the exact
 * total. Malformed legacy rows return an empty schedule rather than crashing the
 * finance tab, matching the JS guard.
 *
 * NOTE: due dates now go through the corrected [Jalali.addMonths], so a schedule
 * can no longer land on a non-existent day such as "1404/12/30".
 */
object LoanCalculator {

    data class Installment(
        val index: Int,
        val dueDate: String,
        val amount: Long,
        val paid: Boolean,
    )

    data class Summary(
        val schedule: List<Installment>,
        val totalWithInterest: Long,
        val paidCount: Int,
        val paidAmount: Long,
        val remainingAmount: Long,
        val nextInstallment: Installment?,
        val isCompleted: Boolean,
    )

    private val DATE_RE = Regex("""^\d{4}/\d{1,2}/\d{1,2}$""")

    fun totalWithInterest(totalAmount: Double, interestPercent: Double): Long {
        if (!totalAmount.isFinite() || totalAmount < 0) return 0
        val interest = if (interestPercent.isFinite()) interestPercent else 0.0
        return jsRound(totalAmount * (1 + interest / 100))
    }

    fun schedule(
        date: String?,
        totalAmount: Double,
        interestPercent: Double,
        installmentsCount: Int,
        paidInstallments: List<Int>,
    ): List<Installment> {
        if (date == null || !DATE_RE.matches(date)) return emptyList()
        val count = maxOf(1, installmentsCount)
        val total = totalWithInterest(totalAmount, interestPercent)
        val per = jsRound(total.toDouble() / count)
        val paid = paidInstallments.toHashSet()

        val out = ArrayList<Installment>(count)
        var allocated = 0L
        for (i in 1..count) {
            val due = Jalali.addMonths(date, i)
            val amount = if (i == count) total - allocated else per
            allocated += amount
            out.add(Installment(i, due, amount, paid.contains(i)))
        }
        return out
    }

    fun summarize(
        date: String?,
        totalAmount: Double,
        interestPercent: Double,
        installmentsCount: Int,
        paidInstallments: List<Int>,
    ): Summary {
        val sched = schedule(date, totalAmount, interestPercent, installmentsCount, paidInstallments)
        val total = totalWithInterest(totalAmount, interestPercent)
        val paidList = sched.filter { it.paid }
        val paidAmount = paidList.sumOf { it.amount }
        return Summary(
            schedule = sched,
            totalWithInterest = total,
            paidCount = paidList.size,
            paidAmount = paidAmount,
            remainingAmount = total - paidAmount,
            nextInstallment = sched.firstOrNull { !it.paid },
            isCompleted = sched.isNotEmpty() && paidList.size >= sched.size,
        )
    }

    /**
     * Auto-marks installments whose due date has already passed as paid — same
     * behaviour as the JS load path, applied only to installments not yet ticked.
     */
    fun autoMarkOverdue(
        date: String?,
        installmentsCount: Int,
        paidInstallments: List<Int>,
        today: String = Jalali.todayString(),
    ): List<Int> {
        if (date == null || !DATE_RE.matches(date)) return paidInstallments.sorted()
        val paid = paidInstallments.toSortedSet()
        val count = maxOf(1, installmentsCount)
        for (i in 1..count) {
            if (Jalali.addMonths(date, i) < today) paid.add(i)
        }
        return paid.toList()
    }
}

/** Personal purchase / income categories — labels and keys preserved from utils/finance.ts. */
enum class PurchaseCategory(val wire: String, val label: String) {
    CIGARETTE("cigarette", "دخانیات"),
    COFFEE("coffee", "قهوه"),
    RESTAURANT("restaurant", "رستوران"),
    CLOTHING("clothing", "لباس"),
    OTHER("other", "سایر");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: OTHER }
}

enum class PersonalIncomeCategory(val wire: String, val label: String) {
    BONUS("bonus", "پاداش"),
    GIFT("gift", "هدیه"),
    EXTRA_WORK("extra-work", "کار جانبی"),
    OTHER("other", "سایر");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: OTHER }
}

enum class MaintenanceType(val wire: String, val label: String) {
    OIL_CHANGE("oil-change", "تعویض روغن"),
    REPAIR("repair", "تعمیر"),
    TIRE("tire", "لاستیک"),
    WASH("wash", "کارواش"),
    SPARK_PLUG("spark-plug", "شمع"),
    BRAKE_PAD("brake-pad", "لنت ترمز"),
    OTHER("other", "سایر");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: OTHER }
}
