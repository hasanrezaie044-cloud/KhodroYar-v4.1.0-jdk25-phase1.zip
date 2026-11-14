package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterFreePolicyTest {
    private val f = OpenRouterFixture
    @Test fun initialRouterAndOnlyVerifiedExplicitFreeVariantsAreEligible() {
        assertEquals("openrouter/free", OpenRouterFreePolicy.INITIAL_MODEL)
        assertEquals(setOf("openrouter/free", f.FREE), OpenRouterFreePolicy.catalog(f.catalog()).map { it.id }.toSet())
    }
    @Test fun freeSuffixOrAnUnsuffixedZeroPriceIsNotProof() {
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row("vendor/paid", "0", "0"))).isEmpty())
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row(prompt = "0.00000000001"))).isEmpty())
        assertFalse(OpenRouterFreePolicy.isFreeId("openrouter/auto"))
        assertFalse(OpenRouterFreePolicy.isFreeId("vendor/model:free:nitro"))
    }
    @Test fun everyPriceFieldMustBeExplicitlyZeroIncludingFutureCharges() {
        for (field in listOf("prompt", "completion", "request", "internal_reasoning", "input_cache_read", "image", "audio", "new_charge")) {
            val row = f.row(); row.getJSONObject("pricing").put(field, "0.001")
            assertTrue(field, OpenRouterFreePolicy.catalog(f.catalog(row)).isEmpty())
        }
    }
    @Test fun negativeMissingNullAndNonnumericPricingFailClosed() {
        for (bad in listOf<Any>("-1", "NaN", "Infinity", "free", JSONObject.NULL, true)) {
            assertTrue(bad.toString(), OpenRouterFreePolicy.catalog(f.catalog(f.row(prompt = bad))).isEmpty())
        }
        val row = f.row(); row.getJSONObject("pricing").remove("completion")
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(row)).isEmpty())
    }
    @Test fun numericZeroAndDecimalZeroAreAcceptedNotFloatingPointEpsilon() {
        assertEquals(1, OpenRouterFreePolicy.catalog(f.catalog(f.row(prompt = "0E-100", completion = 0))).size)
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row(prompt = "1E-1000"))).isEmpty())
    }
    @Test fun bareNumericPositivePricesDoNotBecomeFreeByUnderflowOnAndroid() {
        val json = f.catalog().replace("\"prompt\":\"0\"", "\"prompt\":1e-1000")
        assertTrue(OpenRouterFreePolicy.catalog(json).isEmpty())
    }
    @Test fun nativeToolSupportAndEnoughContextAreMandatory() {
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row(tools = false))).isEmpty())
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row().put("context_length", 8192))).isEmpty())
        val row = f.row(); row.remove("supported_parameters")
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(row)).isEmpty())
    }
    @Test fun routerCannotMaskTheAbsenceOfAnyFreeToolModel() {
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row("openrouter/free"))).isEmpty())
        assertTrue(OpenRouterFreePolicy.catalog(f.catalog(f.row("openrouter/free"), f.row(tools = false))).isEmpty())
    }
    @Test fun noFallbackNoCollectionAndZeroCapsAreAlwaysPresent() {
        val route = OpenRouterFreePolicy.routing()
        assertFalse(route.getBoolean("allow_fallbacks")); assertTrue(route.getBoolean("require_parameters"))
        assertEquals("deny", route.getString("data_collection"))
        for (field in listOf("prompt", "completion", "request")) assertEquals(0, route.getJSONObject("max_price").getInt(field))
    }
    @Test fun returnedPaidUnknownOrUnrequestedModelsAreRejected() {
        val catalog = OpenRouterFreePolicy.catalog(f.catalog(f.row(), f.row("synthetic/other:free")))
        for (model in listOf("vendor/paid", "vendor/unknown:free", "synthetic/other:free")) {
            assertTrue(runCatching { OpenRouterFreePolicy.checkReturnedModel(model, f.FREE, catalog) }.isFailure)
        }
    }
    @Test fun duplicateModelIdentityIsRejected() {
        assertTrue(runCatching { OpenRouterFreePolicy.catalog(f.catalog(f.row(), f.row(prompt = "0.001"))) }.isFailure)
    }
    @Test fun keyCannotContainHeaderInjectionPlaceholdersUnicodeOrTooManyBytes() {
        assertEquals(f.key, OpenRouterFreePolicy.normalizeKey("  ${f.key}  "))
        for (value in listOf("", "YOUR_OPENROUTER_API_KEY", f.key + "\r\nInjected: yes", "sk-or-" + "الف".repeat(30), "x".repeat(1024))) assertNull(OpenRouterFreePolicy.normalizeKey(value))
    }
    @Test fun pastedSecretsAreDetectedBeforeHistoryAndNetwork() {
        assertTrue(OpenRouterFreePolicy.containsCredential("کلید من ${f.key}"))
        assertFalse(OpenRouterFreePolicy.containsCredential("سرویس شب را از اول فروردین تا آخر اسفند ثبت کن"))
    }
    @Test fun schemasAreAllowlistedTypedAndRejectUnknownInternalFields() {
        val schema = FunctionToolSchemas.tools(listOf(requireNotNull(AgentToolRegistry.find("bulk_register_services")))).getJSONObject(0).getJSONObject("function")
        val params = schema.getJSONObject("parameters")
        assertFalse(params.getBoolean("additionalProperties"))
        assertEquals("integer", params.getJSONObject("properties").getJSONObject("per_day").getString("type"))
        assertTrue(FunctionToolSchemas.validate(f.call("add_expense", """{"amount":100,"created_record":{}}""")) is ValidationResult.Invalid)
        assertTrue(FunctionToolSchemas.validate(f.call("run_sql", """{"query":"DELETE FROM services"}""")) is ValidationResult.Invalid)
    }
    @Test fun schemaAndRuntimeRejectInvalidDatesRangesAndNumbers() {
        for (args in listOf("""{"amount":-5}""", """{"amount":"NaN"}""", """{"amount":5,"date":"1404/12/30"}"""))
            assertTrue(FunctionToolSchemas.validate(f.call("add_expense", args)) is ValidationResult.Invalid)
        assertTrue(FunctionToolSchemas.validate(f.call("bulk_register_services", """{"start_date":"1404/01/01","end_date":"1404/12/29","service_type":"night","per_day":21,"days":"all"}""")) is ValidationResult.Invalid)
    }
}
