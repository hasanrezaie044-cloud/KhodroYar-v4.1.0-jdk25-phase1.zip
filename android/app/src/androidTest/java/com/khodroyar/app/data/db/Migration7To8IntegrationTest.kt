package com.khodroyar.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SQLite on the emulator: a version-7 database (5.9.0) with services is opened by the 5.9.1
 * schema. Every service must survive unchanged and start as "not delivered"; the new column must
 * then persist a per-record status across a full close/reopen of the database file.
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8IntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-7-8-test.db"
    private fun build() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun version7ServicesSurviveAndDefaultToNotDelivered() = runBlocking {
        context.deleteDatabase(name)
        build().apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE services DROP COLUMN delivered")
            for (id in listOf("s7-a", "s7-b")) {
                db.execSQL("""INSERT INTO services (id, date, type, carType, km, hours, workHours, startTime, endTime, origin, destination,
                    passengers, requestNumber, tollCount, missionFood, missionToll, missionFine, income, timestamp, attachmentPath)
                    VALUES ('$id', '1405/02/10', 'normal', 'soren', 12.5, 1.0, 1.0, '08:00', '09:00', 'دفتر', 'فرودگاه', '', 'R-$id', 1, 0, 0, 0, 350000, 't', '')""")
            }
            db.version = 7
        }

        val migrated = build()
        try {
            assertEquals(APP_DATABASE_SCHEMA_VERSION, migrated.openHelper.readableDatabase.version)
            assertEquals(8, APP_DATABASE_SCHEMA_VERSION)
            val rows = migrated.services().allOnce()
            assertEquals(listOf("s7-a", "s7-b"), rows.map { it.id }.sorted())
            assertTrue(rows.none { it.delivered })
            assertEquals(350000L, rows.first().income); assertEquals("فرودگاه", rows.first().destination)
            // Same date, independent records: marking one leaves the other untouched.
            assertEquals(1, migrated.services().setDelivered("s7-a", true))
        } finally { migrated.close() }

        val reopened = build()
        try {
            assertTrue(reopened.services().byId("s7-a")!!.delivered)
            assertFalse(reopened.services().byId("s7-b")!!.delivered)
        } finally { reopened.close() }
    }
}
