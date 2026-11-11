package com.khodroyar.app.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khodroyar.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One foreground check, then no polling. Failure does not block normal app use. */
class BazaarUpdatesViewModel @JvmOverloads constructor(
    app: Application,
    private val source: BazaarVersionSource = BazaarVersionService(app),
    private val clock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(app) {
    data class State(val checking: Boolean = false, val availableCode: Long? = null,
                     val showPrompt: Boolean = false, val outcome: Outcome = Outcome.NOT_CHECKED)
    enum class Outcome { NOT_CHECKED, AVAILABLE, NONE_REPORTED, BAZAAR_MISSING, UNAVAILABLE }
    private val prefs = app.getSharedPreferences("bazaar_update_notice", 0)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var lastSessionCheck = 0L

    fun onForeground() = check(manual = false)

    fun onBackground() {
        if (job?.isActive == true) {
            job?.cancel()
            lastSessionCheck = 0
            mutable.value = mutable.value.copy(checking = false)
        }
    }

    fun check(manual: Boolean = true) {
        val now = clock()
        if (job?.isActive == true) return
        if (!manual && lastSessionCheck > 0 && now >= lastSessionCheck &&
            now - lastSessionCheck < BazaarUpdatePolicy.FOREGROUND_RECHECK_MS) return
        lastSessionCheck = now
        mutable.value = State(checking = true)
        job = viewModelScope.launch {
            try {
                mutable.value = when (val result = source.check()) {
                    is BazaarVersionResult.Reported -> if (BazaarUpdatePolicy.isNewer(BuildConfig.VERSION_CODE.toLong(), result.code)) {
                        State(availableCode = result.code, outcome = Outcome.AVAILABLE,
                            showPrompt = BazaarUpdatePolicy.shouldPrompt(BuildConfig.VERSION_CODE.toLong(), result.code,
                                prefs.getLong("dismissed_version", 0), prefs.getLong("dismissed_at", 0), clock(), manual))
                    } else State(outcome = Outcome.NONE_REPORTED)
                    is BazaarVersionResult.Unavailable -> State(outcome =
                        if (result.reason == BazaarVersionResult.Reason.NOT_INSTALLED) Outcome.BAZAAR_MISSING else Outcome.UNAVAILABLE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value = State(outcome = Outcome.UNAVAILABLE)
            }
        }
    }

    fun dismiss() {
        mutable.value.availableCode?.let { code ->
            prefs.edit().putLong("dismissed_version", code).putLong("dismissed_at", clock()).apply()
        }
        mutable.value = mutable.value.copy(showPrompt = false)
    }
}
