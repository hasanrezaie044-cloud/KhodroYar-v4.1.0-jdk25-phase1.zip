package com.khodroyar.app.automation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutomationCodecTest {
    private fun expense() = AutomationRule("2bf15c39-82f4-41f2-85ca-ac4526f9eeac", RuleKind.EXPENSE, "قهوه",
        RepeatSchedule(RepeatFrequency.WORKDAYS, "1405/08/20", zoneId = "Asia/Tehran"), 50_000.0, "coffee")
    private fun fails(block: () -> Unit) { try { block(); fail("must fail closed") } catch (_: IllegalArgumentException) {} }

    @Test fun emptyStoreRoundTrips() { assertEquals(emptyList<AutomationRule>(), AutomationCodec.decode(AutomationCodec.encode(emptyList()))) }
    @Test fun expenseRoundTripsWithRunState() {
        val r = expense().copy(lastDate = "1405/08/20", lastMessage = "ثبت تأیید شد", failures = 0, revision = 3)
        assertEquals(listOf(r), AutomationCodec.decode(AutomationCodec.encode(listOf(r))))
    }
    @Test fun savedServiceIsASnapshotNotASettingsReference() {
        val t = ServiceTemplate("night", "soren", "مبارکه", "اصفهان", 55.0, 1.0, "08:00", "09:00", 1, "")
        val r = expense().copy(kind = RuleKind.SERVICE, amount = 0.0, service = t)
        assertEquals(t, AutomationCodec.decode(AutomationCodec.encode(listOf(r))).single().service)
    }
    @Test fun scheduledTrafficDoesNotStoreAnyApiKey() {
        val r = expense().copy(kind = RuleKind.TRAFFIC, amount = 0.0)
        val json = AutomationCodec.encode(listOf(r))
        assertFalse(json.contains("key", ignoreCase = true)); assertFalse(json.contains("token", ignoreCase = true))
        assertEquals(r, AutomationCodec.decode(json).single())
    }
    @Test fun unknownVersionDoesNotActivateRules() { fails { AutomationCodec.decode("{\"version\":99,\"rules\":[]}") } }
    @Test fun invalidAmountCannotBePersisted() { fails { AutomationCodec.encode(listOf(expense().copy(amount = -1.0))) } }
    @Test fun duplicateIdsCannotBePersisted() { fails { AutomationCodec.encode(listOf(expense(), expense())) } }
    @Test fun corruptDecodedRuleCannotBecomeLive() {
        val o = JSONObject(AutomationCodec.encode(listOf(expense())))
        o.getJSONArray("rules").getJSONObject(0).put("amount", -1.0)
        fails { AutomationCodec.decode(o.toString()) }
    }
    @Test fun oversizedFileIsRejectedBeforeParsing() { fails { AutomationCodec.decode(" ".repeat(256_001)) } }
    @Test fun tooManyRulesAreRejected() { fails { AutomationCodec.encode((0..32).map { expense().copy(id = java.util.UUID.randomUUID().toString()) }) } }
    @Test fun invalidTemplateCannotBecomeAnAutomatedTrip() {
        val t = ServiceTemplate("unknown", "soren", "", "اصفهان", 50.0, 1.0, "08:00", "09:00", 0)
        assertFalse(t.valid()); fails { AutomationCodec.encode(listOf(expense().copy(kind = RuleKind.SERVICE, service = t))) }
    }
    @Test fun weekdaySelectionRoundTrips() {
        val r = expense().copy(schedule = expense().schedule.copy(frequency = RepeatFrequency.WEEKDAYS, weekdays = setOf(0, 2, 4)))
        assertEquals(r, AutomationCodec.decode(AutomationCodec.encode(listOf(r))).single())
    }
}
