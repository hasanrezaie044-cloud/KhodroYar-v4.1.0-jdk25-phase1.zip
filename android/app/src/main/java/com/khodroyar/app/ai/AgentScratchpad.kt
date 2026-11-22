package com.khodroyar.app.ai

import java.util.UUID

/**
 * Verifiable multi-step plan (Albert's goal/step/mark pattern), owned by the app, not the model.
 *
 * The model may write a goal and up to [MAX_STEPS] steps, but it can only mark a step `done` by
 * naming a tool that really returned a successful result after the plan was created. The final
 * reply always lists steps that were not verified, so a confident model text cannot hide them.
 *
 * Every change is handed to [persist] as an [AgentTask], so an interrupted task (process death,
 * new chat, restart) can be resumed or cancelled later; verified operations keep their ids.
 */
class AgentScratchpad(
    private val clock: () -> Long = System::currentTimeMillis,
    private val persist: (AgentTask) -> Unit = {},
) {
    enum class Status(val label: String) { PENDING("در انتظار"), DONE("انجام و تأیید شد"), BLOCKED("متوقف") }
    data class Step(val text: String, val status: Status = Status.PENDING, val evidence: String = "")

    var goal: String = ""; private set
    var steps: List<Step> = emptyList(); private set
    var taskId: String = ""; private set
    var conversationId: String = ""
    var operations: List<AgentOperation> = emptyList(); private set
    var status: AgentTaskStatus = AgentTaskStatus.ACTIVE; private set
    private var createdAt = 0L
    private val evidence = mutableListOf<String>()

    val active: Boolean get() = goal.isNotBlank() && status.open && steps.any { it.status == Status.PENDING }

    /** Forgets the in-memory plan only; a persisted task keeps its last recorded state. */
    fun clear() { goal = ""; steps = emptyList(); evidence.clear(); operations = emptyList(); taskId = ""; status = AgentTaskStatus.ACTIVE; createdAt = 0L }

    /** Resume a persisted open task. Evidence is not restored: old results never verify new steps. */
    fun restore(task: AgentTask) {
        clear()
        taskId = task.id; conversationId = task.conversationId; goal = task.goal; steps = task.steps
        operations = task.operations; status = task.status; createdAt = task.createdAt
    }

    fun snapshot(): AgentTask? = if (goal.isBlank()) null else
        AgentTask(taskId, conversationId, goal, steps, operations, status, createdAt, clock())

    private fun save() { snapshot()?.let { runCatching { persist(it) } } }

    /** Called by the agent only with app-verified successful tool outcomes. */
    fun recordSuccess(tool: String) {
        if (tool in PLAN_TOOLS || tool == AIToolExposure.DISCOVER) return
        evidence += tool
        if (evidence.size > 60) evidence.removeAt(0)
    }

    /** A confirmed write attempt (successful or not) with its operation id; persisted immediately. */
    fun recordOperation(operation: AgentOperation) {
        if (goal.isBlank()) return
        operations = (operations + operation).takeLast(AgentTaskCodec.MAX_OPERATIONS)
        save()
    }

    fun plan(call: ValidatedCall): ToolOutcome {
        val goalText = call.str("goal")?.let(AgentTaskCodec::clean) ?: return ToolOutcome(false, "هدف لازم است.")
        val list = (1..MAX_STEPS).mapNotNull { call.str("step$it")?.let(AgentTaskCodec::clean)?.takeIf(String::isNotBlank) }.distinct()
        if (list.isEmpty()) return ToolOutcome(false, "حداقل یک مرحله لازم است.")
        var closed = ""
        if (active && goal != goalText) {
            // A different goal never silently erases the old one: it is closed as partial and stays in the task log.
            status = AgentTaskStatus.PARTIAL; save()
            closed = "کار قبلی «$goal» با ${steps.count { it.status == Status.DONE }} از ${steps.size} مرحلهٔ تأییدشده به‌صورت «${status.label}» بسته شد.\n"
        }
        if (taskId.isBlank() || !active) { taskId = UUID.randomUUID().toString(); createdAt = clock(); operations = emptyList() }
        goal = goalText; steps = list.map { Step(it) }; evidence.clear(); status = AgentTaskStatus.ACTIVE
        save()
        return ToolOutcome(true, closed + "برنامه ثبت شد (کار ${taskId.take(8)}؛ داده‌ای تغییر نکرد).\n" + render() +
            "\nهر مرحله فقط با نام ابزاری که بعد از این برنامه نتیجهٔ موفق واقعی داده، done می‌شود.")
    }

    fun mark(call: ValidatedCall): ToolOutcome {
        if (goal.isBlank()) return ToolOutcome(false, "برنامهٔ فعالی نیست؛ ابتدا plan_task.")
        if (!status.open) return ToolOutcome(false, "این کار ${status.label} است و دیگر تغییر نمی‌کند.")
        val index = (call.int("step") ?: 0) - 1
        if (index !in steps.indices) return ToolOutcome(false, "شمارهٔ مرحله معتبر نیست.")
        val blocked = call.str("status") == "blocked"
        val tool = call.str("evidence_tool").orEmpty().trim()
        if (!blocked && (tool.isBlank() || tool !in evidence))
            return ToolOutcome(false, "مرحله تأیید نشد: ابزار «$tool» بعد از این برنامه نتیجهٔ موفق نداشته است. ابتدا ابزار واقعی را اجرا کنید؛ متن مدل مدرک نیست.")
        steps = steps.toMutableList().also { it[index] = it[index].copy(status = if (blocked) Status.BLOCKED else Status.DONE, evidence = tool) }
        if (steps.none { it.status == Status.PENDING }) status = if (steps.all { it.status == Status.DONE }) AgentTaskStatus.COMPLETED else AgentTaskStatus.PARTIAL
        save()
        return ToolOutcome(true, render())
    }

    /** The owner stopped the task: open steps stay unverified and nothing is undone silently. */
    fun cancel(): AgentTask? {
        if (goal.isBlank() || !status.open) return null
        status = AgentTaskStatus.CANCELLED; save()
        return snapshot()
    }

    /** A write failed or could not be verified; the task stops so nothing continues on a guess. */
    fun fail() { if (goal.isNotBlank() && status.open) { status = AgentTaskStatus.FAILED; save() } }

    fun render(): String = "هدف: $goal" + (if (status.open) "" else " (${status.label})") + "\n" + steps.mapIndexed { i, s ->
        "${i + 1}. ${s.text} — ${s.status.label}" + if (s.evidence.isNotBlank()) " (مدرک: ${s.evidence})" else ""
    }.joinToString("\n") + if (operations.isEmpty()) "" else "\nعملیات: " + operations.joinToString("، ") { "${it.id.take(8)} ${it.tool} ${if (it.ok) "✓" else "✗"}" }

    /** One honest line for the final reply; empty when there is no plan. */
    fun unverifiedNotice(): String {
        if (goal.isBlank()) return ""
        val open = steps.withIndex().filter { it.value.status != Status.DONE }
        val failedOps = operations.count { !it.ok }
        val ops = if (operations.isEmpty()) "" else " عملیات تأییدشده: ${operations.count { it.ok }}" + (if (failedOps > 0) "، ناموفق: $failedOps" else "") + "."
        if (open.isEmpty()) return "برنامه: همهٔ ${steps.size} مرحله با نتیجهٔ واقعی ابزار تأیید شد.$ops"
        return "برنامه: ${open.size} از ${steps.size} مرحله تأیید نشده است: " + open.joinToString("، ") { "${it.index + 1}) ${it.value.text}" } + ops
    }

    /** Short context for the next request so a multi-turn task resumes from verified state. */
    fun promptContext(): String = if (!active) "" else "\nکار باز این گفتگو (${taskId.take(8)}؛ وضعیت را برنامه تأیید می‌کند):\n" + render()

    companion object {
        const val MAX_STEPS = 8
        /** Tool calls per user turn (reads, discovery, writes); plan bookkeeping is not counted. */
        const val MAX_TOOL_CALLS = 12
        const val PLAN = "plan_task"
        const val MARK = "mark_step"
        val PLAN_TOOLS = setOf(PLAN, MARK)
    }
}
