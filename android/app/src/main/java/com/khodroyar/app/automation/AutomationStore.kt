package com.khodroyar.app.automation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Small, device-local rules, excluded from transfer/backup. All writers AND workers share the gate. */
class AutomationStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gate = Mutex()
    var storageError: Boolean = false
        private set
    private val _rules = MutableStateFlow(load())
    val rules = _rules.asStateFlow()

    private fun load(): List<AutomationRule> = runCatching {
        AutomationCodec.decode(prefs.getString("rules", null) ?: AutomationCodec.encode(emptyList()))
    }.getOrElse { storageError = true; emptyList() }

    /** Block must return the new complete snapshot. Holding the lock through DB readback serializes pause/restore with runs. */
    suspend fun change(block: suspend (List<AutomationRule>) -> List<AutomationRule>): List<AutomationRule> = withContext(Dispatchers.IO) {
        gate.withLock {
            check(!storageError) { "Automation storage needs attention" }
            val next = block(_rules.value)
            check(prefs.edit().putString("rules", AutomationCodec.encode(next)).commit()) { "Automation settings could not be saved" }
            _rules.value = next
            next
        }
    }

    /** Also used by the legacy auto-service worker: the two schedulers cannot race. */
    suspend fun <T> inspectLocked(block: suspend (List<AutomationRule>) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock {
            check(!storageError)
            block(_rules.value)
        }
    }

    suspend fun pauseAll(): List<AutomationRule> = if (storageError) emptyList() else change { rows -> rows.map { it.copy(enabled = false, revision = it.revision + 1, lastMessage = "متوقف پس از بازیابی پشتیبان؛ فعال‌سازی دوباره نیاز به تأیید دارد") } }

    companion object {
        const val PREFS = "khodroyar_agent_automations"
        @Volatile private var instance: AutomationStore? = null
        fun get(context: Context): AutomationStore = instance ?: synchronized(this) {
            instance ?: AutomationStore(context.applicationContext).also { instance = it }
        }
    }
}
