package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

/**
 * Fixed-template-only acceptance policy. A lone CANONICAL native reading at the
 * form-specific 75 threshold is usable; two strong disagreeing readings must be
 * blank (never an arbitrary choice, never a boosted score). Generic thresholds
 * are unchanged; classic native evidence still needs >=85.
 */
class CompanyCellPolicyTest {
    private fun tess(raw: String, score: Float) = CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.TESSERACT, raw, score)
    private fun glyph(raw: String, score: Float) = CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.GLYPH, raw, score)
    private fun classic(raw: String, score: Float) = CompanyCellPolicy.Evidence(CompanyCellPolicy.Engine.CLASSIC, raw, score)

    @Test
    fun loneCanonicalNativeAtSeventyFiveIsAccepted() {
        val decision = CompanyCellPolicy.decide(NumericField.KM, listOf(tess("۵۵", 76f)))
        assertEquals("55", decision.text)
        assertFalse(decision.conflict)
    }

    @Test
    fun belowThresholdOrNonCanonicalNativeIsRejected() {
        assertEquals("", CompanyCellPolicy.decide(NumericField.KM, listOf(tess("55", 74.9f))).text)
        // No score can rescue a malformed value; digits are never repaired.
        assertEquals("", CompanyCellPolicy.decide(NumericField.KM, listOf(tess("۱۲٫٫۳", 99f))).text)
        assertEquals("", CompanyCellPolicy.decide(NumericField.ORDINAL, listOf(tess("0", 99f))).text)
    }

    @Test
    fun twoStrongDisagreeingReadingsStayBlank() {
        // Historical failure: Tesseract saw Western zeros at 95%, the glyph
        // baseline saw Persian 55 at ~76. Either would be wrong alone.
        val conflict = CompanyCellPolicy.decide(NumericField.KM, listOf(tess("00", 95f), glyph("۵۵", 76f)))
        assertTrue(conflict.conflict)
        assertEquals("", conflict.text)
        val second = CompanyCellPolicy.decide(NumericField.KM, listOf(tess("5", 96f), glyph("۰", 90f)))
        assertTrue(second.conflict)
        assertEquals("", second.text)
    }

    @Test
    fun agreementNeverBoostsScoresButOneValidReadingSuffices() {
        val solo = CompanyCellPolicy.decide(NumericField.KM, listOf(tess("55", 76f), glyph("55", 71f)))
        assertEquals("55", solo.text)
        assertEquals(71f, solo.confidence, 0.001f) // score of the accepting strong glyph reading, never boosted
        assertEquals("55", CompanyCellPolicy.decide(NumericField.KM, listOf(glyph("۵۵", 71f))).text)
        assertEquals("", CompanyCellPolicy.decide(NumericField.KM, listOf(glyph("۵۵", 69.9f))).text)
        assertEquals("40", CompanyCellPolicy.decide(NumericField.KM, listOf(classic("۴۰", 85f))).text)
        assertEquals("", CompanyCellPolicy.decide(NumericField.KM, listOf(classic("۴۰", 84.9f))).text)
        // A below-threshold glyph must not veto a lone canonical native at 75.
        assertEquals("40", CompanyCellPolicy.decide(NumericField.KM, listOf(tess("40", 76f), glyph("41", 60f))).text)
    }

    @Test
    fun strongViewConflictFlagForcesBlank() {
        val decision = CompanyCellPolicy.decide(NumericField.KM, listOf(tess("40", 99f)), strongViewConflict = true)
        assertTrue(decision.conflict)
        assertEquals("", decision.text)
    }

    @Test
    fun dotDashAndDateFormattingRules() {
        // A printed dash is not numeric zero at any confidence.
        assertEquals("", CompanyCellPolicy.decide(NumericField.HOURS, listOf(tess("-", 90f))).text)
        assertEquals("", CompanyCellPolicy.decide(NumericField.HOURS, listOf(tess(".", 90f))).text)
        val date = CompanyCellPolicy.decide(NumericField.DATE, listOf(tess("١٤٠٣ / ٠٤ / ١٢", 80f)))
        assertEquals("1403/04/12", date.text)
        val compact = CompanyCellPolicy.decide(NumericField.DATE, listOf(tess("۱۴۰۳۰۴۱۸", 90f)))
        assertEquals("1403/04/18", compact.text)
        assertEquals("", CompanyCellPolicy.decide(NumericField.DATE, listOf(tess("1403/13/12", 99f))).text)
    }

    @Test
    fun dateTextOnlyReformatsNeverRepairs() {
        assertEquals("1403/04/12", ReportDateText.parse("١۴٠۳ / ۰٤ / ۱۲"))
        assertEquals("1403/04/12", ReportDateText.parse("۱۴۰۳--۰۴.۱۲"))
        assertEquals("1403/04/18", ReportDateText.parse("14030418"))
        assertNull(ReportDateText.parse("1403/04/  ۰x"))
        assertNull(ReportDateText.parse("1402/12/30")) // 1402 is common: Esfand has 29 days
    }
}
