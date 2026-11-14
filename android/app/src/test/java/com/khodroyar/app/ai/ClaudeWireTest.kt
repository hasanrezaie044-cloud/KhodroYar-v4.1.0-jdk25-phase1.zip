package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClaudeWireTest {
    private val model = "claude-synthetic"
    private val call = FunctionCall("tool_once", "add_expense", "{\"amount\":100}")
    private fun completion(content: JSONArray, stop: String = "tool_use", identity: String = model) = JSONObject().put("model", identity).put("role", "assistant")
        .put("content", content).put("stop_reason", stop).toString()
    @Test fun nativeImagesToolCallsAndResultsRemainDistinctFromProseAndSystemInstructions() {
        val messages = listOf(FunctionMessage("system", "فارسی"), FunctionMessage("user", "تصویر", images = listOf(AIImage("image/jpeg", "aGVsbG8="))),
            FunctionMessage("assistant", "پیشنهاد", calls = listOf(call)), FunctionMessage("tool", "نتیجهٔ واقعی", toolCallId = call.id))
        val q = ClaudeWire.request(model, AIModelRequest(messages, listOf(requireNotNull(AgentToolRegistry.find("add_expense"))), stream = false), 32768)
        assertEquals("فارسی", q.getString("system")); assertEquals(32768, q.getInt("max_tokens")); assertFalse(q.has("systemInstruction"))
        val rows = q.getJSONArray("messages")
        assertEquals("base64", rows.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("source").getString("type"))
        assertEquals("tool_use", rows.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
        val result = rows.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type")); assertEquals(call.id, result.getString("tool_use_id"))
        assertEquals("add_expense", q.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(q.getJSONArray("tools").getJSONObject(0).has("input_schema"))
    }
    @Test fun completedNativeCallsAreNotExecutedAndJsonInsideTextIsNotACall() {
        val use = JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name).put("input", JSONObject(call.arguments))
        val parsed = ClaudeWire.completion(completion(JSONArray().put(use)), model)
        assertEquals(call, parsed.message.calls.single())
        val prose = JSONObject().put("type", "text").put("text", "{\"tool\":\"delete_service\"}")
        assertTrue(ClaudeWire.completion(completion(JSONArray().put(prose), "end_turn"), model).message.calls.isEmpty())
    }
    @Test fun truncatedUnknownDuplicateOrUnselectedNativeCallsAreNeverExecutable() {
        val use = JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name).put("input", JSONObject(call.arguments))
        for (bad in listOf(completion(JSONArray().put(use), "max_tokens"), completion(JSONArray().put(use), identity = "another-model"),
            completion(JSONArray().put(use).put(use)), completion(JSONArray().put(JSONObject().put("type", "server_tool_use")))))
            assertTrue(runCatching { ClaudeWire.completion(bad, model) }.isFailure)
    }
    @Test fun nativeStreamRequiresMessageIdentityClosedBlocksTerminalReasonAndMessageStop() {
        val seen = mutableListOf<String>(); val stream = ClaudeStream(model, seen::add)
        stream.accept("""{"type":"message_start","message":{"model":"$model","usage":{"input_tokens":20,"output_tokens":0}}}""")
        stream.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_once","name":"add_expense","input":{}}}""")
        stream.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"amount\":"}}""")
        assertTrue(runCatching { stream.result() }.isFailure)
        stream.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"100}"}}""")
        stream.accept("""{"type":"content_block_stop","index":0}""")
        stream.accept("""{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}""")
        assertTrue(runCatching { stream.result() }.isFailure)
        stream.accept("""{"type":"message_stop"}""")
        assertEquals(call, stream.result().message.calls.single()); assertTrue(seen.isEmpty())
    }
    @Test fun noTextFromAnUnselectedModelReachesTheStreamingBubble() {
        val seen = mutableListOf<String>(); val stream = ClaudeStream(model, seen::add)
        assertTrue(runCatching { stream.accept("""{"type":"message_start","message":{"model":"wrong"}}""") }.isFailure)
        assertTrue(seen.isEmpty())
    }
    @Test fun nativeQuotaStreamErrorIsShortSanitisedAndNeverACompletedToolResult() {
        val stream = ClaudeStream(model) { }
        try { stream.accept("""{"type":"error","error":{"type":"rate_limit_error","message":"RAW PRIVATE"}}"""); fail("Must stop") }
        catch (e: OpenRouterReplyFailure) { assertEquals(AiErrorKind.RATE_LIMITED, e.failure.kind); assertFalse(e.failure.message.contains("PRIVATE")) }
        assertTrue(runCatching { stream.result() }.isFailure)
    }
    @Test fun orphanToolResultsAndMalformedImageReferencesNeverGetSerializedAsAValidRequest() {
        assertTrue(runCatching { ClaudeWire.request(model, AIModelRequest(listOf(FunctionMessage("tool", "private", toolCallId = "missing")), emptyList()), 32768) }.isFailure)
        for (file in listOf("../secret.jpg", "/etc/private.jpg", "https://host/img.jpg", "other.png"))
            assertTrue(runCatching { ChatImageRef(file) }.isFailure)
    }
}
