package com.khodroyar.app.ai.gemma

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * The production [LocalLlmEngine]: Google's LiteRT-LM Android runtime executing the
 * pinned Gemma 4 E2B `.litertlm` package fully on-device. No network call of any kind
 * happens in this file — the only I/O is reading the verified model file from the app's
 * private storage.
 *
 * Memory behaviour (target device: Galaxy S23 FE, 8 GB RAM — the official minimum):
 *  - ONE engine instance for the process, created lazily at first use (cold load cost is
 *    paid once per assistant session, never per message);
 *  - a fresh lightweight Conversation per request seeded with a bounded tail of history —
 *    KV cache is released with the conversation, so long chats cannot grow RAM;
 *  - [release] unmaps the model the moment the user leaves the assistant or the Task
 *    watchdog asks (the ViewModel calls it); the engine re-loads on demand;
 *  - one inference at a time via [inferenceMutex] — two parallel prefills on an 8 GB
 *    device is the classic way to get the process killed;
 *  - image input is bounded by [ReportImagePrep] before it ever reaches this class.
 *
 * Timing and memory are logged to logcat (tag "GemmaEngine") on every call; that is the
 * instrumentation used for the on-device performance report.
 */
class LiteRtLmEngine(
    context: Context,
    private val modelPathProvider: () -> File?,
    private val deviceProfile: DeviceProfile,
) : LocalLlmEngine, Closeable {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var loadedWithVision = false
    /** Vision executor init has already failed on this device — don't retry on every call. */
    private var visionFailed = false
    private val inferenceMutex = Mutex()

    @Volatile var lastLoadMs: Long = -1; private set
    @Volatile var lastInferenceMs: Long = -1; private set

    override fun isLoaded(): Boolean = synchronized(lock) { engine?.isInitialized() == true }

    private class LoadFailure(val kind: LocalLlmEngine.EngineError, msg: String) : Exception(msg)

    /**
     * Idempotent + thread-safe. Cascade follows Google's LiteRT-LM compliance checklist:
     *  1) multimodal with vision on GPU (fastest when the driver cooperates),
     *  2) multimodal with vision strictly on CPU,
     *  3) CPU text-only engine (vision still unavailable, chat keeps working).
     * Stages are attempted once per process; after a known vision failure the app answers
     * image questions with an honest "not supported here" instead of re-crashing.
     */
    private fun ensureEngine(vision: Boolean) {
        synchronized(lock) {
            val path = modelPathProvider()?.absolutePath
                ?: throw LoadFailure(LocalLlmEngine.EngineError.NOT_INSTALLED, "model file absent")
            val current = engine
            if (current != null && current.isInitialized() && loadedPath == path) {
                if (!vision || loadedWithVision) return
                if (visionFailed) {
                    throw LoadFailure(LocalLlmEngine.EngineError.INIT_FAILED, "vision executor is known to fail on this device")
                }
                // Upgrade: text-only engine is loaded but an image was attached.
                releaseLocked()
            } else {
                releaseLocked()
            }
            if (!deviceProfile.hasHeadroomForInference()) {
                throw LoadFailure(LocalLlmEngine.EngineError.OUT_OF_MEMORY, "load-time memory check failed")
            }
            val started = SystemClock.elapsedRealtime()
            var e: Engine? = null
            var withVision = false
            if (vision && !visionFailed) {
                for (visionGpu in listOf(true, false)) {
                    try {
                        e = createEngine(path, vision = true, visionGpu = visionGpu).apply { initialize() }
                        withVision = true
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "engine init failed (vision, gpu=$visionGpu): ${t.message}")
                        runCatching { e?.close() }
                        e = null
                    }
                }
                if (!withVision) visionFailed = true
            }
            if (e == null) {
                try {
                    e = createEngine(path, vision = false, visionGpu = false).apply { initialize() }
                } catch (t: Throwable) {
                    throw LoadFailure(LocalLlmEngine.EngineError.INIT_FAILED, t.message ?: "initialize failed")
                }
            }
            engine = e
            loadedPath = path
            loadedWithVision = withVision
            lastLoadMs = SystemClock.elapsedRealtime() - started
            Log.i(
                TAG,
                "engine ready in ${lastLoadMs}ms | vision=$withVision | nativeHeap=${Debug.getNativeHeapAllocatedSize() / (1 shl 20)}MB" +
                    " | availRam=${(availRamMb())}MB",
            )
        }
    }

    private fun createEngine(path: String, vision: Boolean, visionGpu: Boolean): Engine = Engine(
        EngineConfig(
            modelPath = path,
            backend = Backend.CPU(),
            visionBackend = if (vision) (if (visionGpu) Backend.GPU() else Backend.CPU()) else null,
            // 8192 tokens of KV cache: system prompt (~1.2k) + bounded history + one image
            // (≤ ~2.5k patches at our 1280px cap) + 700 output tokens, with headroom.
            maxNumTokens = MAX_CONTEXT_TOKENS,
            maxNumImages = 1,
            cacheDir = File(appContext.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
        ),
    )

    override suspend fun complete(request: LocalLlmEngine.Request): LocalLlmEngine.Reply =
        withContext(Dispatchers.Default) {
            inferenceMutex.withLock {
                try {
                    ensureEngine(vision = request.imageBytes != null)
                    val e = engine
                        ?: return@withLock LocalLlmEngine.Reply.Failure(LocalLlmEngine.EngineError.NOT_INSTALLED, "")
                    if (request.imageBytes != null && !loadedWithVision) {
                        // The vision executor genuinely failed to init on this device —
                        // say so, never send an image payload to a text-only engine.
                        return@withLock LocalLlmEngine.Reply.Failure(
                            LocalLlmEngine.EngineError.FAILED,
                            "پردازش تصویر روی این دستگاه در دسترس نیست (ساخت ویژن بارگذاری نشد). متن همچنان کار می‌کند.",
                        )
                    }
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(request.systemPrompt),
                        initialMessages = request.history.map { turn ->
                            if (turn.role == LocalLlmEngine.Turn.USER) Message.user(turn.text) else Message.model(turn.text)
                        },
                        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.1),
                        maxOutputToken = request.maxOutputTokens,
                        thinkingConfig = if (request.disableThinking) ThinkingConfig(enableThinking = false) else null,
                    )
                    val contents = ArrayList<Content>(2)
                    // LiteRT-LM compliance checklist, multi-modal section: Content.Text goes
                    // BEFORE media content to match the model's expected content ordering.
                    contents.add(Content.Text(request.userText))
                    request.imageBytes?.let { contents.add(Content.ImageBytes(it)) }

                    val t0 = SystemClock.elapsedRealtime()
                    val nativeBefore = Debug.getNativeHeapAllocatedSize()
                    val msg = e.createConversation(config).use { conv ->
                        conv.sendMessage(Contents.of(contents))
                    }
                    lastInferenceMs = SystemClock.elapsedRealtime() - t0
                    val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                    val nativeAfter = Debug.getNativeHeapAllocatedSize()
                    Log.i(
                        TAG,
                        "inference ${lastInferenceMs}ms | vision=${request.imageBytes != null}" +
                            " | nativeHeap ${nativeBefore / (1 shl 20)}→${nativeAfter / (1 shl 20)}MB | availRam=${availRamMb()}MB",
                    )
                    if (text.isEmpty()) {
                        LocalLlmEngine.Reply.Failure(LocalLlmEngine.EngineError.FAILED, "empty model response")
                    } else {
                        LocalLlmEngine.Reply.Text(text)
                    }
                } catch (lf: LoadFailure) {
                    LocalLlmEngine.Reply.Failure(lf.kind, lf.message ?: "")
                } catch (oom: OutOfMemoryError) {
                    LocalLlmEngine.Reply.Failure(
                        LocalLlmEngine.EngineError.OUT_OF_MEMORY,
                        "حافظهٔ کافی برای ادامهٔ پردازش تصویر/متن در دسترس نبود.",
                    )
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    LocalLlmEngine.Reply.Failure(LocalLlmEngine.EngineError.FAILED, t.message ?: t.javaClass.simpleName)
                }
            }
        }

    /** Unmaps the model so Android can reclaim the RAM. Safe to call at any time. */
    fun release() {
        synchronized(lock) { releaseLocked() }
        Log.i(TAG, "engine released (model unmapped)")
    }

    private fun releaseLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    override fun close() = release()

    private fun availRamMb(): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1 shl 20)
    }

    companion object {
        private const val TAG = "GemmaEngine"
        const val MAX_CONTEXT_TOKENS = 8192
    }
}
