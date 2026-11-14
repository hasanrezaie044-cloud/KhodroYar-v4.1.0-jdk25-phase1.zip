package com.khodroyar.app.ai

import org.json.JSONObject

/** Bounded, complete native tool calls only. A truncated/failed stream cannot produce an action. */
object OpenRouterWire {
    fun completion(body: String, selected: String, catalog: List<VerifiedFreeModel>): FunctionResult.Reply {
        require(body.length <= 256 * 1024)
        val root = JSONObject(body)
        require(!root.has("error")); checkCost(root)
        val model = root.getString("model")
        OpenRouterFreePolicy.checkReturnedModel(model, selected, catalog)
        val choices = root.getJSONArray("choices"); require(choices.length() == 1)
        val choice = choices.getJSONObject(0)
        require(choice.getString("finish_reason") in setOf("stop", "tool_calls"))
        val message = message(choice.getJSONObject("message"))
        require((message.calls.isEmpty() && choice.getString("finish_reason") == "stop") ||
            (message.calls.isNotEmpty() && choice.getString("finish_reason") == "tool_calls"))
        return FunctionResult.Reply(message, model)
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
        JSONObject(call.arguments) // Prose or an incomplete JSON object is not executable.
    }
    fun checkCost(root: JSONObject) {
        val usage = root.optJSONObject("usage") ?: return
        if (usage.has("cost") && !usage.isNull("cost")) require(java.math.BigDecimal(usage.get("cost").toString()).signum() == 0)
    }
}

class OpenRouterStream(
    private val selected: String, private val catalog: List<VerifiedFreeModel>, private val onText: (String) -> Unit,
) {
    private val content = StringBuilder()
    private data class Partial(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, Partial>()
    private var model = ""; private var finish = ""; private var done = false
    fun accept(data: String) {
        require(!done)
        if (data == "[DONE]") { done = true; return }
        val root = JSONObject(data); require(!root.has("error")); OpenRouterWire.checkCost(root)
        val actual = root.optString("model", "")
        if (actual.isNotBlank()) {
            OpenRouterFreePolicy.checkReturnedModel(actual, selected, catalog)
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
        OpenRouterFreePolicy.checkReturnedModel(model, selected, catalog)
        require(calls.keys.toList() == (0 until calls.size).toList())
        val complete = calls.values.map { FunctionCall(it.id.toString(), it.name.toString(), it.args.toString()).also(OpenRouterWire::validateCall) }
        require((complete.isEmpty() && finish == "stop") || (complete.isNotEmpty() && finish == "tool_calls"))
        require(complete.map { it.id }.distinct().size == complete.size)
        require(content.isNotBlank() || complete.isNotEmpty())
        return FunctionResult.Reply(FunctionMessage("assistant", content.toString(), complete), model)
    }
}
