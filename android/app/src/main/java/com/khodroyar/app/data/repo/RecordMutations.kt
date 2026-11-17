package com.khodroyar.app.data.repo

import com.khodroyar.app.data.db.AppDatabase
import com.khodroyar.app.data.prefs.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared gate: resolve an interrupted quick mileage write before any later manual/scheduled record writer. */
internal object RecordMutations {
    val mutex = Mutex()
    suspend fun <T> mutate(db: AppDatabase, settings: SettingsStore, invalidateReceipt: Boolean = false, block: suspend () -> T): T = mutex.withLock {
        QuickMileageJournal.recover(db, settings)
        if (invalidateReceipt) QuickEntries.invalidate()
        block()
    }
}
