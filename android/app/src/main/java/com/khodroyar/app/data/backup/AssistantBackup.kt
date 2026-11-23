package com.khodroyar.app.data.backup

import android.content.Context
import com.khodroyar.app.ai.AIProviderRegistry
import com.khodroyar.app.ai.AISelectionMode
import com.khodroyar.app.ai.AgentTask
import com.khodroyar.app.ai.AgentTaskCodec
import com.khodroyar.app.ai.AiMode
import com.khodroyar.app.ai.AiSettingsStore
import com.khodroyar.app.ai.AiTextSafety
import com.khodroyar.app.ai.AssistantAgentTools
import com.khodroyar.app.ai.AssistantMemoryStore
import com.khodroyar.app.ai.Conversation
import com.khodroyar.app.ai.ConversationRepository
import com.khodroyar.app.ai.MemoryFact
import com.khodroyar.app.ai.OpenRouterApiKeyStore
import com.khodroyar.app.ai.ProviderApiKeyStore
import com.khodroyar.app.automation.AutomationCodec
import com.khodroyar.app.automation.AutomationRule
import com.khodroyar.app.automation.AutomationStore
import org.json.JSONObject

/**
 * The assistant part of a full backup: conversations (v2, 5.9.0: with their verified chat images,
 * see [ChatImageBackup]), long-term memory, the agent task
 * log, automation rules and non-secret AI preferences. API keys, consent and account scope are never
 * written. Everything is parsed and validated before the restore touches any existing data.
 */
object AssistantBackup {
    const val KEY = "assistant"
    const val VERSION = 2

    data class Parsed(
        val conversations: List<Conversation>?, val memory: List<MemoryFact>?, val tasks: List<AgentTask>?,
        val automations: List<AutomationRule>?, val ai: JSONObject?,
        val images: List<ChatImageBackup.Image> = emptyList(),
        /** Images the source device could not include (file gone / over the size budget). */
        val imagesMissingAtExport: Int = 0,
    ) {
        val counts: IntArray get() = intArrayOf(conversations?.size ?: 0, memory?.size ?: 0, tasks?.size ?: 0, automations?.size ?: 0)
    }

    data class Applied(val conversations: Int, val memory: Int, val tasks: Int, val automations: Int, val failures: List<String>, val images: Int = 0)

