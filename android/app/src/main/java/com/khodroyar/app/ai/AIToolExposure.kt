package com.khodroyar.app.ai

import org.json.JSONObject

/** Lazy native schema discovery, not a user-intent parser. All existing tools remain in the registry/executor. */
object AIToolExposure {
    const val DISCOVER = "request_app_tools"
    private val groups = listOf("services", "finance", "vehicle", "reports", "automation", "other")
    val discovery = ToolSpec(DISCOVER, ToolKind.READ, "تعریف ابزارهای بیشتر را بدون خواندن داده دریافت کن؛ هر صفحه حداکثر ۸ ابزار دارد.", listOf(
        ToolParam("group", ParamType.ENUM, true, options = groups), ToolParam("page", ParamType.INT, min = 1.0, max = 10.0)))
    private val basic = setOf("get_jalali_calendar", "get_service_catalog", "search_services_range", "add_service", "add_expense")
    fun greeting(messages: List<FunctionMessage>): Boolean {
        if (messages.any { it.role == "tool" || it.calls.isNotEmpty() }) return false
        val text = messages.lastOrNull { it.role == "user" }?.content.orEmpty().trim().replace(Regex("[!؟?.،\\s]+"), " ").trim()
        return text in setOf("سلام", "درود", "سلام خوبی", "سلام خوبین", "hi", "hello")
    }
    private fun group(tool: ToolSpec): String = when {
        tool.name.contains("service") || tool.name.contains("jalali") -> "services"
        tool.name.contains("automation") -> "automation"
        tool.name.contains("company") || tool.name.contains("report") -> "reports"
        tool.name.contains("expense") || tool.name.contains("income") || tool.name.contains("financial") || tool.name.contains("total") || tool.name.contains("period") -> "finance"
        tool.name.contains("vehicle") || tool.name.contains("fuel") || tool.name.contains("repair") || tool.name.contains("maintenance") || tool.name.contains("insurance") || tool.name.contains("reminder") -> "vehicle"
        else -> "other"
    }
    private fun selection(call: FunctionCall): Pair<String, Int>? = runCatching {
        val j = NativeJson.objectValue(call.arguments, 256)
        require(j.keys().asSequence().all { it in setOf("group", "page") })
        val g = j.getString("group"); require(g in groups)
        val page = if (j.has("page")) java.math.BigDecimal(j.get("page").toString()).intValueExact() else 1
        require(page in 1..10); g to page
    }.getOrNull()
    private fun page(g: String, p: Int) = AgentToolRegistry.tools.filter { group(it) == g }.drop((p - 1) * 8).take(8)
    fun discover(call: FunctionCall): ToolOutcome {
        val (g, p) = selection(call) ?: return ToolOutcome(false, "گروه یا صفحهٔ ابزار معتبر نیست.")
        val tools = page(g, p)
        return ToolOutcome(true, "فقط تعریف ابزار؛ داده‌ای خوانده/تغییر نکرد. گروه=$g، صفحه=$p: " + tools.joinToString { it.name } + "; برای گروه‌های دیگر request_app_tools را به‌کار ببر.")
    }
    fun tools(messages: List<FunctionMessage>, enabled: Boolean): List<ToolSpec> {
        if (!enabled || greeting(messages)) return emptyList()
        val selected = messages.asReversed().firstNotNullOfOrNull { m -> m.calls.lastOrNull { it.name == DISCOVER }?.let(::selection) }
        return (listOf(discovery) + AgentToolRegistry.tools.filter { it.name in basic } + (selected?.let { page(it.first, it.second) } ?: emptyList())).distinctBy { it.name }
    }
}
