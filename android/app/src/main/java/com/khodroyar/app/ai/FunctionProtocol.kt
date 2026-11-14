package com.khodroyar.app.ai

/** Provider-neutral native messages/calls. Wire serialization belongs exclusively to adapters. */
data class FunctionCall(val id: String, val name: String, val arguments: String, val providerSignature: String? = null)
data class FunctionMessage(
    val role: String, val content: String = "", val calls: List<FunctionCall> = emptyList(), val toolCallId: String? = null,
    /** Local budgeting identity, never serialized to any provider. */ val turnId: String? = null,
)
sealed interface FunctionResult {
    data class Reply(val message: FunctionMessage, val model: String) : FunctionResult
    data class Failed(val error: AiResult.Failure) : FunctionResult
}

enum class NativeToolProtocolIssue { INVALID_ARGUMENTS, INVALID_IDENTITIES }

/** Replaceable provider boundary; no Android Context, database, or provider-specific tool executor. */
interface FunctionCallingProvider {
    suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit = {}): FunctionResult
    /** Diagnostic only: no record, arguments, prompt or executor crosses back to model management. */
    fun reportToolProtocol(model: String, issue: NativeToolProtocolIssue): Boolean = false
}

object PersianAgentLimits {
    const val USER_CHARS = 3000
    const val HISTORY_CHARS = 4000
    const val MAX_ROUNDS = 5
    const val CALLS_PER_ROUND = 2
    const val MAX_WRITES = 4
    const val ARGUMENT_CHARS = 8192
    const val OUTPUT_CHARS = 6000
    const val OUTPUT_TOKENS = 1536
    const val TOOL_RESULT_CHARS = 1800
}

object FunctionToolSchemas {
    /** Stricter wire boundary than legacy local commands; unknown/internal snapshot fields fail closed. */
    fun validate(call: FunctionCall): ValidationResult {
        if (call.arguments.length > PersianAgentLimits.ARGUMENT_CHARS) return ValidationResult.Invalid("آرگومان ابزار بیش از حد بزرگ است.")
        if (AiTextSafety.containsCredential(call.arguments)) return ValidationResult.Invalid("متن شبیه کلید API در آرگومان ابزار مجاز نیست؛ چیزی اجرا یا ذخیره نمی‌شود.")
        val spec = AgentToolRegistry.find(call.name) ?: return ValidationResult.Invalid("ابزار درخواست‌شده در فهرست مجاز نیست.")
        val args = try { NativeJson.objectValue(call.arguments, PersianAgentLimits.ARGUMENT_CHARS) } catch (_: Exception) { return ValidationResult.Invalid("آرگومان ابزار باید شیء JSON معتبر باشد.") }
        if (args.keys().asSequence().any { key -> spec.params.none { it.name == key } })
            return ValidationResult.Invalid("پارامتر ناشناخته یا داخلی پذیرفته نمی‌شود.")
        for (p in spec.params) {
            if (!args.has(p.name)) continue
            val value = args.opt(p.name)
            val valid = when (p.type) {
                ParamType.NUMBER -> value is Number && value.toDouble().isFinite()
                ParamType.INT -> value is Number && runCatching { java.math.BigDecimal(value.toString()).intValueExact(); true }.getOrDefault(false)
                else -> value is String && value.length <= (if (p.type == ParamType.TEXT) 80 else 100) && value.none { it.isISOControl() }
            }
            if (!valid) return ValidationResult.Invalid("نوع یا طول پارامتر ${p.name} با طرح ابزار تطابق ندارد؛ مقدار حدسی/بریده پذیرفته نمی‌شود.")
        }
        return AgentActionValidator.validate(spec.name, args)
    }
}
