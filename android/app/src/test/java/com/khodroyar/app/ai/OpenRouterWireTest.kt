package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterWireTest {
    private val f = OpenRouterFixture
    private val catalog get() = OpenRouterFreePolicy.catalog(f.catalog())
    private fun parse(body: String) = OpenRouterWire.completion(body, "openrouter/free", catalog)
    private val streamed = mutableListOf<String>()
    private fun stream(onText: (String) -> Unit = streamed::add) = OpenRouterStream("openrouter/free", catalog, onText)
    private fun chunk(delta: JSONObject = JSONObject(), finish: String? = null): String = JSONObject().put("model", f.FREE)
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL))).toString()
    @Test fun nativeCallsNotJsonProseAreExecutable() {
        val prose = parse(f.completion("""{"tool_calls":[{"name":"delete_service"}]}"""))
        assertTrue(prose.message.calls.isEmpty())
        val native = parse(f.completion("", listOf(f.call("add_expense", """{"amount":100}"""))))
        assertEquals("add_expense", native.message.calls.single().name)
    }
    @Test fun incompleteLengthErrorOrPaidFinishCannotProduceCalls() {
        for (body in listOf(f.completion("", listOf(f.call("add_expense")), finish = "length"),
            f.completion(model = "vendor/paid"), f.completion().replace("\"cost\":0", "\"cost\":0.01"),
            f.completion("", listOf(f.call("add_expense")), finish = "stop"))) assertTrue(runCatching { parse(body) }.isFailure)
    }
    @Test fun nativeToolFragmentsAssembleOnlyAfterDoneAndFinish() {
        val seen = mutableListOf<String>(); val s = stream(seen::add)
        val head = JSONObject().put("index", 0).put("id", "call_a").put("type", "function")
            .put("function", JSONObject().put("name", "add_expense").put("arguments", "{\"amount\":"))
        s.accept(chunk(JSONObject().put("role", "assistant").put("content", "بررسی").put("tool_calls", JSONArray().put(head))))
        assertTrue(runCatching { s.result() }.isFailure)
        val tail = JSONObject().put("index", 0).put("function", JSONObject().put("arguments", "100}"))
        s.accept(chunk(JSONObject().put("tool_calls", JSONArray().put(tail))))
        s.accept(chunk(finish = "tool_calls")); assertTrue(runCatching { s.result() }.isFailure)
        s.accept("[DONE]")
        assertEquals("{\"amount\":100}", s.result().message.calls.single().arguments)
        assertEquals(listOf("بررسی"), seen)
    }
    @Test fun abruptSseEvenAfterFinishCannotExecute() {
        val s = stream(); s.accept(chunk(JSONObject().put("content", "ناقص"), "stop"))
        assertTrue(runCatching { s.result() }.isFailure)
    }
    @Test fun sseErrorAfterVisibleTextInvalidatesTheWholeTurn() {
        val s = stream(); s.accept(chunk(JSONObject().put("content", "بخشی از پاسخ")))
        assertTrue(runCatching { s.accept("""{"error":{"message":"PRIVATE RAW DETAIL"}}""") }.isFailure)
    }
    @Test fun duplicateIdsTooManyCallsAndMalformedArgumentsAreRejected() {
        assertTrue(runCatching { parse(f.completion("", listOf(f.call("a"), f.call("b")))) }.isFailure)
        assertTrue(runCatching { parse(f.completion("", (0..4).map { f.call("a", id = "id$it") })) }.isFailure)
        assertTrue(runCatching { parse(f.completion("", listOf(f.call("add_expense", "[]")))) }.isFailure)
    }
    @Test fun unknownModelOrNonzeroSseCostIsRejectedBeforeFinalResult() {
        assertTrue(runCatching { stream().accept(chunk().replace(f.FREE, "vendor/paid")) }.isFailure)
        assertTrue(runCatching { stream().accept("""{"model":"${f.FREE}","choices":[],"usage":{"cost":1}}""") }.isFailure)
    }
    @Test fun sseReaderHandlesCommentsUtf8AndCrLfNotArbitraryUnboundedLines() {
        val seen = mutableListOf<String>()
        val bytes = ": keepalive\r\ndata: {\"text\":\"سلام\"}\r\n\r\ndata: [DONE]\n\n".toByteArray()
        SseEvents.read(bytes.inputStream().reader(), seen::add)
        assertEquals(listOf("{\"text\":\"سلام\"}", "[DONE]"), seen)
        assertTrue(runCatching { SseEvents.read(("data: " + "x".repeat(65537)).reader()) {} }.isFailure)
    }
}
