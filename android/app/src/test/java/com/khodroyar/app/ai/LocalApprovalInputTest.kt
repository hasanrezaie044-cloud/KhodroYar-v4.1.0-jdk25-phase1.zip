package com.khodroyar.app.ai

import org.junit.Assert.*
import org.junit.Test

class LocalApprovalInputTest {
    @Test fun shortAffirmationOnlyConfirmsNonDestructiveVisibleCard() {
        for (s in listOf("بله", "آره", "باشه", "تأیید", "اوکی")) assertEquals(LocalApprovalInput.Decision.CONFIRM, LocalApprovalInput.classify(s, false))
    }
    @Test fun negationAndCorrectionAreNeverAffirmations() {
        for (s in listOf("نه از فردا", "آره ولی مبلغش ۶۰ هزار", "بله نه", "فقط روزهای کاری")) assertEquals(LocalApprovalInput.Decision.REVISE, LocalApprovalInput.classify(s, false))
    }
    @Test fun deletionCannotBeApprovedByAGenericYes() {
        for (s in listOf("بله", "آره", "فعال کن", "انجام بده", "بله حذف کن")) assertEquals(LocalApprovalInput.Decision.REQUIRE_BUTTON, LocalApprovalInput.classify(s, true))
    }
    @Test fun cancellationIsAvailableForAllCards() {
        for (danger in listOf(false, true)) for (s in listOf("لغو", "نه", "بیخیال")) assertEquals(LocalApprovalInput.Decision.CANCEL, LocalApprovalInput.classify(s, danger))
    }
}
