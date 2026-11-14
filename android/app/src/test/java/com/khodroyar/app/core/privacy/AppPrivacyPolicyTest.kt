package com.khodroyar.app.core.privacy

import org.junit.Assert.*
import org.junit.Test

class AppPrivacyPolicyTest {
    private val text = AppPrivacyPolicy.sections.joinToString("\n") { it.body }
    @Test fun materialPolicyChangeRequiresExplicitAcceptanceAgain() {
        assertEquals(5, AppPrivacyPolicy.VERSION)
        assertTrue(AppPrivacyPolicy.INTRO.contains("پذیرش صریح"))
    }
    @Test fun policyDisclosesInternetUploadsAndNeverMakesTheRetiredOfflineOnlyClaim() {
        assertTrue(text.contains("FastOCR"))
        assertTrue(text.contains("تأیید همان ارسال"))
        assertTrue(text.contains("رمزنگاری"))
        assertFalse(text.contains("اپ به اینترنت متصل نمی‌شود"))
        assertFalse(text.contains("هیچ داده‌ای"))
    }
    @Test fun freeAgentDisclosesModelProviderConsentAndVerifiedWriteBoundary() {
        for (term in listOf("OpenRouter", "ارائه‌دهندهٔ مدل", "رضایت جداگانه", "قیمت‌صفر", "منع جایگزینی پولی", "Android Keystore", "بازخوانی‌شده", "پیش‌نمایش و تأیید صریح", "ثبت دستی", "Guardrail", "ساختگی", "سقف روزانه", "جابه‌جا", "اختیاری", "Gemini", "در پس‌زمینه اجرا نمی‌شود")) assertTrue(term, text.contains(term))
    }
    @Test fun inputOcrThirdPartyDecisionsAndUnofficialDocumentRisksAreCovered() {
        for (term in listOf("کیلومترشمار", "پیمایش", "فواصل نگهداری", "ارقام گزارش شرکت", "یادآورها", "مقایسه‌ها", "برآورد هزینه", "پیشنهادهای نگهداری",
            "ناقص یا اشتباه", "تمام تاریخ‌ها، تعدادها و اعداد", "اسناد رسمی شرکت", "فاکتور", "مدارک قانونی", "خارج از کنترل توسعه‌دهنده", "مسئولیت تصمیم‌های")) {
            assertTrue(term, text.contains(term))
        }
        assertTrue(text.contains("تا حد مجاز قانونی"))
    }
}
