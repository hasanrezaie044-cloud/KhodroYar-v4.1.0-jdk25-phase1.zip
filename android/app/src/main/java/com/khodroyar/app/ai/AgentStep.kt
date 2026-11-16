package com.khodroyar.app.ai

/** An operation the model asked for that is waiting for the user's explicit approval. */
class PendingAction(
    val call: ValidatedCall,
    /** Built by the app from validated values. This, not model text, is what the user approves. */
    val summary: String,
    /** For deletions: the real record that would be removed. */
    val target: String?,
    val lead: String,
) {
    val destructive: Boolean get() = call.spec.kind == ToolKind.DELETE
}

/** Common result type of BOTH agent loops: the online one and on-device Gemma one. */
sealed interface AgentStep {
    data class Reply(val text: String) : AgentStep
    data class Confirm(val pending: PendingAction) : AgentStep
    data class Failure(val error: AiResult.Failure) : AgentStep
}
