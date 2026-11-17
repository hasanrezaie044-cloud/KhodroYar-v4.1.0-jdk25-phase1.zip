package com.khodroyar.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS insurance (
                id TEXT PRIMARY KEY NOT NULL,
                type TEXT NOT NULL,
                expiryDate TEXT NOT NULL,
                cost REAL NOT NULL,
                company TEXT NOT NULL,
                policyNumber TEXT NOT NULL,
                reminder INTEGER NOT NULL,
                timestamp TEXT NOT NULL
            )
        """)
        database.execSQL("CREATE INDEX IF NOT EXISTS index_insurance_expiryDate ON insurance(expiryDate)")
    }
}


val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE services ADD COLUMN attachmentPath TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE maintenances ADD COLUMN attachmentPath TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE rates_by_year ADD COLUMN sparkPlugKmInterval INTEGER NOT NULL DEFAULT 40000")
        database.execSQL("ALTER TABLE rates_by_year ADD COLUMN brakePadKmInterval INTEGER NOT NULL DEFAULT 30000")
        database.execSQL("ALTER TABLE rates_by_year ADD COLUMN fuelFilterKmInterval INTEGER NOT NULL DEFAULT 20000")
    }
}


val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE rates_by_year ADD COLUMN dailySalary REAL NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE rates_by_year ADD COLUMN leaveAmount REAL NOT NULL DEFAULT 0")
    }
}

/** Holiday hourly rate; 0 means "not set" (holiday hours fall back to the normal rate). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE rates_by_year ADD COLUMN holidayHour REAL NOT NULL DEFAULT 0")
    }
}

/** Record attachments and editable vehicle/finance details. Existing records keep safe defaults. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE maintenances ADD COLUMN replacementParts TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE maintenances ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE fuels ADD COLUMN km REAL NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE fuels ADD COLUMN station TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE loans ADD COLUMN monthlyInstallment REAL NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE loans ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE personal_expenses ADD COLUMN km REAL NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE personal_expenses ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS record_attachments (
                id TEXT NOT NULL PRIMARY KEY,
                recordType TEXT NOT NULL,
                recordId TEXT NOT NULL,
                filePath TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                addedAt INTEGER NOT NULL
            )
        """)
        database.execSQL("CREATE INDEX IF NOT EXISTS index_record_attachments_recordType_recordId ON record_attachments(recordType, recordId)")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS loan_installments (
                id TEXT NOT NULL PRIMARY KEY,
                loanId TEXT NOT NULL,
                number INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                dueDate TEXT NOT NULL,
                paidDate TEXT NOT NULL,
                notes TEXT NOT NULL
            )
        """)
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_loan_installments_loanId_number ON loan_installments(loanId, number)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_loan_installments_dueDate ON loan_installments(dueDate)")
    }
}