    fun export(context: Context): String {
        val app = context.applicationContext
        val settings = AiSettingsStore(app).load()
        val ai = JSONObject().put("enabled", settings.enabled).put("mode", settings.mode.name)
            .put("openRouterModel", settings.openRouterModel).put("modelSelectionMode", settings.modelSelectionMode.name)
            .put("allowModelFallback", settings.allowModelFallback)
            .put("groqModel", settings.modelFor(AiMode.GROQ))
        val rules = runCatching { AutomationStore.get(app).rules.value }.getOrDefault(emptyList())
        val conversations = ConversationRepository(app)
        val imageStore = com.khodroyar.app.ai.ChatImageStore(app)
        val packed = ChatImageBackup.pack(
            conversations.imageRefsForBackup(),
            read = { name -> imageStore.file(com.khodroyar.app.ai.ChatImageRef(name)).takeIf { it.isFile }?.readBytes() },
            encode = { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) },
        )
        val root = JSONObject().put("version", VERSION)
            .put("conversations", conversations.exportForBackup(keepImages = packed.included))
            .put("memory", AssistantMemoryStore.encode(AssistantAgentTools.memoryFor(app).all()))
            .put("agentTasks", AgentTaskCodec.encode(AssistantAgentTools.tasksFor(app).all()))
            .put("automations", JSONObject(AutomationCodec.encode(rules)))
            .put("ai", ai)
            .put("keysIncluded", false)
            .put("chatImagesMissing", packed.missing + packed.omitted)
        // Defence in depth: a credential-looking token never leaves the device in a backup. The pattern
        // only spans [A-Za-z0-9_-], so the replacement stays inside its JSON string. Image data is
        // attached AFTER redaction: base64 can contain "AIza..." runs, and redacting them would
        // corrupt the image (its SHA-256 would then reject the whole restore).
        val redacted = JSONObject(AiTextSafety.redact(root.toString()))
        return redacted.put("chatImages", packed.array).toString()
    }

    /** Lightweight read of the export-time missing chat image count (no full JSON parse). */
    fun missingImageCount(backupJson: String): Int {
        val key = "\"chatImagesMissing\""
        val at = backupJson.indexOf(key).takeIf { it >= 0 } ?: return 0
        val colon = backupJson.indexOf(':', at + key.length).takeIf { it >= 0 } ?: return 0
        return backupJson.substring(colon + 1).trimStart().takeWhile { it.isDigit() }.take(6).toIntOrNull() ?: 0
    }

    /** Null when the backup predates assistant data (old backups keep the current assistant data). */
    fun parse(context: Context, assistant: JSONObject?): Parsed? {
        if (assistant == null) return null
        val version = assistant.optInt("version", -1)
        require(version in 1..VERSION) { "بخش دستیار این پشتیبان با نسخهٔ جدیدتری ساخته شده یا معتبر نیست." }
        require(!assistant.optBoolean("keysIncluded", false)) { "پشتیبان ادعای داشتن کلید دارد؛ برای امنیت پذیرفته نمی‌شود." }
        val images = if (version >= 2) ChatImageBackup.unpack(assistant.optJSONArray("chatImages")) {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        } else emptyList()
        val conversations = assistant.optJSONArray("conversations")?.let { ConversationRepository(context).parseBackup(it, images.map { i -> i.fileName }.toSet()) }
        val memory = assistant.optJSONArray("memory")?.let { array ->
            require(array.length() <= 1000) { "حافظهٔ پشتیبان بیش از حد مجاز است." }
            AssistantMemoryStore.decode(array).mapNotNull { f -> AssistantMemoryStore.sanitize(f.text)?.let { f.copy(text = it) } }
        }
        val tasks = assistant.optJSONObject("agentTasks")?.let(AgentTaskCodec::decode)
        val automations = assistant.optJSONObject("automations")?.let { AutomationCodec.decode(it.toString()) }
        // Images are only meaningful with their conversations; an orphan image is not restored.
        val referenced = conversations.orEmpty().flatMap { c -> c.messages.mapNotNull { it.image?.fileName } }.toSet()
        return Parsed(conversations, memory, tasks, automations, assistant.optJSONObject("ai"),
            images.filter { it.fileName in referenced }, assistant.optInt("chatImagesMissing", 0).coerceAtLeast(0))
    }

    /**
     * Applied after the database restore committed. Each part is replaced (never merged, so a
     * restore cannot duplicate) and read back; a failing part is reported, never claimed.
     */
    suspend fun apply(context: Context, parsed: Parsed): Applied {
        val app = context.applicationContext
        val failures = mutableListOf<String>()
        fun part(name: String, block: () -> Boolean) { if (!runCatching(block).getOrDefault(false)) failures += name }
        parsed.conversations?.let { list -> part("گفتگوها") { ConversationRepository(app).replaceAllBlocking(list) } }
        // After the conversations: a concurrent prune then only sees referenced files and deletes nothing.
        var restoredImages = 0
        if (parsed.images.isNotEmpty() && "گفتگوها" !in failures) {
            val bad = runCatching { com.khodroyar.app.ai.ChatImageStore(app).restoreVerified(parsed.images) }.getOrDefault(parsed.images.map { it.fileName })
            restoredImages = parsed.images.size - bad.size
            if (bad.isNotEmpty()) failures += "${bad.size} تصویر گفتگو"
        }
        if (parsed.imagesMissingAtExport > 0) failures += "${parsed.imagesMissingAtExport} تصویر گفتگو که در پشتیبان نبود (پیدا نشد یا از سقف حجم بیشتر بود)"
        parsed.memory?.let { list -> part("حافظه") { AssistantAgentTools.memoryFor(app).replaceAll(list) } }
        parsed.tasks?.let { list -> part("کارهای دستیار") { AssistantAgentTools.tasksFor(app).replaceAll(list) } }
        parsed.automations?.let { list ->
            // Rules come back paused; reactivating an unattended write needs the owner again.
            val paused = list.map { it.copy(enabled = false, revision = it.revision + 1) }
            val ok = runCatching { AutomationStore.get(app).change { paused }.map { it.id }.toSet() == paused.map { it.id }.toSet() }.getOrDefault(false)
            if (!ok) failures += "کارهای خودکار"
        }
        parsed.ai?.let { ai -> part("تنظیمات دستیار") { restoreAiSettings(app, ai); true } }
        return Applied(parsed.conversations?.size ?: 0, parsed.memory?.size ?: 0, parsed.tasks?.size ?: 0, parsed.automations?.size ?: 0, failures, restoredImages)
    }

    private fun restoreAiSettings(context: Context, ai: JSONObject) {
        val store = AiSettingsStore(context)
        val current = store.load()
        val mode = AiMode.fromStored(ai.optString("mode", ""), absent = current.mode)
        var next = current.copy(
            enabled = ai.optBoolean("enabled", current.enabled), mode = mode,
            // Consent is per device and per key: a restored connection must be accepted again.
            consented = false,
            openRouterModel = ai.optString("openRouterModel").takeIf { it.isNotBlank() && it.length <= 200 } ?: current.openRouterModel,
            modelSelectionMode = AISelectionMode.entries.firstOrNull { it.name == ai.optString("modelSelectionMode") } ?: current.modelSelectionMode,
            allowModelFallback = ai.optBoolean("allowModelFallback", current.allowModelFallback),
        )
        ai.optString("groqModel").takeIf { it.isNotBlank() && it.length <= 200 }?.let { next = next.withModel(AiMode.GROQ, it) }
        store.save(next)
    }

    /** Honest post-restore line: keys are never in a backup; says whether the selected connection needs one again. */
    fun keyStatusMessage(context: Context): String {
        val app = context.applicationContext
        val mode = AiSettingsStore(app).load().mode
        val base = "کلیدهای API هرگز در پشتیبان نیستند."
        if (mode == AiMode.LOCAL) return "$base دستیار آفلاین به کلید نیاز ندارد."
        val present = runCatching { if (mode == AiMode.OPENROUTER) OpenRouterApiKeyStore(app).read() else ProviderApiKeyStore(app, mode).read() }.getOrNull() != null
        val name = AIProviderRegistry.get(mode).title
        return if (present) "$base کلید $name روی همین دستگاه از قبل موجود است و تغییری نکرد؛ فقط رضایت اتصال دوباره پرسیده می‌شود."
        else "$base برای استفاده از $name، کلید را دوباره در تنظیمات دستیار وارد کنید."
    }
}
