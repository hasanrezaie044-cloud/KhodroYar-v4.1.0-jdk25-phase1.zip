package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, complete native tool calls only. A truncated/failed stream cannot produce an action. */
object OpenRouterWire {
    fun messageJson(message: FunctionMessage): JSONObject = JSONObject().put("role", message.role).apply {
        put("content", message.content)
        if (message.calls.isNotEmpty()) put("tool_calls", JSONArray().apply {
            message.calls.forEach { call -> put(JSONObject().put("id", call.id).put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))) }
        })
        message.toolCallId?.let { put("tool_call_id", it) }
    }
    fun completion(body: String, selected: String, catalog: List<VerifiedFreeModel>, responseIds: Set<String>? = null): FunctionResult.Reply {
        require(body.length <= 256 * 1024)
        val root = NativeJson.objectValue(body, 256 * 1024)
        checkError(root); checkCost(root)
        val model = root.getString("model")
        checkIdentity(model, selected, catalog, responseIds)
        val choices = root.getJSONArray("choices"); require(choices.length() == 1)
        val choice = choices.getJSONObject(0)
        require(choice.getString("finish_reason") in setOf("stop", "tool_calls"))
        val message = message(choice.getJSONObject("message"))
        require((message.calls.isEmpty() && choice.getString("finish_reason") == "stop") ||
            (message.calls.isNotEmpty() && choice.getString("finish_reason") == "tool_calls"))
        return FunctionResult.Reply(message, model)
    }
    internal fun checkIdentity(actual: String, selected: String, catalog: List<VerifiedFreeModel>, responseIds: Set<String>?) {
        if (responseIds != null) {
            if (actual !in responseIds) throw OpenRouterReplyFailure(AiResult.Failure(AiErrorKind.MODEL_INVALID,
                "شناسهٔ پاسخ با مدل یا canonical رسمیِ همان ورودی فهرست تطابق ندارد؛ هیچ ابزار این پاسخ اجرا نمی‌شود."))
        } else OpenRouterFreePolicy.checkReturnedModel(actual, selected, catalog)
    }
    fun message(json: JSONObject): FunctionMessage {
        require(json.isNull("content") || json.opt("content") is String)
        val content = if (json.isNull("content")) "" else json.optString("content", "")
        require(content.length <= PersianAgentLimits.OUTPUT_CHARS)
        val array = json.optJSONArray("tool_calls")
        require(array == null || array.length() <= PersianAgentLimits.CALLS_PER_ROUND)
        val calls = (0 until (array?.length() ?: 0)).map { i ->
            val call = requireNotNull(array).getJSONObject(i); require(call.getString("type") == "function")
            val f = call.getJSONObject("function")
            FunctionCall(call.getString("id"), f.getString("name"), f.getString("arguments")).also(::validateCall)
        }
        require(calls.map { it.id }.distinct().size == calls.size)
        require(content.isNotBlank() || calls.isNotEmpty())
        return FunctionMessage("assistant", content, calls)
    }
    fun validateCall(call: FunctionCall) {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(call.id))
        require(Regex("[A-Za-z0-9_]{1,64}").matches(call.name))
        require(call.arguments.length <= PersianAgentLimits.ARGUMENT_CHARS)
        NativeJson.objectValue(call.arguments, PersianAgentLimits.ARGUMENT_CHARS) // Prose or an incomplete JSON object is not executable.
    }
    fun checkError(root: JSONObject) {
        if (root.has("error")) throw OpenRouterReplyFailure(OpenRouterFreePolicy.error(root.optJSONObject("error")?.optInt("code", 500) ?: 500))
    }
    fun checkCost(root: JSONObject) {
        val usage = root.optJSONObject("usage") ?: return
        if (usage.has("cost") && !usage.isNull("cost")) require(java.math.BigDecimal(usage.get("cost").toString()).signum() == 0)
    }
}

internal class OpenRouterReplyFailure(val failure: AiResult.Failure) : IllegalStateException("OpenRouter reply failed")

