package com.khodroyar.app.automation

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, bounded storage. Unknown versions/corrupt data fail closed, never become live tasks. */
object AutomationCodec {
    const val MAX_RULES = 32
    fun encode(rules: List<AutomationRule>): String {
        require(rules.size <= MAX_RULES && rules.all { it.valid() } && rules.map { it.id }.distinct().size == rules.size)
        return JSONObject().put("version", 1).put("rules", JSONArray().also { a -> rules.forEach { a.put(toJson(it)) } }).toString()
    }

    fun decode(text: String): List<AutomationRule> {
        require(text.length <= 256_000)
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val a = root.getJSONArray("rules")
        require(a.length() <= MAX_RULES)
        val rules = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        require(rules.all { it.valid() } && rules.map { it.id }.distinct().size == rules.size)
        return rules
    }

    fun templateJson(s: ServiceTemplate): JSONObject = JSONObject().put("type", s.type).put("car_type", s.carType)
        .put("origin", s.origin).put("destination", s.destination).put("km", s.km).put("hours", s.hours)
        .put("start", s.startTime).put("end", s.endTime).put("tolls", s.tollCount).put("passengers", s.passengers)

    fun templateFromJson(o: JSONObject): ServiceTemplate = ServiceTemplate(o.getString("type"), o.getString("car_type"),
        o.getString("origin"), o.getString("destination"), o.getDouble("km"), o.getDouble("hours"),
        o.getString("start"), o.getString("end"), o.getInt("tolls"), o.optString("passengers"))

    private fun toJson(r: AutomationRule): JSONObject = JSONObject().put("id", r.id).put("kind", r.kind.name)
        .put("title", r.title).put("amount", r.amount).put("category", r.category).put("enabled", r.enabled)
        .put("last_date", r.lastDate).put("last_message", r.lastMessage.take(200)).put("failures", r.failures).put("revision", r.revision)
        .put("schedule", JSONObject().put("frequency", r.schedule.frequency.name).put("start", r.schedule.startDate)
            .put("end", r.schedule.endDate).put("time", r.schedule.time).put("zone", r.schedule.zoneId)
            .put("weekdays", JSONArray(r.schedule.weekdays.sorted())))
        .also { o -> r.service?.let { o.put("service", templateJson(it)) } }

    private fun fromJson(o: JSONObject): AutomationRule {
        val s = o.getJSONObject("schedule")
        val days = s.getJSONArray("weekdays")
        return AutomationRule(o.getString("id"), RuleKind.valueOf(o.getString("kind")), o.getString("title"),
            RepeatSchedule(RepeatFrequency.valueOf(s.getString("frequency")), s.getString("start"), s.getString("time"),
                s.getString("zone"), s.getString("end"), (0 until days.length()).map { days.getInt(it) }.toSet()),
            o.getDouble("amount"), o.getString("category"), o.optJSONObject("service")?.let(::templateFromJson),
            o.getBoolean("enabled"), o.getString("last_date"), o.getString("last_message"), o.getInt("failures"), o.getInt("revision"))
    }
}
