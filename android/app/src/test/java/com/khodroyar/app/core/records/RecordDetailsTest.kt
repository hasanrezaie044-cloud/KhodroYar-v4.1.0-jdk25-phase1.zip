package com.khodroyar.app.core.records

import com.khodroyar.app.data.db.FuelEntity
import com.khodroyar.app.data.db.InsuranceEntity
import com.khodroyar.app.data.db.LoanEntity
import com.khodroyar.app.data.db.LoanInstallmentEntity
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.db.PersonalExpenseEntity
import com.khodroyar.app.data.db.PersonalIncomeEntity
import com.khodroyar.app.data.db.ServiceEntity
import org.junit.Assert.*
import org.junit.Test

/** 5.9.0 universal details: every kind shows its own stored fields, keyed by its own id. */
class RecordDetailsTest {
    private val today = "1405/08/25"

    private fun texts(d: RecordDetail) = d.fields.map { f ->
        when (val v = f.value) {
            is DetailValue.Text -> v.text
            is DetailValue.Money -> v.toman.toString()
            is DetailValue.Number -> v.value.toString()
            is DetailValue.Date -> v.jalali
        }
    }

    @Test fun fuelShowsAllStoredFieldsAndUnitPrice() {
        val d = RecordDetails.fuel(FuelEntity("f-1", "1405/08/02", "free", 40.0, 1_200_000.0, "soren", "t", km = 120_500.0, station = "جایگاه آزادی"))
        assertEquals(RecordKey(RecordKind.FUEL, "f-1"), d.key)
        assertEquals(1_200_000.0, d.amount!!, 0.0)
        val values = texts(d)
        assertTrue("station", "جایگاه آزادی" in values)
        assertTrue("odometer", "120500.0" in values)
        assertTrue("unit price 30,000", "30000.0" in values)
        assertTrue(d.subtitle.endsWith("1405/08/02"))
        assertEquals("fuel", d.key.kind.attachmentType)
    }

    @Test fun maintenanceKeepsNotesPartsAndLegacyPhoto() {
        val d = RecordDetails.maintenance(MaintenanceEntity("m-1", "1405/07/30", "other", "تعویض باتری", 3_000_000.0, 98_000.0, "soren",
            "باتری ۶۰ آمپر", "t", attachmentPath = "/data/x.jpg", replacementParts = "باتری", notes = "گارانتی ۱۸ ماه"))
        assertEquals("تعویض باتری", d.title)
        assertEquals("گارانتی ۱۸ ماه", d.notes)
        assertEquals("/data/x.jpg", d.legacyAttachmentPath)
        assertTrue("باتری ۶۰ آمپر" in texts(d))
        assertTrue("باتری" in texts(d))
    }

    @Test fun blankOptionalFieldsAreOmittedNotShownAsEmptyRows() {
        val d = RecordDetails.expense(PersonalExpenseEntity("e-1", "1405/08/01", "food", "", 160_000.0, "t"))
        assertTrue(d.fields.none { (it.value as? DetailValue.Text)?.text?.isBlank() == true })
        assertTrue(d.fields.none { it.label == "کیلومتر" })
        assertEquals("expense", d.key.kind.attachmentType)
    }

    @Test fun incomeAndServiceUseTheirOwnKinds() {
        val i = RecordDetails.income(PersonalIncomeEntity("i-1", "1405/08/03", "salary", "حقوق", 30_000_000.0, "t"))
        assertEquals(RecordKind.INCOME, i.key.kind)
        assertEquals("حقوق", i.title)
        val s = RecordDetails.service(ServiceEntity("s-1", "1405/08/04", "night", "soren", 85.0, 3.0, 3.0, "22:00", "01:00",
            "دفتر", "فرودگاه", "۲ نفر", "R-12", 1, 0.0, 50_000.0, 0.0, 950_000L, "t"))
        assertEquals(RecordKind.SERVICE, s.key.kind)
        assertTrue("R-12" in texts(s))
        assertTrue("فرودگاه" in texts(s))
        assertEquals(950_000.0, s.amount!!, 0.0)
    }

