package com.khodroyar.app.ai

/** No AIProvider reference: local commands cannot accidentally fall through to the internet. */
class LocalAssistant(
    private val tools: AgentToolRunner,
    private val parser: LocalIntentParser = LocalIntentParser(),
) {
    private val approval = AgentApprovalGate(tools)

    suspend fun run(text: String): AgentStep = when (val intent = parser.parse(text)) {
        is LocalIntent.Reply -> AgentStep.Reply(intent.text)
        is LocalIntent.Tool -> when (val validated = AgentActionValidator.validate(intent.name, intent.args)) {
            is ValidationResult.Invalid -> AgentStep.Reply("درخواست انجام نشد: ${validated.reason}\nلطفاً اطلاعات را دقیق‌تر بنویسید.")
            is ValidationResult.Ok -> {
                val call = validated.call
                if (call.spec.kind == ToolKind.READ) AgentStep.Result(tools.execute(call))
                else approval.prepare(call)
            }
        }
    }
}
