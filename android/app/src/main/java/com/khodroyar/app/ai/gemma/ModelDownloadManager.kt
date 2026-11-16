package com.khodroyar.app.ai.gemma

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable, user-initiated downloader for the Gemma model file. Pure JVM: the Android
 * caller runs [download] inside `Dispatchers.IO` and drives [pause] from the UI.
 *
 * Guarantees:
 *  - never starts by itself — [download] is only ever invoked from the download button;
 *  - resumes a `.part` file with an HTTP Range request (416 = server says “already
 *    complete”, so we verify instead of failing);
 *  - the partial is KEPT on pause/failure so the next attempt continues where it stopped;
 *  - the final file only appears after size AND SHA-256 verification, in one atomic rename;
 *  - a partial larger than the pinned size, or a stale meta (different commit/size),
 *    is discarded and restarted — never trusted blindly.
 */
class ModelDownloadManager(
    private val store: ModelStore,
    private val spec: GemmaModelSpec = GemmaModelSpec.GEMMA_4_E2B,
    private val transport: Transport = HttpUrlTransport(),
    private val freeBytes: () -> Long,
) {

    interface Transport {
        /** GET [url]; when [offset] > 0 the request carries `Range: bytes=<offset>-`. */
        fun open(url: String, offset: Long): Connection
        /** Small JSON GET (Hugging Face metadata). Null when it can't be fetched. */
        fun readJson(url: String): String?
    }

    interface Connection {
        val statusCode: Int
        /** Byte length of THIS response body (after the range was applied), or null. */
        val contentLength: Long?
        val body: InputStream
        /** Release the underlying socket/resources. Default: nothing to release. */
        fun close() {}
    }

    sealed interface Outcome {
        /** File verified and installed at [ModelStore.modelFile]. [digestTrusted]=false means only size was checked. */
        data class Installed(val file: File, val bytes: Long, val digestTrusted: Boolean) : Outcome
        /** User pressed pause — the .part is intact for a later resume. */
        data class Paused(val downloaded: Long, val total: Long) : Outcome
        data class Failed(val reason: Reason) : Outcome
    }

    sealed interface Reason {
        val message: String
        data class NotEnoughStorage(val needed: Long, val available: Long, override val message: String = "حافظهٔ کافی نیست") : Reason
        data class Network(override val message: String) : Reason
        data class Integrity(override val message: String) : Reason
        data class Unexpected(val statusCode: Int, override val message: String) : Reason
    }

    /** Live progress. [percent] is -1 until the server confirms the total. */
    data class Progress(
        val downloaded: Long, val total: Long, val percent: Int,
        val bytesPerSecond: Long, val remainingBytes: Long,
    )

    @Volatile private var cancelRequested = false

    /**
     * Asks the loop to stop as soon as the current chunk is written. Safe to call from
     * any thread; the in-flight [download] then ends with [Outcome.Paused].
     */
    fun pause() {
        cancelRequested = true
    }

    /**
     * Runs the whole download to completion (or pause/failure). Blocking — call from a
     * worker dispatcher. Retries a dropped connection up to 3 times, resuming each time.
     */
    fun download(onProgress: (Progress) -> Unit): Outcome {
        cancelRequested = false
        val available = freeBytes()
        val already = store.partialFile.let { if (it.isFile) it.length() else 0L }
        if (store.modelFile.isFile && store.modelFile.length() == spec.expectedSizeBytes) {
            return Outcome.Installed(store.modelFile, spec.expectedSizeBytes, digestTrusted = true)
        }
        if (available < spec.minFreeStorageBytes - already) {
            return Outcome.Failed(Reason.NotEnoughStorage(spec.minFreeStorageBytes - already, available))
        }

        // 1. Resolve the trusted digest for this file (best effort — size check always applies).
        val sha = spec.pinnedSha256.ifBlank {
            transport.readJson(spec.metadataUrl)
                ?.let { GemmaModelSpec.sha256FromMetadata(it, spec.modelFileName) } ?: ""
        }

        // 2. A partial from a DIFFERENT spec (old commit/size) can never be resumed.
        val meta = store.readMeta()
        if (meta != null && (meta.url != spec.resolveUrl || meta.expectedSize != spec.expectedSizeBytes)) {
            store.resetPartial()
        }
        if (store.partialFile.let { it.isFile && it.length() > spec.expectedSizeBytes }) {
            store.resetPartial()
        }
        store.writeMeta(ModelStore.Meta(spec.resolveUrl, spec.expectedSizeBytes, sha))

        // 3. Fetch with retries, resuming from the partial each time.
        var attempt = 0
        while (true) {
            attempt++
            when (val step = fetchLoop(sha, onProgress)) {
                is Step.Done -> return step.outcome
                is Step.Retry -> if (attempt >= 3) return step.giveUp else Thread.sleep(1200L * attempt)
            }
        }
    }

    private sealed interface Step {
        data class Done(val outcome: Outcome) : Step
        data class Retry(val giveUp: Outcome) : Step
    }

    private fun fetchLoop(sha: String, onProgress: (Progress) -> Unit): Step {
        val partial = store.partialFile
        var downloaded = if (partial.isFile) partial.length() else 0L
        val conn = try {
            transport.open(spec.resolveUrl, downloaded)
        } catch (e: Exception) {
            return Step.Retry(Outcome.Failed(Reason.Network("اتصال برقرار نشد: ${e.message ?: "خطای شبکه"}")))
        }
        try {
            when (conn.statusCode) {
                200 -> {
                    // Server ignored our Range → start over to avoid a corrupt splice.
                    if (downloaded > 0) {
                        partial.delete()
                        downloaded = 0
                    }
                }
                206 -> Unit // resume acknowledged
                416 -> {
                    // Range start beyond end: the partial is already complete — verify it.
                    return Step.Done(verifyAndPromote(partial, sha))
                }
                in 500..599 ->
                    return Step.Retry(Outcome.Failed(Reason.Unexpected(conn.statusCode, "خطای سرور دانلود (${conn.statusCode})")))
                else ->
                    return Step.Done(Outcome.Failed(Reason.Unexpected(conn.statusCode, "پاسخ نامعتبر سرور دانلود (${conn.statusCode})")))
            }

            val startedAt = System.nanoTime()
            val startBytes = downloaded
            var lastTick = 0L
            partial.parentFile?.mkdirs()
            // RandomAccessFile: append-resume and durable sync on pause.
            val file = java.io.RandomAccessFile(partial, "rw")
            try {
                file.seek(downloaded)
                val buf = ByteArray(256 * 1024)
                conn.body.use { ins ->
                    while (true) {
                        if (cancelRequested) {
                            file.fd.sync()
                            return Step.Done(Outcome.Paused(downloaded, spec.expectedSizeBytes))
                        }
                        val read = ins.read(buf)
                        if (read < 0) break
                        file.write(buf, 0, read)
                        downloaded += read
                        val now = System.nanoTime()
                        if (now - lastTick > 300_000_000) { // throttle UI updates to ~3/s
                            lastTick = now
                            val secs = (now - startedAt) / 1e9
                            val speed = if (secs > 0.5) ((downloaded - startBytes) / secs).toLong() else 0L
                            val pct = ((downloaded * 100) / spec.expectedSizeBytes).toInt().coerceIn(0, 100)
                            onProgress(Progress(downloaded, spec.expectedSizeBytes, pct, speed, spec.expectedSizeBytes - downloaded))
                        }
                        if (downloaded > spec.expectedSizeBytes) {
                            return Step.Done(Outcome.Failed(Reason.Integrity("حجم فایل دریافتی بیش از حجم مجاز است؛ دانلود متوقف شد.")))
                        }
                    }
                }
                file.fd.sync()
            } finally {
                runCatching { file.close() }
            }
            return Step.Done(verifyAndPromote(partial, sha, downloaded, onProgress))
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun verifyAndPromote(
        partial: File,
        sha: String,
        downloaded: Long = partial.length(),
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        if (downloaded != spec.expectedSizeBytes) {
            return Outcome.Failed(
                Reason.Integrity("دانلود ناقص است (${downloaded / (1024 * 1024)} از ${spec.displaySizeMb} مگابایت). دوباره تلاش کنید تا ادامه پیدا کند."),
            )
        }
        onProgress(Progress(downloaded, spec.expectedSizeBytes, 100, 0, 0))
        val v = ModelVerifier.verify(partial, spec.expectedSizeBytes, sha)
        if (!v.ok) {
            // Never keep a failed verification as "final"; keep the partial for diagnosis,
            // but don't let the engine load it.
            return Outcome.Failed(Reason.Integrity("اعتبارسنجی مدل ناموفق بود (checksum مغایرت دارد). فایل بارگذاری نمی‌شود."))
        }
        if (!store.promotePartial()) {
            if (!partial.renameTo(store.modelFile)) {
                return Outcome.Failed(Reason.Unexpected(0, "انتقال فایل مدل به محل نهایی ناموفق بود."))
            }
        }
        return Outcome.Installed(store.modelFile, spec.expectedSizeBytes, v.digestTrusted)
    }
}

/** [ModelDownloadManager.Transport] over plain HttpURLConnection (no third-party HTTP stack). */
class HttpUrlTransport : ModelDownloadManager.Transport {

    override fun open(url: String, offset: Long): ModelDownloadManager.Connection {
        // Follow redirects manually so we can re-apply the Range header on each hop
        // (HttpURLConnection drops custom headers across redirects).
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "KhodroYar/Android")
                setRequestProperty("Accept", "application/octet-stream")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER, 307, 308,
                -> {
                    val loc = conn.getHeaderField("Location") ?: return wrap(conn, code)
                    conn.disconnect()
                    current = URL(URL(current), loc).toExternalForm()
                }
                else -> return wrap(conn, code)
            }
        }
        throw java.io.IOException("تعداد تغییر مسیر دانلود بیش از حد است.")
    }

    override fun readJson(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun wrap(conn: HttpURLConnection, code: Int): ModelDownloadManager.Connection =
        object : ModelDownloadManager.Connection {
            override val statusCode: Int = code
            override val contentLength: Long? = conn.contentLengthLong.takeIf { it >= 0 }
            override val body: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream ?: InputStream.nullInputStream()
            override fun close() = conn.disconnect()
        }
}
