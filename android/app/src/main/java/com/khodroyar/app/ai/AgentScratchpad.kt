package com.khodroyar.app.ai

/**
 * Verifiable multi-step plan (Albert's goal/step/mark pattern), owned by the app, not the model.
 *
 * The model may write a goal and up to [MAX_STEPS] steps, but it can only mark a step `done` by
 * naming a tool that really returned a successful result after the plan was created. The final
 * reply always lists steps that were not verified, so a confident model text cannot hide them.
 */
class AgentScratchpad {
    enum class Status(val label: String) { PENDING("در انتظار"), DONE("انجام و تأیید شد"), BLOCKED("متوقف") }
    data class Step(val text: String, val status: Status = Status.PENDING, val evidence: String = "")

    var goal: String = ""; private set
    var steps: List<Step> = emptyList(); private set
    private val evidence = mutableListOf<String>()

    val active: Boolean get() = goal.isNotBlank() && steps.any { it.status == Status.PENDING }

    fun clear() { goal = ""; steps = emptyList(); evidence.clear() }

    /** Called by the agent only with app-verified successful tool outcomes. */
    fun recordSuccess(tool: String) {
        if (tool in PLAN_TOOLS || tool == AIToolExposure.DISCOVER) return
        evidence += tool
        if (evidence.size > 60) evidence.removeAt(0)
    }

    fun plan(call: ValidatedCall): ToolOutcome {
        val goalText = call.str("goal") ?: return ToolOutcome(false, "هدف لازم است.")
        val list = (1..MAX_STEPS).mapNotNull { call.str("step$it") }.distinct()
        if (list.isEmpty()) return ToolOutcome(false, "حداقل یک مرحله لازم است.")
        goal = goalText; steps = list.map { Step(it) }; evidence.clear()
        return ToolOutcome(true, "برنامه ثبت شد (فقط حافظهٔ همین گفتگو؛ داده‌ای تغییر نکرد).\n" + render() +
            "\nهر مرحله فقط با نام ابزاری که بعد از این برنامه نتیجهٔ موفق واقعی داده، done می‌شود.")
    }

    fun mark(call: ValidatedCall): ToolOutcome {
        if (goal.isBlank()) return ToolOutcome(false, "برنامهٔ فعالی نیست؛ ابتدا plan_task.")
        val index = (call.int("step") ?: 0) - 1
        if (index !in steps.indices) return ToolOutcome(false, "شمارهٔ مرحله معتبر نیست.")
        val status = if (call.str("status") == "blocked") Status.BLOCKED else Status.DONE
        val tool = call.str("evidence_tool").orEmpty().trim()
        if (status == Status.DONE) {
            if (tool.isBlank() || tool !in evidence)
                return ToolOutcome(false, "مرحله تأیید نشد: ابزار «$tool» بعد از این برنامه نتیجهٔ موفق نداشته است. ابتدا ابزار واقعی را اجرا کنید؛ متن مدل مدرک نیست.")
        }
        steps = steps.toMutableList().also { it[index] = it[index].copy(status = status, evidence = tool) }
        return ToolOutcome(true, render())
    }

    fun render(): String = "هدف: $goal\n" + steps.mapIndexed { i, s ->
        "${i + 1}. ${s.text} — ${s.status.label}" + if (s.evidence.isNotBlank()) " (مدرک: ${s.evidence})" else ""
    }.joinToString("\n")

    /** One honest line for the final reply; empty when every step is verified or there is no plan. */
    fun unverifiedNotice(): String {
        if (goal.isBlank()) return ""
        val open = steps.withIndex().filter { it.value.status != Status.DONE }
        if (open.isEmpty()) return "برنامه: همهٔ ${steps.size} مرحله با نتیجهٔ واقعی ابزار تأیید شد."
        return "برنامه: ${open.size} از ${steps.size} مرحله تأیید نشده است: " + open.joinToString("، ") { "${it.index + 1}) ${it.value.text}" }
    }

    /** Short context for the next request so a multi-turn task resumes from verified state. */
    fun promptContext(): String = if (!active) "" else "\nبرنامهٔ باز این گفتگو (وضعیت را برنامه تأیید می‌کند):\n" + render()

    companion object {
        const val MAX_STEPS = 6
        const val PLAN = "plan_task"
        const val MARK = "mark_step"
        val PLAN_TOOLS = setOf(PLAN, MARK)
    }
}

/** Marks tool results of approved, executed changes so the model pool never auto-replays after them. */
object AgentCommit {
    const val KEY = "committed"
    fun anyCommitted(messages: List<FunctionMessage>): Boolean = messages.any { m ->
        m.role == "tool" && m.content.contains("\"$KEY\"") &&
            runCatching { org.json.JSONObject(m.content).optBoolean(KEY, false) }.getOrDefault(false)
    }
}
