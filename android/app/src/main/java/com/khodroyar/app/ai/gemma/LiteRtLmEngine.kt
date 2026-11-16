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
    private val inferenceMutex = Mutex()

    @Volatile var lastLoadMs: Long = -1; private set
    @Volatile var lastInferenceMs: Long = -1; private set

    override fun isLoaded(): Boolean = synchronized(lock) { engine?.isInitialized() == true }

    private class LoadFailure(val kind: LocalLlmEngine.EngineError, msg: String) : Exception(msg)

    /** Idempotent + thread-safe. GPU vision with automatic CPU fallback (driver quirks). */
    private fun ensureEngine(vision: Boolean) {
        synchronized(lock) {
            val path = modelPathProvider()?.absolutePath
                ?: throw LoadFailure(LocalLlmEngine.EngineError.NOT_INSTALLED, "model file absent")
            val current = engine
            if (current != null && current.isInitialized() && loadedPath == path) return
            releaseLocked()
            if (!deviceProfile.hasHeadroomForInference()) {
                throw LoadFailure(LocalLlmEngine.EngineError.OUT_OF_MEMORY, "load-time memory check failed")
            }
            val started = SystemClock.elapsedRealtime()
            var e = createEngine(path, vision, visionGpu = true)
            try {
                e.initialize()
            } catch (first: Throwable) {
                if (vision) {
                    Log.w(TAG, "GPU vision init failed (${first.message}); retrying vision on CPU")
                    runCatching { e.close() }
                    e = createEngine(path, vision = true, visionGpu = false)
                    try {
                        e.initialize()
                    } catch (second: Throwable) {
                        throw LoadFailure(LocalLlmEngine.EngineError.INIT_FAILED, second.message ?: "initialize failed")
                    }
                } else {
                    throw LoadFailure(LocalLlmEngine.EngineError.INIT_FAILED, first.message ?: "initialize failed")
                }
            }
            engine = e
            loadedPath = path
            lastLoadMs = SystemClock.elapsedRealtime() - started
            Log.i(
                TAG,
                "engine ready in ${lastLoadMs}ms | nativeHeap=${Debug.getNativeHeapAllocatedSize() / (1 shl 20)}MB" +
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
                    request.imageBytes?.let { contents.add(Content.ImageBytes(it)) }
                    contents.add(Content.Text(request.userText))

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
