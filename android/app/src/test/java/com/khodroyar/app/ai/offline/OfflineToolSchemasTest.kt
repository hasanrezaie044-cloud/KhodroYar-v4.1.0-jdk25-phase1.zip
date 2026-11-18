package com.khodroyar.app.ai.offline

import com.khodroyar.app.ai.AgentToolRegistry
import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineToolSchemasTest {
    @Test fun registrySchemasExposeOnlyTheRequestedExistingToolAndTypedParameters() {
        val addExpense = requireNotNull(AgentToolRegistry.find("add_expense"))
        val description = OfflineToolSchemas.DescriptionOnlyOpenApiTool(addExpense)
            .getToolDescriptionJsonString()
        val root = JSONObject(description)
        assertEquals("add_expense", root.getString("name"))
        val parameters = root.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertTrue(parameters.getJSONArray("required").toString().contains("amount"))
        assertEquals("number", parameters.getJSONObject("properties").getJSONObject("amount").getString("type"))
        assertEquals("string", parameters.getJSONObject("properties").getJSONObject("category").getString("type"))
        assertTrue(parameters.getJSONObject("properties").getJSONObject("category").has("enum"))
    }

    @Test fun descriptionOnlyExecutorIsAThrowingTripwireAndNeverRunsAnAppTool() {
        val spec = requireNotNull(AgentToolRegistry.find("add_expense"))
        val tool: OpenApiTool = OfflineToolSchemas.DescriptionOnlyOpenApiTool(spec)
        val error = runCatching { tool.execute("{\"amount\":100}") }.exceptionOrNull()
        assertTrue(error is SecurityException)
    }

    @Test fun toolProvidersAreBuiltOnlyForExistingSpecs() {
        val specs = listOfNotNull(AgentToolRegistry.find("get_today_expenses"), AgentToolRegistry.find("add_expense"))
        val providers = OfflineToolSchemas.providers(specs)
        assertEquals(specs.size, providers.size)
        assertTrue(providers.isNotEmpty())
    }
}
