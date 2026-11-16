package com.khodroyar.app.ai.gemma

import com.khodroyar.app.ai.AgentActionValidator
import org.json.JSONObject

/**
 * Turns raw on-device model output into the SAME action protocol the online assistant
 * uses (`{"action":"reply"|"tool", …}`). Small 2B-class models are far less reliable at
 * emitting clean JSON than cloud models, so this parser is deliberately tolerant:
 *
 *  - finds the first '{' … last '}' window anywhere in the output (ignores prose around it);
 *  - strips ```json fences and common role-token leftovers (`<start_of_turn>` …);
 *  - normalises Persian/Arabic digits inside the whole payload before parsing, and again
 *    per raw string value at validation time ([AgentActionValidator.normalizeDigits]);
 *  - accepts `say`/`text`/`response` as the visible sentence and `tool`/`name` as the tool key.
 *
 * Everything the model writes is untrusted until [AgentActionValidator] has validated the
 * tool name and every argument; plain text can only ever be displayed.
 */
object GemmaJsonParser {

    class ModelAction(val tool: String?, val args: JSONObject?, val visibleText: String) {
        val isToolCall: Boolean get() = tool != null
    }

    fun parse(raw: String): ModelAction {
        val cleaned = strip(raw)
        val window = jsonWindow(cleaned)
        if (window != null) {
            // IMPORTANT: never digit-normalise the whole JSON window up front — it would
            // convert Persian digits inside user-visible text (…"۱۲۳"…) too. Try the raw
            // payload first; only if strict JSON parsing fails (e.g. unquoted Persian
            // digits as number literals) retry with a normalised window. Argument values
            // are digit-normalised individually downstream by AgentActionValidator anyway.
            val obj = try {
                JSONObject(window)
            } catch (_: Exception) {
                try {
                    JSONObject(AgentActionValidator.normalizeDigits(window))
                } catch (_: Exception) {
                    null
                }
            }
            if (obj != null) {
                val action = obj.optString("action", "").trim().lowercase()
                val toolName = obj.optString("tool", obj.optString("name", "")).trim()
                if (action == "tool" || (obj.has("tool") && toolName.isNotBlank())) {
                    if (toolName.isNotBlank()) {
                        return ModelAction(
                            toolName,
                            obj.optJSONObject("args") ?: obj.optJSONObject("arguments"),
                            obj.optString("say", obj.optString("text", "")).trim(),
                        )
                    }
                }
                val text = firstNonBlank(obj, "text", "say", "response", "answer")
                if (action == "reply" || text.isNotBlank()) return ModelAction(null, null, text)
            }
        }
        // Not parseable JSON → the whole answer is plain text and can only be SHOWN.
        return ModelAction(null, null, cleaned)
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> o.optString(k, "").takeIf { it.isNotBlank() } } ?: ""

    private fun jsonWindow(s: String): String? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else null
    }

    private val FENCE = Regex("^```[a-zA-Z]*\\n?|```\\s*$")

    private fun strip(raw: String): String {
        var s = raw.trim()
        s = s.replace(FENCE, "").trim()
        // Gemma special tokens that occasionally leak into generated text.
        s = s.replace("<start_of_turn>", "\n").replace("<end_of_turn>", "\n")
            .replace("<eos>", "").replace("<|think|>", "").replace("<|/think|>", "")
        return s.trim()
    }
}
