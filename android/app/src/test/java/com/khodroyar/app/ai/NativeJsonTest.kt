package com.khodroyar.app.ai

import org.junit.Assert.*
import org.junit.Test

class NativeJsonTest {
    @Test fun quotedBracesEscapesAndPersianAreDataNotNesting() {
        val source = """ {"عنوان":"قهوه { شب }","nested":{"array":[1,2]},"quote":"\"hi\" \\ end"} """
        assertEquals("قهوه { شب }", NativeJson.objectValue(source, 1024).getString("عنوان"))
    }
    @Test fun excessiveDepthIsRejectedBeforeRecursiveParser() {
        val source = "{" + "\"a\":{".repeat(100) + "\"x\":1" + "}".repeat(101)
        assertTrue(runCatching { NativeJson.objectValue(source, 10000) }.isFailure)
    }
    @Test fun trailingRootsTextAndIncompleteJsonAreNotAnExecutableObject() {
        for (source in listOf("{} {}", "{} pay", "{\"a\":\"unfinished", "[]", "null", "{", "")) {
            assertTrue(source, runCatching { NativeJson.objectValue(source, 1000) }.isFailure)
        }
    }
    @Test fun androidPermissiveSyntaxAndDuplicateEscapedKeysAreNotNativeJson() {
        for (source in listOf("{amount:1}", "{'amount':1}", "{\"amount\":01}", "{\"amount\":+1}",
            "{\"amount\":.5}", "{\"amount\":1.}", "{\"amount\":NaN}", "{\"a\":[1,]}", "{\"a\":1,}",
            "{/*comment*/\"a\":1}", "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"a\":1;\"b\":2}",
            "{\"a\":\"unescaped\nnewline\"}", "{\"a\":\"\\x20\"}", "{\"a\":\"\\u123Z\"}", "{\"a\":[1}}")) {
            assertTrue(source, runCatching { NativeJson.objectValue(source, 1024) }.isFailure)
        }
    }
    @Test fun validJsonNumbersBooleansNullArraysAndEscapesRetainTheirValues() {
        val json = NativeJson.objectValue("""{"a":[true,false,null,-1.25e+2],"\u0062":"\u0633\u0644\u0627\u0645"}""", 1024)
        assertEquals(-125.0, json.getJSONArray("a").getDouble(3), 0.0)
        assertEquals("سلام", json.getString("b"))
    }
    @Test fun evenSubEpsilonFractionalIntegersAreNotRoundedIntoDifferentNativeArguments() {
        for (args in listOf("""{"month":1.0000000001}""", """{"month":null}""", """{"month":"1"}""", """{"month":true}""")) {
            assertTrue(FunctionToolSchemas.validate(FunctionCall("test", "get_monthly_report", args)) is ValidationResult.Invalid)
        }
        assertTrue(FunctionToolSchemas.validate(FunctionCall("test", "get_monthly_report", """{"month":1}""")) is ValidationResult.Ok)
    }
    @Test fun payloadLengthAndCredentialRedactionAreBoundedAtTheWireBoundary() {
        assertTrue(runCatching { NativeJson.objectValue("{\"x\":\"" + "a".repeat(100) + "\"}", 20) }.isFailure)
        val redacted = OpenRouterFreePolicy.redactCredentials("عنوان ${OpenRouterFixture.key} پایان")
        assertFalse(redacted.contains(OpenRouterFixture.key)); assertTrue(redacted.contains("کلید پنهان شد"))
        assertTrue(FunctionToolSchemas.validate(FunctionCall("credential", "add_expense",
            """{"amount":100,"title":"${OpenRouterFixture.key}"}""")) is ValidationResult.Invalid)
    }
    @Test fun transportRejectsAnyOtherHostRedirectTargetMethodOrPathBeforeNetwork() {
        for (request in listOf(AiHttpRequest("http://openrouter.ai/api/v1/chat/completions", "POST", emptyMap(), "{}"),
            AiHttpRequest("https://elsewhere.invalid", "POST", emptyMap(), "{}"), AiHttpRequest(OpenRouterFreePolicy.CHAT_URL + "/extra", "POST", emptyMap(), "{}"),
            AiHttpRequest(OpenRouterFreePolicy.MODELS_URL, "DELETE", emptyMap()), AiHttpRequest(OpenRouterFreePolicy.CHAT_URL, "GET", emptyMap()))) {
            assertTrue(runCatching { OpenRouterHttp().execute(request) }.isFailure)
        }
    }
}
