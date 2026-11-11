package com.khodroyar.app.ai

import com.khodroyar.app.ai.albert.PersianIntentModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Optional on-device ALBERT hint. Only explicitly requested traffic tools may use their own network source. */
class LocalAssistant(
    private val tools: AgentToolRunner,
    parser: LocalIntentParser = LocalIntentParser(),
    val dialogue: LocalDialogueEngine = LocalDialogueEngine(legacy = parser),
    private val model: PersianIntentModel? = null,
) {
    private val approval = AgentApprovalGate(tools)

    suspend fun run(text: String): AgentStep {
        val hint = if (dialogue.acceptsModelHint) try {
            model?.predict(text)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } else null
        // An awaited model result cannot resume an obsolete/cancelled user request.
        coroutineContext.ensureActive()
        return when (val intent = dialogue.parse(text, hint)) {
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
}
