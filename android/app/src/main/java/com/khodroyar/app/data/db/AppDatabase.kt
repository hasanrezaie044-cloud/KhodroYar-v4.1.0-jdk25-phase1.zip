package com.khodroyar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServiceEntity::class, FuelEntity::class, MaintenanceEntity::class,
        LoanEntity::class, PersonalExpenseEntity::class, PersonalIncomeEntity::class,
        PersonnelEntity::class, HolidayEntity::class, RatesEntity::class,
        SuggestionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun services(): ServiceDao
    abstract fun fuels(): FuelDao
    abstract fun maintenances(): MaintenanceDao
    abstract fun loans(): LoanDao
    abstract fun personalExpenses(): PersonalExpenseDao
    abstract fun personalIncomes(): PersonalIncomeDao
    abstract fun personnel(): PersonnelDao
    abstract fun holidays(): HolidayDao
    abstract fun rates(): RatesDao
    abstract fun suggestions(): SuggestionDao

    companion object {
        private const val NAME = "car_management.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // WAL keeps reads non-blocking while a write is in flight.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
