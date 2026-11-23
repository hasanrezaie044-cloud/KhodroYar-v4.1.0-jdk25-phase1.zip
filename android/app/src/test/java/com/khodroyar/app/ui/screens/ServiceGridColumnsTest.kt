package com.khodroyar.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** 5.9.1 calendar service shortcuts: the square grid's columns follow the available width. */
class ServiceGridColumnsTest {
    @Test fun columnsGrowWithWidthAndStayWithinTwoToSix() {
        assertEquals(2, serviceGridColumns(288f, 1f))   // 320dp phone minus padding
        assertEquals(3, serviceGridColumns(328f, 1f))   // 360dp phone
        assertEquals(3, serviceGridColumns(379f, 1f))   // 411dp phone
        assertEquals(5, serviceGridColumns(568f, 1f))   // 600dp tablet
        assertEquals(6, serviceGridColumns(1200f, 1f))  // capped
        assertEquals(2, serviceGridColumns(120f, 1f))   // never fewer than two
    }

    @Test fun largeFontUsesWiderCells() {
        assertEquals(2, serviceGridColumns(328f, 1.5f))
        assertEquals(2, serviceGridColumns(379f, 1.5f))
        assertEquals(4, serviceGridColumns(568f, 1.5f))
    }
}
