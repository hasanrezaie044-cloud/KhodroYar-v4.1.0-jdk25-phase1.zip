package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Synthetic wire fixtures, not evidence of live Persian model quality or account availability. */
internal object OpenRouterFixture {
    const val FREE = "synthetic/persian:free"
    val key get() = "sk-or-v1-" + "a".repeat(64)
    fun settings() = AiSettings(mode = AiMode.OPENROUTER, consented = true)
    fun row(id: String = FREE, prompt: Any = "0", completion: Any = "0", tools: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("name", "Synthetic Persian").put("context_length", 131072)
        .put("pricing", JSONObject().put("prompt", prompt).put("completion", completion))
        .put("supported_parameters", JSONArray(if (tools) listOf("tools", "tool_choice", "max_tokens") else listOf("max_tokens")))
    fun catalog(vararg rows: JSONObject): String = JSONObject().put("data", JSONArray(rows.toList())).toString()
    fun catalog(): String = catalog(row(OpenRouterFreePolicy.INITIAL_MODEL), row())
    fun completion(content: String = "سلام!", calls: List<FunctionCall> = emptyList(), model: String = FREE, finish: String = if (calls.isEmpty()) "stop" else "tool_calls"): String =
        JSONObject().put("model", model).put("usage", JSONObject().put("cost", 0))
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", finish).put("message", FunctionMessage("assistant", content, calls).json()))).toString()
    fun call(name: String, args: String = "{}", id: String = "call_1") = FunctionCall(id, name, args)
}
