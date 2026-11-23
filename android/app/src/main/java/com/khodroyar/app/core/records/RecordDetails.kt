package com.khodroyar.app.core.records

import com.khodroyar.app.core.finance.MaintenanceType
import com.khodroyar.app.core.finance.PersonalIncomeCategory
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.CarType
import com.khodroyar.app.core.rates.FuelType
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.FuelEntity
import com.khodroyar.app.data.db.InsuranceEntity
import com.khodroyar.app.data.db.LoanEntity
import com.khodroyar.app.data.db.LoanInstallmentEntity
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.db.PersonalExpenseEntity
import com.khodroyar.app.data.db.PersonalIncomeEntity
import com.khodroyar.app.data.db.ServiceEntity

/**
 * Universal record details (5.9.0).
 *
 * Every saved row of every module opens the same page: a [RecordKey] (kind + primary key)
 * is resolved by the repository into a [RecordDetail], which is rendered by one composable.
 * Only the *field layout* differs per kind; title, hero, attachments, edit and the
 * confirm-before-delete flow are shared.
 *
 * This file is deliberately Compose-free so the field layouts are JVM-tested: a detail is
 * always built from the one entity that was loaded by its own id, so a page can never show
 * another record's values.
 */
enum class RecordKind(
    val wire: String,
    val title: String,
    /** `record_attachments.recordType`, or null where a kind has no attachment list. */
    val attachmentType: String?,
) {
    SERVICE("service", "سرویس", "service"),
    FUEL("fuel", "سوخت‌گیری", "fuel"),
    MAINTENANCE("maintenance", "تعمیر و نگهداری", "maintenance"),
    EXPENSE("expense", "خرید شخصی", "expense"),
    INCOME("income", "درآمد شخصی", "income"),
    LOAN("loan", "وام", "loan"),
    INSTALLMENT("installment", "قسط وام", null),
    INSURANCE("insurance", "بیمه و مدرک", "insurance");

    companion object {
        fun fromWire(v: String?): RecordKind? = entries.firstOrNull { it.wire == v }
        /** Attachment owners accepted by the repository (backup stays generic over recordType). */
        val attachmentTypes: Set<String> = entries.mapNotNull { it.attachmentType }.toSet()
    }
}

data class RecordKey(val kind: RecordKind, val id: String) {
    init { require(id.isNotBlank()) { "A record key needs a stored id" } }
}

/** Typed so the UI formats money with the privacy mask and Persian digits. */
sealed interface DetailValue {
    data class Text(val text: String) : DetailValue
    data class Money(val toman: Double) : DetailValue
    data class Number(val value: Double, val unit: String) : DetailValue
    data class Date(val jalali: String) : DetailValue
}

data class DetailField(val label: String, val value: DetailValue, val emphasis: Boolean = false)

data class DetailSection(val title: String, val fields: List<DetailField>)

enum class DetailTone { NEUTRAL, GOOD, WARNING, DANGER }

data class RecordDetail(
    val key: RecordKey,
    val title: String,
    val subtitle: String,
    val date: String,
    val amount: Double?,
    val amountLabel: String,
    val status: String? = null,
    val tone: DetailTone = DetailTone.NEUTRAL,
    val sections: List<DetailSection>,
    val notes: String = "",
    /** Legacy single-photo column (services/maintenance), shown read-only next to the attachment list. */
    val legacyAttachmentPath: String = "",
    val deleteTitle: String,
    val deleteMessage: String,
    val deleteLabel: String = "حذف",
    /** A short honest note shown beside the delete button when "delete" has special semantics. */
    val deleteNote: String? = null,
    /** Delivery status for services ("تحویل" toggle); null for every other record kind. */
    val delivered: Boolean? = null,
) {
    val fields: List<DetailField> get() = sections.flatMap { it.fields }
}

object RecordDetails {
    fun weekdayDate(date: String): String = Jalali.parse(date)
        ?.let { "${Jalali.weekdayName(it.year, it.month, it.day)} · $date" } ?: date

    private fun text(label: String, value: String?, emphasis: Boolean = false): DetailField? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { DetailField(label, DetailValue.Text(it), emphasis) }
    private fun money(label: String, value: Double, emphasis: Boolean = false, showZero: Boolean = false): DetailField? =
        value.takeIf { it.isFinite() && (showZero || it != 0.0) }?.let { DetailField(label, DetailValue.Money(it), emphasis) }
    private fun number(label: String, value: Double, unit: String): DetailField? =
        value.takeIf { it.isFinite() && it > 0.0 }?.let { DetailField(label, DetailValue.Number(it, unit)) }
    private fun date(label: String, value: String?): DetailField? =
        value?.takeIf { it.isNotBlank() }?.let { DetailField(label, DetailValue.Date(it)) }
    private fun section(title: String, vararg fields: DetailField?) =
        DetailSection(title, fields.filterNotNull()).takeIf { it.fields.isNotEmpty() }

