package com.khodroyar.app.ai.offline

import com.khodroyar.app.ai.AIToolExposure
import com.khodroyar.app.ai.AgentToolRegistry
import com.khodroyar.app.ai.FunctionCall
import com.khodroyar.app.ai.FunctionMessage
import com.khodroyar.app.ai.PersianToolAgent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exports the exact system prompt, tool schemas and progressive tool exposure that the in-app
 * offline agent sends, so the offline model evaluation (tools/offline-eval) feeds Gemma and Qwen
 * the same inputs as the app. Written to build/offline-eval/app-agent.json.
 */
class OfflineEvalExportTest {
    @Test fun exportAgentContractForOfflineModelEvaluation() {
        val schema = { name: String ->
            JSONObject(OfflineToolSchemas.DescriptionOnlyOpenApiTool(
                requireNotNull(AgentToolRegistry.find(name) ?: AIToolExposure.discovery.takeIf { it.name == name })
            ).getToolDescriptionJsonString())
        }
        val user = FunctionMessage("user", "یک درخواست")
        val initial = AIToolExposure.tools(listOf(user), enabled = true).map { it.name }
        val pages = JSONObject()
        for (group in listOf("services", "finance", "vehicle", "reports", "automation", "other")) {
            val groupPages = JSONArray()
            for (page in 1..10) {
                val call = FunctionCall("c", AIToolExposure.DISCOVER, JSONObject().put("group", group).put("page", page).toString())
                val exposed = AIToolExposure.tools(listOf(user, FunctionMessage("assistant", calls = listOf(call))), enabled = true)
                    .map { it.name }.filter { it !in initial }
                if (exposed.isEmpty()) break
                groupPages.put(JSONObject().put("page", page).put("tools", JSONArray(exposed))
                    .put("discover_result", AIToolExposure.discover(call).text))
            }
            pages.put(group, groupPages)
        }
        val all = (listOf(AIToolExposure.discovery) + AgentToolRegistry.tools).distinctBy { it.name }
        val root = JSONObject()
            .put("system_prompt", PersianToolAgent.prompt())
            .put("initial_tools", JSONArray(initial))
            .put("discovery_pages", pages)
            .put("tool_kinds", JSONObject().apply { all.forEach { put(it.name, it.kind.name) } })
            .put("tools", JSONArray().apply { all.forEach { put(schema(it.name)) } })
        val out = File("build/offline-eval/app-agent.json")
        out.parentFile.mkdirs()
        out.writeText(root.toString(2))
        assertTrue(initial.contains(AIToolExposure.DISCOVER))
        assertTrue(root.getJSONArray("tools").length() > 40)
    }
}
