package com.khodroyar.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonnelInitialsTest {
    @Test fun twoWordsGiveTwoLettersJoinedByZwnj() {
        assertEquals("ح\u200cر", personnelInitials("حسن رضایی"))
        assertEquals("ح\u200cر", personnelInitials("  حسن   رضایی  محمدی "))
    }

    @Test fun singleWordAndBlankNamesNeverGuess() {
        assertEquals("ع", personnelInitials("علی"))
        assertEquals("؟", personnelInitials(""))
        assertEquals("؟", personnelInitials("   "))
    }
}
