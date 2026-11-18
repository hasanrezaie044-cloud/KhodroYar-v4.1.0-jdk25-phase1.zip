package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One durable fact the owner approved for the assistant to remember across conversations. */
data class MemoryFact(val id: String, val text: String, val category: String, val createdAt: Long)

/**
 * On-device assistant memory (Albert-style "recall on demand", not a prompt blob).
 *
 * - Stored only in app-private storage as a small JSON file; never synced, never sent in full.
 * - The model reaches it through tools: recall is read-only, remember/forget need the owner's
 *   approval card like every other write. Only a few relevant facts are added to a turn.
 * - Credentials, card numbers and similar secrets are refused before they reach the file.
 */
class AssistantMemoryStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    @Volatile private var cache: List<MemoryFact>? = null

    /** Cached after the first read, so building a prompt does not touch the disk again. */
    fun count(): Int = cache?.size ?: all().size

    fun all(): List<MemoryFact> = synchronized(lock) { read() }

    fun find(id: String): MemoryFact? = all().singleOrNull { it.id == id || (id.length >= 6 && it.id.startsWith(id)) }

    /** Returns the stored fact, or null when it was refused (secret-like, empty, duplicate kept as-is). */
    fun remember(text: String, category: String = "other"): MemoryFact? = synchronized(lock) {
        val clean = sanitize(text) ?: return@synchronized null
        val facts = read().toMutableList()
        facts.firstOrNull { normalize(it.text) == normalize(clean) }?.let { return@synchronized it }
        val fact = MemoryFact(UUID.randomUUID().toString(), clean, category.takeIf { it in CATEGORIES } ?: "other", clock())
        facts += fact
        // Bounded: the oldest facts are dropped first so the file and recall stay small.
        write(facts.sortedBy { it.createdAt }.takeLast(MAX_FACTS))
        read().singleOrNull { it.id == fact.id }
    }

    fun forget(id: String): Boolean = synchronized(lock) {
        val facts = read()
        val target = facts.singleOrNull { it.id == id } ?: return@synchronized false
        write(facts - target)
        read().none { it.id == target.id }
    }

    fun clear(): Boolean = synchronized(lock) { write(emptyList()); read().isEmpty() }

    /** Lexical relevance with Persian normalisation; recent facts break ties. */
    fun recall(query: String?, limit: Int = 8): List<MemoryFact> {
        val facts = all()
        val terms = tokens(query.orEmpty())
        if (terms.isEmpty()) return facts.sortedByDescending { it.createdAt }.take(limit)
        return facts.map { fact -> fact to tokens(fact.text + " " + categoryLabel(fact.category)).let { words ->
            terms.count { term -> words.any { it.startsWith(term) || term.startsWith(it) && it.length >= 3 } }
        } }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryFact, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit).map { it.first }
    }

    private fun read(): List<MemoryFact> = cache ?: readFile().also { cache = it }

    private fun readFile(): List<MemoryFact> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONObject(file.readText()).optJSONArray("facts") ?: JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text"); val id = o.optString("id")
            if (id.isBlank() || text.isBlank()) null else MemoryFact(id, text, o.optString("category", "other"), o.optLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    private fun write(facts: List<MemoryFact>) {
        cache = null // Readback after every write comes from the file, not from memory.
        file.parentFile?.mkdirs()
        val json = JSONObject().put("version", 1).put("facts", JSONArray().apply {
            facts.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("category", it.category).put("createdAt", it.createdAt)) }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "memory write failed" } }
    }

    companion object {
        const val MAX_FACTS = 100
        const val MAX_CHARS = 80
        val CATEGORIES = listOf("vehicle", "preference", "work", "finance", "other")
        fun categoryLabel(category: String) = when (category) {
            "vehicle" -> "خودرو"; "preference" -> "ترجیح"; "work" -> "کار"; "finance" -> "مالی"; else -> "سایر"
        }
        private val CARD = Regex("""(?:\d[\s-]?){16,19}""")
        private val SECRET_WORDS = listOf("رمز", "پسورد", "password", "cvv2", "cvv", "پین", "otp", "کد یکبار")

        /** Null when the text is empty or looks like a secret that must never be stored. */
        fun sanitize(text: String): String? {
            val t = text.filter { !it.isISOControl() }.trim().take(MAX_CHARS)
            if (t.isBlank() || AiTextSafety.containsCredential(t)) return null
            val digits = AgentActionValidator.normalizeDigits(t)
            if (CARD.containsMatchIn(digits)) return null
            val lower = t.lowercase()
            if (SECRET_WORDS.any { lower.contains(it) }) return null
            return t
        }

        /** Persian/Arabic digits and letters folded; unlike the amount parser, spaces are kept. */
        fun normalize(s: String): String = s.map { ch -> when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰'); in '٠'..'٩' -> '0' + (ch - '٠'); else -> ch } }.joinToString("")
            .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک').replace('ة', 'ه').replace("\u200c", " ")
            .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private val STOP = setOf("را", "به", "از", "در", "که", "با", "این", "آن", "و", "یا", "من", "برای", "تا", "هم", "است", "هست", "بود", "the", "a")
        fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.length >= 2 && it !in STOP }.distinct()
    }
}
