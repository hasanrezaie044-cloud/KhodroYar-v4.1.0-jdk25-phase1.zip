package com.khodroyar.app.ai.gemma

import com.khodroyar.app.ai.AgentActionValidator
import com.khodroyar.app.ai.AgentApprovalGate
import com.khodroyar.app.ai.AgentStep
import com.khodroyar.app.ai.AgentToolRegistry
import com.khodroyar.app.ai.AgentToolRunner
import com.khodroyar.app.ai.AiErrorKind
import com.khodroyar.app.ai.AiLimits
import com.khodroyar.app.ai.AiResult
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.PendingAction
import com.khodroyar.app.ai.ToolKind
import com.khodroyar.app.ai.ToolOutcome
import com.khodroyar.app.ai.ValidationResult
import org.json.JSONObject

/**
 * The Gemma (on-device) agent loop. Deliberately reuses the SHARED pieces of the online
 * assistant — [AgentToolRegistry] catalogue, [AgentActionValidator], [AgentToolExecutor],
 * [PendingAction], [AgentStep] — so both assistants speak one action protocol and write
 * through one business-logic surface. The only difference is where the model lives.
 *
 * Safety invariants (identical to the online assistant):
 *  - model output can never execute anything; only the app executes, after validation;
 *  - READ tools run immediately; WRITE/DELETE stop as a [AgentStep.Confirm] until the
 *    user approves the app-built summary;
 *  - real outcomes come from the database, never from model prose.
 */
class LocalAgentOrchestrator(
    private val engine: LocalLlmEngine,
    private val executor: AgentToolRunner,
) {

    companion object {
        const val MAX_HISTORY_TURNS = 6
        const val MAX_HISTORY_CHARS = 3_500
    }

    /**
     * One inference per user turn, matching 5.6.3's explicit-action policy. A READ tool
     * returns its verified app result directly; the assistant never starts hidden follow-up
     * generations or additional tool hops. The user may then ask a separate follow-up.
     */
    suspend fun run(history: List<ChatEntry>, imageBytes: ByteArray? = null): AgentStep {
        val userText = history.lastOrNull { it.role == ChatRole.USER }?.text?.trim().orEmpty()
        if (userText.isEmpty()) return AgentStep.Reply("پیام خالی است.")

        // Bounded context: seed the one model request with the recent visible chat only.
        val turns = historyTurns(history.dropLast(1))
        val reply = engine.complete(
            LocalLlmEngine.Request(
                systemPrompt = GemmaPrompts.systemPrompt(),
                history = turns,
                userText = userText,
                imageBytes = imageBytes,
                maxOutputTokens = GemmaPrompts.MAX_TOOL_OUTPUT_TOKENS,
                disableThinking = true,
            ),
        )
        return when (reply) {
            is LocalLlmEngine.Reply.Failure -> AgentStep.Failure(reply.toAiFailure())
            is LocalLlmEngine.Reply.Text -> {
                val action = GemmaJsonParser.parse(reply.text)
                if (!action.isToolCall) {
                    AgentStep.Reply(action.visibleText.ifBlank { "پاسخی دریافت نشد." })
                } else when (val validation = AgentActionValidator.validate(action.tool!!, action.args)) {
                    is ValidationResult.Invalid -> AgentStep.Reply("درخواست ابزار معتبر نبود: ${validation.reason} چیزی اجرا یا تغییر نکرد.")
                    is ValidationResult.Ok -> {
                        val call = validation.call
                        if (call.spec.kind == ToolKind.READ) {
                            val outcome = try {
                                executor.execute(call)
                            } catch (e: com.khodroyar.app.ai.AgentUserException) {
                                return AgentStep.Reply(e.userMessage)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                return AgentStep.Reply("خواندن اطلاعات انجام نشد؛ چیزی تغییر نکرد. لطفاً دوباره درخواست کنید.")
                            }
                            AgentStep.Result(outcome.copy(text = outcome.text.take(AiLimits.MAX_TOOL_RESULT_CHARS)))
                        } else {
                            // Match 5.6.3's shared approval path: resolve app-owned snapshots,
                            // build the preview from validated data, and never execute here.
                            AgentApprovalGate(executor).prepare(call, action.visibleText)
                        }
                    }
                }
            }
        }
    }

    /** Runs an approved action and returns the REAL outcome (never model-written). */
    suspend fun execute(pending: PendingAction): ToolOutcome = executor.execute(pending.call)

    // ----------------------------------------------------------------- helpers

    private fun historyTurns(history: List<ChatEntry>): List<LocalLlmEngine.Turn> {
        val usable = history.filter { it.role != ChatRole.ERROR && it.text.isNotBlank() }
        var recent = usable.takeLast(MAX_HISTORY_TURNS)
        while (recent.size > 1 && recent.sumOf { it.text.length } > MAX_HISTORY_CHARS) {
            recent = recent.drop(1)
        }
        return recent.map { e ->
            when (e.role) {
                ChatRole.USER -> LocalLlmEngine.Turn(LocalLlmEngine.Turn.USER, e.text.take(600))
                ChatRole.ASSISTANT -> LocalLlmEngine.Turn(
                    LocalLlmEngine.Turn.ASSISTANT,
                    JSONObject().put("action", "reply").put("text", e.text.take(800)).toString(),
                )
                ChatRole.RESULT -> LocalLlmEngine.Turn(LocalLlmEngine.Turn.USER, "نتیجهٔ واقعی عملیات از طرف برنامه: ${e.text.take(400)}")
                ChatRole.ERROR -> LocalLlmEngine.Turn(LocalLlmEngine.Turn.USER, "")
            }
        }
    }

    private fun LocalLlmEngine.Reply.Failure.toAiFailure(): AiResult.Failure = when (error) {
        LocalLlmEngine.EngineError.NOT_INSTALLED -> AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "مدل آفلاین هنوز نصب نشده است.")
        LocalLlmEngine.EngineError.OUT_OF_MEMORY -> AiResult.Failure(AiErrorKind.UNKNOWN, "حافظهٔ دستگاه برای اجرای مدل کافی نیست.\n$detail")
        LocalLlmEngine.EngineError.BUSY -> AiResult.Failure(AiErrorKind.UNKNOWN, "دستیار مشغول پردازش قبلی است؛ چند لحظه صبر کنید.")
        LocalLlmEngine.EngineError.CANCELLED -> AiResult.Failure(AiErrorKind.UNKNOWN, "پاسخ متوقف شد.")
        LocalLlmEngine.EngineError.INIT_FAILED -> AiResult.Failure(AiErrorKind.UNKNOWN, "راه‌اندازی مدل ناموفق بود.\n$detail")
        LocalLlmEngine.EngineError.FAILED -> AiResult.Failure(AiErrorKind.UNKNOWN, "خطا در اجرای مدل.\n$detail")
    }
}
