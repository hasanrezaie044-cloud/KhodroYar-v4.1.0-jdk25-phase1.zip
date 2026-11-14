package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Native Gemini REST mapping; only text and local function calls. No SDK automatic execution, grounding, files or code. */
object GeminiWire {
    fun request(query: AIModelRequest): JSONObject {
        val names = query.messages.flatMap { it.calls }.associateBy { it.id }
        val contents = JSONArray()
        query.messages.filter { it.role != "system" }.forEach { m ->
            val parts = JSONArray()
            if (m.role == "tool") {
                val call = requireNotNull(names[m.toolCallId])
                parts.put(JSONObject().put("functionResponse", JSONObject().put("name", call.name).put("id", call.id)
                    .put("response", JSONObject().put("result", m.content))))
            } else {
                if (m.content.isNotEmpty()) parts.put(JSONObject().put("text", m.content))
                m.calls.forEach { c -> parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("id", c.id)
                    .put("args", NativeJson.objectValue(c.arguments, PersianAgentLimits.ARGUMENT_CHARS))).apply {
                        c.providerSignature?.let { put("thoughtSignature", it) }
                    }) }
            }
            require(parts.length() > 0)
            contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user").put("parts", parts))
        }
        val root = JSONObject().put("contents", contents).put("generationConfig", JSONObject().put("maxOutputTokens", query.maxOutputTokens).put("temperature", 0.2))
        val system = query.messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        if (system.isNotBlank()) root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        if (query.tools.isNotEmpty()) {
            val functions = JSONArray()
            OpenRouterToolSchemas.tools(query.tools, compact = true).let { schemas ->
                for (i in 0 until schemas.length()) {
                    val f = schemas.getJSONObject(i).getJSONObject("function")
                    val parameters = f.getJSONObject("parameters")
                    // Gemini's Schema subset is not the whole OpenAI JSON Schema dialect.
                    parameters.remove("additionalProperties")
                    val props = parameters.getJSONObject("properties")
                    props.keys().asSequence().forEach { name -> props.getJSONObject(name).remove("maxLength") }
                    functions.put(f)
                }
            }
            root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
                .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
        }
        return root
    }
    fun completion(body: String, selected: String, aliases: Set<String>): FunctionResult.Reply {
        val stream = GeminiTextStream(selected, aliases, {})
        stream.accept(body)
        return stream.result()
    }
}

class GeminiTextStream(private val selected: String, private val aliases: Set<String>, private val onText: (String) -> Unit) {
    private val text = StringBuilder(); private val calls = mutableListOf<FunctionCall>()
    private val prefix = java.util.UUID.randomUUID().toString().take(8)
    private var identity = ""; private var stopped = false
    fun accept(event: String) {
        if (event == "[DONE]") return
        val root = NativeJson.objectValue(event, 256 * 1024)
        root.optJSONObject("error")?.let { throw OpenRouterReplyFailure(ProviderFailurePolicy.http(it.optInt("code", 500))) }
        val model = root.optString("modelVersion")
        if (model.isNotBlank()) { require(model in aliases && (identity.isEmpty() || identity == model)); identity = model }
        require(root.optJSONObject("promptFeedback")?.has("blockReason") != true)
        val candidates = root.optJSONArray("candidates") ?: return
        require(candidates.length() <= 1)
        if (candidates.length() == 0) return
        require(!stopped)
        val candidate = candidates.getJSONObject(0); require(candidate.optInt("index", 0) == 0)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought", false)) continue
            require(part.keys().asSequence().all { it in setOf("text", "functionCall", "thoughtSignature", "thought") })
            part.opt("text")?.let { require(it is String); text.append(it); require(text.length <= PersianAgentLimits.OUTPUT_CHARS) }
            part.optJSONObject("functionCall")?.let { f ->
                val id = f.optString("id").ifBlank { "gemini_${prefix}_${calls.size}" }
                val signature = part.optString("thoughtSignature").takeIf { it.isNotBlank() }
                require(signature == null || signature.length <= 8192)
                calls += FunctionCall(id, f.getString("name"), f.getJSONObject("args").toString(), signature).also(OpenRouterWire::validateCall)
                require(calls.size <= PersianAgentLimits.CALLS_PER_ROUND && calls.map { it.id }.distinct().size == calls.size)
            }
        }
        if (identity.isNotEmpty() && text.isNotEmpty()) onText(text.toString())
        if (candidate.has("finishReason")) { require(candidate.getString("finishReason") == "STOP"); stopped = true }
    }
    fun result(): FunctionResult.Reply {
        require(stopped && identity.isNotBlank() && (text.isNotBlank() || calls.isNotEmpty()))
        return FunctionResult.Reply(FunctionMessage("assistant", text.toString(), calls), selected)
    }
}
