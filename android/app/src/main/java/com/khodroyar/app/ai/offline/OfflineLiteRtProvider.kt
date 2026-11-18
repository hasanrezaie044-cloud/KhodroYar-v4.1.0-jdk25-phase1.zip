package com.khodroyar.app.ai.offline

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import com.google.ai.edge.litertlm.tool as liteRtTool
import com.khodroyar.app.ai.AIImage
import com.khodroyar.app.ai.AIToolExposure
import com.khodroyar.app.ai.AgentToolRegistry
import com.khodroyar.app.ai.AiErrorKind
import com.khodroyar.app.ai.AiResult
import com.khodroyar.app.ai.AIUsageLimits
import com.khodroyar.app.ai.FunctionCall
import com.khodroyar.app.ai.FunctionCallingProvider
import com.khodroyar.app.ai.FunctionMessage
import com.khodroyar.app.ai.FunctionResult
import com.khodroyar.app.ai.NativeToolProtocolIssue
import com.khodroyar.app.ai.NativeJson
import com.khodroyar.app.ai.ParamType
import com.khodroyar.app.ai.PersianAgentLimits
import com.khodroyar.app.ai.ToolParam
import com.khodroyar.app.ai.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Projects the existing vehicle tool registry into LiteRT-LM's *description-only* tool API.
 * LiteRT-LM has an automatic execution path; this adapter always disables it, and these
 * OpenApiTool executors throw rather than invoking an application action. PersianToolAgent
 * remains the sole route from a model call to validation, approval, and AppAgentTools.
 */
object OfflineToolSchemas {
    fun providers(specs: List<ToolSpec>): List<com.google.ai.edge.litertlm.ToolProvider> =
        specs.map { spec -> liteRtTool(DescriptionOnlyOpenApiTool(spec)) }

    internal class DescriptionOnlyOpenApiTool(private val spec: ToolSpec) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            val properties = JSONObject()
            spec.params.forEach { param ->
                properties.put(param.name, parameterSchema(param))
            }
            val required = JSONArray()
            spec.params.filter { it.required }.forEach { required.put(it.name) }
            return JSONObject()
                .put("name", spec.name)
                .put("description", spec.desc.take(500))
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required)
                    .put("additionalProperties", false))
                .toString()
        }

        /** Defensive tripwire: this must never execute; all real calls go through PersianToolAgent. */
        override fun execute(paramsJsonString: String): String =
            throw SecurityException("Offline model tool execution is app-owned and requires validation/approval")

        private fun parameterSchema(param: ToolParam): JSONObject {
            val type = when (param.type) {
                ParamType.NUMBER -> "number"
                ParamType.INT -> "integer"
                else -> "string"
            }
            return JSONObject().put("type", type).put("description", param.desc.take(240)).apply {
                if (param.options.isNotEmpty()) put("enum", JSONArray(param.options))
                if (param.type == ParamType.NUMBER || param.type == ParamType.INT) {
                    if (param.min != 0.0) put("minimum", param.min)
                    if (param.max != 1.0e12) put("maximum", param.max)
                }
            }
        }
    }
}

/**
 * On-device LiteRT-LM provider for the downloaded Gemma package. It has no network transport.
 * The model is read from app-private, no-backup storage after the install gate passes; initialized
 * demand-first; and released after an idle window or Android memory-pressure notification.
 */
