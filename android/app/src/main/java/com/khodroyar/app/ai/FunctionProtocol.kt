package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Native Chat Completions protocol. Plain assistant prose is NEVER parsed as an operation. */
data class FunctionCall(val id: String, val name: String, val arguments: String)
data class FunctionMessage(
    val role: String, val content: String = "", val calls: List<FunctionCall> = emptyList(), val toolCallId: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("role", role).apply {
        put("content", content)
        if (calls.isNotEmpty()) put("tool_calls", JSONArray().apply {
            calls.forEach { c -> put(JSONObject().put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))) }
        })
        toolCallId?.let { put("tool_call_id", it) }
    }
}
sealed interface FunctionResult {
    data class Reply(val message: FunctionMessage, val model: String) : FunctionResult
    data class Failed(val error: AiResult.Failure) : FunctionResult
}

/** Replaceable provider boundary; no Android Context, database, or provider-specific tool executor. */
interface FunctionCallingProvider {
    suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit = {}): FunctionResult
}

object PersianAgentLimits {
    const val USER_CHARS = 6000
    const val HISTORY_CHARS = 12000
    const val MAX_ROUNDS = 8
    const val CALLS_PER_ROUND = 4
    const val MAX_WRITES = 8
    const val ARGUMENT_CHARS = 8192
    const val OUTPUT_CHARS = 16000
    const val OUTPUT_TOKENS = 4096
    const val TOOL_RESULT_CHARS = 3500
}

object FunctionToolSchemas {
    fun tools(specs: List<ToolSpec> = AgentToolRegistry.tools): JSONArray = JSONArray().apply {
        specs.forEach { spec ->
            val properties = JSONObject()
            spec.params.forEach { p ->
                val type = when (p.type) { ParamType.NUMBER -> "number"; ParamType.INT -> "integer"; else -> "string" }
                properties.put(p.name, JSONObject().put("type", type).put("description", p.desc).apply {
                    if (p.options.isNotEmpty()) put("enum", JSONArray(p.options))
                    if (p.type in setOf(ParamType.NUMBER, ParamType.INT)) { put("minimum", p.min); put("maximum", p.max) }
                    else put("maxLength", if (p.type == ParamType.TEXT) 80 else 100)
                })
            }
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", spec.name)
                .put("description", "[${spec.kind}] ${spec.desc}").put("parameters", JSONObject().put("type", "object")
                    .put("properties", properties).put("additionalProperties", false)
                    .put("required", JSONArray(spec.params.filter { it.required }.map { it.name })))))
        }
    }

    /** Stricter wire boundary than legacy local commands; unknown/internal snapshot fields fail closed. */
    fun validate(call: FunctionCall): ValidationResult {
        if (call.arguments.length > PersianAgentLimits.ARGUMENT_CHARS) return ValidationResult.Invalid("آرگومان ابزار بیش از حد بزرگ است.")
        if (OpenRouterFreePolicy.containsCredential(call.arguments)) return ValidationResult.Invalid("متن شبیه کلید API در آرگومان ابزار مجاز نیست؛ چیزی اجرا یا ذخیره نمی‌شود.")
        val spec = AgentToolRegistry.find(call.name) ?: return ValidationResult.Invalid("ابزار درخواست‌شده در فهرست مجاز نیست.")
        val args = try { NativeJson.objectValue(call.arguments, PersianAgentLimits.ARGUMENT_CHARS) } catch (_: Exception) { return ValidationResult.Invalid("آرگومان ابزار باید شیء JSON معتبر باشد.") }
        if (args.keys().asSequence().any { key -> spec.params.none { it.name == key } })
            return ValidationResult.Invalid("پارامتر ناشناخته یا داخلی پذیرفته نمی‌شود.")
        for (p in spec.params) {
            if (!args.has(p.name)) continue
            val value = args.opt(p.name)
            val valid = when (p.type) {
                ParamType.NUMBER -> value is Number && value.toDouble().isFinite()
                ParamType.INT -> value is Number && value.toDouble().isFinite() && value.toDouble() == Math.rint(value.toDouble()) &&
                    value.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
                else -> value is String && value.length <= (if (p.type == ParamType.TEXT) 80 else 100) && value.none { it.isISOControl() }
            }
            if (!valid) return ValidationResult.Invalid("نوع یا طول پارامتر ${p.name} با طرح ابزار تطابق ندارد؛ مقدار حدسی/بریده پذیرفته نمی‌شود.")
        }
        return AgentActionValidator.validate(spec.name, args)
    }
}
