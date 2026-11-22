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
 * Real SQLite on the emulator: a version-6 database with user rows is opened by the 5.8.0 Room
 * schema. The migration must keep every row, add the new columns with neutral defaults and create
 * the installment/attachment tables (Room itself validates the resulting schema on open).
 */
@RunWith(AndroidJUnit4::class)
class Migration6To7IntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-6-7-test.db"
    private fun build() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun version6RowsSurviveAndNewTablesWork() = runBlocking {
        context.deleteDatabase(name)
        // 1) Create the current schema, then turn it back into the exact version-6 shape.
        build().apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE record_attachments")
            db.execSQL("DROP TABLE loan_installments")
            listOf("maintenances" to "replacementParts", "maintenances" to "notes", "fuels" to "km", "fuels" to "station",
                "loans" to "monthlyInstallment", "loans" to "notes", "personal_expenses" to "km", "personal_expenses" to "notes")
                .forEach { (table, column) -> db.execSQL("ALTER TABLE $table DROP COLUMN $column") }
            db.execSQL("INSERT INTO fuels (id, date, type, liters, total, carType, timestamp) VALUES ('f6', '1403/12/01', 'gasoline', 30.0, 450000.0, 'soren', 't')")
            db.execSQL("INSERT INTO loans (id, title, totalAmount, interestPercent, date, installmentsCount, paidInstallments, timestamp) VALUES ('l6', 'وام قدیمی', 12000000.0, 0.0, '1403/01/01', 12, '1,2', 't')")
            db.execSQL("INSERT INTO personal_expenses (id, date, category, title, amount, timestamp) VALUES ('e6', '1403/12/02', 'food', 'ناهار', 250000.0, 't')")
            db.version = 6
        }

        // 2) Open with Room 7: migration + schema validation happen here.
        val migrated = build()
        try {
            val fuel = migrated.fuels().allOnce().single()
            assertEquals("f6", fuel.id); assertEquals(450000.0, fuel.total, 0.0); assertEquals(0.0, fuel.km, 0.0); assertEquals("", fuel.station)
            val loan = migrated.loans().allOnce().single()
            assertEquals("1,2", loan.paidInstallments); assertEquals(0.0, loan.monthlyInstallment, 0.0); assertEquals("", loan.notes)
            val expense = migrated.personalExpenses().allOnce().single()
            assertEquals(250000.0, expense.amount, 0.0); assertEquals("", expense.notes)

            migrated.loanInstallments().insert(LoanInstallmentEntity("i1", "l6", 1, 1_000_000, "1403/02/01", "1403/02/01"))
            migrated.attachments().insert(RecordAttachmentEntity("a1", "loan", "l6", "/data/none.jpg", "image/jpeg", 1L))
            assertEquals(1, migrated.loanInstallments().forLoanOnce("l6").size)
            assertEquals("loan", migrated.attachments().allOnce().single().recordType)
            assertEquals(7, migrated.openHelper.readableDatabase.version)
        } finally { migrated.close() }
    }
}
