package com.khodroyar.app.ai.offline

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.khodroyar.app.ai.AIToolExposure
import com.khodroyar.app.ai.AIUsageLimits
import com.khodroyar.app.ai.AiErrorKind
import com.khodroyar.app.ai.AiResult
import com.khodroyar.app.ai.FunctionCall
import com.khodroyar.app.ai.FunctionMessage
import com.khodroyar.app.ai.FunctionResult
import com.khodroyar.app.ai.NativeJson
import com.khodroyar.app.ai.PersianAgentLimits
import com.khodroyar.app.ai.offline.llama.LlamaNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Measured facts of the last on-device run (shown in «مدیریت هوش مصنوعی آفلاین», logged without content). */
data class OfflineRunStats(
    val loadMs: Long? = null,
    val ttftMs: Long = 0,
    val promptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val prefillMs: Long = 0,
    val imageMs: Long = 0,
    val generatedTokens: Int = 0,
    val decodeTokensPerSecond: Double = 0.0,
    val rssBytes: Long = -1,
    val peakRssBytes: Long = -1,
)

/**
 * On-device Qwen3.5 provider over official llama.cpp (JNI, CPU). Same contract as the LiteRT-LM
 * provider: no network, PersianToolAgent stays the only path from a model tool call to validation,
 * approval and AppAgentTools. Images reach the vision encoder as real RGB pixels; the language model
 * never receives a file name or a description in place of the image.
 */
