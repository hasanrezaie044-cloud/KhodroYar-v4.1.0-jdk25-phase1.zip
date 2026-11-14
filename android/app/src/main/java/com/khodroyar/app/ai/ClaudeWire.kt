package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Native Anthropic Messages, including images and application-owned local tools only. */
object ClaudeWire {
    fun request(model: String, query: AIModelRequest, providerOutput: Int): JSONObject {
        val calls = query.messages.flatMap { it.calls }.associateBy { it.id }
        val messages = JSONArray()
        query.messages.filter { it.role != "system" }.forEach { m ->
            val content = JSONArray()
            if (m.role == "tool") {
                require(calls.containsKey(m.toolCallId))
                content.put(JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId).put("content", m.content))
            } else {
                m.images.forEach { image -> content.put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", image.mimeType).put("data", image.base64))) }
                if (m.content.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", m.content))
                m.calls.forEach { c -> content.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name)
                    .put("input", NativeJson.objectValue(c.arguments, PersianAgentLimits.ARGUMENT_CHARS))) }
            }
            require(content.length() > 0)
            messages.put(JSONObject().put("role", if (m.role == "assistant") "assistant" else "user").put("content", content))
        }
        val root = JSONObject().put("model", model).put("messages", messages).put("max_tokens", providerOutput).put("stream", query.stream)
        val system = query.messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        if (system.isNotBlank()) root.put("system", system)
        if (query.tools.isNotEmpty()) {
            val schemas = OpenRouterToolSchemas.tools(query.tools, compact = true)
            root.put("tools", JSONArray().apply { for (i in 0 until schemas.length()) {
                val f = schemas.getJSONObject(i).getJSONObject("function")
                put(JSONObject().put("name", f.getString("name")).put("description", f.optString("description"))
                    .put("input_schema", f.getJSONObject("parameters")))
            } })
        }
        return root
    }
    fun completion(body: String, selected: String): FunctionResult.Reply {
        val root = NativeJson.objectValue(body, 16 * 1024 * 1024)
        require(root.getString("model") == selected)
        val text = StringBuilder(); val calls = mutableListOf<FunctionCall>()
        val content = root.getJSONArray("content")
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            when (part.getString("type")) {
                "text" -> text.append(part.getString("text"))
                "tool_use" -> calls += FunctionCall(part.getString("id"), part.getString("name"), part.getJSONObject("input").toString()).also(OpenRouterWire::validateCall)
                "thinking", "redacted_thinking" -> Unit
                else -> error("Unknown content block")
            }
        }
        require(root.getString("stop_reason") in setOf("end_turn", "tool_use", "stop_sequence"))
        require(calls.map { it.id }.distinct().size == calls.size)
        require(text.isNotBlank() || calls.isNotEmpty())
        return FunctionResult.Reply(FunctionMessage("assistant", text.toString(), calls), selected)
    }
}

class ClaudeStream(private val selected: String, private val onText: (String) -> Unit) {
    private data class Block(val type: String, val id: String = "", val name: String = "", val initial: String = "{}", val data: StringBuilder = StringBuilder(), var closed: Boolean = false)
    private val blocks = sortedMapOf<Int, Block>(); private val text = StringBuilder()
    private var identity = ""; private var finish = ""; private var done = false
    fun accept(event: String) {
        val root = NativeJson.objectValue(event, 1024 * 1024)
        if (root.optString("type") == "ping") return
        require(!done)
        when (root.getString("type")) {
            "message_start" -> { require(identity.isBlank()); identity = root.getJSONObject("message").getString("model"); require(identity == selected) }
            "content_block_start" -> {
                val i = root.getInt("index"); require(i >= 0 && !blocks.containsKey(i))
                val b = root.getJSONObject("content_block"); val type = b.getString("type")
                require(type in setOf("text", "tool_use", "thinking", "redacted_thinking"))
                blocks[i] = Block(type, b.optString("id"), b.optString("name"), b.optJSONObject("input")?.toString() ?: "{}")
                if (type == "text") { text.append(b.optString("text")); onText(text.toString()) }
            }
            "content_block_delta" -> {
                val b = requireNotNull(blocks[root.getInt("index")]); require(!b.closed)
                val delta = root.getJSONObject("delta")
                when (delta.getString("type")) {
                    "text_delta" -> { require(b.type == "text"); text.append(delta.getString("text")); onText(text.toString()) }
                    "input_json_delta" -> { require(b.type == "tool_use"); b.data.append(delta.getString("partial_json")); require(b.data.length <= PersianAgentLimits.ARGUMENT_CHARS) }
                    "thinking_delta", "signature_delta" -> Unit
                    else -> error("Unknown delta")
                }
            }
            "content_block_stop" -> { val b = requireNotNull(blocks[root.getInt("index")]); require(!b.closed); b.closed = true }
            "message_delta" -> { finish = root.getJSONObject("delta").getString("stop_reason"); require(finish in setOf("end_turn", "tool_use", "stop_sequence")) }
            "message_stop" -> { require(finish.isNotBlank() && blocks.values.all { it.closed }); done = true }
            "error" -> throw OpenRouterReplyFailure(ProviderFailurePolicy.fromError(root))
            else -> error("Unknown stream event")
        }
    }
    fun result(): FunctionResult.Reply {
        require(done && identity == selected)
        val calls = blocks.values.filter { it.type == "tool_use" }.map { b -> FunctionCall(b.id, b.name, b.data.toString().ifBlank { b.initial }).also(OpenRouterWire::validateCall) }
        require(calls.map { it.id }.distinct().size == calls.size)
        require(text.isNotBlank() || calls.isNotEmpty())
        return FunctionResult.Reply(FunctionMessage("assistant", text.toString(), calls), selected)
    }
}
