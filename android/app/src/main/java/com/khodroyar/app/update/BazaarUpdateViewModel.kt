package com.khodroyar.app.update

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Checks at entry/resume after authentication; never delays or blocks entry. */
class BazaarUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val service = BazaarUpdateService(app)
    private val prefs = app.getSharedPreferences("bazaar-update", 0)
    private var lastCheck: Long? = null
    private var job: Job? = null
    var available by mutableStateOf<Long?>(null); private set
    var checking by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set

    fun check(manual: Boolean = false) {
        if (job?.isActive == true) return
        val elapsed = SystemClock.elapsedRealtime()
        if (!manual && lastCheck?.let { elapsed - it < 5 * 60_000 } == true) return
        lastCheck = elapsed; checking = true
        if (manual) message = null
        job = viewModelScope.launch {
            try {
                when (val result = service.check(BuildConfig.VERSION_CODE.toLong())) {
                    is BazaarUpdateResult.Available -> {
                        message = "نسخهٔ جدید در بازار موجود است."
                        if (manual || BazaarUpdatePolicy.shouldPrompt(result.versionCode, BuildConfig.VERSION_CODE.toLong(),
                                prefs.getLong("dismissed-version", 0), prefs.getLong("dismissed-at", 0), System.currentTimeMillis()))
                            available = result.versionCode
                    }
                    BazaarUpdateResult.Current -> { available = null; if (manual) message = "نسخهٔ جدیدتری در بازار یافت نشد." }
                    BazaarUpdateResult.NotInstalled -> if (manual) message = "بازار نصب نیست؛ از دکمهٔ زیر صفحهٔ برنامه در بازار را باز کنید."
                    BazaarUpdateResult.Unavailable -> if (manual) message = "بررسی انجام نشد؛ اتصال اینترنت و نسخهٔ بازار را بررسی کنید."
                }
            } finally { checking = false }
        }
    }

    fun later() {
        available?.let { prefs.edit().putLong("dismissed-version", it).putLong("dismissed-at", System.currentTimeMillis()).apply() }
        available = null
    }

    fun openFailed() { message = "بازار یا مرورگر باز نشد؛ بازار را نصب یا به‌روز کنید." }
}
