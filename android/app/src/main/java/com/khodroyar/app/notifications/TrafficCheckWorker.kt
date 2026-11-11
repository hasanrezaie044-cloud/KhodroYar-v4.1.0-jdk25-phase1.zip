package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.traffic.TrafficRepository
import org.json.JSONObject

/**
 * Background check for the three Mobarakeh→Isfahan corridors.
 * Posts a notification only when a corridor worsens into MODERATE or HEAVY
 * compared with the last stored level.
 */
class TrafficCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore.get(applicationContext)
        val settings = store.current
        if (!settings.trafficNotifyEnabled) return Result.success()
        val key = settings.neshanApiKey.trim()
        if (key.isBlank()) return Result.success()

        return try {
            val levels = TrafficRepository().fetch(key).statuses.mapValues { it.value.level.name }

            val previous = parseLevels(settings.trafficLastLevels)
            val worsened = mutableListOf<String>()
            for (c in TrafficRepository.CORRIDORS) {
                val now = levels[c.id] ?: continue
                val was = previous[c.id]
                if (now == "HEAVY" && was != "HEAVY") {
                    worsened += "${c.title}: سنگین"
                } else if (now == "MODERATE" && was != "MODERATE" && was != "HEAVY") {
                    worsened += "${c.title}: نیمه‌سنگین"
                }
            }

            store.update {
                it.copy(trafficLastLevels = levelsToJson(levels))
            }

            if (worsened.isNotEmpty() && Notifications.canPost(applicationContext)) {
                Notifications.post(
                    applicationContext,
                    Notifications.ID_TRAFFIC,
                    Notifications.CHANNEL_TRAFFIC,
                    "هشدار ترافیک",
                    worsened.joinToString("\n"),
                )
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount >= 2) Result.success() else Result.retry()
        }
    }

    private companion object {
        fun parseLevels(json: String): Map<String, String> = runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        fun levelsToJson(levels: Map<String, String>): String = JSONObject(levels).toString()
    }
}