    @Test fun loanSumsComeFromItsOwnScheduleRowsOnly() {
        val loan = LoanEntity("l-1", "وام خودرو", 12_000_000.0, 18.0, "1405/06/01", 3, "1", "t")
        val rows = listOf(
            LoanInstallmentEntity("r1", "l-1", 1, 4_000_000L, "1405/07/01", paidDate = "1405/07/01"),
            LoanInstallmentEntity("r2", "l-1", 2, 4_000_000L, "1405/08/01"),
            LoanInstallmentEntity("r3", "l-1", 3, 4_000_000L, "1405/09/01"),
            LoanInstallmentEntity("x1", "other-loan", 1, 99_000_000L, "1405/07/01"),
        )
        val d = RecordDetails.loan(loan, rows, today)
        assertEquals(8_000_000.0, d.amount!!, 0.0)
        assertEquals(DetailTone.DANGER, d.tone) // installment 2 is overdue on 1405/08/25
        assertTrue(d.fields.any { it.label == "اقساط پرداخت‌شده" && (it.value as DetailValue.Text).text == "1 از 3" })
        assertTrue(d.deleteMessage.contains("3 قسط"))
    }

    @Test fun installmentDeleteIsAnHonestReset() {
        val loan = LoanEntity("l-1", "وام", 1.0, 0.0, "1405/06/01", 1, "", "t")
        val paid = RecordDetails.installment(LoanInstallmentEntity("r1", "l-1", 1, 5_000L, "1405/07/01", paidDate = "1405/07/03"), loan, today)
        assertEquals("پرداخت‌شده", paid.status)
        assertEquals("بازنشانی", paid.deleteLabel)
        assertNotNull(paid.deleteNote)
        assertNull(RecordKind.INSTALLMENT.attachmentType)
        val overdue = RecordDetails.installment(LoanInstallmentEntity("r2", "l-1", 2, 5_000L, "1405/08/01"), loan, today)
        assertEquals(DetailTone.DANGER, overdue.tone)
    }

    @Test fun insuranceStatusAndCategory() {
        val e = InsuranceEntity("n-1", "بیمه شخص ثالث", "1405/09/10", 9_000_000.0, "بیمه ایران", "P-77", true, "t")
        val d = RecordDetails.insurance(e, today)
        assertEquals(DetailTone.WARNING, d.tone)
        assertEquals("15 روز تا انقضا", d.status)
        assertTrue("بیمه" in texts(d))
        assertEquals("insurance", d.key.kind.attachmentType)
        assertEquals(DocumentStatus.Kind.EXPIRED, DocumentStatus.of("1405/08/24", today).kind)
        assertEquals(DocumentStatus.Kind.SOON, DocumentStatus.of(today, today).kind)
        assertEquals(DocumentStatus.Kind.VALID, DocumentStatus.of("1406/01/01", today).kind)
        assertEquals(DocumentStatus.Kind.NO_DATE, DocumentStatus.of("", today).kind)
        assertEquals(DocumentCategory.INSPECTION, DocumentCategory.of("معاینه فنی"))
        assertEquals(DocumentCategory.LICENSE, DocumentCategory.of("کارت خودرو"))
        assertEquals(DocumentCategory.OTHER, DocumentCategory.of("رسید"))
    }

    @Test fun keysRequireAStoredIdAndWireRoundTrips() {
        assertThrows { RecordKey(RecordKind.FUEL, " ") }
        RecordKind.entries.forEach { assertEquals(it, RecordKind.fromWire(it.wire)) }
        assertEquals(setOf("service", "fuel", "maintenance", "expense", "income", "loan", "insurance"), RecordKind.attachmentTypes)
    }

    private fun assertThrows(block: () -> Unit) {
        try { block(); fail("expected an exception") } catch (_: IllegalArgumentException) {}
    }
}