    fun service(e: ServiceEntity) = RecordDetail(
        key = RecordKey(RecordKind.SERVICE, e.id),
        title = ServiceType.fromWire(e.type).label,
        subtitle = weekdayDate(e.date),
        date = e.date,
        amount = e.income.toDouble(), amountLabel = "درآمد سرویس",
        sections = listOfNotNull(
            section("زمان و مسیر",
                date("تاریخ", e.date),
                text("شروع", e.startTime), text("پایان", e.endTime),
                text("مبدأ", e.origin), text("مقصد", e.destination)),
            section("کارکرد",
                number("کیلومتر", e.km, "کیلومتر"),
                number("ساعت", e.hours, "ساعت"),
                number("ساعت کاری", e.workHours, "ساعت"),
                text("خودرو", CarType.fromWire(e.carType).label),
                e.tollCount.takeIf { it > 0 }?.let { DetailField("تعداد عوارضی", DetailValue.Number(it.toDouble(), "بار")) }),
            section("درخواست",
                text("شماره درخواست", e.requestNumber), text("مسافران", e.passengers)),
            section("هزینه‌های مأموریت",
                money("غذا", e.missionFood), money("عوارض", e.missionToll), money("جریمه", e.missionFine)),
        ),
        legacyAttachmentPath = e.attachmentPath,
        status = if (e.delivered) "تحویل شد" else null,
        tone = if (e.delivered) DetailTone.GOOD else DetailTone.NEUTRAL,
        delivered = e.delivered,
        deleteTitle = "حذف سرویس",
        deleteMessage = "سرویس ${e.date} (${ServiceType.fromWire(e.type).label}) حذف شود؟ پیوست‌ها و کیلومتر ثبت‌شده‌اش هم اصلاح می‌شود.",
    )

    fun fuel(e: FuelEntity) = RecordDetail(
        key = RecordKey(RecordKind.FUEL, e.id),
        title = "${FuelType.fromWire(e.type).label} · ${trim(e.liters)} لیتر",
        subtitle = weekdayDate(e.date),
        date = e.date,
        amount = e.total, amountLabel = "هزینهٔ کل",
        sections = listOfNotNull(
            section("سوخت‌گیری",
                date("تاریخ", e.date),
                text("نوع سوخت", FuelType.fromWire(e.type).label),
                number("مقدار", e.liters, "لیتر"),
                if (e.liters > 0) money("نرخ هر لیتر", e.total / e.liters) else null,
                money("هزینهٔ کل", e.total, emphasis = true, showZero = true)),
            section("خودرو و جایگاه",
                text("خودرو", CarType.fromWire(e.carType).label),
                number("کیلومترشمار", e.km, "کیلومتر"),
                text("جایگاه", e.station)),
        ),
        deleteTitle = "حذف سوخت‌گیری",
        deleteMessage = "سوخت‌گیری ${e.date} با ${trim(e.liters)} لیتر حذف شود؟",
    )

    fun maintenance(e: MaintenanceEntity) = RecordDetail(
        key = RecordKey(RecordKind.MAINTENANCE, e.id),
        title = e.typeText.ifBlank { MaintenanceType.fromWire(e.type).label },
        subtitle = weekdayDate(e.date),
        date = e.date,
        amount = e.cost, amountLabel = "هزینه",
        sections = listOfNotNull(
            section("مشخصات",
                date("تاریخ", e.date),
                text("نوع", MaintenanceType.fromWire(e.type).label),
                number("کیلومتر", e.km, "کیلومتر"),
                text("خودرو", CarType.fromWire(e.carType).label),
                money("هزینه", e.cost, emphasis = true, showZero = true)),
            section("شرح کار",
                text("توضیح", e.description),
                text("قطعات تعویضی", e.replacementParts)),
        ),
        notes = e.notes,
        legacyAttachmentPath = e.attachmentPath,
        deleteTitle = "حذف سابقهٔ تعمیر",
        deleteMessage = "سابقهٔ «${e.typeText.ifBlank { MaintenanceType.fromWire(e.type).label }}» در ${e.date} حذف شود؟",
    )

