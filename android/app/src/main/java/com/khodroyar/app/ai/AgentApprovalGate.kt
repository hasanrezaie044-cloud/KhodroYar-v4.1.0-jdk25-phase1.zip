package com.khodroyar.app.ai

/** Shared read/write boundary for the restored local assistant. No model or network access. */
interface AgentToolRunner {
    suspend fun execute(call: ValidatedCall): ToolOutcome
    fun describe(call: ValidatedCall): String
    suspend fun describeTarget(call: ValidatedCall): String?
}

class AgentApprovalGate(private val tools: AgentToolRunner) {
    /** Preparing a confirmation cannot execute a write. */
    suspend fun prepare(call: ValidatedCall): AgentStep {
        require(call.spec.kind != ToolKind.READ)
        val target = if (call.spec.kind == ToolKind.DELETE) tools.describeTarget(call) else null
        if (call.spec.kind == ToolKind.DELETE && target == null) {
            return AgentStep.Reply("رکورد پیدا نشد یا شناسه یکتا نیست. چیزی حذف نشد.")
        }
        return AgentStep.Confirm(PendingAction(call, tools.describe(call), target, ""))
    }
}