class OfflineLiteRtProvider(
    context: Context,
    private val usage: () -> AIUsageLimits = { AIUsageLimits() },
) : OfflineChatEngine, ComponentCallbacks2, Closeable {
    private val app = context.applicationContext
    private val manager = OfflineModelManager.get(app)
    val store: OfflineModelStore get() = manager.store
    private val inferenceMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val turnSequence = AtomicLong()
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedWithVision: Boolean = false
    @Volatile private var activeConversation: Conversation? = null

    private val idleRelease = Runnable { lifecycleScope.launch { releaseModel() } }
    private val releaseHook: suspend () -> Unit = { releaseModel() }

    init {
        app.registerComponentCallbacks(this)
        manager.addReleaseHook(releaseHook)
    }

    /** Installed marker + exact size (full SHA-256 was computed when the download finished). */
    override val modelPresent: Boolean get() = store.state() is OfflineModelStore.State.Present
    val isLoaded: Boolean get() = engine?.isInitialized() == true

    override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult =
        inferenceMutex.withLock {
            handler.removeCallbacks(idleRelease)
            try {
                withContext(Dispatchers.IO) { infer(messages, onText) }
            } catch (cancelled: CancellationException) {
                cancel()
                throw cancelled
            } catch (_: OutOfMemoryError) {
                closeEngineLocked()
                FunctionResult.Failed(AiResult.Failure(
                    AiErrorKind.MODEL_INVALID,
                    "حافظهٔ کافی برای اجرای مدل محلی در دسترس نیست؛ مدل آزاد شد و هیچ درخواست آنلاین ارسال نشد.",
                ))
            } catch (_: LinkageError) {
                closeEngineLocked()
                FunctionResult.Failed(AiResult.Failure(
                    AiErrorKind.MODEL_INVALID,
                    "کتابخانهٔ محلی سازگار در این دستگاه در دسترس نیست؛ اجرای مدل متوقف شد و هیچ درخواست آنلاین ارسال نشد.",
                ))
            } catch (_: Exception) {
                FunctionResult.Failed(AiResult.Failure(
                    AiErrorKind.MODEL_INVALID,
                    "اجرای مدل آفلاین کامل نشد. مدل یا بستهٔ runtime را بررسی کنید؛ هیچ fallback شبکه‌ای انجام نشد.",
                ))
            } finally {
                scheduleIdleRelease()
            }
        }

    private suspend fun infer(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
        val transcript = validateTranscript(messages)
            ?: return failure("ساختار گفتگوی محلی معتبر نیست؛ هیچ ابزاری اجرا نشد.")
        val model = store.modelFile
        if (!store.verifyInstalled()) {
            return failure("مدل آفلاین نصب نیست یا فایل آن کامل نیست؛ از «مدیریت هوش مصنوعی آفلاین» آن را دانلود کنید. هیچ درخواست آنلاینی ارسال نشد.")
        }
        val imageRequested = transcript.any { it.images.isNotEmpty() }
        val modelEngine = try {
            ensureEngine(model, imageRequested)
        } catch (_: OutOfMemoryError) {
            closeEngineLocked()
            return failure("حافظهٔ کافی برای راه‌اندازی مدل محلی در دسترس نیست.")
        } catch (_: Exception) {
            closeEngineLocked()
            return failure("بارگذاری مدل آفلاین ناموفق بود؛ اگر تکرار شد «دانلود دوباره» را بزنید. هیچ درخواست آنلاینی ارسال نشد.")
        }

        val system = transcript.firstOrNull()?.takeIf { it.role == "system" }
            ?: return failure("دستورالعمل محلی موجود نیست؛ هیچ داده‌ای ارسال نشد.")
        val bodyMessages = transcript.drop(1)
        val last = bodyMessages.lastOrNull()
            ?: return failure("پیام کاربر وجود ندارد.")
        val tools = OfflineToolSchemas.providers(AIToolExposure.tools(transcript, usage().toolsEnabled))
        val nativeHistory = bodyMessages.dropLast(1).map { toLiteRtMessage(it, bodyMessages) }
        val nativeLast = toLiteRtMessage(last, bodyMessages)
        val config = ConversationConfig(
            systemInstruction = Contents.of(system.content),
            initialMessages = nativeHistory,
            tools = tools,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2),
            automaticToolCalling = false,
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )

        var finalMessage: Message? = null
        val output = StringBuilder()
        val conversation = modelEngine.createConversation(config)
        activeConversation = conversation
        try {
            conversation.sendMessageAsync(
                nativeLast,
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            ).collect { chunk ->
                finalMessage = chunk
                val delta = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (delta.isNotEmpty()) {
                    if (output.length + delta.length > PersianAgentLimits.OUTPUT_CHARS) {
                        conversation.cancelProcess()
                        throw IllegalStateException("local output limit exceeded")
                    }
                    output.append(delta)
                    onText(output.toString())
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw cancelled
        } finally {
            activeConversation = null
            runCatching { conversation.close() }
        }

        val response = finalMessage ?: return failure("مدل محلی پاسخ نداد.")
        if (response.toolCalls.size > MAX_TOOL_CALLS_PER_REPLY) {
            return protocolFailure("تعداد فراخوانی ابزار مدل از سقف امن بیشتر بود؛ هیچ ابزاری اجرا نشد.")
        }
        val turnId = transcript.firstOrNull()?.turnId?.takeIf { it.length <= 128 }
            ?: "local-${turnSequence.incrementAndGet()}-${UUID.randomUUID()}"
        val calls = response.toolCalls.mapIndexed { index, call ->
            val encodedArgs = JSONObject().apply { call.arguments.forEach { (key, value) -> put(key, value) } }.toString()
            if (call.name.length !in 1..100 || encodedArgs.length > PersianAgentLimits.ARGUMENT_CHARS) {
                return protocolFailure("نام یا آرگومان ابزار پاسخ محلی نامعتبر/بزرگ است؛ هیچ ابزاری اجرا نشد.")
            }
            FunctionCall("$turnId-$index-${UUID.randomUUID()}", call.name, encodedArgs)
        }
        if (output.isBlank() && calls.isEmpty()) return failure("پاسخ مدل محلی خالی بود.")
        return FunctionResult.Reply(FunctionMessage("assistant", output.toString(), calls), store.spec.id)
    }

    private fun validateTranscript(messages: List<FunctionMessage>): List<FunctionMessage>? {
        if (messages.isEmpty() || messages.size > MAX_TRANSCRIPT_MESSAGES) return null
        if (messages.sumOf { it.content.length } > MAX_TRANSCRIPT_CHARS) return null
        if (messages.any { it.content.length > PersianAgentLimits.OUTPUT_CHARS }) return null
        if (messages.count { it.role == "system" } != 1 || messages.firstOrNull()?.role != "system") return null
        if (messages.drop(1).any { it.role !in setOf("user", "assistant", "tool") }) return null
        val imageMessages = messages.withIndex().filter { it.value.images.isNotEmpty() }
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (imageMessages.size > 1 || imageMessages.any { it.index != lastUserIndex || it.value.role != "user" || it.value.images.size != 1 }) return null
        return messages
    }

    private fun toLiteRtMessage(message: FunctionMessage, transcript: List<FunctionMessage>): Message = when (message.role) {
        "user" -> {
            val content = ArrayList<Content>(1 + message.images.size)
            message.images.forEach { image ->
                require(image.mimeType in SUPPORTED_IMAGE_MIME_TYPES)
                val bytes = Base64.decode(image.base64, Base64.DEFAULT)
                require(bytes.size in 1..MAX_IMAGE_BYTES)
                content += Content.ImageBytes(bytes)
            }
            if (message.content.isNotEmpty()) content += Content.Text(message.content)
            require(content.isNotEmpty())
            Message.user(Contents.of(content))
        }
        "assistant" -> {
            val toolCalls = message.calls.map { call ->
                val args = NativeJson.objectValue(call.arguments, PersianAgentLimits.ARGUMENT_CHARS)
                LiteRtToolCall(call.name, jsonObjectToMap(args))
            }
            Message.model(Contents.of(message.content), toolCalls)
        }
        "tool" -> {
            val callId = message.toolCallId ?: throw IllegalArgumentException("missing local call identity")
            val toolName = transcript.asSequence().flatMap { it.calls.asSequence() }.firstOrNull { it.id == callId }?.name
                ?: throw IllegalArgumentException("unmatched local tool response")
            val result = NativeJson.objectValue(message.content, PersianAgentLimits.TOOL_RESULT_CHARS + 256)
            Message.tool(Contents.of(Content.ToolResponse(toolName, jsonObjectToMap(result))))
        }
        else -> throw IllegalArgumentException("unsupported local role")
    }

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key ->
        jsonToValue(value.get(key))
    }

    private fun jsonToValue(value: Any?): Any? = when {
        value == null || value === JSONObject.NULL -> null
        value is JSONObject -> jsonObjectToMap(value)
        value is JSONArray -> (0 until value.length()).map { jsonToValue(value.get(it)) }
        else -> value
    }

    private fun ensureEngine(model: File, vision: Boolean): Engine {
        val path = model.absolutePath
        val existing = engine
        if (existing?.isInitialized() == true && loadedPath == path && (!vision || loadedWithVision)) return existing
        closeEngineLocked()
        val memory = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw IllegalStateException("memory manager unavailable")
        val info = ActivityManager.MemoryInfo()
        memory.getMemoryInfo(info)
        if (info.lowMemory) throw OutOfMemoryError("Android reports low memory")

        File(app.cacheDir, LITERT_CACHE_DIRECTORY).apply { mkdirs() }
        val created = Engine(EngineConfig(
            modelPath = path,
            backend = Backend.CPU(),
            visionBackend = if (vision) Backend.CPU() else null,
            maxNumTokens = MAX_CONTEXT_TOKENS,
            maxNumImages = if (vision) 1 else null,
            cacheDir = File(app.cacheDir, LITERT_CACHE_DIRECTORY).absolutePath,
        ))
        try {
            created.initialize()
        } catch (failure: Throwable) {
            runCatching { created.close() }
            throw failure
        }
        engine = created
        loadedPath = path
        loadedWithVision = vision
        return created
    }

    private fun closeEngineLocked() {
        val old = engine
        engine = null
        loadedPath = null
        loadedWithVision = false
        if (old?.isInitialized() == true) runCatching { old.close() }
    }

    private fun failure(message: String) = FunctionResult.Failed(AiResult.Failure(AiErrorKind.MODEL_INVALID, message))
    private fun protocolFailure(message: String) = FunctionResult.Failed(AiResult.Failure(AiErrorKind.BAD_REQUEST, message))

    fun cancel() {
        activeConversation?.let { conversation -> runCatching { conversation.cancelProcess() } }
    }

    override fun cancelActive() = cancel()

    private fun scheduleIdleRelease() {
        if (closed.get()) return
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    suspend fun releaseModel() {
        handler.removeCallbacks(idleRelease)
        inferenceMutex.withLock { withContext(Dispatchers.IO) { closeEngineLocked() } }
    }

    private fun releaseAsync() {
        lifecycleScope.launch { releaseModel() }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseAsync()
    }

    override fun onLowMemory() = releaseAsync()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacks(idleRelease)
        runCatching { app.unregisterComponentCallbacks(this) }
        manager.removeReleaseHook(releaseHook)
        cancel()
        lifecycleScope.launch {
            try { releaseModel() } finally { lifecycleScope.cancel() }
        }
    }

    companion object {
        private const val LITERT_CACHE_DIRECTORY = "litertlm"
        const val MAX_CONTEXT_TOKENS = 4096
        const val MAX_OUTPUT_TOKENS = 512
        private const val MAX_TRANSCRIPT_MESSAGES = 32
        private const val MAX_TRANSCRIPT_CHARS = 48 * 1024
        private const val MAX_TOOL_CALLS_PER_REPLY = 8
        private const val MAX_IMAGE_BYTES = 18 * 1024 * 1024
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L
        private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