    fun expense(e: PersonalExpenseEntity) = RecordDetail(
        key = RecordKey(RecordKind.EXPENSE, e.id),
        title = e.title.ifBlank { PurchaseCategory.fromWire(e.category).label },
        subtitle = weekdayDate(e.date),
        date = e.date,
        amount = e.amount, amountLabel = "مبلغ",
        sections = listOfNotNull(
            section("خرید",
                date("تاریخ", e.date),
                text("دسته", PurchaseCategory.fromWire(e.category).label),
                text("عنوان", e.title),
                money("مبلغ", e.amount, emphasis = true, showZero = true),
                number("کیلومتر", e.km, "کیلومتر")),
        ),
        notes = e.notes,
        deleteTitle = "حذف خرید",
        deleteMessage = "خرید «${e.title.ifBlank { PurchaseCategory.fromWire(e.category).label }}» در ${e.date} حذف شود؟",
    )

    fun income(e: PersonalIncomeEntity) = RecordDetail(
        key = RecordKey(RecordKind.INCOME, e.id),
        title = e.title.ifBlank { PersonalIncomeCategory.fromWire(e.category).label },
        subtitle = weekdayDate(e.date),
        date = e.date,
        amount = e.amount, amountLabel = "مبلغ",
        sections = listOfNotNull(
            section("درآمد",
                date("تاریخ", e.date),
                text("دسته", PersonalIncomeCategory.fromWire(e.category).label),
                text("عنوان", e.title),
                money("مبلغ", e.amount, emphasis = true, showZero = true)),
        ),
        deleteTitle = "حذف درآمد",
        deleteMessage = "درآمد «${e.title.ifBlank { PersonalIncomeCategory.fromWire(e.category).label }}» در ${e.date} حذف شود؟",
    )

    fun loan(e: LoanEntity, installments: List<LoanInstallmentEntity>, today: String): RecordDetail {
        val rows = installments.filter { it.loanId == e.id }
        val paid = rows.filter { it.paidDate.isNotBlank() }
        val open = rows.filter { it.paidDate.isBlank() }
        val next = open.minByOrNull { it.dueDate }
        val overdue = open.count { it.dueDate < today }
        return RecordDetail(
            key = RecordKey(RecordKind.LOAN, e.id),
            title = e.title.ifBlank { "وام" },
            subtitle = weekdayDate(e.date),
            date = e.date,
            amount = open.sumOf { it.amount }.toDouble(), amountLabel = "مانده بدهی",
            status = when { rows.isNotEmpty() && open.isEmpty() -> "تسویه‌شده"; overdue > 0 -> "$overdue قسط سررسیدگذشته"; else -> null },
            tone = when { rows.isNotEmpty() && open.isEmpty() -> DetailTone.GOOD; overdue > 0 -> DetailTone.DANGER; else -> DetailTone.NEUTRAL },
            sections = listOfNotNull(
                section("مشخصات وام",
                    date("تاریخ دریافت", e.date),
                    money("اصل وام", e.totalAmount, showZero = true),
                    DetailField("سود", DetailValue.Number(e.interestPercent, "درصد")),
                    DetailField("تعداد اقساط", DetailValue.Number(e.installmentsCount.toDouble(), "قسط")),
                    money("قسط ماهانه (ثابت)", e.monthlyInstallment)),
                section("وضعیت پرداخت",
                    money("مبلغ کل با سود", rows.sumOf { it.amount }.toDouble(), showZero = true),
                    money("پرداخت‌شده", paid.sumOf { it.amount }.toDouble(), showZero = true),
                    money("باقی‌مانده", open.sumOf { it.amount }.toDouble(), emphasis = true, showZero = true),
                    DetailField("اقساط پرداخت‌شده", DetailValue.Text("${paid.size} از ${rows.size}")),
                    next?.let { DetailField("قسط بعدی", DetailValue.Text("${it.dueDate} · قسط ${it.number}")) }),
            ),
            notes = e.notes,
            deleteTitle = "حذف وام",
            deleteMessage = "وام «${e.title.ifBlank { "وام" }}» همراه جدول ${rows.size} قسط و پیوست‌هایش حذف شود؟ این کار برگشت‌پذیر نیست.",
        )
    }

