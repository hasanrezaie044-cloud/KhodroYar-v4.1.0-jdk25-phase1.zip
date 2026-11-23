package com.khodroyar.app.core.finance

import org.junit.Assert.*
import org.junit.Test

/** 5.9.1 installment-edit audit: only real dates are stored, canonically; title edits keep the schedule. */
class InstallmentEditRulesTest {
    @Test fun canonicalDateAcceptsRealDatesInAnyDigitsAndPads() {
        assertEquals("1405/02/07", InstallmentEditRules.canonicalDate("1405/2/7"))
        assertEquals("1405/02/07", InstallmentEditRules.canonicalDate("۱۴۰۵/۰۲/۰۷"))
        assertEquals("1405/02/07", InstallmentEditRules.canonicalDate(" ١٤٠٥/٢/٧ "))
        assertEquals("1405/12/29", InstallmentEditRules.canonicalDate("1405/12/29"))
    }

    @Test fun canonicalDateRejectsImpossibleOrMalformedDates() {
        assertNull(InstallmentEditRules.canonicalDate("1405/13/01"))
        assertNull(InstallmentEditRules.canonicalDate("1405/07/31")) // months 7..11 have 30 days
        assertNull(InstallmentEditRules.canonicalDate("1405/00/10"))
        assertNull(InstallmentEditRules.canonicalDate("abc"))
        assertNull(InstallmentEditRules.canonicalDate("1405-02-07"))
        assertNull(InstallmentEditRules.canonicalDate(""))
    }

    @Test fun paidDateBlankMeansUnpaidAndFreeTextIsRejected() {
        assertEquals("", InstallmentEditRules.paidDate(""))
        assertEquals("", InstallmentEditRules.paidDate("   "))
        assertEquals("1405/03/01", InstallmentEditRules.paidDate("۱۴۰۵/۳/۱"))
        assertNull("free text must not mark an installment paid", InstallmentEditRules.paidDate("پرداخت شد"))
        assertNull(InstallmentEditRules.paidDate("1405/02/40"))
    }

    @Test fun onlyScheduleTermsTriggerRegeneration() {
        assertFalse(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 12, 0.0, 12e6, 18.0, "1405/01/01", 12, 0.0))
        // 0 and 1 installments are both stored as one installment.
        assertFalse(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 0, 0.0, 12e6, 18.0, "1405/01/01", 1, 0.0))
        assertTrue(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 12, 0.0, 13e6, 18.0, "1405/01/01", 12, 0.0))
        assertTrue(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 12, 0.0, 12e6, 20.0, "1405/01/01", 12, 0.0))
        assertTrue(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 12, 0.0, 12e6, 18.0, "1405/02/01", 12, 0.0))
        assertTrue(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 12, 0.0, 12e6, 18.0, "1405/01/01", 24, 0.0))
        assertTrue(InstallmentEditRules.scheduleTermsChanged(12e6, 18.0, "1405/01/01", 12, 0.0, 12e6, 18.0, "1405/01/01", 12, 1_200_000.0))
    }
}
