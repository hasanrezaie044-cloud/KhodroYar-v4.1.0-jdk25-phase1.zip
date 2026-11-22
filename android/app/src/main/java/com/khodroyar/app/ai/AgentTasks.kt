package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One app-verified write: the operation id is shown to the user and never reused for a replay. */
data class AgentOperation(val id: String, val tool: String, val ok: Boolean, val summary: String, val at: Long)

enum class AgentTaskStatus(val label: String) {
    ACTIVE("در جریان"), COMPLETED("کامل و تأییدشده"), PARTIAL("بخشی انجام شد"), CANCELLED("لغو شد"), FAILED("متوقف با خطا");
    val open: Boolean get() = this == ACTIVE
}

/**
 * A persistent multi-step task owned by the app. It survives process death, a new chat and an
 * app restart, and it is part of the full backup. It never contains keys or raw tool payloads;
 * step text and operation summaries are bounded and redacted.
 */
data class AgentTask(
    val id: String,
    val conversationId: String,
    val goal: String,
    val steps: List<AgentScratchpad.Step>,
    val operations: List<AgentOperation> = emptyList(),
    val status: AgentTaskStatus = AgentTaskStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val verifiedSteps: Int get() = steps.count { it.status == AgentScratchpad.Status.DONE }
}

object AgentTaskCodec {
    const val VERSION = 1
    private const val MAX_TEXT = 160

    fun encode(tasks: List<AgentTask>): JSONObject = JSONObject().put("version", VERSION).put("tasks", JSONArray().apply {
        tasks.forEach { t ->
            put(JSONObject().put("id", t.id).put("conversationId", t.conversationId).put("goal", t.goal)
                .put("status", t.status.name).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)
                .put("steps", JSONArray().apply { t.steps.forEach { s -> put(JSONObject().put("text", s.text).put("status", s.status.name).put("evidence", s.evidence)) } })
                .put("operations", JSONArray().apply { t.operations.forEach { o -> put(JSONObject().put("id", o.id).put("tool", o.tool).put("ok", o.ok).put("summary", o.summary).put("at", o.at)) } }))
        }
    })

    /** Tolerant reader: malformed entries are skipped, never guessed. An unknown (newer) format fails closed. */
    fun decode(root: JSONObject?): List<AgentTask> {
        if (root == null) return emptyList()
        require(root.optInt("version", VERSION) in 1..VERSION) { "Unsupported agent task format" }
        val array = root.optJSONArray("tasks") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val goal = clean(o.optString("goal"))
            if (!ID.matches(id) || goal.isBlank()) return@mapNotNull null
            val steps = o.optJSONArray("steps")?.let { a -> (0 until minOf(a.length(), AgentScratchpad.MAX_STEPS)).mapNotNull { j ->
                val s = a.optJSONObject(j) ?: return@mapNotNull null
                val text = clean(s.optString("text")).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AgentScratchpad.Step(text, enumValueOrNull<AgentScratchpad.Status>(s.optString("status")) ?: AgentScratchpad.Status.PENDING,
                    s.optString("evidence").take(60))
            } }.orEmpty()
            if (steps.isEmpty()) return@mapNotNull null
            val operations = o.optJSONArray("operations")?.let { a -> (0 until minOf(a.length(), MAX_OPERATIONS)).mapNotNull { j ->
                val p = a.optJSONObject(j) ?: return@mapNotNull null
                val opId = p.optString("id").takeIf { ID.matches(it) } ?: return@mapNotNull null
                AgentOperation(opId, p.optString("tool").take(60), p.optBoolean("ok"), clean(p.optString("summary")), p.optLong("at"))
            } }.orEmpty()
            AgentTask(id, o.optString("conversationId").take(64), goal, steps, operations,
                enumValueOrNull<AgentTaskStatus>(o.optString("status")) ?: AgentTaskStatus.ACTIVE, o.optLong("createdAt"), o.optLong("updatedAt"))
        }
    }

    fun clean(text: String): String = AiTextSafety.redact(text.filter { !it.isISOControl() || it == '\n' }).trim().take(MAX_TEXT)

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
    private val ID = Regex("[A-Za-z0-9-]{4,64}")
    const val MAX_OPERATIONS = AgentScratchpad.MAX_TOOL_CALLS * 2
}

/** Small JSON file in app-private storage; atomic replace and read-back like the memory store. */
class AgentTaskStore(private val file: File) {
    private val lock = Any()

    fun all(): List<AgentTask> = synchronized(lock) { read() }
    fun latestFor(conversationId: String): AgentTask? = all().filter { it.conversationId == conversationId }.maxByOrNull { it.updatedAt }
    fun open(): List<AgentTask> = all().filter { it.status.open }

    fun upsert(task: AgentTask): Boolean = synchronized(lock) {
        // Compare with what the codec actually keeps (redacted, bounded), not with the raw input.
        val stored = normalize(listOf(task)).singleOrNull() ?: return false
        val next = (read().filterNot { it.id == stored.id } + stored).sortedBy { it.updatedAt }.takeLast(MAX_TASKS)
        write(next)
        read().any { it == stored }
    }

    fun delete(id: String): Boolean = synchronized(lock) { write(read().filterNot { it.id == id }); read().none { it.id == id } }
    fun replaceAll(tasks: List<AgentTask>): Boolean = synchronized(lock) {
        val bounded = normalize(tasks.distinctBy { it.id }).sortedBy { it.updatedAt }.takeLast(MAX_TASKS)
        write(bounded); read() == bounded
    }
    private fun normalize(tasks: List<AgentTask>): List<AgentTask> = AgentTaskCodec.decode(AgentTaskCodec.encode(tasks))
    fun clear(): Boolean = replaceAll(emptyList())

    private fun read(): List<AgentTask> = runCatching { if (file.isFile) AgentTaskCodec.decode(JSONObject(file.readText())) else emptyList() }.getOrDefault(emptyList())

    private fun write(tasks: List<AgentTask>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AgentTaskCodec.encode(tasks).toString())
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "agent task write failed" } }
    }

    companion object { const val MAX_TASKS = 50 }
}
