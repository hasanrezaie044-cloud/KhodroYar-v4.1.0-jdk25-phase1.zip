package com.khodroyar.app.reconciliation

import org.junit.Assert.*
import org.junit.Test

class StatementParserTest {
    private val columns = StatementColumns(ColumnBand(.66, 1.0), ColumnBand(.33, .65), ColumnBand(0.0, .32))
    private fun word(text: String, x: Int, y: Int = 100, confidence: Int = 95) = OcrWord(text, confidence, RowArea(x, y, x + 150, y + 30))
    private fun page(id: String = "p1", words: List<OcrWord> = listOf(word("۱۴۰۵/۰۱/۱۲", 750), word("۱۶۰", 420), word("۱۰", 100))) = OcrPage(id, 1000, 1400, words)

    @Test fun usesExplicitColumnGeometryNotOriginDestinationOrReadingOrder() {
        val r = StatementParser.parse(page(), columns).single()
        assertEquals("1405/01/12", r.date); assertEquals("160", r.kilometers); assertEquals("10", r.hours)
        assertFalse(r.confirmed); assertEquals(RowArea(100, 100, 900, 130), r.area)
    }
    @Test fun absentColumnMappingNeverGuessesUnlabelledAmounts() {
        val r = StatementParser.parse(page()).single()
        assertEquals("1405/01/12", r.date); assertEquals("", r.kilometers); assertEquals("", r.hours)
        assertNull(ReconciliationEngine.values(r))
    }
    @Test fun totalAtBottomAndPageNumberAreExcluded() {
        val words = page().words + listOf(word("جمع کل", 750, 200), word("۱۶۰", 420, 200), word("۱۰", 100, 200), word("صفحه ۱", 420, 300))
        val rows = StatementParser.parse(page(words = words), columns)
        assertEquals(1, rows.count { it.kind == StatementRowKind.SERVICE })
        assertEquals(1, rows.count { it.kind == StatementRowKind.TOTAL })
        assertEquals(1, rows.count { it.kind == StatementRowKind.OTHER })
    }
    @Test fun textualHeaderDoesNotBecomeService() {
        val r = StatementParser.parse(page(words = listOf(word("تاریخ", 750), word("کیلومتر", 420), word("ساعت", 100))), columns).single()
        assertEquals(StatementRowKind.OTHER, r.kind)
    }
    @Test fun unreadableKilometersStayUnreadableWithImageAreaAndConfidence() {
        val r = StatementParser.parse(page(words = listOf(word("۱۴۰۵/۰۱/۱۲", 750), word("؟", 420, confidence = 20), word("۱۰", 100))), columns).single()
        assertEquals("؟", r.kilometers); assertEquals(20, r.confidence); assertNotNull(r.area); assertNull(ReconciliationEngine.values(r))
    }
    @Test fun missingCellDoesNotShiftHoursIntoDistanceColumn() {
        val r = StatementParser.parse(page(words = listOf(word("۱۴۰۵/۰۱/۱۲", 750), word("۱۰", 100))), columns).single()
        assertEquals("", r.kilometers); assertEquals("10", r.hours); assertNull(ReconciliationEngine.values(r))
    }
    @Test fun repeatedRowsAcrossPagesAreFlaggedButNotWithinSamePage() {
        val a = StatementParser.parse(page(), columns).single()
        val b = StatementParser.parse(page("p2"), columns).single()
        assertEquals(2, ReconciliationEngine.duplicateCandidates(listOf(a, b)).size)
        assertTrue(ReconciliationEngine.duplicateCandidates(listOf(a, a.copy(id = "other"))).isEmpty())
    }
    @Test fun eachSameDatePhysicalLineIsASeparateService() {
        val p = page(words = page().words + listOf(word("۱۴۰۵/۰۱/۱۲", 750, 200), word("۱۶۰", 420, 200), word("۱۰", 100, 200)))
        assertEquals(2, StatementParser.parse(p, columns).count { it.kind == StatementRowKind.SERVICE })
    }
    @Test fun routeDescriptionCannotTurnADatedServiceIntoATotalRow() {
        val p = page(words = page().words + word("جمع آوری", 225))
        val precise = StatementColumns(ColumnBand(.7, 1.0), ColumnBand(.4, .65), ColumnBand(0.0, .25))
        val r = StatementParser.parse(p, precise).single()
        assertEquals(StatementRowKind.SERVICE, r.kind)
        assertNotNull(ReconciliationEngine.values(r))
    }

    @Test fun damagedDateWithNumericCellsRequiresReviewInsteadOfDisappearing() {
        val r = StatementParser.parse(page(words = listOf(word("۱۴۰۵/؟/۱۲", 750), word("۱۶۰", 420), word("۱۰", 100)))).single()
        assertEquals(StatementRowKind.SERVICE, r.kind)
        assertNull(ReconciliationEngine.values(r)); assertFalse(r.confirmed); assertNotNull(r.area)
    }

    @Test fun overlappingOrReversedColumnsAreInvalid() {
        assertFalse(StatementColumns(ColumnBand(.5, 1.0), ColumnBand(.4, .7), ColumnBand(.0, .3)).valid())
        assertFalse(ColumnBand(.5, .4).valid()); assertFalse(ColumnBand(-.1, .3).valid())
    }
}
