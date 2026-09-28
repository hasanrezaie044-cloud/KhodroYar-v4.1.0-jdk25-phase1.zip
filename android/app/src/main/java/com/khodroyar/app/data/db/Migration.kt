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
