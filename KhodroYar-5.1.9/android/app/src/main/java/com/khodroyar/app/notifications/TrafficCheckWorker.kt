package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.khodroyar.app.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
            val levels = coroutineScope {
                CORRIDORS.associate { c ->
                    c.id to async {
                        fetchLevel(key, c.originLat, c.originLng, c.destLat, c.destLng, c.referenceSec)
                    }
                }.mapValues { it.value.await() }
            }

            val previous = parseLevels(settings.trafficLastLevels)
            val worsened = mutableListOf<String>()
            for (c in CORRIDORS) {
                val now = levels[c.id] ?: continue
                val was = previous[c.id]
                if (now == "HEAVY" && was != "HEAVY") {
                    worsened += "${c.shortTitle}: سنگین"
                } else if (now == "MODERATE" && was != "MODERATE" && was != "HEAVY") {
                    worsened += "${c.shortTitle}: نیمه‌سنگین"
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
        } catch (_: Exception) {
            if (runAttemptCount >= 2) Result.success() else Result.retry()
        }
    }

    private data class Corridor(
        val id: String,
        val shortTitle: String,
        val originLat: Double,
        val originLng: Double,
        val destLat: Double,
        val destLng: Double,
        val referenceSec: Double,
    )

    private companion object {
        val CORRIDORS = listOf(
            Corridor("shiraz", "اتوبان اصفهان–شیراز", 32.3100, 51.5600, 32.6200, 51.6600, 45.0 * 60),
            Corridor("zobahan", "اتوبان ذوب‌آهن", 32.3461, 51.5211, 32.6539, 51.6660, 50.0 * 60),
            Corridor("kharrazi", "اتوبان شهید خرازی", 32.6150, 51.5750, 32.6550, 51.7450, 25.0 * 60),
        )

        fun parseLevels(json: String): Map<String, String> {
            if (json.isBlank()) return emptyMap()
            return try {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.getString(it) }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        fun levelsToJson(levels: Map<String, String>): String {
            val o = JSONObject()
            levels.forEach { (k, v) -> o.put(k, v) }
            return o.toString()
        }

        suspend fun fetchLevel(
            apiKey: String,
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            referenceSec: Double,
        ): String = withContext(Dispatchers.IO) {
            val url = URL(
                "https://api.neshan.org/v4/direction?type=car" +
                    "&origin=$originLat,$originLng&destination=$destLat,$destLng"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Api-Key", apiKey)
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            try {
                if (conn.responseCode != 200) throw Exception("http ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val routes = json.optJSONArray("routes") ?: throw Exception("no routes")
                if (routes.length() == 0) throw Exception("empty")
                val legs = routes.getJSONObject(0).optJSONArray("legs") ?: throw Exception("no legs")
                var durationSec = 0.0
                for (i in 0 until legs.length()) {
                    durationSec += legs.getJSONObject(i).optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
                }
                if (durationSec <= 0) throw Exception("bad duration")
                val ratio = durationSec / referenceSec
                when {
                    ratio < 1.25 -> "SMOOTH"
                    ratio < 1.7 -> "MODERATE"
                    else -> "HEAVY"
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
