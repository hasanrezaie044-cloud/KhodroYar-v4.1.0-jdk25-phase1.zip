package com.khodroyar.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Chat history, stored as one small JSON file in the app's private storage. It is deliberately
 * NOT in the Room database: the existing database schema, migrations and backups stay untouched.
 * Deleting the history never touches any financial or vehicle record.
 */
class ConversationRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, "ai_conversations.json")
    private companion object { val lock = Any(); const val MAX_BACKUP_CONVERSATIONS = 2000 }

    suspend fun load(): List<Conversation> = withContext(Dispatchers.IO) {
        synchronized(lock) { readAll() }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        synchronized(lock) {
            coroutine.ensureActive()
            val trimmedMessages = conversation.messages.map { it.copy(text = AiTextSafety.redact(it.text)) }
            val updated = conversation.copy(title = AiTextSafety.redact(conversation.title), messages = trimmedMessages)
            val all = readAll().filter { it.id != updated.id } + updated
            val kept = all.sortedByDescending { it.updatedAt }
            writeAll(kept)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) { writeAll(readAll().filter { it.id != id }) }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        synchronized(lock) { if (file.exists()) file.delete() }
        Unit
    }

    /**
     * Full-backup form: texts are redacted again. An image reference is kept only when that image
     * is embedded in the same backup ([keepImages]); otherwise it would point at a missing file.
     */
    fun exportForBackup(keepImages: Set<String> = emptySet()): JSONArray = synchronized(lock) {
        encode(readAll().map { c -> c.copy(title = AiTextSafety.redact(c.title), messages = c.messages.map {
            it.copy(text = AiTextSafety.redact(it.text), image = it.image?.takeIf { ref -> ref.fileName in keepImages })
        }) })
    }

    /** Image file names referenced by saved chats, most recently updated conversation first. */
    fun imageRefsForBackup(): List<String> = synchronized(lock) {
        readAll().sortedByDescending { it.updatedAt }.flatMap { c -> c.messages.asReversed().mapNotNull { it.image?.fileName } }.distinct()
    }

    /** Parses a backup array without touching the stored file; malformed entries are rejected, not guessed. */
    fun parseBackup(array: JSONArray, allowedImages: Set<String> = emptySet()): List<Conversation> {
        require(array.length() <= MAX_BACKUP_CONVERSATIONS) { "تعداد گفتگوهای پشتیبان بیش از حد مجاز است." }
        return (0 until array.length()).map { i ->
            val o = array.optJSONObject(i)
            // Stricter than the on-disk reader: a crafted backup must look like an exported conversation.
            val shaped = o != null && (o.opt("id") as? String)?.let { it.isNotBlank() && it.length <= 64 } == true && o.optJSONArray("messages") != null
            requireNotNull(o?.takeIf { shaped }?.let(::parse)) { "گفتگوی شمارهٔ ${i + 1} در پشتیبان معتبر نیست." }
        }
            .map { c -> c.copy(messages = c.messages.map { it.copy(image = it.image?.takeIf { ref -> ref.fileName in allowedImages }) }) }.distinctBy { it.id }
    }

    /** Replaces the whole history (restore never duplicates conversations) and reads it back. */
    fun replaceAllBlocking(list: List<Conversation>): Boolean = synchronized(lock) {
        writeAll(list.sortedByDescending { it.updatedAt })
        readAll().map { it.id }.toSet() == list.map { it.id }.toSet()
    }

    private fun readAll(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(list: List<Conversation>) {
        val arr = encode(list)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun encode(list: List<Conversation>): JSONArray {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("mode", c.mode.name); put("accountScope", c.accountScope); put("contextStart", c.contextStart.coerceIn(0, c.messages.size))
                put("id", c.id); put("title", c.title); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
                put("messages", JSONArray().also { ma ->
                    c.messages.forEach { m ->
                        ma.put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text).put("createdAt", m.createdAt).apply {
                            m.image?.let { image -> put("image", image.fileName) }
                        })
                    }
                })
            })
        }
        return arr
    }

    private fun parse(o: JSONObject?): Conversation? {
        if (o == null) return null
        val ma = o.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until ma.length()).mapNotNull { i ->
            val m = ma.optJSONObject(i) ?: return@mapNotNull null
            val role = runCatching { ChatRole.valueOf(m.optString("role")) }.getOrNull() ?: return@mapNotNull null
            ChatEntry(m.optString("id", UUID.randomUUID().toString()), role, AiTextSafety.redact(m.optString("text", "")), m.optLong("createdAt", 0L),
                m.optString("image").takeIf { it.isNotBlank() }?.let { runCatching { ChatImageRef(it) }.getOrNull() })
        }
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val mode = AiMode.fromStored(o.optString("mode", ""), absent = AiMode.LOCAL)
        return Conversation(id, AiTextSafety.redact(o.optString("title", "")), o.optLong("createdAt", 0L), o.optLong("updatedAt", 0L), msgs, mode, o.optString("accountScope", ""), o.optInt("contextStart", 0).coerceIn(0, msgs.size))
    }
}
