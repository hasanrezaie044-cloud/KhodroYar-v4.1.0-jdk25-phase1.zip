package com.khodroyar.app.ai

import com.khodroyar.app.automation.ServiceTemplate
import org.junit.Assert.*
import org.junit.Test

class ServicePresetRevisionTest {
    private fun saved() = ServiceTemplate("request", "soren", "مبارکه", "اصفهان", 80.0, 2.0, "20:00", "22:00", 1, "مخاطب")

    @Test fun onlyTheExplicitCategoryChangesInTheNewSnapshot() {
        val original = saved()
        val revised = ServicePresetRevision.apply(original, "night")
        assertEquals("night", revised.type)
        assertEquals(original.copy(type = "night"), revised)
        assertEquals("request", original.type)
        assertEquals(original, ServicePresetRevision.apply(original, null))
    }

    @Test fun unsupportedTypeAndInvalidSavedFieldsNeverCreateAnAppSnapshot() {
        for (action in listOf<() -> Unit>(
            { ServicePresetRevision.apply(saved(), "day") },
            { ServicePresetRevision.apply(saved().copy(km = Double.NaN), "night") },
            { ServicePresetRevision.apply(saved().copy(origin = ""), "night") },
        )) {
            try { action(); fail("Invalid service must not be prepared") } catch (_: AgentUserException) { }
        }
    }

    @Test fun salaryAndLeaveCannotLendTheirFieldsToAnUnrelatedTrip() {
        for (type in listOf("fixed-salary", "leave")) {
            val pay = saved().copy(type = type, km = 0.0, hours = 0.0)
            assertEquals(pay, ServicePresetRevision.apply(pay, null))
            try { ServicePresetRevision.apply(pay, "night"); fail("A real trip needs its own fields") }
            catch (e: AgentUserException) { assertTrue(e.userMessage.contains("کیلومتر")) }
        }
    }
}
