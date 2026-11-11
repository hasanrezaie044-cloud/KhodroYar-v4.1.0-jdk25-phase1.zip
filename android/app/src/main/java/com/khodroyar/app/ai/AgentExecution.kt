package com.khodroyar.app.ai

/** The only data access surface available to either assistant. */
interface AgentToolRunner {
    suspend fun execute(call: ValidatedCall): ToolOutcome
    fun describe(call: ValidatedCall): String
    suspend fun describeTarget(call: ValidatedCall): String?
    /** Resolve app-owned snapshots/revisions before building the confirmation card. Never writes. */
    suspend fun prepare(call: ValidatedCall): ValidatedCall = call
}

/** Real application data/state, never a model's claim that an operation succeeded. */
data class ToolOutcome(val ok: Boolean, val text: String, val section: AgentSection? = null)

/** An operation waiting for the user's explicit approval. */
class PendingAction(
    val call: ValidatedCall,
    /** Built by the app from validated values, not from model text. */
    val summary: String,
    val target: String?,
    val lead: String,
) {
    val destructive: Boolean get() = call.spec.kind == ToolKind.DELETE
}

sealed interface AgentStep {
    data class Reply(val text: String) : AgentStep
    data class Result(val outcome: ToolOutcome) : AgentStep
    data class Confirm(val pending: PendingAction) : AgentStep
    data class Failure(val error: AiResult.Failure) : AgentStep
}

/** Only application-authored messages may cross this boundary; never raw provider exceptions. */
class AgentUserException(val userMessage: String) : IllegalStateException(userMessage)

/** Shared fail-closed approval gate. Calling this NEVER executes a write or deletion. */
class AgentApprovalGate(private val tools: AgentToolRunner) {
    suspend fun prepare(requested: ValidatedCall, lead: String = ""): AgentStep {
        require(requested.spec.kind != ToolKind.READ)
        val call = try { tools.prepare(requested) } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AgentUserException) {
            return AgentStep.Reply(e.userMessage)
        } catch (_: Exception) {
            return AgentStep.Reply("اطلاعات این عملیات کامل یا به‌روز نیست. برای سرویس، ابتدا الگو را در تب ثبت سرویس ذخیره کنید؛ برای قانون موجود، کارهای خودکار را دوباره باز کنید. چیزی تغییر نکرد.")
        }
        val target = if (call.spec.kind == ToolKind.DELETE) tools.describeTarget(call) else null
        if (call.spec.kind == ToolKind.DELETE && target == null) {
            return AgentStep.Reply("رکورد پیدا نشد یا شناسه یکتا نیست. چیزی حذف نشد؛ ابتدا فهرست سوابق را بررسی کنید.")
        }
        return AgentStep.Confirm(PendingAction(call, tools.describe(call), target, lead))
    }
}
