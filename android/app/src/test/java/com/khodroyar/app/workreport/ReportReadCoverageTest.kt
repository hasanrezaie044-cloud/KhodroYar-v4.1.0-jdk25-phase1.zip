package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

class ReportReadCoverageTest {
    private val pages = listOf(ReportPage("a", "a.pdf", "A", 0), ReportPage("b", "b.pdf", "B", 0))
    private fun read(id: String, incomplete: Boolean = false, text: String = "synthetic") =
        OcrPage(id, 1, emptyList(), text, source = ReportTextSource.FASTOCR, incomplete = incomplete)

    @Test fun onlineTextNeedsNoOfflineWordGeometryForComparison() {
        assertTrue(ReportReadCoverage.complete(pages, listOf(read("a"), read("b"))))
    }
    @Test fun partialTruncatedOrUnprocessedPagesCannotPassTheReviewGate() {
        assertFalse(ReportReadCoverage.complete(pages, listOf(read("a"))))
        assertFalse(ReportReadCoverage.complete(pages, listOf(read("a"), read("b", incomplete = true))))
        assertFalse(ReportReadCoverage.complete(pages, listOf(read("a"), read("b", text = " "))))
    }
    @Test fun wrongAndDuplicatePageIdsCannotSatisfyCoverage() {
        assertFalse(ReportReadCoverage.complete(pages, listOf(read("a"), read("a"))))
        assertFalse(ReportReadCoverage.complete(pages, listOf(read("a"), read("c"))))
        assertFalse(ReportReadCoverage.complete(listOf(pages[0], pages[0]), listOf(read("a"), read("a"))))
        assertFalse(ReportReadCoverage.complete(emptyList(), emptyList()))
    }
    @Test fun explicitUserTextIsReviewInputNotAnOfflineOcrPass() {
        val pasted = read("a").copy(source = ReportTextSource.USER_TEXT)
        assertTrue(ReportReadCoverage.complete(listOf(pages[0]), listOf(pasted)))
    }
    @Test fun existingLegacyWordDraftsCanStillBeReviewed() {
        val old = OcrPage("a", 1, listOf(OcrWord("1405/05/10", .1f, .1f, .2f, .2f, 80f)), "")
        assertTrue(ReportReadCoverage.complete(listOf(pages[0]), listOf(old)))
    }
    @Test fun gapWarnsWithoutFillingAnyDatesOrRowsFromAppCounts() {
        val rows = FastOcrReportText.parsePage("a", 1, "7 1405/05/10 شهر الف شهر ب 40 .\n10 1405/05/12 شهر ب شهر الف 95 .")
        val messages = ReportReadCoverage.warnings(pages, listOf(read("a")), rows)
        assertEquals(2, rows.size)
        assertTrue(messages.any { it.contains("8، 9") })
        assertTrue(messages.any { it.contains("صفحهٔ 2") })
    }
    @Test fun emptyRowsAndIncompleteReplyHaveDifferentWarnings() {
        assertTrue(ReportReadCoverage.warnings(listOf(pages[0]), listOf(read("a")), emptyList()).single().contains("به معنی نبود سرویس نیست"))
        assertTrue(ReportReadCoverage.warnings(listOf(pages[0]), listOf(read("a", true)), emptyList()).single().contains("ناقص"))
    }
    @Test fun newDocumentsDoNotPretendTheyHaveBeenRead() {
        assertTrue(ReportReadCoverage.warnings(pages, emptyList(), emptyList()).isEmpty())
    }
}
