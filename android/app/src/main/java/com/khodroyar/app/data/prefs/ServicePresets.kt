package com.khodroyar.app.data.prefs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Named service route presets stored as JSON in [AppSettings.servicePresetsJson].
 * Uses Android org.json (no kotlinx.serialization plugin required) so apply/save
 * never hangs or crashes when the serialization compiler plugin is absent.
 */
data class ServicePreset(
    val id: String,
    val name: String,
    val type: String = "",
    val carType: String = "",
    val origin: String = "",
    val destination: String = "",
    val passengers: String = "",
    val km: Double = 0.0,
    val hours: Double = 0.0,
    val startTime: String = "",
    val endTime: String = "",
    val tollCount: Int = 0,
)

object ServicePresets {
    fun parse(raw: String?): List<ServicePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val name = o.optString("name")
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        ServicePreset(
                            id = id,
                            name = name,
                            type = o.optString("type"),
                            carType = o.optString("carType"),
                            origin = o.optString("origin"),
                            destination = o.optString("destination"),
                            passengers = o.optString("passengers"),
                            km = o.optDouble("km", 0.0),
                            hours = o.optDouble("hours", 0.0),
                            startTime = o.optString("startTime"),
                            endTime = o.optString("endTime"),
                            tollCount = o.optInt("tollCount", 0),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(list: List<ServicePreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("carType", p.carType)
                    .put("origin", p.origin)
                    .put("destination", p.destination)
                    .put("passengers", p.passengers)
                    .put("km", p.km)
                    .put("hours", p.hours)
                    .put("startTime", p.startTime)
                    .put("endTime", p.endTime)
                    .put("tollCount", p.tollCount),
            )
        }
        return arr.toString()
    }
}