class OpenRouterStream(
    private val selected: String, private val catalog: List<VerifiedFreeModel>, private val onText: (String) -> Unit,
    private val responseIds: Set<String>? = null,
) {
    private val content = StringBuilder()
    private data class Partial(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, Partial>()
    private var model = ""; private var finish = ""; private var done = false
    fun accept(data: String) {
        require(!done)
        if (data == "[DONE]") { done = true; return }
        val root = NativeJson.objectValue(data, 65536); OpenRouterWire.checkError(root); OpenRouterWire.checkCost(root)
        val actual = root.optString("model", "")
        if (actual.isNotBlank()) {
            OpenRouterWire.checkIdentity(actual, selected, catalog, responseIds)
            require(model.isEmpty() || model == actual); model = actual
        }
        val choices = root.optJSONArray("choices") ?: return
        require(choices.length() <= 1)
        if (choices.length() == 0) return // Final usage-only chunk.
        require(finish.isEmpty()) { "Delta after terminal finish" }
        val choice = choices.getJSONObject(0); require(choice.optInt("index", 0) == 0)
        val delta = choice.optJSONObject("delta") ?: JSONObject()
        val text = if (delta.isNull("content")) "" else delta.optString("content", "")
        require(content.length + text.length <= PersianAgentLimits.OUTPUT_CHARS)
        content.append(text)
        // No unverified model's text reaches the visible streaming bubble.
        if (text.isNotEmpty() && model.isNotEmpty()) onText(content.toString())
        val toolCalls = delta.optJSONArray("tool_calls")
        for (i in 0 until (toolCalls?.length() ?: 0)) {
            val item = requireNotNull(toolCalls).getJSONObject(i); val index = item.getInt("index")
            require(index in 0 until PersianAgentLimits.CALLS_PER_ROUND)
            require(!item.has("type") || item.getString("type") == "function")
            val p = calls.getOrPut(index) { Partial() }
            if (item.has("id")) p.id.append(item.getString("id"))
            item.optJSONObject("function")?.let { f ->
                if (f.has("name")) p.name.append(f.getString("name"))
                if (f.has("arguments")) p.args.append(f.getString("arguments"))
            }
            require(p.id.length <= 128 && p.name.length <= 64 && p.args.length <= PersianAgentLimits.ARGUMENT_CHARS)
        }
        if (!choice.isNull("finish_reason") && choice.has("finish_reason")) {
            require(finish.isEmpty()); finish = choice.getString("finish_reason")
        }
    }
    fun result(): FunctionResult.Reply {
        require(done && finish in setOf("stop", "tool_calls") && model.isNotEmpty())
        OpenRouterWire.checkIdentity(model, selected, catalog, responseIds)
        require(calls.keys.toList() == (0 until calls.size).toList())
        val complete = calls.values.map { FunctionCall(it.id.toString(), it.name.toString(), it.args.toString()).also(OpenRouterWire::validateCall) }
        require((complete.isEmpty() && finish == "stop") || (complete.isNotEmpty() && finish == "tool_calls"))
        require(complete.map { it.id }.distinct().size == complete.size)
        require(content.isNotBlank() || complete.isNotEmpty())
        return FunctionResult.Reply(FunctionMessage("assistant", content.toString(), complete), model)
    }
}

/** OpenRouter/OpenAI-compatible schema encoding; never part of the business/tool registry. */
object OpenRouterToolSchemas {
    fun tools(specs: List<ToolSpec> = AgentToolRegistry.tools, compact: Boolean = false): JSONArray = JSONArray().apply {
        specs.forEach { spec ->
            val properties = JSONObject()
            spec.params.forEach { p ->
                val type = when (p.type) { ParamType.NUMBER -> "number"; ParamType.INT -> "integer"; else -> "string" }
                properties.put(p.name, JSONObject().put("type", type).apply {
                    if (!compact) put("description", p.desc)
                    if (p.options.isNotEmpty()) put("enum", JSONArray(p.options))
                    if (p.type in setOf(ParamType.NUMBER, ParamType.INT)) { put("minimum", p.min); put("maximum", p.max) }
                    else put("maxLength", if (p.type == ParamType.TEXT) 80 else 100)
                })
            }
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", spec.name)
                .put("description", "[${spec.kind}] ${if (compact) spec.desc.take(90) else spec.desc}").put("parameters", JSONObject().put("type", "object")
                    .put("properties", properties).put("additionalProperties", false)
                    .put("required", JSONArray(spec.params.filter { it.required }.map { it.name })))))
        }
    }

}
