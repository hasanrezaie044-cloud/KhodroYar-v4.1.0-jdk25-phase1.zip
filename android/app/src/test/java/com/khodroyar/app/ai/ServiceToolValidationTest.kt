package com.khodroyar.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation coverage for the service/work-record tools added for both assistants. */
class ServiceToolValidationTest {

    private fun ok(name: String, json: String): ValidatedCall {
        val r = AgentActionValidator.validate(name, JSONObject(json))
        assertTrue("expected Ok but was $r", r is ValidationResult.Ok)
        return (r as ValidationResult.Ok).call
    }

    private fun bad(name: String, json: String) {
        val r = AgentActionValidator.validate(name, JSONObject(json))
        assertTrue("expected Invalid but was $r", r is ValidationResult.Invalid)
    }

    @Test fun addServiceIsRegisteredAsWriteTool() {
        val spec = AgentToolRegistry.find("add_service")!!
        assertEquals(ToolKind.WRITE, spec.kind)
    }

    @Test fun addServiceAcceptsEmptyArgsAndDefaultsLater() {
        // Nothing is strictly required: date defaults to today and type is auto-detected by the app.
        ok("add_service", "{}")
    }

    @Test fun addServiceTakesPersianDigitsAndTimes() {
        val c = ok(
            "add_service",
            """{"date":"۱۴۰۵/۰۸/۱۲","km":"۱۲۴","hours":"۸","start_time":"۶:۳۰","end_time":"۱۴:۳۰","destination":"کارخانه"}""",
        )
        assertEquals("1405/08/12", c.str("date"))
        assertEquals(124.0, c.dbl("km")!!, 0.0)
        assertEquals("06:30", c.str("start_time"))
        assertEquals("14:30", c.str("end_time"))
    }

    @Test fun invalidClockIsRejected() {
        bad("add_service", """{"start_time":"25:00"}""")
        bad("add_service", """{"start_time":"06:75"}""")
        bad("add_service", """{"start_time":"صبح"}""")
    }

    @Test fun serviceTypeIsAnEnum() {
        ok("add_service", """{"service_type":"night"}""")
        ok("add_service", """{"service_type":"auto"}""")
        bad("add_service", """{"service_type":"flying"}""")
    }

    @Test fun hoursBeyondADayAreRejected() {
        bad("add_service", """{"hours":30}""")
        bad("add_service", """{"work_hours":-2}""")
    }

    @Test fun updateServiceNeedsAChange() = bad("update_service", """{"id":"abcd1234"}""")

    @Test fun updateServiceAcceptsFieldChange() {
        val c = ok("update_service", """{"id":"abcd1234","km":150,"end_time":"17:45"}""")
        assertEquals("17:45", c.str("end_time"))
        assertEquals(150.0, c.dbl("km")!!, 0.0)
    }

    @Test fun autoIsNotAValidManualUpdateType() =
        bad("update_service", """{"id":"abcd1234","service_type":"auto"}""")

    @Test fun deleteServiceIsDestructive() {
        assertEquals(ToolKind.DELETE, ok("delete_service", """{"id":"abcd1234"}""").spec.kind)
    }

    @Test fun unknownServiceParamIsDroppedNotExecuted() {
        val c = ok("add_service", """{"km":10,"drop_table":"fuels"}""")
        assertNull(c.str("drop_table"))
    }
}
