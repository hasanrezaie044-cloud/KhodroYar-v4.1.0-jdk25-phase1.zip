package com.khodroyar.app.ai.albert

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.khodroyar.app.ai.LocalIntentParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Real local ALBERT inference; no network, account, user-data training or model download.
 * A session exists only while this workspace is open and after the first typed request.
 * Decoder is a domain hint, not a source of facts, slots, approval or writes.
 */
class PersianAlbertEngine(context: Context) : PersianIntentModel, AutoCloseable {
    enum class Status { NOT_LOADED, READY, MEMORY_LIMITED, UNAVAILABLE }
    private val app = context.applicationContext
    private val lock = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: SentencePieceBridge? = null
    private var modelBuffer: ByteBuffer? = null
    private var manifest: JSONObject? = null
    @Volatile var status: Status = Status.NOT_LOADED
        private set

    override suspend fun predict(text: String): PersianIntentPrediction? = withContext(Dispatchers.Default) {
        if (text.length !in 2..600) return@withContext null
        val normalized = LocalIntentParser.normalize(text)
        lock.withLock {
            currentCoroutineContext().ensureActive()
            if (!load()) return@withLock null
            try {
                val pieces = tokenizer!!.encode(normalized)
                val meta = manifest!!
                val count = meta.getInt("maxTokens")
                // No silent truncation: a dropped negation/amount must never turn into a write draft.
                if (pieces.size > count - 2) return@withLock null
                val ids = LongArray(count)
                val mask = LongArray(count)
                ids[0] = 2
                for (i in pieces.indices) ids[i + 1] = pieces[i].toLong()
                ids[pieces.size + 1] = 3
                for (i in 0..(pieces.size + 1)) mask[i] = 1
                val labels = meta.getJSONArray("labels").let { a -> List(a.length()) { a.getString(it) } }
                val env = OrtEnvironment.getEnvironment()
                val start = SystemClock.elapsedRealtimeNanos()
                val result = OnnxTensor.createTensor(env, arrayOf(ids)).use { input ->
                    OnnxTensor.createTensor(env, arrayOf(mask)).use { attention ->
                        OnnxTensor.createTensor(env, arrayOf(LongArray(count))).use { type ->
                            session!!.run(mapOf("input_ids" to input, "attention_mask" to attention, "token_type_ids" to type)).use { output ->
                                @Suppress("UNCHECKED_CAST")
                                val logits = (output[0].value as Array<FloatArray>)[0]
                                PersianIntentScores.decode(labels, logits, meta.getDouble("threshold"), meta.getDouble("minimumMargin"))
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                result?.copy(inferenceMs = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - start))
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { release(Status.MEMORY_LIMITED); null }
            catch (_: LinkageError) { release(Status.UNAVAILABLE); null }
            catch (_: Exception) { release(Status.UNAVAILABLE); null }
        }
    }

    private fun load(): Boolean {
        if (session != null) return true
        if (status == Status.MEMORY_LIMITED || status == Status.UNAVAILABLE) return false
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        if (manager.isLowRamDevice || info.lowMemory || info.availMem < 384L * 1024 * 1024) {
            status = Status.MEMORY_LIMITED
            return false
        }
        try {
            val meta = app.assets.open("albert/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            check(meta.getString("revision") == "2315c0d4ddd6bd68f7703c5651978706bf8aff37" && meta.getInt("maxTokens") == 64)
            val assets = meta.getJSONObject("assets")
            // Android mmap of an UNCOMPRESSED APK asset: no extra model copy on internal storage.
            val mapped = app.assets.openFd("albert/intent-int8.onnx").use { fd ->
                check(fd.declaredLength in 1..44_999_999)
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            val expected = assets.getJSONObject("intent-int8.onnx")
            check(mapped.remaining().toLong() == expected.getLong("bytes") && digest(mapped) == expected.getString("sha256"))
            val sp = app.assets.open("albert/spiece.model").use { it.readBytes() }
            val spMeta = assets.getJSONObject("spiece.model")
            check(sp.size.toLong() == spMeta.getLong("bytes") && digest(ByteBuffer.wrap(sp)) == spMeta.getString("sha256"))
            tokenizer = SentencePieceBridge(sp)
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
                session = OrtEnvironment.getEnvironment().createSession(mapped, options)
            } finally { options.close() }
            modelBuffer = mapped // keep mapping alive for the lifetime of the ORT session
            manifest = meta
            status = Status.READY
            return true
        } catch (_: OutOfMemoryError) { release(Status.MEMORY_LIMITED) }
        catch (_: LinkageError) { release(Status.UNAVAILABLE) }
        catch (_: Exception) { release(Status.UNAVAILABLE) }
        return false
    }

    private fun digest(source: ByteBuffer): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(source.asReadOnlyBuffer())
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun release(next: Status) {
        runCatching { session?.close() }; session = null
        runCatching { tokenizer?.close() }; tokenizer = null
        modelBuffer = null; manifest = null; status = next
    }

    /** Never block the UI waiting for a native inference to finish. */
    suspend fun releaseMemory() = withContext(Dispatchers.Default) { lock.withLock { release(Status.NOT_LOADED) } }
    override fun close() {
        // ViewModel's scope is already cancelled in onCleared. This finite cleanup
        // waits off-main even if the native call was still finishing on that turn.
        CoroutineScope(Dispatchers.Default).launch { lock.withLock { release(Status.NOT_LOADED) } }
    }
}
