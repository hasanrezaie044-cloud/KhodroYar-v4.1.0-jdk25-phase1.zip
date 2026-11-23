package com.khodroyar.app.data.legacy

import com.khodroyar.app.core.records.DetailTone
import com.khodroyar.app.core.records.RecordDetails
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.repo.toJson
import org.junit.Assert.*
import org.junit.Test

/** 5.9.1 delivery status: exported per service, restored per service, absent in old backups = not delivered. */
class ServiceDeliveryBackupTest {
    private fun service(id: String, delivered: Boolean) = ServiceEntity(id, "1405/08/20", "night", "soren", 80.0, 3.5, 3.5, "08:00", "11:30",
        "مبارکه", "اصفهان", "", "", 1, 0.0, 0.0, 0.0, 2_750_000L, "t", delivered = delivered)

    private fun backup(vararg services: String) = """{"version":"2.3","services":[${services.joinToString(",")}],"fuels":[],"maintenances":[]}"""

    @Test fun exportCarriesDeliveredAndRestoreKeepsEachServiceIndependent() {
        val a = service("same-day-a", delivered = true)
        val b = service("same-day-b", delivered = false)
        assertTrue(a.toJson().contains("\"delivered\":true"))
        assertTrue(b.toJson().contains("\"delivered\":false"))
        val restored = requireNotNull(LegacyBackup.parse(backup(a.toJson(), b.toJson()))).services.associateBy { it.id }
        assertEquals(a, restored.getValue("same-day-a"))
        assertEquals(b, restored.getValue("same-day-b"))
    }

    @Test fun olderBackupWithoutTheFieldRestoresAsNotDelivered() {
        val old = service("old", delivered = false).toJson().replace(",\"delivered\":false", "")
        assertFalse(old.contains("delivered"))
        val restored = requireNotNull(LegacyBackup.parse(backup(old))).services.single()
        assertEquals("old", restored.id)
        assertFalse(restored.delivered)
        assertEquals(2_750_000L, restored.income)
    }

    @Test fun serviceDetailExposesDeliveryStateOnlyForServices() {
        val delivered = RecordDetails.service(service("s", delivered = true))
        assertEquals(true, delivered.delivered)
        assertEquals("تحویل شد", delivered.status)
        assertEquals(DetailTone.GOOD, delivered.tone)
        val open = RecordDetails.service(service("s", delivered = false))
        assertEquals(false, open.delivered)
        assertNull(open.status)
    }
}