    fun installment(row: LoanInstallmentEntity, loan: LoanEntity?, today: String): RecordDetail {
        val paid = row.paidDate.isNotBlank()
        val overdue = !paid && row.dueDate < today
        return RecordDetail(
            key = RecordKey(RecordKind.INSTALLMENT, row.id),
            title = "قسط ${row.number} · ${loan?.title?.ifBlank { null } ?: "وام"}",
            subtitle = "سررسید ${weekdayDate(row.dueDate)}",
            date = row.dueDate,
            amount = row.amount.toDouble(), amountLabel = "مبلغ قسط",
            status = when { paid -> "پرداخت‌شده"; overdue -> "سررسید گذشته"; else -> "پرداخت‌نشده" },
            tone = when { paid -> DetailTone.GOOD; overdue -> DetailTone.DANGER; else -> DetailTone.WARNING },
            sections = listOfNotNull(
                section("قسط",
                    text("وام", loan?.title?.ifBlank { "وام" }),
                    DetailField("شمارهٔ قسط", DetailValue.Number(row.number.toDouble(), "")),
                    date("تاریخ سررسید", row.dueDate),
                    money("مبلغ", row.amount.toDouble(), emphasis = true, showZero = true),
                    date("تاریخ پرداخت", row.paidDate)),
            ),
            notes = row.notes,
            deleteTitle = "بازنشانی قسط",
            deleteMessage = "قسط ${row.number} به مبلغ و تاریخ جدول وام برگردد و پرداختش پاک شود؟",
            deleteLabel = "بازنشانی",
            deleteNote = "قسط بخشی از جدول وام است؛ «حذف» آن را به مقدار برنامه‌ریزی‌شده برمی‌گرداند. برای حذف کامل، خود وام را حذف کنید.",
        )
    }

    fun insurance(e: InsuranceEntity, today: String): RecordDetail {
        val status = DocumentStatus.of(e.expiryDate, today)
        return RecordDetail(
            key = RecordKey(RecordKind.INSURANCE, e.id),
            title = e.type.ifBlank { "بیمه" },
            subtitle = listOf(e.company, e.policyNumber.takeIf { it.isNotBlank() }?.let { "شماره $it" }).filter { !it.isNullOrBlank() }.joinToString(" · "),
            date = e.expiryDate,
            amount = e.cost.takeIf { it > 0 }, amountLabel = "هزینه",
            status = status.label,
            tone = status.tone,
            sections = listOfNotNull(
                section("مدرک",
                    text("نوع", e.type),
                    text("دسته", DocumentCategory.of(e.type).label),
                    text("شرکت / صادرکننده", e.company),
                    text("شماره", e.policyNumber),
                    money("هزینه", e.cost)),
                section("اعتبار",
                    date("تاریخ انقضا", e.expiryDate),
                    DetailField("وضعیت", DetailValue.Text(status.label)),
                    DetailField("یادآوری پیش از انقضا", DetailValue.Text(if (e.reminder) "فعال" else "خاموش"))),
            ),
            deleteTitle = "حذف بیمه یا مدرک",
            deleteMessage = "«${e.type.ifBlank { "بیمه" }}» و پیوست‌هایش حذف شود؟",
        )
    }

    private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
}

/** Expiry state of an insurance policy or vehicle document, from Jalali dates only. */
data class DocumentStatus(val kind: Kind, val daysLeft: Int?) {
    enum class Kind { NO_DATE, VALID, SOON, EXPIRED }

    val label: String get() = when (kind) {
        Kind.NO_DATE -> "بدون تاریخ انقضا"
        Kind.EXPIRED -> if ((daysLeft ?: 0) == 0) "امروز منقضی می‌شود" else "منقضی شده · ${-(daysLeft ?: 0)} روز پیش"
        Kind.SOON -> if (daysLeft == 0) "امروز منقضی می‌شود" else "$daysLeft روز تا انقضا"
        Kind.VALID -> "معتبر · $daysLeft روز مانده"
    }
    val tone: DetailTone get() = when (kind) {
        Kind.NO_DATE -> DetailTone.NEUTRAL
        Kind.VALID -> DetailTone.GOOD
        Kind.SOON -> DetailTone.WARNING
        Kind.EXPIRED -> DetailTone.DANGER
    }

    companion object {
        const val SOON_DAYS = 30
        fun of(expiry: String, today: String): DocumentStatus {
            if (Jalali.parse(expiry) == null || Jalali.parse(today) == null) return DocumentStatus(Kind.NO_DATE, null)
            val left = Jalali.daysBetween(today, expiry)
            return DocumentStatus(when { left < 0 -> Kind.EXPIRED; left <= SOON_DAYS -> Kind.SOON; else -> Kind.VALID }, left)
        }
    }
}

/** Booklet category derived from the stored free-text type (no schema change). */
enum class DocumentCategory(val label: String) {
    INSURANCE("بیمه"), INSPECTION("معاینه فنی"), LICENSE("گواهینامه و کارت"), OWNERSHIP("سند و مالکیت"), OTHER("مدرک");

    companion object {
        fun of(type: String): DocumentCategory {
            val t = type.trim()
            return when {
                "بیمه" in t || "insurance" in t.lowercase() -> INSURANCE
                "معاینه" in t -> INSPECTION
                "گواهینامه" in t || "کارت" in t -> LICENSE
                "سند" in t || "مالکیت" in t || "پلاک" in t -> OWNERSHIP
                else -> OTHER
            }
        }
    }
}