class OfflineLlamaProvider(
    context: Context,
    private val usage: () -> AIUsageLimits = { AIUsageLimits() },
) : OfflineChatEngine, ComponentCallbacks2 {
    private val app = context.applicationContext
    private val manager = OfflineModelManager.get(app)
    private val inferenceMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val turnSequence = AtomicLong()
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var handle: Long = 0L
    @Volatile private var loadedPath: String? = null
    @Volatile private var visionAttached = false

    private val _stats = MutableStateFlow<OfflineRunStats?>(null)
    val stats: StateFlow<OfflineRunStats?> = _stats.asStateFlow()

    private val idleRelease = Runnable { lifecycleScope.launch { releaseModel() } }
    private val releaseHook: suspend () -> Unit = { releaseModel() }

    init {
        app.registerComponentCallbacks(this)
        manager.addReleaseHook(releaseHook)
    }

    override val modelPresent: Boolean get() = manager.modelReady

    override suspend fun complete(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult =
        inferenceMutex.withLock {
            handler.removeCallbacks(idleRelease)
            try {
                withContext(Dispatchers.IO) { infer(messages, onText) }
            } catch (cancelled: CancellationException) {
                cancel()
                throw cancelled
            } catch (_: OutOfMemoryError) {
                closeLocked()
                failure("حافظهٔ کافی برای اجرای مدل محلی در دسترس نیست؛ مدل آزاد شد و هیچ درخواست آنلاین ارسال نشد.")
            } catch (_: LinkageError) {
                closeLocked()
                failure("کتابخانهٔ محلی llama.cpp در این دستگاه در دسترس نیست؛ اجرای مدل متوقف شد و هیچ درخواست آنلاین ارسال نشد.")
            } catch (_: Exception) {
                failure("اجرای مدل آفلاین کامل نشد. فایل‌های مدل را بررسی کنید؛ هیچ fallback شبکه‌ای انجام نشد.")
            } finally {
                scheduleIdleRelease()
            }
        }

    private fun infer(messages: List<FunctionMessage>, onText: (String) -> Unit): FunctionResult {
        val transcript = validateTranscript(messages)
            ?: return failure("ساختار گفتگوی محلی معتبر نیست؛ هیچ ابزاری اجرا نشد.")
        if (!manager.stores.all { it.verifyInstalled() }) {
            return failure("فایل‌های مدل آفلاین کامل نیستند؛ از «مدیریت هوش مصنوعی آفلاین» آن‌ها را دانلود کنید. هیچ درخواست آنلاینی ارسال نشد.")
        }
        val imageRequested = transcript.any { it.images.isNotEmpty() }
        val engine = try {
            ensureEngine(imageRequested)
        } catch (lowMemory: OutOfMemoryError) {
            closeLocked()
            return failure(lowMemory.message ?: "حافظهٔ کافی برای راه‌اندازی مدل محلی در دسترس نیست.")
        }
        if (engine == 0L) {
            closeLocked()
            return failure("بارگذاری مدل آفلاین ناموفق بود؛ اگر تکرار شد «دانلود دوباره» را بزنید. هیچ درخواست آنلاینی ارسال نشد.")
        }
        if (imageRequested && !visionAttached) {
            return failure("بخش بینایی مدل (mmproj) بارگذاری نشد؛ تصویر پردازش نشد و هیچ درخواست آنلاینی ارسال نشد.")
        }

        val pixels = transcript.flatMap { it.images }.map { decodeImage(it.base64) }
        val request = buildRequest(transcript)
        val output = StringBuilder()
        val resultText = LlamaNative.chat(
            engine,
            request.toString(),
            pixels.map { it.rgb }.toTypedArray(),
            pixels.map { it.width }.toIntArray(),
            pixels.map { it.height }.toIntArray(),
            pixels.map { it.id }.toTypedArray(),
        ) { text ->
            if (text.length > PersianAgentLimits.OUTPUT_CHARS) return@chat false
            output.setLength(0)
            output.append(text)
            onText(text)
            true
        }
        val result = JSONObject(resultText)
        recordStats(result.optJSONObject("timings"))
        if (!result.optBoolean("ok")) {
            return when (result.optString("error")) {
                "aborted" -> failure("اجرای مدل محلی لغو شد.")
                "context_full" -> failure("گفتگو برای حافظهٔ متنی مدل محلی بیش از حد طولانی است؛ گفتگوی تازه‌ای شروع کنید.")
                "vision_not_loaded", "image_encode_failed", "image_decode_failed", "bad_image" ->
                    failure("تصویر توسط بخش بینایی مدل محلی پردازش نشد؛ هیچ درخواست آنلاینی ارسال نشد.")
                else -> failure("اجرای مدل آفلاین کامل نشد (${result.optString("error").take(40)}). هیچ fallback شبکه‌ای انجام نشد.")
            }
        }
        val content = result.optString("content")
        val rawCalls = result.optJSONArray("tool_calls") ?: JSONArray()
        if (rawCalls.length() > MAX_TOOL_CALLS_PER_REPLY) {
            return protocolFailure("تعداد فراخوانی ابزار مدل از سقف امن بیشتر بود؛ هیچ ابزاری اجرا نشد.")
        }
        val turnId = transcript.firstOrNull()?.turnId?.takeIf { it.length <= 128 }
            ?: "local-${turnSequence.incrementAndGet()}-${UUID.randomUUID()}"
        val calls = (0 until rawCalls.length()).map { index ->
            val call = rawCalls.getJSONObject(index)
            val name = call.optString("name")
            val args = call.optString("arguments").ifBlank { "{}" }
            val normalized = runCatching { JSONObject(args).toString() }.getOrNull()
            if (name.length !in 1..100 || normalized == null || normalized.length > PersianAgentLimits.ARGUMENT_CHARS) {
                return protocolFailure("نام یا آرگومان ابزار پاسخ محلی نامعتبر/بزرگ است؛ هیچ ابزاری اجرا نشد.")
            }
            FunctionCall("$turnId-$index-${UUID.randomUUID()}", name, normalized)
        }
        if (content.isBlank() && calls.isEmpty()) return failure("پاسخ مدل محلی خالی بود.")
        return FunctionResult.Reply(FunctionMessage("assistant", content, calls), manager.pkg.model.id)
    }

    /** OpenAI-style transcript; tool schemas are byte-identical to what the LiteRT-LM path and the evaluation use. */
    private fun buildRequest(transcript: List<FunctionMessage>): JSONObject {
        val messages = JSONArray()
        transcript.forEach { message ->
            when (message.role) {
                "system" -> messages.put(JSONObject().put("role", "system").put("content", message.content))
                "user" -> {
                    val content: Any = if (message.images.isEmpty()) message.content else JSONArray().apply {
                        message.images.forEach { _ -> put(JSONObject().put("type", "media_marker").put("text", mediaMarker)) }
                        if (message.content.isNotEmpty()) put(JSONObject().put("type", "text").put("text", message.content))
                    }
                    messages.put(JSONObject().put("role", "user").put("content", content))
                }
                "assistant" -> messages.put(JSONObject().put("role", "assistant").put("content", message.content).apply {
                    if (message.calls.isNotEmpty()) put("tool_calls", JSONArray().apply {
                        message.calls.forEach { call ->
                            val args = NativeJson.objectValue(call.arguments, PersianAgentLimits.ARGUMENT_CHARS)
                            put(JSONObject().put("id", call.id).put("type", "function")
                                .put("function", JSONObject().put("name", call.name).put("arguments", args.toString())))
                        }
                    })
                })
                "tool" -> {
                    val callId = message.toolCallId ?: throw IllegalArgumentException("missing local call identity")
                    require(transcript.any { m -> m.calls.any { it.id == callId } }) { "unmatched local tool response" }
                    val result = NativeJson.objectValue(message.content, PersianAgentLimits.TOOL_RESULT_CHARS + 256)
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", result.toString()))
                }
                else -> throw IllegalArgumentException("unsupported local role")
            }
        }
        val tools = JSONArray()
        AIToolExposure.tools(transcript, usage().toolsEnabled).forEach { spec ->
            tools.put(JSONObject().put("type", "function")
                .put("function", JSONObject(OfflineToolSchemas.DescriptionOnlyOpenApiTool(spec).getToolDescriptionJsonString())))
        }
        return JSONObject()
            .put("messages", messages)
            .apply { if (tools.length() > 0) put("tools", tools) }
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("temperature", 0.2)
            .put("top_k", 40)
            .put("top_p", 0.9)
            .put("enable_thinking", false)
    }

    private class Pixels(val rgb: ByteArray, val width: Int, val height: Int, val id: String)

    /** JPEG/PNG/WebP/HEIF bytes -> upright RGB888, longest side <= [MAX_IMAGE_SIDE]; libmtmd then sizes it for the encoder. */
    private fun decodeImage(base64: String): Pixels {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        require(bytes.size in 1..MAX_IMAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalArgumentException("undecodable image")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
            val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) postScale(scale, scale)
        }
        val upright = if (matrix.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        try {
            val w = upright.width
            val h = upright.height
            val argb = IntArray(w * h)
            upright.getPixels(argb, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            var o = 0
            for (p in argb) {
                rgb[o++] = (p shr 16 and 0xFF).toByte()
                rgb[o++] = (p shr 8 and 0xFF).toByte()
                rgb[o++] = (p and 0xFF).toByte()
            }
            val id = MessageDigest.getInstance("SHA-256").digest(rgb).joinToString("") { "%02x".format(it) }
            return Pixels(rgb, w, h, id)
        } finally {
            upright.recycle()
        }
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
        if (messages.any { m -> m.images.any { it.mimeType !in SUPPORTED_IMAGE_MIME_TYPES } }) return null
        return messages
    }

    private fun ensureEngine(vision: Boolean): Long {
        val model = manager.store.modelFile.absolutePath
        if (handle != 0L && loadedPath == model) {
            if (vision && !visionAttached) attachVision()
            return handle
        }
        closeLocked()
        val memory = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw IllegalStateException("memory manager unavailable")
        val info = ActivityManager.MemoryInfo()
        memory.getMemoryInfo(info)
        if (info.lowMemory || info.availMem < MIN_AVAILABLE_BYTES) {
            throw OutOfMemoryError("حافظهٔ آزاد گوشی برای مدل محلی کافی نیست (${info.availMem / (1024 * 1024)} مگابایت آزاد). برنامه‌های دیگر را ببندید.")
        }
        val diagnostics = backends()
        val started = System.nanoTime()
        val created = LlamaNative.load(model, CONTEXT_TOKENS, threads(), BATCH_TOKENS, false)
        if (created == 0L) return 0L
        handle = created
        loadedPath = model
        val loadMs = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "model loaded in ${loadMs}ms; ${LlamaNative.info(created)}; backends=${diagnostics.optJSONArray("devices")}")
        _stats.value = (_stats.value ?: OfflineRunStats()).copy(loadMs = loadMs)
        if (vision) attachVision()
        return created
    }

    private fun attachVision() {
        val mmproj = manager.visionStore?.modelFile ?: return
        val started = System.nanoTime()
        visionAttached = LlamaNative.attachVision(handle, mmproj.absolutePath, IMAGE_MAX_TOKENS)
        Log.i(TAG, "vision attached=$visionAttached in ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    private fun recordStats(timings: JSONObject?) {
        timings ?: return
        val (rss, peak) = processMemory()
        val stats = OfflineRunStats(
            loadMs = _stats.value?.loadMs,
            ttftMs = timings.optDouble("ttft_ms", 0.0).toLong(),
            promptTokens = timings.optInt("prompt_tokens"),
            cachedTokens = timings.optInt("cached_tokens"),
            prefillMs = timings.optDouble("prefill_ms", 0.0).toLong(),
            imageMs = timings.optDouble("image_ms", 0.0).toLong(),
            generatedTokens = timings.optInt("generated_tokens"),
            decodeTokensPerSecond = timings.optDouble("decode_tps", 0.0),
            rssBytes = rss,
            peakRssBytes = peak,
        )
        _stats.value = stats
        Log.i(TAG, "run: $stats checkpoints=${timings.optInt("checkpoints")} ckptBytes=${timings.optLong("checkpoint_bytes")}")
    }

    fun cancel() { handle.takeIf { it != 0L }?.let { LlamaNative.abort(it) } }
    override fun cancelActive() = cancel()

    private fun closeLocked() {
        val old = handle
        handle = 0L
        loadedPath = null
        visionAttached = false
        if (old != 0L) runCatching { LlamaNative.free(old) }
    }

    private fun scheduleIdleRelease() {
        if (closed.get()) return
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    suspend fun releaseModel() {
        handler.removeCallbacks(idleRelease)
        cancel()
        inferenceMutex.withLock { withContext(Dispatchers.IO) { closeLocked() } }
    }

    private fun releaseAsync() { lifecycleScope.launch { releaseModel() } }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseAsync()
    }

    @Deprecated("Deprecated in Android")
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

    private fun failure(message: String) = FunctionResult.Failed(AiResult.Failure(AiErrorKind.MODEL_INVALID, message))
    private fun protocolFailure(message: String) = FunctionResult.Failed(AiResult.Failure(AiErrorKind.BAD_REQUEST, message))

    private fun backends(): JSONObject = synchronized(OfflineLlamaProvider::class.java) {
        cachedBackends ?: run {
            System.loadLibrary("khodroyar_llama")
            val dir = app.applicationInfo.nativeLibraryDir ?: throw UnsatisfiedLinkError("no native library dir")
            JSONObject(LlamaNative.initBackends(dir)).also {
                cachedBackends = it
                mediaMarker = it.optString("media_marker", "<__media__>")
                Log.i(TAG, "llama.cpp backends: $it")
            }
        }
    }

    companion object {
        private const val TAG = "KhodroYarLlama"
        @Volatile private var cachedBackends: JSONObject? = null
        @Volatile private var mediaMarker: String = "<__media__>"

        /** Context for system prompt + exposed tool schemas + one image (<= [IMAGE_MAX_TOKENS]) + a short chat. */
        const val CONTEXT_TOKENS = 8192
        const val BATCH_TOKENS = 512
        /** Vision token budget per image: enough for small Persian print on receipts; bounded for an 8 GB phone. */
        const val IMAGE_MAX_TOKENS = 1024
        const val MAX_IMAGE_SIDE = 1536
        const val MAX_OUTPUT_TOKENS = 512
        private const val MIN_AVAILABLE_BYTES = 1_200L * 1024 * 1024
        private const val MAX_TRANSCRIPT_MESSAGES = 32
        private const val MAX_TRANSCRIPT_CHARS = 48 * 1024
        private const val MAX_TOOL_CALLS_PER_REPLY = 8
        private const val MAX_IMAGE_BYTES = 18 * 1024 * 1024
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L
        private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        /**
         * Big cores only: Exynos 2200 = 1x Cortex-X2 + 3x A710 + 4x A510. Matching llama.cpp's Android example:
         * clamp(cores - 2, 2, 4). Spreading over the little cores makes every step wait for the slowest thread.
         */
        fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

        /** VmRSS / VmHWM of this process from /proc (bytes), -1 if unavailable. */
        fun processMemory(): Pair<Long, Long> = runCatching {
            var rss = -1L
            var hwm = -1L
            File("/proc/self/status").forEachLine { line ->
                if (line.startsWith("VmRSS:")) rss = line.filter { it.isDigit() }.toLong() * 1024
                if (line.startsWith("VmHWM:")) hwm = line.filter { it.isDigit() }.toLong() * 1024
            }
            rss to hwm
        }.getOrDefault(-1L to -1L)
    }
}

/** What the assistant screen needs from either on-device runtime (LiteRT-LM or llama.cpp). */
interface OfflineChatEngine : com.khodroyar.app.ai.FunctionCallingProvider, java.io.Closeable {
    val modelPresent: Boolean
    fun cancelActive()
}
