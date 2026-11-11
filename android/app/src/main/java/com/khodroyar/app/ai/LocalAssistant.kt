package com.khodroyar.app.ai

/** No AIProvider reference. Only an explicitly requested data tool (traffic) may use its own network source. */
class LocalAssistant(
    private val tools: AgentToolRunner,
    parser: LocalIntentParser = LocalIntentParser(),
    val dialogue: LocalDialogueEngine = LocalDialogueEngine(legacy = parser),
) {
    private val approval = AgentApprovalGate(tools)

    suspend fun run(text: String): AgentStep = when (val intent = dialogue.parse(text)) {
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
