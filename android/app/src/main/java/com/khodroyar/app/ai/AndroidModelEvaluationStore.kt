package com.khodroyar.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Private, bounded local receipts; never stores prompts/replies or any user records/API key. */
class AndroidModelEvaluationStore(context: Context) : AIModelEvaluationStore {
    private val prefs = context.applicationContext.getSharedPreferences("khodroyar_ai_models", Context.MODE_PRIVATE)
    private val settings = AiSettingsStore(context)
    override fun load(scope: String): List<AIModelEvaluation> = synchronized(lock) {
        if (prefs.getString("scope", "") != scope) return@synchronized emptyList()
        runCatching {
            val text = prefs.getString("receipts", "{\"data\":[]}").orEmpty()
            val data = NativeJson.objectValue(text, 65536).getJSONArray("data"); require(data.length() <= 32)
            (0 until data.length()).map { index ->
                val row = data.getJSONObject(index); val checks = row.getJSONArray("cases"); require(checks.length() <= 5)
                AIModelEvaluation(AIModelKey(row.getString("provider"), row.getString("id")), row.getString("fingerprint"), row.getLong("at"),
                    (0 until checks.length()).map { i -> checks.getJSONObject(i).let { AIModelTestCase(it.getString("name").take(100), it.getBoolean("passed")) } },
                    row.getInt("score"), row.getInt("suite"), row.optString("explanation").take(400),
                    errorKind = runCatching { AiErrorKind.valueOf(row.optString("error")) }.getOrNull())
            }
        }.getOrDefault(emptyList())
    }
    override fun save(scope: String, result: AIModelEvaluation) = synchronized(lock) {
        if (settings.load().modelAccountScope != scope) return@synchronized
        val current = (load(scope).filterNot { it.key == result.key } + result).sortedByDescending { it.checkedAt }.take(32)
        val rows = JSONArray()
        current.forEach { e -> rows.put(JSONObject().put("provider", e.key.provider).put("id", e.key.id).put("fingerprint", e.fingerprint)
            .put("error", e.errorKind?.name ?: "").put("at", e.checkedAt).put("score", e.score).put("suite", e.suiteVersion).put("explanation", AiTextSafety.redact(e.explanation).take(400))
            .put("cases", JSONArray().apply { e.cases.forEach { c -> put(JSONObject().put("name", c.name.take(100)).put("passed", c.passed)) } })) }
        check(prefs.edit().putString("scope", scope).putString("receipts", JSONObject().put("data", rows).toString()).commit())
    }
    override fun active(scope: String): AIModelKey? = synchronized(lock) {
        if (prefs.getString("active_scope", "") != scope) return@synchronized null
        val provider = prefs.getString("active_provider", "").orEmpty(); val id = prefs.getString("active_id", "").orEmpty()
        if (provider.isBlank() || id.isBlank()) null else AIModelKey(provider, id)
    }
    override fun setActive(scope: String, key: AIModelKey?) = synchronized(lock) {
        if (settings.load().modelAccountScope != scope) return@synchronized
        check(prefs.edit().putString("active_scope", scope).putString("active_provider", key?.provider.orEmpty()).putString("active_id", key?.id.orEmpty()).commit())
    }
    override fun selected(scope: String): AIModelKey? = synchronized(lock) {
        if (prefs.getString("selected_scope", "") != scope) return@synchronized null
        val provider = prefs.getString("selected_provider", "").orEmpty(); val id = prefs.getString("selected_id", "").orEmpty()
        if (provider.isBlank() || id.isBlank()) null else AIModelKey(provider, id)
    }
    override fun setSelected(scope: String, key: AIModelKey) = synchronized(lock) {
        if (settings.load().modelAccountScope == scope) check(prefs.edit().putString("selected_scope", scope)
            .putString("selected_provider", key.provider).putString("selected_id", key.id).commit())
    }
    override fun reserve(scope: String, now: Long, automatic: Boolean): Boolean = synchronized(lock) {
        if (automatic || now <= 0 || settings.load().modelAccountScope != scope) return@synchronized false
        val day = now / 86_400_000L
        if (prefs.getString("budget_scope", "") == scope && day < prefs.getLong("budget_day", -1)) return@synchronized false
        val same = prefs.getString("budget_scope", "") == scope && prefs.getLong("budget_day", -1) == day
        val total = if (same) prefs.getInt("total", 0) else 0
        val auto = if (same) prefs.getInt("auto", 0) else 0
        if (total >= 16 || automatic && auto >= 8) return@synchronized false
        prefs.edit().putString("budget_scope", scope).putLong("budget_day", day).putInt("total", total + 1)
            .putInt("auto", auto + if (automatic) 1 else 0).commit()
    }
    companion object { private val lock = Any() }
}
