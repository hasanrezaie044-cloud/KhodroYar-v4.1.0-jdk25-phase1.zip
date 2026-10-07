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
