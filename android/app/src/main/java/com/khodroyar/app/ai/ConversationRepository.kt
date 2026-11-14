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
    private companion object { val lock = Any() }

    suspend fun load(): List<Conversation> = withContext(Dispatchers.IO) {
        synchronized(lock) { readAll() }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        synchronized(lock) {
            coroutine.ensureActive()
            val trimmedMessages = conversation.messages.takeLast(AiLimits.MAX_STORED_MESSAGES).map { it.copy(text = AiTextSafety.redact(it.text)) }
            val updated = conversation.copy(title = AiTextSafety.redact(conversation.title), messages = trimmedMessages)
            val all = readAll().filter { it.id != updated.id } + updated
            val kept = all.sortedByDescending { it.updatedAt }.take(AiLimits.MAX_STORED_CONVERSATIONS)
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
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("mode", c.mode.name)
                put("id", c.id); put("title", c.title); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
                put("messages", JSONArray().also { ma ->
                    c.messages.forEach { m ->
                        ma.put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text).put("createdAt", m.createdAt))
                    }
                })
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun parse(o: JSONObject?): Conversation? {
        if (o == null) return null
        val ma = o.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until ma.length()).mapNotNull { i ->
            val m = ma.optJSONObject(i) ?: return@mapNotNull null
            val role = runCatching { ChatRole.valueOf(m.optString("role")) }.getOrNull() ?: return@mapNotNull null
            ChatEntry(m.optString("id", UUID.randomUUID().toString()), role, AiTextSafety.redact(m.optString("text", "")), m.optLong("createdAt", 0L))
        }
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val mode = runCatching { AiMode.valueOf(o.optString("mode", "ONLINE")) }.getOrDefault(AiMode.ONLINE)
        return Conversation(id, AiTextSafety.redact(o.optString("title", "")), o.optLong("createdAt", 0L), o.optLong("updatedAt", 0L), msgs, mode)
    }
}
